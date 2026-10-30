package com.edgepulse.monitor.monitoring

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.work.*
import com.edgepulse.core.camera.HeartbeatPolicy
import com.edgepulse.core.cloud.MonitoringNetworkInterlock
import com.edgepulse.monitor.data.local.*
import com.edgepulse.monitor.security.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Fifteen-minute periodic work; Doze/OEM detection may take 15–30+ minutes. */
class WatchdogHeartbeatWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    @EntryPoint @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun settings(): SecureSettingsStore
        fun keys(): TinkKeyManager
        fun auditSigner(): KeystoreAuditSigner
        fun networkInterlock(): MonitoringNetworkInterlock
    }
    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        return try {
            dependencies.networkInterlock().withSessionState {
                val saved = dependencies.settings().dataStore.data.first()
                val boot = Settings.Global.getInt(applicationContext.contentResolver, Settings.Global.BOOT_COUNT, -1).toString()
                val now = SystemClock.elapsedRealtime()
                if (!HeartbeatPolicy.isStale(saved.activeSessionId != null, saved.lastHeartbeatElapsedMs, now, saved.bootId == boot)) return@withSessionState Result.success()
                val db = EdgePulseDatabase.open(applicationContext, dependencies.settings())
                try {
                    val id = checkNotNull(saved.activeSessionId)
                    val session = db.monitoringDao().session(id)
                    if (session?.is_session_active != true || db.monitoringDao().hasProcessGap(id)) return@withSessionState Result.success()
                    if (saved.bootId == boot) {
                        MonitoringAuditRepository(db, WebpCircularArchiver(applicationContext, db, dependencies.keys().streamingAead),
                            dependencies.auditSigner(), saved.trustedAuditPublicKey)
                            .recordGap(id, "OS_KILL_DETECTED", saved.lastHeartbeatElapsedMs, now, saved.lastHeartbeatWallMs, System.currentTimeMillis())
                    }
                    // Never fabricate a cross-reboot elapsed interval. User closes the retained session.
                    MonitoringNotifications.alert(applicationContext, "Camera heartbeat stopped. Open EdgePulse and close the interrupted session.")
                    Result.success()
                } finally { db.close() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
    }
    companion object {
        const val WORK_NAME = "edgepulse.monitoring.heartbeat"
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatchdogHeartbeatWorker>(15, TimeUnit.MINUTES).setInitialDelay(15, TimeUnit.MINUTES).build())
        }
    }
}
