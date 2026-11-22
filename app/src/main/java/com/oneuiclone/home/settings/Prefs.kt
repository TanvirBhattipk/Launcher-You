package com.oneuiclone.home.settings

import android.content.Context
import android.content.SharedPreferences

/** Launcher settings (One UI "Home screen settings"). */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("home_settings", Context.MODE_PRIVATE)

    init {
        com.oneuiclone.home.icons.IconShape.current = iconShape
        com.oneuiclone.home.ui.LiquidGlass.enabled = false
        com.oneuiclone.home.ui.LiquidGlass.refractionHeightDp = refractionHeight.toFloat()
        com.oneuiclone.home.ui.LiquidGlass.refractionAmountDp = refractionAmount.toFloat()
    }

    var homeCols: Int get() = sp.getInt("home_cols", 4); set(v) = sp.edit().putInt("home_cols", v).apply()
    var homeRows: Int get() = sp.getInt("home_rows", 6); set(v) = sp.edit().putInt("home_rows", v).apply()
    var appsCols: Int get() = sp.getInt("apps_cols", 4); set(v) = sp.edit().putInt("apps_cols", v).apply()
    var appsRows: Int get() = sp.getInt("apps_rows", 6); set(v) = sp.edit().putInt("apps_rows", v).apply()
    /** 85..115 % (Home Up range); reduced by 2% default = 98. */
    var iconScale: Int get() = sp.getInt("icon_scale", 98); set(v) = sp.edit().putInt("icon_scale", v).apply()
    /** Favorite contacts on the search page. */
    var searchContacts: Boolean get() = sp.getBoolean("search_contacts", true); set(v) = sp.edit().putBoolean("search_contacts", v).apply()
    /** Home screen apps: no Apps screen; every app is on a Home page. */
    var homeOnly: Boolean get() = sp.getBoolean("home_only", false); set(v) = sp.edit().putBoolean("home_only", v).apply()
    /** Space (dp) above and below the icons of Home and of the Apps screen (Home screen settings). */
    var homeTopPad: Int get() = sp.getInt("home_top_pad", 0); set(v) = sp.edit().putInt("home_top_pad", v).apply()
    var homeBottomPad: Int get() = sp.getInt("home_bottom_pad", 0); set(v) = sp.edit().putInt("home_bottom_pad", v).apply()
    var appsTopPad: Int get() = sp.getInt("apps_top_pad", 0); set(v) = sp.edit().putInt("apps_top_pad", v).apply()
    var appsBottomPad: Int get() = sp.getInt("apps_bottom_pad", 0); set(v) = sp.edit().putInt("apps_bottom_pad", v).apply()
    var labelScale: Int get() = sp.getInt("label_scale", 100); set(v) = sp.edit().putInt("label_scale", v).apply()
    var homeLabels: Boolean get() = sp.getBoolean("home_labels", true); set(v) = sp.edit().putBoolean("home_labels", v).apply()
    var appsLabels: Boolean get() = sp.getBoolean("apps_labels", true); set(v) = sp.edit().putBoolean("apps_labels", v).apply()
    var swipeDownNotifications: Boolean get() = sp.getBoolean("swipe_down_notif", true); set(v) = sp.edit().putBoolean("swipe_down_notif", v).apply()
    /** Search sends what you type to Google for suggestions; off keeps search entirely on the device. */
    var webSuggestions: Boolean get() = sp.getBoolean("web_suggestions", true); set(v) = sp.edit().putBoolean("web_suggestions", v).apply()
    var swipeUpSearch: Boolean get() = sp.getBoolean("swipe_up_search", false); set(v) = sp.edit().putBoolean("swipe_up_search", v).apply()
    /**
     * Swiping down on Home opens search instead of the notification panel, and swiping up always opens the Apps
     * screen (overrides [swipeDownNotifications] and [swipeUpSearch]); off by default.
     */
    var swipeDownSearch: Boolean get() = sp.getBoolean("swipe_down_search", false); set(v) = sp.edit().putBoolean("swipe_down_search", v).apply()
    /** Home screen: an icon dropped between others slides them along, and the gap an icon leaves is closed by the next icons. */
    var fillEmptyCells: Boolean get() = sp.getBoolean("fill_empty_cells", true); set(v) = sp.edit().putBoolean("fill_empty_cells", v).apply()
    /** Liquid glass disabled across the launcher. */
    var liquidGlass: Boolean get() = false; set(_) = sp.edit().putBoolean("liquid_glass", false).apply()
/** Blur radius defaults (in dp): folder/drawer = 50, home pill = 20. */
var blurHomePill: Int get() = sp.getInt("blur_home_pill", 20); set(v) = sp.edit().putInt("blur_home_pill", v).apply()
var blurAppDrawer: Int get() = sp.getInt("blur_app_drawer", 50); set(v) = sp.edit().putInt("blur_app_drawer", v).apply()
var blurAppSearch: Int get() = appSearchBgBlur; set(v) { appSearchBgBlur = v }
var blurSearchBar: Int get() = appSearchBarBlur; set(v) { appSearchBarBlur = v }
    var blurFolder: Int get() = sp.getInt("blur_folder", 50); set(v) = sp.edit().putInt("blur_folder", v).apply()
    var blurWidget: Int get() = sp.getInt("blur_widget", 40); set(v) = sp.edit().putInt("blur_widget", v).apply()
    // Card 1: App Drawer
    var drawerBgOpacity: Int get() = sp.getInt("drawer_bg_opacity", 60); set(v) = sp.edit().putInt("drawer_bg_opacity", v).apply()
    var drawerBgBlur: Int get() = sp.getInt("drawer_bg_blur", 50); set(v) = sp.edit().putInt("drawer_bg_blur", v).apply()
    var drawerSearchBarOpacity: Int get() = sp.getInt("drawer_search_bar_opacity", 40); set(v) = sp.edit().putInt("drawer_search_bar_opacity", v).apply()
    var drawerSearchBarBlur: Int get() = sp.getInt("drawer_search_bar_blur", 50); set(v) = sp.edit().putInt("drawer_search_bar_blur", v).apply()
    var drawerSearchBorderSize: Int get() = sp.getInt("drawer_search_border_size", 10); set(v) = sp.edit().putInt("drawer_search_border_size", v).apply()
    var drawerSearchBorderOpacity: Int get() = sp.getInt("drawer_search_border_opacity", 30); set(v) = sp.edit().putInt("drawer_search_border_opacity", v).apply()

    // Card 2: Home Search Pill
    var homePillBgOpacity: Int get() = sp.getInt("home_pill_bg_opacity", 40); set(v) = sp.edit().putInt("home_pill_bg_opacity", v).apply()
    var homePillBgBlur: Int get() = sp.getInt("home_pill_bg_blur", 20); set(v) = sp.edit().putInt("home_pill_bg_blur", v).apply()
    var homePillBorderSize: Int get() = sp.getInt("home_pill_border_size", 10); set(v) = sp.edit().putInt("home_pill_border_size", v).apply()
    var homePillBorderOpacity: Int get() = sp.getInt("home_pill_border_opacity", 30); set(v) = sp.edit().putInt("home_pill_border_opacity", v).apply()

    // Card 3: App Search (Overlay)
    var appSearchBgOpacity: Int get() = sp.getInt("app_search_bg_opacity", 60); set(v) = sp.edit().putInt("app_search_bg_opacity", v).apply()
    var appSearchBgBlur: Int get() = sp.getInt("app_search_bg_blur", 50); set(v) = sp.edit().putInt("app_search_bg_blur", v).apply()
    var appSearchBarOpacity: Int get() = sp.getInt("app_search_bar_opacity", 40); set(v) = sp.edit().putInt("app_search_bar_opacity", v).apply()
    var appSearchBarBlur: Int get() = sp.getInt("app_search_bar_blur", 50); set(v) = sp.edit().putInt("app_search_bar_blur", v).apply()
    var appSearchBorderSize: Int get() = sp.getInt("app_search_border_size", 10); set(v) = sp.edit().putInt("app_search_border_size", v).apply()
    var appSearchBorderOpacity: Int get() = sp.getInt("app_search_border_opacity", 30); set(v) = sp.edit().putInt("app_search_border_opacity", v).apply()

    // Card 4: App Search Cards
    var appSearchCardsBgOpacity: Int get() = sp.getInt("app_search_cards_bg_opacity", 40); set(v) = sp.edit().putInt("app_search_cards_bg_opacity", v).apply()
    var appSearchCardsBgBlur: Int get() = sp.getInt("app_search_cards_bg_blur", 50); set(v) = sp.edit().putInt("app_search_cards_bg_blur", v).apply()
    var appSearchCardsBorderSize: Int get() = sp.getInt("app_search_cards_border_size", 10); set(v) = sp.edit().putInt("app_search_cards_border_size", v).apply()
    var appSearchCardsBorderOpacity: Int get() = sp.getInt("app_search_cards_border_opacity", 30); set(v) = sp.edit().putInt("app_search_cards_border_opacity", v).apply()
    /** Lens height and strength, in dp. */
    var refractionHeight: Int get() = sp.getInt("refraction_height", 12); set(v) = sp.edit().putInt("refraction_height", v).apply()
    var refractionAmount: Int get() = sp.getInt("refraction_amount", 24); set(v) = sp.edit().putInt("refraction_amount", v).apply()
    var lockLayout: Boolean get() = sp.getBoolean("lock_layout", false); set(v) = sp.edit().putBoolean("lock_layout", v).apply()
    var addNewApps: Boolean get() = sp.getBoolean("add_new_apps", false); set(v) = sp.edit().putBoolean("add_new_apps", v).apply()
    var themedIcons: Boolean get() = sp.getBoolean("themed_icons", false); set(v) = sp.edit().putBoolean("themed_icons", v).apply()
    /** Force a tonal theme icon on apps that don't ship a monochrome layer. */
    var themeAllApps: Boolean get() = sp.getBoolean("theme_all_apps", true); set(v) = sp.edit().putBoolean("theme_all_apps", v).apply()
    /** Dark icons (Icon theme › Dark): every app's icon on a dark plate (IconFactory.createDark); excludes [themedIcons]. */
    var darkIcons: Boolean get() = sp.getBoolean("dark_icons", false); set(v) = sp.edit().putBoolean("dark_icons", v).apply()
    /** Minimal icons (Icon theme › Minimal): themed icons' white glyph alone, no plate (needs [themedIcons]). */
    var minimalIcons: Boolean get() = sp.getBoolean("minimal_icons", false); set(v) = sp.edit().putBoolean("minimal_icons", v).apply()
    /**
     * Dot, Prism, Stencil, Satin, Doodle, Sprinkles, Comic or Groovy icons (Icon theme, IconFactory.LOOK_*): the themed glyph drawn in that
     * style on a plate of its own; empty for the other styles (needs [themedIcons]).
     */
    var iconLook: String get() = sp.getString("icon_look", "") ?: ""; set(v) = sp.edit().putString("icon_look", v).apply()
    /** Icons › Light or dark for Dot and Prism: IconFactory.MODE_AUTO (follow dark mode), MODE_LIGHT or MODE_DARK. */
    var iconMode: String get() = sp.getString("icon_mode", com.oneuiclone.home.icons.IconFactory.MODE_AUTO) ?: com.oneuiclone.home.icons.IconFactory.MODE_AUTO
        set(v) = sp.edit().putString("icon_mode", v).apply()
    /** Icons › Icon shape (IconShape.SQUIRCLE, CIRCLE, …). Setting it moves IconShape.current along with it. */
    var iconShape: String get() = sp.getString("icon_shape", com.oneuiclone.home.icons.IconShape.SQUIRCLE) ?: com.oneuiclone.home.icons.IconShape.SQUIRCLE
        set(v) { sp.edit().putString("icon_shape", v).apply(); com.oneuiclone.home.icons.IconShape.current = v }
    /** Theme icons on the Apps screen and in search too, not just Home (needs [themedIcons]). */
    var themeAppsScreen: Boolean get() = sp.getBoolean("theme_apps_screen", false); set(v) = sp.edit().putBoolean("theme_apps_screen", v).apply()
    /**
     * Glass icons: themed icons whose solid plate is see-through by [glassIconTransparency] (0..100 %) over the
     * blurred wallpaper, like widgets and folders set to Style › Blur.
     */
    var glassIcons: Boolean get() = sp.getBoolean("glass_icons", false); set(v) = sp.edit().putBoolean("glass_icons", v).apply()
    var glassIconTransparency: Int get() = sp.getInt("glass_icon_transparency", 50); set(v) = sp.edit().putInt("glass_icon_transparency", v).apply()
    /** Opacity of a themed icon's plate: below 1 only with glass icons on (and theme icons, which they build on). */
    val iconPlateAlpha: Float get() = if (themedIcons && glassIcons) 1f - glassIconTransparency / 100f else 1f
    /** Icons show the blurred wallpaper under them: glass icons' see-through plate, or Stencil's cut-out symbol. */
    val iconGlass: Boolean get() = iconPlateAlpha < 1f || (themedIcons && iconLook == com.oneuiclone.home.icons.IconFactory.LOOK_STENCIL)
    /** Good Lock Home Up "pop-up folder" style. */
    var popupFolders: Boolean get() = sp.getBoolean("popup_folders", false); set(v) = sp.edit().putBoolean("popup_folders", v).apply()
    /** "Bubble" in an app's quick options (see AppBubbles); off until the user turns it on. */
    var appBubbles: Boolean get() = sp.getBoolean("app_bubbles", false); set(v) = sp.edit().putBoolean("app_bubbles", v).apply()
    /** First-run setup (SetupActivity) has been shown; it opens by itself only once. */
    var setupShown: Boolean get() = sp.getBoolean("setup_shown", false); set(v) = sp.edit().putBoolean("setup_shown", v).apply()
    var badges: Boolean get() = sp.getBoolean("badges", true); set(v) = sp.edit().putBoolean("badges", v).apply()
    var badgeNumbers: Boolean get() = sp.getBoolean("badge_numbers", true); set(v) = sp.edit().putBoolean("badge_numbers", v).apply()
    /** Blur the wallpaper behind the Home screen; [homeBlurAmount] is % of the Apps screen blur. */
    var homeBlur: Boolean get() = sp.getBoolean("home_blur", false); set(v) = sp.edit().putBoolean("home_blur", v).apply()
    var homeBlurAmount: Int get() = sp.getInt("home_blur_amount", 40); set(v) = sp.edit().putInt("home_blur_amount", v).apply()
    /** The baked wallpaper blur is gone (the Apps screen's own look is set by its opacity): always 0 = sharp. */
    val homeBlurLevel: Float get() = 0f

    var openAnimation: String get() = sp.getString("open_animation", OPEN_ZOOM_IN) ?: OPEN_ZOOM_IN; set(v) = sp.edit().putString("open_animation", v).apply()
    val zoomInOpen: Boolean get() = openAnimation == OPEN_ZOOM_IN
    val openEffect: com.oneuiclone.home.ui.LiveWallpaper.Kind? get() = when (openAnimation) {
        OPEN_AURA -> com.oneuiclone.home.ui.LiveWallpaper.Kind.AURA
        OPEN_SHUTTER -> com.oneuiclone.home.ui.LiveWallpaper.Kind.SHUTTER
        OPEN_ORBIT -> com.oneuiclone.home.ui.LiveWallpaper.Kind.ORBIT
        OPEN_ANTICIPATE -> com.oneuiclone.home.ui.LiveWallpaper.Kind.ANTICIPATE
        OPEN_SCAN -> com.oneuiclone.home.ui.LiveWallpaper.Kind.SCAN
        OPEN_MIST -> com.oneuiclone.home.ui.LiveWallpaper.Kind.MIST
        else -> null
    }
    val wallpaperOpen: Boolean get() = openEffect != null

    companion object {
        const val OPEN_SMOOTH = "smooth"
        const val OPEN_ZOOM_IN = "zoom_in"
        const val OPEN_AURA = "aura"
        const val OPEN_SHUTTER = "shutter"
        const val OPEN_ORBIT = "orbit"
        const val OPEN_ANTICIPATE = "anticipate"
        const val OPEN_SCAN = "scan"
        const val OPEN_MIST = "mist"

        /** Keys applied live by the launcher (no view rebuild when only these change). */
        fun isLive(key: String) = key.startsWith("home_blur") ||
            key.startsWith("blur_") ||
            key.startsWith("refraction_") ||
            key == "liquid_glass" ||
            key == "open_animation"
    }
}
