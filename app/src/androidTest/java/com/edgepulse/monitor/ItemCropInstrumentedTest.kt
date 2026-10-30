package com.edgepulse.monitor

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.edgepulse.core.cloud.MonitoringNetworkInterlock
import com.edgepulse.monitor.data.remote.CloudAnalysisService
import com.edgepulse.monitor.security.*
import com.edgepulse.monitor.ui.*
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real photo/crop/save flow with isolated storage, a portrait image and compact large-text UI. */
class ItemCropInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private class Scratch(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = File(root,"private").apply { mkdirs() }
    }
    private suspend fun withEditor(block: suspend (ItemViewModel) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val root = File(base.cacheDir,"crop-test-${UUID.randomUUID()}").apply { mkdirs() }
        val alias = "edgepulse.test.crop.${UUID.randomUUID()}"
        val context = Scratch(base,root)
        val keys = TinkKeyManager(context,alias)
        val settings = SecureSettingsStore(context,keys)
        val interlock = MonitoringNetworkInterlock()
        val store = ViewModelStore()
        lateinit var model: ItemViewModel
        try {
            instrumentation.runOnMainSync {
                model = ItemViewModel(context,settings,ReferenceImageStore(context,keys),ApiKeyRepository(settings),
                    CloudAnalysisService(settings,interlock,ApiUsageRepository(settings)),interlock)
                store.put("crop",model)
                model.acceptCameraPhoto(Bitmap.createBitmap(320,640,Bitmap.Config.ARGB_8888).apply {
                    for(y in 0 until height) for(x in 0 until width) setPixel(x,y,
                        if ((x/40+y/80)%2 == 0) Color.BLUE else Color.WHITE)
                })
            }
            withTimeout(10_000) { model.state.first { it.photo != null && !it.busy } }
            compose.setContent {
                val state by model.state.collectAsState()
                CompositionLocalProvider(LocalDensity provides Density(2f,1.3f)) {
                    EdgePulseTheme(reducedMotion = true) {
                        Box(Modifier.requiredSize(320.dp,568.dp).testTag("crop-root")) {
                            EdgeAppShell(EdgeDestination.ITEMS,{}) {
                                ItemSetupScreen(state,model,emptyList(),{},{},{},{},embedded = true)
                            }
                        }
                    }
                }
            }
            block(model)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            settings.close()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            check(root.canonicalFile.parentFile == base.cacheDir.canonicalFile); root.deleteRecursively()
        }
    }
    private fun capture(name: String) {
        val arguments = InstrumentationRegistry.getArguments()
        val root = arguments.getString("additionalTestOutputDir")?.let(::File)
            ?: InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        val file = File(root,"crop-regression/$name.png")
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        file.outputStream().use { check(compose.onNodeWithTag("crop-root").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)) }
    }
    @Test fun portraitEditorScrollsAppliesCropAndSavesWithoutUpload() = runBlocking {
        withEditor { model ->
            compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
            compose.onNodeWithText("Use this crop").performScrollTo().assertIsDisplayed().assertIsEnabled()
            capture("apply-reachable")
            compose.onNodeWithText("Use this crop").performClick()
            withTimeout(10_000) { model.state.first { it.approved != null && !it.busy } }
            assertTrue(model.state.value.approved!!.width < model.state.value.photo!!.width)
            compose.onNodeWithTag("item-setup-list").performScrollToNode(hasText("Save item for offline monitoring"))
            capture("offline-save-reachable")
            compose.onNodeWithText("Save item for offline monitoring").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("Save item for offline monitoring").performClick()
            val saved = withTimeout(10_000) { model.state.first { it.profiles.size == 1 && !it.busy } }
            assertEquals("Item 1",saved.profiles.single().name)
            assertTrue(saved.usage.isEmpty())
        }
    }
    @Test fun imageCenterScrollsPageWhileCornerDragChangesCrop() = runBlocking {
        withEditor { model ->
            compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
            val photo = compose.onNodeWithTag("photo-crop-canvas")
            photo.performScrollTo().assertIsDisplayed()
            val list = compose.onNodeWithTag("item-setup-list")
            fun scrollPosition() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            val before = scrollPosition()
            photo.performTouchInput { swipe(Offset(width*.5f,height*.35f),Offset(width*.5f,height*.8f)) }
            compose.waitForIdle()
            assertTrue("Swiping the image center must scroll the page",scrollPosition() < before)
            photo.performScrollTo()
            photo.performTouchInput { swipe(Offset(width*.1f,height*.1f),Offset(width*.25f,height*.25f),500) }
            compose.onNodeWithText("Use this crop").performScrollTo().performClick()
            val approved = withTimeout(10_000) { model.state.first { it.approved != null && !it.busy } }.approved!!
            assertTrue("Dragging top-left corner must reduce crop width",approved.width < 240)
            assertTrue("Dragging top-left corner must reduce crop height",approved.height < 480)
        }
    }
    @Test fun maskStrokeStaysOnPhotoAndSurvivesApplyingCrop() = runBlocking {
        withEditor { model ->
            compose.onNodeWithText("Mask",substring = false).performScrollTo().performClick()
            val photo = compose.onNodeWithTag("photo-crop-canvas")
            photo.performScrollTo()
            val list = compose.onNodeWithTag("item-setup-list")
            fun position() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            val before = position()
            photo.performTouchInput { swipe(Offset(width*.5f,height*.3f),Offset(width*.5f,height*.7f),500) }
            compose.waitForIdle()
            assertEquals("Masking must not scroll the page",before,position(),.01f)
            capture("mask-stroke")
            compose.onNodeWithText("Use this crop").performScrollTo().performClick()
            val approved = withTimeout(10_000) { model.state.first { it.approved != null && !it.busy } }.approved!!
            assertEquals("Mask must be burned into the approved image",Color.BLACK,approved.getPixel(approved.width/2,approved.height/2))
            assertTrue(model.state.value.usage.isEmpty())
        }
    }
}
