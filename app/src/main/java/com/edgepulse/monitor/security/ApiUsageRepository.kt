package com.edgepulse.monitor.security

import com.edgepulse.core.cloud.*
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class ApiUsageRepository @Inject constructor(private val settings: SecureSettingsStore) {
    suspend fun record(provider: CloudProvider, purpose: String, model: String, images: Int, response: CloudResponse?, outcome: String) {
        val usage = response?.let { UsageParser.tokens(provider, it.body) }
        val entry = ApiUsage(UUID.randomUUID().toString(), System.currentTimeMillis(), provider, purpose, model,
            response?.statusCode, outcome, images, usage?.input, usage?.output, usage?.total)
        settings.dataStore.updateData { saved ->
            val previous = Json.decodeFromString<List<ApiUsage>>(saved.apiUsageJson)
            saved.copy(apiUsageJson = Json.encodeToString(previous.takeLast(499) + entry))
        }
    }
}
