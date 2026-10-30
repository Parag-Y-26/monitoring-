package com.edgepulse.core.vision

import org.junit.Assert.*
import org.junit.Test

class LiveVisionTest {
    @Test fun `letterbox round trip preserves raw points for all four rotations`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val box = LetterboxTransform(640, 480, rotation)
            for ((x, y) in listOf(0f to 0f, 640f to 480f, 123f to 321f, 320f to 240f)) {
                val mx = box.rawToModelX(x, y); val my = box.rawToModelY(x, y)
                assertEquals(x, box.modelToRawX(mx, my), 0.001f)
                assertEquals(y, box.modelToRawY(mx, my), 0.001f)
            }
            assertEquals(320, box.resizedWidth.coerceAtLeast(box.resizedHeight))
        }
    }
    @Test fun `YOLOX decodes each grid stride and uses class aware NMS`() {
        val raw = FloatArray(2100 * 85)
        fun candidate(row: Int, dx: Float, category: Int, score: Float) {
            val offset = row * 85
            raw[offset] = dx; raw[offset + 1] = 10f
            raw[offset + 4] = 0.9f; raw[offset + 5 + category] = score
        }
        candidate(0, 10f, 0, 0.9f); candidate(1, 9f, 0, 0.8f); candidate(2, 8f, 1, 0.8f)
        val detections = DetectionBuffer()
        YoloxPostProcessor().decode(raw, LetterboxTransform(320, 320, 0), 0.25f, 0.45f, detections)
        assertEquals(2, detections.size)
        assertEquals(0.81f, detections.scores[0], 0.0001f)
        assertEquals(76f / 320f, detections.left[0], 0.0001f)
        assertEquals(84f / 320f, detections.bottom[0], 0.0001f)
        assertEquals(0, detections.classes[0]); assertEquals(1, detections.classes[1])
    }
    @Test fun `tracker confirms on third hit holds short occlusion and expires after 1500ms`() {
        val tracker = ObjectTracker()
        val detections = DetectionBuffer()
        fun see(time: Long) { detections.clear(); detections.add(.2f, .2f, .4f, .4f, .9f, 0); tracker.update(detections, time) }
        see(0); assertEquals(0, tracker.confirmedVisible)
        see(100); assertEquals(0, tracker.confirmedVisible)
        see(200); assertEquals(1, tracker.confirmedVisible)
        val id = tracker.ids[0]
        detections.clear(); tracker.update(detections, 1000)
        see(1600); assertEquals(id, tracker.ids[0]); assertEquals(1, tracker.confirmedVisible)
        detections.clear(); tracker.update(detections, 3101)
        assertEquals(0, tracker.size)
        see(3200); assertNotEquals(id, tracker.ids[0]); assertEquals(0, tracker.confirmedVisible)
    }
    @Test fun `reference matching locates two exemplars without a COCO category`() {
        val reference = FloatArray(16 * 16 * 3)
        for (y in 0..15) for (x in 0..15) {
            val i = (y * 16 + x) * 3
            reference[i] = if (x < 8) 220f else 30f
            reference[i + 1] = if (y < 8) 150f else 10f
            reference[i + 2] = if (x < 8) 20f else 170f
        }
        val image = FloatArray(64 * 64 * 3) { 70f }
        for (start in listOf(4 to 8, 36 to 36)) for (y in 0..15) for (x in 0..15) for (c in 0..2)
            image[((start.second + y) * 64 + start.first + x) * 3 + c] = reference[(y * 16 + x) * 3 + c]
        val matches = DetectionBuffer()
        ReferenceMatcher(reference, 16, 16).detect(image, 64, 64, .94f, 16, 16, matches)
        assertEquals(2, matches.size)
        assertTrue(matches.scores[0] >= .94f)
    }
    @Test fun `target profiles validate supported class and private reference names`() {
        val id = "11111111-1111-1111-1111-111111111111"
        ItemProfile(id,"Bottle",classId=39).validate()
        ItemProfile(id,"Custom",referenceFilename="$id.webp.enc").validate()
        assertThrows(IllegalArgumentException::class.java) { ItemProfile(id,"",classId=39).validate() }
        assertThrows(IllegalArgumentException::class.java) { ItemProfile(id,"Bad class",classId=80).validate() }
        assertThrows(IllegalArgumentException::class.java) { ItemProfile(id,"Bad file",referenceFilename="../secret").validate() }
    }
    @Test fun `rotation reset removes stale tracks and low texture reference is refused`() {
        val tracker = ObjectTracker()
        val detections = DetectionBuffer().apply { add(.1f,.1f,.3f,.3f,.9f,0) }
        tracker.update(detections,100)
        tracker.reset()
        assertEquals(0,tracker.size)
        assertEquals(0,tracker.boxes.size)
        val flat = FloatArray(16*16*3) { if(it%3==0) 200f else 50f }
        val result = DetectionBuffer()
        val matcher = ReferenceMatcher(flat,16,16)
        assertFalse(matcher.usable)
        matcher.detect(flat,16,16,.9f,16,16,result)
        assertEquals(0,result.size)
    }
}
