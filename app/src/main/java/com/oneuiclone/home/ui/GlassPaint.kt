package com.oneuiclone.home.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * A paint that fills with [WidgetGlass]: shows the blurred wallpaper behind it on screen (standard clean blur).
 */
class GlassPaint {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bitmap: Bitmap? = null
    private var bitmapShader: BitmapShader? = null
    private val local = Matrix()

    private fun aimBitmap(glass: WidgetGlass?, toWindow: Matrix): BitmapShader? {
        val b = glass?.bitmap ?: return null
        if (!toWindow.invert(local)) return null
        local.preScale(glass.scale, glass.scale)
        var s = bitmapShader
        if (bitmap !== b || s == null) {
            bitmap = b
            s = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            bitmapShader = s
        }
        s.setLocalMatrix(local)
        return s
    }

    fun aim(glass: WidgetGlass?, toWindow: Matrix): Boolean {
        val s = aimBitmap(glass, toWindow) ?: return false
        if (paint.shader !== s) paint.shader = s
        if (paint.colorFilter != null) paint.colorFilter = null
        return true
    }

    fun aimLiquid(
        @Suppress("UNUSED_PARAMETER") canvas: android.graphics.Canvas,
        glass: WidgetGlass?,
        toWindow: Matrix,
        @Suppress("UNUSED_PARAMETER") rect: RectF,
        @Suppress("UNUSED_PARAMETER") radius: Float,
        @Suppress("UNUSED_PARAMETER") density: Float
    ): Boolean = aim(glass, toWindow)
}

class GlassView(
    context: android.content.Context,
    private val glass: WidgetGlass,
    private val fill: Int,
    @Suppress("UNUSED_PARAMETER") private val cornerPx: Float = 0f,
) : android.view.View(context) {
    private val glassPaint = GlassPaint()
    private val fillPaint = Paint().apply { color = fill }
    private val toWindow = Matrix()
    private val drawnAt = Matrix()
    private val moved = android.view.ViewTreeObserver.OnPreDrawListener {
        toWindow.reset(); transformMatrixToGlobal(toWindow)
        if (toWindow != drawnAt) invalidate()
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glass.views += this
        viewTreeObserver.addOnPreDrawListener(moved)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(moved)
        glass.views -= this
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        drawnAt.reset(); transformMatrixToGlobal(drawnAt)
        if (glassPaint.aim(glass, drawnAt)) canvas.drawPaint(glassPaint.paint)
        canvas.drawPaint(fillPaint)
    }
}
