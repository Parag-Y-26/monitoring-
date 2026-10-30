package com.edgepulse.core.vision

import kotlin.math.*

/** Shared by successive camera owners so thermal resumes cannot reuse session track IDs. */
class TrackIdSequence {
    private var nextId = 1L
    @Synchronized fun next(): Long = nextId++
}

/** Bounded four-state constant-velocity Kalman tracks with real elapsed intervals. */
class ObjectTracker(private val capacity: Int = 128, private val idSequence: TrackIdSequence = TrackIdSequence()) {
    private class Track {
        val x = DoubleArray(4)
        val p = DoubleArray(16)
        val votes = FloatArray(128)
        var width = 0f; var height = 0f; var hits = 0; var seen = 0L; var predicted = 0L; var score = 0f; var category = 0
        var id = 0L
    }
    private val slots = Array(capacity) { Track() }
    private val matched = BooleanArray(capacity)
    private val used = BooleanArray(2100)
    private val a = DoubleArray(16); private val b = DoubleArray(16); private val gain = DoubleArray(8)
    val ids = LongArray(capacity)
    val boxIds = LongArray(capacity)
    val boxObserved = BooleanArray(capacity)
    val boxes = DetectionBuffer(capacity)
    var size = 0; private set
    var confirmedVisible = 0; private set
    private var clock = -1L
    fun reset() { size = 0; confirmedVisible = 0; clock = -1; boxes.clear() }

    fun update(detections: DetectionBuffer, elapsedMs: Long) {
        require(elapsedMs >= 0 && elapsedMs >= clock)
        clock = elapsedMs
        var i = 0
        while (i < size) {
            if (elapsedMs - slots[i].seen > 1500) {
                val reusable = slots[i]; slots[i] = slots[size - 1]; slots[size - 1] = reusable; size--
            } else { predict(slots[i], elapsedMs); i++ }
        }
        matched.fill(false); used.fill(false)
        repeat(min(size, detections.size)) {
            var bestCost = 1.15; var bestTrack = -1; var bestDetection = -1
            for (t in 0 until size) if (!matched[t]) for (d in 0 until detections.size) if (!used[d]) {
                val value = cost(slots[t], detections, d)
                if (value < bestCost) { bestCost = value; bestTrack = t; bestDetection = d }
            }
            if (bestTrack >= 0) {
                correct(slots[bestTrack], detections, bestDetection, elapsedMs)
                matched[bestTrack] = true; used[bestDetection] = true
            }
        }
        for (d in 0 until detections.size) if (!used[d] && size < capacity) {
            val track = slots[size]
            track.x.fill(0.0); track.p.fill(0.0); track.votes.fill(0f)
            track.x[0] = ((detections.left[d] + detections.right[d]) / 2).toDouble()
            track.x[1] = ((detections.top[d] + detections.bottom[d]) / 2).toDouble()
            for (axis in 0..3) track.p[axis * 4 + axis] = if (axis < 2) .0025 else .05
            track.width = detections.right[d]-detections.left[d]; track.height = detections.bottom[d]-detections.top[d]
            track.hits = 0; track.seen = elapsedMs; track.predicted = elapsedMs; track.id = idSequence.next()
            correct(track, detections, d, elapsedMs); matched[size] = true; size++
        }
        boxes.clear(); confirmedVisible = 0
        for (t in 0 until size) {
            val track = slots[t]; ids[t] = track.id
            if (track.hits >= 3 && elapsedMs - track.seen <= 450L) {
                confirmedVisible++
                boxIds[boxes.size] = track.id; boxObserved[boxes.size] = track.seen == elapsedMs
                boxes.add((track.x[0]-track.width/2).toFloat().coerceIn(0f, 1f), (track.x[1]-track.height/2).toFloat().coerceIn(0f, 1f),
                    (track.x[0]+track.width/2).toFloat().coerceIn(0f, 1f), (track.x[1]+track.height/2).toFloat().coerceIn(0f, 1f), track.score, track.category)
            }
        }
    }

    private fun f(row: Int, column: Int, dt: Double): Double = if (row == column) 1.0 else if (row < 2 && column == row + 2) dt else 0.0
    private fun predict(track: Track, now: Long) {
        val dt = (now - track.predicted) / 1000.0
        track.x[0] += dt * track.x[2]; track.x[1] += dt * track.x[3]
        for (r in 0..3) for (c in 0..3) {
            var sum = 0.0; for (k in 0..3) sum += f(r, k, dt) * track.p[k*4+c]; a[r*4+c] = sum
        }
        for (r in 0..3) for (c in 0..3) {
            var sum = 0.0; for (k in 0..3) sum += a[r*4+k] * f(c, k, dt)
            val q = if (r % 2 != c % 2) 0.0 else when { r < 2 && c < 2 -> dt.pow(4)/4; r >= 2 && c >= 2 -> dt*dt; else -> dt.pow(3)/2 }
            track.p[r*4+c] = sum + .01*q
        }
        track.predicted = now
    }

    private fun correct(track: Track, detections: DetectionBuffer, index: Int, now: Long) {
        val confidence = detections.scores[index].coerceIn(.05f, 1f)
        val noise = .0004 / confidence
        val s00 = track.p[0] + noise; val s11 = track.p[5] + noise
        val s01 = track.p[1]; val s10 = track.p[4]
        val determinant = max(1e-12, s00*s11-s01*s10)
        for (r in 0..3) {
            gain[r*2] = (track.p[r*4]*s11-track.p[r*4+1]*s10)/determinant
            gain[r*2+1] = (track.p[r*4+1]*s00-track.p[r*4]*s01)/determinant
        }
        val dx = (detections.left[index]+detections.right[index])/2-track.x[0]
        val dy = (detections.top[index]+detections.bottom[index])/2-track.x[1]
        for (r in 0..3) track.x[r] += gain[r*2]*dx+gain[r*2+1]*dy
        // Joseph covariance update preserves positive measurement uncertainty.
        for (r in 0..3) for (c in 0..3) a[r*4+c] = (if (r == c) 1.0 else 0.0) - (if (c < 2) gain[r*2+c] else 0.0)
        for (r in 0..3) for (c in 0..3) { var sum = 0.0; for (k in 0..3) sum += a[r*4+k]*track.p[k*4+c]; b[r*4+c] = sum }
        for (r in 0..3) for (c in 0..3) {
            var sum = noise*(gain[r*2]*gain[c*2]+gain[r*2+1]*gain[c*2+1])
            for (k in 0..3) sum += b[r*4+k]*a[c*4+k]
            track.p[r*4+c] = if (r == c) max(1e-9, sum) else sum
        }
        track.width = .7f*(detections.right[index]-detections.left[index])+.3f*track.width
        track.height = .7f*(detections.bottom[index]-detections.top[index])+.3f*track.height
        for (c in track.votes.indices) track.votes[c] *= .98f
        val category = detections.classes[index].coerceIn(0, 127)
        track.votes[category] += confidence
        var winner = 0; for (c in track.votes.indices) if (track.votes[c] > track.votes[winner]) winner = c
        track.category = winner; track.score = confidence; track.hits++; track.seen = now
    }

    private fun cost(track: Track, detections: DetectionBuffer, i: Int): Double {
        val cx = (detections.left[i]+detections.right[i])/2; val cy = (detections.top[i]+detections.bottom[i])/2
        val distance = hypot(track.x[0]-cx, track.x[1]-cy)
        if (distance > .35) return Double.POSITIVE_INFINITY
        val l = (track.x[0]-track.width/2).toFloat(); val t = (track.x[1]-track.height/2).toFloat()
        val r = l+track.width; val btm = t+track.height
        val dl = detections.left[i]; val dt = detections.top[i]; val dr = detections.right[i]; val db = detections.bottom[i]
        val intersection = max(0f, min(r, dr)-max(l, dl))*max(0f, min(btm, db)-max(t, dt))
        val area = max(1e-6f, track.width*track.height); val other = max(1e-6f, (dr-dl)*(db-dt))
        val union = area+other-intersection; val enclosing = max(1e-6f, (max(r, dr)-min(l, dl))*(max(btm, db)-min(t, dt)))
        val giou = intersection/union-(enclosing-union)/enclosing
        return .6*(1-giou)+.25*distance/.35+.15*min(2.0, abs(ln(other/area).toDouble()))+
            if (track.hits >= 2 && detections.classes[i] != track.category) .35 else 0.0
    }
}

/** Finite directed gate in upright normalized coordinates. Positive side changes are OUT. */
class LineCrossingCounter(private val ax: Float = .2f, private val ay: Float = .5f,
    private val bx: Float = .8f, private val by: Float = .5f, private val deadband: Float = .03f,
    capacity: Int = 128) {
    private val dx = bx-ax; private val dy = by-ay
    private val length = hypot(dx,dy)
    init {
        require(capacity > 0 && ax in 0f..1f && ay in 0f..1f && bx in 0f..1f && by in 0f..1f)
        require(length > 1e-6f && deadband in 0f..1f)
    }
    private val keys = LongArray(capacity) { -1L }
    private val sides = IntArray(capacity)
    private val previousX = FloatArray(capacity); private val previousY = FloatArray(capacity)
    private val counted = BooleanArray(capacity)
    val crossingIds = LongArray(capacity)
    val crossingDirections = IntArray(capacity)
    var size = 0; private set
    fun reset() { keys.fill(-1L); sides.fill(0); counted.fill(false); size = 0 }
    fun update(tracker: ObjectTracker) {
        size = 0
        // Keep state through occlusion, reclaim only identities that actually expired.
        for (slot in keys.indices) if (keys[slot] >= 0) {
            var alive = false
            for (i in 0 until tracker.size) if (tracker.ids[i] == keys[slot]) { alive = true; break }
            if (!alive) { keys[slot] = -1; sides[slot] = 0; counted[slot] = false }
        }
        for (i in 0 until tracker.boxes.size) {
            if (!tracker.boxObserved[i]) continue // Predicted motion is not crossing evidence.
            val id = tracker.boxIds[i]
            var slot = -1; var free = -1
            for (j in keys.indices) { if (keys[j] == id) { slot = j; break }; if (keys[j] < 0 && free < 0) free = j }
            if (slot < 0) { slot = free; if (slot < 0) continue; keys[slot] = id }
            if (counted[slot]) continue
            val boxes = tracker.boxes
            val x = (boxes.left[i]+boxes.right[i])*.5f; val y = (boxes.top[i]+boxes.bottom[i])*.5f
            val distance = (dx*(y-ay)-dy*(x-ax))/length
            val side = when { distance > deadband -> 1; distance < -deadband -> -1; else -> 0 }
            if (side == 0) continue
            if (sides[slot] != 0 && side != sides[slot]) {
                val oldDistance = dx*(previousY[slot]-ay)-dy*(previousX[slot]-ax)
                val fraction = oldDistance/(oldDistance-distance*length)
                val ix = previousX[slot]+fraction*(x-previousX[slot])
                val iy = previousY[slot]+fraction*(y-previousY[slot])
                val along = ((ix-ax)*dx+(iy-ay)*dy)/(length*length)
                if (along in 0f..1f) {
                    crossingIds[size] = id; crossingDirections[size] = side; size++; counted[slot] = true
                }
            }
            sides[slot] = side; previousX[slot] = x; previousY[slot] = y
        }
    }
}
