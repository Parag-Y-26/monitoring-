package com.edgepulse.monitor

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.edgepulse.core.cloud.*
import com.edgepulse.monitor.security.ProviderConnection
import com.edgepulse.monitor.monitoring.MonitoringState
import com.edgepulse.monitor.monitoring.DetectionOverlay
import com.edgepulse.core.vision.DetectionBuffer
import com.edgepulse.monitor.vision.ml.BenchmarkReport
import com.edgepulse.monitor.ui.*
import com.edgepulse.monitor.ui.auth.CloudSetupActions
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated UI fixtures; no credentials, providers, benchmark or monitoring are invoked. */
class DesignReviewInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private data class Review(val name: String, val state: SetupUiState = SetupUiState(storageReady = true),
        val width: Int = 360, val height: Int = 780, val dark: Boolean = true, val fontScale: Float = 1f)

    @Test fun bottomDestinationsStaySelectedAndStopRemainsAvailableAwayFromCamera() {
        var selected by mutableStateOf(EdgeDestination.MONITOR)
        var stopped = false
        compose.setContent { EdgePulseTheme(reducedMotion = true) {
            EdgeAppShell(selected, { selected = it }, sessionAway = selected != EdgeDestination.MONITOR,
                onReturn = { selected = EdgeDestination.MONITOR }, onStop = { stopped = true }) {
                androidx.compose.material3.Text("Content")
            }
        } }
        compose.onNodeWithText("Items").assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
        compose.onNodeWithText("Usage").assertDoesNotExist()
        compose.onAllNodes(isSelectable()).assertCountEquals(3)
        compose.onNodeWithText("Settings").performClick().assertIsSelected()
        compose.onNodeWithText("Stop monitoring").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(stopped) }
        compose.onNodeWithText("Return to session").performClick()
        compose.onNodeWithText("Monitor").assertIsSelected()
        compose.onNodeWithText("Return to session").assertDoesNotExist()
        compose.runOnIdle { selected = EdgeDestination.USAGE }
        compose.onNodeWithText("Settings").assertIsSelected()
    }

    @Test fun figmaMonitorRendersInBothThemesAndCompactTextKeepsActionReachable() {
        var review by mutableStateOf(Review("monitor-dark"))
        var paused by mutableStateOf(false)
        var live by mutableStateOf(false)
        val overlay = DetectionOverlay().apply {
            copyFrom(DetectionBuffer(1).apply { add(.25f,.2f,.75f,.8f,.95f,0) },480,640)
        }
        val setup = SetupUiState(storageReady = true, identityReady = true, folderName = "Local reports",
            connections = listOf(ProviderConnection(CloudProvider.GEMINI, "fixture/model", emptyList(), 1)),
            benchmark = BenchmarkReport("fixture", "fixture", "fixture", emptyList(), "fixture", 30.0, true, true, 5))
        compose.setContent {
            key(review.name) { CompositionLocalProvider(LocalDensity provides Density(2f, review.fontScale)) {
                EdgePulseTheme(darkTheme = review.dark, reducedMotion = true) {
                    Box(Modifier.requiredSize(review.width.dp, review.height.dp).testTag("design-root")) {
                        EdgeAppShell(if (review.name == "usage-empty") EdgeDestination.USAGE else EdgeDestination.MONITOR, {}, immersive = live && review.name != "usage-empty") {
                            if (review.name == "usage-empty") ApiUsageScreen(emptyList(), {}, embedded = true)
                            else MonitoringScreen(setup, MonitoringState(active = live, paused = paused, burst = live && !paused,
                                visibleItems = if (live && !paused) 1 else 0), {}, {}, {}, {}, overlay = overlay, labels = listOf("Fixture object"))
                        }
                    }
                }
            } }
        }
        compose.onNodeWithText("Start monitoring").assertIsDisplayed().assertIsEnabled()
        capture("monitor-dark")
        compose.runOnIdle { review = Review("monitor-light", dark = false) }
        capture("monitor-light")
        compose.runOnIdle { review = Review("monitor-compact", width = 320, height = 568, fontScale = 1.3f) }
        capture("monitor-compact-top")
        compose.onNodeWithTag("monitor-content").performScrollToNode(hasText("What the count means"))
        compose.onNodeWithText("Start monitoring").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        capture("monitor-compact")
        compose.runOnIdle { paused = true; live = true; review = Review("monitor-paused") }
        compose.onNodeWithContentDescription("Stop monitoring").assertIsDisplayed()
        compose.onNodeWithTag("live-monitor").assertIsDisplayed()
        compose.onNodeWithText("Rescan").assertIsNotEnabled()
        compose.onNodeWithText("More").performClick()
        compose.onNodeWithText("Burst duty 0.0% · 30 s freezes: 0").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        capture("monitor-paused")
        compose.runOnIdle { paused = false; review = Review("monitor-live") }
        compose.onNodeWithTag("live-monitor").assertWidthIsEqualTo(360.dp).assertHeightIsEqualTo(780.dp)
        compose.onNodeWithText("Rescan").assertIsEnabled().assertIsDisplayed()
        compose.onNodeWithText("Monitor").assertDoesNotExist()
        compose.onNodeWithContentDescription("Stop monitoring").assertIsDisplayed()
        capture("monitor-live")
        compose.runOnIdle { review = Review("monitor-live-compact", width = 320, height = 568, fontScale = 1.3f) }
        compose.onNodeWithTag("live-monitor").assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(568.dp)
        compose.onNodeWithContentDescription("Stop monitoring").assertIsDisplayed()
        compose.onNodeWithText("More").assertIsDisplayed()
        capture("monitor-live-compact")
        compose.runOnIdle { review = Review("monitor-live-landscape", width = 780, height = 360) }
        compose.onNodeWithTag("live-monitor").assertWidthIsEqualTo(780.dp).assertHeightIsEqualTo(360.dp)
        compose.onNodeWithContentDescription("Stop monitoring").assertIsDisplayed()
        capture("monitor-live-landscape")
        compose.runOnIdle { review = Review("usage-empty") }
        compose.onNodeWithText("No requests logged yet").assertIsDisplayed()
        capture("usage-empty")
    }

    @Test fun renderedStatesFitPhoneAndCompactLargeText() {
        var review by mutableStateOf(Review("dark-empty"))
        compose.setContent {
            key(review.name) {
                CompositionLocalProvider(LocalDensity provides Density(2f, review.fontScale)) {
                    EdgePulseTheme(darkTheme = review.dark, reducedMotion = true) {
                        Box(Modifier.requiredSize(review.width.dp, review.height.dp).testTag("design-root")) {
                            EdgePulseScreen(review.state, CloudSetupActions(), { _, _ -> }, {}, {}, {}, {}, {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("API key").assertIsDisplayed()
        compose.onNodeWithText("OpenRouter").assertIsDisplayed()
        capture("dark-empty")
        compose.runOnIdle { review = Review("light-empty", dark = false) }
        capture("light-empty")
        compose.runOnIdle { review = Review("compact-large-text", width = 320, height = 568, fontScale = 1.3f) }
        compose.onNodeWithText("OpenRouter").assertIsDisplayed()
        compose.onNodeWithText("Connect & load models").performScrollTo().assertIsDisplayed()
        capture("compact-large-text")

        compose.runOnIdle { review = Review("error", SetupUiState(storageReady = true, cloudError = true,
            cloudMessage = "This key was rejected. Paste a new key and reconnect.")) }
        compose.onNodeWithText("Connection needs attention").performScrollTo().assertIsDisplayed()
        capture("error")
        compose.runOnIdle { review = Review("loading", SetupUiState(storageReady = true, busy = "Connecting to Gemini…")) }
        compose.onNodeWithText("Cancel request").performScrollTo().assertIsDisplayed()
        capture("loading")

        val models = listOf(CloudModel("fixture/model", "Fixture returned model"))
        compose.runOnIdle { review = Review("partial", SetupUiState(storageReady = true, provider = CloudProvider.NVIDIA_NIM,
            models = models, selectedModel = "fixture/model", previewCatalog = true, verificationNeeded = true, pendingKeyAvailable = true)) }
        compose.onNodeWithText("Verify & save model").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Saved").assertDoesNotExist()
        capture("partial")
        compose.runOnIdle { review = Review("success", SetupUiState(storageReady = true, models = models, selectedModel = "fixture/model",
            connections = listOf(ProviderConnection(CloudProvider.GEMINI, "fixture/model", models, 1)))) }
        compose.onNodeWithText("Saved").assertIsDisplayed()
        compose.onNodeWithText("Change model").performScrollTo().assertIsDisplayed()
        capture("success")
        compose.runOnIdle { review = Review("permission", SetupUiState(storageReady = true, identityReady = true)) }
        compose.onNodeWithText("Reports").performClick()
        compose.onNodeWithText("Choose folder").assertIsEnabled().assertIsDisplayed()
        capture("permission")
    }

    @Test fun navigationHasLargeSelectedTargetsAndReducedMotionRetainsMeaning() {
        var motionEnabled = true
        var contrast = 0f
        compose.setContent {
            EdgePulseTheme(darkTheme = true, reducedMotion = true) {
                motionEnabled = LocalEdgeMotion.current.enabled
                val colors = MaterialTheme.colorScheme
                contrast = (colors.onSurfaceVariant.luminance() + 0.05f) / (colors.surface.luminance() + 0.05f)
                EdgePulseScreen(SetupUiState(storageReady = true), CloudSetupActions(), { _, _ -> }, {}, {}, {}, {}, {})
            }
        }
        compose.runOnIdle { assertFalse(motionEnabled); assertTrue("Secondary text needs 4.5:1 contrast", contrast >= 4.5f) }
        compose.onNodeWithText("Device").assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
        compose.onNodeWithText("Prepare this phone").assertIsDisplayed()
        compose.onNodeWithText("Reports").performClick().assertIsSelected()
        compose.onNodeWithText("Choose folder").assertIsNotEnabled()
        compose.onNodeWithText("Tools").performClick()
        compose.onNodeWithText("Settings & tools").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Reports").assertIsSelected()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        val directory = File(root, "design-review")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$name.png").outputStream().use {
            check(compose.onNodeWithTag("design-root").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
}
