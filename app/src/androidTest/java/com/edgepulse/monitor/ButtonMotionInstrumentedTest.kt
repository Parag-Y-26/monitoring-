package com.edgepulse.monitor

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.edgepulse.monitor.ui.EdgePrimaryButton
import com.edgepulse.monitor.ui.EdgePulseTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ButtonMotionInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private fun verifyPress(reduced: Boolean) {
        var clicks = 0
        var enabled by mutableStateOf(true)
        compose.setContent { EdgePulseTheme(reducedMotion = reduced) {
            Box(Modifier.requiredSize(320.dp,120.dp).padding(20.dp).testTag("motion-preview")) {
                EdgePrimaryButton("Continue",{ clicks++ },enabled,Modifier.testTag("motion-button"))
            }
        } }
        compose.mainClock.autoAdvance = false
        val button = compose.onNodeWithTag("motion-button")
        val resting = button.fetchSemanticsNode().boundsInRoot
        button.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        val held = button.fetchSemanticsNode().boundsInRoot
        if (reduced) assertEquals("Reduced motion must disable press transforms",resting.width,held.width,.1f)
        else assertTrue("Press feedback must visibly compress the button",held.width < resting.width)
        capture(if (reduced) "reduced-pressed" else "spring-pressed")
        button.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1,clicks); enabled = false }
        button.assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals("Disabled buttons must not dispatch actions",1,clicks) }
    }

    @Test fun springPressKeepsTheActionResponsiveAndSingleShot() = verifyPress(false)
    @Test fun reducedMotionKeepsTheButtonStillAndClickable() = verifyPress(true)

    private fun capture(name: String) {
        val root = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        val file = File(root,"button-motion/$name.png")
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        file.outputStream().use { check(compose.onNodeWithTag("motion-preview").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)) }
    }
}
