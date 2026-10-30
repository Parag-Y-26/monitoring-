package com.edgepulse.monitor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.test.platform.app.InstrumentationRegistry
import com.edgepulse.core.audit.AuditCanonical
import com.edgepulse.core.vision.ObjectTracker
import com.edgepulse.monitor.vision.ml.YoloxDetector
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

/** Real COCO -> camera YUV -> packaged INT8 -> selected-category decode -> confirmed count. */
class SelectedCategoryInstrumentedTest {
    @Test fun cowsBottlesDogsAndChairsReachConfirmedCountsUsingTheirOwnClassScore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val cases = listOf(
            Triple("000000573626.jpg", 19, "71ec645db592393c36812f60f8d1744f2f473679bd3d2d86eeb2c3f732bf7f28"),
            Triple("000000513283.jpg", 39, "91725f1fcd58873d1ded35bcbca2de0c4dbc11188c3a8795a81b1d928b942b9d"),
            Triple("000000269113.jpg", 16, "5cae45e13f5cda50f16266e773aaad6115965d518fd5e4bff4c40bc24126fcd4"),
            Triple("000000081061.jpg", 56, "d609877d71bb235a132923cb287d90f3d0d0cf3299f01dc4b6b8b660240e1544")
        )
        val names = context.assets.open("models/coco_labels.txt").bufferedReader().use { it.readLines() }
        val y = ByteBuffer.allocateDirect(640 * 480)
        val u = ByteBuffer.allocateDirect(320 * 240)
        val v = ByteBuffer.allocateDirect(320 * 240)
        val missing = mutableListOf<String>()
        YoloxDetector(context, "XNNPACK_INT8").use { detector ->
            for ((filename, category, hash) in cases) {
                val bytes = assets.open("vision/$filename").use { it.readBytes() }
                assertEquals(hash, AuditCanonical.hex(AuditCanonical.hash(bytes)))
                val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val frame = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(frame)
                    canvas.drawColor(Color.rgb(114, 114, 114))
                    val scale = minOf(640f / source.width, 480f / source.height)
                    val px = (640 - source.width * scale) / 2
                    val py = (480 - source.height * scale) / 2
                    canvas.drawBitmap(source, null, RectF(px, py, px + source.width * scale, py + source.height * scale), null)
                    for (sy in 0 until 480) for (sx in 0 until 640) {
                        val color = frame.getPixel(sx, sy)
                        val red = color shr 16 and 255; val green = color shr 8 and 255; val blue = color and 255
                        y.put(sy * 640 + sx, (16 + .257 * red + .504 * green + .098 * blue).roundToInt().coerceIn(0, 255).toByte())
                        if (sx % 2 == 0 && sy % 2 == 0) {
                            val offset = sy / 2 * 320 + sx / 2
                            u.put(offset, (128 - .148 * red - .291 * green + .439 * blue).roundToInt().coerceIn(0, 255).toByte())
                            v.put(offset, (128 + .439 * red - .368 * green - .071 * blue).roundToInt().coerceIn(0, 255).toByte())
                        }
                    }
                    detector.infer(y, 0, 640, 1, u, 0, 320, 1, v, 0, 320, 1, 0, .25f, category)
                    val boxes = detector.detections
                    val raw = YoloxDetector::class.java.getDeclaredField("raw").apply { isAccessible = true }.get(detector) as FloatArray
                    var peak = 0f
                    for (row in 0 until 2100) peak = maxOf(peak,raw[row*85+4]*raw[row*85+5+category])
                    println("CATEGORY_SCORE ${names[category]} peak=$peak boxes=${boxes.size} threshold=0.25")
                    if (boxes.size == 0) { missing += names[category]; continue }
                    assertTrue((0 until boxes.size).all { boxes.classes[it] == category })
                    val tracker = ObjectTracker()
                    tracker.update(boxes, 0)
                    tracker.update(boxes, 200)
                    assertEquals(0, tracker.confirmedVisible)
                    tracker.update(boxes, 400)
                    assertTrue(tracker.confirmedVisible > 0)
                    println("CATEGORY_REGRESSION ${names[category]} boxes=${boxes.size} confirmed=${tracker.confirmedVisible}")
                } finally { source.recycle(); frame.recycle() }
            }
        }
        assertTrue("Packaged baseline missed pinned categories at 0.25: $missing",missing.isEmpty())
    }
}
