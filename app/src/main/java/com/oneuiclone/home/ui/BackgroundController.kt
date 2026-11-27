package com.oneuiclone.home.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.ColorUtils
import java.util.function.Consumer
import kotlin.math.roundToInt

/**
 * Wallpaper blur + Samsung tone curve + dim for each HoneyState (spec §5).
 *
 * One UI applies blur(radius = progress × 250) then a colour curve
 * (minX 15, maxX 255 → minY 53.5, maxY 232) and finally a #A6000000 dim × dimFactor.
 * The linear curve is equivalent to blending a 165-grey at 25.6 % over the blurred wallpaper, which a
 * plain overlay can reproduce exactly on top of the window's cross-window blur.
 */
class BackgroundController(
    private val activity: Activity,
    private val scrim: ScrimView,
    private val wallpaper: WallpaperLayer,
) {

    enum class Kind(val blurFactor: Float, val dim: Float) {
        NONE(0f, 0f),
        APPS(1f, 0.25f),          // config_appsBgDarken 25
        FOLDER(1f, 0.20f),        // config_homeFolderBgDarken 20
        APPS_FOLDER(1f, 0.25f),   // config_appsFolderBgDarken 25
        EDIT(1f, 0.25f),          // config_PageEditBgDarken 25
        DRAG(0.5f, 0f),           // HomeScreen.Drag blur factor 0.5
        WIDGETS(1f, 0.25f),
        QUICK_OPEN(1f, 0.20f),    // the Bubble & Split panel, Home faded out behind it
    }

    private val wm = activity.getSystemService(WindowManager::class.java)
    private var blurEnabled = wm.isCrossWindowBlurEnabled
    private val listener = Consumer<Boolean> { blurEnabled = it; apply() }

    /** Full-strength blur radius in px (tuned against One UI screenshots). */
    var maxRadiusPx = (activity.resources.displayMetrics.widthPixels * 0.16f)

    /** 1 = the Apps screen's full blur and dimming. */
    var appsBlurScale = 1f

    /** Settings › Liquid glass: the blur radius (px) of the Apps screen's background, and of the search page's. */
    var appsRadiusPx = maxRadiusPx
    var searchRadiusPx = maxRadiusPx
    var onChange: (() -> Unit)? = null

    /** Applies the background again (after a radius or [searchOpen] changed). */
    fun refresh() = apply()

    /** Background tint 0..1: App Drawer › Background Opacity, and App Search › Background Opacity. */
    var appsDim = 0.4f
    /** 0 = the Apps screen's background blurs but is not darkened (the drawer sheet); 1 = darkened as set. */
    var appsDimScale = 1f
    /** true while the search closes onto Home: the drawer's own tint must not show through that crossfade. */
    var suppressDrawerTint = false

    /** How much of the drawer's own tint/dim shows: it gives way to the search tint, and stays off while search closes onto Home. */
    private fun drawerFactor() = if (suppressDrawerTint) 0f else 1f - searchMix
    var searchTintAlpha = 0.6f
    
    /** Pixel-style: the Apps screen's wallpaper is washed with the sheet's light colour (0..1 at full blur) instead of darkened. */
    var appsWash = 0f
    /** true = the Drawer background opacity setting darkens the Apps wallpaper again (the wash stays). */
    var darkenApps = true
    var searchDim = 0.55f
    /** The search page is open: blends from the Drawer background settings (0) to the App Search ones (1). */
    var searchOpen = false
        set(v) {
            if (field == v) return
            field = v
            searchAnim?.cancel()
            searchAnim = android.animation.ValueAnimator.ofFloat(searchMix, if (v) 1f else 0f).apply {
                // leaving search: the tint is gone by the time Home is revealed (the page itself is out in ~170ms)
                duration = if (v) 300 else 110
                interpolator = if (v) android.view.animation.AccelerateDecelerateInterpolator()
                               else android.view.animation.LinearInterpolator()
                addUpdateListener { searchMix = it.animatedValue as Float; apply() }
                start()
            }
        }
    private var searchMix = 0f
    private var searchAnim: android.animation.ValueAnimator? = null

    private fun isApps(k: Kind) = k == Kind.APPS || k == Kind.APPS_FOLDER
    private val SEARCH_TINT_RGB = 0x121212              // the drawer's tint
    private val SEARCH_BASE_RGB = 0x121212             // the dim colour outside search

    private fun radiusOf(k: Kind): Float =
        if (isApps(k)) k.blurFactor * (appsRadiusPx + (searchRadiusPx - appsRadiusPx) * searchMix) else k.blurFactor * maxRadiusPx

    private fun washOf(k: Kind): Float =
        if (isApps(k)) appsWash * drawerFactor() * appsBlurScale else 0f

    private fun dimOf(k: Kind): Float =
        if (isApps(k)) (if (darkenApps) appsDim else 0f) * drawerFactor() * 255f / 0xA6 * appsBlurScale * appsDimScale else k.dim

    private fun factor(k: Kind) = if (k == Kind.APPS || k == Kind.APPS_FOLDER) k.blurFactor * appsBlurScale else k.blurFactor

    private var kind = Kind.NONE
    private var progress = 0f
    private var lastRadius = -1

    fun attach() {
        DrawerInk.refreshWallpaper(activity)
        wm.addCrossWindowBlurEnabledListener(listener)
        android.util.Log.i("OneUiHome", "cross-window blur enabled=" + wm.isCrossWindowBlurEnabled)
    }
    fun detach() { wm.removeCrossWindowBlurEnabledListener(listener) }

    /** The state [kind]'s background blends in from (at 1 − progress), e.g. a drag leaving an open folder. */
    private var from = Kind.NONE

    fun set(kind: Kind, progress: Float) {
        this.kind = kind
        this.progress = progress.coerceIn(0f, 1f)
        from = Kind.NONE
        apply()
    }

    /** Part way from [from]'s full background to [to]'s: [f] 0 is all [from], 1 all [to]. */
    fun blend(from: Kind, to: Kind, f: Float) {
        kind = to
        progress = f.coerceIn(0f, 1f)
        this.from = from
        apply()
    }

    val current get() = kind
    val currentProgress get() = progress

    private fun apply() {
        val p = progress
        val q = 1f - p
        val blur = p * factor(kind) + q * factor(from)
        // the App Search tint belongs to the search page itself: it must not follow the Home/Apps crossfade progress
        // the scrim's colour follows whichever dim is actually showing: no mid-fade shift toward black
        val drawerDim = p * dimOf(kind) + q * dimOf(from)
        // the App Search tint belongs to the search page itself: it must not follow the Home/Apps crossfade progress
        val searchDimPart = searchMix * searchDim * 255f / 0xA6 * appsBlurScale
        val dim = maxOf(drawerDim, searchDimPart)
        val tintShare = if (drawerDim + searchDimPart <= 0f) 0f else searchDimPart / (drawerDim + searchDimPart)
        scrim.dimRgb = ColorUtils.blendARGB(
            0xFF000000.toInt() or SEARCH_BASE_RGB, SearchInk.page(activity), tintShare
        ) and 0x00FFFFFF
        // the App Search page darkens with the drawer's tint (light by day, dark at night) instead of a fixed near-black
        scrim.washColor = DrawerInk.wash(activity)
        scrim.washAlpha = p * washOf(kind) + q * washOf(from)
        if (wallpaper.available) {
            // launcher-drawn wallpaper: exact One UI pipeline (blur + colour curve), dim on top
            if (lastRadius != 0) { activity.window.setBackgroundBlurRadius(0); lastRadius = 0 }
            val radiusPx = p * radiusOf(kind) + q * radiusOf(from)
            val reach = maxOf(maxRadiusPx, appsRadiusPx, searchRadiusPx)
            wallpaper.maxBlurRadiusPx = reach
            wallpaper.extraSaturation = if (kind == Kind.APPS || kind == Kind.APPS_FOLDER) YunoGlass.SATURATION else 1f
            wallpaper.setBlur(if (reach > 0f) radiusPx / reach else 0f)
            scrim.curveAlpha = 0f
            scrim.dimAlpha = dim * 0xA6 / 255f
            scrim.invalidate()
            onChange?.invoke()
            return
        }
        val radius = if (blurEnabled) (p * radiusOf(kind) + q * radiusOf(from)).roundToInt() else 0
        if (radius != lastRadius) {
            activity.window.setBackgroundBlurRadius(radius)
            lastRadius = radius
        }
        // tone curve only where One UI applies the wallpaper blur view
        scrim.curveAlpha = (p * factor(kind).coerceAtMost(1f) + q * factor(from).coerceAtMost(1f)) * 0.256f
        val fallbackExtra = if (!blurEnabled) 0.2f else 0f
        val shown = p + (if (from != Kind.NONE) q else 0f)
        scrim.dimAlpha = dim * 0xA6 / 255f + shown * fallbackExtra
        scrim.invalidate()
        onChange?.invoke()
    }
}

/** Full-screen layer drawing the tone-curve grey and the dim. */
class ScrimView(context: Context) : View(context) {
    var curveAlpha = 0f
    var dimAlpha = 0f
    /** The light wash over the blurred wallpaper (the sheet's colour). */
    var washColor = 0xFFF2F2F6.toInt()
    var washAlpha = 0f
    var dimRgb = 0x121212

    private val frostedNoisePaint by lazy {
        val size = 128
        val pixels = IntArray(size * size)
        val rnd = kotlin.random.Random(System.currentTimeMillis())
        for (i in pixels.indices) {
            val gray = rnd.nextInt(256)
            pixels[i] = android.graphics.Color.argb(12, gray, gray, gray)
        }
        val bmp = android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val shader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        android.graphics.Paint().apply { this.shader = shader }
    }

        override fun onDraw(canvas: Canvas) {
        if (curveAlpha > 0f) canvas.drawColor(((curveAlpha * 255).roundToInt() shl 24) or 0xA5A5A5)
        if (washAlpha > 0f) canvas.drawColor(((washAlpha.coerceAtMost(1f) * 255).roundToInt() shl 24) or (washColor and 0x00FFFFFF))
        if (dimAlpha > 0f) canvas.drawColor(((dimAlpha.coerceAtMost(1f) * 255).roundToInt() shl 24) or dimRgb)
        
        // Add Acrylic Frost Texture
        val progress = maxOf(curveAlpha.coerceAtMost(1f), dimAlpha.coerceAtMost(1f), washAlpha.coerceAtMost(1f))
        if (progress > 0.01f) {
            Acrylic.noise.alpha = (255 * progress).toInt()
            canvas.drawPaint(Acrylic.noise)
        }
    }
}
