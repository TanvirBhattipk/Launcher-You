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

@SuppressLint("ViewConstructor")
class HomeSearchButton(
    context: Context,
    private val dp: DeviceProfile,
    label: String,
    private val icon: ((Canvas, Float, Float, Float, Paint) -> Unit)? = MagnifierView::draw,
) : View(context) {

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * resources.displayMetrics.scaledDensity
        typeface = Fonts.ui(context, 400)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = maxOf(2f, dp.dp(1f))
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
        val c = SearchColors.of(context)
        fill.color = c.homePill
        border.color = c.stroke
        text.color = c.text
        glyph.color = c.text
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
        border.strokeWidth = maxOf(2f, dp.dp(1f))
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

class MagnifierView(context: Context, color: Int) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; this.color = color
    }
    var color: Int
        get() = p.color
        set(v) { if (p.color != v) { p.color = v; invalidate() } }

    override fun onDraw(canvas: Canvas) = draw(canvas, 0f, 0f, width.toFloat(), p)

    companion object {
        fun draw(canvas: Canvas, left: Float, top: Float, s: Float, p: Paint) {
            p.strokeWidth = s * 0.085f
            val cr = s * 0.30f; val cx = left + s * 0.42f; val cy = top + s * 0.42f
            canvas.drawCircle(cx, cy, cr, p)
            canvas.drawLine(cx + cr * 0.72f, cy + cr * 0.72f, left + s * 0.86f, top + s * 0.86f, p)
        }
    }
}
