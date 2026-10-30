package com.edgepulse.monitor.monitoring

import androidx.camera.core.Preview
import androidx.camera.core.ImageAnalysis
import android.os.Handler
import android.os.Looper
import com.edgepulse.core.vision.DetectionBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MonitoringState(
    val active: Boolean = false, val starting: Boolean = false, val paused: Boolean = false,
    val sessionId: String? = null, val frames: Long = 0, val foreground: Double = 0.0,
    val burst: Boolean = false, val duty: Double = 0.0, val freezes: Long = 0,
    val preRollFrames: Int = 0, val fps: Double = 0.0, val error: String? = null,
    val visibleItems: Int = 0, val inferenceMs: Double = 0.0, val detectorReady: Boolean = false,
)

@Singleton
class MonitoringRuntime @Inject constructor() {
    private val mutable = MutableStateFlow(MonitoringState())
    val state = mutable.asStateFlow()
    val overlay = DetectionOverlay()
    private val main = Handler(Looper.getMainLooper())
    private val posted = AtomicBoolean(false)
    private val publish = Runnable { posted.set(false); overlay.changed() }
    private val scanRequested = AtomicBoolean(false)
    @Volatile var thermalStatus: Int = 0
    fun requestRescan() { scanRequested.set(true) }
    fun consumeRescan(): Boolean = scanRequested.getAndSet(false)
    fun detection(boxes: DetectionBuffer, width: Int, height: Int) {
        synchronized(overlay.lock) { overlay.copyFrom(boxes, width, height) }
        if (posted.compareAndSet(false, true)) main.post(publish)
    }
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    var targetRotation: Int = 0; private set
    fun setRotation(rotation: Int) {
        if (targetRotation != rotation) { requestRescan(); overlay.clear() }
        targetRotation = rotation; preview?.targetRotation = rotation; analysis?.targetRotation = rotation
    }
    fun attachAnalysis(value: ImageAnalysis?) { analysis = value; value?.targetRotation = targetRotation }
    private var surface: Preview.SurfaceProvider? = null
    /** Main-thread camera ownership only. The service owns the use case. */
    fun attachSurface(value: Preview.SurfaceProvider?) { surface = value; preview?.setSurfaceProvider(value) }
    fun attachPreview(value: Preview?) { preview = value; value?.targetRotation = targetRotation; value?.setSurfaceProvider(surface) }
    fun starting() { mutable.value = MonitoringState(starting = true) }
    fun started(id: String) { mutable.update { it.copy(active = true, starting = false, sessionId = id, error = null) } }
    fun paused(value: Boolean) { mutable.update { it.copy(paused = value) } }
    fun stopped(message: String? = null) { overlay.clear(); mutable.update { it.copy(active = false, starting = false, paused = false, error = message) } }
    fun telemetry(frames: Long, foreground: Double, burst: Boolean, duty: Double, freezes: Long, preroll: Int, fps: Double, visible: Int = 0, inferenceMs: Double = 0.0) {
        mutable.update { it.copy(frames = frames, foreground = foreground, burst = burst, duty = duty, freezes = freezes, preRollFrames = preroll, fps = fps,
            visibleItems = visible, inferenceMs = inferenceMs, detectorReady = true) }
    }
}
