package com.edgepulse.monitor

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.edgepulse.core.cloud.CloudProvider
import com.edgepulse.monitor.monitoring.MonitoringState
import com.edgepulse.monitor.security.ProviderConnection
import com.edgepulse.monitor.ui.*
import com.edgepulse.monitor.vision.ml.BenchmarkReport
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MonitoringHomeUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun incompleteSetupExplainsWhyStartIsUnavailable() {
        var started = false
        compose.setContent { EdgePulseTheme(reducedMotion = true) {
            MonitoringScreen(SetupUiState(storageReady = true), MonitoringState(), {}, { started = true }, {}, {})
        } }
        compose.onNodeWithText("Start monitoring").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Connect an API key once in Settings.").assertIsDisplayed()
        compose.runOnIdle { assertFalse(started) }
    }
    @Test fun readyHomeOffersStartAndAnActiveSessionOffersStopWithoutFabricatingCounts() {
        var monitor by mutableStateOf(MonitoringState())
        var starts = 0; var stops = 0
        val setup = SetupUiState(storageReady = true, identityReady = true, folderName = "Fixture local folder",
            connections = listOf(ProviderConnection(CloudProvider.GEMINI, "fixture/model", emptyList(), 1)),
            benchmark = BenchmarkReport("fixture", "fixture", "fixture", emptyList(), "fixture", 30.0, true, true, 5))
        compose.setContent { EdgePulseTheme(reducedMotion = true) {
            MonitoringScreen(setup, monitor, {}, { starts++; monitor = MonitoringState(active = true, paused = true) },
                { stops++; monitor = MonitoringState() }, {})
        } }
        compose.onNodeWithText("Start monitoring").assertIsEnabled().performClick()
        compose.onNodeWithText("Stop monitoring").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(1, stops) }
        compose.onNodeWithText("What the count means").performScrollTo().assertIsDisplayed()
    }
}
