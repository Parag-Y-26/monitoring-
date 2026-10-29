package com.edgepulse.monitor.security

import kotlinx.coroutines.flow.first
import com.edgepulse.core.cloud.CloudProvider
import com.edgepulse.core.cloud.ProviderSetup

/** The cloud adapter validates only after an explicit UI confirmation; this class has no HTTP client. */
fun interface ConfirmedKeyValidator {
    suspend fun validate(key: ByteArray): Boolean
}

class ApiKeyRepository(private val settings: SecureSettingsStore) {
    suspend fun isUnlockedOffline(): Boolean = settings.isUnlockedOffline()
    suspend fun setup(provider: CloudProvider): ProviderSetup? = settings.dataStore.data.first()
        .credentials.singleOrNull { it.setup.provider == provider && it.validated }?.setup

    /** Consumes and clears the caller's buffer even on validation failure/cancellation. */
    suspend fun validateAndStore(setup: ProviderSetup, key: ByteArray, validator: ConfirmedKeyValidator): Boolean {
        try {
            setup.validate()
            require(key.size in 1..4096)
            val forValidation = key.copyOf()
            try {
                if (!validator.validate(forValidation)) return false
                settings.dataStore.updateData { state ->
                    state.copy(credentials = state.credentials.filterNot { it.setup.provider == setup.provider } +
                        ProviderCredential(setup, key.copyOf(), true, System.currentTimeMillis()))
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
        settings.dataStore.updateData { state -> state.copy(credentials = state.credentials.filterNot { it.setup.provider == provider }) }
    }
}
