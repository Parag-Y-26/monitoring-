package com.edgepulse.core.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ApiUsage(val id: String, val wallMs: Long, val provider: CloudProvider, val purpose: String, val model: String = "",
    val httpStatus: Int? = null, val outcome: String, val images: Int = 0, val inputTokens: Long? = null,
    val outputTokens: Long? = null, val totalTokens: Long? = null)

data class TokenUsage(val input: Long?, val output: Long?, val total: Long?)
object UsageParser {
    fun tokens(provider: CloudProvider, body: String): TokenUsage = try {
        val root = Json.parseToJsonElement(body).jsonObject
        val usage = root[if (provider == CloudProvider.GEMINI) "usageMetadata" else "usage"]?.jsonObject
        fun value(key: String) = usage?.get(key)?.jsonPrimitive?.longOrNull?.takeIf { it >= 0 }
        if (provider == CloudProvider.GEMINI) TokenUsage(value("promptTokenCount"), value("candidatesTokenCount"), value("totalTokenCount"))
        else TokenUsage(value("prompt_tokens"), value("completion_tokens"), value("total_tokens"))
    } catch (_: Exception) { TokenUsage(null, null, null) }
}

data class ItemCharacteristics(val name: String, val characteristics: String, val matchedCocoClass: String? = null)
object CharacteristicsParser {
    fun parse(provider: CloudProvider, body: String): ItemCharacteristics {
        val root = Json.parseToJsonElement(body).jsonObject
        val text = if (provider == CloudProvider.GEMINI) root.getValue("candidates").jsonArray.first().jsonObject
            .getValue("content").jsonObject.getValue("parts").jsonArray
            .filterNot { it.jsonObject["thought"]?.jsonPrimitive?.booleanOrNull == true }
            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("")
        else root.getValue("choices").jsonArray.first().jsonObject.getValue("message").jsonObject.getValue("content").jsonPrimitive.content
        require(text.length <= 16_000)
        val start = text.indexOf('{'); val end = text.lastIndexOf('}')
        require(start >= 0 && end > start)
        val result = Json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        val name = result.getValue("name").jsonPrimitive.content.filterNot(Char::isISOControl).trim().take(80)
        val traits = when (val value = result.getValue("characteristics")) {
            is JsonPrimitive -> value.content
            is JsonArray -> value.joinToString(", ") { it.jsonPrimitive.content }
            is JsonObject -> value.entries.joinToString("; ") { "${it.key}: ${it.value.jsonPrimitive.content}" }
        }.trim().take(4000)
        val category = result["matchedCocoClass"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.lowercase(java.util.Locale.ROOT)?.takeIf { it.isNotEmpty() && it != "null" }?.take(80)
        require(name.isNotBlank() && traits.isNotBlank())
        return ItemCharacteristics(name, traits, category)
    }
}
