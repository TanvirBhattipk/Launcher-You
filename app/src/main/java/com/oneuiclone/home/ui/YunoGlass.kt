package com.oneuiclone.home.ui

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.roundToInt

/** Yuno's navbar glass, driven by the Search & Blur sliders. */
object YunoGlass {
    const val SATURATION = 1.5f
    private const val TINT_DARK = 0x121212
    private const val TINT_LIGHT = 0xFAFAFA

    val saturation = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(SATURATION) })

    private fun a(percent: Int) = (percent.coerceIn(0, 100) / 100f * 255f).roundToInt()

    /** Tint over the blur: #121212 (dark) or #FAFAFA (light) at the slider's opacity. */
    fun fill(night: Boolean, opacityPercent: Int): Int =
        (a(opacityPercent) shl 24) or (if (night) TINT_DARK else TINT_LIGHT)

    /** White border at the slider's opacity. */
    fun border(opacityPercent: Int): Int = (a(opacityPercent) shl 24) or 0xFFFFFF

    /** Rim highlight strength: border opacity 10 = Yuno's 50%. */
    fun rimPeak(borderOpacityPercent: Int): Float = (borderOpacityPercent / 100f * 5f).coerceIn(0f, 1f)
}

/** Yuno's 45° edge highlight: bright at top-left and bottom-right, fading across the middle. */
class YunoRim {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var l = Float.NaN
    private var t = Float.NaN
    private var r = Float.NaN
    private var b = Float.NaN
    private var lastPeak = -1f

    private fun aim(rect: RectF, strokePx: Float, peak: Float): Boolean {
        if (peak <= 0f) return false
        if (rect.left != l || rect.top != t || rect.right != r || rect.bottom != b || peak != lastPeak) {
            l = rect.left; t = rect.top; r = rect.right; b = rect.bottom; lastPeak = peak
            val hi = ((peak * 255f).roundToInt() shl 24) or 0xFFFFFF
            paint.shader = LinearGradient(
                l, t, r, b,
                intArrayOf(hi, 0x00FFFFFF, 0x00FFFFFF, hi),
                floatArrayOf(0f, 0.3f, 0.7f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        paint.strokeWidth = strokePx
        return true
    }

    fun draw(canvas: Canvas, rect: RectF, radius: Float, strokePx: Float, peak: Float) {
        if (aim(rect, strokePx, peak)) canvas.drawRoundRect(rect, radius, radius, paint)
    }

    fun drawPath(canvas: Canvas, path: Path, rect: RectF, strokePx: Float, peak: Float) {
        if (aim(rect, strokePx, peak)) canvas.drawPath(path, paint)
    }
}
