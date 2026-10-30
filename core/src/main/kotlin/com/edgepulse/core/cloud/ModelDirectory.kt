package com.edgepulse.core.cloud

import java.net.URLEncoder
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

data class ProviderProfile(val name: String, val modelsEndpoint: String, val validationEndpoint: String)

/** Public provider configuration, allowed by the key-only setup amendment. Never contains credentials. */
object ProviderDefaults {
    fun profile(provider: CloudProvider): ProviderProfile = when (provider) {
        CloudProvider.GEMINI -> ProviderProfile("Gemini", "https://generativelanguage.googleapis.com/v1beta/models", "https://generativelanguage.googleapis.com/v1beta/models")
        CloudProvider.OPENROUTER -> ProviderProfile("OpenRouter", "https://openrouter.ai/api/v1/models", "https://openrouter.ai/api/v1/key")
        CloudProvider.NVIDIA_NIM -> ProviderProfile("NVIDIA NIM", "https://integrate.api.nvidia.com/v1/models", "https://integrate.api.nvidia.com/v1/chat/completions")
    }

    fun setup(provider: CloudProvider, model: String = "") = ProviderSetup(provider, profile(provider).validationEndpoint, model)
    fun discoveryContext(provider: CloudProvider) = "EdgePulse/model-discovery/v1/${provider.name}"
    fun isOfficialOrigin(provider: CloudProvider, endpoint: String): Boolean = try {
        val uri = URI(endpoint)
        uri.scheme == "https" && uri.host == URI(profile(provider).validationEndpoint).host &&
            uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443)
    } catch (_: Exception) { false }
    fun detect(key: String): CloudProvider? = when {
        key.trim().startsWith("AIza") -> CloudProvider.GEMINI
        key.trim().startsWith("sk-or-v1-") -> CloudProvider.OPENROUTER
        key.trim().startsWith("nvapi-") -> CloudProvider.NVIDIA_NIM
        else -> null
    }
}

@Serializable
data class CloudModel(val id: String, val name: String, val supportsImages: Boolean? = null, val contextTokens: Long? = null)

data class CloudResponse(val statusCode: Int, val body: String)
data class ModelDiscovery(val models: List<CloudModel>, val keyAuthenticated: Boolean)
class CatalogException(val status: KeyValidationStatus) : IllegalStateException(status.name)

/** Discovers returned IDs only. Gemini's documented multimodal family supplies missing modality metadata. */
object ModelDirectory {
    suspend fun load(provider: CloudProvider, fetch: suspend (String) -> CloudResponse): ModelDiscovery {
        try {
            val profile = ProviderDefaults.profile(provider)
            if (provider == CloudProvider.OPENROUTER) {
                val key = fetch(profile.validationEndpoint)
                val status = ProviderResponseValidator.validate(provider, key.statusCode, key.body)
                if (status != KeyValidationStatus.VALID) throw CatalogException(status)
            }
            val models = linkedMapOf<String, CloudModel>()
            val tokens = mutableSetOf<String>()
            var next: String? = null
            var pages = 0
            do {
                if (++pages > 10) throw CatalogException(KeyValidationStatus.UNEXPECTED_RESPONSE)
                val endpoint = if (provider == CloudProvider.GEMINI) profile.modelsEndpoint + "?pageSize=1000" +
                    (next?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "") else profile.modelsEndpoint
                val response = fetch(endpoint)
                if (response.statusCode != 200) throw CatalogException(ProviderResponseValidator.validate(provider, response.statusCode, response.body))
                val root = Json.parseToJsonElement(response.body).jsonObject
                val items = root.getValue(if (provider == CloudProvider.GEMINI) "models" else "data").jsonArray
                require(items.size <= 5000)
                for (item in items) {
                    val value = item.jsonObject
                    if (provider == CloudProvider.GEMINI && value["supportedGenerationMethods"]?.jsonArray
                            ?.none { it.jsonPrimitive.content == "generateContent" } != false) continue
                    val rawId = value.getValue(if (provider == CloudProvider.GEMINI) "name" else "id").jsonPrimitive
                    require(rawId.isString)
                    val id = rawId.content
                    require(id.length in 1..256 && id.none { it.isISOControl() || it.isWhitespace() })
                    val label = value[if (provider == CloudProvider.GEMINI) "displayName" else "name"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }?.filterNot { it.isISOControl() }?.take(160) ?: id
                    val modalities = value["architecture"]?.jsonObject?.get("input_modalities")?.jsonArray
                    val images = modalities?.any { it.jsonPrimitive.content == "image" }
                        ?: if (provider == CloudProvider.GEMINI) id.startsWith("models/gemini-") &&
                            !id.contains("tts") && !id.contains("embedding") else null
                    val context = value[if (provider == CloudProvider.GEMINI) "inputTokenLimit" else "context_length"]
                        ?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
                    models.putIfAbsent(id, CloudModel(id, label, images, context))
                    require(models.size <= 5000)
                }
                next = if (provider == CloudProvider.GEMINI) root["nextPageToken"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) else null
                if (next != null) require(next.length <= 2048 && tokens.add(next))
            } while (next != null)
            if (models.isEmpty()) throw CatalogException(KeyValidationStatus.NO_MODELS)
            return ModelDiscovery(models.values.sortedWith(compareBy({ it.name.lowercase(Locale.ROOT) }, { it.id })), provider != CloudProvider.NVIDIA_NIM)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CatalogException) { throw failure }
        catch (_: Exception) { throw CatalogException(KeyValidationStatus.UNEXPECTED_RESPONSE) }
    }

    fun initialSelection(models: List<CloudModel>, saved: String?): String? =
        models.firstOrNull { it.id == saved }?.id ?:
            models.firstOrNull { it.supportsImages == true && it.id.contains("flash", ignoreCase = true) }?.id ?:
            models.firstOrNull { it.supportsImages == true }?.id ?: models.firstOrNull()?.id
}
