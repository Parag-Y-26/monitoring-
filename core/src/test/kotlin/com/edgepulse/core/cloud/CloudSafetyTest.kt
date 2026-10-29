package com.edgepulse.core.cloud

import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CloudSafetyTest {
    private fun endpoint(scheme: String = "https") = URI(scheme, "example.invalid", "/validate", null).toString()

    @Test fun `provider setup requires explicit HTTPS endpoint and NVIDIA model`() {
        ProviderSetup(CloudProvider.GEMINI, endpoint()).validate()
        assertThrows(IllegalArgumentException::class.java) { ProviderSetup(CloudProvider.GEMINI, endpoint("http")).validate() }
        assertThrows(IllegalArgumentException::class.java) { ProviderSetup(CloudProvider.OPENROUTER, endpoint() + "?key=forbidden").validate() }
        assertThrows(IllegalArgumentException::class.java) { ProviderSetup(CloudProvider.NVIDIA_NIM, endpoint()).validate() }
        ProviderSetup(CloudProvider.NVIDIA_NIM, endpoint(), "user-selected-model").validate()
    }

    @Test fun `a confirmation is single use short lived and bound to the selected endpoint`() {
        val token = OneShotUploadConsent.fromUserTap("setup-a", 100)
        assertThrows(SecurityException::class.java) { token.consume("setup-b", 101) }
        token.consume("setup-a", 101)
        assertThrows(SecurityException::class.java) { token.consume("setup-a", 102) }
        assertThrows(SecurityException::class.java) { OneShotUploadConsent.fromUserTap("a", 0).consume("a", 60_001) }
        assertThrows(SecurityException::class.java) { OneShotUploadConsent.fromUserTap("a", 5).consume("a", 4) }
    }

    @Test fun `no cloud block executes while monitoring is active`() = runBlocking {
        val gate = MonitoringNetworkInterlock()
        gate.startMonitoring { }
        var sent = false
        try { gate.withConfirmedUpload(OneShotUploadConsent.fromUserTap("a", 0), "a", { 0 }) { sent = true } }
        catch (_: IllegalStateException) { }
        assertFalse(sent)
        gate.stopMonitoring { }
        gate.withConfirmedUpload(OneShotUploadConsent.fromUserTap("a", 0), "a", { 0 }) { sent = true }
        assertTrue(sent)
    }

    @Test fun `monitoring waits for a confirmed request to finish`() = runBlocking {
        val gate = MonitoringNetworkInterlock()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val upload = async {
            gate.withConfirmedUpload(OneShotUploadConsent.fromUserTap("a", 0), "a", { 0 }) { entered.complete(Unit); finish.await() }
        }
        entered.await()
        var started = false
        val monitoring = async { gate.startMonitoring { started = true } }
        assertFalse(started)
        finish.complete(Unit); upload.await(); monitoring.await()
        assertTrue(started)
    }

    @Test fun `HTTP success without provider authentication structure never unlocks`() {
        assertEquals(KeyValidationStatus.INVALID_KEY, ProviderResponseValidator.validate(CloudProvider.GEMINI, 401, "{}"))
        assertEquals(KeyValidationStatus.PERMISSION_DENIED, ProviderResponseValidator.validate(CloudProvider.NVIDIA_NIM, 403, "{}"))
        assertEquals(KeyValidationStatus.QUOTA_OR_RATE_LIMIT, ProviderResponseValidator.validate(CloudProvider.OPENROUTER, 429, "{}"))
        assertEquals(KeyValidationStatus.UNEXPECTED_RESPONSE, ProviderResponseValidator.validate(CloudProvider.OPENROUTER, 200, "{}"))
        assertEquals(KeyValidationStatus.VALID, ProviderResponseValidator.validate(CloudProvider.GEMINI, 200, "{\"models\":[{\"name\":\"configured\"}]}"))
        assertEquals(KeyValidationStatus.VALID, ProviderResponseValidator.validate(CloudProvider.OPENROUTER, 200, "{\"data\":{\"is_management_key\":false,\"label\":\"redacted\"}}"))
        assertEquals(KeyValidationStatus.VALID, ProviderResponseValidator.validate(CloudProvider.NVIDIA_NIM, 200, "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"))
        assertEquals(KeyValidationStatus.UNEXPECTED_RESPONSE, ProviderResponseValidator.validate(CloudProvider.NVIDIA_NIM, 200, "not json"))
    }
}
