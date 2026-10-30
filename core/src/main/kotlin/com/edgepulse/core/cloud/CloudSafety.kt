package com.edgepulse.core.cloud

import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
enum class CloudProvider { GEMINI, OPENROUTER, NVIDIA_NIM }

@Serializable
data class ProviderSetup(val provider: CloudProvider, val validationEndpoint: String, val model: String = "") {
    fun validate() {
        require(validationEndpoint.length in 1..2048)
        val uri = URI(validationEndpoint)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
        require(!uri.rawPath.isNullOrEmpty())
        require(model.length <= 256 && model.none { it.isISOControl() })
        if (provider == CloudProvider.NVIDIA_NIM) require(model.isNotBlank())
    }
    fun consentContext(): String = Json.encodeToString(this)
}

class OneShotUploadConsent private constructor(private val context: String, private val issuedElapsedMs: Long) {
    private val consumed = AtomicBoolean(false)
    fun consume(expectedContext: String, nowElapsedMs: Long) {
        if (context != expectedContext || nowElapsedMs < issuedElapsedMs || nowElapsedMs - issuedElapsedMs > 60_000 || !consumed.compareAndSet(false, true)) {
            throw SecurityException("A fresh matching Confirm & Upload tap is required")
        }
    }
    companion object {
        /** Called only in the explicit confirmation button's click handler. */
        fun fromUserTap(context: String, elapsedMs: Long): OneShotUploadConsent {
            require(context.isNotBlank() && elapsedMs >= 0)
            return OneShotUploadConsent(context, elapsedMs)
        }
    }
}

/** Phase 2 must use the same singleton when starting/stopping monitoring. */
class MonitoringActiveException : IllegalStateException("Stop monitoring before any cloud request")

class MonitoringNetworkInterlock {
    private val mutex = Mutex()
    private var monitoring = false
    suspend fun startMonitoring(persistActive: suspend () -> Unit) = mutex.withLock {
        check(!monitoring)
        persistActive()
        monitoring = true
    }
    suspend fun stopMonitoring(persistInactive: suspend () -> Unit) = mutex.withLock {
        persistInactive()
        monitoring = false
    }
    /** Heartbeat, recovery and stop decisions share the upload/start lock. */
    suspend fun <T> withSessionState(block: suspend () -> T): T = mutex.withLock { block() }
    suspend fun <T> withConfirmedUpload(consent: OneShotUploadConsent, context: String, elapsedMs: () -> Long, block: suspend () -> T): T = mutex.withLock {
        consent.consume(context, elapsedMs())
        if (monitoring) throw MonitoringActiveException()
        block()
    }
}

enum class KeyValidationStatus {
    VALID, INVALID_KEY, PERMISSION_DENIED, QUOTA_OR_RATE_LIMIT, CONFIGURATION_ERROR,
    PROVIDER_UNAVAILABLE, UNEXPECTED_RESPONSE, NETWORK_ERROR,
    NO_MODELS,
}

object ProviderResponseValidator {
    fun validate(provider: CloudProvider, statusCode: Int, body: String): KeyValidationStatus {
        if (statusCode != 200) return when (statusCode) {
            401 -> KeyValidationStatus.INVALID_KEY
            403 -> KeyValidationStatus.PERMISSION_DENIED
            429 -> KeyValidationStatus.QUOTA_OR_RATE_LIMIT
            in 500..599 -> KeyValidationStatus.PROVIDER_UNAVAILABLE
            else -> KeyValidationStatus.CONFIGURATION_ERROR
        }
        return try {
            val json = Json.parseToJsonElement(body).jsonObject
            val valid = when (provider) {
                CloudProvider.GEMINI -> json["models"]?.jsonArray?.any { !it.jsonObject["name"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() } == true
                CloudProvider.OPENROUTER -> json["data"]?.jsonObject?.let {
                    it["is_management_key"]?.jsonPrimitive?.booleanOrNull == false &&
                        it["is_provisioning_key"]?.jsonPrimitive?.booleanOrNull != true && it["label"]?.jsonPrimitive?.contentOrNull != null
                } == true
                CloudProvider.NVIDIA_NIM -> json["choices"]?.jsonArray?.any { it.jsonObject["message"] is JsonObject } == true
            }
            if (valid) KeyValidationStatus.VALID else KeyValidationStatus.UNEXPECTED_RESPONSE
        } catch (_: Exception) { KeyValidationStatus.UNEXPECTED_RESPONSE }
    }
}
