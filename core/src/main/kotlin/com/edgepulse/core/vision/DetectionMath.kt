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
    private val candidatesProposals = DetectionBuffer()
    val proposals = DetectionBuffer(256)
    private val nms = NonMaximumSuppression()
    fun decode(raw: FloatArray, transform: LetterboxTransform, threshold: Float, nmsThreshold: Float, output: DetectionBuffer,
        selectedClassId: Int? = null) {
        require(raw.size == 2100 * 85)
        require(selectedClassId == null || selectedClassId in 0..79)
        candidates.clear(); candidatesProposals.clear()
        var row = 0
        for (level in 0..2) {
            val stride = 8 shl level
            val grid = 320 / stride
            for (gy in 0 until grid) for (gx in 0 until grid) {
                val offset = row++ * 85
                val objectness = raw[offset + 4]
                if (!objectness.isFinite() || objectness < min(.20f, threshold)) continue
                // A selected target must pass on its own score, even when another class scores higher.
                var category = selectedClassId ?: 0
                var classScore = if (selectedClassId != null) raw[offset + 5 + selectedClassId] else 0f
                if (selectedClassId == null) for (c in 0..79) if (raw[offset + 5 + c] > classScore) {
                    classScore = raw[offset + 5 + c]; category = c
                }
                val score = objectness * classScore
                val cx = (raw[offset] + gx) * stride; val cy = (raw[offset + 1] + gy) * stride
                val width = exp(raw[offset + 2].coerceIn(-10f, 10f)) * stride
                val height = exp(raw[offset + 3].coerceIn(-10f, 10f)) * stride
                if (!cx.isFinite() || !cy.isFinite()) continue
                if (objectness >= .20f) candidatesProposals.add(transform.uprightX(cx-width/2), transform.uprightY(cy-height/2),
                    transform.uprightX(cx+width/2), transform.uprightY(cy+height/2), objectness.coerceAtMost(1f), 80)
                if (score < threshold || !score.isFinite()) continue
                candidates.add(transform.uprightX(cx-width/2), transform.uprightY(cy-height/2),
                    transform.uprightX(cx+width/2), transform.uprightY(cy+height/2), score.coerceIn(0f, 1f), category)
            }
        }
        nms.apply(candidates, nmsThreshold, output)
        nms.apply(candidatesProposals, .45f, proposals, 64)
    }
}

/** Reused channel-normalized appearance and luma-gradient features; scores are not probabilities. */
class ReferenceMatcher(reference: FloatArray, private val referenceWidth: Int, private val referenceHeight: Int) {
    private val template = FloatArray(12 * 12 * 3)
    private val scratch = FloatArray(template.size)
    private val channelEnergy = DoubleArray(3)
    private val scratchEnergy = DoubleArray(3)
    private val gradTemplate = FloatArray(144)
    private val gradScratch = FloatArray(144)
    private val luma = FloatArray(144)
    val usable: Boolean
    private val candidates = DetectionBuffer()
    private val nms = NonMaximumSuppression()
    init {
        require(referenceWidth > 0 && referenceHeight > 0 && reference.size == referenceWidth * referenceHeight * 3)
        sample(reference, referenceWidth, referenceHeight, 0, 0, referenceWidth, referenceHeight, template)
        gradients(template, gradTemplate)
        usable = normalize(template, channelEnergy) >= 100
    }
    private fun normalize(values: FloatArray, energies: DoubleArray): Double {
        var total = 0.0
        for (c in 0..2) {
            var mean = 0.0; for (i in 0 until 144) mean += values[i*3+c]; mean /= 144
            var energy = 0.0
            for (i in 0 until 144) { val d = values[i*3+c]-mean; values[i*3+c] = d.toFloat(); energy += d*d }
            energies[c] = energy; total += energy
            val scale = if (energy > 1e-6) (1/sqrt(energy)).toFloat() else 0f
            for (i in 0 until 144) values[i*3+c] *= scale
        }
        return total
    }
    private fun gradients(values: FloatArray, output: FloatArray) {
        for (i in 0 until 144) luma[i] = .114f*values[i*3]+.587f*values[i*3+1]+.299f*values[i*3+2]
        var energy = 0.0
        for (y in 0..11) for (x in 0..11) {
            val dx = luma[y*12+min(11,x+1)]-luma[y*12+max(0,x-1)]
            val dy = luma[min(11,y+1)*12+x]-luma[max(0,y-1)*12+x]
            val magnitude = sqrt(dx*dx+dy*dy); output[y*12+x] = magnitude; energy += magnitude*magnitude
        }
        val scale = if (energy > 1e-6) (1/sqrt(energy)).toFloat() else 0f
        for (i in output.indices) output[i] *= scale
    }
    private fun score(image: FloatArray, width: Int, height: Int, x: Int, y: Int, w: Int, h: Int): Float {
        sample(image,width,height,x,y,w,h,scratch)
        gradients(scratch,gradScratch)
        if (normalize(scratch,scratchEnergy) < 100) return 0f
        var zncc = 0f; var channels = 0
        for (c in 0..2) if (channelEnergy[c] > 1e-6) {
            var dot = 0f; for (i in 0 until 144) dot += template[i*3+c]*scratch[i*3+c]
            zncc += dot; channels++
        }
        var gradient = 0f; for (i in 0 until 144) gradient += gradTemplate[i]*gradScratch[i]
        return (.65f*zncc/channels.coerceAtLeast(1)+.35f*gradient).coerceIn(-1f,1f)
    }
    fun detect(image: FloatArray, width: Int, height: Int, threshold: Float, minWidth: Int, maxWidth: Int, output: DetectionBuffer) {
        detectInRegion(image,width,height,0,0,width,height,threshold,minWidth,maxWidth,null,null,output)
    }
    /** Outputs upright normalized boxes when transform is supplied, otherwise full-image normalized boxes. */
    fun detectInRegion(image: FloatArray, width: Int, height: Int, activeLeft: Int, activeTop: Int,
        activeWidth: Int, activeHeight: Int, threshold: Float, minWidth: Int, maxWidth: Int,
        proposals: DetectionBuffer?, transform: LetterboxTransform?, output: DetectionBuffer) {
        require(width > 0 && height > 0 && image.size == width * height * 3 && minWidth > 0 && maxWidth >= minWidth)
        require(activeLeft >= 0 && activeTop >= 0 && activeWidth > 0 && activeHeight > 0 &&
            activeLeft+activeWidth <= width && activeTop+activeHeight <= height && threshold in -1f..1f)
        candidates.clear()
        if (!usable) { output.clear(); return }
        if (proposals != null && transform != null) for (i in 0 until proposals.size) {
            val l = (proposals.left[i]*transform.scale*transform.uprightWidth+transform.padX).roundToInt()
            val t = (proposals.top[i]*transform.scale*transform.uprightHeight+transform.padY).roundToInt()
            val r = (proposals.right[i]*transform.scale*transform.uprightWidth+transform.padX).roundToInt()
            val b = (proposals.bottom[i]*transform.scale*transform.uprightHeight+transform.padY).roundToInt()
            if (l >= activeLeft && t >= activeTop && r <= activeLeft+activeWidth && b <= activeTop+activeHeight &&
                r-l in minWidth..maxWidth && b > t) evaluate(image,width,height,l,t,r-l,b-t,threshold,transform)
        }
        var window = minWidth
        while (window <= min(maxWidth, activeWidth)) {
            val h = max(4, window * referenceHeight / referenceWidth)
            if (h <= activeHeight) {
                val step = max(2, window / 8)
                var y = activeTop
                while (y + h <= activeTop+activeHeight) {
                    var x = activeLeft
                    while (x + window <= activeLeft+activeWidth) {
                        evaluate(image,width,height,x,y,window,h,threshold,transform)
                        x += step
                    }
                    y += step
                }
            }
            window = max(window + 1, (window * 1.25f).roundToInt())
        }
        nms.apply(candidates, .35f, output, 64)
    }
    private fun evaluate(image: FloatArray, width: Int, height: Int, x: Int, y: Int, w: Int, h: Int, threshold: Float, transform: LetterboxTransform?) {
        val confidence = score(image,width,height,x,y,w,h)
        if (confidence < threshold) return
        candidates.add(transform?.uprightX(x.toFloat()) ?: x.toFloat()/width, transform?.uprightY(y.toFloat()) ?: y.toFloat()/height,
            transform?.uprightX((x+w).toFloat()) ?: (x+w).toFloat()/width, transform?.uprightY((y+h).toFloat()) ?: (y+h).toFloat()/height,confidence,80)
    }
    private fun sample(image: FloatArray, width: Int, height: Int, x: Int, y: Int, w: Int, h: Int, output: FloatArray) {
        for (gy in 0..11) for (gx in 0..11) {
            val to = (gy*12+gx)*3
            output[to] = 0f; output[to+1] = 0f; output[to+2] = 0f
            for (sy in 0..1) for (sx in 0..1) {
                val px = (x+(gx+(sx+.5f)/2)*w/12).toInt().coerceIn(x,min(width-1,x+w-1))
                val py = (y+(gy+(sy+.5f)/2)*h/12).toInt().coerceIn(y,min(height-1,y+h-1))
                val from = (py*width+px)*3
                for(c in 0..2) output[to+c] += image[from+c]*.25f
            }
        }
    }
}
