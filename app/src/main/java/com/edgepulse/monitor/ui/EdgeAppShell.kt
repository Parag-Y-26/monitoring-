package com.edgepulse.monitor.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
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
    val primary = if (destination == EdgeDestination.USAGE) EdgeDestination.SETTINGS else destination
    Surface {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp,vertical = 8.dp).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(EdgeDestination.MONITOR,EdgeDestination.ITEMS,EdgeDestination.SETTINGS).forEach { item ->
                val interaction = remember { MutableInteractionSource() }
                val selected = item == primary
                val background by animateColorAsState(if (selected) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .3f) else MaterialTheme.colorScheme.surface,
                    tween(motion.duration(EdgeMotion.FastMs)),label = "Destination highlight")
                val color by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    tween(motion.duration(EdgeMotion.FastMs)), label = "Destination color")
                Box(Modifier.weight(1f).heightIn(min = 52.dp)
                    .edgePressFeedback(interaction).background(background,RoundedCornerShape(14.dp))
                    .selectable(selected, interactionSource = interaction,indication = LocalIndication.current,role = Role.Tab, onClick = { onDestination(item) })
                    .padding(vertical = 12.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
                    Text(item.title, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun TargetSelector(name: String, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(16.dp),
        interactionSource = interaction,color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .25f),
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).edgePressFeedback(interaction,enabled)) {
        Row(Modifier.padding(16.dp),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Monitoring item", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(name, style = MaterialTheme.typography.titleMedium)
                if (!enabled) Text("Stop to change item", style = MaterialTheme.typography.bodySmall)
            }
            Text("›",style = MaterialTheme.typography.titleLarge,color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
