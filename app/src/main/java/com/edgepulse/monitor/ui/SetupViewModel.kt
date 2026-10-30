package com.edgepulse.monitor.ui

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edgepulse.core.audit.AuditCanonical
import com.edgepulse.core.cloud.*
import com.edgepulse.core.vision.ItemProfile
import com.edgepulse.monitor.data.local.EdgePulseDatabase
import com.edgepulse.monitor.data.remote.CloudAnalysisService
import com.edgepulse.monitor.security.*
import com.edgepulse.monitor.vision.ml.BenchmarkReport
import com.edgepulse.monitor.vision.ml.DelegateBenchmarkHarness
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Base64
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json

data class SetupUiState(
    val storageReady: Boolean = false,
    val storageError: Boolean = false,
    val provider: CloudProvider = CloudProvider.GEMINI,
    val connections: List<ProviderConnection> = emptyList(),
    val models: List<CloudModel> = emptyList(),
    val selectedModel: String? = null,
    val previewCatalog: Boolean = false,
    val verificationNeeded: Boolean = false,
    val pendingKeyAvailable: Boolean = false,
    val retryConnectionAvailable: Boolean = false,
    val cloudMessage: String? = null,
    val cloudError: Boolean = false,
    val identityReady: Boolean = false,
    val identityMismatch: Boolean = false,
    val benchmark: BenchmarkReport? = null,
    val folderName: String? = null,
    val activeSessionId: String? = null,
    val itemProfiles: List<ItemProfile> = emptyList(),
    val selectedItemId: String = "",
    val busy: String? = null,
    val message: String? = null,
) {
    val connection get() = connections.singleOrNull { it.provider == provider }
    val cloudReady get() = connections.any { it.model.isNotBlank() && !it.needsReconnect }
    val deviceReady get() = identityReady && benchmark?.constructionEligible == true
    val completedSteps get() = listOf(cloudReady, deviceReady, folderName != null).count { it }
    val selectedTarget get() = itemProfiles.singleOrNull { it.id == selectedItemId }
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SecureSettingsStore,
    private val signer: KeystoreAuditSigner,
    private val repository: ApiKeyRepository,
    private val cloud: CloudAnalysisService,
    private val checkpoints: DailyCheckpointCoordinator,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SetupUiState())
    val state = mutableState.asStateFlow()
    private var database: EdgePulseDatabase? = null
    private var operation: Job? = null
    private var pendingKey: ByteArray? = null
    private var preferencesLoaded = false
    private var providerChosen = false

    init {
        viewModelScope.launch {
            try {
                database = EdgePulseDatabase.open(context, settings)
                val cached = DelegateBenchmarkHarness(context, settings).readCachedReport()
                mutableState.update { it.copy(benchmark = cached) }
                settings.dataStore.data.collect { saved ->
                    val connections = repository.connections(saved)
                    val provider = if (!preferencesLoaded && !providerChosen) saved.activeCloudProvider
                        ?: connections.firstOrNull()?.provider ?: state.value.provider else state.value.provider
                    preferencesLoaded = true
                    val connection = connections.singleOrNull { it.provider == provider }
                    val enrolled = withContext(Dispatchers.IO) { signer.isEnrolled() }
                    val identity = enrolled && saved.trustedAuditPublicKey == signer.publicKeySpkiBase64
                    val folder = localFolderName(saved.checkpointTreeUri)
                    mutableState.update { old -> old.copy(storageReady = true, provider = provider, connections = connections,
                        models = if (old.previewCatalog) old.models else connection?.models.orEmpty(),
                        selectedModel = if (old.previewCatalog) old.selectedModel else connection?.model?.takeIf(String::isNotBlank),
                        identityReady = identity, identityMismatch = enrolled && !identity, folderName = folder, activeSessionId = saved.activeSessionId,
                        itemProfiles = saved.itemProfiles, selectedItemId = saved.selectedItemId) }
                    if (folder != null && identity) DailyCheckpointWorker.schedule(context)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(storageError = true, storageReady = false) } }
        }
    }

    fun chooseProvider(provider: CloudProvider) {
        if (state.value.busy != null) return
        providerChosen = true
        if (state.value.provider == provider) return
        clearPending()
        val connection = state.value.connections.singleOrNull { it.provider == provider }
        mutableState.update { it.copy(provider = provider, models = connection?.models.orEmpty(), selectedModel = connection?.model,
            previewCatalog = false, verificationNeeded = false, pendingKeyAvailable = false, retryConnectionAvailable = false, cloudMessage = null, cloudError = false) }
        if (connection != null) viewModelScope.launch { repository.useProvider(provider) }
    }

    fun connect(key: ByteArray, consent: OneShotUploadConsent) {
        if (state.value.busy != null || !state.value.storageReady) { key.fill(0); return }
        val provider = state.value.provider
        clearPending()
        mutableState.update { it.copy(retryConnectionAvailable = false) }
        action("Connecting to ${ProviderDefaults.profile(provider).name}…", cloudAction = true) {
            var retained = false
            try {
                val result = cloud.fetchModels(provider, key, consent)
                currentCoroutineContext().ensureActive()
                val selected = checkNotNull(ModelDirectory.initialSelection(result.models, state.value.connection?.model))
                if (result.keyAuthenticated) {
                    repository.validateAndStore(ProviderDefaults.setup(provider, selected), key, result.models) { result.keyAuthenticated }
                    mutableState.update { it.copy(previewCatalog = false, verificationNeeded = false,
                        models = result.models, selectedModel = selected, cloudMessage = "Connected. Models are saved for offline use.", cloudError = false) }
                } else {
                    pendingKey = key
                    retained = true
                    mutableState.update { it.copy(models = result.models, selectedModel = selected, previewCatalog = true,
                        verificationNeeded = true, pendingKeyAvailable = true, cloudMessage = "Models loaded. Verify the model you want to use.", cloudError = false) }
                }
            } catch (failure: CatalogException) {
                if (failure.status in setOf(KeyValidationStatus.NETWORK_ERROR, KeyValidationStatus.PROVIDER_UNAVAILABLE, KeyValidationStatus.QUOTA_OR_RATE_LIMIT)) {
                    pendingKey = key; retained = true
                    mutableState.update { it.copy(retryConnectionAvailable = true) }
                }
                throw failure
            } finally { if (!retained) key.fill(0) }
        }
    }

    fun retryConnection(consent: OneShotUploadConsent) {
        if (state.value.busy != null || !state.value.retryConnectionAvailable) return
        val key = pendingKey?.copyOf() ?: return
        connect(key, consent)
    }

    fun refreshModels(consent: OneShotUploadConsent) {
        if (state.value.previewCatalog) return
        if (state.value.connection?.needsReconnect == true) { reconnectRequired(); return }
        val provider = state.value.provider
        action("Refreshing models…", cloudAction = true) {
            val result = repository.withApiKey(provider) { cloud.fetchModels(provider, it, consent) }
            repository.updateCatalog(provider, result.models)
            mutableState.update { it.copy(previewCatalog = false, verificationNeeded = false,
                cloudMessage = "Model list refreshed. Your saved list also works offline.", cloudError = false) }
        }
    }

    fun chooseModel(id: String) {
        if (state.value.busy != null || state.value.models.none { it.id == id }) return
        if (state.value.provider == CloudProvider.NVIDIA_NIM) {
            if (pendingKey == null && id == state.value.connection?.model) {
                mutableState.update { it.copy(selectedModel = id, previewCatalog = false, verificationNeeded = false) }
                return
            }
            mutableState.update { it.copy(selectedModel = id, previewCatalog = true, verificationNeeded = true, cloudError = false,
                cloudMessage = "Verify this model before saving it.") }
        } else action("Saving model…", cloudAction = true) {
            repository.selectModel(state.value.provider, id)
            mutableState.update { it.copy(cloudMessage = "Model saved. No network request was needed.", cloudError = false) }
        }
    }

    fun verifyModel(consent: OneShotUploadConsent) {
        val snapshot = state.value
        if (pendingKey == null && snapshot.connection?.needsReconnect == true) { reconnectRequired(); return }
        val id = snapshot.selectedModel ?: return
        if (snapshot.models.none { it.id == id }) return
        action("Verifying model access…", cloudAction = true) {
            val key = pendingKey?.copyOf() ?: repository.withApiKey(snapshot.provider) { it.copyOf() }
            var status = KeyValidationStatus.UNEXPECTED_RESPONSE
            val setup = ProviderDefaults.setup(snapshot.provider, id)
            val saved = repository.validateAndStore(setup, key, snapshot.models) { candidate ->
                status = cloud.validateKey(setup, candidate, consent)
                status == KeyValidationStatus.VALID
            }
            if (saved) {
                clearPending()
                mutableState.update { it.copy(previewCatalog = false, verificationNeeded = false, pendingKeyAvailable = false,
                    cloudMessage = "Model verified and saved. You're connected.", cloudError = false) }
            } else cloudFailure(status)
        }
    }

    fun disconnect() {
        val provider = state.value.provider
        action("Removing saved connection…", cloudAction = true) {
            clearPending()
            repository.clear(provider)
            mutableState.update { it.copy(models = emptyList(), selectedModel = null, previewCatalog = false,
                verificationNeeded = false, pendingKeyAvailable = false, retryConnectionAvailable = false, cloudMessage = "Connection removed from this phone.", cloudError = false) }
        }
    }

    fun prepareDevice(challengeText: String = "", force: Boolean = false) = action("Preparing this phone…") {
        check(!state.value.identityMismatch) { "Audit identity needs review" }
        ensureIdentity(challengeText)
        val report = DelegateBenchmarkHarness(context, settings).run(force) { progress ->
            mutableState.update { it.copy(busy = progress) }
        }
        mutableState.update { it.copy(benchmark = report, message = if (report.constructionEligible)
            "Device check complete. Your phone passed the speed check." else "Device check complete. This phone has not passed the construction speed check.") }
    }

    fun acceptFolder(uri: Uri) {
        if (!state.value.storageReady && !state.value.storageError) {
            viewModelScope.launch {
                state.first { it.storageReady || it.storageError }
                if (state.value.storageReady) acceptFolder(uri)
            }
            return
        }
        action("Saving your report folder…") {
            LocalSafFolder.requireLocalTree(uri)
            check(state.value.identityReady) { "Prepare the phone first" }
            val saved = settings.dataStore.updateData { it.copy(checkpointTreeUri = uri.toString()) }
            DailyCheckpointExporter(context.contentResolver).exportIdentity(uri, signer, saved)
            DailyCheckpointWorker.schedule(context)
            mutableState.update { it.copy(message = "Report folder saved. Public audit identity exported automatically.") }
        }
    }

    fun exportAudit() = action("Exporting audit…") {
        val message = when (checkpoints.exportNow()) {
            "EXPORTED" -> "Audit exported to your saved folder."
            "NO_HISTORY" -> "There are no signed events to export yet."
            "FOLDER_REQUIRED" -> "Choose a report folder first."
            "FOLDER_ACCESS_LOST" -> "Folder access expired. Choose your local folder again."
            else -> "Export couldn't finish. Check folder access and audit integrity."
        }
        mutableState.update { it.copy(message = message) }
    }

    fun exportBenchmark() = action("Exporting device check…") {
        val saved = settings.dataStore.data.first()
        val report = DelegateBenchmarkHarness(context, settings).readCachedReport()
        if (saved.checkpointTreeUri.isEmpty()) mutableState.update { it.copy(message = "Choose a report folder first.") }
        else if (report == null) mutableState.update { it.copy(message = "Run the device check first.") }
        else {
            DailyCheckpointExporter(context.contentResolver).exportBenchmark(Uri.parse(saved.checkpointTreeUri), report)
            mutableState.update { it.copy(message = "Device check exported to ${localFolderName(saved.checkpointTreeUri) ?: "your report folder"}.") }
        }
    }

    fun clearMessage() { mutableState.update { it.copy(message = null) } }
    fun showMessage(value: String) { mutableState.update { it.copy(message = value) } }
    fun cancel() { operation?.cancel() }

    private suspend fun ensureIdentity(challengeText: String) {
        if (signer.isEnrolled()) { check(state.value.identityReady) { "Audit identity needs review" }; return }
        val nonce = challengeText.trim().takeIf(String::isNotEmpty)?.let { Base64.getDecoder().decode(it) }
        val digest = nonce?.let(AuditCanonical::hash) ?: byteArrayOf()
        try { withContext(Dispatchers.IO) { signer.enroll(nonce) } } finally { nonce?.fill(0) }
        settings.dataStore.updateData { it.copy(trustedAuditPublicKey = signer.publicKeySpkiBase64,
            attestationNonceMode = if (nonce == null) "NONE" else "VERIFIER_SUPPLIED", verifierChallengeSha256 = digest) }
        mutableState.update { it.copy(identityReady = true) }
    }

    private fun localFolderName(value: String): String? = try {
        if (value.isBlank()) null else {
            val uri = Uri.parse(value)
            LocalSafFolder.requireLocalTree(uri)
            if (context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isWritePermission }) null
            else DocumentsContract.getTreeDocumentId(uri).substringAfter(':').substringAfterLast('/').ifBlank { "Local storage" }
        }
    } catch (_: Exception) { null }

    private fun action(label: String, cloudAction: Boolean = false, block: suspend () -> Unit) {
        if (state.value.busy != null || !state.value.storageReady) return
        mutableState.update { it.copy(busy = label) }
        operation = viewModelScope.launch {
            try { block() }
            catch (_: TimeoutCancellationException) { if (cloudAction) cloudFailure(KeyValidationStatus.NETWORK_ERROR) }
            catch (cancelled: CancellationException) {
                mutableState.update { if (cloudAction) it.copy(cloudMessage = "Cancelled. Your saved connection hasn't been removed.") else it.copy(message = "Cancelled.") }
                throw cancelled
            }
            catch (failure: CatalogException) { cloudFailure(failure.status) }
            catch (_: MonitoringActiveException) { mutableState.update { it.copy(cloudMessage = "Stop monitoring before connecting or refreshing models.", cloudError = true) } }
            catch (_: Exception) {
                mutableState.update { if (cloudAction) it.copy(cloudMessage = "Couldn't finish. Check your key or connection and try again.", cloudError = true)
                    else it.copy(message = "Couldn't finish. Check device storage, folder access or audit identity.") }
            }
            finally { mutableState.update { it.copy(busy = null) } }
        }
    }

    private fun cloudFailure(status: KeyValidationStatus) {
        val message = when (status) {
            KeyValidationStatus.INVALID_KEY -> "This key was rejected. Paste a new key and reconnect."
            KeyValidationStatus.PERMISSION_DENIED -> "This key can't access the service or model. Check access or choose another model."
            KeyValidationStatus.QUOTA_OR_RATE_LIMIT -> "Provider quota or rate limit reached. Check credits or retry later."
            KeyValidationStatus.PROVIDER_UNAVAILABLE -> "The provider is unavailable. Try again later."
            KeyValidationStatus.NETWORK_ERROR -> "Couldn't connect. Check your internet connection and retry. Saved models remain available."
            KeyValidationStatus.NO_MODELS -> "The provider returned no usable models for this connection."
            KeyValidationStatus.CONFIGURATION_ERROR -> "This model couldn't pass the short connection check. Choose another model."
            else -> "The provider returned an unexpected result. Try refreshing or reconnecting."
        }
        mutableState.update { it.copy(cloudMessage = message, cloudError = true) }
    }

    private fun clearPending() { pendingKey?.fill(0); pendingKey = null }
    private fun reconnectRequired() { mutableState.update { it.copy(cloudError = true,
        cloudMessage = "This saved connection used a custom service. Paste a key again to use automatic provider setup. Its saved key hasn't been sent to a new service.") } }
    override fun onCleared() { clearPending(); database?.close(); super.onCleared() }
}
