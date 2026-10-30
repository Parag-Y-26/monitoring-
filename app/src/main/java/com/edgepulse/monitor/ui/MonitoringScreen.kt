package com.edgepulse.monitor.ui

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.edgepulse.monitor.monitoring.DetectionOverlay
import com.edgepulse.monitor.monitoring.MonitoringState
import com.edgepulse.core.vision.PreviewProjection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitoringScreen(setup: SetupUiState, monitor: MonitoringState, onSettings: () -> Unit,
    onStart: () -> Unit, onStop: () -> Unit, onSurface: (Preview.SurfaceProvider?) -> Unit,
    overlay: DetectionOverlay? = null, labels: List<String> = emptyList(), onRescan: () -> Unit = {}, onItems: () -> Unit = {}, onUsage: () -> Unit = {}) {
    val interrupted = setup.activeSessionId != null && !monitor.active && !monitor.starting
    val ready = setup.storageReady && setup.cloudReady && setup.deviceReady && setup.folderName != null && !setup.identityMismatch
    var explanation by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { onSurface(null) } }
    if (monitor.active || monitor.starting) {
        LiveMonitoringView(setup.selectedTarget?.name ?: "All objects", monitor, onStop, onSurface, overlay, labels, onRescan, onSettings)
        return
    }
    Scaffold(topBar = { TopAppBar(title = { Column {
        Text("EdgePulse", style = MaterialTheme.typography.titleLarge)
        Text("DRISHTI / LOCAL MONITORING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }) }, bottomBar = {
        Surface {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                if (interrupted) Button(onClick = onStop, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text("Close interrupted session")
                } else Button(onClick = onStart, enabled = ready && !monitor.starting, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    Text(if (monitor.starting) "Starting camera…" else "Start monitoring")
                }
            }
        }
    }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).testTag("monitor-content"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Text(if (interrupted) "Session interrupted" else "Ready when you are", style = MaterialTheme.typography.headlineMedium)
            }
            item { TargetSelector(setup.selectedTarget?.name ?: "All supported objects", !monitor.active && !monitor.starting, onItems) }
            if (!ready && !monitor.active && !interrupted) item {
                Text(when {
                    !setup.storageReady -> "Opening secure storage."
                    setup.identityMismatch -> "Review the audit identity in Settings."
                    !setup.cloudReady -> "Connect an API key once in Settings."
                    !setup.deviceReady -> "Run the device check in Settings; construction requires 40 ms or faster."
                    else -> "Choose a local report folder in Settings."
                }, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onSettings) { Text("Finish setup  →") }
            }
            monitor.error?.let { error -> item { Text(error, style = MaterialTheme.typography.bodyMedium) } }
            setup.message?.let { message -> item { Text(message, style = MaterialTheme.typography.bodyMedium) } }
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.Center) {
                        Text("Your live view starts here", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            item { Text("On-device detection · No video saved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item {
                TextButton(onClick = { explanation = !explanation }) { Text("What the count means") }
                if (explanation) Text("Counts show confirmed visible detections, not a cumulative stock total. Reference matching is experimental and sensitive to viewpoint, scale and background. Label scores are not calibrated probabilities.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onUsage) { Text("API purpose & usage  →") }
            }
        }
    }
}

@Composable
private fun LiveMonitoringView(target: String, monitor: MonitoringState, onStop: () -> Unit,
    onSurface: (Preview.SurfaceProvider?) -> Unit, overlay: DetectionOverlay?, labels: List<String>, onRescan: () -> Unit, onSettings: () -> Unit) {
    var more by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(EdgePalette.Ink).testTag("live-monitor")) {
        if (!monitor.paused && !monitor.starting) {
            AndroidView(factory = { context -> PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                onSurface(surfaceProvider)
            } }, modifier = Modifier.fillMaxSize())
            if (overlay != null) LiveLabels(overlay, labels)
        }
        Box(Modifier.fillMaxWidth().height(180.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .65f), Color.Transparent))))
        Surface(color = EdgePalette.Ink.copy(alpha = .86f), contentColor = EdgePalette.Paper, shape = RoundedCornerShape(24.dp),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 440.dp)) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when { monitor.starting -> "Starting camera…"; monitor.paused -> "Camera paused"; monitor.burst -> "LIVE · ${monitor.visibleItems} detected";
                    else -> "WATCH · ${monitor.visibleItems} last detected" }, style = MaterialTheme.typography.titleSmall)
                Text(target, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        if (monitor.paused || monitor.error != null) Text(monitor.error ?: "Cooling down", color = EdgePalette.Paper,
            modifier = Modifier.align(Alignment.Center).padding(24.dp), style = MaterialTheme.typography.titleMedium)
        Surface(color = EdgePalette.Ink.copy(alpha = .94f), contentColor = EdgePalette.Paper, shape = RoundedCornerShape(30.dp),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).widthIn(max = 520.dp)) {
            FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = EdgePalette.Paper, contentColor = EdgePalette.Ink), modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop monitoring") }
                TextButton(onClick = onRescan, enabled = !monitor.paused && !monitor.starting, colors = ButtonDefaults.textButtonColors(contentColor = EdgePalette.Paper)) { Text("Rescan") }
                TextButton(onClick = { more = true }, colors = ButtonDefaults.textButtonColors(contentColor = EdgePalette.Paper)) { Text("More") }
            }
        }
    }
    if (more) AlertDialog(onDismissRequest = { more = false }, title = { Text("Session") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("%.1f ms · %.1f FPS".format(monitor.inferenceMs, monitor.fps))
            Text("Burst duty %.1f%% · 30 s freezes: ${monitor.freezes}".format(monitor.duty))
            Text("${monitor.frames} frames · pre-roll ${monitor.preRollFrames}")
            Text("Confirmed detections, not stock totals. Scores are not calibrated probabilities.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { more = false }) { Text("Close") } },
        dismissButton = { TextButton(onClick = { more = false; onSettings() }) { Text("Settings") } })
}

@Composable
private fun LiveLabels(overlay: DetectionOverlay, labels: List<String>) {
    val fontSize = with(LocalDensity.current) { 11.sp.toPx() }
    val paint = remember(fontSize) { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = fontSize } }
    val captions = remember(labels) { Array(81) { category -> Array(101) { score -> "${labels.getOrNull(category)?.take(28) ?: "Object"} · ${score / 100f}" } } }
    val projection = remember { PreviewProjection() }
    Canvas(Modifier.fillMaxSize()) {
        overlay.revision
        synchronized(overlay.lock) {
            projection.configure(overlay.uprightWidth, overlay.uprightHeight, size.width, size.height)
            val canvas = drawContext.canvas.nativeCanvas
            val saved = canvas.save(); canvas.clipRect(0f, 0f, size.width, size.height)
            for (i in 0 until overlay.size) {
                val x = projection.x(overlay.left[i]); val y = projection.y(overlay.top[i])
                val w = projection.x(overlay.right[i])-x; val h = projection.y(overlay.bottom[i])-y
                if (x+w < 0 || y+h < 0 || x > size.width || y > size.height) continue
                drawRect(Color.Black, Offset(x, y), Size(w, h), style = Stroke(5.dp.toPx()))
                drawRect(Color.White, Offset(x, y), Size(w, h), style = Stroke(2.dp.toPx()))
                val caption = captions[overlay.classes[i].coerceIn(0, 80)][(overlay.scores[i]*100).toInt().coerceIn(0, 100)]
                val textWidth = paint.measureText(caption)
                val tx = x.coerceAtMost(size.width-textWidth-8).coerceAtLeast(0f)
                val ty = (y-4.dp.toPx()).coerceIn(fontSize+4, maxOf(fontSize+4, size.height-8))
                drawRect(Color.Black, Offset(tx, ty-fontSize-4), Size(textWidth+8, fontSize+8))
                canvas.drawText(caption, tx+4, ty, paint)
            }
            canvas.restoreToCount(saved)
        }
    }
}
