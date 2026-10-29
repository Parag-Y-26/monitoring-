package com.edgepulse.monitor.security

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.tink.AeadSerializer
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import com.edgepulse.monitor.proto.SecureSettingsProto
import com.edgepulse.monitor.proto.ProviderCredentialProto
import com.edgepulse.core.cloud.CloudProvider
import com.edgepulse.core.cloud.ProviderSetup
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

@Serializable
data class ProviderCredential(
    val setup: ProviderSetup,
    val key: ByteArray,
    val validated: Boolean,
    val validatedWallMs: Long,
)

@Serializable
data class SecureSettings(
    val sqlCipherPassphrase: ByteArray = byteArrayOf(),
    val trustedAuditPublicKey: String = "",
    val activeSessionId: String? = null,
    val lastHeartbeatElapsedMs: Long = 0,
    val lastHeartbeatWallMs: Long = 0,
    val bootId: String = "",
    val benchmarkCache: String = "",
    val credentials: List<ProviderCredential> = emptyList(),
    val checkpointTreeUri: String = "",
    val attestationNonceMode: String = "NONE",
    val verifierChallengeSha256: ByteArray = byteArrayOf(),
    val lastCheckpointStatus: String = "NOT_EXPORTED",
    val lastCheckpointWallMs: Long = 0,
)

class SecureSettingsStore(context: Context, keys: TinkKeyManager) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dataStore: DataStore<SecureSettings> = DataStoreFactory.create(
        serializer = AeadSerializer(keys.aead, SettingsSerializer, "EdgePulse/secure-settings/v1".toByteArray()),
        scope = scope,
        produceFile = { File(context.noBackupFilesDir, "secure-settings.enc") },
    )

    /** Returned copy belongs to the caller and must be zero-filled after opening SQLCipher. */
    suspend fun databasePassphrase(): ByteArray {
        val state = dataStore.updateData { current ->
            if (current.sqlCipherPassphrase.isNotEmpty()) current
            else current.copy(sqlCipherPassphrase = ByteArray(32).also(SecureRandom()::nextBytes))
        }
        check(state.sqlCipherPassphrase.size == 32)
        return state.sqlCipherPassphrase.copyOf()
    }

    suspend fun isUnlockedOffline(): Boolean = dataStore.data.first().let {
        it.credentials.any { credential -> credential.validated && credential.key.isNotEmpty() }
    }

    suspend fun close() { scope.coroutineContext[Job]?.cancelAndJoin() }

    private object SettingsSerializer : Serializer<SecureSettings> {
        override val defaultValue = SecureSettings()
        override suspend fun readFrom(input: InputStream): SecureSettings {
            return try {
                val value = SecureSettingsProto.parseFrom(input)
                SecureSettings(
                    sqlCipherPassphrase = value.sqlCipherPassphrase.toByteArray(),
                    trustedAuditPublicKey = value.trustedAuditPublicKey,
                    activeSessionId = value.activeSessionId.takeIf { it.isNotEmpty() },
                    lastHeartbeatElapsedMs = value.lastHeartbeatElapsedMs,
                    lastHeartbeatWallMs = value.lastHeartbeatWallMs,
                    bootId = value.bootId, benchmarkCache = value.benchmarkCache,
                    credentials = value.credentialsList.map { credential ->
                        ProviderCredential(ProviderSetup(CloudProvider.valueOf(credential.provider), credential.validationEndpoint, credential.model),
                            credential.apiKey.toByteArray(), credential.validated, credential.validatedWallMs)
                    },
                    checkpointTreeUri = value.checkpointTreeUri,
                    attestationNonceMode = value.attestationNonceMode.ifEmpty { "NONE" },
                    verifierChallengeSha256 = value.verifierChallengeSha256.toByteArray(),
                    lastCheckpointStatus = value.lastCheckpointStatus.ifEmpty { "NOT_EXPORTED" },
                    lastCheckpointWallMs = value.lastCheckpointWallMs,
                )
            } catch (failure: InvalidProtocolBufferException) {
                throw CorruptionException("Invalid encrypted settings", failure)
            }
        }
        override suspend fun writeTo(t: SecureSettings, output: OutputStream) {
            val value = SecureSettingsProto.newBuilder()
                .setSqlCipherPassphrase(ByteString.copyFrom(t.sqlCipherPassphrase))
                .setTrustedAuditPublicKey(t.trustedAuditPublicKey)
                .setActiveSessionId(t.activeSessionId.orEmpty())
                .setLastHeartbeatElapsedMs(t.lastHeartbeatElapsedMs)
                .setLastHeartbeatWallMs(t.lastHeartbeatWallMs)
                .setBootId(t.bootId).setBenchmarkCache(t.benchmarkCache)
                .addAllCredentials(t.credentials.map { credential ->
                    ProviderCredentialProto.newBuilder().setProvider(credential.setup.provider.name)
                        .setApiKey(ByteString.copyFrom(credential.key))
                        .setValidationEndpoint(credential.setup.validationEndpoint).setModel(credential.setup.model)
                        .setValidated(credential.validated).setValidatedWallMs(credential.validatedWallMs).build()
                }).setCheckpointTreeUri(t.checkpointTreeUri)
                .setAttestationNonceMode(t.attestationNonceMode)
                .setVerifierChallengeSha256(ByteString.copyFrom(t.verifierChallengeSha256))
                .setLastCheckpointStatus(t.lastCheckpointStatus).setLastCheckpointWallMs(t.lastCheckpointWallMs).build()
            value.writeTo(output)
        }
    }
}
