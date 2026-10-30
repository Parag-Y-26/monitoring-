package com.edgepulse.monitor.data.remote

import android.os.SystemClock
import com.edgepulse.core.cloud.*
import com.edgepulse.monitor.security.SecureSettingsStore
import com.edgepulse.monitor.security.ApiUsageRepository
import com.edgepulse.core.audit.AuditCanonical
import java.util.Base64
import kotlinx.coroutines.NonCancellable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** The only HTTP-client owner. A new one-shot confirmation is required for each operation. */
@Singleton
class CloudAnalysisService @Inject constructor(
    private val settings: SecureSettingsStore,
    private val interlock: MonitoringNetworkInterlock,
    private val usage: ApiUsageRepository,
) {
    suspend fun fetchModels(provider: CloudProvider, key: ByteArray, approval: OneShotUploadConsent): ModelDiscovery = withContext(Dispatchers.IO) {
        requireKey(key)
        withConfirmedClient(approval, ProviderDefaults.discoveryContext(provider)) { client ->
            withTimeout(60_000) {
                ModelDirectory.load(provider) { endpoint ->
                    val profile = ProviderDefaults.profile(provider)
                    require(endpoint.substringBefore('?') in setOf(profile.modelsEndpoint, profile.validationEndpoint))
                    val builder = Request.Builder().url(endpoint).get()
                    authorize(builder, provider, key)
                    try {
                        recordedRequest(client.newCall(builder.build()), 8 * 1024 * 1024, provider, "MODEL_CATALOG", "", 0)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: IOException) { throw CatalogException(KeyValidationStatus.NETWORK_ERROR) }
                }
            }
        }
    }

    suspend fun validateKey(setup: ProviderSetup, key: ByteArray, approval: OneShotUploadConsent): KeyValidationStatus = withContext(Dispatchers.IO) {
        setup.validate()
        require(setup.validationEndpoint == ProviderDefaults.profile(setup.provider).validationEndpoint)
        requireKey(key)
        withConfirmedClient(approval, setup.consentContext()) { client ->
            try {
                val response = recordedRequest(client.newCall(request(setup, key)), 1_048_576, setup.provider, "KEY_OR_MODEL_CHECK", setup.model, 0)
                ProviderResponseValidator.validate(setup.provider, response.statusCode, response.body)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { KeyValidationStatus.NETWORK_ERROR }
        }
    }

    suspend fun describeItem(setup: ProviderSetup, key: ByteArray, approvedJpeg: ByteArray, approval: OneShotUploadConsent): ItemCharacteristics = withContext(Dispatchers.IO) {
        setup.validate(); requireKey(key)
        require(ProviderDefaults.isOfficialOrigin(setup.provider, setup.validationEndpoint))
        require(approvedJpeg.size in 1..1_000_000 && setup.model.isNotBlank())
        withConfirmedClient(approval, photoConsentContext(setup, approvedJpeg)) { client ->
            val prompt = "Identify the single main object. Return JSON: {\"name\":string,\"characteristics\":string,\"matchedCocoClass\":string|null} using a standard COCO 80 label if applicable. Ignore people/PII."
            val encoded = Base64.getEncoder().encodeToString(approvedJpeg)
            val body: JsonObject
            val endpoint: String
            if (setup.provider == CloudProvider.GEMINI) {
                endpoint = ProviderDefaults.profile(setup.provider).modelsEndpoint.toHttpUrl().newBuilder()
                    .addPathSegment(setup.model.removePrefix("models/") + ":generateContent").build().toString()
                body = buildJsonObject {
                    putJsonArray("contents") { addJsonObject { putJsonArray("parts") {
                        addJsonObject { put("text", prompt) }
                        addJsonObject { putJsonObject("inlineData") { put("mimeType", "image/jpeg"); put("data", encoded) } }
                    } } }
                    putJsonObject("generationConfig") {
                        put("responseMimeType", "application/json"); put("maxOutputTokens", 256)
                        // 2.5 Pro cannot disable thinking; other generations use different controls.
                        if (setup.model.removePrefix("models/").startsWith("gemini-2.5-flash"))
                            putJsonObject("thinkingConfig") { put("thinkingBudget", 0) }
                        putJsonObject("responseSchema") {
                            put("type", "OBJECT")
                            putJsonObject("properties") {
                                putJsonObject("name") { put("type", "STRING") }
                                putJsonObject("characteristics") { put("type", "STRING") }
                                putJsonObject("matchedCocoClass") { put("type", "STRING"); put("nullable", true) }
                            }
                            putJsonArray("required") { add("name"); add("characteristics"); add("matchedCocoClass") }
                        }
                    }
                }
            } else {
                endpoint = if (setup.provider == CloudProvider.NVIDIA_NIM) ProviderDefaults.profile(setup.provider).validationEndpoint
                    else ProviderDefaults.profile(setup.provider).modelsEndpoint.toHttpUrl().newBuilder().encodedPath("/api/v1/chat/completions").build().toString()
                body = buildJsonObject {
                    put("model", setup.model); put("max_tokens", 256); put("stream", false)
                    putJsonArray("messages") { addJsonObject { put("role", "user"); putJsonArray("content") {
                        addJsonObject { put("type", "text"); put("text", prompt) }
                        addJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", "data:image/jpeg;base64,$encoded") } }
                    } } }
                }
            }
            val builder = Request.Builder().url(endpoint).post(body.toString().toRequestBody("application/json".toMediaType()))
            authorize(builder, setup.provider, key)
            val response = recordedRequest(client.newCall(builder.build()), 1_048_576, setup.provider, "ITEM_PHOTO_DESCRIPTION", setup.model, 1)
            if (response.statusCode != 200) throw CatalogException(ProviderResponseValidator.validate(setup.provider, response.statusCode, response.body))
            try { CharacteristicsParser.parse(setup.provider, response.body) }
            catch (_: Exception) { throw CatalogException(KeyValidationStatus.UNEXPECTED_RESPONSE) }
        }
    }

    private suspend fun recordedRequest(call: Call, limit: Int, provider: CloudProvider, purpose: String, model: String, images: Int): CloudResponse {
        var response: CloudResponse? = null
        var outcome = "NETWORK_ERROR_OR_CANCELLED"
        try {
            response = readResponse(call, limit)
            outcome = "HTTP_${response.statusCode}"
            return response
        } finally { withContext(NonCancellable) { usage.record(provider, purpose, model, images, response, outcome) } }
    }

    companion object {
        fun photoConsentContext(setup: ProviderSetup, approvedJpeg: ByteArray): String =
            "EdgePulse/item-photo/v1/" + setup.consentContext() + "/" + AuditCanonical.hex(AuditCanonical.hash(approvedJpeg))
    }

    private suspend fun <T> withConfirmedClient(approval: OneShotUploadConsent, context: String, block: suspend (OkHttpClient) -> T): T =
        interlock.withConfirmedUpload(approval, context, SystemClock::elapsedRealtime) {
            // Also reject stale active flags after process death, before constructing a client.
            if (settings.dataStore.data.first().activeSessionId != null) throw MonitoringActiveException()
            val client = OkHttpClient.Builder()
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS).build()
            try {
                block(client)
            }
            finally {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }

    private fun requireKey(key: ByteArray) = require(key.size in 1..4096 && key.all { it.toInt() in 33..126 })

    private fun authorize(builder: Request.Builder, provider: CloudProvider, key: ByteArray) {
        val credential = key.toString(Charsets.US_ASCII)
        if (provider == CloudProvider.GEMINI) builder.header("x-goog-api-key", credential)
        else builder.header("Authorization", "Bearer $credential")
    }

    private fun body(response: Response, limit: Int): String {
        if (response.body.contentLength() > limit) throw IOException("Provider response exceeded metadata limit")
        val output = ByteArrayOutputStream()
        val scratch = ByteArray(16 * 1024)
        response.body.byteStream().use { input ->
            while (true) {
                val count = input.read(scratch)
                if (count < 0) break
                if (output.size() + count > limit) throw IOException("Provider response exceeded metadata limit")
                output.write(scratch, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun request(setup: ProviderSetup, key: ByteArray): Request {
        // HTTP header APIs require String; the caller still zero-fills its application-managed byte buffers.
        val credential = key.toString(Charsets.US_ASCII)
        val builder = Request.Builder()
        when (setup.provider) {
            CloudProvider.GEMINI -> builder
                .url(setup.validationEndpoint.toHttpUrl().newBuilder().addQueryParameter("pageSize", "1").build())
                .header("x-goog-api-key", credential).get()
            CloudProvider.OPENROUTER -> builder.url(setup.validationEndpoint)
                .header("Authorization", "Bearer $credential").get()
            CloudProvider.NVIDIA_NIM -> {
                val body = buildJsonObject {
                    put("model", setup.model)
                    put("max_tokens", 1)
                    put("stream", false)
                    putJsonArray("messages") {
                        addJsonObject { put("role", "user"); put("content", "Reply OK.") }
                    }
                }.toString().toRequestBody("application/json".toMediaType())
                builder.url(setup.validationEndpoint).header("Authorization", "Bearer $credential").post(body)
            }
        }
        return builder.build()
    }

    private suspend fun readResponse(call: Call, limit: Int): CloudResponse = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val result = CloudResponse(response.code, body(response, limit))
                        continuation.resume(result) { _, _, _ -> }
                    } catch (failure: Exception) {
                        if (!continuation.isCancelled) continuation.resumeWithException(failure)
                    }
                }
            }
        })
    }
}
