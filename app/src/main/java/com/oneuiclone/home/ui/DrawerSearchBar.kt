package com.oneuiclone.home.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.settings.Prefs

/**
 * The search bar at the top of the App drawer sheet. It is filled like the App Search page's bar (the notification
 * surface colour, [SearchInk.surface], with Settings › App Search › Search Bar Opacity), has the same hint and
 * magnifier, and the same text colours (#212124 by day, #F1F1F4 at night). Tapping it is the caller's job
 * (the launcher closes the sheet and opens App Search).
 */
class DrawerSearchBar(
    context: Context,
    private val dp: DeviceProfile,
    private val hint: String,
) : View(context) {

    private val prefs = Prefs(context)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
    }
    private val rect = RectF()

    // same measurements as the App Search bar's text and glyph
    private val glyphLeft = dp.dp(16f)
    private val glyphBox = dp.dp(20f)
    private val textStart = dp.dp(16f + 20f + 10f)

    private val isNight: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val inkRgb: Int
        get() = if (isNight) 0xF1F1F4 else 0x212124

    init {
        isClickable = true
        isFocusable = true
        contentDescription = hint
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }

    private fun fillColor(): Int {
        val alpha = YunoGlass.barFill(prefs.appSearchBarOpacity) ushr 24
        val shade = if (isPressed) 0.85f else 1f
        return (SearchInk.surface(context) and 0x00FFFFFF) or ((alpha * shade).toInt().coerceIn(0, 255) shl 24)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // the pill
        fillPaint.color = fillColor()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, fillPaint)

        // the magnifier
        glyphPaint.color = 0xCC000000.toInt() or inkRgb
        glyphPaint.strokeWidth = dp.dp(2f)
        val cx = glyphLeft + glyphBox * 0.42f
        val cy = h / 2f - glyphBox * 0.08f
        val r = glyphBox * 0.30f
        canvas.drawCircle(cx, cy, r, glyphPaint)
        canvas.drawLine(cx + r * 0.71f, cy + r * 0.71f, glyphLeft + glyphBox * 0.90f, h / 2f + glyphBox * 0.40f, glyphPaint)

        // the hint
        textPaint.color = 0x99000000.toInt() or inkRgb
        val baseline = h / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(hint, textStart, baseline, textPaint)
    }

    /** What App Search closes back onto (and what it opens from), in the launcher's coordinates. */
    fun origin(left: Float, top: Float): AppsSearch.Origin =
        AppsSearch.Origin(
            rect = RectF(left, top, left + width, top + height),
            fill = fillColor(),
            stroke = 0,
            elevation = 0f,
            glyphLeft = glyphLeft,
            glyphBox = glyphBox,
            textStart = textStart,
            textSizePx = textPaint.textSize,
            glass = null,
            ink = 0xCC000000.toInt() or inkRgb,
        )
}
