package com.edgepulse.core.vision

import org.junit.Assert.*
import org.junit.Test

class PreviewProjectionTest {
    @Test fun `caption stays below status island and above floating controls`() {
        val underStatus = previewCaptionBaseline(206f,22f,1136f,208f,750f)
        assertTrue(underStatus-22f-4f >= 208f)
        val underControls = previewCaptionBaseline(900f,22f,1136f,208f,750f)
        assertTrue(underControls+4f <= 750f)
        assertEquals(400f,previewCaptionBaseline(400f,22f,1136f,208f,750f),.001f)
        assertTrue(previewCaptionBaseline(100f,22f,360f,300f,320f).isNaN())
    }
    @Test fun `portrait crop fills view and maps the source center without stretching`() {
        val p = PreviewProjection().apply { configure(480, 640, 360f, 800f) }
        assertEquals(-120f, p.x(0f), .001f)
        assertEquals(480f, p.x(1f), .001f)
        assertEquals(0f, p.y(0f), .001f)
        assertEquals(800f, p.y(1f), .001f)
        assertEquals(180f, p.x(.5f), .001f)
        assertEquals(400f, p.y(.5f), .001f)
    }
    @Test fun `all upright rotations stay centered in portrait and landscape views`() {
        for (rotation in listOf(0,90,180,270)) for ((vw,vh) in listOf(360f to 800f,800f to 360f)) {
            val upright = LetterboxTransform(640,480,rotation)
            val p = PreviewProjection().apply { configure(upright.uprightWidth,upright.uprightHeight,vw,vh) }
            assertEquals(vw/2,p.x(.5f),.001f)
            assertEquals(vh/2,p.y(.5f),.001f)
            assertTrue(p.x(0f) <= .001f && p.x(1f) >= vw-.001f)
            assertTrue(p.y(0f) <= .001f && p.y(1f) >= vh-.001f)
            assertEquals((p.x(1f)-p.x(0f))/upright.uprightWidth,(p.y(1f)-p.y(0f))/upright.uprightHeight,.001f)
        }
    }
}
