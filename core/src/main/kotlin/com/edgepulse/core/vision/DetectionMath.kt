package com.edgepulse.core.vision

import kotlin.math.*

class LetterboxTransform(val rawWidth: Int, val rawHeight: Int, val rotation: Int, val inputSize: Int = 320) {
    init { require(rawWidth > 0 && rawHeight > 0 && rotation in intArrayOf(0, 90, 180, 270)) }
    val uprightWidth = if (rotation % 180 == 0) rawWidth else rawHeight
    val uprightHeight = if (rotation % 180 == 0) rawHeight else rawWidth
    val scale = min(inputSize.toFloat() / uprightWidth, inputSize.toFloat() / uprightHeight)
    val resizedWidth = (uprightWidth * scale).toInt()
    val resizedHeight = (uprightHeight * scale).toInt()
    val padX = (inputSize - resizedWidth) / 2
    val padY = (inputSize - resizedHeight) / 2
    fun rawToModelX(x: Float, y: Float): Float = (when (rotation) { 0 -> x; 90 -> rawHeight - y; 180 -> rawWidth - x; else -> y }) * scale + padX
    fun rawToModelY(x: Float, y: Float): Float = (when (rotation) { 0 -> y; 90 -> x; 180 -> rawHeight - y; else -> rawWidth - x }) * scale + padY
    fun modelToRawX(x: Float, y: Float): Float = when (rotation) { 0 -> (x-padX)/scale; 90 -> (y-padY)/scale; 180 -> rawWidth-(x-padX)/scale; else -> rawWidth-(y-padY)/scale }
    fun modelToRawY(x: Float, y: Float): Float = when (rotation) { 0 -> (y-padY)/scale; 90 -> rawHeight-(x-padX)/scale; 180 -> rawHeight-(y-padY)/scale; else -> (x-padX)/scale }
    fun uprightX(x: Float) = ((x - padX) / (scale * uprightWidth)).coerceIn(0f, 1f)
    fun uprightY(y: Float) = ((y - padY) / (scale * uprightHeight)).coerceIn(0f, 1f)
}

/** Reused primitive storage. Coordinates are normalized in the upright, uncropped image. */
class DetectionBuffer(val capacity: Int = 2100) {
    val left = FloatArray(capacity); val top = FloatArray(capacity); val right = FloatArray(capacity); val bottom = FloatArray(capacity)
    val scores = FloatArray(capacity); val classes = IntArray(capacity)
    var size = 0; private set
    fun clear() { size = 0 }
    fun add(l: Float, t: Float, r: Float, b: Float, score: Float, category: Int) {
        if (size >= capacity || r <= l || b <= t) return
        val i = size++; left[i] = l; top[i] = t; right[i] = r; bottom[i] = b; scores[i] = score; classes[i] = category
    }
    fun copyFrom(source: DetectionBuffer, i: Int) = add(source.left[i], source.top[i], source.right[i], source.bottom[i], source.scores[i], source.classes[i])
}

fun boxIou(l: Float, t: Float, r: Float, b: Float, x: Float, y: Float, z: Float, w: Float): Float {
    val intersection = max(0f, min(r, z) - max(l, x)) * max(0f, min(b, w) - max(t, y))
    val union = (r-l)*(b-t)+(z-x)*(w-y)-intersection
    return if (union > 0) intersection / union else 0f
}

class NonMaximumSuppression(private val capacity: Int = 2100) {
    private val order = IntArray(capacity)
    fun apply(source: DetectionBuffer, threshold: Float, destination: DetectionBuffer, limit: Int = 100) {
        destination.clear()
        for (i in 0 until source.size) order[i] = i
        // Select only the next highest score; no boxed sorting or per-frame lists.
        for (i in 0 until source.size) {
            var best = i
            for (j in i + 1 until source.size) if (source.scores[order[j]] > source.scores[order[best]]) best = j
            val swap = order[i]; order[i] = order[best]; order[best] = swap
            val candidate = order[i]
            var keep = true
            for (k in 0 until destination.size) if (source.classes[candidate] == destination.classes[k] &&
                boxIou(source.left[candidate], source.top[candidate], source.right[candidate], source.bottom[candidate],
                    destination.left[k], destination.top[k], destination.right[k], destination.bottom[k]) > threshold) { keep = false; break }
            if (keep) destination.copyFrom(source, candidate)
            if (destination.size >= limit) break
        }
    }
}

class YoloxPostProcessor {
    private val candidates = DetectionBuffer()
    private val nms = NonMaximumSuppression()
    fun decode(raw: FloatArray, transform: LetterboxTransform, threshold: Float, nmsThreshold: Float, output: DetectionBuffer) {
        require(raw.size == 2100 * 85)
        candidates.clear()
        var row = 0
        for (level in 0..2) {
            val stride = 8 shl level
            val grid = 320 / stride
            for (gy in 0 until grid) for (gx in 0 until grid) {
                val offset = row++ * 85
                val objectness = raw[offset + 4]
                if (!objectness.isFinite() || objectness < threshold) continue
                var category = 0; var classScore = 0f
                for (c in 0..79) if (raw[offset + 5 + c] > classScore) { classScore = raw[offset + 5 + c]; category = c }
                val score = objectness * classScore
                if (score < threshold || !score.isFinite()) continue
                val cx = (raw[offset] + gx) * stride; val cy = (raw[offset + 1] + gy) * stride
                val width = exp(raw[offset + 2].coerceIn(-10f, 10f)) * stride
                val height = exp(raw[offset + 3].coerceIn(-10f, 10f)) * stride
                if (!cx.isFinite() || !cy.isFinite()) continue
                candidates.add(transform.uprightX(cx-width/2), transform.uprightY(cy-height/2),
                    transform.uprightX(cx+width/2), transform.uprightY(cy+height/2), score.coerceIn(0f, 1f), category)
            }
        }
        nms.apply(candidates, nmsThreshold, output)
    }
}

/** Multiscale normalized appearance correlation; similarity is not a calibrated probability. */
class ReferenceMatcher(reference: FloatArray, private val referenceWidth: Int, private val referenceHeight: Int) {
    private val template = FloatArray(8 * 8 * 3)
    private val scratch = FloatArray(template.size)
    private var templateEnergy = 0.0
    val usable: Boolean
    private val candidates = DetectionBuffer()
    private val nms = NonMaximumSuppression()
    init {
        require(reference.size == referenceWidth * referenceHeight * 3)
        sample(reference, referenceWidth, referenceHeight, 0, 0, referenceWidth, referenceHeight, template)
        var spatialEnergy = 0.0
        for (c in 0..2) {
            var channelMean = 0f; for (i in 0 until 64) channelMean += template[i*3+c]; channelMean /= 64
            for (i in 0 until 64) { val difference = template[i*3+c]-channelMean; spatialEnergy += difference*difference }
        }
        usable = spatialEnergy >= 100
        var mean = 0f; for (v in template) mean += v; mean /= template.size
        for (i in template.indices) { template[i] -= mean; templateEnergy += template[i] * template[i] }
    }
    fun detect(image: FloatArray, width: Int, height: Int, threshold: Float, minWidth: Int, maxWidth: Int, output: DetectionBuffer) {
        require(image.size == width * height * 3 && minWidth > 0 && maxWidth >= minWidth)
        candidates.clear()
        if (!usable || templateEnergy < 100) { output.clear(); return }
        var window = minWidth
        while (window <= min(maxWidth, width)) {
            val h = max(4, window * referenceHeight / referenceWidth)
            if (h <= height) {
                val step = max(2, window / 8)
                var y = 0
                while (y + h <= height) {
                    var x = 0
                    while (x + window <= width) {
                        sample(image, width, height, x, y, window, h, scratch)
                        var mean = 0f; for (v in scratch) mean += v; mean /= scratch.size
                        var correlation = 0.0; var energy = 0.0
                        for (i in scratch.indices) { val value = scratch[i] - mean; correlation += value * template[i]; energy += value * value }
                        val score = if (energy > 100) (correlation / sqrt(energy * templateEnergy)).toFloat() else 0f
                        if (score >= threshold) candidates.add(x.toFloat()/width, y.toFloat()/height, (x+window).toFloat()/width, (y+h).toFloat()/height, score, 80)
                        x += step
                    }
                    y += step
                }
            }
            window = max(window + 1, (window * 1.25f).roundToInt())
        }
        nms.apply(candidates, .35f, output, 64)
    }
    private fun sample(image: FloatArray, width: Int, height: Int, x: Int, y: Int, w: Int, h: Int, output: FloatArray) {
        for (gy in 0..7) for (gx in 0..7) {
            val px = (x + (gx + .5f) * w / 8).toInt().coerceIn(0, width-1)
            val py = (y + (gy + .5f) * h / 8).toInt().coerceIn(0, height-1)
            val from = (py * width + px) * 3; val to = (gy * 8 + gx) * 3
            output[to] = image[from]; output[to+1] = image[from+1]; output[to+2] = image[from+2]
        }
    }
}
