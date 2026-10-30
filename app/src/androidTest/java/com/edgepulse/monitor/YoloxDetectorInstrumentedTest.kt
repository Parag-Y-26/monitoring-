package com.edgepulse.monitor

import androidx.test.platform.app.InstrumentationRegistry
import com.edgepulse.monitor.vision.ml.YoloxDetector
import java.nio.ByteBuffer
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class YoloxDetectorInstrumentedTest {
    @Test fun actualReusableCpuBackendAcceptsRawYuvAndAllRotations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val y = ByteBuffer.allocateDirect(640*480).apply { for(i in 0 until capacity()) put(i, 100) }
        val uv = ByteBuffer.allocateDirect(320*240).apply { for(i in 0 until capacity()) put(i, 128.toByte()) }
        YoloxDetector(context,"XNNPACK_INT8").use { detector ->
            val storage = detector.bgr
            for(rotation in listOf(0,90,180,270)) {
                detector.infer(y,0,640,1,uv,0,320,1,uv,0,320,1,rotation,.25f)
                assertSame(storage,detector.bgr)
                assertEquals(if(rotation%180==0) 640 else 480,detector.transform.uprightWidth)
                assertTrue(detector.bgr.all { it.isFinite() && it in 0f..255f })
            }
            InstrumentationRegistry.getArguments().getString("visionFixture")?.let { filename ->
                val source = checkNotNull(BitmapFactory.decodeFile(filename))
                val frame = Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(frame); canvas.drawColor(android.graphics.Color.rgb(114,114,114))
                    val scale = minOf(640f/source.width,480f/source.height)
                    val px = (640-source.width*scale)/2; val py = (480-source.height*scale)/2
                    canvas.drawBitmap(source,null,RectF(px,py,px+source.width*scale,py+source.height*scale),null)
                    val u = ByteBuffer.allocateDirect(320*240); val v = ByteBuffer.allocateDirect(320*240)
                    for(sy in 0 until 480) for(sx in 0 until 640) {
                        val color = frame.getPixel(sx,sy)
                        val red = color shr 16 and 255; val green = color shr 8 and 255; val blue = color and 255
                        y.put(sy*640+sx,(16+.257*red+.504*green+.098*blue).roundToInt().coerceIn(0,255).toByte())
                        if(sx%2==0 && sy%2==0) {
                            val offset = sy/2*320+sx/2
                            u.put(offset,(128-.148*red-.291*green+.439*blue).roundToInt().coerceIn(0,255).toByte())
                            v.put(offset,(128+.439*red-.368*green-.071*blue).roundToInt().coerceIn(0,255).toByte())
                        }
                    }
                    detector.infer(y,0,640,1,u,0,320,1,v,0,320,1,0,.25f)
                    assertTrue("Real COCO fixture must produce at least one object",detector.detections.size > 0)
                    val names = context.assets.open("models/coco_labels.txt").bufferedReader().use { it.readLines() }
                    val labels = (0 until detector.detections.size).map { names[detector.detections.classes[it]] }.distinct()
                    println("COCO integration probe: ${detector.detections.size} boxes; classes=$labels")
                } finally { source.recycle(); frame.recycle() }
            }
        }
    }
}
