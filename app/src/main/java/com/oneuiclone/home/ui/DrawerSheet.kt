package com.oneuiclone.home.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.oneuiclone.home.device.DeviceProfile

/** Colours of the App drawer sheet: a light frosted sheet with dark text by day, a dark one with light text at night. */
object DrawerInk {
    fun isNight(c: Context) =
        (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Labels and the "All apps" header sit on the sheet: dark text on the light sheet. */
    fun darkLabels(c: Context) = !isNight(c)

    /**
     * The sheet's glass, like the reference buttons: by day a frosted white that lets the wallpaper through, at night
     * a deep navy that is darker than what is behind it.
     */
    fun sheetFill(c: Context): Int = if (isNight(c)) 0x8C081430.toInt() else 0x73FFFFFF

    /** The thin light rim around the sheet: brightest along the top edge, fading down the sides. */
    fun rimTop(c: Context): Int = if (isNight(c)) 0x8CFFFFFF.toInt() else 0xE6FFFFFF.toInt()
    fun rimBottom(c: Context): Int = if (isNight(c)) 0x14FFFFFF else 0x33FFFFFF

    fun handle(c: Context) = if (isNight(c)) 0x99FFFFFF.toInt() else 0xB30B1230.toInt()
    fun header(c: Context) = if (isNight(c)) 0xF2FFFFFF.toInt() else 0xD90B1230.toInt()
}

/**
 * The App drawer's sheet, used as the background of the drawer's list: it is as big as the list (the whole screen) and
 * stays put while the list scrolls. It draws the rounded top of the sheet from [DeviceProfile.sheetTop] down and the
 * drag handle on it.
 */
class DrawerSheetDrawable(private val context: Context, private val dp: DeviceProfile) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp.dp(1.2f) }
    private val path = Path()
    private val rect = RectF()

    init { refreshColors() }

    fun refreshColors() {
        fill.color = DrawerInk.sheetFill(context)
        handle.color = DrawerInk.handle(context)
        rimShader()
        invalidateSelf()
    }

    private fun rimShader() {
        val top = bounds.top + dp.sheetTop.toFloat()
        rim.shader = android.graphics.LinearGradient(0f, top, 0f, top + dp.sheetRadius * 3f,
            DrawerInk.rimTop(context), DrawerInk.rimBottom(context), android.graphics.Shader.TileMode.CLAMP)
    }

    override fun onBoundsChange(b: android.graphics.Rect) {
        val r = dp.sheetRadius
        val top = b.top + dp.sheetTop.toFloat()
        path.reset()
        // rounded on the top two corners only; the sides and bottom run off the screen
        rect.set(b.left.toFloat(), top, b.right.toFloat(), b.bottom + r)
        path.addRoundRect(rect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
        rimShader()
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, fill)
        canvas.drawPath(path, rim)
        val cx = bounds.exactCenterX()
        val cy = bounds.top + dp.sheetTop + dp.sheetHandleCenterY
        val hw = dp.sheetHandleWidth / 2f
        val hh = dp.sheetHandleHeight / 2f
        rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
        canvas.drawRoundRect(rect, hh, hh, handle)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
