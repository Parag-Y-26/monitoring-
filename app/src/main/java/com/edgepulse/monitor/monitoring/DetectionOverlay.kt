package com.edgepulse.monitor.monitoring

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import com.edgepulse.core.vision.DetectionBuffer

/** Analyzer and main thread exchange bounded primitive arrays, not per-frame object lists. */
class DetectionOverlay {
    val lock = Any()
    val left = FloatArray(128); val top = FloatArray(128); val right = FloatArray(128); val bottom = FloatArray(128)
    val scores = FloatArray(128); val classes = IntArray(128)
    var size = 0; private set
    var uprightWidth = 640; private set
    var uprightHeight = 480; private set
    var revision by mutableLongStateOf(0L); private set
    fun copyFrom(boxes: DetectionBuffer, width: Int, height: Int) {
        size = boxes.size.coerceAtMost(128)
        boxes.left.copyInto(left, endIndex = size); boxes.top.copyInto(top, endIndex = size)
        boxes.right.copyInto(right, endIndex = size); boxes.bottom.copyInto(bottom, endIndex = size)
        boxes.scores.copyInto(scores, endIndex = size); boxes.classes.copyInto(classes, endIndex = size)
        uprightWidth = width; uprightHeight = height
    }
    fun changed() { revision++ }
    fun clear() { size = 0; revision++ }
}
