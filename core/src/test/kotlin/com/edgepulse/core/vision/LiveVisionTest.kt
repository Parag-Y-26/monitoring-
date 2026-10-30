package com.edgepulse.core.vision

import org.junit.Assert.*
import org.junit.Test

class LiveVisionTest {
    @Test fun `confirmed boxes coast for 450ms but tracks expire after 1500ms`() {
        val tracker = ObjectTracker(); val detections = DetectionBuffer().apply { add(.2f,.2f,.4f,.4f,.9f,0) }
        tracker.update(detections,0); tracker.update(detections,100); tracker.update(detections,200)
        detections.clear(); tracker.update(detections,650)
        assertEquals(1,tracker.confirmedVisible); assertEquals(1,tracker.boxes.size)
        tracker.update(detections,651)
        assertEquals(0,tracker.confirmedVisible); assertEquals(1,tracker.size)
        tracker.update(detections,1700); assertEquals(1,tracker.size)
        tracker.update(detections,1701); assertEquals(0,tracker.size)
    }
    @Test fun `established nearby categories retain identity when their positions swap slightly`() {
        val tracker = ObjectTracker(); val detections = DetectionBuffer()
        fun frame(swapped: Boolean, time: Long) {
            detections.clear()
            val first = if (swapped) .21f else .20f; val second = if (swapped) .20f else .21f
            detections.add(first,.2f,first+.2f,.4f,.9f,0); detections.add(second,.2f,second+.2f,.4f,.9f,1)
            tracker.update(detections,time)
        }
        frame(false,0); frame(false,100)
        val id0 = tracker.ids[0]; val id1 = tracker.ids[1]
        frame(true,200)
        assertEquals(id0,tracker.ids[0]); assertEquals(id1,tracker.ids[1])
        assertEquals(0,tracker.boxes.classes[0]); assertEquals(1,tracker.boxes.classes[1])
        assertTrue(tracker.boxes.left[0] > tracker.boxes.left[1])
    }
    private fun texturedReference(): FloatArray = FloatArray(24*24*3) { i ->
        val x = i/3%24; val y = i/3/24
        ((x*17+y*31+(i%3)*11)%101).toFloat()
    }
    @Test fun `reference matcher tolerates independent channel illumination shifts`() {
        val reference = texturedReference()
        val shifted = reference.copyOf()
        for (i in shifted.indices) shifted[i] = reference[i]*(i%3+1) + (i%3)*70f
        val output = DetectionBuffer()
        ReferenceMatcher(reference,24,24).detect(shifted,24,24,.95f,24,24,output)
        assertEquals(1,output.size)
    }
    @Test fun `region matcher excludes padding and scores upright proposals outside scan grid`() {
        val reference = texturedReference()
        val image = FloatArray(320*320*3)
        fun place(x0: Int, y0: Int) {
            for (y in 0..23) for (x in 0..23) for(c in 0..2)
                image[((y0+y)*320+x0+x)*3+c] = reference[(y*24+x)*3+c]
        }
        place(0,0); place(101,107)
        val map = LetterboxTransform(640,480,0)
        val proposals = DetectionBuffer().apply { add(101f/320,(107f-40)/240,125f/320,(131f-40)/240,.8f,80) }
        val output = DetectionBuffer()
        ReferenceMatcher(reference,24,24).detectInRegion(image,320,320,0,40,320,240,.98f,24,24,proposals,map,output)
        assertEquals(1,output.size)
        assertEquals(101f/320,output.left[0],.001f)
        assertEquals(67f/240,output.top[0],.001f)
    }
    @Test fun `YOLOX keeps objectness proposals below category confidence and clears old proposals`() {
        val raw = FloatArray(2100*85)
        raw[0] = 10f; raw[1] = 10f; raw[4] = .21f; raw[5] = .1f
        val post = YoloxPostProcessor(); val output = DetectionBuffer()
        post.decode(raw,LetterboxTransform(320,320,0),.9f,.45f,output)
        assertEquals(0,output.size); assertEquals(1,post.proposals.size)
        raw.fill(0f)
        post.decode(raw,LetterboxTransform(320,320,0),.9f,.45f,output)
        assertEquals(0,post.proposals.size)
        assertEquals(.78f,ItemProfile("11111111-1111-1111-1111-111111111111","Bottle",classId=39).similarityThreshold,0f)
    }
    @Test fun `selected category uses its own score before class selection and NMS`() {
        val raw = FloatArray(2100 * 85)
        raw[0] = 10f; raw[1] = 10f
        raw[4] = .9f
        raw[5 + 21] = .8f // A competing animal class wins general-purpose argmax.
        raw[5 + 19] = .6f
        val detections = DetectionBuffer()
        val post = YoloxPostProcessor()
        val transform = LetterboxTransform(320, 320, 0)
        post.decode(raw, transform, .25f, .45f, detections)
        assertEquals(21, detections.classes[0])
        post.decode(raw, transform, .25f, .45f, detections, selectedClassId = 19)
        assertEquals(1, detections.size)
        assertEquals(19, detections.classes[0])
        assertEquals(.54f, detections.scores[0], .0001f)
        post.decode(raw, transform, .60f, .45f, detections, selectedClassId = 19)
        assertEquals(0, detections.size)
        post.decode(raw, transform, .25f, .45f, detections, selectedClassId = 39)
        assertEquals(0, detections.size)
        assertThrows(IllegalArgumentException::class.java) {
            post.decode(raw, transform, .25f, .45f, detections, selectedClassId = 80)
        }
    }
    @Test fun `every trained category can be selected without falling back to person`() {
        val raw = FloatArray(2100 * 85)
        val detections = DetectionBuffer()
        val post = YoloxPostProcessor()
        for (category in 0..79) {
            raw.fill(0f)
            raw[0] = 10f; raw[1] = 10f; raw[4] = .9f
            raw[5 + category] = .7f
            raw[5 + (category + 1) % 80] = .8f
            post.decode(raw, LetterboxTransform(320, 320, 0), .25f, .45f, detections, category)
            assertEquals(1, detections.size)
            assertEquals(category, detections.classes[0])
        }
    }
    @Test fun `category threshold persists and legacy targets keep their original default`() {
        val legacy = """{"id":"11111111-1111-1111-1111-111111111111","name":"Cow","classId":19}"""
        val profile = kotlinx.serialization.json.Json.decodeFromString<ItemProfile>(legacy)
        assertEquals(.25f, profile.detectionThreshold, 0f)
        val adjusted = profile.copy(detectionThreshold = .2f)
        adjusted.validate()
        val encoded = kotlinx.serialization.json.Json.encodeToString(ItemProfile.serializer(), adjusted)
        assertEquals(adjusted, kotlinx.serialization.json.Json.decodeFromString<ItemProfile>(encoded))
        for (invalid in listOf(.09f, .91f, Float.NaN)) {
            assertThrows(IllegalArgumentException::class.java) { profile.copy(detectionThreshold = invalid).validate() }
        }
    }
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
