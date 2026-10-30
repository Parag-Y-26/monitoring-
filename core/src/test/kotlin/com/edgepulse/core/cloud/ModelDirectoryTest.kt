package com.edgepulse.core.cloud

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModelDirectoryTest {
    @Test fun `key prefixes suggest one provider without making requests`() {
        assertEquals(CloudProvider.GEMINI, ProviderDefaults.detect("AIza-fixture"))
        assertEquals(CloudProvider.OPENROUTER, ProviderDefaults.detect("sk-or-v1-fixture"))
        assertEquals(CloudProvider.NVIDIA_NIM, ProviderDefaults.detect("nvapi-fixture"))
        assertNull(ProviderDefaults.detect("unknown-format"))
        for (provider in CloudProvider.entries) {
            val profile = ProviderDefaults.profile(provider)
            assertTrue(profile.modelsEndpoint.startsWith("https://"))
            ProviderDefaults.setup(provider, "selected-model").validate()
            assertFalse(ProviderDefaults.discoveryContext(provider).isBlank())
        }
    }

    @Test fun `Gemini discovers real returned generation models and follows pagination`() = runBlocking {
        val visited = mutableListOf<String>()
        val result = ModelDirectory.load(CloudProvider.GEMINI) { endpoint ->
            visited += endpoint
            if (visited.size == 1) CloudResponse(200, """{"models":[{"name":"models/z","displayName":"Zeta","inputTokenLimit":100,"supportedGenerationMethods":["generateContent"]},{"name":"models/embed","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"next page"}""")
            else CloudResponse(200, """{"models":[{"name":"models/a","displayName":"Alpha","supportedGenerationMethods":["generateContent"]},{"name":"models/z","supportedGenerationMethods":["generateContent"]}]}""")
        }
        assertTrue(result.keyAuthenticated)
        assertEquals(listOf("models/a", "models/z"), result.models.map { it.id })
        assertTrue(visited[1].contains("pageToken=next+page"))
        assertEquals(100L, result.models.last().contextTokens)
        assertNull(result.models.first().supportsImages)
    }

    @Test fun `OpenRouter checks key before reading catalog and preserves capabilities`() = runBlocking {
        val visited = mutableListOf<String>()
        val result = ModelDirectory.load(CloudProvider.OPENROUTER) { endpoint ->
            visited += endpoint
            if (endpoint.endsWith("/key")) CloudResponse(200, """{"data":{"label":"fixture","is_management_key":false}}""")
            else CloudResponse(200, """{"data":[{"id":"owner/vision","name":"Vision","context_length":2000,"architecture":{"input_modalities":["text","image"],"output_modalities":["text"]}},{"id":"owner/text","architecture":{"input_modalities":["text"],"output_modalities":["text"]}}]}""")
        }
        assertTrue(visited.first().endsWith("/key"))
        assertTrue(result.keyAuthenticated)
        assertEquals(true, result.models.single { it.id == "owner/vision" }.supportsImages)
        assertEquals(false, result.models.single { it.id == "owner/text" }.supportsImages)
        assertEquals(2000L, result.models.single { it.id == "owner/vision" }.contextTokens)
    }

    @Test fun `NVIDIA public catalog never establishes key authentication`() = runBlocking {
        val result = ModelDirectory.load(CloudProvider.NVIDIA_NIM) {
            CloudResponse(200, """{"data":[{"id":"owner/returned-model"}]}""")
        }
        assertFalse(result.keyAuthenticated)
        assertEquals("owner/returned-model", result.models.single().name)
        assertNull(result.models.single().supportsImages)
    }

    @Test fun `authentication failure stops catalog fetching`() = runBlocking {
        var calls = 0
        try {
            ModelDirectory.load(CloudProvider.OPENROUTER) { calls++; CloudResponse(401, "{}") }
            fail("Must reject invalid key")
        } catch (failure: CatalogException) { assertEquals(KeyValidationStatus.INVALID_KEY, failure.status) }
        assertEquals(1, calls)
        Unit
    }

    @Test fun `bad or empty responses do not create invented choices`() = runBlocking {
        for (body in listOf("{}", "not JSON", "{\"data\":[]}", "{\"data\":[{\"id\":\"bad\\nmodel\"}]}")) {
            try {
                ModelDirectory.load(CloudProvider.NVIDIA_NIM) { CloudResponse(200, body) }
                fail("Must reject invalid or empty catalog")
            } catch (_: CatalogException) { }
        }
    }

    @Test fun `pagination cycles and oversized catalogs fail explicitly`() = runBlocking {
        try {
            ModelDirectory.load(CloudProvider.GEMINI) {
                CloudResponse(200, """{"models":[{"name":"models/a","supportedGenerationMethods":["generateContent"]}],"nextPageToken":"cycle"}""")
            }
            fail("Must reject pagination cycle")
        } catch (failure: CatalogException) { assertEquals(KeyValidationStatus.UNEXPECTED_RESPONSE, failure.status) }
        val oversized = "{\"data\":[" + (1..5001).joinToString(",") { "{\"id\":\"model-$it\"}" } + "]}"
        try {
            ModelDirectory.load(CloudProvider.NVIDIA_NIM) { CloudResponse(200, oversized) }
            fail("Must bound metadata")
        } catch (_: CatalogException) { }
    }

    @Test fun `model selection uses only discovered IDs and preserves a saved choice`() {
        val models = listOf(CloudModel("text", "Text", supportsImages = false), CloudModel("vision", "Vision", supportsImages = true))
        assertEquals("text", ModelDirectory.initialSelection(models, "text"))
        assertEquals("vision", ModelDirectory.initialSelection(models, "removed"))
        assertNull(ModelDirectory.initialSelection(emptyList(), null))
        assertEquals("text", ModelDirectory.initialSelection(listOf(models.first()), null))
    }

    @Test fun `saved proxy credentials are never treated as official provider credentials`() {
        val provider = CloudProvider.GEMINI
        assertTrue(ProviderDefaults.isOfficialOrigin(provider, ProviderDefaults.profile(provider).validationEndpoint))
        for (endpoint in listOf("https://example.invalid/models", "http://generativelanguage.googleapis.com/models",
            "https://generativelanguage.googleapis.com.example.invalid/models", "not a URI",
            "https://generativelanguage.googleapis.com:444/models", "https://user@generativelanguage.googleapis.com/models")) {
            assertFalse(ProviderDefaults.isOfficialOrigin(provider, endpoint))
        }
    }
}
