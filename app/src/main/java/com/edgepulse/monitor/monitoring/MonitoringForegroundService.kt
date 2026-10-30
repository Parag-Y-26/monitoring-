package com.edgepulse.monitor.monitoring

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.edgepulse.core.cloud.MonitoringNetworkInterlock
import com.edgepulse.monitor.data.local.*
import com.edgepulse.monitor.security.*
import com.edgepulse.monitor.vision.ml.DelegateBenchmarkHarness
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@AndroidEntryPoint
class MonitoringForegroundService : LifecycleService() {
    @Inject lateinit var settings: SecureSettingsStore
    @Inject lateinit var signer: KeystoreAuditSigner
    @Inject lateinit var keys: TinkKeyManager
    @Inject lateinit var interlock: MonitoringNetworkInterlock
    @Inject lateinit var runtime: MonitoringRuntime
    @Inject lateinit var checkpoints: DailyCheckpointCoordinator
    @Inject lateinit var references: ReferenceImageStore
    private val operations = Mutex()
    private var database: EdgePulseDatabase? = null
    private var camera: StationaryCameraXController? = null
    private var thermal: ThermalMonitor? = null
    private var heartbeat: Job? = null
    private var sessionId: String? = null
    private var pauseElapsed = -1L
    private var pauseWall = 0L
    private var pauseReason = "THERMAL_SEVERE"
    private var stoppedNormally = false
    private var launched = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == STOP) {
            lifecycleScope.launch { operations.withLock { stopSession() } }
            return START_NOT_STICKY
        }
        if (launched) return START_NOT_STICKY
        launched = true
        try {
            ServiceCompat.startForeground(this, MonitoringNotifications.RUNNING_ID, MonitoringNotifications.ongoing(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } catch (_: Exception) { runtime.stopped("Camera permission or foreground-service access is missing."); stopSelf(); return START_NOT_STICKY }
        runtime.starting()
        lifecycleScope.launch { operations.withLock {
            try {
                database = EdgePulseDatabase.open(this@MonitoringForegroundService, settings)
                interlock.startMonitoring {
                    val saved = settings.dataStore.data.first()
                    check(saved.activeSessionId == null) { "Close the interrupted session first" }
                    check(saved.credentials.any { it.validated && it.key.isNotEmpty() }) { "Connect an AI key first" }
                    check(signer.isEnrolled() && saved.trustedAuditPublicKey == signer.publicKeySpkiBase64) { "Audit identity mismatch" }
                    check(DelegateBenchmarkHarness(this@MonitoringForegroundService, settings).readCachedReport()?.constructionEligible == true) { "Run the device check first" }
                    val id = UUID.randomUUID().toString()
                    val elapsed = SystemClock.elapsedRealtime(); val wall = System.currentTimeMillis()
                    val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1).toString()
                    database!!.monitoringDao().insertSession(MonitoringSessionEntity(id, is_session_active = true,
                        last_heartbeat_elapsed_ms = elapsed, last_heartbeat_wall_ms = wall, boot_id = boot,
                        pubkey_spki_b64 = signer.publicKeySpkiBase64, attestation_cert_pem = signer.certificateChainPem(),
                        attestation_nonce_mode = saved.attestationNonceMode, key_continuity_status = "ENROLLED",
                        started_elapsed_ms = elapsed, started_wall_ms = wall))
                    try {
                        settings.dataStore.updateData { it.copy(activeSessionId = id, lastHeartbeatElapsedMs = elapsed, lastHeartbeatWallMs = wall, bootId = boot) }
                    } catch (failure: Exception) {
                        withContext(NonCancellable) { database!!.monitoringDao().setActive(id, false, wall) }; throw failure
                    }
                    sessionId = id
                }
                WatchdogHeartbeatWorker.schedule(this@MonitoringForegroundService)
                runtime.started(checkNotNull(sessionId))
                thermal = ThermalMonitor(this@MonitoringForegroundService) { status ->
                    lifecycleScope.launch { operations.withLock {
                        try { handleThermal(status) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { runtime.paused(true); MonitoringNotifications.alert(this@MonitoringForegroundService, "Camera could not resume. Stop the session and review camera access.") }
                    } }
                }.also { it.start() }
                if (android.os.Build.VERSION.SDK_INT < 29 || getSystemService(PowerManager::class.java).currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE) bindCamera()
                heartbeat = lifecycleScope.launch {
                    while (isActive) {
                        delay(10_000)
                        interlock.withSessionState {
                            val saved = settings.dataStore.data.first()
                            if (saved.activeSessionId == sessionId) {
                                val elapsed = SystemClock.elapsedRealtime(); val wall = System.currentTimeMillis()
                                val id = checkNotNull(sessionId)
                                database!!.monitoringDao().heartbeat(id, elapsed, wall)
                                val metrics = runtime.state.value
                                database!!.monitoringDao().updateDutyMetrics(id, metrics.duty, metrics.freezes)
                                settings.dataStore.updateData { it.copy(lastHeartbeatElapsedMs = elapsed, lastHeartbeatWallMs = wall) }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                camera?.close(); camera = null
                runtime.stopped("Camera monitoring could not start. Review device readiness and camera access; close any interrupted session before retrying.")
                stopSelf()
            }
        } }
        return START_NOT_STICKY
    }

    private suspend fun bindCamera() {
        val saved = settings.dataStore.data.first()
        val report = checkNotNull(DelegateBenchmarkHarness(this, settings).readCachedReport())
        val target = saved.itemProfiles.singleOrNull { it.id == saved.selectedItemId }?.also { it.validate() }
        val image = target?.takeIf { it.classId == null && it.referenceFilename.isNotBlank() }?.let {
            withContext(Dispatchers.IO) { references.load(it.referenceFilename) }
        }
        val controller = StationaryCameraXController(this, runtime, checkNotNull(report.tinyWinner), report.burstFps, target, image)
        camera = controller
        controller.start(this) {
            lifecycleScope.launch { operations.withLock {
                if (runtime.state.value.active && !runtime.state.value.paused) {
                    camera?.close(); camera = null
                    pauseElapsed = SystemClock.elapsedRealtime(); pauseWall = System.currentTimeMillis(); pauseReason = "CAMERA_ERROR"
                    runtime.paused(true)
                    MonitoringNotifications.alert(this@MonitoringForegroundService, "Camera interrupted. Open Drishti and stop the session.")
                }
            } }
        }
    }

    private suspend fun handleThermal(status: Int) {
        runtime.thermalStatus = status
        if (!runtime.state.value.active) return
        if (status >= PowerManager.THERMAL_STATUS_SEVERE && pauseElapsed < 0) {
            pauseElapsed = SystemClock.elapsedRealtime(); pauseWall = System.currentTimeMillis()
            pauseReason = "THERMAL_SEVERE"
            camera?.close(); camera = null; runtime.paused(true)
            MonitoringNotifications.alert(this, "Phone too hot. Camera monitoring is paused until it cools.")
        } else if (status < PowerManager.THERMAL_STATUS_SEVERE && pauseElapsed >= 0 && pauseReason == "THERMAL_SEVERE") {
            interlock.withSessionState { closeThermalGap() }
            bindCamera(); runtime.paused(false)
        }
    }

    private suspend fun closeThermalGap() {
        if (pauseElapsed < 0) return
        val saved = settings.dataStore.data.first()
        audit(saved).recordGap(checkNotNull(sessionId), pauseReason, pauseElapsed, SystemClock.elapsedRealtime(), pauseWall, System.currentTimeMillis())
        pauseElapsed = -1
    }

    private fun audit(saved: SecureSettings) = MonitoringAuditRepository(checkNotNull(database), WebpCircularArchiver(this, checkNotNull(database), keys.streamingAead), signer, saved.trustedAuditPublicKey)

    private suspend fun stopSession() {
        try {
            heartbeat?.cancelAndJoin(); thermal?.close(); thermal = null
            camera?.close(); camera = null
            if (database == null) database = EdgePulseDatabase.open(this, settings)
            interlock.stopMonitoring {
                val saved = settings.dataStore.data.first()
                val id = saved.activeSessionId
                if (id != null) {
                    sessionId = id
                    closeThermalGap()
                    val metrics = runtime.state.value
                    database!!.monitoringDao().updateDutyMetrics(id, metrics.duty, metrics.freezes)
                    database!!.monitoringDao().setActive(id, false, System.currentTimeMillis())
                    settings.dataStore.updateData { it.copy(activeSessionId = null) }
                }
            }
            stoppedNormally = true
            runtime.stopped()
            checkpoints.exportNow()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { runtime.stopped("Session could not close safely. Reopen Drishti and try Stop again.") }
        finally { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
    }

    override fun onDestroy() {
        heartbeat?.cancel(); thermal?.close(); camera?.close(); database?.close()
        if (!stoppedNormally && runtime.state.value.error == null) runtime.stopped("Monitoring was interrupted. Close the saved session before restarting.")
        // An unexpected destruction retains the encrypted active flag for the watchdog.
        super.onDestroy()
    }
    companion object { const val STOP = "com.edgepulse.monitor.STOP_MONITORING" }
}
