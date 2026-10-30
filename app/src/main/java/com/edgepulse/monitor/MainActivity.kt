package com.edgepulse.monitor

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.edgepulse.monitor.data.local.EdgePulseDatabase
import com.edgepulse.monitor.security.KeystoreAuditSigner
import com.edgepulse.monitor.security.SecureSettingsStore
import com.edgepulse.monitor.security.ApiKeyRepository
import com.edgepulse.monitor.security.LocalSafFolder
import com.edgepulse.monitor.security.DailyCheckpointExporter
import com.edgepulse.monitor.security.DailyCheckpointCoordinator
import com.edgepulse.monitor.security.DailyCheckpointWorker
import com.edgepulse.core.audit.AuditCanonical
import com.edgepulse.monitor.data.remote.CloudAnalysisService
import com.edgepulse.monitor.ui.auth.ApiKeyGateScreen
import com.edgepulse.monitor.vision.ml.DelegateBenchmarkHarness
import com.edgepulse.monitor.vision.ml.BenchmarkReport
import dagger.hilt.android.AndroidEntryPoint
import java.util.Base64
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SecureSettingsStore
    @Inject lateinit var signer: KeystoreAuditSigner
    @Inject lateinit var apiKeys: ApiKeyRepository
    @Inject lateinit var cloud: CloudAnalysisService
    @Inject lateinit var checkpoints: DailyCheckpointCoordinator
    private var database: EdgePulseDatabase? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF63DEB2))) {
                var storage by remember { mutableStateOf("Opening encrypted storage…") }
                var identity by remember { mutableStateOf("Not enrolled") }
                var unlocked by remember { mutableStateOf(false) }
                var challenge by remember { mutableStateOf("") }
                var benchmark by remember { mutableStateOf("Run the benchmark to view current results") }
                var benchmarkReport by remember { mutableStateOf<BenchmarkReport?>(null) }
                var busy by remember { mutableStateOf(false) }
                var exportStatus by remember { mutableStateOf("") }
                val scope = rememberCoroutineScope()
                val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                    if (uri != null) scope.launch {
                        try {
                            LocalSafFolder.requireLocalTree(uri)
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            val current = settings.dataStore.updateData { it.copy(checkpointTreeUri = uri.toString()) }
                            DailyCheckpointExporter(contentResolver).exportIdentity(uri, signer, current)
                            DailyCheckpointWorker.schedule(applicationContext)
                            exportStatus = "Public identity exported. Enroll its fingerprint with your verifier."
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: IllegalArgumentException) { exportStatus = "Choose a local device, SD-card or USB folder." }
                        catch (_: Exception) { exportStatus = "Export failed. Check local folder access and audit identity." }
                    }
                }
                LaunchedEffect(Unit) {
                    try {
                        database = EdgePulseDatabase.open(applicationContext, settings)
                        storage = "Encrypted storage opened"
                        unlocked = settings.isUnlockedOffline()
                        if (signer.isEnrolled()) {
                            val current = settings.dataStore.data.first()
                            val anchor = current.trustedAuditPublicKey
                            identity = if (anchor == signer.publicKeySpkiBase64) "Local identity enrolled" else "PUBKEY_ROTATED_TAMPER_ALERT · trust review required"
                            if (current.checkpointTreeUri.isNotEmpty()) DailyCheckpointWorker.schedule(applicationContext)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { storage = "Storage could not open. Preserve app data and review the device key." }
                }
                Surface(modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()
                    .statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("DRISHTI", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("EdgePulse AI", style = MaterialTheme.typography.headlineLarge)
                    Text("Phase 1 · Secure foundation", style = MaterialTheme.typography.titleMedium)
                    Text("Construction inventory monitoring", style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                    Text(storage)
                    Text("Audit: $identity")
                    Text(if (unlocked) "API key previously validated · offline unlock available" else "Setup locked · online key validation is pending")
                    Text("Benchmark: $benchmark")
                    benchmarkReport?.measurements?.forEach { measurement ->
                        Text("${measurement.model} · ${measurement.delegate}: ${measurement.p95Ms?.let { "%.2f ms".format(it) } ?: measurement.status}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Construction stays blocked until a real YOLOX-Tiny benchmark passes the 40 ms gate.")
                    if (!signer.isEnrolled()) {
                        OutlinedTextField(challenge, { challenge = it }, label = { Text("Verifier challenge, base64 (optional)") },
                            supportingText = { Text("Obtain this from your supervisor. Empty means no attestation claim.") }, modifier = Modifier.fillMaxWidth())
                        Button(enabled = !busy && database != null, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val nonce = challenge.takeIf { it.isNotBlank() }?.let { Base64.getDecoder().decode(it.trim()) }
                                    val nonceHash = nonce?.let(AuditCanonical::hash) ?: byteArrayOf()
                                    try { withContext(Dispatchers.IO) { signer.enroll(nonce) } }
                                    finally { nonce?.fill(0) }
                                    settings.dataStore.updateData { it.copy(trustedAuditPublicKey = signer.publicKeySpkiBase64,
                                        attestationNonceMode = if (nonceHash.isEmpty()) "NONE" else "VERIFIER_SUPPLIED", verifierChallengeSha256 = nonceHash) }
                                    identity = "Local identity enrolled · external enrollment pending"
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { identity = "Enrollment failed. Check the verifier nonce and device Keystore support." }
                                finally { busy = false }
                            }
                        }) { Text("Create local audit identity") }
                    }
                    OutlinedButton(enabled = !busy && database != null, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val result = DelegateBenchmarkHarness(applicationContext, settings).run(force = true)
                                benchmarkReport = result
                                benchmark = result.tinyP95Ms?.let { "YOLOX-Tiny p95: %.2f ms · %s".format(it, result.tinyWinner) }
                                    ?: "No usable delegate. Construction blocked."
                                if (result.environment == "EMULATOR") benchmark += " · emulator only, Construction blocked"
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { benchmark = "Required model assets are unavailable or invalid. Construction blocked." }
                            finally { busy = false }
                        }
                    }) { Text("Run local benchmark") }
                    if (signer.isEnrolled()) {
                        OutlinedButton(enabled = !busy && database != null, onClick = { folderPicker.launch(null) }) { Text("Export public audit identity") }
                        OutlinedButton(enabled = !busy && database != null, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    exportStatus = when (checkpoints.exportNow()) {
                                        "EXPORTED" -> "Verified audit checkpoint exported to your local folder."
                                        "NO_HISTORY" -> "No signed events yet. A checkpoint will be available after monitoring."
                                        "FOLDER_REQUIRED" -> "Export your public identity and choose a local folder first."
                                        "FOLDER_ACCESS_LOST" -> "Folder access was lost. Choose the local folder again."
                                        else -> "Checkpoint was not exported. Check folder access and audit integrity."
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { exportStatus = "Checkpoint export could not open encrypted storage." }
                                finally { busy = false }
                            }
                        }) { Text("Export audit checkpoint") }
                        OutlinedButton(enabled = !busy && database != null, onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val current = settings.dataStore.data.first()
                                    if (current.checkpointTreeUri.isEmpty()) exportStatus = "Choose a local folder with Export public audit identity first."
                                    else if (current.benchmarkCache.isEmpty()) exportStatus = "Run the local benchmark first."
                                    else {
                                        val report = Json.decodeFromString<BenchmarkReport>(current.benchmarkCache)
                                        val manifestHash = withContext(Dispatchers.IO) {
                                            assets.open("models/manifest.json").use { AuditCanonical.hex(AuditCanonical.hash(it.readBytes())) }
                                        }
                                        if (report.manifestSha256 != manifestHash || report.measurements.any { it.model != "YOLOX_TINY" }) {
                                            exportStatus = "Model bundle changed. Run the local benchmark again."
                                        } else {
                                            DailyCheckpointExporter(contentResolver).exportBenchmark(Uri.parse(current.checkpointTreeUri), report)
                                            exportStatus = "Benchmark report exported. Hardware acceleration still needs device profiling."
                                        }
                                    }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { exportStatus = "Benchmark export failed. Check local folder access." }
                                finally { busy = false }
                            }
                        }) { Text("Export benchmark report") }
                        if (exportStatus.isNotEmpty()) Text(exportStatus, style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                    if (database != null) ApiKeyGateScreen(apiKeys, cloud, onUnlocked = { scope.launch { unlocked = apiKeys.isUnlockedOffline() } })
                    HorizontalDivider()
                    Text("No monitoring session is running. No video is recorded. Provider setup requires your explicit confirmation.", style = MaterialTheme.typography.bodySmall)
                    Text("Field accuracy, battery use and hardware latency remain HUMAN-GATED.", style = MaterialTheme.typography.bodySmall)
                }
                }
            }
        }
    }

    override fun onDestroy() {
        database?.close()
        super.onDestroy()
    }
}
