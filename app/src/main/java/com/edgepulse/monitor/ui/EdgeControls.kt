package com.edgepulse.monitor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Shared alignment and action hierarchy across Monitor, Items and Settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgePageHeader(title: String, subtitle: String, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(title = {
        Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title,style = MaterialTheme.typography.titleLarge)
            Text(subtitle,style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, actions = { Row(Modifier.padding(end = 4.dp),content = actions) })
}

@Composable
fun EdgePrimaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.buttonColors()) {
    val interaction = remember { MutableInteractionSource() }
    Button(onClick = onClick,enabled = enabled,shape = RoundedCornerShape(14.dp),
        colors = colors,interactionSource = interaction,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).edgePressFeedback(interaction,enabled),contentPadding = PaddingValues(16.dp,12.dp)) {
        EdgeButtonLabel(label,true)
    }
}

@Composable
fun EdgeSecondaryButton(label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    OutlinedButton(onClick = onClick,enabled = enabled,shape = RoundedCornerShape(14.dp),
        interactionSource = interaction,modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).edgePressFeedback(interaction,enabled),
        contentPadding = PaddingValues(16.dp,12.dp)) {
        EdgeButtonLabel(label,true)
    }
}

@Composable
fun EdgeTextAction(label: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.textButtonColors()) {
    val interaction = remember { MutableInteractionSource() }
    TextButton(onClick = onClick,enabled = enabled,colors = colors,interactionSource = interaction,
        modifier = modifier.heightIn(min = 48.dp).edgePressFeedback(interaction,enabled),
        contentPadding = PaddingValues(vertical = 12.dp)) { EdgeButtonLabel(label,false) }
}

/** Render-layer feedback preserves layout and dispatches the normal click without waiting for motion. */
@Composable
fun Modifier.edgePressFeedback(interaction: MutableInteractionSource, enabled: Boolean = true): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val policy = LocalEdgeMotion.current
    val scale by animateFloatAsState(if (enabled && policy.enabled && pressed) EdgeMotion.PressScale else 1f,
        animationSpec = if (policy.enabled) EdgeMotion.ButtonSpring else snap(),label = "Button press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

@Composable
private fun EdgeButtonLabel(label: String, centered: Boolean) {
    val motion = LocalEdgeMotion.current
    Crossfade(label,animationSpec = tween(motion.duration(EdgeMotion.FastMs)),label = "Button label",
        modifier = if (centered) Modifier.fillMaxWidth() else Modifier) { value ->
        Text(value,modifier = if (centered) Modifier.fillMaxWidth() else Modifier,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start)
    }
}
