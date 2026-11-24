package com.oneuiclone.home.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewTreeObserver
import com.oneuiclone.home.device.DeviceProfile
import kotlin.math.roundToInt

internal const val SEARCH_GLASS_INK = 0xE6FFFFFF.toInt()
private const val NIGHT_GLASS_BG = 0xFF1C1C1E.toInt()

internal fun searchGlassFill(context: Context, transparency: Float): Int {
    val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val base = if (night) NIGHT_GLASS_BG else FolderIconView.DEFAULT_BG
    return (base and 0x00FFFFFF) or ((android.graphics.Color.alpha(base) * (1f - transparency)).toInt() shl 24)
}

class SearchPill(context: Context, private val dp: DeviceProfile, private val hint: String, private val menuIcon: Boolean = false) : View(context) {

    private val prefs = com.oneuiclone.home.settings.Prefs(context)
    private fun isNight() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * resources.displayMetrics.scaledDensity
        typeface = Fonts.ui(context, 400)
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rim = YunoRim()
    var backdrop: LiveBackdrop? = null
    
    // Acrylic frost texture 
    private val frostedNoisePaint by lazy {
        val size = 256
        val pixels = IntArray(size * size)
        val rnd = kotlin.random.Random(12345)
        for (i in pixels.indices) {
            val gray = rnd.nextInt(256)
            pixels[i] = android.graphics.Color.argb(10, gray, gray, gray)
        }
        val bmp = android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val shader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        android.graphics.Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
    }

    private val rect = RectF()
    private val inset = dp.dp(0.5f)

    var glass: WidgetGlass? = null
        set(v) {
            if (field === v) return
            if (isAttachedToWindow && !menuIcon) { field?.views?.remove(this); v?.views?.add(this) }
            field = v; applyColors(); invalidate()
        }

    var glassTransparency = 0f
        set(v) { field = v; applyColors(); invalidate() }

    var backgroundOpacity = 1f
        set(v) { field = v.coerceIn(0f, 1f); applyColors(); invalidate() }

    private var baseFill = 0
    private var baseStroke = 0
    
    private fun scaled(c: Int) = (c and 0x00FFFFFF) or ((android.graphics.Color.alpha(c) * backgroundOpacity).toInt() shl 24)

    private val glassPaint = GlassPaint()
    private val toWindow = Matrix()
    private val drawnAt = Matrix()
    private val moved = ViewTreeObserver.OnPreDrawListener {
        if (glass != null && !menuIcon) {
            toWindow.reset(); transformMatrixToGlobal(toWindow)
            if (toWindow != drawnAt) invalidate()
        }
        true
    }

    init {
        isClickable = true
        applyColors()
                if (!menuIcon) {
            elevation = dp.dp(10f)
            outlineAmbientShadowColor = 0x33000000
            outlineSpotShadowColor = 0x59000000
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                    outline.alpha = 1f
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!menuIcon) { glass?.views?.add(this); viewTreeObserver.addOnPreDrawListener(moved) }
    }

    override fun onDetachedFromWindow() {
        if (!menuIcon) { viewTreeObserver.removeOnPreDrawListener(moved); glass?.views?.remove(this) }
        super.onDetachedFromWindow()
    }

private fun applyColors() {
        baseFill = YunoGlass.fill(true, prefs.drawerSearchBarOpacity)
        baseStroke = YunoGlass.border(prefs.drawerSearchBorderOpacity)
        fill.color = scaled(baseFill)
        border.color = scaled(baseStroke)
        border.strokeWidth = maxOf(1f, dp.dp(prefs.drawerSearchBorderSize / 10f))

        text.color = 0xFFFFFFFF.toInt()
        glyph.color = text.color
        dots.color = text.color
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyColors()
        invalidate()
    }

    fun origin(left: Float, top: Float): AppsSearch.Origin {
        val i = inset
        return AppsSearch.Origin(
            RectF(left + i, top + i, left + width - i, top + height - i),
            baseFill,
            baseStroke,
            0f,
            dp.dp(16f) - i,
            dp.dp(24f),
            dp.dp(16f + 24f + 6f) - i,
            text.textSize,
            glass,
            text.color,
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (menuIcon) {
            val cx = width / 2f; val cy = height / 2f
            for (i in -1..1) canvas.drawCircle(cx, cy + i * dp.dp(6f), dp.dp(2.2f), dots)
            return
        }
        val gap = inset
        rect.set(gap, gap, width - gap, height - gap)
        val r = rect.height() / 2f
        drawnAt.reset(); transformMatrixToGlobal(drawnAt)
        if (prefs.drawerSearchBarBlur > 0 && glassPaint.aimLens(glass, drawnAt, rect, r, dp.dp(YunoGlass.LENS_AMOUNT_DP), dp.dp(YunoGlass.LENS_DEPTH_DP))) {
            glassPaint.paint.colorFilter = YunoGlass.saturation
            glassPaint.paint.alpha = (255 * backgroundOpacity).toInt()
            canvas.drawRoundRect(rect, r, r, glassPaint.paint)
        }
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(rect, r, r, border)
        rim.draw(canvas, rect, r, border.strokeWidth, YunoGlass.rimPeak(prefs.drawerSearchBorderOpacity))
        val box = dp.dp(22f)
        val gx = dp.dp(16f)
        MagnifierView.draw(canvas, gx, (height - box) / 2f, box, glyph)
        val fm = text.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(hint, gx + box + dp.dp(8f), baseline, text)
    }
}

class SearchColors(
    val pill: Int, val homePill: Int, val open: Int, val stroke: Int, val hint: Int, val text: Int,
) {
    companion object {
        private val DARK = SearchColors(0x26FFFFFF, 0x26FFFFFF, 0x26FFFFFF, 0x4DFFFFFF, 0x80FFFFFF.toInt(), 0xFFFFFFFF.toInt())
        private val LIGHT = SearchColors(0x66FFFFFF, 0x66FFFFFF, 0x66FFFFFF, 0xFFFFFFFF.toInt(), 0x80FFFFFF.toInt(), 0xFFFFFFFF.toInt())
        fun dark() = DARK
        fun of(context: Context): SearchColors {
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            return if (night) DARK else LIGHT
        }
    }
}
