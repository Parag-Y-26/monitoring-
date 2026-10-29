package com.edgepulse.monitor.data.remote

import android.os.SystemClock
import com.edgepulse.core.cloud.*
import com.edgepulse.monitor.security.SecureSettingsStore
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
) {
    suspend fun validateKey(setup: ProviderSetup, key: ByteArray, approval: OneShotUploadConsent): KeyValidationStatus = withContext(Dispatchers.IO) {
        setup.validate()
        require(key.size in 1..4096 && key.all { it.toInt() in 33..126 })
        interlock.withConfirmedUpload(approval, setup.consentContext(), SystemClock::elapsedRealtime) {
            // Also reject stale active flags after process death, before constructing a client.
            if (settings.dataStore.data.first().activeSessionId != null) throw MonitoringActiveException()
            val client = OkHttpClient.Builder()
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS).build()
            try {
                val request = request(setup, key)
                await(client.newCall(request)).use { response ->
                    if (response.code != 200) return@withConfirmedUpload ProviderResponseValidator.validate(setup.provider, response.code, "")
                    if (response.body.contentLength() > 1_048_576L) return@withConfirmedUpload KeyValidationStatus.UNEXPECTED_RESPONSE
                    val output = ByteArrayOutputStream()
                    val scratch = ByteArray(4096)
                    response.body.byteStream().use { input ->
                        while (true) {
                            val length = input.read(scratch)
                            if (length < 0) break
                            if (output.size() + length > 1_048_576) return@withConfirmedUpload KeyValidationStatus.UNEXPECTED_RESPONSE
                            output.write(scratch, 0, length)
                        }
                    }
                    ProviderResponseValidator.validate(setup.provider, response.code, output.toString(Charsets.UTF_8.name()))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IOException) { KeyValidationStatus.NETWORK_ERROR }
            finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
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

    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }
}
