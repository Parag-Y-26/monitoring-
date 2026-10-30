package com.edgepulse.monitor

import android.net.TrafficStats
import android.os.Build
import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Whole-app navigation and persisted target selection on an isolated emulator, never user data. */
class MainActivityJourneyInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun categorySelectionAndKeyEntryNavigateThroughRealAppWithoutConnecting() {
        assumeTrue("Journey uses only a disposable emulator profile", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val tx = TrafficStats.getUidTxBytes(Process.myUid())
        val rx = TrafficStats.getUidRxBytes(Process.myUid())
        assertTrue(tx >= 0 && rx >= 0)
        compose.onNodeWithText("Drishti").assertIsDisplayed()
        compose.onNodeWithText("Start monitoring").assertIsNotEnabled()
        compose.onNodeWithText("Items").performClick()
        compose.onNode(hasText("Choose a trained category", substring = true)).performScrollTo().performClick()
        compose.onNodeWithText("Search categories").performTextInput("bottle")
        compose.onNode(hasText("bottle") and !hasSetTextAction()).performScrollTo().performClick()
        compose.onNodeWithText("Monitor").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("bottle").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("bottle").assertIsDisplayed()
        compose.onNodeWithText("Start monitoring").assertIsNotEnabled()
        compose.onNodeWithText("Usage").assertDoesNotExist()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Tools").performClick()
        compose.onNodeWithText("API activity").performScrollTo().performClick()
        compose.onNodeWithText("No requests logged yet").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("API key").performScrollTo().performTextInput("nvapi-fixture")
        compose.onNodeWithText("API key").performImeAction()
        compose.onNodeWithText("NVIDIA NIM detected").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connect & load models").performScrollTo().assertIsEnabled()
        // No Connect tap: this typed test value must never become a provider request.
        compose.onNodeWithText("API key").performScrollTo().performTextClearance()
        compose.onNodeWithText("API key").performImeAction()
        compose.onNodeWithText("Items").performClick()
        compose.onNodeWithText("Remove").performScrollTo().performClick()
        compose.onNodeWithText("Monitor").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("All supported objects").fetchSemanticsNodes().size == 1 }
        assertEquals(tx, TrafficStats.getUidTxBytes(Process.myUid()))
        assertEquals(rx, TrafficStats.getUidRxBytes(Process.myUid()))
    }
}
