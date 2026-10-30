package com.edgepulse.monitor.ui.auth

import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.*
import com.edgepulse.core.cloud.*
import com.edgepulse.monitor.ui.SetupUiState
import com.edgepulse.monitor.ui.EdgePrimaryButton
import com.edgepulse.monitor.ui.EdgeTextAction
import com.edgepulse.monitor.ui.edgePressFeedback

data class CloudSetupActions(
    val chooseProvider: (CloudProvider) -> Unit = {},
    val connect: (ByteArray, OneShotUploadConsent) -> Unit = { key, _ -> key.fill(0) },
    val refresh: (OneShotUploadConsent) -> Unit = {},
    val chooseModel: (String) -> Unit = {},
    val verifyModel: (OneShotUploadConsent) -> Unit = {},
    val disconnect: () -> Unit = {},
    val retry: (OneShotUploadConsent) -> Unit = {},
    val cancel: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeyGateScreen(state: SetupUiState, actions: CloudSetupActions) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var keyText by remember { mutableStateOf("") }
    var keyVisible by remember(state.provider) { mutableStateOf(false) }
    var editKey by remember { mutableStateOf(state.connection == null || state.connection?.needsReconnect == true) }
    var modelPicker by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val busy = state.busy != null || !state.storageReady
    val modelInteraction = remember { MutableInteractionSource() }
    val profile = ProviderDefaults.profile(state.provider)
    LaunchedEffect(state.provider, state.connection != null) {
        if (keyText.isEmpty()) editKey = state.connection == null || state.connection?.needsReconnect == true
    }

    fun acceptKey(value: String) {
        keyText = value.take(4097)
        editKey = true
        ProviderDefaults.detect(value)?.let(actions.chooseProvider)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("AI connection", style = MaterialTheme.typography.titleLarge)
            if (state.connection?.needsReconnect == false && !state.verificationNeeded) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("Saved", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text("One key. Your models, loaded automatically.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CloudProvider.entries.forEach { provider ->
                val interaction = remember { MutableInteractionSource() }
                FilterChip(selected = state.provider == provider, enabled = !busy, onClick = {
                    actions.chooseProvider(provider)
                }, label = { Text(ProviderDefaults.profile(provider).name, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false) },
                    interactionSource = interaction,modifier = Modifier.heightIn(min = 48.dp).edgePressFeedback(interaction,!busy))
            }
        }
        if (state.connection?.needsReconnect == true) Text("Reconnect this saved custom connection to load models from the official provider.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

        if (editKey) {
            OutlinedTextField(value = keyText, onValueChange = ::acceptKey, enabled = !busy, singleLine = true,
                label = { Text("API key") }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                trailingIcon = { EdgeTextAction(if (keyVisible) "Hide" else "Show",{ keyVisible = !keyVisible }) },
                isError = keyText.length > 4096,
                supportingText = { Text(if (keyText.length > 4096) "This key is too long." else "Encrypted on this phone. Never added to a report.") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                EdgeTextAction("Paste key",enabled = !busy, onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let(::acceptKey)
                })
                ProviderDefaults.detect(keyText)?.let { Text("${ProviderDefaults.profile(it).name} detected", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary) }
            }
            Text("Connect sends your key to ${profile.name}. No photos.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            EdgePrimaryButton(if (state.busy?.startsWith("Connecting") == true) "Connecting…" else "Connect & load models",
                enabled = !busy && keyText.isNotBlank() && keyText.length <= 4096, onClick = {
                val bytes = keyText.trim().toByteArray()
                keyText = ""; keyVisible = false; keyboard?.hide()
                actions.connect(bytes, OneShotUploadConsent.fromUserTap(ProviderDefaults.discoveryContext(state.provider), SystemClock.elapsedRealtime()))
            })
        } else EdgeTextAction("Replace API key",{ editKey = true },!busy)

        if (state.models.isNotEmpty()) {
            val selected = state.models.singleOrNull { it.id == state.selectedModel }
            OutlinedButton(onClick = { search = ""; modelPicker = true }, enabled = !busy,interactionSource = modelInteraction,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).edgePressFeedback(modelInteraction,!busy),
                contentPadding = PaddingValues(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(selected?.name ?: "Choose model", fontWeight = FontWeight.SemiBold)
                    Text("Change model", style = MaterialTheme.typography.labelSmall)
                }
                Text("▾")
            }
            Text("${state.models.size} models loaded from ${profile.name}. " + if (state.verificationNeeded) "Verify to save this selection." else "Saved lists work offline.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (state.connection != null) {
            Text("Your key is saved. Refresh the model list when you're online.", style = MaterialTheme.typography.bodySmall)
        }

        if (state.verificationNeeded) {
            Text("NVIDIA's list is public. Verify your chosen model with a one-token text check; provider quota may apply.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            EdgePrimaryButton("Verify & save model",enabled = !busy && state.selectedModel != null && (state.pendingKeyAvailable || state.connection?.needsReconnect == false),
                onClick = { keyboard?.hide(); actions.verifyModel(OneShotUploadConsent.fromUserTap(
                    ProviderDefaults.setup(state.provider, state.selectedModel!!).consentContext(), SystemClock.elapsedRealtime())) })
        }

        if (state.connection?.needsReconnect == false && !state.verificationNeeded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EdgeTextAction("Refresh models",{ actions.refresh(OneShotUploadConsent.fromUserTap(
                    ProviderDefaults.discoveryContext(state.provider), SystemClock.elapsedRealtime())) },!busy)
                EdgeTextAction("Remove connection",actions.disconnect,!busy)
            }
        }
        state.cloudMessage?.let { message ->
            Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (state.cloudError) Text("Connection needs attention", style = MaterialTheme.typography.titleSmall)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.busy != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(state.busy, style = MaterialTheme.typography.bodySmall)
            EdgeTextAction("Cancel request",actions.cancel)
        }
        if (state.retryConnectionAvailable && keyText.isEmpty()) OutlinedButton(enabled = !busy, onClick = {
            actions.retry(OneShotUploadConsent.fromUserTap(ProviderDefaults.discoveryContext(state.provider), SystemClock.elapsedRealtime()))
        }) { Text("Retry connection") }
    }

    if (modelPicker) ModalBottomSheet(sheetState = sheetState, onDismissRequest = { modelPicker = false; keyboard?.hide() }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.86f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Choose a model", style = MaterialTheme.typography.headlineSmall)
            Text("${profile.name} catalog · ${state.models.size} models. Access and quota depend on your account.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(search, { search = it }, label = { Text("Search models") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val filtered = state.models.filter { it.name.contains(search, true) || it.id.contains(search, true) }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).selectableGroup().navigationBarsPadding()) {
                if (filtered.isEmpty()) item { Text("No matching models. Try another search.", Modifier.padding(vertical = 20.dp)) }
                items(filtered, key = { it.id }) { model ->
                    Row(Modifier.fillMaxWidth().selectable(selected = state.selectedModel == model.id, role = Role.RadioButton) {
                        keyboard?.hide(); modelPicker = false; actions.chooseModel(model.id)
                    }.heightIn(min = 64.dp).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = state.selectedModel == model.id, onClick = null)
                        Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(model.name, style = MaterialTheme.typography.titleSmall)
                            Text(model.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(when (model.supportsImages) { true -> "Image input supported"; false -> "Text input"; null -> "Capabilities not listed by provider" },
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            model.contextTokens?.let { Text("Context: $it tokens", style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
