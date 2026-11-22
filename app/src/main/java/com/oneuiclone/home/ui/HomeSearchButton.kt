package com.oneuiclone.home.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.motion.Easing
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class HomeSearchButton(
    context: Context,
    private val dp: DeviceProfile,
    label: String,
    private val icon: ((Canvas, Float, Float, Float, Paint) -> Unit)? = MagnifierView::draw,
) : View(context) {

    private val prefs = com.oneuiclone.home.settings.Prefs(context)
    private fun isNight() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * resources.displayMetrics.scaledDensity
        typeface = Fonts.ui(context, 400)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()

    var glass: WidgetGlass? = null
        set(v) {
            if (field === v) return
            if (isAttachedToWindow) { field?.views?.remove(this); v?.views?.add(this) }
            field = v; applyColors(); invalidate()
        }
    var glassTransparency = 0f
        set(v) { field = v; applyColors(); invalidate() }

    private val glassPaint = GlassPaint()
    private val toWindow = android.graphics.Matrix()
    private val drawnAt = android.graphics.Matrix()
    private val moved = android.view.ViewTreeObserver.OnPreDrawListener {
        if (glass != null) {
            toWindow.reset(); transformMatrixToGlobal(toWindow)
            if (toWindow != drawnAt) invalidate()
        }
        true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        glass?.views?.add(this)
        viewTreeObserver.addOnPreDrawListener(moved)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(moved)
        glass?.views?.remove(this)
        super.onDetachedFromWindow()
    }

    val pillHeight = dp.dpi(32f)
    private val glyphLeft = dp.dp(12f)
    private val glyphBox = dp.dp(16f)
    private val textStart = if (icon != null) glyphLeft + glyphBox + dp.dp(6f) else dp.dp(14f)
    var label = label
        set(v) { if (field != v) { field = v; contentDescription = v; invalidate() } }
    val pillWidth get() = pillWidth(label)
    fun pillWidth(label: String) = (textStart + text.measureText(label) + dp.dp(14f)).toInt()
    var progress = -1f
        set(v) { if (field != v) { field = v; invalidate() } }
    private val progressFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }

    init {
        isClickable = true
        contentDescription = label
        applyColors()
    }

private fun applyColors() {
        val night = isNight()
        val baseRgb = if (night) 0x00 else 0xFF
        val borderRgb = if (night) 0xFF else 0x00
        val fillPercent = if (prefs.homePillBgOpacity in 1..100) prefs.homePillBgOpacity else 65
        val strokePercent = if (prefs.homePillBorderOpacity in 1..100) prefs.homePillBorderOpacity else 35
        val strokeSizeDp = prefs.homePillBorderSize.coerceAtLeast(10) / 10f

        fill.color = android.graphics.Color.argb(
            ((fillPercent.coerceIn(0, 100) / 100f) * 255f).roundToInt().coerceIn(0, 255),
            baseRgb,
            baseRgb,
            baseRgb,
        )
        border.color = android.graphics.Color.argb(
            ((strokePercent.coerceIn(0, 100) / 100f) * 255f).roundToInt().coerceIn(0, 255),
            borderRgb,
            borderRgb,
            borderRgb,
        )
        border.strokeWidth = maxOf(2f, dp.dp(strokeSizeDp))

        text.color = if (night) 0xFFFFFFFF.toInt() else 0xFF444444.toInt()
        glyph.color = text.color
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyColors()
        invalidate()
    }

    fun origin(left: Float, top: Float) = AppsSearch.Origin(
        RectF(left, top, left + pillWidth, top + pillHeight), fill.color, border.color, 0f,
        glyphLeft, glyphBox, textStart, text.textSize, glass, text.color,
    )

    override fun onDraw(canvas: Canvas) {
        val inset = dp.dp(0.5f)
        rect.set(inset, inset, width - inset, height - inset)
        val r = rect.height() / 2f
        drawnAt.reset(); transformMatrixToGlobal(drawnAt)
        if (glassPaint.aim(glass, drawnAt)) canvas.drawRoundRect(rect, r, r, glassPaint.paint)
        canvas.drawRoundRect(rect, r, r, fill)
        if (progress >= 0f) {
            canvas.save()
            canvas.clipRect(rect.left, rect.top, rect.left + rect.width() * progress, rect.bottom)
            canvas.drawRoundRect(rect, r, r, progressFill)
            canvas.restore()
        }
        canvas.drawRoundRect(rect, r, r, border)
        icon?.invoke(canvas, glyphLeft, (height - glyphBox) / 2f, glyphBox, glyph)
        val fm = text.fontMetrics
        canvas.drawText(label, textStart, height / 2f - (fm.ascent + fm.descent) / 2f, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.94f).scaleY(0.94f).setDuration(120).setInterpolator(Easing.BASIC).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(Easing.BASIC).start()
        }
        return super.onTouchEvent(event)
    }
}
