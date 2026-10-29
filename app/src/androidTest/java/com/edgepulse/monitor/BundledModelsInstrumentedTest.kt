package com.edgepulse.monitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.edgepulse.core.audit.AuditCanonical
import com.edgepulse.monitor.vision.ml.ModelManifest
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/** Runtime compatibility checks only. Emulator timings are never device acceptance evidence. */
@RunWith(AndroidJUnit4::class)
class BundledModelsInstrumentedTest {
    @Test fun allFourBundledAssetsInvokeWithTheExportedTensorContract() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val manifest = assets.open("models/manifest.json").use { Json.decodeFromString<ModelManifest>(it.readBytes().toString(Charsets.UTF_8)) }
        assertEquals(4, manifest.models.size)
        for (asset in manifest.models) {
            val bytes = assets.open(asset.asset).use { it.readBytes() }
            assertEquals(asset.sha256, AuditCanonical.hex(AuditCanonical.hash(bytes)))
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
            Interpreter(model, Interpreter.Options().setNumThreads(2).setUseXNNPACK(true)).use { interpreter ->
                val input = interpreter.getInputTensor(0)
                val output = interpreter.getOutputTensor(0)
                assertArrayEquals(intArrayOf(1, 320, 320, 3), input.shape())
                assertArrayEquals(intArrayOf(1, 2100, 85), output.shape())
                val expectedType = if (asset.variant == "INT8") DataType.INT8 else DataType.FLOAT32
                assertEquals(expectedType, input.dataType())
                assertEquals(expectedType, output.dataType())
                val inputBuffer = ByteBuffer.allocateDirect(input.numBytes()).order(ByteOrder.nativeOrder())
                repeat(input.numElements()) { index ->
                    // A nonconstant deterministic BGR test pattern, never calibration/accuracy data.
                    val value = (index % 251).toFloat()
                    if (expectedType == DataType.INT8) {
                        val quant = input.quantizationParams()
                        inputBuffer.put((kotlin.math.round(value / quant.scale) + quant.zeroPoint).toInt().coerceIn(-128, 127).toByte())
                    } else inputBuffer.putFloat(value)
                }
                val result = ByteBuffer.allocateDirect(output.numBytes()).order(ByteOrder.nativeOrder())
                inputBuffer.rewind()
                interpreter.run(inputBuffer, result)
                result.rewind()
                val values = FloatArray(output.numElements()) {
                    if (expectedType == DataType.INT8) {
                        val quant = output.quantizationParams()
                        (result.get().toInt() - quant.zeroPoint) * quant.scale
                    } else result.float
                }
                assertTrue(asset.asset, values.all(Float::isFinite))
                assertTrue("Raw head must be nonconstant: ${asset.asset}", values.max() > values.min())
            }
        }
    }

    @Test fun compiledModelPathInvokesTheFp16NanoAssetOnCpu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Environment.create(context).use { environment ->
            CompiledModel.create(context.assets, "models/yolox_nano_320_fp16.tflite", CompiledModel.Options(Accelerator.CPU), environment).use { model ->
                val inputs = model.createInputBuffers()
                try {
                    val outputs = model.createOutputBuffers()
                    try {
                        assertEquals(1, inputs.size)
                        inputs[0].writeFloat(FloatArray(320 * 320 * 3) { 114f })
                        model.run(inputs, outputs)
                        val values = outputs.single().readFloat()
                        assertEquals(2100 * 85, values.size)
                        assertTrue(values.all(Float::isFinite))
                    } finally { outputs.forEach { it.close() } }
                } finally { inputs.forEach { it.close() } }
            }
        }
    }
}
