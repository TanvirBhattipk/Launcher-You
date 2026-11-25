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
    fun sheetTint(c: Context): Int = if (isNight(c)) 0x081430 else 0xE4E4E7

    fun handle(c: Context) = if (isNight(c)) 0x99FFFFFF.toInt() else 0xB30B1230.toInt()
    fun header(c: Context) = if (isNight(c)) 0xF2FFFFFF.toInt() else 0xD90B1230.toInt()
}

/**
 * The App drawer's sheet, used as the background of the drawer's list: it is as big as the list (the whole screen) and
 * stays put while the list scrolls. It draws, from [DeviceProfile.sheetTop] down: a deep shadow above the sheet's top
 * edge, the blurred wallpaper (Settings: Sheet Blur), the tint (Sheet Opacity), a thin rim (Border Size / Opacity) and
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

    /** The view this is the background of (for its place in the window) and the blurred wallpaper to show through. */
    var host: android.view.View? = null
    /** The views behind the sheet, bottom to top (wallpaper, Home). */
    var layers: (() -> List<android.view.View>)? = null
    private val blurNode = android.graphics.RenderNode("drawerSheetBlur")
    private var blurEffect: android.graphics.RenderEffect? = null
    private var blurEffectPx = -1f
    private val at = IntArray(2)
    private val layerAt = IntArray(2)

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
        // 1) the deep shadow that lifts the sheet off the background: only what falls outside the sheet shows
        canvas.save()
        canvas.clipOutPath(path)
        shadow.setShadowLayer(dp.dp(64f), 0f, -dp.dp(14f), 0x8C000000.toInt())
        canvas.drawPath(path, shadow)
        shadow.setShadowLayer(dp.dp(18f), 0f, -dp.dp(3f), 0x66000000)
        canvas.drawPath(path, shadow)
        canvas.restore()

        // 2) the live home screen (wallpaper + icons) behind the sheet, blurred by Sheet Blur
        // 2) the home screen (wallpaper + icons) behind the sheet, blurred by Sheet Blur
        val h = host
        val behind = layers?.invoke()
        if (h != null && behind != null && h.width > 0 && h.height > 0 && canvas.isHardwareAccelerated) {
            val blurPx = dp.dp(prefs.drawerSheetBlur.toFloat())
            if (blurPx != blurEffectPx) {
                blurEffectPx = blurPx
                blurEffect = if (blurPx >= 1f)
                    android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP)
                else null
            }
            h.getLocationInWindow(at)
            blurNode.setPosition(0, 0, h.width, h.height)
            blurNode.setRenderEffect(blurEffect)
            val rc = blurNode.beginRecording()
            try {
                rc.translate(-at[0].toFloat(), -at[1].toFloat())          // window coordinates -> this view
                for (v in behind) {
                    if (v.width == 0 || v.height == 0 || v.visibility != android.view.View.VISIBLE) continue
                    v.getLocationInWindow(layerAt)
                    rc.save()
                    rc.translate(layerAt[0].toFloat(), layerAt[1].toFloat())
                    rc.translate(-v.scrollX.toFloat(), -v.scrollY.toFloat())
                    v.draw(rc)
                    rc.restore()
                }
            } finally {
                blurNode.endRecording()
            }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawRenderNode(blurNode)
            canvas.restore()
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
