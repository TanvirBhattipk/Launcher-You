package com.oneuiclone.home.ui

import android.app.Activity
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.text.format.DateFormat
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsets
import android.widget.FrameLayout
import com.oneuiclone.home.Launcher
import com.oneuiclone.home.settings.Prefs
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.icons.IconFactory
import com.oneuiclone.home.model.AppKey
import com.oneuiclone.home.model.AppRepository
import com.oneuiclone.home.model.Container
import com.oneuiclone.home.model.HomeItem
import com.oneuiclone.home.model.WorkspaceModel
import com.oneuiclone.home.ui.CellLayout
import com.oneuiclone.home.ui.FolderIconView
import com.oneuiclone.home.ui.Fonts
import com.oneuiclone.home.ui.HomeSearchButton
import com.oneuiclone.home.ui.IconView
import com.oneuiclone.home.ui.LargeFolderIconView
import com.oneuiclone.home.ui.PageIndicator
import com.oneuiclone.home.ui.WallpaperLayer
import com.oneuiclone.home.ui.WidgetGlass
import com.oneuiclone.home.widgets.WidgetStyles
import java.util.concurrent.Executors

/**
 * Wallpaper and style's previews. Home's first page is built from Home's own views (IconView, folders, the dock,
 * the search button and dots) with Home's own [DeviceProfile] at the full display size, off screen, and drawn
 * scaled down, so it's the real layout with the [Look] being edited. Widgets are copies of the running Home's.
 *
 * Wallpapers come from the copies the launcher keeps of ones it set (it can't read the system's); anything else
 * shows as a gradient of the wallpaper's colours.
 */
class StylePreview(private val activity: Activity) {

    /** The icon settings being previewed (sliders preview before they're saved). */
    data class Look(val iconScale: Int, val themed: Boolean, val themeAll: Boolean, val plateAlpha: Float, val labels: Boolean, val dark: Boolean,
                    val minimal: Boolean, val look: String, val mode: String, val shape: String, val labelScale: Int = 100) {
        companion object {
            /** The saved settings, with any slider being dragged in their place. */
            fun of(prefs: Prefs, iconScale: Int = prefs.iconScale, transparency: Int = prefs.glassIconTransparency, labelScale: Int = prefs.labelScale) = Look(
                iconScale = iconScale,
                themed = prefs.themedIcons,
                themeAll = prefs.themeAllApps,
                plateAlpha = if (prefs.themedIcons && prefs.glassIcons) 1f - transparency / 100f else 1f,
                labels = prefs.homeLabels,
                dark = prefs.darkIcons,
                minimal = prefs.minimalIcons,
                look = prefs.iconLook,
                mode = prefs.iconMode,
                shape = prefs.iconShape,
                labelScale = labelScale,
            )
        }
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val iconFactory = IconFactory(activity)
    private val prefs = Prefs(activity)
    private val glass = WidgetGlass()

    /** Display size, portrait, and the system bars Home lays out around. */
    val width: Int
    val height: Int
    private val insets: Rect

    init {
        val m = activity.windowManager.maximumWindowMetrics
        width = minOf(m.bounds.width(), m.bounds.height())
        height = maxOf(m.bounds.width(), m.bounds.height())
        val i = m.windowInsets.getInsets(WindowInsets.Type.systemBars())
        insets = Rect(i.left, i.top, i.right, i.bottom)
    }
