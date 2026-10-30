package com.edgepulse.core.camera

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Reused sample/background storage; no RGB conversion or result allocation per frame. */
class LumaMotionGate {
    private val samples = FloatArray(1024)
    private val background = FloatArray(1024)
    private var initialized = false
    private var freezeStart = -1L
    var foregroundFraction = 0.0; private set
    var freezeTimeouts = 0L; private set

    fun evaluate(y: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, nowMs: Long): Boolean {
        require(width > 0 && height > 0 && rowStride > 0 && pixelStride > 0 && nowMs >= 0)
        val base = y.position()
        var mean = 0f
        for (gy in 0..31) for (gx in 0..31) {
            val sy = ((2 * gy + 1) * height / 64).coerceAtMost(height - 1)
            val sx = ((2 * gx + 1) * width / 64).coerceAtMost(width - 1)
            val value = (y.get(base + sy * rowStride + sx * pixelStride).toInt() and 255).toFloat()
            samples[gy * 32 + gx] = value; mean += value
        }
        mean /= 1024f
        var foreground = 0
        for (i in 0 until 1024) {
            samples[i] -= mean
            if (initialized && abs(samples[i] - background[i]) > 18f) foreground++
        }
        foregroundFraction = foreground / 1024.0
        if (!initialized) { samples.copyInto(background); initialized = true; return false }
        val motion = foregroundFraction >= 0.035
        if (motion) {
            if (freezeStart < 0) freezeStart = nowMs
            if (nowMs - freezeStart >= 30_000) {
                freezeTimeouts++; samples.copyInto(background); freezeStart = -1
            } else for (i in 0 until 1024) if (abs(samples[i] - background[i]) <= 18f)
                background[i] += 0.02f * (samples[i] - background[i])
        } else {
            freezeStart = -1
            for (i in 0 until 1024) background[i] += 0.02f * (samples[i] - background[i])
        }
        return motion
    }
}

/** Manual requests are evaluated on every frame, independently of burst wakeup. */
class RescanSchedule(private val intervalMs: Long = 60_000) {
    init { require(intervalMs > 0) }
    private var lastScan = -1L
    fun reset() { lastScan = -1L }
    fun requested(manual: Boolean, nowMs: Long): Boolean {
        require(nowMs >= 0)
        val periodic = lastScan < 0 || nowMs-lastScan >= intervalMs
        if (manual || periodic) { lastScan = nowMs; return true }
        return false
    }
}

class TemporalCadenceController(private val startedMs: Long) {
    private var lastMs = startedMs
    private var burstUntil = Long.MIN_VALUE
    private var activeMs = 0L
    var isActive = false; private set
    /** True once on wakeup; caller visits the raw pre-roll before the current frame. */
    fun update(motion: Boolean, nowMs: Long): Boolean {
        account(nowMs)
        val woke = motion && nowMs >= burstUntil
        if (motion) burstUntil = nowMs + 3000
        isActive = nowMs < burstUntil
        return woke
    }
    fun dutyPercent(nowMs: Long): Double {
        account(nowMs)
        return if (nowMs == startedMs) 0.0 else 100.0 * activeMs / (nowMs - startedMs)
    }
    private fun account(nowMs: Long) {
        require(nowMs >= lastMs)
        if (burstUntil > lastMs) activeMs += min(nowMs, burstUntil) - lastMs
        lastMs = nowMs
    }
}

class FramePreRollBuffer(private val width: Int = 640, private val height: Int = 480) {
    class RawYuvSlot(bytes: Int) {
        val data: ByteBuffer = ByteBuffer.allocateDirect(bytes)
        var rotationDegrees = 0; internal set
        var elapsedMs = 0L; internal set
    }
    private val chromaWidth = (width + 1) / 2
    private val chromaHeight = (height + 1) / 2
    private val slots = Array(2) { RawYuvSlot(width * height + 2 * chromaWidth * chromaHeight) }
    private var next = 0
    var size = 0; private set
    fun oldest(index: Int): RawYuvSlot { require(index in 0 until size); return slots[(next - size + index + 2) % 2] }
    fun clear() { size = 0; next = 0 }
    fun copy(y: ByteBuffer, yr: Int, yp: Int, u: ByteBuffer, ur: Int, up: Int, v: ByteBuffer, vr: Int, vp: Int, rotation: Int, elapsedMs: Long) {
        val slot = slots[next]
        copyPlane(y, yr, yp, width, height, slot.data, 0)
        copyPlane(u, ur, up, chromaWidth, chromaHeight, slot.data, width * height)
        copyPlane(v, vr, vp, chromaWidth, chromaHeight, slot.data, width * height + chromaWidth * chromaHeight)
        slot.rotationDegrees = rotation; slot.elapsedMs = elapsedMs
        next = (next + 1) % 2; size = min(2, size + 1)
    }
    private fun copyPlane(source: ByteBuffer, row: Int, pixel: Int, w: Int, h: Int, destination: ByteBuffer, offset: Int) {
        val base = source.position()
        if (pixel == 1 && row == w) {
            val limit = source.limit(); val position = destination.position()
            try { source.limit(base + w*h); destination.position(offset); destination.put(source) }
            finally { source.limit(limit); source.position(base); destination.position(position) }
            return
        }
        for (y in 0 until h) for (x in 0 until w) destination.put(offset + y * w + x, source.get(base + y * row + x * pixel))
    }
}

object HeartbeatPolicy {
    fun isStale(active: Boolean, lastMs: Long, nowMs: Long, sameBoot: Boolean): Boolean =
        active && (!sameBoot || nowMs < lastMs || nowMs - lastMs > 45_000)
}
