package com.edgepulse.monitor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    var threshold by remember { mutableFloatStateOf(.90f) }
    val enabled = !state.busy && !state.monitoring
    Scaffold(topBar = { TopAppBar(title = { Column {
        Text("Items to monitor", style = MaterialTheme.typography.titleLarge)
        Text("CHOOSE A CATEGORY OR PHOTO", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }, actions = { if (!embedded) TextButton(onClick = onBack) { Text("Done") } }) }) { insets ->
        LazyColumn(Modifier.fillMaxSize().padding(insets).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.monitoring) item { Text("Stop the active session before changing items or using photo analysis.") }
            if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = model::cancel) { Text("Cancel") } }
            state.message?.let { message -> item { Text(message, style = MaterialTheme.typography.bodyMedium) } }
            if (state.photo != null) {
                item { TextButton(enabled = enabled, onClick = model::discardPhoto) { Text("Discard photo") } }
                if (state.approved == null) item {
                    Text("Crop one item", style = MaterialTheme.typography.titleLarge)
                    Text("Drag the corners to fit the object. Use Mask to cover private details.", style = MaterialTheme.typography.bodySmall)
                    PhotoCropEditor(state.photo, enabled, model::approvePhoto)
                } else {
                    item {
                        Image(state.approved.asImageBitmap(), contentDescription = "Approved cropped reference; inspect for private details", modifier = Modifier.fillMaxWidth().height(240.dp))
                        TextButton(enabled = enabled, onClick = model::editPhoto) { Text("Edit crop or mask") }
                    }
                    item {
                        Text("AI description (optional)", style = MaterialTheme.typography.titleMedium)
                        Text(state.setup?.let { "Send only this approved crop to ${ProviderDefaults.profile(it.provider).name}, using ${it.model}. Provider quota may apply." }
                            ?: "Choose an image-capable model in Settings, or name the item yourself.", style = MaterialTheme.typography.bodySmall)
                        Row {
                            Checkbox(checked = reviewed, onCheckedChange = { reviewed = it }, enabled = enabled)
                            Text("I reviewed the crop and masked private details.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                        Button(enabled = enabled && reviewed && state.photoConsentContext != null, modifier = Modifier.fillMaxWidth(), onClick = {
                            model.confirmUpload(OneShotUploadConsent.fromUserTap(state.photoConsentContext!!, SystemClock.elapsedRealtime()))
                        }) { Text("Confirm & Upload") }
                        TextButton(onClick = onSettings) { Text("Change AI connection or model") }
                    }
                    item {
                        OutlinedTextField(state.name, model::name, label = { Text("Item name") }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(state.traits, model::traits, label = { Text("Visual characteristics") }, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), minLines = 2, maxLines = 6)
                    }
                    item {
                        Text("Match threshold: %.2f".format(threshold), style = MaterialTheme.typography.labelLarge)
                        Slider(threshold, { threshold = it }, valueRange = .70f.. .99f, enabled = enabled)
                        Text("Higher = stricter · Experimental appearance match", style = MaterialTheme.typography.bodySmall)
                        Button(enabled = enabled && state.name.isNotBlank() && state.referenceUsable, onClick = { model.savePhotoTarget(threshold) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Save item for offline monitoring") }
                    }
                }
            } else {
                item {
                    Text("Choose what to watch", style = MaterialTheme.typography.headlineMedium)
                    Text("Trained category or photo reference", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                }
                item {
                    Button(enabled = enabled, onClick = onCamera, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Take an item photo") }
                    TextButton(enabled = enabled, onClick = onPhoto, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Choose an image  →") }
                }
                item {
                    TargetSelector(state.profiles.firstOrNull { it.id == state.selectedId }?.name ?: "All supported objects", enabled) { classPicker = true }
                    OutlinedButton(enabled = enabled, onClick = { model.select("") }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.selectedId.isEmpty()) "✓  All supported objects" else "All supported objects")
                    }
                    TextButton(enabled = enabled, onClick = { classPicker = true }) { Text("Choose a trained category (${labels.size})  →") }
                }
                item {
                    Text("Saved items", style = MaterialTheme.typography.titleLarge)
                    if (state.profiles.isEmpty()) Text("No saved items yet. Your references stay encrypted on this phone.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                }
                itemsIndexed(state.profiles, key = { _, p -> p.id }) { _, profile ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(profile.name, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                    Text(if (profile.classId != null) "Trained object category" else "Photo reference · experimental match", style = MaterialTheme.typography.bodySmall)
                    if (profile.characteristics.isNotBlank()) Text(profile.characteristics, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(enabled = enabled, onClick = { model.select(profile.id) }) { Text(if (profile.id == state.selectedId) "✓ Selected" else "Monitor this item") }
                        TextButton(enabled = enabled, onClick = { model.remove(profile.id) }) { Text("Remove") }
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
    var mask by remember { mutableStateOf(false) }
    var precise by remember { mutableStateOf(false) }
    var corner by remember { mutableIntStateOf(-1) }
    val points = remember(bitmap) { mutableStateListOf<Offset>() }
    val picture = remember(bitmap) { bitmap.asImageBitmap() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!mask, { mask = false }, label = { Text("Crop") })
        FilterChip(mask, { mask = true }, label = { Text("Mask") })
        TextButton(onClick = { points.clear(); crop = Rect(.1f,.1f,.9f,.9f) }) { Text("Reset") }
        TextButton(onClick = { precise = !precise }) { Text("Precise crop") }
    }
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
    Canvas(Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat()/bitmap.height).pointerInput(bitmap, mask, enabled) {
        if (enabled) detectDragGestures(onDragStart = { position ->
            val p = Offset(position.x/size.width, position.y/size.height)
            if (mask) points.add(p) else {
                val corners = arrayOf(crop.topLeft, crop.topRight, crop.bottomRight, crop.bottomLeft)
                corner = corners.indices.minByOrNull { (corners[it]-p).getDistanceSquared() } ?: 0
            }
        }, onDragEnd = { if (mask) points.add(Offset.Unspecified) }, onDrag = { change, delta ->
            change.consume()
            val p = Offset((change.position.x/size.width).coerceIn(0f,1f), (change.position.y/size.height).coerceIn(0f,1f))
            if (mask) points.add(p) else crop = when (corner) {
                0 -> Rect(p.x.coerceAtMost(crop.right-.10f), p.y.coerceAtMost(crop.bottom-.10f), crop.right, crop.bottom)
                1 -> Rect(crop.left, p.y.coerceAtMost(crop.bottom-.10f), p.x.coerceAtLeast(crop.left+.10f), crop.bottom)
                2 -> Rect(crop.left, crop.top, p.x.coerceAtLeast(crop.left+.10f), p.y.coerceAtLeast(crop.top+.10f))
                else -> Rect(p.x.coerceAtMost(crop.right-.10f), crop.top, crop.right, p.y.coerceAtLeast(crop.top+.10f))
            }
        })
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
    Button(enabled = enabled, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), onClick = {
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
    }) { Text("Use this crop") }
}
