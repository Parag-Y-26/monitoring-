package com.edgepulse.monitor.monitoring

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.os.SystemClock
import android.graphics.Bitmap
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.edgepulse.core.camera.*
import com.edgepulse.core.vision.*
import com.edgepulse.monitor.vision.ml.YoloxDetector
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StationaryCameraXController(private val context: Context, private val runtime: MonitoringRuntime,
    private val delegate: String = "XNNPACK_INT8", private val burstFps: Int = 5,
    private val target: ItemProfile? = null, private val reference: Bitmap? = null) {
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val gate = LumaMotionGate()
    private val preRoll = FramePreRollBuffer()
    private val cadence = TemporalCadenceController(SystemClock.elapsedRealtime())
    private var frames = 0L
    private var lastPublished = 0L
    private var previousFrames = 0L
    private var wakePreRoll = 0
    private var detector: YoloxDetector? = null
    private var matcher: ReferenceMatcher? = null
    private val selected = DetectionBuffer()
    private val matches = DetectionBuffer()
    private val tracker = ObjectTracker()
    private var lastInference = 0L
    private var lastScan = 0L
    private var inferenceMs = 0.0
    private var lastRotation = -1
    @Volatile private var closed = false
    private val resourceLock = Any()

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    suspend fun start(owner: LifecycleOwner, onError: () -> Unit) {
        withContext(Dispatchers.Default) {
            val loaded = YoloxDetector(context, delegate)
            synchronized(resourceLock) { if (closed) loaded.close() else detector = loaded }
            reference?.let { bitmap ->
                try {
                    val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val bgr = FloatArray(pixels.size * 3)
                    for (i in pixels.indices) { bgr[i*3] = (pixels[i] and 255).toFloat(); bgr[i*3+1] = (pixels[i] shr 8 and 255).toFloat(); bgr[i*3+2] = (pixels[i] shr 16 and 255).toFloat() }
                    matcher = ReferenceMatcher(bgr, bitmap.width, bitmap.height)
                    pixels.fill(0); bgr.fill(0f)
                } finally { bitmap.recycle() }
            }
        }
        val future = ProcessCameraProvider.getInstance(context)
        val cameraProvider = suspendCancellableCoroutine { continuation ->
            future.addListener({
                try { continuation.resume(future.get()) } catch (failure: Exception) { continuation.resumeWithException(failure) }
            }, ContextCompat.getMainExecutor(context))
        }
        provider = cameraProvider
        val resolution = ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_NONE)).build()
        val builder = ImageAnalysis.Builder().setResolutionSelector(resolution)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        Camera2Interop.Extender(builder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(5, 10))
        val useCase = builder.build()
        analysis = useCase
        runtime.attachAnalysis(useCase)
        useCase.setAnalyzer(executor) { image ->
            try {
                if (closed) return@setAnalyzer
                check(image.width == 640 && image.height == 480)
                val now = SystemClock.elapsedRealtime()
                if (image.imageInfo.rotationDegrees != lastRotation) {
                    tracker.reset(); preRoll.clear(); lastScan = 0
                    lastRotation = image.imageInfo.rotationDegrees
                }
                val planes = image.planes
                val motion = gate.evaluate(planes[0].buffer, image.width, image.height, planes[0].rowStride, planes[0].pixelStride, now)
                val rescan = lastScan == 0L || now - lastScan >= 60_000 || runtime.consumeRescan()
                if (cadence.update(motion || rescan, now)) {
                    wakePreRoll = preRoll.size
                    lastScan = now
                    for (i in 0 until preRoll.size) {
                        val slot = preRoll.oldest(i)
                        detector!!.infer(slot.data, 0, 640, 1, slot.data, 640*480, 320, 1,
                            slot.data, 640*480+320*240, 320, 1, slot.rotationDegrees, .25f)
                        processDetections(slot.elapsedMs)
                    }
                    preRoll.clear()
                }
                val limitFps = if (runtime.thermalStatus >= 2) 5 else burstFps.coerceIn(5, 10)
                if (cadence.isActive && now - lastInference >= 1000 / limitFps) {
                    val started = System.nanoTime()
                    detector!!.infer(planes[0].buffer, planes[0].buffer.position(), planes[0].rowStride, planes[0].pixelStride,
                        planes[1].buffer, planes[1].buffer.position(), planes[1].rowStride, planes[1].pixelStride,
                        planes[2].buffer, planes[2].buffer.position(), planes[2].rowStride, planes[2].pixelStride, image.imageInfo.rotationDegrees, .25f)
                    processDetections(now)
                    inferenceMs = (System.nanoTime() - started) / 1_000_000.0
                    lastInference = now
                }
                if (!cadence.isActive) preRoll.copy(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride,
                    planes[1].buffer, planes[1].rowStride, planes[1].pixelStride, planes[2].buffer, planes[2].rowStride,
                    planes[2].pixelStride, image.imageInfo.rotationDegrees, now)
                frames++
                if (now - lastPublished >= 1000) {
                    val fps = if (lastPublished == 0L) 0.0 else (frames - previousFrames) * 1000.0 / (now - lastPublished)
                    runtime.telemetry(frames, gate.foregroundFraction, cadence.isActive, cadence.dutyPercent(now), gate.freezeTimeouts, wakePreRoll, fps, tracker.confirmedVisible, inferenceMs)
                    lastPublished = now; previousFrames = frames
                }
            } catch (_: Exception) {
                ContextCompat.getMainExecutor(context).execute(onError)
            } finally { image.close() }
        }
        val livePreview = Preview.Builder().setResolutionSelector(resolution).build()
        preview = livePreview
        runtime.attachPreview(livePreview)
        val camera = cameraProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, useCase, livePreview)
        camera.cameraInfo.cameraState.observe(owner) { if (it.error != null) onError() }
    }

    fun close() {
        if (closed) return
        closed = true
        analysis?.clearAnalyzer()
        val a = analysis; val p = preview
        if (a != null && p != null) provider?.unbind(a, p)
        runtime.attachPreview(null)
        runtime.attachAnalysis(null)
        executor.execute { synchronized(resourceLock) { detector?.close(); detector = null } }
        executor.shutdown()
    }

    private fun processDetections(elapsedMs: Long) {
        if (closed) return
        val model = checkNotNull(detector)
        selected.clear()
        if (matcher != null && target != null && target.classId == null) {
            matcher!!.detect(model.bgr, 320, 320, target.similarityThreshold,
                (target.minWidthFraction*320).toInt().coerceAtLeast(4), (target.maxWidthFraction*320).toInt(), matches)
            for (i in 0 until matches.size) selected.add(model.transform.uprightX(matches.left[i]*320), model.transform.uprightY(matches.top[i]*320),
                model.transform.uprightX(matches.right[i]*320), model.transform.uprightY(matches.bottom[i]*320), matches.scores[i], 80)
        } else for (i in 0 until model.detections.size) if (target?.classId == null || model.detections.classes[i] == target.classId) selected.copyFrom(model.detections, i)
        tracker.update(selected, elapsedMs)
        runtime.detection(tracker.boxes, model.transform.uprightWidth, model.transform.uprightHeight)
    }
}
