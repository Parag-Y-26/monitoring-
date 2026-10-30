package com.edgepulse.core.camera

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class StationaryPipelineTest {
    @Test fun `manual rescan during burst is consumed and resets periodic schedule`() {
        val scan = RescanSchedule()
        val cadence = TemporalCadenceController(0)
        assertTrue(scan.requested(false,0)); assertTrue(cadence.update(true,0))
        assertFalse(scan.requested(false,100))
        assertTrue(scan.requested(true,200)); assertFalse(cadence.update(true,200))
        assertFalse(scan.requested(false,60_000)); assertTrue(scan.requested(false,60_200))
        scan.reset(); assertTrue(scan.requested(false,60_201))
    }
    @Test fun `unsigned stride aware luma ignores uniform exposure shifts`() {
        val plane = ByteBuffer.allocateDirect(32 * 70 + 3)
        plane.position(3)
        fun fill(value: Int) { for (y in 0..31) for (x in 0..31) plane.put(3 + y * 70 + x * 2, value.toByte()) }
        val gate = LumaMotionGate()
        fill(180); assertFalse(gate.evaluate(plane, 32, 32, 70, 2, 0))
        fill(230); assertFalse(gate.evaluate(plane, 32, 32, 70, 2, 100))
        assertEquals(0.0, gate.foregroundFraction, 0.0)
        assertEquals(3, plane.position())
    }
    @Test fun `stationary foreground remains awake until logged freeze timeout`() {
        val plane = ByteBuffer.allocateDirect(1024).apply { for (i in 0 until 1024) put(i, 80) }
        val gate = LumaMotionGate()
        gate.evaluate(plane, 32, 32, 32, 1, 0)
        for (i in 0 until 64) plane.put(i, 180.toByte())
        assertTrue(gate.evaluate(plane, 32, 32, 32, 1, 100))
        assertTrue(gate.evaluate(plane, 32, 32, 32, 1, 30_099))
        gate.evaluate(plane, 32, 32, 32, 1, 30_100)
        assertEquals(1L, gate.freezeTimeouts)
        assertFalse(gate.evaluate(plane, 32, 32, 32, 1, 30_200))
    }
    @Test fun `static doorway drops every frame after initialization`() {
        val plane = ByteBuffer.allocateDirect(1024).apply { for (i in 0 until 1024) put(i, (i % 200).toByte()) }
        val gate = LumaMotionGate()
        repeat(1000) { assertFalse(gate.evaluate(plane, 32, 32, 32, 1, it * 100L)) }
    }
    @Test fun `burst lasts three seconds extends for motion and measures actual duty`() {
        val cadence = TemporalCadenceController(0)
        assertFalse(cadence.update(false, 0)); assertFalse(cadence.isActive)
        assertTrue(cadence.update(true, 1000)); assertTrue(cadence.isActive)
        assertFalse(cadence.update(false, 3999)); assertTrue(cadence.isActive)
        cadence.update(false, 4000); assertFalse(cadence.isActive)
        assertEquals(60.0, cadence.dutyPercent(5000), 0.001)
        assertTrue(cadence.update(true, 5000))
        assertFalse(cadence.update(true, 6000))
        cadence.update(false, 8999); assertTrue(cadence.isActive)
        cadence.update(false, 9000); assertFalse(cadence.isActive)
    }
    @Test fun `idle duty starts at zero for a real nonzero system uptime`() {
        val cadence = TemporalCadenceController(9_000_000)
        cadence.update(false, 9_000_100)
        assertEquals(0.0, cadence.dutyPercent(9_000_200), 0.0)
    }
    @Test fun `raw preroll reuses two direct buffers with chronological stride correct planes`() {
        val ring = FramePreRollBuffer(4, 4)
        val y = ByteBuffer.allocateDirect(40).apply { position(2); for (i in 2..39) put(i, i.toByte()) }
        val u = ByteBuffer.allocateDirect(12).apply { put(0, 10); put(2, 11); put(6, 12); put(8, 13) }
        val v = ByteBuffer.allocateDirect(12).apply { put(0, 20); put(2, 21); put(6, 22); put(8, 23) }
        ring.copy(y, 8, 1, u, 6, 2, v, 6, 2, 90, 100)
        val original = ring.oldest(0).data
        assertTrue(original.isDirect)
        assertEquals(2.toByte(), original.get(0)); assertEquals(10.toByte(), original.get(4))
        assertEquals(13.toByte(), original.get(19)); assertEquals(23.toByte(), original.get(23))
        ring.copy(y, 8, 1, u, 6, 2, v, 6, 2, 180, 200)
        ring.copy(y, 8, 1, u, 6, 2, v, 6, 2, 270, 300)
        assertEquals(2, ring.size); assertEquals(200L, ring.oldest(0).elapsedMs)
        assertSame(original, ring.oldest(1).data); assertEquals(270, ring.oldest(1).rotationDegrees)
        ring.clear(); assertEquals(0, ring.size)
    }
    @Test fun `stopped sessions never alarm and stale gap begins at heartbeat`() {
        assertFalse(HeartbeatPolicy.isStale(false, 10, 60_000, true))
        assertFalse(HeartbeatPolicy.isStale(true, 10, 45_010, true))
        assertTrue(HeartbeatPolicy.isStale(true, 10, 45_011, true))
        assertTrue(HeartbeatPolicy.isStale(true, 10, 5, false))
    }
}
