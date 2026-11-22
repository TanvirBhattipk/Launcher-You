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

/** Ink of a pill drawn on the blurred-wallpaper glass: white, as on the Home screen's Search button. */
internal const val SEARCH_GLASS_INK = 0xE6FFFFFF.toInt()
private const val NIGHT_GLASS_BG = 0xFF1C1C1E.toInt()

/** Tint of a glass pill (the folder-glass look of [HomeSearchButton]); [transparency] 0..1. */
internal fun searchGlassFill(context: Context, transparency: Float): Int {
    val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val base = if (night) NIGHT_GLASS_BG else FolderIconView.DEFAULT_BG
    return (base and 0x00FFFFFF) or ((android.graphics.Color.alpha(base) * (1f - transparency)).toInt() shl 24)
}

/**
 * The apps-screen search bar: full width, drawn with frosted blur, adaptive light/dark tint,
 * and a subtle hairline border, with the hint and magnifier inside.
 */
class SearchPill(context: Context, private val dp: DeviceProfile, private val hint: String, private val menuIcon: Boolean = false) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(2f, dp.dp(1f))
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
    private val rect = RectF()
    private val inset = dp.dp(0.5f)

    /** The blurred wallpaper behind the pill; null for none. */
    var glass: WidgetGlass? = null
        set(v) {
            if (field === v) return
            if (isAttachedToWindow && !menuIcon) { field?.views?.remove(this); v?.views?.add(this) }
            field = v; applyColors(); invalidate()
        }

    /** How see-through the glass tint is (0..1). */
    var glassTransparency = 0f
        set(v) { field = v; applyColors(); invalidate() }

    /** 0..1: how much of the pill's background (glass, tint and border) shows. */
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
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!menuIcon) { glass?.views?.add(this); viewTreeObserver.addOnPreDrawListener(moved) }
    }

    override fun onDetachedFromWindow() {
        if (!menuIcon) { viewTreeObserver.removeOnPreDrawListener(moved); glass?.views?.remove(this) }
        super.onDetachedFromWindow()
    }

private val prefs = com.oneuiclone.home.settings.Prefs(context)

    private fun isNight() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

private val prefs = com.oneuiclone.home.settings.Prefs(context)

    private fun isNight() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun applyColors() {
        val baseGlass = if (isNight()) 0x000000 else 0xFFFFFF
        val baseBorder = if (isNight()) 0xFFFFFF else 0x000000
        
        baseFill = (prefs.drawerSearchBarOpacity * 255 / 100) shl 24 or baseGlass
        baseStroke = (prefs.drawerSearchBorderOpacity * 255 / 100) shl 24 or baseBorder
        
        fill.color = scaled(baseFill)
        border.color = scaled(baseStroke)
        border.strokeWidth = maxOf(2f, dp.dp(prefs.drawerSearchBorderSize / 10f))
        
        text.color = if (isNight()) 0xFFFFFFFF.toInt() else 0xFF444444.toInt()
        glyph.color = text.color
        dots.color = text.color
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyColors()
        invalidate()
    }

    /** The drawn pill as the search bar's start / end state; [left]/[top] in root coordinates. */
    fun origin(left: Float, top: Float): AppsSearch.Origin {
        val i = inset
        return AppsSearch.Origin(
            RectF(left + i, top + i, left + dp.searchBarWidth - i, top + dp.searchBarHeight - i),
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
        if (glassPaint.aim(glass, drawnAt)) {
            glassPaint.paint.alpha = (255 * backgroundOpacity).toInt()
            canvas.drawRoundRect(rect, r, r, glassPaint.paint)
        }
        canvas.drawRoundRect(rect, r, r, fill)
        border.strokeWidth = maxOf(2f, dp.dp(1f))
        border.color = scaled(baseStroke)
        canvas.drawRoundRect(rect, r, r, border)
        val box = dp.dp(22f)
        val gx = dp.dp(16f)
        MagnifierView.draw(canvas, gx, (height - box) / 2f, box, glyph)
        val fm = text.fontMetrics
        val baseline = height / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(hint, gx + box + dp.dp(8f), baseline, text)
    }
}
