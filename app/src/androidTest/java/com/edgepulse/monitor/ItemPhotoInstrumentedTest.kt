package com.edgepulse.monitor

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import com.edgepulse.monitor.ui.ItemSetupScreen
import com.edgepulse.monitor.ui.EdgePulseTheme
import com.edgepulse.core.cloud.*
import com.edgepulse.monitor.data.remote.CloudAnalysisService
import com.edgepulse.monitor.security.*
import com.edgepulse.monitor.ui.ItemViewModel
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule

class ItemPhotoInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private class Scratch(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(root,"private").apply { mkdirs() }
    }
    @Test fun offlinePhotoSaveDoesNotUploadAndDifferentPhotoConsentIsRejected() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val root = File(base.cacheDir,"item-photo-${UUID.randomUUID()}").apply { mkdirs() }
        val context = Scratch(base,root)
        val alias = "edgepulse.test.photo.${UUID.randomUUID()}"
        val settings = SecureSettingsStore(context,TinkKeyManager(context,alias))
        val interlock = MonitoringNetworkInterlock()
        val usage = ApiUsageRepository(settings)
        val cloud = CloudAnalysisService(settings,interlock,usage)
        val store = ViewModelStore()
        lateinit var model: ItemViewModel
        try {
            instrumentation.runOnMainSync {
                model = ItemViewModel(context,settings,ReferenceImageStore(context,TinkKeyManager(context,alias)),ApiKeyRepository(settings),cloud,interlock)
                store.put("fixture",model)
                model.acceptCameraPhoto(Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) })
                model.approvePhoto(Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.BLUE)
                    for(y in 0..31) for(x in 0..15) setPixel(x,y,Color.WHITE)
                })
            }
            withTimeout(10_000) { model.state.first { it.approved != null && !it.busy } }
            assertEquals("Item 1", model.state.value.name)
            compose.setContent {
                val state by model.state.collectAsState()
                EdgePulseTheme(reducedMotion = true) { Box(Modifier.fillMaxSize().testTag("item-review")) {
                    ItemSetupScreen(state,model,emptyList(),{},{},{},{})
                } }
            }
            compose.onNodeWithTag("item-setup-list").performScrollToNode(hasText("Save item for offline monitoring"))
            compose.onNodeWithText("Save item for offline monitoring").assertIsDisplayed().assertIsEnabled()
            val outputRoot = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
                ?: base.getExternalFilesDir(null)
            val screenshot = File(outputRoot,"item-review.png")
            screenshot.parentFile?.mkdirs()
            screenshot.outputStream().use { compose.onNodeWithTag("item-review").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
            instrumentation.runOnMainSync { model.name("Reference fixture"); model.savePhotoTarget() }
            val saved = withTimeout(10_000) { model.state.first { it.profiles.size == 1 && !it.busy } }
            assertEquals("Reference fixture",saved.profiles.single().name)
            assertTrue(saved.profiles.single().referenceFilename.endsWith(".webp.enc"))
            assertTrue(saved.usage.isEmpty())
            val setup = ProviderDefaults.setup(CloudProvider.GEMINI,"models/fixture")
            val consent = OneShotUploadConsent.fromUserTap(CloudAnalysisService.photoConsentContext(setup,byteArrayOf(1)),SystemClock.elapsedRealtime())
            var rejected = false
            try { cloud.describeItem(setup,"fixture-key".toByteArray(),byteArrayOf(2),consent) }
            catch (_: SecurityException) { rejected = true }
            assertTrue(rejected)
            assertEquals("[]",settings.dataStore.data.first().apiUsageJson)
            interlock.startMonitoring { settings.dataStore.updateData { it.copy(activeSessionId="fixture-active") } }
            instrumentation.runOnMainSync { model.selectClass(39,"Bottle") }
            withTimeout(10_000) { model.state.first { !it.busy && it.message != null } }
            assertEquals(1,settings.dataStore.data.first().itemProfiles.size)
            interlock.stopMonitoring { settings.dataStore.updateData { it.copy(activeSessionId=null) } }
            instrumentation.runOnMainSync { model.selectClass(39,"Bottle") }
            val category = withTimeout(10_000) { model.state.first { !it.busy && it.profiles.size == 2 } }
                .profiles.single { it.classId == 39 }
            instrumentation.runOnMainSync { model.setDetectionThreshold(category.id, .20f) }
            withTimeout(10_000) { model.state.first { !it.busy && it.profiles.single { p -> p.id == category.id }.detectionThreshold == .20f } }
            assertEquals(.20f, settings.dataStore.data.first().itemProfiles.single { it.id == category.id }.detectionThreshold, 0f)
            interlock.startMonitoring { settings.dataStore.updateData { it.copy(activeSessionId="fixture-active") } }
            instrumentation.runOnMainSync { model.setDetectionThreshold(category.id, .30f) }
            withTimeout(10_000) { model.state.first { !it.busy && it.message != null } }
            assertEquals(.20f, settings.dataStore.data.first().itemProfiles.single { it.id == category.id }.detectionThreshold, 0f)
            interlock.stopMonitoring { settings.dataStore.updateData { it.copy(activeSessionId=null) } }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            settings.close()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile); root.deleteRecursively()
        }
    }
}
