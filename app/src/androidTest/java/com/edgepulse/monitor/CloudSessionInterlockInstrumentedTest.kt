package com.edgepulse.monitor

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.edgepulse.core.cloud.*
import com.edgepulse.monitor.data.remote.CloudAnalysisService
import com.edgepulse.monitor.security.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real encrypted settings and real cloud boundary; every request must fail before HTTP creation. */
class CloudSessionInterlockInstrumentedTest {
    private class Scratch(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
    }

    @Test fun activeAndRestartedSessionsRejectEveryCloudEntryPointWithoutUsage() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "cloud-interlock-${UUID.randomUUID()}").apply { mkdirs() }
        val alias = "edgepulse.test.interlock.${UUID.randomUUID()}"
        val context = Scratch(base, root)
        val settings = SecureSettingsStore(context, TinkKeyManager(context, alias))
        val key = "fixture-only-no-provider-access".toByteArray()
        val image = byteArrayOf(1, 2, 3)
        try {
            val lock = MonitoringNetworkInterlock()
            lock.startMonitoring { settings.dataStore.updateData { it.copy(activeSessionId = "test-session") } }
            suspend fun rejectAll(interlock: MonitoringNetworkInterlock) {
                val cloud = CloudAnalysisService(settings, interlock, ApiUsageRepository(settings))
                val setup = ProviderDefaults.setup(CloudProvider.GEMINI, "models/fixture")
                fun consent(value: String) = OneShotUploadConsent.fromUserTap(value, SystemClock.elapsedRealtime())
                suspend fun rejected(block: suspend () -> Unit) {
                    try { block(); fail("Monitoring must block cloud work") }
                    catch (_: MonitoringActiveException) { /* expected */ }
                }
                rejected { cloud.fetchModels(setup.provider, key, consent(ProviderDefaults.discoveryContext(setup.provider))) }
                rejected { cloud.validateKey(setup, key, consent(setup.consentContext())) }
                rejected { cloud.describeItem(setup, key, image, consent(CloudAnalysisService.photoConsentContext(setup, image))) }
                assertEquals("[]", settings.dataStore.data.first().apiUsageJson)
            }
            rejectAll(lock)
            // A new in-memory lock simulates process recreation; persisted active state must still block HTTP.
            rejectAll(MonitoringNetworkInterlock())
            lock.stopMonitoring { settings.dataStore.updateData { it.copy(activeSessionId = null) } }
            val cloud = CloudAnalysisService(settings, lock, ApiUsageRepository(settings))
            val wrong = OneShotUploadConsent.fromUserTap("different-action", SystemClock.elapsedRealtime())
            try { cloud.fetchModels(CloudProvider.GEMINI, key, wrong); fail("Unmatched consent must block HTTP") }
            catch (_: SecurityException) { /* expected */ }
            assertEquals("[]", settings.dataStore.data.first().apiUsageJson)
        } finally {
            key.fill(0); image.fill(0); settings.close()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile)
            root.deleteRecursively()
        }
    }
}
