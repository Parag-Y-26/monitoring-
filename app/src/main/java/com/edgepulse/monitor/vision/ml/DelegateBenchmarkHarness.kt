package com.edgepulse.monitor.vision.ml

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.edgepulse.monitor.BuildConfig
import com.edgepulse.core.audit.AuditCanonical
import com.edgepulse.core.benchmark.BenchmarkPolicy
import com.edgepulse.monitor.security.SecureSettingsStore
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import kotlin.coroutines.coroutineContext

@Serializable
data class BundledModel(
    val model: String,
    val variant: String,
    val asset: String,
    val sha256: String,
    val license: String,
)

@Serializable
data class ModelManifest(val version: Int, val models: List<BundledModel>)

@Serializable
data class DelegateMeasurement(
    val model: String, val delegate: String, val p95Ms: Double? = null, val status: String,
    val samplesMs: List<Double> = emptyList(), val warmups: Int = 20, val failureReason: String? = null,
)

@Serializable
data class BenchmarkReport(
    val fingerprint: String,
    val manifestSha256: String,
    val runtimeVersion: String,
    val measurements: List<DelegateMeasurement>,
    val tinyWinner: String?,
    val tinyP95Ms: Double?,
    val constructionEligible: Boolean,
    val tierCTargetMet: Boolean,
    val burstFps: Int,
    val environment: String = "UNCLASSIFIED",
    val startedWallMs: Long = 0,
    val completedWallMs: Long = 0,
    val thermalStatusStart: Int? = null,
    val thermalStatusEnd: Int? = null,
)

/** Runs only on explicit first-run setup, outside any camera frame loop. */
class DelegateBenchmarkHarness(private val context: Context, private val settings: SecureSettingsStore) {
    private val runtimeVersion = BuildConfig.LITERT_VERSION

    suspend fun run(force: Boolean = false): BenchmarkReport = withContext(Dispatchers.IO) {
        val startedWallMs = System.currentTimeMillis()
        val thermalStart = thermalStatus()
        // A virtual CPU's timings must never unlock physical-device acceptance.
        val emulator = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.PRODUCT.startsWith("sdk_") ||
            Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("Emulator", ignoreCase = true)
        val environment = if (emulator) "EMULATOR" else "PHYSICAL_DEVICE_CANDIDATE"
        val manifestBytes = context.assets.open("models/manifest.json").use { it.readBytes() }
        val manifestHash = AuditCanonical.hex(AuditCanonical.hash(manifestBytes))
        val manifest = Json.decodeFromString<ModelManifest>(manifestBytes.toString(Charsets.UTF_8))
        require(manifest.version == 1)
        require(manifest.models.map { it.model to it.variant }.distinct().size == manifest.models.size)
        require(manifest.models.map { it.model to it.variant }.toSet() == setOf(
            "YOLOX_TINY" to "FP16", "YOLOX_TINY" to "INT8", "YOLOX_NANO" to "FP16", "YOLOX_NANO" to "INT8"))
        // A cached latency is reusable only for the exact bytes that were benchmarked.
        for (asset in manifest.models) validateAsset(asset)
        val cached = settings.dataStore.data.first().benchmarkCache
        if (!force && cached.isNotEmpty()) {
            val previous = Json.decodeFromString<BenchmarkReport>(cached)
            if (previous.fingerprint == Build.FINGERPRINT && previous.manifestSha256 == manifestHash && previous.runtimeVersion == runtimeVersion && previous.environment == environment) return@withContext previous
        }
        val measurements = mutableListOf<DelegateMeasurement>()
        for (model in listOf("YOLOX_TINY", "YOLOX_NANO")) {
            for ((delegate, variant) in listOf("NPU_COMPILED" to "INT8", "GPU_FP16" to "FP16", "XNNPACK_INT8" to "INT8")) {
                coroutineContext.ensureActive()
                val asset = manifest.models.singleOrNull { it.model == model && it.variant == variant }
                if (asset == null) {
                    measurements += DelegateMeasurement(model, delegate, status = "MODEL_MISSING")
                    continue
                }
                try {
                    val backend = if (delegate == "XNNPACK_INT8") cpu(asset) else compiled(asset, delegate)
                    backend.use {
                        repeat(20) { coroutineContext.ensureActive(); backend.invoke() }
                        val samples = DoubleArray(100)
                        repeat(samples.size) { index ->
                            coroutineContext.ensureActive()
                            val start = System.nanoTime()
                            backend.invoke()
                            samples[index] = (System.nanoTime() - start) / 1_000_000.0
                        }
                        measurements += DelegateMeasurement(model, delegate, BenchmarkPolicy.p95(samples), "MEASURED", samplesMs = samples.toList())
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (unsupported: Exception) {
                    measurements += DelegateMeasurement(model, delegate, status = "UNAVAILABLE_${unsupported.javaClass.simpleName}",
                        warmups = 0, failureReason = unsupported.message?.replace('\n', ' ')?.take(240))
                }
            }
        }
        val winner = measurements.filter { it.model == "YOLOX_TINY" && it.status == "MEASURED" }.minByOrNull { it.p95Ms!! }
        val p95 = winner?.p95Ms
        val report = BenchmarkReport(Build.FINGERPRINT, manifestHash, runtimeVersion, measurements,
            winner?.delegate, p95, !emulator && (p95?.let(BenchmarkPolicy::constructionEligible) ?: false),
            !emulator && (p95?.let(BenchmarkPolicy::tierCTargetMet) ?: false),
            if (emulator) 0 else p95?.let(BenchmarkPolicy::burstFps) ?: 0, environment,
            startedWallMs, System.currentTimeMillis(), thermalStart, thermalStatus())
        settings.dataStore.updateData { it.copy(benchmarkCache = Json.encodeToString(report)) }
        report
    }

    private interface Backend : Closeable { fun invoke() }

    private fun thermalStatus(): Int? = if (Build.VERSION.SDK_INT >= 29)
        context.getSystemService(PowerManager::class.java)?.currentThermalStatus else null

    private fun validateAsset(asset: BundledModel) {
        require(asset.model in setOf("YOLOX_TINY", "YOLOX_NANO") && asset.variant in setOf("FP16", "INT8"))
        require(asset.license == "Apache-2.0" && AuditCanonical.validHash(asset.sha256))
        require(Regex("models/[a-z0-9_]+\\.tflite").matches(asset.asset))
        val actual = context.assets.open(asset.asset).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val scratch = ByteArray(32 * 1024)
            while (true) { val n = input.read(scratch); if (n < 0) break; digest.update(scratch, 0, n) }
            AuditCanonical.hex(digest.digest())
        }
        require(actual == asset.sha256) { "Model checksum mismatch" }
    }

    private fun cpu(asset: BundledModel): Backend {
        val bytes = context.assets.open(asset.asset).use { it.readBytes() }
        val modelBuffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).apply { rewind() }
        val interpreter = Interpreter(modelBuffer, Interpreter.Options().setNumThreads(4).setUseXNNPACK(true))
        try {
            val inputTensor = interpreter.getInputTensor(0)
            require(inputTensor.dataType() == DataType.INT8 && inputTensor.numElements() == 320 * 320 * 3)
            val input = ByteBuffer.allocateDirect(inputTensor.numBytes()).order(ByteOrder.nativeOrder())
            val outputs = (0 until interpreter.outputTensorCount).associateWith { index ->
                ByteBuffer.allocateDirect(interpreter.getOutputTensor(index).numBytes()).order(ByteOrder.nativeOrder())
            }
            val inputs = arrayOf<Any>(input)
            return object : Backend {
                override fun invoke() {
                    input.rewind(); outputs.values.forEach { it.rewind() }
                    interpreter.runForMultipleInputsOutputs(inputs, outputs)
                }
                override fun close() = interpreter.close()
            }
        } catch (failure: Exception) { interpreter.close(); throw failure }
    }

    private fun compiled(asset: BundledModel, delegate: String): Backend {
        val environment = Environment.create(context)
        val accelerator = if (delegate == "NPU_COMPILED") Accelerator.NPU else Accelerator.GPU
        var model: CompiledModel? = null
        try {
            require(accelerator in environment.getAvailableAccelerators())
            val options = CompiledModel.Options(accelerator)
            if (accelerator == Accelerator.GPU) options.gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP16)
            val loaded = CompiledModel.create(context.assets, asset.asset, options, environment)
            model = loaded
            val inputs = loaded.createInputBuffers()
            try {
                val outputs = loaded.createOutputBuffers()
                try {
                    require(inputs.size == 1)
                    if (asset.variant == "INT8") inputs[0].writeInt8(ByteArray(320 * 320 * 3))
                    else inputs[0].writeFloat(FloatArray(320 * 320 * 3))
                    return object : Backend {
                        override fun invoke() {
                            loaded.run(inputs, outputs)
                            // Force host-visible completion; includes readback cost in this conservative gate.
                            for (output in outputs) { if (asset.variant == "INT8") output.readInt8() else output.readFloat() }
                        }
                        override fun close() {
                            try { inputs.forEach { it.close() } }
                            finally {
                                try { outputs.forEach { it.close() } }
                                finally { try { loaded.close() } finally { environment.close() } }
                            }
                        }
                    }
                } catch (failure: Exception) { outputs.forEach { it.close() }; throw failure }
            } catch (failure: Exception) { inputs.forEach { it.close() }; throw failure }
        } catch (failure: Exception) {
            model?.close(); environment.close(); throw failure
        }
    }
}
