package com.edgepulse.core.vision

import kotlin.math.max
import kotlin.math.min

/** Baseline in canvas pixels; NaN means chrome leaves no readable caption area. */
fun previewCaptionBaseline(preferred: Float, textHeight: Float, viewHeight: Float, reservedTop: Float, reservedBottom: Float): Float {
    val minimum = max(textHeight+4f, reservedTop+textHeight+8f)
    val maximum = min(viewHeight-8f, reservedBottom-8f)
    return if (maximum < minimum) Float.NaN else preferred.coerceIn(minimum, maximum)
}

/** Reusable normalized upright-image projection matching PreviewView.FILL_CENTER. */
class PreviewProjection {
    private var width = 0f
    private var height = 0f
    private var left = 0f
    private var top = 0f
    fun configure(sourceWidth: Int, sourceHeight: Int, viewWidth: Float, viewHeight: Float) {
        require(sourceWidth > 0 && sourceHeight > 0 && viewWidth > 0 && viewHeight > 0)
        val scale = max(viewWidth / sourceWidth, viewHeight / sourceHeight)
        width = sourceWidth * scale; height = sourceHeight * scale
        left = (viewWidth-width)/2; top = (viewHeight-height)/2
    }
    fun x(normalized: Float) = left + normalized * width
    fun y(normalized: Float) = top + normalized * height
}
