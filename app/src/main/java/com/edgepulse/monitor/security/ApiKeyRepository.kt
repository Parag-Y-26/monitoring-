package com.edgepulse.monitor.security

import kotlinx.coroutines.flow.first
import com.edgepulse.core.cloud.CloudProvider
import com.edgepulse.core.cloud.ProviderSetup
import com.edgepulse.core.cloud.CloudModel
import com.edgepulse.core.cloud.ProviderDefaults

/** The cloud adapter validates only after an explicit UI confirmation; this class has no HTTP client. */
fun interface ConfirmedKeyValidator {
    suspend fun validate(key: ByteArray): Boolean
}

data class ProviderConnection(val provider: CloudProvider, val model: String, val models: List<CloudModel>, val fetchedWallMs: Long,
    val needsReconnect: Boolean = false)

class ApiKeyRepository(private val settings: SecureSettingsStore) {
    suspend fun isUnlockedOffline(): Boolean = settings.isUnlockedOffline()
    suspend fun setup(provider: CloudProvider): ProviderSetup? = settings.dataStore.data.first()
        .credentials.singleOrNull { it.setup.provider == provider && it.validated }?.setup

    /** Consumes and clears the caller's buffer even on validation failure/cancellation. */
    suspend fun validateAndStore(setup: ProviderSetup, key: ByteArray, models: List<CloudModel> = emptyList(), validator: ConfirmedKeyValidator): Boolean {
        try {
            setup.validate()
            require(key.size in 1..4096)
            val forValidation = key.copyOf()
            try {
                if (!validator.validate(forValidation)) return false
                settings.dataStore.updateData { state ->
                    require(models.isEmpty() || models.any { it.id == setup.model })
                    state.copy(credentials = state.credentials.filterNot { it.setup.provider == setup.provider } +
                        ProviderCredential(setup, key.copyOf(), true, System.currentTimeMillis(), models,
                            if (models.isEmpty()) 0 else System.currentTimeMillis()), activeCloudProvider = setup.provider)
                }
                return true
            } finally { forValidation.fill(0) }
        } finally {
            key.fill(0)
        }
    }

    suspend fun <T> withApiKey(provider: CloudProvider, block: suspend (ByteArray) -> T): T {
        val current = settings.dataStore.data.first()
        val credential = checkNotNull(current.credentials.singleOrNull { it.setup.provider == provider })
        check(credential.validated && credential.key.isNotEmpty())
        val key = credential.key.copyOf()
        return try { block(key) } finally { key.fill(0) }
    }

    suspend fun clear(provider: CloudProvider) {
        settings.dataStore.updateData { state ->
            val remaining = state.credentials.filterNot { it.setup.provider == provider }
            state.copy(credentials = remaining, activeCloudProvider = if (state.activeCloudProvider == provider)
                remaining.firstOrNull { it.validated }?.setup?.provider else state.activeCloudProvider)
        }
    }

    fun connections(state: SecureSettings): List<ProviderConnection> = state.credentials.filter { it.validated && it.key.isNotEmpty() }
        .map { ProviderConnection(it.setup.provider, it.setup.model, it.models, it.modelsFetchedWallMs,
            !ProviderDefaults.isOfficialOrigin(it.setup.provider, it.setup.validationEndpoint)) }

    suspend fun selectModel(provider: CloudProvider, id: String) {
        settings.dataStore.updateData { state ->
            val credential = checkNotNull(state.credentials.singleOrNull { it.setup.provider == provider && it.validated })
            require(credential.models.any { it.id == id })
            state.copy(credentials = state.credentials.map { if (it.setup.provider == provider) it.copy(setup = it.setup.copy(model = id)) else it },
                activeCloudProvider = provider)
        }
    }

    suspend fun useProvider(provider: CloudProvider) {
        settings.dataStore.updateData { state ->
            require(state.credentials.any { it.setup.provider == provider && it.validated && it.key.isNotEmpty() })
            state.copy(activeCloudProvider = provider)
        }
    }

    suspend fun updateCatalog(provider: CloudProvider, models: List<CloudModel>) {
        require(models.isNotEmpty())
        settings.dataStore.updateData { state -> state.copy(credentials = state.credentials.map { credential ->
            if (credential.setup.provider != provider) credential else credential.copy(models = models, modelsFetchedWallMs = System.currentTimeMillis(),
                setup = credential.setup.copy(model = credential.setup.model.takeIf { id -> models.any { it.id == id } }.orEmpty()))
        }) }
    }
}
