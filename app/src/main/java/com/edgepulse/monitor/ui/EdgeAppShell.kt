package com.edgepulse.monitor.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

enum class EdgeDestination(val title: String) { MONITOR("Monitor"), ITEMS("Items"), USAGE("Usage"), SETTINGS("Settings") }

/** Figma 3:390: text destinations retain their meaning at large font sizes. */
@Composable
fun EdgeAppShell(destination: EdgeDestination, onDestination: (EdgeDestination) -> Unit,
    sessionAway: Boolean = false, onReturn: () -> Unit = {}, onStop: () -> Unit = {}, immersive: Boolean = false, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        if (immersive) { content(); return@Surface }
        Column {
            Box(Modifier.weight(1f).fillMaxWidth().consumeWindowInsets(WindowInsets.navigationBars)) { content() }
            if (sessionAway) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onReturn, modifier = Modifier.weight(1f)) { Text("Return to session") }
                        TextButton(onClick = onStop) { Text("Stop monitoring") }
                    }
                }
            }
            EdgeNavigation(destination, onDestination)
        }
    }
}

@Composable
fun EdgeNavigation(destination: EdgeDestination, onDestination: (EdgeDestination) -> Unit) {
    val motion = LocalEdgeMotion.current
    Surface {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup()) {
            EdgeDestination.entries.forEach { item ->
                val selected = item == destination
                val color by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    tween(motion.duration(EdgeMotion.FastMs)), label = "Destination color")
                Column(Modifier.weight(1f).heightIn(min = 72.dp)
                    .selectable(selected, role = Role.Tab, onClick = { onDestination(item) })
                    .padding(vertical = 16.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(32.dp).height(4.dp).background(if (selected) color else MaterialTheme.colorScheme.surface, RoundedCornerShape(2.dp)))
                    Text(item.title, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun TargetSelector(name: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("MONITORING TARGET", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(if (enabled) "Change item  →" else "Stop to change item", style = MaterialTheme.typography.labelMedium)
        }
    }
}
