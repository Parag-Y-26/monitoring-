package com.edgepulse.monitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.edgepulse.monitor.ui.auth.ApiKeyGateScreen
import com.edgepulse.monitor.ui.auth.CloudSetupActions
import com.edgepulse.monitor.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgePulseScreen(
    state: SetupUiState,
    cloudActions: CloudSetupActions,
    onPrepare: (String, Boolean) -> Unit,
    onChooseFolder: () -> Unit,
    onExportAudit: () -> Unit,
    onExportBenchmark: () -> Unit,
    onCancel: () -> Unit,
    onMessageDismiss: () -> Unit,
    onHome: (() -> Unit)? = null,
    onUsage: (() -> Unit)? = null,
    embedded: Boolean = false,
) {
    var tools by rememberSaveable { mutableStateOf(false) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var challenge by rememberSaveable { mutableStateOf("") }
    val enabled = state.storageReady && state.busy == null
    val snackbar = remember { SnackbarHostState() }
    val motion = LocalEdgeMotion.current
    BackHandler(enabled = tools) { tools = false }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); onMessageDismiss() } }
    Scaffold(
        topBar = { EdgePageHeader(if (embedded) "Settings" else stringResource(R.string.app_name),"Preferences and setup") {
            onHome?.let { EdgeTextAction("Done",it) }
            EdgeTextAction(if (tools) "Back" else "Tools",{ tools = !tools })
        } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).imePadding().widthIn(max = 640.dp)) {
            if (!tools) {
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Phone setup", style = MaterialTheme.typography.titleMedium)
                        Text("${state.completedSteps} / 3 complete", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("AI", "Device", "Reports").forEachIndexed { index, title ->
                            StepTab(title, step == index,
                                listOf(state.cloudReady, state.deviceReady, state.folderName != null)[index], Modifier.weight(1f)) { step = index }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            // Opacity changes never resize the scrolling viewport.
            Crossfade(targetState = if (tools) 3 else step, animationSpec = tween(motion.duration(EdgeMotion.NormalMs)),
                label = "Setup step", modifier = Modifier.weight(1f)) { page ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (!state.storageReady) item {
                        StatusPanel(if (state.storageError) "Secure storage needs attention" else "Opening secure storage…",
                            if (state.storageError) "Restart the app and check this device's key/storage access. Your data has not been reset." else null,
                            loading = !state.storageError)
                    }
                    if (state.identityMismatch) item {
                        StatusPanel("Audit identity needs review", "The signing key doesn't match the enrolled identity. Preserve app data and review it with your verifier.")
                    }
                    if (state.busy != null && page != 0) item {
                        StatusPanel(state.busy, loading = true)
                        TextButton(onClick = onCancel) { Text("Cancel") }
                    }
                    when (page) {
                        0 -> {
                            item { ApiKeyGateScreen(state, cloudActions) }
                            item {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                TextButton(onClick = { step = 1 }, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                    Text(if (state.cloudReady) "Continue to device check  →" else "Check this phone first  →")
                                }
                            }
                        }
                        1 -> {
                            item { SetupSection {
                                Text("Prepare this phone", style = MaterialTheme.typography.headlineSmall)
                                Text("Set up the audit identity and check local AI speed. This runs offline.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                state.benchmark?.let { report ->
                                    StatusPanel(when {
                                        report.environment == "EMULATOR" -> "Emulator check only"
                                        report.constructionEligible -> "Speed check passed"
                                        else -> "Speed check not passed"
                                    }, report.tinyP95Ms?.let { "%.2f ms per frame · p95".format(it) })
                                }
                                if (state.identityReady) Text("✓  Audit identity ready", style = MaterialTheme.typography.labelLarge)
                                EdgePrimaryButton(if (state.deviceReady) "Check again" else "Prepare phone",
                                    { onPrepare(challenge, state.benchmark != null) },enabled && !state.identityMismatch)
                                Text("Required: 40 ms or faster. Detailed results in Tools.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                            item { OutlinedButton(onClick = { step = 2 }, enabled = state.identityReady && enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                Text("Continue to report folder  →")
                            } }
                        }
                        2 -> {
                            item { SetupSection {
                                Text("Your report folder", style = MaterialTheme.typography.headlineSmall)
                                Text("Choose storage on this device, an SD card or USB drive. We'll export the public audit identity and schedule checkpoints.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                state.folderName?.let { StatusPanel("Folder saved", it) }
                                EdgePrimaryButton(if (state.folderName == null) "Choose folder" else "Change folder",
                                    onChooseFolder,enabled && state.identityReady && !state.identityMismatch)
                                if (!state.identityReady) TextButton(onClick = { step = 1 }) { Text("Prepare the phone first  →") }
                                if (state.folderName != null) OutlinedButton(onClick = onExportAudit, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Export audit") }
                                Text("No events yet? We'll tell you before exporting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                        }
                        3 -> {
                            item { Text("Settings & tools", style = MaterialTheme.typography.headlineSmall) }
                            if (onUsage != null) item {
                                EdgeSecondaryButton("API activity",onUsage)
                                Text("Optional history of approved cloud requests.",Modifier.padding(top = 8.dp),
                                    style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            item { SetupSection {
                                Text("Device performance", style = MaterialTheme.typography.titleMedium)
                                if (state.benchmark == null) Text("No current check. Prepare the phone to measure local AI speed.")
                                else state.benchmark.measurements.forEach { value ->
                                    Text("${value.delegate}: ${value.p95Ms?.let { "%.2f ms p95".format(it) } ?: "Unavailable"}", style = MaterialTheme.typography.bodyMedium)
                                    value.failureReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                OutlinedButton(enabled = enabled && !state.identityMismatch, onClick = { onPrepare(challenge, true) }) { Text("Run device check again") }
                            } }
                            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                            item { SetupSection {
                                Text("Audit & verification", style = MaterialTheme.typography.titleMedium)
                                Text(if (state.identityReady) "Existing audit identity preserved" else "An identity is created when you prepare this phone.")
                                if (!state.identityReady && !state.identityMismatch) OutlinedTextField(challenge, { challenge = it }, modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Verifier challenge (optional)") }, supportingText = { Text("Use only a base64 challenge supplied by your auditor. Empty means no attestation claim.") })
                                Text("Hardware attestation requires separate external verification.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
                            item { SetupSection {
                                Text("Exports", style = MaterialTheme.typography.titleMedium)
                                Text(state.folderName?.let { "Folder: $it" } ?: "Choose a local folder in Reports first.")
                                OutlinedButton(onClick = onExportAudit, enabled = enabled && state.folderName != null, modifier = Modifier.fillMaxWidth()) { Text("Export audit") }
                                OutlinedButton(onClick = onExportBenchmark, enabled = enabled && state.folderName != null && state.benchmark != null, modifier = Modifier.fillMaxWidth()) { Text("Export device check") }
                                TextButton(onClick = { tools = false; step = 2 }) { Text("Manage report folder  →") }
                            } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepTab(title: String, selected: Boolean, complete: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val policy = LocalEdgeMotion.current
    val background by animateColorAsState(if (selected) colors.primary else colors.surface, tween(policy.duration(EdgeMotion.FastMs)), label = "Step selection")
    val foreground by animateColorAsState(if (selected) colors.onPrimary else colors.onSurface, tween(policy.duration(EdgeMotion.FastMs)), label = "Step label")
    Surface(modifier = modifier.edgePressFeedback(interaction).border(1.dp, if (selected) colors.primary else colors.outlineVariant, RoundedCornerShape(12.dp)),
        color = background, contentColor = foreground, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.selectable(selected, interactionSource = interaction,indication = LocalIndication.current,role = Role.Tab, onClick = onClick)
            .semantics { stateDescription = if (complete) "Complete" else "Not complete" }.heightIn(min = 52.dp).padding(8.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun StatusPanel(title: String, detail: String? = null, loading: Boolean = false) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SetupSection(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}
