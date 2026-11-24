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
    private var lensShader: android.graphics.RuntimeShader? = null

    /** Like [aim], but the glass bends the blurred wallpaper near its edges, like a convex lens. */
    fun aimLens(glass: WidgetGlass?, toWindow: Matrix, rect: RectF, radius: Float, amountPx: Float, depthPx: Float): Boolean {
        val s = aimBitmap(glass, toWindow) ?: return false
        val lens = lensShader ?: android.graphics.RuntimeShader(LENS_AGSL).also { lensShader = it }
        lens.setInputShader("content", s)
        lens.setFloatUniform("origin", rect.left, rect.top)
        lens.setFloatUniform("size", rect.width(), rect.height())
        lens.setFloatUniform("radius", minOf(radius, rect.width() / 2f, rect.height() / 2f))
        lens.setFloatUniform("amount", amountPx)
        lens.setFloatUniform("depth", depthPx)
        if (paint.shader !== lens) paint.shader = lens
        if (paint.colorFilter != null) paint.colorFilter = null
        return true
    }

    private companion object {
        const val LENS_AGSL = """
            uniform shader content;
            uniform float2 origin;
            uniform float2 size;
            uniform float radius;
            uniform float amount;
            uniform float depth;

            float sdRoundedRect(float2 p, float2 halfSize, float r) {
                float2 q = abs(p) - (halfSize - float2(r));
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            }
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
                float2 p = coord - (origin + halfSize);
                float inside = -sdRoundedRect(p, halfSize, radius);   // distance to the edge, > 0 inside
                float t = clamp(1.0 - inside / depth, 0.0, 1.0);
                float2 n = gradSdRoundedRect(p, halfSize, radius);    // outward normal
                // edges show the scene from further out, squeezed into the rim
                return content.eval(coord + n * (t * t * amount));
            }
        """
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
// Generates the frosted acrylic noise texture
object Acrylic {
    val noise by lazy {
        val size = 256
        val pixels = IntArray(size * size)
        val rnd = kotlin.random.Random(12345) // Fixed seed for consistent grain
        for (i in pixels.indices) {
            val gray = rnd.nextInt(256)
            // 10 alpha out of 255 creates a subtle, premium frost
            pixels[i] = android.graphics.Color.argb(10, gray, gray, gray) 
        }
        val bmp = android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val shader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
    }
}
