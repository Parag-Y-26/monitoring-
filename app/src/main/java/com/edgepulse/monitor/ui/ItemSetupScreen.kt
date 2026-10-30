package com.edgepulse.monitor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.edgepulse.core.cloud.OneShotUploadConsent
import com.edgepulse.core.cloud.ProviderDefaults
import android.os.SystemClock
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemSetupScreen(state: ItemUiState, model: ItemViewModel, labels: List<String>, onBack: () -> Unit,
    onCamera: () -> Unit, onPhoto: () -> Unit, onSettings: () -> Unit, embedded: Boolean = false) {
    var classPicker by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var reviewed by remember(state.approved) { mutableStateOf(false) }
    var threshold by remember { mutableFloatStateOf(.78f) }
    val enabled = !state.busy && !state.monitoring
    Scaffold(topBar = { EdgePageHeader("Items to monitor","Category or photo") {
        if (!embedded) EdgeTextAction("Done",onBack)
    } }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).imePadding().testTag("item-setup-list"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.monitoring) item { Text("Stop the active session before changing items or using photo analysis.") }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = model::cancel) { Text("Cancel") } }
            state.message?.let { message -> item { Text(message, style = MaterialTheme.typography.bodyMedium) } }
            if (state.photo != null) {
                item { EdgeTextAction("Discard photo",model::discardPhoto,enabled) }
                if (state.approved == null) item {
                    Text("Crop one item", style = MaterialTheme.typography.titleLarge)
                    Text("Drag the corners to fit the object, then tap Use this crop. Use Mask to cover private details.", style = MaterialTheme.typography.bodySmall)
                    PhotoCropEditor(state.photo, enabled, model::approvePhoto)
                } else {
                    item {
                        Image(state.approved.asImageBitmap(), contentDescription = "Approved cropped reference; inspect for private details",
                            modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp))
                                .border(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f),RoundedCornerShape(16.dp)))
                        EdgeTextAction("Edit crop or mask",model::editPhoto,enabled)
                    }
                    item {
                        OutlinedTextField(state.name, model::name, label = { Text("Item name") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                    }
                    item {
                        EdgePrimaryButton("Save item for offline monitoring",{ model.savePhotoTarget(threshold) },enabled && state.name.isNotBlank() && state.referenceUsable)
                    }
                    item {
                        OutlinedTextField(state.traits, model::traits, label = { Text("Visual characteristics") }, enabled = enabled, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 6)
                    }
                    item {
                        Text("Match threshold: %.2f".format(threshold), style = MaterialTheme.typography.labelLarge)
                        Slider(threshold, { threshold = it }, valueRange = .55f.. .95f, enabled = enabled)
                        Text("Higher = stricter · Experimental appearance match", style = MaterialTheme.typography.bodySmall)
                    }
                    item {
                        Text("AI description (optional)", style = MaterialTheme.typography.titleMedium)
                        Text(state.setup?.let { "Send only this approved crop to ${ProviderDefaults.profile(it.provider).name}, using ${it.model}. Provider quota may apply." }
                            ?: "Choose an image-capable model in Settings, or name the item yourself.", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(checked = reviewed, onCheckedChange = { reviewed = it }, enabled = enabled)
                            Text("I reviewed the crop and masked private details.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        }
                        EdgePrimaryButton("Confirm & Upload", enabled = enabled && reviewed && state.photoConsentContext != null, onClick = {
                            model.confirmUpload(OneShotUploadConsent.fromUserTap(state.photoConsentContext!!, SystemClock.elapsedRealtime()))
                        })
                        EdgeTextAction("Change AI connection or model",onSettings)
                    }
                }
            } else {
                item {
                    Text("Add an item", style = MaterialTheme.typography.titleLarge)
                    Text("Use a trained category or an encrypted photo reference.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                item {
                    EdgePrimaryButton("Take an item photo",onCamera,enabled)
                    EdgeSecondaryButton("Choose an image",onPhoto,enabled,Modifier.padding(top = 12.dp))
                }
                item {
                    TargetSelector(state.profiles.firstOrNull { it.id == state.selectedId }?.name ?: "All supported objects", enabled) { classPicker = true }
                    OutlinedButton(enabled = enabled, onClick = { model.select("") }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 52.dp)) {
                        Text(if (state.selectedId.isEmpty()) "✓  All supported objects" else "All supported objects")
                    }
                    TextButton(enabled = enabled, onClick = { classPicker = true },modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Choose a trained category (${labels.size})") }
                }
                item {
                    Text("Saved items", style = MaterialTheme.typography.titleLarge)
                    if (state.profiles.isEmpty()) Text("No saved items yet. Your references stay encrypted on this phone.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                }
                itemsIndexed(state.profiles, key = { _, p -> p.id }) { _, profile ->
                    Surface(shape = RoundedCornerShape(16.dp),color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .18f),modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (profile.classId != null) "Trained object category" else "Photo reference · experimental match",
                        style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (profile.classId != null && profile.id == state.selectedId) {
                        var detectionThreshold by remember(profile.id, profile.detectionThreshold) { mutableFloatStateOf(profile.detectionThreshold) }
                        Text("Detection threshold: %.2f".format(detectionThreshold), style = MaterialTheme.typography.labelLarge)
                        Slider(detectionThreshold, { detectionThreshold = it }, valueRange = .10f.. .90f, enabled = enabled,
                            onValueChangeFinished = { model.setDetectionThreshold(profile.id, detectionThreshold) })
                        Text("Lower finds weaker matches but can add false detections. Keep the phone upright and the whole object well lit.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (profile.characteristics.isNotBlank()) Text(profile.characteristics, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(12.dp),verticalAlignment = Alignment.CenterVertically) {
                        EdgeSecondaryButton(if (profile.id == state.selectedId) "Selected" else "Select item",{ model.select(profile.id) },
                            enabled && profile.id != state.selectedId,Modifier.weight(1f))
                        EdgeTextAction("Remove",{ model.remove(profile.id) },enabled,Modifier.weight(1f).heightIn(min = 52.dp))
                    }
                    }
                    }
                }
            }
        }
    }
    if (classPicker) ModalBottomSheet(onDismissRequest = { classPicker = false }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Trained categories", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(search, { search = it }, label = { Text("Search categories") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(labels) { id, label -> if (label.contains(search, true)) TextButton(onClick = { classPicker = false; model.selectClass(id, label) }, modifier = Modifier.fillMaxWidth()) { Text(label) } }
            }
        }
    }
}

@Composable
private fun PhotoCropEditor(bitmap: Bitmap, enabled: Boolean, onApproved: (Bitmap) -> Unit) {
    var crop by remember(bitmap) { mutableStateOf(Rect(.1f, .1f, .9f, .9f)) }
    var mask by remember(bitmap) { mutableStateOf(false) }
    var precise by remember(bitmap) { mutableStateOf(false) }
    val points = remember(bitmap) { mutableStateListOf<Offset>() }
    val picture = remember(bitmap) { bitmap.asImageBitmap() }
    val cropInteraction = remember { MutableInteractionSource() }
    val maskInteraction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilterChip(!mask, { mask = false }, enabled = enabled, label = { Text("Adjust crop") },interactionSource = cropInteraction,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).edgePressFeedback(cropInteraction,enabled))
        FilterChip(mask, { mask = true }, enabled = enabled, label = { Text("Mask") },interactionSource = maskInteraction,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).edgePressFeedback(maskInteraction,enabled))
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EdgeTextAction("Reset",{ points.clear(); crop = Rect(.1f,.1f,.9f,.9f) },enabled,Modifier.weight(1f))
        EdgeTextAction("Precise crop",{ precise = !precise },enabled,Modifier.weight(1f))
    }
    Text(if (mask) "Paint over private details. Switch to Adjust crop to scroll on the photo."
        else "Drag a white corner to resize. Swipe the middle of the photo to scroll.", style = MaterialTheme.typography.bodySmall)
    // Keep the explicit apply action ahead of the photo, including on compact screens.
    EdgePrimaryButton("Use this crop",enabled = enabled, modifier = Modifier.padding(vertical = 12.dp), onClick = {
        val left = (crop.left*bitmap.width).toInt().coerceIn(0,bitmap.width-1)
        val top = (crop.top*bitmap.height).toInt().coerceIn(0,bitmap.height-1)
        val right = (crop.right*bitmap.width).toInt().coerceIn(left+1,bitmap.width)
        val bottom = (crop.bottom*bitmap.height).toInt().coerceIn(top+1,bitmap.height)
        val result = Bitmap.createBitmap(right-left,bottom-top,Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        canvas.drawBitmap(bitmap, android.graphics.Rect(left,top,right,bottom), android.graphics.Rect(0,0,result.width,result.height), null)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; strokeWidth = bitmap.width*.07f; strokeCap = android.graphics.Paint.Cap.ROUND }
        var previous = Offset.Unspecified
        for (p in points) {
            if (p.isSpecified) {
                val x = p.x*bitmap.width-left; val y = p.y*bitmap.height-top
                canvas.drawCircle(x,y,bitmap.width*.035f,paint)
                if (previous.isSpecified) canvas.drawLine(previous.x*bitmap.width-left,previous.y*bitmap.height-top,x,y,paint)
            }
            previous = p
        }
        onApproved(result)
    })
    if (precise) {
        Text("Left edge", style = MaterialTheme.typography.labelMedium)
        Slider(crop.left, { crop = Rect(it,crop.top,crop.right,crop.bottom) }, valueRange = 0f..(crop.right-.10f), enabled = enabled)
        Text("Top edge", style = MaterialTheme.typography.labelMedium)
        Slider(crop.top, { crop = Rect(crop.left,it,crop.right,crop.bottom) }, valueRange = 0f..(crop.bottom-.10f), enabled = enabled)
        Text("Right edge", style = MaterialTheme.typography.labelMedium)
        Slider(crop.right, { crop = Rect(crop.left,crop.top,it,crop.bottom) }, valueRange = (crop.left+.10f)..1f, enabled = enabled)
        Text("Bottom edge", style = MaterialTheme.typography.labelMedium)
        Slider(crop.bottom, { crop = Rect(crop.left,crop.top,crop.right,it) }, valueRange = (crop.top+.10f)..1f, enabled = enabled)
    }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    val ratio = bitmap.width.toFloat()/bitmap.height
    val previewWidth = minOf(maxWidth,360.dp*ratio)
    Canvas(Modifier.width(previewWidth).height(previewWidth/ratio).testTag("photo-crop-canvas").pointerInput(bitmap, mask, enabled) {
        if (enabled) awaitEachGesture {
            val down = awaitFirstDown()
            val corner = if (mask) -1 else {
                val corners = arrayOf(crop.topLeft, crop.topRight, crop.bottomRight, crop.bottomLeft)
                val nearest = corners.indices.minByOrNull {
                    (Offset(corners[it].x*size.width,corners[it].y*size.height)-down.position).getDistanceSquared()
                } ?: 0
                val distance = (Offset(corners[nearest].x*size.width,corners[nearest].y*size.height)-down.position).getDistance()
                if (distance > 32.dp.toPx()) return@awaitEachGesture
                nearest
            }
            down.consume()
            if (mask) points.add(Offset((down.position.x/size.width).coerceIn(0f,1f),(down.position.y/size.height).coerceIn(0f,1f)))
            try { drag(down.id) { change ->
            change.consume()
            val p = Offset((change.position.x/size.width).coerceIn(0f,1f), (change.position.y/size.height).coerceIn(0f,1f))
            if (mask) points.add(p) else crop = when (corner) {
                0 -> Rect(p.x.coerceAtMost(crop.right-.10f), p.y.coerceAtMost(crop.bottom-.10f), crop.right, crop.bottom)
                1 -> Rect(crop.left, p.y.coerceAtMost(crop.bottom-.10f), p.x.coerceAtLeast(crop.left+.10f), crop.bottom)
                2 -> Rect(crop.left, crop.top, p.x.coerceAtLeast(crop.left+.10f), p.y.coerceAtLeast(crop.top+.10f))
                else -> Rect(p.x.coerceAtMost(crop.right-.10f), crop.top, crop.right, p.y.coerceAtLeast(crop.top+.10f))
            }
            } } finally { if (mask) points.add(Offset.Unspecified) }
        }
    }) {
        drawImage(picture, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
        var previous = Offset.Unspecified
        for (p in points) {
            if (p.isSpecified) {
                val current = Offset(p.x*size.width,p.y*size.height)
                drawCircle(Color.Black, size.width*.035f, current)
                if (previous.isSpecified) drawLine(Color.Black, Offset(previous.x*size.width,previous.y*size.height), current, size.width*.07f)
            }
            previous = p
        }
        val origin = Offset(crop.left*size.width,crop.top*size.height)
        val extent = Size(crop.width*size.width,crop.height*size.height)
        drawRect(Color.Black, origin, extent, style = Stroke(5.dp.toPx()))
        drawRect(Color.White, origin, extent, style = Stroke(2.dp.toPx()))
        for (p in arrayOf(crop.topLeft,crop.topRight,crop.bottomRight,crop.bottomLeft)) drawCircle(Color.White, 6.dp.toPx(), Offset(p.x*size.width,p.y*size.height))
    }
    }
}
