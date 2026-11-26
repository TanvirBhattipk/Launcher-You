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

    /** The sheet's tint (opaque): a frosted white by day, a deep navy at night, like the reference buttons. */
    fun sheetTint(c: Context): Int = if (isNight(c)) 0x0212124 else 0xF2F2F6

    fun handle(c: Context) = if (isNight(c)) 0x99FFFFFF.toInt() else 0xB30B1230.toInt()
    fun header(c: Context) = if (isNight(c)) 0xF2FFFFFF.toInt() else 0xD90B1230.toInt()
}

/**
 * The App drawer's sheet, used as the background of the drawer's list: it is as big as the list (the whole screen) and
 * stays put while the list scrolls. It draws, from [DeviceProfile.sheetTop] down: a deep shadow above the sheet's top
 * edge, the live blurred background, like the App Search cards (Settings: Sheet Blur), the tint (Sheet Opacity), a thin rim (Border Size / Opacity) and
 * the drag handle.
 */
class DrawerSheetDrawable(
    private val context: Context,
    private val dp: DeviceProfile,
    private val prefs: com.oneuiclone.home.settings.Prefs,
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val path = Path()
    private val rect = RectF()
    private val sheetRect = RectF()
    private val glassPaint = GlassPaint()
    private val toWindow = android.graphics.Matrix()
    /** Pre-blurred wallpaper copy, used when the live backdrop can't draw (same fallback the App Search cards have). */
    var glass: WidgetGlass? = null

    /** The view this is the background of (for its place in the window). */
    var host: android.view.View? = null
    /** The same live backdrop the App Search cards use: wallpaper + background tint, blurred, bent at the edge, saturated. */
    var backdrop: LiveBackdrop? = null
    private val lensRect = RectF()

    init { refreshColors() }

    /** Reads Sheet Opacity, Sheet Blur and the border settings again. */
    fun refreshColors() {
        val a = (prefs.drawerSheetOpacity.coerceIn(0, 100) * 255 / 100)
        fill.color = (DrawerInk.sheetTint(context) and 0x00FFFFFF) or (a shl 24)
        handle.color = DrawerInk.handle(context)
        rim.strokeWidth = maxOf(1f, dp.dp(prefs.drawerSheetBorderSize / 10f))
        rimShader()
        invalidateSelf()
    }

    private fun rimShader() {
        val top = bounds.top + dp.sheetTop.toFloat()
        val peak = (prefs.drawerSheetBorderOpacity.coerceIn(0, 100) * 255 / 100)
        // brightest along the top edge, fading down the sides
        rim.shader = android.graphics.LinearGradient(0f, top, 0f, top + dp.sheetRadius * 3f,
            (peak shl 24) or 0xFFFFFF, ((peak * 0.2f).toInt() shl 24) or 0xFFFFFF, android.graphics.Shader.TileMode.CLAMP)
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
        // 2) the blur (Sheet Blur = strength; 0 = none). The wallpaper isn't drawn by the app at rest (the system
        //    wallpaper shows through the window), so the live backdrop can't blur it: first the pre-blurred
        //    wallpaper copy, then Home's icons and widgets blurred live on top of it.
        val h = host
        if (h != null && prefs.drawerSheetBlur > 0) {
            val amount = dp.dp(YunoGlass.LENS_AMOUNT_DP)
            val depth = dp.dp(YunoGlass.LENS_DEPTH_DP)
            val side = amount + depth + 2f
            sheetRect.set(bounds.left.toFloat(), bounds.top + dp.sheetTop.toFloat(),
                bounds.right.toFloat(), bounds.bottom + dp.sheetRadius)
            toWindow.reset(); h.transformMatrixToGlobal(toWindow)
            if (glassPaint.aimLens(glass, toWindow, sheetRect, dp.sheetRadius, amount, depth)) {
                glassPaint.paint.colorFilter = YunoGlass.saturation
                glassPaint.paint.alpha = 255
                canvas.drawPath(path, glassPaint.paint)
            }
            lensRect.set(bounds.left - side, sheetRect.top, bounds.right + side, sheetRect.bottom + side)
            backdrop?.draw(canvas, h, lensRect, dp.sheetRadius,
                dp.dp(prefs.drawerSheetBlur.toFloat()), amount, depth, path)
        }

        // 3) tint, rim, handle
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
