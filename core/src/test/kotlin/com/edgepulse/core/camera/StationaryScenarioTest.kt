package com.edgepulse.core.camera

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class StationaryScenarioTest {
    @Test fun `two hour virtual static exposure scenario stays within five percent scan duty`() {
        val gate = LumaMotionGate()
        val start = 10_000L
        val cadence = TemporalCadenceController(start)
        val luma = ByteBuffer.allocateDirect(32*32)
        var lastScan = 0L
        var inferredFrames = 0
        var frames = 0
        // Policy simulation, not a two-hour camera, allocation or battery measurement.
        for (elapsed in 0L until 7_200_000L step 200L) {
            val now = start + elapsed
            val exposure = if ((elapsed / 20_000L) % 2L == 0L) 90 else 180
            for (i in 0 until luma.capacity()) luma.put(i, exposure.toByte())
            val motion = gate.evaluate(luma, 32, 32, 32, 1, now)
            assertFalse("Uniform exposure must not wake motion inference", motion)
            val rescan = lastScan == 0L || now-lastScan >= 60_000L
            if (cadence.update(motion || rescan, now)) lastScan = now
            if (cadence.isActive) inferredFrames++
            frames++
        }
        assertEquals(36_000, frames)
        assertTrue(inferredFrames.toDouble()/frames <= .05)
        assertEquals(5.0, cadence.dutyPercent(start+7_200_000L), .001)
        assertEquals(0L, gate.freezeTimeouts)
    }

    @Test fun `stationary foreground times out once and cadence returns to idle`() {
        val gate = LumaMotionGate()
        val cadence = TemporalCadenceController(0)
        val luma = ByteBuffer.allocateDirect(32*32)
        for (i in 0 until luma.capacity()) luma.put(i, 100.toByte())
        cadence.update(gate.evaluate(luma,32,32,32,1,0),0)
        for (i in 0 until 256) luma.put(i, 210.toByte())
        for (time in 100L..40_000L step 100L) cadence.update(gate.evaluate(luma,32,32,32,1,time),time)
        assertEquals(1L, gate.freezeTimeouts)
        assertFalse(cadence.isActive)
        assertTrue(cadence.dutyPercent(40_000) in 75.0..85.0)
    }
}
