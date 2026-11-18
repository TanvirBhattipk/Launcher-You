package com.oneuiclone.home.ui

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.RectF

/** Liquid glass removed: delegates to standard clean frosted blur with no refraction or white rim. */
object LiquidGlass {
    @JvmField var enabled = false
    const val REFRACTION_DP = 0f
    const val AMOUNT_DP = 0f
    const val RIM_DP = 0f
    @JvmField var refractionHeightDp = 0f
    @JvmField var refractionAmountDp = 0f

    val vibrancy = ColorMatrixColorFilter(ColorMatrix())

    fun saturationFilter(saturation: Float): ColorMatrixColorFilter =
        ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) })

    fun usable(@Suppress("UNUSED_PARAMETER") canvas: Canvas) = false

    private val baseGlassPaint by lazy { GlassPaint() }

    fun drawBase(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        glass: WidgetGlass?,
        glassAt: Matrix,
        @Suppress("UNUSED_PARAMETER") tint: Int,
        alpha: Float
    ) {
        if (baseGlassPaint.aim(glass, glassAt)) {
            baseGlassPaint.paint.alpha = (255 * alpha).toInt()
            baseGlassPaint.paint.colorFilter = null
            canvas.drawRoundRect(rect, radius, radius, baseGlassPaint.paint)
        }
    }

    fun drawChrome(
        @Suppress("UNUSED_PARAMETER") canvas: Canvas,
        @Suppress("UNUSED_PARAMETER") rect: RectF,
        @Suppress("UNUSED_PARAMETER") radius: Float,
        @Suppress("UNUSED_PARAMETER") night: Boolean
    ) {
        // Liquid glass rim disabled
    }
}

class LiquidRim {
    fun draw(
        @Suppress("UNUSED_PARAMETER") canvas: Canvas,
        @Suppress("UNUSED_PARAMETER") rect: RectF,
        @Suppress("UNUSED_PARAMETER") radius: Float,
        @Suppress("UNUSED_PARAMETER") width: Float,
        @Suppress("UNUSED_PARAMETER") strength: Float = 0f
    ) {
        // Liquid glass rim disabled
    }
}
