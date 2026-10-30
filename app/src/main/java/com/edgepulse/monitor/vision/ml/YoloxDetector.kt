package com.edgepulse.monitor.vision.ml

import android.content.Context
import com.edgepulse.core.vision.*
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.roundToInt
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/** Single analyzer-thread owner; all application pixel/tensor/result storage is reused. */
class YoloxDetector(context: Context, delegate: String) : Closeable {
    private val interpreter: Interpreter
    private val input: ByteBuffer
    private val output: ByteBuffer
    private val inputs: Array<Any>
    private val outputs: Map<Int, Any>
    private val inputScale: Float
    private val inputZero: Int
    private val outputScale: Float
    private val outputZero: Int
    private val floatOutput: Boolean
    private val floatValues: java.nio.FloatBuffer
    val bgr = FloatArray(320 * 320 * 3)
    private val raw = FloatArray(2100 * 85)
    private val transforms = Array(4) { LetterboxTransform(640, 480, it * 90) }
    val post = YoloxPostProcessor()
    val detections = DetectionBuffer()
    var transform = transforms[0]; private set

    init {
        require(delegate == "XNNPACK_INT8") { "Camera delegate needs reusable tensor IO" }
        val bytes = context.assets.open("models/yolox_tiny_320_int8.tflite").use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(4).setUseXNNPACK(true))
        try {
            val source = interpreter.getInputTensor(0); val destination = interpreter.getOutputTensor(0)
            check(source.dataType() == DataType.INT8 && source.numElements() == bgr.size)
            floatOutput = destination.dataType() == DataType.FLOAT32
            check((floatOutput || destination.dataType() == DataType.INT8) && destination.numElements() == raw.size)
            inputScale = source.quantizationParams().scale; inputZero = source.quantizationParams().zeroPoint
            outputScale = destination.quantizationParams().scale; outputZero = destination.quantizationParams().zeroPoint
            check(inputScale > 0 && (floatOutput || outputScale > 0))
            input = ByteBuffer.allocateDirect(source.numBytes()).order(ByteOrder.nativeOrder())
            output = ByteBuffer.allocateDirect(destination.numBytes()).order(ByteOrder.nativeOrder())
            floatValues = output.asFloatBuffer()
            inputs = arrayOf(input); outputs = mapOf(0 to output)
        } catch (failure: Exception) { interpreter.close(); throw failure }
    }

    fun infer(y: ByteBuffer, yo: Int, yr: Int, yp: Int, u: ByteBuffer, uo: Int, ur: Int, up: Int,
        v: ByteBuffer, vo: Int, vr: Int, vp: Int, rotation: Int, threshold: Float, selectedClassId: Int? = null) {
        transform = transforms[rotation / 90]
        bgr.fill(114f)
        val map = transform
        for (dy in 0 until map.resizedHeight) for (dx in 0 until map.resizedWidth) {
            val ux = (dx + .5f) / map.scale; val uy = (dy + .5f) / map.scale
            val sx = floor(when (rotation) { 0 -> ux; 90 -> uy; 180 -> 640-ux; else -> 640-uy }).toInt().coerceIn(0, 639)
            val sy = floor(when (rotation) { 0 -> uy; 90 -> 480-ux; 180 -> 480-uy; else -> ux }).toInt().coerceIn(0, 479)
            val luma = (y.get(yo + sy * yr + sx * yp).toInt() and 255) - 16
            val cb = (u.get(uo + sy / 2 * ur + sx / 2 * up).toInt() and 255) - 128
            val cr = (v.get(vo + sy / 2 * vr + sx / 2 * vp).toInt() and 255) - 128
            val target = ((dy + map.padY) * 320 + dx + map.padX) * 3
            bgr[target] = (1.164f*luma+2.017f*cb).coerceIn(0f, 255f)
            bgr[target+1] = (1.164f*luma-.392f*cb-.813f*cr).coerceIn(0f, 255f)
            bgr[target+2] = (1.164f*luma+1.596f*cr).coerceIn(0f, 255f)
        }
        input.rewind(); output.rewind()
        for (value in bgr) input.put((value / inputScale + inputZero).roundToInt().coerceIn(-128, 127).toByte())
        input.rewind()
        interpreter.runForMultipleInputsOutputs(inputs, outputs)
        if (floatOutput) { floatValues.rewind(); floatValues.get(raw) }
        else for (i in raw.indices) raw[i] = (output.get(i).toInt() - outputZero) * outputScale
        post.decode(raw, map, threshold, .45f, detections, selectedClassId)
    }
    override fun close() { interpreter.close(); bgr.fill(0f); raw.fill(0f) }
}
