package com.oneuiclone.home.ui

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class WallpaperLayer(context: Context) : android.view.ViewGroup(context) {

    private val wm = WallpaperManager.getInstance(context)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val blurView = BitmapView(context)
    private val sharpView = BitmapView(context)

    var available = false; private set
    private var full: Bitmap? = null
    private var small: Bitmap? = null
    private var blurProgress = 0f
    private var zoom = 1f
    var maxBlurRadiusPx = 0f

    private val systemScale = systemScale()

    companion object {
        const val DOWNSCALE = 4
        const val OWN_FILE = "wallpaper.png"
        const val PREFS = "wallpaper"
        const val KEY_SYSTEM_ID = "system_id"
        const val KEY_COPY_STAMP = "copy_stamp"
        const val KEY_LIVE_WANTED = "live_wanted"
        const val LOCK_FILE = "wallpaper_lock.png"
        const val KEY_LOCK_ID = "lock_id"
        const val SATURATION = 1.25f
        private const val MIN_X = 15f
        private const val MAX_X = 255f
        private const val MIN_Y = 53.5f
        private const val MAX_Y = 232f

        fun systemScale(): Float = try {
            val res = android.content.res.Resources.getSystem()
            val id = res.getIdentifier("config_wallpaperMaxScale", "dimen", "android")
            if (id != 0) res.getFloat(id).coerceIn(1f, 1.5f) else 1f
        } catch (_: Exception) { 1f }

        fun hasOwnCopy(context: Context): Boolean = try {
            val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SYSTEM_ID, -1)
            id > 0 && java.io.File(context.filesDir, OWN_FILE).exists() &&
                (WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM) == id || LiveWallpaper.isActive(context))
        } catch (_: Exception) { false }

        fun recordOwnCopy(context: Context, bmp: Bitmap, set: () -> Int): Int {
            val file = java.io.File(context.filesDir, OWN_FILE)
            val tmp = java.io.File(context.filesDir, "$OWN_FILE.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val id = set().takeIf { it > 0 } ?: WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            if (!tmp.renameTo(file)) throw java.io.IOException("couldn't keep the wallpaper copy")
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_SYSTEM_ID, id).putLong(KEY_COPY_STAMP, prefs.getLong(KEY_COPY_STAMP, 0L) + 1).commit()
            return id
        }

        fun setHomeBitmap(context: Context, bmp: Bitmap): Int {
            val wm = WallpaperManager.getInstance(context)
            if (!LiveWallpaper.isActive(context)) return recordOwnCopy(context, bmp) { wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM) }
            return recordOwnCopy(context, bmp) { wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM) }.also { LiveWallpaper.contentChanged() }
        }

        @Synchronized fun adoptLiveId(context: Context) {
            if (!LiveWallpaper.isActive(context)) return
            val id = WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val old = prefs.getInt(KEY_SYSTEM_ID, -1)
            if (id <= 0 || old < 0 || old == id) return
            val e = prefs.edit().putInt(KEY_SYSTEM_ID, id)
            if (prefs.getInt(WallpaperBlur.KEY_BLURRED_ID, -1) == old) e.putInt(WallpaperBlur.KEY_BLURRED_ID, id)
            e.commit()
        }

        fun setDisplayBitmap(context: Context, bmp: Bitmap, which: Int) {
            val wm = WallpaperManager.getInstance(context)
            when {
                which and WallpaperManager.FLAG_SYSTEM != 0 && LiveWallpaper.isActive(context) -> {
                    setHomeBitmap(context, bmp)
                    if (which and WallpaperManager.FLAG_LOCK != 0) wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_LOCK)
                }
                which and WallpaperManager.FLAG_SYSTEM != 0 -> recordOwnCopy(context, bmp) { wm.setBitmap(bmp, null, true, which) }
                else -> wm.setBitmap(bmp, null, true, which)
            }
            if (which and WallpaperManager.FLAG_LOCK != 0) recordLockCopy(context, bmp)
        }

        fun recordLockCopy(context: Context, bmp: Bitmap) {
            val id = WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_LOCK)
            if (id <= 0) return
            val file = java.io.File(context.filesDir, LOCK_FILE)
            val tmp = java.io.File(context.filesDir, "$LOCK_FILE.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (tmp.renameTo(file)) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_LOCK_ID, id).apply()
        }

        fun lockCopyFile(context: Context): java.io.File? = try {
            val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LOCK_ID, -1)
            val f = java.io.File(context.filesDir, LOCK_FILE)
            f.takeIf { id > 0 && it.exists() && WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_LOCK) == id }
        } catch (_: Exception) { null }

        fun drawFramed(c: Canvas, b: Bitmap) {
            val s = systemScale()
            c.save()
            c.scale(s, s, b.width / 2f, b.height / 2f)
            c.drawBitmap(b, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
        }

        fun centerCrop(src: Bitmap, w: Int, h: Int): Bitmap {
            val scale = max(w / src.width.toFloat(), h / src.height.toFloat())
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val m = Matrix().apply {
                setScale(scale, scale)
                postTranslate((w - src.width * scale) / 2f, (h - src.height * scale) / 2f)
            }
            Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }

        fun curveMatrix(): ColorMatrix {
            val m = ColorMatrix().apply { setSaturation(SATURATION) }
            val k = (MAX_Y - MIN_Y) / (MAX_X - MIN_X)
            val b = MIN_Y - MIN_X * k
            val curve = ColorMatrix(floatArrayOf(
                k, 0f, 0f, 0f, b,
                0f, k, 0f, 0f, b,
                0f, 0f, k, 0f, b,
                0f, 0f, 0f, 1f, 0f,
            ))
            m.postConcat(curve)
            return m
        }
    }

    init {
        clipChildren = false
        addView(sharpView)
        addView(blurView)
        sharpView.visibility = INVISIBLE
        blurView.visibility = INVISIBLE
        blurView.pivotX = 0f
        blurView.pivotY = 0f
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        sharpView.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        blurView.measure(MeasureSpec.makeMeasureSpec(max(1, w / DOWNSCALE), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(max(1, h / DOWNSCALE), MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        sharpView.layout(0, 0, r - l, b - t)
        blurView.layout(0, 0, blurView.measuredWidth, blurView.measuredHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (maxBlurRadiusPx == 0f) maxBlurRadiusPx = w * 0.16f
        post { reload() }
    }

    private val ownFile = java.io.File(context.filesDir, OWN_FILE)
    private val ownPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val ownCopyRecorded = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_SYSTEM_ID || key == KEY_COPY_STAMP) reload()
    }
    @Volatile private var destroyed = false
    init { ownPrefs.registerOnSharedPreferenceChangeListener(ownCopyRecorded) }

    private fun ownWallpaper(): Bitmap? = try {
        val id = ownPrefs.getInt(KEY_SYSTEM_ID, -1)
        if (id < 0 || !ownFile.exists() || (wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM) != id && !LiveWallpaper.isActive(context))) null
        else android.graphics.BitmapFactory.decodeFile(ownFile.path)
    } catch (_: Exception) { null }

    /** The wallpaper as the system has it: readable when All files access is granted, and not for a live wallpaper. */
    private fun systemWallpaper(): Bitmap? = try {
        if (!android.os.Environment.isExternalStorageManager() || wm.wallpaperInfo != null) null
        else (wm.getDrawable() as? android.graphics.drawable.BitmapDrawable)?.bitmap?.let {
            if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it
        }
    } catch (_: Exception) { null }

    @Volatile private var loaded = Triple(-2, -2, -2L)
    private fun ids() = Triple(wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM), ownPrefs.getInt(KEY_SYSTEM_ID, -1), ownPrefs.getLong(KEY_COPY_STAMP, 0L))

    fun reloadIfChanged() {
        if (!destroyed) io.execute { if (ids() != loaded) main.post { reload() } }
    }

    var onLoaded: (() -> Unit)? = null

    fun reload() {
        val w = width
        val h = height
        if (w == 0 || h == 0 || destroyed) return
        io.execute {
            loaded = ids()
            val src = ownWallpaper() ?: systemWallpaper()
            android.util.Log.i("OneUiHome", "wallpaper copy: " + if (src == null) "none (system ${loaded.first}, copy ${loaded.second})" else "loaded (${loaded.first})")
            if (src == null) { main.post { if (!destroyed) { available = false; updateViews(); onLoaded?.invoke() } }; return@execute }
            val f = if (src.width == w && src.height == h) src else centerCrop(src, w, h)
            val s = Bitmap.createScaledBitmap(f, max(1, w / DOWNSCALE), max(1, h / DOWNSCALE), true)
            main.post {
                if (destroyed) return@post
                full = f; small = s
                sharpView.bitmap = f
                blurView.bitmap = s
                available = true
                updateViews()
                onLoaded?.invoke()
            }
        }
    }

    fun setBlur(progress: Float) {
        blurProgress = progress.coerceIn(0f, 1f)
        updateViews()
    }

    var copyAlpha = 1f
        set(v) { field = v; sharpView.alpha = v }

    var holdCopy = false
        set(v) { field = v; updateViews() }

    var drivesLive = false

    fun setZoom(z: Float) {
        zoom = z
        if (drivesLive) LiveWallpaper.setZoom(z)
        updateViews()
    }

    private var lastRadius = -1f

    private fun updateViews() {
        if (!available) {
            blurView.visibility = INVISIBLE; sharpView.visibility = INVISIBLE
            return
        }
        val zoomed = zoom != 1f
        val live = drivesLive && LiveWallpaper.drives
        sharpView.visibility = if ((zoomed || holdCopy) && blurProgress < 1f && !live) VISIBLE else INVISIBLE
        val z = zoom * systemScale
        sharpView.scaleX = z; sharpView.scaleY = z
        if (blurProgress > 0f) {
            blurView.visibility = VISIBLE
            blurView.alpha = (blurProgress * 3f).coerceAtMost(1f)
            val r = (blurProgress * maxBlurRadiusPx / DOWNSCALE).coerceAtLeast(0.01f)
            if (kotlin.math.abs(r - lastRadius) > 0.05f) {
                lastRadius = r
                blurView.setRenderEffect(
                    RenderEffect.createColorFilterEffect(
                        ColorMatrixColorFilter(interpolatedCurve(blurProgress)),
                        RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP),
                    )
                )
            }
            val s = DOWNSCALE * z
            blurView.pivotX = 0f; blurView.pivotY = 0f
            blurView.scaleX = s; blurView.scaleY = s
            blurView.translationX = width * (1 - z) / 2f
            blurView.translationY = height * (1 - z) / 2f
        } else {
            blurView.visibility = INVISIBLE
        }
    }

    private fun interpolatedCurve(p: Float): ColorMatrix {
        val target = curveMatrix().array
        val id = ColorMatrix().array
        val t = p.coerceIn(0f, 1f)
        return ColorMatrix(FloatArray(20) { id[it] + (target[it] - id[it]) * t })
    }

    fun drawSnapshot(c: Canvas): Boolean {
        drawFramed(c, full ?: return false)
        return true
    }

    fun destroy() {
        destroyed = true
        ownPrefs.unregisterOnSharedPreferenceChangeListener(ownCopyRecorded)
        io.shutdown()
    }

    private class BitmapView(context: Context) : View(context) {
        var bitmap: Bitmap? = null
            set(v) { field = v; invalidate() }
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        override fun onDraw(canvas: Canvas) {
            val b = bitmap ?: return
            canvas.drawBitmap(b, null, android.graphics.Rect(0, 0, width, height), paint)
        }
    }

    @Suppress("unused")
    private fun Float.r() = roundToInt()
}
