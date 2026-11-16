package com.oneuiclone.home.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.os.Bundle
import android.os.UserHandle
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.RemoteViews
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.ui.GlassPaint
import com.oneuiclone.home.ui.WidgetGlass
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * App widget hosting. One UI clips widgets to 26dp corners (AppTransitionParams.WIDGET_FINAL_RADIUS).
 */
class WidgetManager(private val activity: Activity) {

    companion object {
        const val HOST_ID = 0x0E1
        const val REQUEST_BIND = 4101
        const val REQUEST_CONFIGURE = 4102
    }

    val appWidgetManager: AppWidgetManager = AppWidgetManager.getInstance(activity)
    val host = object : AppWidgetHost(activity, HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
            RoundedWidgetView(context)
    }

    /**
     * Widget clicks that send a PendingIntent: RemoteViews starts it through the clicked view's context, which is
     * the widget's [LaunchContext]. Return true when the launcher sends it itself (by calling `send`).
     */
    var onLaunch: ((appWidgetId: Int, sender: IntentSender, send: () -> Unit) -> Boolean)? = null

    /** Pending add: provider waiting for bind/config result. */
    var pending: Pending? = null

    class Pending(val appWidgetId: Int, val info: AppWidgetProviderInfo, val onDone: (Int, AppWidgetProviderInfo) -> Unit,
                  val onCancel: () -> Unit = {})

    private var listening = false

    /**
     * Listen from first start until destroy (Quickstep's widget holder does the same). Restarting on every
     * onStart re-applies every RemoteViews, which shows up as the Home screen "refreshing" after an app closes.
     */
    fun start() {
        if (listening) return
        try { host.startListening(); listening = true } catch (_: Exception) {}
    }

    fun stop() {
        if (!listening) return
        listening = false
        try { host.stopListening() } catch (_: Exception) {}
    }

    fun providers(): List<AppWidgetProviderInfo> = appWidgetManager.installedProviders
        .filter { (it.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) != 0 }

    fun findProvider(cn: ComponentName, user: UserHandle? = null): AppWidgetProviderInfo? =
        appWidgetManager.getInstalledProvidersForPackage(cn.packageName, user).firstOrNull { it.provider == cn }

    /** Cell span for a provider (targetCellWidth/Height when declared, else min size). */
    fun spanFor(info: AppWidgetProviderInfo, dp: DeviceProfile): Pair<Int, Int> {
        if (info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            return info.targetCellWidth.coerceAtMost(dp.homeCols) to info.targetCellHeight.coerceAtMost(dp.homeRows)
        }
        val sx = max(1, ceil(info.minWidth / dp.cellWidth.toFloat()).toInt())
        val sy = max(1, ceil(info.minHeight / dp.cellHeight.toFloat()).toInt())
        return sx.coerceAtMost(dp.homeCols) to sy.coerceAtMost(dp.homeRows)
    }

    /** Start adding [info]; [onDone] is called with the bound id once ready, [onCancel] if the user says no. */
    fun add(info: AppWidgetProviderInfo, onCancel: () -> Unit = {}, onDone: (Int, AppWidgetProviderInfo) -> Unit) {
        val id = host.allocateAppWidgetId()
        val opts = Bundle()
        if (appWidgetManager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, opts)) {
            configureOrFinish(id, info, onDone, onCancel)
        } else {
            pending = Pending(id, info, onDone, onCancel)
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            activity.startActivityForResult(intent, REQUEST_BIND)
        }
    }

    private fun configureOrFinish(id: Int, info: AppWidgetProviderInfo, onDone: (Int, AppWidgetProviderInfo) -> Unit,
                                  onCancel: () -> Unit = {}) {
        val needsConfig = info.configure != null &&
            (info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL) == 0
        if (needsConfig) {
            pending = Pending(id, info, onDone, onCancel)
            try {
                host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE, null)
            } catch (e: Exception) {
                pending = null; onDone(id, info)
            }
        } else {
            onDone(id, info)
        }
    }

    /** Forwarded from Activity.onActivityResult. */
    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        val p = pending ?: return false
        when (requestCode) {
            REQUEST_BIND -> {
                pending = null
                if (resultCode == Activity.RESULT_OK) configureOrFinish(p.appWidgetId, p.info, p.onDone, p.onCancel)
                else { host.deleteAppWidgetId(p.appWidgetId); p.onCancel() }
                return true
            }
            REQUEST_CONFIGURE -> {
                pending = null
                if (resultCode == Activity.RESULT_OK) p.onDone(p.appWidgetId, p.info) else { host.deleteAppWidgetId(p.appWidgetId); p.onCancel() }
                return true
            }
        }
        return false
    }

    /**
     * Host views survive workspace rebinds (drop, rename, resize, page changes): creating a new one re-inflates
     * the widget's RemoteViews, which is slow and makes widgets flash. Only a span change re-sizes it.
     */
    private val views = HashMap<Int, AppWidgetHostView>()
    private val sizes = HashMap<Int, SizeF>()
    private val themes = HashMap<Int, Int>()

    fun createView(context: Context, appWidgetId: Int, dp: DeviceProfile, spanX: Int, spanY: Int): AppWidgetHostView? {
        val info = appWidgetManager.getAppWidgetInfo(appWidgetId) ?: return null
        val theme = WidgetStyles.theme(activity, appWidgetId, info.provider.packageName)
        val v = views[appWidgetId]?.takeIf { it.appWidgetInfo?.provider == info.provider && themes[appWidgetId] == theme }
            ?: host.createView(LaunchContext(context, appWidgetId, theme), appWidgetId, info).also {
                views[appWidgetId]?.let { old -> (old.parent as? ViewGroup)?.removeView(old) }
                views[appWidgetId] = it; themes[appWidgetId] = theme; sizes.remove(appWidgetId)
            }
        (v.parent as? ViewGroup)?.removeView(v)
        val padX = 2 * dp.widgetPadXPx
        val padY = 2 * dp.widgetPadYPx
        val wDp = ((spanX * dp.cellWidth - padX) / dp.density).coerceAtLeast(1f)
        val hDp = ((spanY * dp.cellHeight - padY) / dp.density).coerceAtLeast(1f)
        val size = SizeF(wDp, hDp)
        if (sizes[appWidgetId] != size) {
            val opts = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp.roundToInt())
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp.roundToInt())
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp.roundToInt())
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp.roundToInt())
            }
            v.updateAppWidgetSize(opts, listOf(size))
            sizes[appWidgetId] = size
        }
        return v
    }

    /**
     * Drop every cached host view so the next bind inflates the widgets' RemoteViews again. Needed on a light/dark
     * switch: the activity handles uiMode itself, and an existing view keeps the layout it inflated in the old mode.
     */
    fun clearViews() {
        views.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        views.clear(); sizes.clear()
    }

    fun delete(appWidgetId: Int) {
        views.remove(appWidgetId); sizes.remove(appWidgetId); themes.remove(appWidgetId)
        WidgetStyles.clear(activity, appWidgetId)
        try { host.deleteAppWidgetId(appWidgetId) } catch (_: Exception) {}
    }

    /**
     * A widget's view context. The public route to a widget's clicks: AppWidgetHostView's InteractionHandler is
     * hidden API, but its default handler starts every PendingIntent with `view.context.startIntentSender`.
     */
    private inner class LaunchContext(base: Context, private val appWidgetId: Int, theme: Int) : ContextWrapper(base) {
        /**
         * Style › Theme: the widget's light or dark version whatever the system mode. RemoteViews inflates with
         * this context's configuration (getContextForResourcesEnsuringCorrectCachedApkPaths keeps it), so its
         * own night resources and day/night colours follow it.
         */
        private val themed: Context? = if (theme == WidgetStyles.THEME_AUTO) null else base.createConfigurationContext(
            android.content.res.Configuration(base.resources.configuration).apply {
                uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (theme == WidgetStyles.THEME_DARK) android.content.res.Configuration.UI_MODE_NIGHT_YES
                    else android.content.res.Configuration.UI_MODE_NIGHT_NO
            })

        override fun getResources(): android.content.res.Resources = themed?.resources ?: super.getResources()

        override fun startIntentSender(intent: IntentSender, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int,
                                       extraFlags: Int, options: Bundle?) {
            val send = { super.startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, options) }
            if (onLaunch?.invoke(appWidgetId, intent, send) != true) send()
        }

        override fun startIntentSender(intent: IntentSender, fillInIntent: Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int) =
            startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, null)
    }

    /** AppWidgetHostView clipped to One UI's 26dp widget radius. */
    class RoundedWidgetView(context: Context) : AppWidgetHostView(context) {
        private val radius = 26f * context.resources.displayMetrics.density
        init {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        }

        /**
         * Style › Blur: the widget's own background at [backgroundOpacity] over [glass], which is masked by that
         * same background so it takes its exact shape. Its content stays as it is. Every update can replace the
         * background, so this is re-applied after updates and checked before each frame (which also redraws the
         * glass when the widget moves).
         */
        var backgroundOpacity = 1f
            set(v) { field = v.coerceIn(0f, 1f); applyStyle() }
        var glass: WidgetGlass? = null
            set(v) {
                if (field === v) return
                if (isAttachedToWindow) { field?.views?.remove(this); v?.views?.add(this) }
                field = v
                applyStyle()
            }
        private val styled get() = backgroundOpacity < 1f || glass != null
        private val wrapped = ArrayList<GlassDrawable>()
        private val keepStyled = ViewTreeObserver.OnPreDrawListener { applyStyle(); true }

        override fun updateAppWidget(remoteViews: RemoteViews?) {
            super.updateAppWidget(remoteViews)
            getChildAt(0)?.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            keyDirty = true
            applyStyle()
        }

        override fun addView(child: View?, index: Int, params: ViewGroup.LayoutParams?) {
            val lp = (params as? LayoutParams)?.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
            } ?: LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            super.addView(child, index, lp)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            keyDirty = true
            glass?.views?.add(this)
            viewTreeObserver.addOnPreDrawListener(keepStyled)
        }

        override fun onDetachedFromWindow() {
            viewTreeObserver.removeOnPreDrawListener(keepStyled)
            glass?.views?.remove(this)
            super.onDetachedFromWindow()
        }

        private fun applyStyle() {
            if (!styled && wrapped.isEmpty() && !keyed) return
            val root = getChildAt(0) ?: return
            val targets = backgroundImages(root) + listOfNotNull(backgroundView(root))
            wrapped.clear()
            for (v in targets) {
                val d = (if (v is ImageView) v.drawable else v.background) ?: continue
                if (!styled) { if (d is GlassDrawable) setDrawable(v, d.drawable!!); continue }
                val g = d as? GlassDrawable ?: GlassDrawable(d, v, this).also { setDrawable(v, null); setDrawable(v, it) }
                g.update(backgroundOpacity, glass)
                wrapped += g
            }
            if (styled && wrapped.isEmpty()) applyKey() else clearKey()
        }

        // ---- colour-key fallback ----------------------------------------------------------------------------
        private var keyed = false
        private var keyColor = 0
        private var keyInner = 0
        private var keyDirty = true
        private var keyApplied = 0
        private var keyInnerApplied = 0
        private var keyRetries = 0
        private val keyRetry = Runnable { applyStyle() }
        private var keyShader: RuntimeShader? = null
        private val keyAt = Matrix()
        private val keyToWindow = Matrix()
        private val keyGlassMatrix = Matrix()
        private var keyBitmap: Bitmap? = null
        private var keyOpacity = -1f
        private var keyW = 0
        private var keyH = 0

        private fun applyKey() {
            if (width == 0 || height == 0) return
            if (keyDirty || width != keyW || height != keyH) {
                val k = readKey()
                when {
                    k != null -> { keyColor = k.first; keyInner = k.second; keyDirty = false; keyRetries = 0 }
                    keyRetries < ColourKey.KEY_RETRIES -> { keyRetries++; removeCallbacks(keyRetry); postDelayed(keyRetry, 300) }
                    else -> { keyDirty = false; keyRetries = 0; if (keyColor == 0) return }
                }
            }
            val key = keyColor
            val inner = keyInner
            keyToWindow.reset(); transformMatrixToGlobal(keyToWindow)
            val g = glass
            val bmp = g?.bitmap
            if (keyed && keyToWindow == keyAt && bmp === keyBitmap && backgroundOpacity == keyOpacity && width == keyW &&
                height == keyH && key == keyApplied && inner == keyInnerApplied) return
            keyAt.set(keyToWindow); keyBitmap = bmp; keyOpacity = backgroundOpacity; keyW = width; keyH = height
            keyApplied = key; keyInnerApplied = inner
            val sh = keyShader ?: RuntimeShader(ColourKey.AGSL).also { keyShader = it }
            val glassShader = if (bmp != null && keyToWindow.invert(keyGlassMatrix)) {
                keyGlassMatrix.preScale(g.scale, g.scale)
                BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(keyGlassMatrix) }
            } else ColourKey.NO_GLASS
            sh.setInputShader("glass", glassShader)
            sh.setFloatUniform("key", android.graphics.Color.red(key) / 255f, android.graphics.Color.green(key) / 255f,
                android.graphics.Color.blue(key) / 255f, android.graphics.Color.alpha(key) / 255f)
            sh.setFloatUniform("inner", android.graphics.Color.red(inner) / 255f, android.graphics.Color.green(inner) / 255f,
                android.graphics.Color.blue(inner) / 255f, android.graphics.Color.alpha(inner) / 255f)
            sh.setFloatUniform("opacity", backgroundOpacity)
            sh.setFloatUniform("hasGlass", if (glassShader === ColourKey.NO_GLASS) 0f else 1f)
            setRenderEffect(RenderEffect.createRuntimeShaderEffect(sh, "content"))
            keyed = true
        }

        private fun firstSolid(col: Bitmap): Int? {
            val run = 4
            for (y in 0 until col.height - run) {
                val c = col.getPixel(0, y)
                if (android.graphics.Color.alpha(c) < 250) continue
                if ((1..run).all { col.getPixel(0, y + it) == c }) return c
            }
            return null
        }

        private fun clearKey() {
            keyDirty = true
            removeCallbacks(keyRetry)
            if (!keyed) return
            keyed = false
            setRenderEffect(null)
        }

        private fun innerSolid(col: Bitmap, key: Int): Int {
            val minRun = (ColourKey.INNER_RUN_DP * resources.displayMetrics.density).toInt().coerceAtLeast(4)
            val runs = HashMap<Int, Int>()
            var y = 0
            while (y < col.height) {
                val c = col.getPixel(0, y)
                var end = y + 1
                while (end < col.height && col.getPixel(0, end) == c) end++
                if (end - y >= minRun && ColourKey.isInnerCard(c, key)) runs[c] = (runs[c] ?: 0) + end - y
                y = end
            }
            return runs.maxByOrNull { it.value }?.key ?: 0
        }

        private fun readKey(): Pair<Int, Int>? {
            val root = getChildAt(0) ?: return null
            if (root.width == 0 || root.height == 0) return null
            val clearInner = ColourKey.clearsInner(appWidgetInfo?.provider)
            val depth = if (clearInner) height else (ColourKey.DEPTH_DP * resources.displayMetrics.density).toInt().coerceIn(8, height)
            val x = width / 2
            return try {
                val pic = android.graphics.Picture()
                val c = pic.beginRecording(width, depth)
                c.clipRect(x, 0, x + 1, depth)
                c.translate(root.left.toFloat(), root.top.toFloat())
                root.draw(c)
                pic.endRecording()
                val hw = Bitmap.createBitmap(pic, width, depth, Bitmap.Config.HARDWARE)
                val col = hw.copy(Bitmap.Config.ARGB_8888, false)
                hw.recycle()
                val column = Bitmap.createBitmap(col, x, 0, 1, depth)
                val key = firstSolid(column) ?: return null
                key to if (clearInner) innerSolid(column, key) else 0
            } catch (e: Exception) {
                android.util.Log.w("OneUiHome", "widget colour key read failed", e)
                null
            }
        }

        fun describe(): String {
            val sb = StringBuilder()
            val root = getChildAt(0)
            sb.append("provider: ").append(appWidgetInfo?.provider?.flattenToShortString()).append('\n')
            sb.append("android: ").append(android.os.Build.VERSION.RELEASE).append(" (sdk ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
            sb.append("host: ").append(width).append('x').append(height).append('\n')
            sb.append("style: opacity ").append(backgroundOpacity).append(", glass ").append(glass?.bitmap != null)
                .append(", wrapped ").append(wrapped.size).append(", colour key ").append(keyed)
                .append(" #").append(Integer.toHexString(keyColor)).append(" inner #").append(Integer.toHexString(keyInner)).append('\n')
            sb.append("background view: ").append(root?.let { backgroundView(it) }?.let { name(it) })
                .append(", background images: ").append(root?.let { backgroundImages(it).size }).append("\n\n")
            fun walk(v: View, depth: Int) {
                sb.append("  ".repeat(depth)).append(name(v))
                    .append(" [").append(v.left).append(',').append(v.top).append(' ').append(v.width).append('x').append(v.height).append(']')
                if (v.visibility != VISIBLE) sb.append(if (v.visibility == GONE) " GONE" else " INVISIBLE")
                if (v.alpha != 1f) sb.append(" alpha=").append(v.alpha)
                if (v.clipToOutline) sb.append(" clipToOutline")
                v.background?.let { sb.append(" bg=").append(drawableInfo(it)) }
                v.backgroundTintList?.let { sb.append(" bgTint=#").append(Integer.toHexString(it.defaultColor)) }
                if (v is ImageView) v.drawable?.let { sb.append(" src=").append(drawableInfo(it)) }
                if (v is android.widget.TextView) sb.append(" text=\"").append(v.text.toString().take(30)).append('"')
                sb.append('\n')
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
            root?.let { walk(it, 0) }
            return sb.toString()
        }

        private fun name(v: View): String {
            val id = if (v.id == View.NO_ID) "" else try { "#" + v.resources.getResourceEntryName(v.id) } catch (_: Exception) { "#" + Integer.toHexString(v.id) }
            return v.javaClass.name.substringAfterLast('.') + id
        }

        private fun drawableInfo(d: Drawable): String = when (d) {
            is android.graphics.drawable.ColorDrawable -> "Color#" + Integer.toHexString(d.color)
            is android.graphics.drawable.GradientDrawable -> "Gradient(" + (d.color?.defaultColor?.let { "#" + Integer.toHexString(it) } ?: "-") + ", r=" + d.cornerRadius + ")"
            is DrawableWrapper -> d.javaClass.simpleName + "(" + (d.drawable?.let { drawableInfo(it) } ?: "-") + ")"
            else -> d.javaClass.name.substringAfterLast('.') + " " + d.intrinsicWidth + "x" + d.intrinsicHeight
        }

        private fun setDrawable(v: View, d: Drawable?) = if (v is ImageView) v.setImageDrawable(d) else v.background = d

        private fun backgroundView(root: View): View? {
            root.findViewById<View>(android.R.id.background)?.takeIf { it.background != null }?.let { return it }
            var v: View = root
            while (v.background == null) v = (v as? ViewGroup)?.takeIf { it.childCount == 1 }?.getChildAt(0) ?: return null
            return v
        }

        private fun backgroundImages(root: View): List<ImageView> {
            var g = root as? ViewGroup ?: return emptyList()
            while (g.childCount == 1) g = g.getChildAt(0) as? ViewGroup ?: return emptyList()
            if (g.width == 0 || g.height == 0) return emptyList()
            val out = ArrayList<ImageView>()
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                if (c.visibility == GONE) continue
                if (c !is ImageView || c.width < g.width * 0.9f || c.height < g.height * 0.9f) break
                out += c
            }
            return out
        }
    }

    /** The colour-key fallback's shader ([RoundedWidgetView] with no background view to wrap). */
    private object ColourKey {
        const val KEY_RETRIES = 10
        const val DEPTH_DP = 28f
        const val INNER_RUN_DP = 12f
        private val CLEAR_INNER = setOf("com.google.android.apps.weather")

        fun clearsInner(provider: ComponentName?): Boolean = provider != null && (provider.packageName in CLEAR_INNER ||
            provider.packageName == "com.google.android.googlequicksearchbox" && provider.className.contains("weather", true))

        fun isInnerCard(c: Int, key: Int): Boolean {
            if (android.graphics.Color.alpha(c) < 250) return false
            val r = android.graphics.Color.red(c); val g = android.graphics.Color.green(c); val b = android.graphics.Color.blue(c)
            if (maxOf(r, g, b) - minOf(r, g, b) > 16) return false
            val lum = (r + g + b) / 3
            if (lum in 40..215) return false
            val keyLum = (android.graphics.Color.red(key) + android.graphics.Color.green(key) + android.graphics.Color.blue(key)) / 3
            if (if (keyLum < 128) lum > keyLum else lum < keyLum) return false
            val dr = r - android.graphics.Color.red(key); val dg = g - android.graphics.Color.green(key); val db = b - android.graphics.Color.blue(key)
            return dr * dr + dg * dg + db * db > 6 * 6
        }

        val NO_GLASS = BitmapShader(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

        const val AGSL = """
            uniform shader content;
            uniform shader glass;
            uniform float4 key;
            uniform float4 inner;
            uniform float opacity;
            uniform float hasGlass;

            half4 main(float2 p) {
                half4 c = content.eval(p);
                if (key.a < 0.9 || c.a < 0.004) return c;
                float3 u = float3(c.rgb) / float(c.a);
                float t = 0.0;
                if (inner.a > 0.9) {
                    float3 e = inner.rgb - key.rgb;
                    t = clamp(dot(u - key.rgb, e) / dot(e, e), 0.0, 1.0);
                }
                float3 k = mix(key.rgb, inner.rgb, t);
                float w = 1.0 - smoothstep(0.015, 0.08, distance(u, k));
                half4 g = hasGlass > 0.5 ? glass.eval(p) : half4(0.0);
                half4 bg = half4(half3(k), 1.0) * opacity + g * (1.0 - opacity);
                return mix(c, bg * c.a, w);
            }
        """
    }

    /**
     * A widget background ([owner]'s background or image) drawn at [opacity] over [glass].
     */
    private class GlassDrawable(inner: Drawable, private val owner: View, private val host: View) : DrawableWrapper(inner) {
        private val box = android.graphics.RectF()
        private var opacity = 1f
        private var glass: WidgetGlass? = null
        private val toWindow = Matrix()
        private val drawnAt = Matrix()
        private var drawnBitmap: Bitmap? = null
        private val glassPaint = GlassPaint().apply { paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }

        fun update(opacity: Float, glass: WidgetGlass?) {
            var dirty = opacity != this.opacity || glass !== this.glass
            this.opacity = opacity; this.glass = glass
            if (glass != null) {
                locate(toWindow)
                if (toWindow != drawnAt || glass.bitmap !== drawnBitmap) dirty = true
            }
            if (dirty) invalidateSelf()
        }

        private fun locate(m: Matrix) {
            m.reset()
            owner.transformMatrixToGlobal(m)
            if (owner is ImageView) {
                m.preTranslate(owner.paddingLeft.toFloat(), owner.paddingTop.toFloat())
                m.preConcat(owner.imageMatrix)
            }
        }

        override fun draw(canvas: Canvas) {
            val l = bounds.left.toFloat(); val t = bounds.top.toFloat()
            val r = bounds.right.toFloat(); val b = bounds.bottom.toFloat()
            drawnBitmap = glass?.bitmap
            locate(drawnAt)
            box.set(l, t, r, b)
            if (glassPaint.aim(glass, drawnAt)) {
                val sc = canvas.saveLayer(l, t, r, b, null)
                super.draw(canvas)
                canvas.drawRect(l, t, r, b, glassPaint.paint)
                canvas.restoreToCount(sc)
            }
            if (opacity > 0f) {
                val sc = canvas.saveLayerAlpha(l, t, r, b, (opacity * 255).roundToInt())
                super.draw(canvas)
                canvas.restoreToCount(sc)
            }
        }
    }
