package com.edgepulse.monitor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
        topBar = { TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PulseMark(active = state.busy != null)
                Column {
                    Text(if (embedded) "Settings" else stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    Text("PHONE SETUP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }, actions = {
            onHome?.let { TextButton(onClick = it) { Text("Done") } }
            TextButton(onClick = { tools = !tools }) { Text(if (tools) "Back" else "Tools") }
        }) },
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
                            StepTab(index, title, step == index,
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
                            item { SectionLabel("01", "AI CONNECTION") }
                            item { ApiKeyGateScreen(state, cloudActions) }
                            if (onUsage != null) item { TextButton(onClick = onUsage) { Text("What is my key used for? View usage  →") } }
                            item {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                TextButton(onClick = { step = 1 }, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                    Text(if (state.cloudReady) "Continue to device check  →" else "Check this phone first  →")
                                }
                            }
                        }
                        1 -> {
                            item { SectionLabel("02", "DEVICE READINESS") }
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
                                Button(onClick = { onPrepare(challenge, state.benchmark != null) }, enabled = enabled && !state.identityMismatch,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (state.deviceReady) "Check again" else "Prepare phone") }
                                Text("Required: 40 ms or faster. Detailed results in Tools.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                            item { OutlinedButton(onClick = { step = 2 }, enabled = state.identityReady && enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                Text("Continue to report folder  →")
                            } }
                        }
                        2 -> {
                            item { SectionLabel("03", "LOCAL REPORTS") }
                            item { SetupSection {
                                Text("Your report folder", style = MaterialTheme.typography.headlineSmall)
                                Text("Choose storage on this device, an SD card or USB drive. We'll export the public audit identity and schedule checkpoints.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                state.folderName?.let { StatusPanel("Folder saved", it) }
                                Button(onClick = onChooseFolder, enabled = enabled && state.identityReady && !state.identityMismatch,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (state.folderName == null) "Choose folder" else "Change folder") }
                                if (!state.identityReady) TextButton(onClick = { step = 1 }) { Text("Prepare the phone first  →") }
                                if (state.folderName != null) OutlinedButton(onClick = onExportAudit, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Export audit") }
                                Text("No events yet? We'll tell you before exporting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                        }
                        3 -> {
                            item { Text("Settings & tools", style = MaterialTheme.typography.headlineSmall) }
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
                    if (page != 3) item {
                        Text("FOUNDATION BUILD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Camera monitoring is on Home. Bag counting is still in development.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepTab(index: Int, title: String, selected: Boolean, complete: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val policy = LocalEdgeMotion.current
    val background by animateColorAsState(if (selected) colors.primary else colors.surface, tween(policy.duration(EdgeMotion.FastMs)), label = "Step selection")
    val foreground by animateColorAsState(if (selected) colors.onPrimary else colors.onSurface, tween(policy.duration(EdgeMotion.FastMs)), label = "Step label")
    Surface(modifier = modifier.border(1.dp, if (selected) colors.primary else colors.outlineVariant, RoundedCornerShape(12.dp)),
        color = background, contentColor = foreground, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.selectable(selected, role = Role.Tab, onClick = onClick).heightIn(min = 66.dp).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.Start) {
            Text(if (complete) "✓" else "0${index + 1}", style = MaterialTheme.typography.labelSmall)
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SectionLabel(number: String, title: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(number, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun PulseMark(active: Boolean) {
    val policy = LocalEdgeMotion.current
    val ink = MaterialTheme.colorScheme.onPrimary
    val alpha = if (active && policy.enabled) {
        val transition = rememberInfiniteTransition(label = "Activity signal")
        val value by transition.animateFloat(0.4f, 1f, infiniteRepeatable(tween(EdgeMotion.ActivityMs, easing = LinearEasing), RepeatMode.Reverse), label = "Activity opacity")
        value
    } else 1f
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) {
        Canvas(Modifier.padding(9.dp)) {
            val pulse = Path().apply {
                moveTo(0f, size.height * 0.55f); lineTo(size.width * 0.24f, size.height * 0.55f)
                lineTo(size.width * 0.38f, size.height * 0.15f); lineTo(size.width * 0.58f, size.height * 0.85f)
                lineTo(size.width * 0.73f, size.height * 0.42f); lineTo(size.width, size.height * 0.42f)
            }
            drawPath(pulse, ink.copy(alpha = alpha), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
