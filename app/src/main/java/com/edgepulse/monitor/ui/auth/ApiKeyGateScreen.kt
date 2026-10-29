package com.edgepulse.monitor.ui.auth

import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.edgepulse.core.cloud.*
import com.edgepulse.monitor.data.remote.CloudAnalysisService
import com.edgepulse.monitor.security.ApiKeyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ApiKeyGateScreen(repository: ApiKeyRepository, cloud: CloudAnalysisService, onUnlocked: () -> Unit) {
    var provider by remember { mutableStateOf(CloudProvider.GEMINI) }
    val selected = provider
    var endpoint by remember(selected) { mutableStateOf("") }
    var model by remember(selected) { mutableStateOf("") }
    var keyText by remember(selected) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember(selected) { mutableStateOf("Enter a key and its validation endpoint.") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(selected) {
        repository.setup(selected)?.let { setup ->
            endpoint = setup.validationEndpoint; model = setup.model
            status = "Saved validation · available offline."
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Cloud key setup", style = MaterialTheme.typography.titleLarge)
        Text("Choose a provider. Each key is stored separately in encrypted storage.", style = MaterialTheme.typography.bodySmall)
        for ((id, label) in listOf(CloudProvider.GEMINI to "Gemini", CloudProvider.OPENROUTER to "OpenRouter", CloudProvider.NVIDIA_NIM to "NVIDIA NIM")) {
            Row(Modifier.fillMaxWidth().selectable(selected == id, enabled = !busy, role = Role.RadioButton, onClick = { provider = id }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected == id, onClick = null, enabled = !busy)
                Text(label, Modifier.padding(start = 8.dp))
            }
        }
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Full HTTPS validation endpoint") },
            supportingText = { Text(when (selected) {
                CloudProvider.GEMINI -> "Use the provider's models-list endpoint."
                CloudProvider.OPENROUTER -> "Use the provider's current-key information endpoint."
                CloudProvider.NVIDIA_NIM -> "Use the provider's chat-completions endpoint."
            }) }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        if (selected == CloudProvider.NVIDIA_NIM) {
            OutlinedTextField(model, { model = it }, label = { Text("NVIDIA model ID you have access to") },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        }
        OutlinedTextField(keyText, { keyText = it }, label = { Text("API key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Text(if (selected == CloudProvider.NVIDIA_NIM)
            "Confirm sends your key and a one-token text validation request to the endpoint above. It may use provider quota. No images are sent."
            else "Confirm sends your key to the endpoint above for validation. No images are sent.", style = MaterialTheme.typography.bodySmall)
        Button(enabled = !busy && keyText.isNotBlank() && endpoint.isNotBlank(), onClick = {
            val setup = ProviderSetup(selected, endpoint.trim(), model.trim())
            val approval = OneShotUploadConsent.fromUserTap(setup.consentContext(), SystemClock.elapsedRealtime())
            val key = keyText.trim().toByteArray()
            keyText = ""
            busy = true
            scope.launch {
                try {
                    var validation = KeyValidationStatus.UNEXPECTED_RESPONSE
                    val saved = repository.validateAndStore(setup, key) { candidate ->
                        validation = cloud.validateKey(setup, candidate, approval)
                        validation == KeyValidationStatus.VALID
                    }
                    status = when (validation) {
                        KeyValidationStatus.VALID -> "Key validated and saved. Offline unlock is available."
                        KeyValidationStatus.INVALID_KEY -> "Key rejected. Check or replace the key."
                        KeyValidationStatus.PERMISSION_DENIED -> "Permission denied. Check account and model access."
                        KeyValidationStatus.QUOTA_OR_RATE_LIMIT -> "Quota or rate limit reached. Check credits or retry later."
                        KeyValidationStatus.CONFIGURATION_ERROR -> "Check the full endpoint and selected model."
                        KeyValidationStatus.PROVIDER_UNAVAILABLE -> "Provider unavailable. Retry later."
                        KeyValidationStatus.NETWORK_ERROR -> "Connection failed. Check network access and HTTPS configuration."
                        KeyValidationStatus.UNEXPECTED_RESPONSE -> "Unexpected response. Check that the endpoint matches this provider."
                    }
                    if (saved) onUnlocked()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IllegalArgumentException) { status = "Check the HTTPS endpoint, key format and required model ID." }
                catch (_: MonitoringActiveException) { status = "Stop the monitoring session before validating a key." }
                catch (_: Exception) { status = "Validation could not finish. Check setup and retry." }
                finally { key.fill(0); busy = false }
            }
        }) { Text(if (busy) "Validating…" else "Confirm & Upload") }
        Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}
