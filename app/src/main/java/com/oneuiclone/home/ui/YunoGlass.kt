package com.oneuiclone.home.ui

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/** Yuno's navbar glass: 8dp blur, 1.5 saturation, 40% tint, 0.5dp white-10% border, 45° rim highlight. */
object YunoGlass {
    const val BLUR_DP = 8f
    const val SATURATION = 1.5f
    const val BORDER_DP = 0.5f
    const val BORDER_COLOR = 0x1AFFFFFF          // white 10%
    private const val FILL_DARK = 0x66121212     // #121212 40%
    private const val FILL_LIGHT = 0x66FAFAFA    // #FAFAFA 40%

    fun fill(night: Boolean): Int = if (night) FILL_DARK else FILL_LIGHT

    val saturation = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(SATURATION) })
}

/** Yuno's directional edge highlight: white 50% at the top-left and bottom-right, fading across the middle. */
class YunoRim {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var l = Float.NaN
    private var t = Float.NaN
    private var r = Float.NaN
    private var b = Float.NaN

    fun draw(canvas: Canvas, rect: RectF, radius: Float, strokePx: Float) {
        if (rect.left != l || rect.top != t || rect.right != r || rect.bottom != b) {
            l = rect.left; t = rect.top; r = rect.right; b = rect.bottom
            paint.shader = LinearGradient(
                l, t, r, b,
                intArrayOf(0x80FFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF, 0x80FFFFFF.toInt()),
                floatArrayOf(0f, 0.3f, 0.7f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        paint.strokeWidth = strokePx
        canvas.drawRoundRect(rect, radius, radius, paint)
    }
}
