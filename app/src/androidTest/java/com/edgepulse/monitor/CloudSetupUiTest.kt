package com.edgepulse.monitor

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.edgepulse.core.cloud.*
import com.edgepulse.monitor.security.ProviderConnection
import com.edgepulse.monitor.ui.SetupUiState
import com.edgepulse.monitor.ui.auth.ApiKeyGateScreen
import com.edgepulse.monitor.ui.auth.CloudSetupActions
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CloudSetupUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun keyPasteDetectsProviderAndConnectUsesOnlyAnExplicitTap() {
        var state by mutableStateOf(SetupUiState(storageReady = true))
        var connections = 0
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding()) { ApiKeyGateScreen(state, CloudSetupActions(
            chooseProvider = { state = state.copy(provider = it) },
            connect = { key, consent ->
                consent.consume(ProviderDefaults.discoveryContext(state.provider), android.os.SystemClock.elapsedRealtime())
                assertEquals("nvapi-fixture", key.toString(Charsets.UTF_8))
                key.fill(0); connections++
            },
        )) } } }
        compose.onNodeWithText("API key").performTextInput("nvapi-fixture")
        compose.runOnIdle { assertEquals(CloudProvider.NVIDIA_NIM, state.provider); assertEquals(0, connections) }
        compose.onNodeWithText("Full HTTPS validation endpoint").assertDoesNotExist()
        compose.onNodeWithText("NVIDIA model ID you have access to").assertDoesNotExist()
        compose.onNodeWithText("Connect & load models").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, connections) }
    }

    @Test fun returnedModelsAreSearchableAndSelectionUsesTheProviderId() {
        val models = listOf(CloudModel("api/alpha", "Alpha text", false), CloudModel("api/beta", "Beta vision", true))
        val state = SetupUiState(storageReady = true, provider = CloudProvider.OPENROUTER,
            models = models, selectedModel = "api/alpha", connections = listOf(ProviderConnection(CloudProvider.OPENROUTER, "api/alpha", models, 1)))
        var selected: String? = null
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ApiKeyGateScreen(state, CloudSetupActions(chooseModel = { selected = it })) } } }
        compose.onNodeWithText("Change model").performClick()
        compose.onNodeWithText("Search models").performTextInput("Beta")
        compose.onNodeWithText("Beta vision").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("api/beta", selected) }
    }

    @Test fun publicNvidiaCatalogStillNeedsModelVerification() {
        val models = listOf(CloudModel("api/returned", "Returned model"))
        val state = SetupUiState(storageReady = true, provider = CloudProvider.NVIDIA_NIM, models = models,
            selectedModel = "api/returned", previewCatalog = true, verificationNeeded = true, pendingKeyAvailable = true)
        compose.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ApiKeyGateScreen(state, CloudSetupActions()) } } }
        compose.onNodeWithText("Verify & save model").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connected").assertDoesNotExist()
    }
}
