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
import android.graphics.Matrix
import android.graphics.RuntimeShader

/** Yuno's navbar glass, driven by the Search & Blur sliders. */
object YunoGlass {
    const val SATURATION = 1.5f
    private const val TINT_DARK = 0x121212
    private const val TINT_LIGHT = 0xFAFAFA
    const val LENS_DEPTH_DP = 14f    // how wide the refracting rim is
    const val LENS_AMOUNT_DP = 16f   // how far the edge pulls the background in from outside

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

/** Yuno's directional edge highlight: every edge lit by its angle to a 45° light, brightest at top-left and bottom-right. */
class YunoRim {
    private val shader = RuntimeShader(AGSL)
    private val matrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.shader = this@YunoRim.shader
    }

    private fun aim(rect: RectF, radius: Float, strokePx: Float, peak: Float): Boolean {
        if (peak <= 0f || rect.width() <= 0f || rect.height() <= 0f) return false
        shader.setFloatUniform("size", rect.width(), rect.height())
        shader.setFloatUniform("radius", minOf(radius, rect.width() / 2f, rect.height() / 2f))
        shader.setFloatUniform("peak", peak)
        matrix.setTranslate(rect.left, rect.top)
        shader.setLocalMatrix(matrix)
        paint.strokeWidth = strokePx
        return true
    }

    fun draw(canvas: Canvas, rect: RectF, radius: Float, strokePx: Float, peak: Float) {
        if (aim(rect, radius, strokePx, peak)) canvas.drawRoundRect(rect, radius, radius, paint)
    }

    fun drawPath(canvas: Canvas, path: Path, rect: RectF, radius: Float, strokePx: Float, peak: Float) {
        if (aim(rect, radius, strokePx, peak)) canvas.drawPath(path, paint)
    }

    private companion object {
        const val AGSL = """
            uniform float2 size;
            uniform float radius;
            uniform float peak;
            float2 gradSdRoundedRect(float2 coord, float2 halfSize, float r) {
                float2 cornerCoord = abs(coord) - (halfSize - float2(r));
                if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
                    return sign(coord) * normalize(max(cornerCoord, 0.0) + 0.00001);
                } else {
                    float gradX = step(cornerCoord.y, cornerCoord.x);
                    return sign(coord) * float2(gradX, 1.0 - gradX);
                }
            }
            half4 main(float2 coord) {
                float2 halfSize = size * 0.5;
                float2 grad = gradSdRoundedRect(coord - halfSize, halfSize, radius);
                float2 normal = float2(cos(0.7853982), sin(0.7853982));
                float a = abs(dot(grad, normal)) * peak;
                return half4(a);
            }
        """
    }
}
