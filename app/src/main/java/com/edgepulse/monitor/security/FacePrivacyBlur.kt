package com.edgepulse.monitor.security

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.media.FaceDetector

/** Best-effort frontal-face masking, outside monitoring. Manual preview/redaction is mandatory. */
object FacePrivacyBlur {
    fun redact(source: Bitmap): Bitmap {
        if (source.width < 2) return source
        val rgb = Bitmap.createScaledBitmap(source, source.width and -2, source.height, true).copy(Bitmap.Config.RGB_565, false)
        val faces = arrayOfNulls<FaceDetector.Face>(16)
        val canvas = Canvas(source)
        val paint = Paint().apply { color = Color.BLACK }
        try {
            FaceDetector(rgb.width, rgb.height, faces.size).findFaces(rgb, faces)
            val point = PointF()
            for (face in faces) if (face != null && face.confidence() >= .4f) {
                face.getMidPoint(point); val span = face.eyesDistance()
                canvas.drawRect(point.x-1.6f*span, point.y-1.6f*span, point.x+1.6f*span, point.y+2f*span, paint)
            }
        } catch (_: RuntimeException) { /* The visible manual redaction/approval remains required. */ }
        finally { rgb.recycle() }
        return source
    }
}
