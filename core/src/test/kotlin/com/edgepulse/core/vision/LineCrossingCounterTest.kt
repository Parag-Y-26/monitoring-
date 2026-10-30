package com.edgepulse.core.vision

import org.junit.Assert.*
import org.junit.Test

class LineCrossingCounterTest {
    @Test fun `successive camera trackers share unique session identity sequence`() {
        val ids = TrackIdSequence()
        val detections = DetectionBuffer().apply { add(.2f,.2f,.3f,.3f,.9f,0) }
        val first = ObjectTracker(idSequence = ids); first.update(detections,0)
        val second = ObjectTracker(idSequence = ids); second.update(detections,100)
        assertNotEquals(first.ids[0],second.ids[0])
        val before = second.ids[0]; second.reset(); second.update(detections,200)
        assertNotEquals(before,second.ids[0])
    }
    private class Journey(val x: Float = .5f) {
        val tracker = ObjectTracker()
        val counter = LineCrossingCounter()
        val detections = DetectionBuffer()
        var time = 0L
        fun see(y: Float, category: Int = 0): Int {
            detections.clear(); detections.add(x-.05f,y-.05f,x+.05f,y+.05f,.95f,category)
            tracker.update(detections,time); time += 100; counter.update(tracker)
            return counter.size
        }
    }
    @Test fun `horizontal line emits OUT and IN once per confirmed identity`() {
        for (direction in listOf(1,-1)) {
            val journey = Journey()
            fun position(y: Float) = if (direction == 1) y else 1f-y
            repeat(3) { assertEquals(0,journey.see(position(.35f))) }
            var events = 0
            for (step in 0..10) {
                events += journey.see(position(.35f+step*.03f))
                if (journey.counter.size > 0) {
                    assertEquals(direction,journey.counter.crossingDirections[0])
                    assertEquals(journey.tracker.boxIds[0],journey.counter.crossingIds[0])
                }
            }
            assertEquals(1,events)
            for (step in 10 downTo 0) assertEquals(0,journey.see(position(.35f+step*.03f)))
        }
    }
    @Test fun `deadband jitter and crossings beyond gate endpoints do not count`() {
        val jitter = Journey()
        repeat(12) { assertEquals(0,jitter.see(if (it%2 == 0) .49f else .51f)) }
        val outside = Journey(.95f)
        repeat(3) { outside.see(.35f) }
        for (step in 0..10) assertEquals(0,outside.see(.35f+step*.03f))
    }
    @Test fun `predicted coasting alone cannot generate crossing and expired slots are reusable`() {
        val journey = Journey()
        repeat(3) { journey.see(.35f) }
        journey.see(.39f); journey.see(.44f)
        journey.detections.clear()
        journey.tracker.update(journey.detections,journey.time+400)
        journey.counter.update(journey.tracker); assertEquals(0,journey.counter.size)
        journey.tracker.update(journey.detections,journey.time+2000)
        journey.counter.update(journey.tracker); assertEquals(0,journey.counter.size)
        journey.time += 2100
        repeat(3) { journey.see(.35f) }
        var events = 0; for (step in 0..10) events += journey.see(.35f+step*.03f)
        assertEquals(1,events)
    }
    @Test fun `invalid lines are rejected and diagonal gates use signed perpendicular distance`() {
        assertThrows(IllegalArgumentException::class.java) { LineCrossingCounter(.5f,.5f,.5f,.5f) }
        assertThrows(IllegalArgumentException::class.java) { LineCrossingCounter(deadband = Float.NaN) }
        val tracker = ObjectTracker(); val counter = LineCrossingCounter(.2f,.2f,.8f,.8f)
        val detections = DetectionBuffer()
        var events = 0
        for (step in 0..15) {
            val y = if (step < 3) .3f else .3f+(step-3)*.035f
            detections.clear(); detections.add(.45f,y-.04f,.55f,y+.04f,.9f,0)
            tracker.update(detections,step*100L); counter.update(tracker); events += counter.size
        }
        assertEquals(1,events)
    }
}
