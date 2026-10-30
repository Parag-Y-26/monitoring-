package com.edgepulse.monitor

import android.Manifest
import android.content.Intent
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.edgepulse.monitor.monitoring.MonitoringRuntime
import com.edgepulse.monitor.monitoring.StationaryCameraXController
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual CameraX -> YUV -> gate/cadence -> Tiny -> tracker -> overlay/telemetry. No saved images. */
@SdkSuppress(minSdkVersion = 29)
class CameraPipelineInstrumentedTest {
    @Test fun cameraFramesReachLocalDetectorRescanAndReleaseWithoutUidTraffic() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.CAMERA)
        val runtime = MonitoringRuntime()
        val errors = AtomicInteger()
        val beforeTx = TrafficStats.getUidTxBytes(Process.myUid())
        val beforeRx = TrafficStats.getUidRxBytes(Process.myUid())
        assertTrue("UID traffic counters must be available", beforeTx >= 0 && beforeRx >= 0)
        var controller: StationaryCameraXController? = null
        val startup = CompletableDeferred<Unit>()
        val scenario = ActivityScenario.launch<ComponentActivity>(Intent(context, ComponentActivity::class.java))
        try {
            scenario.onActivity { activity ->
                val preview = PreviewView(activity).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
                activity.setContentView(preview)
                runtime.attachSurface(preview.surfaceProvider)
                controller = StationaryCameraXController(context, runtime)
                activity.lifecycleScope.launch {
                    try { controller!!.start(activity) { errors.incrementAndGet() }; startup.complete(Unit) }
                    catch (failure: Exception) { startup.completeExceptionally(failure) }
                }
            }
            withTimeout(45_000) { startup.await() }
            val first = withTimeout(45_000) { runtime.state.first { it.frames >= 12 && it.detectorReady } }
            assertEquals(0, errors.get())
            assertTrue(first.inferenceMs > 0)
            assertTrue(first.duty in 0.0..100.0)
            val rescanAt = SystemClock.elapsedRealtime()
            runtime.requestRescan()
            val rescanned = withTimeout(10_000) { runtime.state.first { it.frames > first.frames && it.burst } }
            assertTrue(rescanned.visibleItems in 0..128)
            assertEquals(0, errors.get())
            assertEquals("No UID transmit bytes during local camera pipeline", beforeTx, TrafficStats.getUidTxBytes(Process.myUid()))
            assertEquals("No UID receive bytes during local camera pipeline", beforeRx, TrafficStats.getUidRxBytes(Process.myUid()))
            println("CAMERA_PIPELINE frames=${rescanned.frames} rescanMs=${SystemClock.elapsedRealtime()-rescanAt} inferenceMs=${rescanned.inferenceMs} uidTxDelta=0 uidRxDelta=0")
            instrumentation.runOnMainSync { controller!!.close() }
            controller = null
            val provider = ProcessCameraProvider.getInstance(context).get()
            withTimeout(10_000) {
                while (true) {
                    var closed = false
                    instrumentation.runOnMainSync { closed = provider.availableCameraInfos.all { it.cameraState.value?.type == CameraState.Type.CLOSED } }
                    if (closed) break
                    delay(100)
                }
            }
        } finally {
            instrumentation.runOnMainSync { controller?.close(); runtime.attachSurface(null) }
            scenario.close()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
