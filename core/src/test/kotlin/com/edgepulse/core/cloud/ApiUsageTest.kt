package com.edgepulse.core.cloud

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class ApiUsageTest {
    private fun gemini(result: String) = buildJsonObject {
        putJsonArray("candidates") { addJsonObject { putJsonObject("content") { putJsonArray("parts") {
            addJsonObject { put("thought", true); put("text", "Private reasoning {invalid}") }
            addJsonObject { put("text", result) }
        } } } }
    }.toString()

    @Test fun `Gemini ignores thoughts and accepts wrapped JSON with array traits`() {
        val item = CharacteristicsParser.parse(CloudProvider.GEMINI,
            gemini("Result: ```json\n{\"name\":\"Bottle\",\"characteristics\":[\"clear\",\"red cap\"],\"matchedCocoClass\":\" BOTTLE \"}\n```"))
        assertEquals("Bottle", item.name)
        assertEquals("clear, red cap", item.characteristics)
        assertEquals("bottle", item.matchedCocoClass)
    }

    @Test fun `object traits and absent or null category are accepted`() {
        for (category in listOf("", ",\"matchedCocoClass\":null", ",\"matchedCocoClass\":\"null\"")) {
            val item = CharacteristicsParser.parse(CloudProvider.GEMINI,
                gemini("{\"name\":\"Box\",\"characteristics\":{\"color\":\"red\",\"shape\":\"square\"}$category}"))
            assertEquals("color: red; shape: square", item.characteristics)
            assertNull(item.matchedCocoClass)
        }
        assertThrows(Exception::class.java) { CharacteristicsParser.parse(CloudProvider.GEMINI, gemini("No JSON")) }
    }
    @Test fun `missing provider usage stays unknown rather than zero`() {
        val usage = UsageParser.tokens(CloudProvider.GEMINI, "{}")
        assertNull(usage.total); assertNull(usage.input); assertNull(usage.output)
        assertNull(UsageParser.tokens(CloudProvider.OPENROUTER, "not JSON").total)
    }
    @Test fun `ledger preserves provider totals including unitemized reasoning tokens`() {
        val gemini = UsageParser.tokens(CloudProvider.GEMINI, """{"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3,"totalTokenCount":20}}""")
        assertEquals(10L, gemini.input); assertEquals(3L, gemini.output); assertEquals(20L, gemini.total)
        val other = UsageParser.tokens(CloudProvider.NVIDIA_NIM, """{"usage":{"prompt_tokens":15,"completion_tokens":5,"total_tokens":20}}""")
        assertEquals(20L, other.total)
    }
    @Test fun `AI traits are parsed as bounded data and never executed`() {
        val body = """{"choices":[{"message":{"content":"{\"name\":\"Bottle\",\"characteristics\":\"Red cap; clear cylindrical body.\"}"}}]}"""
        assertEquals("Bottle", CharacteristicsParser.parse(CloudProvider.OPENROUTER, body).name)
        assertThrows(Exception::class.java) { CharacteristicsParser.parse(CloudProvider.GEMINI, "{}") }
    }
}
