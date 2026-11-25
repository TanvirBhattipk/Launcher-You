package com.oneuiclone.home

import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.graphics.RectF
import android.os.Process
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.Toast
import com.oneuiclone.home.debug.AnimLog
import com.oneuiclone.home.device.DeviceProfile
import com.oneuiclone.home.icons.IconFactory
import com.oneuiclone.home.model.AppInfo
import com.oneuiclone.home.model.AppRepository
import com.oneuiclone.home.model.Container
import com.oneuiclone.home.model.HomeItem
import com.oneuiclone.home.model.WorkspaceModel
import com.oneuiclone.home.motion.Easing
import com.oneuiclone.home.ui.BackgroundController
import com.oneuiclone.home.ui.CellLayout
import com.oneuiclone.home.ui.FolderIconView
import com.oneuiclone.home.ui.HomeSearchButton
import com.oneuiclone.home.ui.IconView
import com.oneuiclone.home.ui.LargeFolderIconView
import com.oneuiclone.home.ui.PageIndicator
import com.oneuiclone.home.ui.PagedView
import com.oneuiclone.home.ui.ScrimView
import com.oneuiclone.home.ui.SearchPill
import com.oneuiclone.home.ui.StateController
import kotlin.math.abs

private const val CLOSE_ZOOMS_WALLPAPER = false
private const val RECENTS_CLOSE_ZOOM = true
private const val OPEN_ZOOM_MS = 280L
private const val OPEN_SCALE = 0.85f
private const val OPEN_HOLD_SCALE = 0.925f
private const val CLOSE_MAX_MS = 1200L
private const val LAUNCH_WAIT_MS = 400L
private const val RETURN_WAIT_MS = 2000L
private const val KEY_CONTRACT_SEEN = "contract_seen"
private const val ZOOM_IN_STIFFNESS = 300f
private const val ZOOM_IN_DAMPING = 0.84f
private const val ZOOM_IN_MS = 650L
private const val ZOOM_IN_SCALE = 1.15f
private const val ZOOM_IN_CARD_SCALE = 1.2f
private const val AURA_MS = 420L
private val ANTICIPATE_HOME = android.view.animation.Interpolator { t ->
    val m = t * 410f
    val wind = 1f - (1f - (m / 110f).coerceIn(0f, 1f)).let { it * it * it }
    val go = Easing.STANDARD.getInterpolation(((m - 110f) / 300f).coerceIn(0f, 1f))
    (1f - (1f + 0.04f * wind + (0.8f - 1.04f) * go)) / 0.2f
}
private const val CARD_TIMEOUT_MS = 8000L

class Launcher(val activity: LauncherActivity) : AppRepository.Listener {

    companion object {
        @Volatile var live: java.lang.ref.WeakReference<Launcher>? = null
    }

    val iconFactory = IconFactory(activity)
    val prefs = com.oneuiclone.home.settings.Prefs(activity)
    private var prefsSnapshot = ""
    private fun snapshot() = prefs.sp.all.filterKeys { !com.oneuiclone.home.settings.Prefs.isLive(it) }.toSortedMap().toString()
    lateinit var dp: DeviceProfile; private set
    var repo: AppRepository? = null; private set
    var model: WorkspaceModel? = null; private set
    private val launcherApps = activity.getSystemService(LauncherApps::class.java)
    private val usage = com.oneuiclone.home.model.AppUsage(activity)
    private val quickOpenRecents = com.oneuiclone.home.model.QuickOpenRecents(activity)
    private val searchSources = com.oneuiclone.home.ui.SearchSources(activity, usage) { repo?.apps ?: emptyList() }

    val root = RootLayout()
    val scrim = ScrimView(activity)
    val wallpaper = com.oneuiclone.home.ui.WallpaperLayer(activity).apply { drivesLive = true }
    private val widgetGlass = com.oneuiclone.home.ui.WidgetGlass()
    private val folderGlass = com.oneuiclone.home.ui.WidgetGlass()
    private val pillGlass = com.oneuiclone.home.ui.WidgetGlass()       // Home pill
    private val drawerBarGlass = com.oneuiclone.home.ui.WidgetGlass()  // drawer search bar
    private val barGlass = com.oneuiclone.home.ui.WidgetGlass()        // App Search bar
    private val cardGlass = com.oneuiclone.home.ui.WidgetGlass()       // App Search cards
    private val sheetGlass = com.oneuiclone.home.ui.WidgetGlass()      // App drawer sheet
    private val glasses = listOf(widgetGlass, folderGlass, pillGlass, drawerBarGlass, barGlass, cardGlass)
    private val drawerBackdrop = com.oneuiclone.home.ui.LiveBackdrop { listOf(wallpaper, scrim, appsScroll) }
    private val searchBackdrop = com.oneuiclone.home.ui.LiveBackdrop { listOf(wallpaper, scrim) }
    private val sheetBackdrop = com.oneuiclone.home.ui.LiveBackdrop { listOf(wallpaper, scrim) }
    init {
        com.oneuiclone.home.ui.LiquidGlass.enabled = false
        val d = activity.resources.displayMetrics.density
        widgetGlass.radiusPx = prefs.blurWidget * d; folderGlass.radiusPx = prefs.blurFolder * d
        pillGlass.radiusPx = prefs.homePillBgBlur * d; drawerBarGlass.radiusPx = prefs.drawerSearchBarBlur * d
        barGlass.radiusPx = prefs.appSearchBarBlur * d; cardGlass.radiusPx = prefs.appSearchCardsBgBlur * d; sheetGlass.radiusPx = prefs.drawerSheetBlur * d
        wallpaper.onLoaded = {
            glasses.forEach { it.rebuild(wallpaper) }
            syncBlurredWallpaper()
            background.set(background.current, background.currentProgress)
        }
    }

    val background = BackgroundController(activity, scrim, wallpaper).apply {
        val d = activity.resources.displayMetrics.density
        appsRadiusPx = prefs.drawerBgBlur * d; searchRadiusPx = prefs.appSearchBgBlur * d
        appsDim = prefs.drawerBgOpacity / 100f
        searchDim = prefs.appSearchBgOpacity / 100f
        onChange = {
            if (::searchPill.isInitialized) searchPill.invalidate()
            search?.refreshBackdrop()
            if (::appsScroll.isInitialized) appsScroll.invalidate()   // redraw the sheet's blur when the background animates
        }
    }
    private fun applyGlassPrefs() {
        val d = activity.resources.displayMetrics.density
        com.oneuiclone.home.ui.LiquidGlass.enabled = false
        widgetGlass.setRadius(prefs.blurWidget * d, wallpaper); folderGlass.setRadius(prefs.blurFolder * d, wallpaper)
        pillGlass.setRadius(prefs.homePillBgBlur * d, wallpaper); drawerBarGlass.setRadius(prefs.drawerSearchBarBlur * d, wallpaper)
        barGlass.setRadius(prefs.appSearchBarBlur * d, wallpaper); cardGlass.setRadius(prefs.appSearchCardsBgBlur * d, wallpaper); sheetGlass.setRadius(prefs.drawerSheetBlur * d, wallpaper)
        if (::appsScroll.isInitialized) (appsScroll.background as? com.oneuiclone.home.ui.DrawerSheetDrawable)?.refreshColors()
        background.appsDim = prefs.drawerBgOpacity / 100f
        background.searchDim = prefs.appSearchBgOpacity / 100f
        background.appsRadiusPx = prefs.drawerBgBlur * d; background.searchRadiusPx = prefs.appSearchBgBlur * d
        background.refresh()
        glasses.forEach { g -> g.views.toList().forEach { it.invalidate() } }
        root.invalidate()
    }

    val homeLayer = FrameLayout(activity)
    val appsLayer = FrameLayout(activity)
    val overlay = FrameLayout(activity)

    lateinit var workspace: PagedView; private set
    lateinit var indicator: PageIndicator; private set
    lateinit var hotseat: CellLayout; private set
    lateinit var appsScroll: android.widget.ScrollView; private set
    private lateinit var appsGrid: CellLayout
    lateinit var searchPill: SearchPill; private set
    lateinit var homeSearch: HomeSearchButton; private set
    private lateinit var updatePill: HomeSearchButton
    private var updateAnnounced = false
    private lateinit var moreButton: SearchPill

    val state = StateController(homeLayer, appsLayer, background) { dp.swipeTransitionY }
    val folders = com.oneuiclone.home.ui.FolderController(
        activity, overlay, { dp }, background,
        { if (state.state == StateController.State.APPS) appsLayer else homeLayer },
        { v, app -> launch(v, app) },
        { folder, title -> folder.title = title; model?.save(); bindWorkspace() },
        { v, item, app ->
            attachItem(v, item, app, false) { folderSiblings(v) }
            applyBadge(v, listOf(app.key.component.packageName to app.user))
        },
        { folder -> addAppsToFolder(folder) },
        { folder -> pickFolderColor(folder) },
        { prefs.popupFolders },
        { rect, done -> snapshotBehind(rect, done) },
        { folder -> com.oneuiclone.home.widgets.WidgetStyles.folderTransparency(activity, folder.id) },
        onReorder = { folder, ordered ->
            model?.let { m ->
                val rest = m.appsInFolder(folder.id).filter { a -> ordered.none { it === a } }
                (ordered + rest).forEachIndexed { i, a -> a.rank = i }
                m.save(); bindWorkspace()
            }
        },
    )

    private fun addAppsToFolder(folder: HomeItem.Folder) {
        val m = model ?: return
        val r = repo ?: return
        val inFolder = m.appsInFolder(folder.id).map { it.key }.toSet()
        val apps = r.apps
        val checked = BooleanArray(apps.size) { apps[it].key in inFolder }
        android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Add apps")
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("Done") { _, _ ->
                m.items.removeAll { it is HomeItem.App && it.folderId == folder.id && it.container == Container.FOLDER &&
                    apps.indexOfFirst { a -> a.key == it.key }.let { i -> i >= 0 && !checked[i] } }
                apps.forEachIndexed { i, a ->
                    if (checked[i] && a.key !in inFolder) {
                        m.items += HomeItem.App(m.newId(), a.key, Container.FOLDER, 0, 0, 0, folder.id, m.appsInFolder(folder.id).size)
                    }
                }
                m.appsInFolder(folder.id).forEachIndexed { i, a -> a.rank = i }
                m.save(); bindWorkspace()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun folderSiblings(v: View): List<View> {
        val g = v.parent as? ViewGroup ?: return emptyList()
        return (0 until g.childCount).map { g.getChildAt(it) }
    }

    private var ripple = com.oneuiclone.home.ui.TouchRipple(1f)
    val drag = com.oneuiclone.home.ui.DragController(
        activity, overlay, { dp }, { model }, { workspace }, { hotseat }, background,
        { bindWorkspace() }, { findView(it) }, { widgets.delete(it.appWidgetId) }, { findItemView(it) },
        homeOnly = { prefs.homeOnly },
        tidyHome = { m, first ->
            if (prefs.homeOnly) repo?.let { fillHome(m, it.apps, first) }
            else if (prefs.fillEmptyCells) m.compactHome(dp.homeCols, dp.homeRows, first)
        },
        fillEmpty = { prefs.fillEmptyCells },
        openFolderForDrop = { view, folder, app, dragged ->
            val m = model; val r = repo
            if (m == null || r == null) null else {
                val its = m.appsInFolder(folder.id).filter { it !== dragged && r.get(it.key) != null }
                folders.openForDrop(view, folder, its.mapNotNull { r.get(it.key) } + app, its + dragged)
            }
        },
    )
    private var popup: com.oneuiclone.home.ui.QuickOptionPopup? = null
    private val shortcutLoader = com.oneuiclone.home.ui.ShortcutLoader(activity, (52 * activity.resources.displayMetrics.density).toInt())
    val widgets = com.oneuiclone.home.widgets.WidgetManager(activity).also {
        it.onLaunch = { appWidgetId, sender, send -> launchFromWidget(appWidgetId, sender, send) }
    }
    private var picker: com.oneuiclone.home.widgets.WidgetPicker? = null
    private var firstRun = false
    private var seeding = false
    val readyForSetup get() = bound && !seeding && widgets.pending == null
    val edit = com.oneuiclone.home.ui.EditModeController(
        activity, { dp }, { workspace }, { hotseat }, { indicator }, homeLayer, background,
        { model?.let { it.pageCount++; it.save() }; bindWorkspace() },
        listOf(
            "Wallpaper and style" to { onStyleButton() },
            "Icons" to { onIconsButton() },
            "Widgets" to { onWidgetsButton() },
            "Settings" to { onSettingsButton() },
        ),
        { deletePage(it) },
        {
            if (!::homeSearch.isInitialized) emptyList()
            else listOfNotNull(homeSearch, updatePill.takeIf { AppUpdate.available != null })
        },
    )

    private fun deletePage(page: Int) {
        val m = model ?: return
        if (m.pageCount <= 1) return
        val onPage = m.items.filter { it.container == Container.WORKSPACE && it.screen == page }
        val remove = {
            for (it in onPage) {
                m.items.remove(it)
                if (it is HomeItem.Folder) m.items.removeAll { a -> a is HomeItem.App && a.folderId == it.id }
                if (it is HomeItem.Widget) widgets.delete(it.appWidgetId)
            }
            m.items.filter { it.container == Container.WORKSPACE && it.screen > page }.forEach { it.screen-- }
            m.pageCount--
            m.save()
            bindWorkspace()
            workspace.post { workspace.snapToPage(page.coerceAtMost(m.pageCount - 1)) }
        }
        if (onPage.isEmpty()) { remove(); return }
        android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Remove page?")
            .setMessage("The apps, folders, and widgets on this page will be removed from the Home screen. Apps stay on the Apps screen.")
            .setPositiveButton("Remove") { _, _ -> remove() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private var dragArmed: View? = null
    private var armX = 0f
    private var armY = 0f
    private var lastLaunchedApp: AppInfo? = null
    private class WidgetLaunch(val appWidgetId: Int, val packages: Set<String>)
    private var lastWidgetLaunch: WidgetLaunch? = null
    private var insets = Rect()
    private var bound = false
    private var pendingReturnAnim = false
    private var contentAnim: ValueAnimator? = null
    private val wallpaperColorsListener = android.app.WallpaperManager.OnColorsChangedListener { _, _ ->
        wallpaper.reload(); if (::indicator.isInitialized) updateWallpaperColors(); syncBlurredWallpaper()
    }
    private val bubblesListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> if (bound) bindWorkspace() }

    init {
        live = java.lang.ref.WeakReference(this)
        com.oneuiclone.home.widgets.BubblesWidget.prefs(activity).registerOnSharedPreferenceChangeListener(bubblesListener)
        root.clipChildren = false
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = centerWallpaper()
            override fun onViewDetachedFromWindow(v: View) {}
        })
        root.addView(wallpaper, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(homeLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(appsLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        appsLayer.visibility = View.INVISIBLE
        appsLayer.alpha = 0f
        homeLayer.clipChildren = false
        appsLayer.clipChildren = false
        root.setOnApplyWindowInsetsListener { _, wi ->
            val sb = wi.getInsets(android.view.WindowInsets.Type.systemBars())
            val r = Rect(sb.left, sb.top, sb.right, sb.bottom)
            if (r != insets) {
                insets = r
                if (root.width > 0) setupProfile(root.width, root.height)
            }
            wi
        }
        android.app.WallpaperManager.getInstance(activity).addOnColorsChangedListener(
            wallpaperColorsListener, android.os.Handler(android.os.Looper.getMainLooper()))
        state.onStateChanged = { s ->
            applyTextMode()
            if (s == StateController.State.HOME) {
                appsScroll.scrollTo(0, 0)
                search?.takeIf { it.isOpen }?.let { it.visibility = View.INVISIBLE; it.close() }
                if (search == null) restoreAppsChrome()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // profile + binding
    // ---------------------------------------------------------------------------------------------

    private fun setupProfile(w: Int, h: Int) {
        prefsSnapshot = snapshot()
        val newDp = DeviceProfile(w, h, activity.resources.displayMetrics.density, insets,
            homeCols = prefs.homeCols, homeRows = prefs.homeRows, appsCols = prefs.appsCols, appsRows = prefs.appsRows,
            iconScale = prefs.iconScale / 100f, labelScale = prefs.labelScale / 100f)
        ripple = com.oneuiclone.home.ui.TouchRipple(newDp.samsungPxScale)
        val iconChanged = !::dp.isInitialized || dp.iconSizePx != newDp.iconSizePx
        dp = newDp
        buildViews()
        val r = repo
        if (r == null) {
            val nr = AppRepository(activity, iconFactory, dp.iconSizePx).also {
                it.themedIcons = prefs.themedIcons; it.themeAllApps = prefs.themeAllApps; it.plateAlpha = prefs.iconPlateAlpha
                it.darkIcons = prefs.darkIcons; it.minimalIcons = prefs.minimalIcons; it.iconLook = prefs.iconLook; it.iconMode = prefs.iconMode
                it.iconShape = prefs.iconShape
            }
            repo = nr
            model = WorkspaceModel(activity, nr)
            nr.addListener(this)
            nr.reload()
        } else {
            val themeChanged = r.themedIcons != prefs.themedIcons || r.themeAllApps != prefs.themeAllApps ||
                r.plateAlpha != prefs.iconPlateAlpha || r.darkIcons != prefs.darkIcons ||
                r.minimalIcons != prefs.minimalIcons || r.iconLook != prefs.iconLook || r.iconMode != prefs.iconMode ||
                r.iconShape != prefs.iconShape
            r.themedIcons = prefs.themedIcons
            r.iconShape = prefs.iconShape
            r.iconLook = prefs.iconLook
            r.iconMode = prefs.iconMode
            r.darkIcons = prefs.darkIcons
            r.minimalIcons = prefs.minimalIcons
            r.themeAllApps = prefs.themeAllApps
            r.plateAlpha = prefs.iconPlateAlpha
            model?.let { fillHome(it, r.apps) }
            if (iconChanged || themeChanged) r.invalidateIcons(dp.iconSizePx) else bindAll()
        }
    }

    private fun buildViews() {
        homeLayer.removeAllViews()
        appsLayer.removeAllViews()

        workspace = PagedView(activity)
        homeLayer.addView(workspace, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        indicator = PageIndicator(activity, dp.indicatorSlotPx * 0.7f, dp.indicatorDotPx).apply { opacity = 0.8f }
        homeLayer.addView(indicator, FrameLayout.LayoutParams(MATCH_PARENT, dp.indicatorRect.height()).apply {
            topMargin = dp.indicatorRect.top
        })
        homeSearch = HomeSearchButton(activity, dp, activity.getString(R.string.search_hint))
        homeSearch.setOnClickListener { openSearch(fromHome = true) }
        homeSearch.glassTransparency = com.oneuiclone.home.widgets.WidgetStyles.FOLDER_DEFAULT_TRANSPARENCY / 100f
        homeSearch.glass = pillGlass
        homeLayer.addView(homeSearch, FrameLayout.LayoutParams(homeSearch.pillWidth, homeSearch.pillHeight).apply {
            topMargin = dp.indicatorRect.top + (dp.indicatorRect.height() - homeSearch.pillHeight) / 2
        })
        updatePill = HomeSearchButton(activity, dp, "Update", icon = null)
        updatePill.setOnClickListener {
            if (state.state == StateController.State.HOME && !state.isTransitioning && !edit.isActive && !folders.isOpen)
                AppUpdate.install(activity)
        }
        updatePill.glassTransparency = homeSearch.glassTransparency
        updatePill.glass = pillGlass
        homeLayer.addView(updatePill, FrameLayout.LayoutParams(updatePill.pillWidth, updatePill.pillHeight).apply {
            topMargin = dp.indicatorRect.top + (dp.indicatorRect.height() - updatePill.pillHeight) / 2
        })
        indicator.leadingGapPx = dp.dp(6f)
        indicator.forceVisible = { edit.isActive }
        indicator.leadingPx = homeSearch.pillWidth.toFloat()
        indicator.onLeadingMoved = { x -> homeSearch.translationX = x; placeUpdatePill() }
        AppUpdate.onChanged = { syncUpdatePill() }
        syncUpdatePill()
        hotseat = CellLayout(activity, 5, 1)
        homeLayer.addView(hotseat, FrameLayout.LayoutParams(dp.hotseatRect.width(), dp.hotseatRect.height()).apply {
            leftMargin = dp.hotseatRect.left
            topMargin = dp.hotseatRect.top
        })
        workspace.listener = object : PagedView.Listener {
            override fun onPageChanged(page: Int) = indicator.setPage(page)
            override fun onScrollProgress(position: Float) { edit.onWorkspaceScroll(); onPagesScrolling(position) }
        }
        edit.reset()
        workspace.onEmptyLongPress = { _, _ ->
            if (!edit.isActive && state.state == StateController.State.HOME && !folders.isOpen) {
                workspace.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                edit.enter()
            }
        }
        workspace.onEmptyTap = { x, _ ->
            if (edit.isActive) {
                val page = when {
                    x < dp.widthPx * 0.12f -> workspace.currentPage - 1
                    x > dp.widthPx * 0.88f -> workspace.currentPage + 1
                    else -> workspace.currentPage
                }
                edit.exit(page.coerceAtLeast(0))
            }
        }

            val listTop = dp.appsListTop
            appsScroll = com.oneuiclone.home.ui.DrawerScrollView(activity, dp).apply {
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = false   // the progressive blur replaces the old fade
            clipToPadding = true
            setPadding(0, listTop, 0, 0)          // the list clips just under the sheet's drag handle
            background = com.oneuiclone.home.ui.DrawerSheetDrawable(activity, dp, prefs).also {   // rounded sheet + handle, fixed while the list scrolls
                it.host = this
                it.backdrop = sheetBackdrop
                it.glass = sheetGlass
            }
            trackSheet = true
            onTapOutside = { if (state.state == StateController.State.APPS && !state.isTransitioning) state.goTo(StateController.State.HOME) }
            overScrollMode = View.OVER_SCROLL_NEVER
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.clipBounds = Rect(0, 0, v.width, v.height) }
        }
        appsGrid = CellLayout(activity, dp.appsCols, 1)
        appsScroll.addView(appsGrid, FrameLayout.LayoutParams(MATCH_PARENT, dp.appsGrid.height() / dp.appsRows))
        appsLayer.addView(appsScroll, FrameLayout.LayoutParams(MATCH_PARENT, dp.heightPx))
        val scrollbar = com.oneuiclone.home.ui.DrawerScrollbar(activity, dp, appsScroll).apply {
            glassTransparency = homeSearch.glassTransparency
            glass = barGlass
        }
        appsLayer.addView(scrollbar, FrameLayout.LayoutParams(dp.dpi(28f), dp.heightPx - listTop, android.view.Gravity.END).apply {
            topMargin = listTop
        })
        appsScroll.setOnScrollChangeListener { _, _, _, _, _ -> scrollbar.onScrolled(); searchPill.invalidate() }
        // The drawer is a sheet with no search bar. The pill and the menu button are still created (the search page
        // morphs from / to them) but are not added to the drawer, so nothing is drawn or touched there.
        searchPill = SearchPill(activity, dp, activity.getString(R.string.search_hint))
        searchPill.glassTransparency = homeSearch.glassTransparency
        searchPill.glass = barGlass
        searchPill.backdrop = drawerBackdrop
        searchPill.setOnClickListener { openSearch() }
        moreButton = SearchPill(activity, dp, "", menuIcon = true).apply {
            glassTransparency = homeSearch.glassTransparency
            glass = barGlass
            setOnClickListener { showAppsMenu(this) }
        }

        bound = false
        if (repo?.apps?.isNotEmpty() == true) bindAll()
    }

    override fun onAppsChanged(apps: List<AppInfo>) {
        searchSources.refresh { refreshSuggested() }
        if (prefs.zoomInOpen || prefs.wallpaperOpen) splashLook.prefetch(apps.map { it.activityInfo })
        val m = model ?: return
        if (!bound && m.items.isEmpty()) {
            if (!m.load()) { m.createDefault(dp.homeCols, dp.homeRows); firstRun = true }
        }
        m.prune()
        fillHome(m, apps)
        if (knownApps.isNotEmpty() && prefs.addNewApps) {
            val onHome = m.items.mapNotNullTo(HashSet()) { (it as? HomeItem.App)?.key }
            apps.filter { it.key !in knownApps && it.key !in onHome }.forEach { addToHome(it, rebind = false) }
        }
        knownApps = apps.map { it.key }.toHashSet()
        reflowGrid(m)
        bindAll()
    }

    private var knownApps: Set<com.oneuiclone.home.model.AppKey> = emptySet()
    private val recentApps = com.oneuiclone.home.model.RecentApps(activity)

    private var dotsAnim: android.animation.ValueAnimator? = null
    private var dotsLevel = 0f
    private var lifted = true

    private fun applyDots(level: Float) {
        dotsLevel = level
        indicator.dotsShown = level
        if (!edit.isActive) homeSearch.alpha = 1f - level
    }

    private fun onPagesScrolling(@Suppress("UNUSED_PARAMETER") position: Float) {
        if (!::indicator.isInitialized || indicator.count <= 1 || edit.isActive) return
        if (workspace.isDragging) {
            lifted = false
            fadeDots(1f, 140L)
        } else if (!lifted) {
            lifted = true
            fadeDots(0f, 150L)
        }
    }

    private var dotsTarget = 0f

    private fun fadeDots(end: Float, ms: Long) {
        if (dotsTarget == end) return
        dotsTarget = end
        dotsAnim?.cancel()
        if (dotsLevel == end) return
        dotsAnim = android.animation.ValueAnimator.ofFloat(dotsLevel, end).apply {
            duration = ms
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { applyDots(it.animatedValue as Float) }
            start()
        }
    }

    private fun reflowGrid(m: WorkspaceModel) {
        var changed = false
        for (it in m.items.filter { it.container == Container.WORKSPACE }) {
            if (it.x + it.spanX > dp.homeCols || it.y + it.spanY > dp.homeRows) {
                if (it is HomeItem.Widget) { it.sx = it.sx.coerceAtMost(dp.homeCols); it.sy = it.sy.coerceAtMost(dp.homeRows) }
                var page = it.screen
                var pos = m.findSpace(page, it.spanX, it.spanY, dp.homeCols, dp.homeRows)
                while (pos == null) { page++; if (page >= m.pageCount) m.pageCount = page + 1; pos = m.findSpace(page, it.spanX, it.spanY, dp.homeCols, dp.homeRows) }
                it.screen = page; it.x = pos.first; it.y = pos.second
                changed = true
            }
        }
        if (changed) m.save()
    }

    private var wallpaperBright = false

    fun updateWallpaperColors() {
        val colors = try { android.app.WallpaperManager.getInstance(activity).getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM) } catch (_: Exception) { null }
        wallpaperBright = colors != null && (colors.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
        val dark = colors != null && (colors.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_THEME) != 0
        applyTextMode()
        indicator.darkWallpaper = dark
    }

    private fun applyTextMode() {
        val homeDark = wallpaperBright && state.state == StateController.State.HOME && !folders.isOpen && !edit.isActive
        fun walk(g: ViewGroup, darkLabels: Boolean) {
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                if (c is IconView) c.darkText = darkLabels else if (c is ViewGroup) walk(c, darkLabels)
            }
        }
        walk(homeLayer, wallpaperBright)
        walk(appsLayer, com.oneuiclone.home.ui.DrawerInk.darkLabels(activity))
        if (::appsScroll.isInitialized) (appsScroll.background as? com.oneuiclone.home.ui.DrawerSheetDrawable)?.refreshColors()
        indicator.darkDots = wallpaperBright
        @Suppress("DEPRECATION")
        val flags = activity.window.decorView.systemUiVisibility
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = if (homeDark)
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        else flags and (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
    }

    private fun applyBadge(v: IconView, pkgs: List<Pair<String, android.os.UserHandle>>) {
        v.badgeNumber = prefs.badgeNumbers
        v.badgeCount = if (!prefs.badges) 0 else pkgs.sumOf { BadgeService.count(it.first, it.second) }
    }

    fun updateBadges() {
        val r = repo ?: return
        val m = model
        fun walk(g: ViewGroup) {
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                if (c is IconView) {
                    when (val it = c.item) {
                        is HomeItem.App -> r.get(it.key)?.let { a -> applyBadge(c, listOf(a.key.component.packageName to a.user)) }
                        is HomeItem.Folder -> applyBadge(c, m?.appsInFolder(it.id)?.mapNotNull { f -> r.get(f.key) }?.map { a -> a.key.component.packageName to a.user } ?: emptyList())
                        else -> c.appInfo?.let { a -> applyBadge(c, listOf(a.key.component.packageName to a.user)) }
                    }
                } else if (c is ViewGroup) walk(c)
            }
        }
        walk(homeLayer); walk(appsLayer); walk(overlay)
    }

    private val badgeListener = { updateBadges() }

    private fun bindAll() {
        bindWorkspace()
        bindApps()
        bound = true
        updateWallpaperColors()
        if (firstRun) {
            firstRun = false
            val queue = ArrayDeque(model?.defaultWidgets.orEmpty().mapNotNull { w -> defaultProvider(w)?.let { w to it } })
            if (queue.isNotEmpty()) {
                seeding = true
                root.postDelayed({ addDefaultWidgets(queue) }, 600)
            }
        }
        activity.showSetupIfDue()
    }

    private fun defaultProvider(w: com.oneuiclone.home.model.WorkspaceModel.DefaultWidget): android.appwidget.AppWidgetProviderInfo? =
        widgets.providers()
            .filter { it.provider.packageName in w.packages && it.provider.className.contains(w.classHint, ignoreCase = true) }
            .sortedBy { w.packages.indexOf(it.provider.packageName) }
            .maxByOrNull { if (it.targetCellWidth == w.sx && it.targetCellHeight == w.sy) 1 else 0 }

    private fun addDefaultWidgets(queue: ArrayDeque<Pair<com.oneuiclone.home.model.WorkspaceModel.DefaultWidget, android.appwidget.AppWidgetProviderInfo>>) {
        val (w, info) = queue.removeFirstOrNull() ?: run {
            seeding = false
            activity.showSetupIfDue()
            return
        }
        widgets.add(info, onCancel = { addDefaultWidgets(queue) }) { id, i ->
            placeWidget(id, i, 0, w.x to w.y, w.sx to w.sy)
            addDefaultWidgets(queue)
        }
    }

    fun placeWidget(appWidgetId: Int, info: android.appwidget.AppWidgetProviderInfo, page: Int = workspace.currentPage,
                    at: Pair<Int, Int>? = null, span: Pair<Int, Int>? = null) {
        val m = model ?: return
        val (sx, sy) = span ?: widgets.spanFor(info, dp)
        var p = page
        var pos = at?.takeIf { !m.isOccupied(p, it.first, it.second, sx, sy) } ?: m.findSpace(p, sx, sy, dp.homeCols, dp.homeRows)
        while (pos == null) {
            p++
            if (p >= m.pageCount) m.pageCount = p + 1
            pos = m.findSpace(p, sx, sy, dp.homeCols, dp.homeRows)
        }
        m.items += HomeItem.Widget(m.newId(), appWidgetId, info.provider, Container.WORKSPACE, p, pos.first, pos.second, sx, sy)
        m.save()
        bindWorkspace()
        workspace.post { workspace.snapToPage(p) }
    }

    private fun pagePadding(cl: CellLayout, grid: Rect) {
        cl.setPadding(grid.left, grid.top, dp.widthPx - grid.right, dp.heightPx - grid.bottom)
    }

    fun bindWorkspace() {
        val m = model ?: return
        val r = repo ?: return
        val keep = workspace.currentPage
        workspace.removeAllViews()
        val pages = maxOf(m.pageCount, (m.items.filter { it.container == Container.WORKSPACE }.maxOfOrNull { it.screen } ?: 0) + 1)
        m.pageCount = pages
        for (p in 0 until pages) {
            val cl = CellLayout(activity, dp.homeCols, dp.homeRows)
            pagePadding(cl, dp.workspaceGrid)
            workspace.addView(cl, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        hotseat.removeAllViews()
        val dock = m.items.filter { it.container == Container.HOTSEAT }.sortedBy { it.x }
        hotseat.cols = dock.size.coerceIn(1, 7)
        dock.forEachIndexed { i, it -> it.x = i }
        for (item in m.items) {
            if (item is HomeItem.Widget) {
                if (item.container != Container.WORKSPACE) continue
                val quick = com.oneuiclone.home.widgets.QuickOpenWidget.kindOf(activity, item.provider)
                val hv = (if (item.provider == bubblesProvider) bubblesView(item)
                    else if (quick != null) quickOpenView(quick)
                    else if (item.provider == quickPairProvider) quickPairView(item.appWidgetId)
                    else widgets.createView(activity, item.appWidgetId, dp, item.sx, item.sy)) ?: continue
                val wc = com.oneuiclone.home.ui.WidgetContainer(activity, item, hv, dp.widgetPaddingPx).apply {
                    setPadding(dp.widgetPadXPx, dp.widgetPadYPx, dp.widgetPadXPx, dp.widgetPadYPx)
                }
                applyWidgetStyle(wc)
                wc.onLongPress = { showWidgetOptions(it) }
                wc.onDragStart = { c, x, y ->
                    if (prefs.lockLayout || edit.isActive) false else {
                        popup?.dismiss(); resizeFrame?.dismiss(); ripple.reset()
                        drag.startWidget(c, x, y); true
                    }
                }
                wc.onDragMove = { x, y -> drag.move(x, y) }
                wc.onDragEnd = { drag.drop() }
                (workspace.pageAt(item.screen) as? CellLayout)?.addCell(wc, item.x, item.y, item.sx, item.sy)
                continue
            }
            val v = createItemView(item, r) ?: continue
            when (item.container) {
                Container.WORKSPACE -> { v.showLabel = prefs.homeLabels; (workspace.pageAt(item.screen) as? CellLayout)?.addCell(v, item.x, item.y, item.spanX, item.spanY) }
                Container.HOTSEAT -> { v.showLabel = false; hotseat.addCell(v, item.x, 0) }
                Container.FOLDER -> Unit
            }
        }
        indicator.count = pages
        edit.refreshPages()
        workspace.post { workspace.setCurrentPageImmediately(keep.coerceAtMost(pages - 1)) }
        indicator.setPage(keep.coerceAtMost(pages - 1), false)
        folders.rebound { findView(it) as? com.oneuiclone.home.ui.FolderIconView }
        updateBadges()
    }

    private var resizeFrame: com.oneuiclone.home.ui.WidgetResizeFrame? = null

    private fun showWidgetOptions(wc: com.oneuiclone.home.ui.WidgetContainer) {
        popup?.dismiss()
        val m = model ?: return
        val info = widgets.appWidgetManager.getAppWidgetInfo(wc.item.appWidgetId)
        val bubbles = wc.item.provider == bubblesProvider
        val iconLike = bubbles || com.oneuiclone.home.widgets.QuickOpenWidget.kindOf(activity, wc.item.provider) != null ||
            wc.item.provider == quickPairProvider
        val opts = arrayListOf(
            com.oneuiclone.home.ui.QuickOption("Remove", com.oneuiclone.home.ui.QuickOption.REMOVE) {
                m.items.remove(wc.item); widgets.delete(wc.item.appWidgetId); m.save(); bindWorkspace()
            },
        )
        if (!iconLike) opts += com.oneuiclone.home.ui.QuickOption("Style", com.oneuiclone.home.ui.QuickOption.STYLE) { showWidgetStyle(wc) }
        (wc.getChildAt(0) as? com.oneuiclone.home.ui.QuickOpenPairView)?.let { pair ->
            opts += com.oneuiclone.home.ui.QuickOption(if (pair.centred) "Align with icons" else "Centre in row",
                com.oneuiclone.home.ui.QuickOption.STYLE) {
                pair.centred = !pair.centred
                com.oneuiclone.home.widgets.QuickOpenWidget.setPairCentred(activity, wc.item.appWidgetId, pair.centred)
            }
        }
        if (info?.configure != null) {
            opts += com.oneuiclone.home.ui.QuickOption(if (bubbles) "Configure" else "Settings", com.oneuiclone.home.ui.QuickOption.EDIT) {
                configureWidget(wc.item.appWidgetId)
            }
        }
        if (info != null && info.resizeMode != android.appwidget.AppWidgetProviderInfo.RESIZE_NONE && !prefs.lockLayout) {
            val minX = maxOf(1, kotlin.math.ceil(info.minResizeWidth.coerceAtLeast(1) / dp.cellWidth.toFloat()).toInt())
            val minY = maxOf(1, kotlin.math.ceil(info.minResizeHeight.coerceAtLeast(1) / dp.cellHeight.toFloat()).toInt())
            resizeFrame?.dismiss()
            val f = com.oneuiclone.home.ui.WidgetResizeFrame(activity, dp, m, wc.item,
                if ((info.resizeMode and android.appwidget.AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0) minX else wc.item.sx,
                if ((info.resizeMode and android.appwidget.AppWidgetProviderInfo.RESIZE_VERTICAL) != 0) minY else wc.item.sy,
                { animateWorkspaceRebind() }, { resizeFrame = null })
            overlay.addView(f, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            resizeFrame = f
        }
        val title = info?.loadLabel(activity.packageManager) ?: "Widget"
        val p = com.oneuiclone.home.ui.QuickOptionPopup(activity, dp, iconFactory, title, emptyList(), opts, { popup = null }, null,
            { rect, done -> snapshotBehind(rect, done) })
        p.passThroughOutside = true
        overlay.addView(p, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val loc = IntArray(2); wc.getLocationInWindow(loc)
        p.showFor(Rect(loc[0], loc[1], loc[0] + wc.width, loc[1] + wc.height), false)
        popup = p
    }

    private fun animateWorkspaceRebind() {
        val oldPositions = HashMap<Long, Pair<Float, Float>>()
        for (p in 0 until workspace.childCount) {
            val page = workspace.getChildAt(p) as? ViewGroup ?: continue
            for (i in 0 until page.childCount) {
                val v = page.getChildAt(i) as? IconView ?: continue
                val id = v.item?.id ?: continue
                oldPositions[id] = (p * workspace.width + v.left.toFloat()) to v.top.toFloat()
            }
        }
        bindWorkspace()
        workspace.post {
            for (p in 0 until workspace.childCount) {
                val page = workspace.getChildAt(p) as? ViewGroup ?: continue
                for (i in 0 until page.childCount) {
                    val v = page.getChildAt(i) as? IconView ?: continue
                    val id = v.item?.id ?: continue
                    val old = oldPositions[id] ?: continue
                    val newX = p * workspace.width + v.left.toFloat()
                    val newY = v.top.toFloat()
                    val dx = old.first - newX
                    val dy = old.second - newY
                    if (dx != 0f || dy != 0f) {
                        v.translationX = dx
                        v.translationY = dy
                        v.animate()
                            .translationX(0f)
                            .translationY(0f)
                            .setDuration(220L)
                            .setInterpolator(Easing.STANDARD)
                            .start()
                    }
                }
            }
        }
    }

    private fun configureWidget(appWidgetId: Int) {
        try { widgets.host.startAppWidgetConfigureActivityForResult(activity, appWidgetId, 0, 0, null) } catch (_: Exception) {}
    }

    private val bubblesProvider by lazy { com.oneuiclone.home.widgets.BubblesWidget.provider(activity) }
    private val bubbleLayers = HashMap<Int, List<com.oneuiclone.home.widgets.BubblesWidget.Layer>>()

    private fun bubblesView(item: HomeItem.Widget): IconView {
        val bw = com.oneuiclone.home.widgets.BubblesWidget
        val themed = appsScreenThemed
        val icons = bw.apps(activity, item.appWidgetId).mapNotNull { repo?.get(it)?.let { a -> if (themed) a.icon else a.drawerIcon } }.take(bw.MAX)
        val minimal = themed && prefs.themedIcons && prefs.minimalIcons
        val stencil = themed && prefs.themedIcons && prefs.iconLook == com.oneuiclone.home.icons.IconFactory.LOOK_STENCIL
        val layers = bubbleLayers[item.appWidgetId]?.takeIf { old -> old.size == icons.size &&
            old.indices.all { old[it].icon === icons[it] && old[it].cut == minimal && (old[it].hole != null) == stencil } }
            ?: bw.layers(icons, minimal, stencil).also { bubbleLayers[item.appWidgetId] = it }
        return com.oneuiclone.home.ui.BubblesIconView(activity, dp).apply {
            this.layers = layers
            if (layers.isEmpty()) icon = bw.emptyTile(dp.iconSizePx)
            if (prefs.iconGlass && (themed || layers.isEmpty())) plateGlass = widgetGlass
            label = activity.getString(R.string.bubbles_widget)
            contentDescription = label
            showLabel = prefs.homeLabels
            isLongClickable = false
            setOnClickListener { openBubbles(item.appWidgetId, this) }
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> ripple.start(homeSiblings(), ev.rawX, ev.rawY)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ripple.reverse()
                }
                false
            }
        }
    }

    private fun openBubbles(appWidgetId: Int, view: com.oneuiclone.home.ui.BubblesIconView) {
        val r = repo ?: return
        val bw = com.oneuiclone.home.widgets.BubblesWidget
        val apps = bw.apps(activity, appWidgetId).mapNotNull { r.get(it) }.filter { AppBubbles.supports(it) }.take(bw.MAX)
        if (apps.isEmpty()) { configureWidget(appWidgetId); return }
        if (bw.open(activity, apps) { i, _ -> view.appDone(i) }) view.startOpening(apps.size)
    }

    private var quickOpen: com.oneuiclone.home.ui.QuickOpenPanel? = null

    private fun quickOpenFrost() = com.oneuiclone.home.widgets.QuickOpenWidget.frost(activity, iconFactory.isNight(), prefs.iconPlateAlpha)

    private fun quickOpenView(kind: com.oneuiclone.home.widgets.QuickOpenWidget.Kind): IconView =
        IconView(activity, dp).apply {
            icon = com.oneuiclone.home.widgets.QuickOpenWidget.tile(kind, dp.iconSizePx, quickOpenFrost())
            plateGlass = widgetGlass
            label = com.oneuiclone.home.widgets.QuickOpenWidget.label(activity, kind)
            contentDescription = label
            showLabel = false
            isLongClickable = false
            val icon = this
            setOnClickListener { openQuickOpen(kind, QuickOpenSource(
                bounds = { Rect().also { icon.iconBoundsOnScreen(it) } },
                look = { icon.displayedIcon() ?: com.oneuiclone.home.widgets.QuickOpenWidget.tile(kind, dp.iconSizePx, quickOpenFrost()) },
                hole = { Rect().also { icon.iconBoundsOnScreen(it) }.apply { inset(-width() / 8, -height() / 8) } },
                hide = { icon.iconVisible = !it })) }
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> ripple.start(homeSiblings(), ev.rawX, ev.rawY)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ripple.reverse()
                }
                false
            }
        }

    private val quickPairProvider by lazy { com.oneuiclone.home.widgets.QuickOpenWidget.pairProvider(activity) }

    private fun quickPairView(appWidgetId: Int): com.oneuiclone.home.ui.QuickOpenPairView =
        com.oneuiclone.home.ui.QuickOpenPairView(activity, dp, quickOpenFrost()).apply {
            glass = widgetGlass
            centred = com.oneuiclone.home.widgets.QuickOpenWidget.pairCentred(activity, appWidgetId)
            label = ""
            contentDescription = activity.getString(R.string.quick_pair_widget)
            onPress = { ev, down -> if (down) ripple.start(homeSiblings(), ev.rawX, ev.rawY) else ripple.reverse() }
            onTap = { kind -> openQuickOpen(kind, null) }
        }

    private class QuickOpenSource(val bounds: () -> Rect, val look: () -> android.graphics.Bitmap,
                                  val hole: () -> Rect, val hide: (Boolean) -> Unit)

    private fun openQuickOpen(kind: com.oneuiclone.home.widgets.QuickOpenWidget.Kind, src: QuickOpenSource?) {
        val r = repo ?: return
        val qo = com.oneuiclone.home.widgets.QuickOpenWidget
        if (quickOpen != null || quickOpenPending || search != null || edit.isActive || folders.isOpen || drag.isDragging || popup != null) return
        if (kind != com.oneuiclone.home.widgets.QuickOpenWidget.Kind.SPLIT && qo.needsBubbleSetup(activity)) return
        if (src == null) { showQuickOpen(kind, r, null, null); return }
        quickOpenPending = true
        val icon = src.hole()
        val loc = IntArray(2); root.getLocationInWindow(loc)
        icon.offset(-loc[0], -loc[1])
        snapshotBehind(Rect(0, 0, root.width, root.height), { shot ->
            quickOpenPending = false
            if (quickOpen == null && search == null && !edit.isActive && !folders.isOpen) showQuickOpen(kind, r, shot, src)
        }, holes = listOf(icon))
        root.postDelayed({ quickOpenPending = false }, 500)
    }

    private var quickOpenPending = false

    private fun showQuickOpen(kind: com.oneuiclone.home.widgets.QuickOpenWidget.Kind, r: com.oneuiclone.home.model.AppRepository,
                              shot: android.graphics.Bitmap?, src: QuickOpenSource?) {
        val qo = com.oneuiclone.home.widgets.QuickOpenWidget
        lateinit var p: com.oneuiclone.home.ui.QuickOpenPanel
        p = com.oneuiclone.home.ui.QuickOpenPanel(activity, kind == com.oneuiclone.home.widgets.QuickOpenWidget.Kind.SPLIT, qo.candidates(r.apps), searchSources.snapshot.scores,
            backdrop = shot,
            origin = src?.let { com.oneuiclone.home.ui.QuickOpenPanel.Origin(it.bounds(), it.look()) },
            glass = widgetGlass,
            frost = quickOpenFrost(),
            onBubble = { app ->
                usage.launched(app.key)
                p.close()
                qo.openBubble(activity, app) { a -> launcherApps.startMainActivity(a.key.component, a.user, null, null) }
            },
            onSplit = { top, bottom ->
                usage.launched(top.key); usage.launched(bottom.key)
                quickOpenRecents.split(top, bottom)
                com.oneuiclone.home.widgets.SplitLaunch.fromHome(activity, top, bottom)
                root.postDelayed({ p.close(animate = false) }, 2000)
            },
            onClosed = { _ ->
                overlay.removeView(p)
                if (quickOpen === p) quickOpen = null
                src?.hide(false)
                if (src == null) quickOpenHome(1f, 0f)
            },
            home = if (src == null) { scale, gone -> quickOpenHome(scale, gone) } else null,
            multi = kind == com.oneuiclone.home.widgets.QuickOpenWidget.Kind.MULTI,
            onBubbles = { apps ->
                apps.forEach { usage.launched(it.key) }
                p.close()
                com.oneuiclone.home.widgets.BubblesWidget.open(activity, apps)
            },
            iconOf = if (appsScreenThemed) { a -> a.icon } else { a -> a.drawerIcon },
            recent = if (kind == com.oneuiclone.home.widgets.QuickOpenWidget.Kind.SPLIT) quickOpenRecents.splits() else quickOpenRecents.bubbles())
        overlay.addView(p, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        quickOpen = p
        p.requestApplyInsets()
        p.open()
        src?.hide(true)
    }

    private fun quickOpenHome(scale: Float, gone: Float) {
        val away = scale != 1f || gone > 0f
        if (away && homeLayer.layerType != View.LAYER_TYPE_HARDWARE) { centrePivot(homeLayer); homeLayer.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        homeLayer.scaleX = scale; homeLayer.scaleY = scale
        homeLayer.alpha = 1f - gone
        background.set(if (away) BackgroundController.Kind.QUICK_OPEN else BackgroundController.Kind.NONE, gone)
        if (!away) homeLayer.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    private fun applyWidgetStyle(wc: com.oneuiclone.home.ui.WidgetContainer) {
        val t = com.oneuiclone.home.widgets.WidgetStyles.transparency(activity, wc.item.appWidgetId, wc.item.provider.packageName)
        val hv = wc.getChildAt(0) as? com.oneuiclone.home.widgets.WidgetManager.RoundedWidgetView ?: return
        hv.backgroundOpacity = if (t == null) 1f else 1f - t / 100f
        hv.glass = if (t == null) null else widgetGlass
    }

    private fun showWidgetStyle(wc: com.oneuiclone.home.ui.WidgetContainer) {
        resizeFrame?.dismiss()
        saveWidgetDiagnostics(wc)
        val id = wc.item.appWidgetId
        val styles = com.oneuiclone.home.widgets.WidgetStyles
        val current = { widgetContainer(id) ?: wc }
        showStyleSheet("Widget style", wc, styles.transparency(activity, id, wc.item.provider.packageName), { t -> styles.set(activity, id, t) },
            { applyWidgetStyle(current()) }) { body ->
            val pkg = wc.item.provider.packageName
            body.addView(android.widget.TextView(activity).apply { text = "Theme"; setPadding(0, dp.dpi(16f), 0, dp.dpi(4f)) })
            val group = android.widget.RadioGroup(activity).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
            val choices = listOf(styles.THEME_AUTO to "Auto", styles.THEME_LIGHT to "Light", styles.THEME_DARK to "Dark")
            val ids = choices.map { (theme, label) ->
                val b = android.widget.RadioButton(activity).apply { text = label; this.id = View.generateViewId() }
                group.addView(b, android.widget.RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                theme to b.id
            }
            group.check(ids.first { it.first == styles.theme(activity, id, pkg) }.second)
            group.setOnCheckedChangeListener { _, checked ->
                styles.setTheme(activity, id, ids.first { it.second == checked }.first)
                bindWorkspace()
            }
            body.addView(group)
        }
    }

    private fun widgetContainer(appWidgetId: Int): com.oneuiclone.home.ui.WidgetContainer? {
        for (p in 0 until workspace.childCount) {
            val page = workspace.getChildAt(p) as? ViewGroup ?: continue
            for (i in 0 until page.childCount) {
                val c = page.getChildAt(i) as? com.oneuiclone.home.ui.WidgetContainer ?: continue
                if (c.item.appWidgetId == appWidgetId) return c
            }
        }
        return null
    }

    private fun applyFolderStyle(icon: FolderIconView, folder: HomeItem.Folder) {
        val t = com.oneuiclone.home.widgets.WidgetStyles.folderTransparency(activity, folder.id)
        icon.fillOpacity = if (t == null) 1f else 1f - t / 100f
        icon.glass = if (t == null) null else folderGlass
    }

    private fun showStyleSheet(title: String, item: View, saved: Int?, save: (Int?) -> Unit, apply: () -> Unit,
                               extra: ((android.widget.LinearLayout) -> Unit)? = null) {
        var transparency = saved ?: com.oneuiclone.home.widgets.WidgetStyles.DEFAULT_TRANSPARENCY
        val pad = dp.dpi(24f)
        val body = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, dp.dpi(8f), pad, 0)
        }
        val value = android.widget.TextView(activity).apply { text = "Transparency  $transparency%"; setPadding(0, dp.dpi(16f), 0, 0) }
        val slider = android.widget.SeekBar(activity).apply {
            max = 100; progress = transparency
            val thumb = dp.dpi(12f)
            setPadding(thumb, dp.dpi(12f), thumb, dp.dpi(8f))
        }
        val blurOn = { slider.isEnabled = true; value.alpha = 1f; slider.alpha = 1f }
        val blurOff = { slider.isEnabled = false; value.alpha = 0.4f; slider.alpha = 0.4f }
        val group = android.widget.RadioGroup(activity).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        val original = android.widget.RadioButton(activity).apply { text = "Original"; this.id = View.generateViewId() }
        val blur = android.widget.RadioButton(activity).apply { text = "Blur"; this.id = View.generateViewId() }
        group.addView(original, android.widget.RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        group.addView(blur, android.widget.RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        group.check(if (saved == null) original.id else blur.id)
        if (group.checkedRadioButtonId == original.id) blurOff() else blurOn()
        group.setOnCheckedChangeListener { _, checked ->
            if (checked == blur.id) { save(transparency); blurOn() } else { save(null); blurOff() }
            apply()
        }
        slider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                transparency = p
                value.text = "Transparency  $p%"
                if (fromUser) { save(p); apply() }
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })
        body.addView(group)
        body.addView(value)
        body.addView(slider)
        extra?.invoke(body)
        val dialog = android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setView(body)
            .setPositiveButton("Done", null)
            .create()
        val loc = IntArray(2)
        item.getLocationOnScreen(loc)
        val low = loc[1] + item.height / 2 > root.height / 2
        dialog.window?.apply {
            setGravity(if (low) android.view.Gravity.TOP else android.view.Gravity.BOTTOM)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.show()
    }

    private fun saveWidgetDiagnostics(wc: com.oneuiclone.home.ui.WidgetContainer) {
        val hv = wc.getChildAt(0) as? com.oneuiclone.home.widgets.WidgetManager.RoundedWidgetView ?: return
        val text = try { hv.describe() } catch (e: Exception) { "describe failed: $e" }
        val pkg = wc.item.provider.packageName
        backdropHandler.post {
            try {
                val resolver = activity.contentResolver
                val downloads = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val name = "OneUIHome-widget-$pkg.txt"
                resolver.delete(downloads, "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name))
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                }
                resolver.insert(downloads, values)?.let { uri -> resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
            } catch (e: Exception) {
                android.util.Log.w("OneUiHome", "widget diagnostics failed", e)
            }
        }
    }

    private fun createItemView(item: HomeItem, r: AppRepository): IconView? = when (item) {
        is HomeItem.App -> {
            val app = r.get(item.key) ?: return null
            IconView(activity, dp).apply {
                this.item = item
                icon = app.icon
                if (prefs.iconGlass) plateGlass = widgetGlass
                label = app.label
                contentDescription = app.label
                setOnClickListener { launch(this, app) }
                attachItem(this, item, app, false) { homeSiblings() }
            }
        }
        is HomeItem.Widget -> null
        is HomeItem.Folder -> if (item.large) {
            val content = model!!.appsInFolder(item.id).mapNotNull { r.get(it.key) }
            com.oneuiclone.home.ui.LargeFolderIconView(activity, dp).apply {
                this.item = item
                label = item.title
                apps = content.map { it.icon to it }
                if (item.color != 0) bgColor = item.color
                applyFolderStyle(this, item)
                setOnClickListener {
                    val i = hitApp(lastTouchX, lastTouchY)
                    if (i >= 0) launch(this, content[i]) else folders.open(this, item, content, model!!.appsInFolder(item.id).filter { r.get(it.key) != null })
                }
                attachItem(this, item, null, false) { homeSiblings() }
            }
        } else {
            val content = model!!.appsInFolder(item.id).mapNotNull { r.get(it.key) }
            FolderIconView(activity, dp).apply {
                this.item = item
                label = item.title
                previews = content.map { it.icon }
                if (item.color != 0) bgColor = item.color
                applyFolderStyle(this, item)
                setOnClickListener {
                    val its = model!!.appsInFolder(item.id).filter { r.get(it.key) != null }
                    folders.open(this, item, its.mapNotNull { r.get(it.key) }, its)
                }
                attachItem(this, item, null, false) { homeSiblings() }
            }
        }
    }

    private fun homeSiblings(): List<View> {
        val out = ArrayList<View>()
        (workspace.pageAt(workspace.currentPage) as? ViewGroup)?.let { g -> for (i in 0 until g.childCount) out += g.getChildAt(i) }
        for (i in 0 until hotseat.childCount) out += hotseat.getChildAt(i)
        return out
    }

    private fun appsSiblings(): List<View> {
        val top = appsScroll.scrollY - appsGrid.top
        val bottom = top + appsScroll.height
        return (0 until appsGrid.childCount).map { appsGrid.getChildAt(it) }.filter { it is IconView && it.bottom > top && it.top < bottom }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachRipple(v: View, siblings: () -> List<View>) = attachItem(v as IconView, null, null, false, siblings = siblings)

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachItem(v: IconView, item: HomeItem?, app: AppInfo?, fromApps: Boolean, forFolder: Boolean = false, siblings: () -> List<View>) {
        val noDragToHome = fromApps && !forFolder && prefs.homeOnly
        v.setOnLongClickListener {
            ripple.reverse()
            v.parent?.requestDisallowInterceptTouchEvent(true)
            showQuickOptions(v, item, app, fromApps, forFolder)
            dragArmed = v
            true
        }
        v.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    armX = ev.rawX; armY = ev.rawY
                    app?.let { shortcutLoader.prefetch(it) }
                    ripple.start(siblings(), ev.rawX, ev.rawY)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (drag.isDragging) { drag.move(ev.rawX, ev.rawY); return@setOnTouchListener true }
                    if (dragArmed === v && !noDragToHome && !(prefs.lockLayout && !fromApps) && kotlin.math.hypot(ev.rawX - armX, ev.rawY - armY) > slop) {
                        popup?.dismiss()
                        ripple.reset()
                        if (fromApps) state.setImmediate(StateController.State.HOME)
                        drag.start(v, item, app, fromApps, ev.rawX, ev.rawY,
                            if (item?.container == Container.FOLDER) folders.beginDrag(v) else null)
                        return@setOnTouchListener true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    ripple.reverse()
                    val wasDragging = drag.isDragging
                    if (wasDragging) { drag.drop(); folders.finishDetached() }
                    dragArmed = null
                    if (wasDragging) return@setOnTouchListener true
                }
            }
            false
        }
    }

    private fun showQuickOptions(v: IconView, item: HomeItem?, app: AppInfo?, fromApps: Boolean, forFolder: Boolean = false) {
        popup?.dismiss()
        val m = model ?: return
        val opts = ArrayList<com.oneuiclone.home.ui.QuickOption>()
        if (item !is HomeItem.Folder || fromApps) opts += com.oneuiclone.home.ui.QuickOption("Select", com.oneuiclone.home.ui.QuickOption.SELECT) {}
        if (fromApps && app != null) {
            if (forFolder || !prefs.homeOnly) opts += com.oneuiclone.home.ui.QuickOption("Add to Home", com.oneuiclone.home.ui.QuickOption.ADD) { addToHome(app) }
        } else if (item != null && !prefs.lockLayout && (!prefs.homeOnly || item is HomeItem.Folder)) {
            opts += com.oneuiclone.home.ui.QuickOption("Remove", com.oneuiclone.home.ui.QuickOption.REMOVE) {
                m.items.remove(item)
                if (item is HomeItem.Folder) m.items.removeAll { it is HomeItem.App && it.folderId == item.id }
                m.save(); bindWorkspace()
            }
        }
        if (item is HomeItem.Folder && !fromApps) {
            opts += com.oneuiclone.home.ui.QuickOption(if (item.large) "Shrink" else "Enlarge", com.oneuiclone.home.ui.QuickOption.EDIT) {
                toggleLargeFolder(item)
            }
            opts += com.oneuiclone.home.ui.QuickOption("Rename", com.oneuiclone.home.ui.QuickOption.RENAME) { renameFolder(item) }
            opts += com.oneuiclone.home.ui.QuickOption("Add", com.oneuiclone.home.ui.QuickOption.ADD) { addAppsToFolder(item) }
            opts += com.oneuiclone.home.ui.QuickOption("Color", com.oneuiclone.home.ui.QuickOption.SELECT) { pickFolderColor(item) }
            opts += com.oneuiclone.home.ui.QuickOption("Style", com.oneuiclone.home.ui.QuickOption.STYLE) {
                showStyleSheet("Folder style", v, com.oneuiclone.home.widgets.WidgetStyles.folderTransparency(activity, item.id),
                    { t -> com.oneuiclone.home.widgets.WidgetStyles.setFolder(activity, item.id, t) },
                    { (v as? FolderIconView)?.let { applyFolderStyle(it, item) } })
            }
        }
        if (app != null && prefs.appBubbles && AppBubbles.supports(app)) {
            opts += com.oneuiclone.home.ui.QuickOption("Bubble", com.oneuiclone.home.ui.QuickOption.BUBBLE) { bubble(v, app) }
        }
        if (app != null) opts += com.oneuiclone.home.ui.QuickOption("Rename", com.oneuiclone.home.ui.QuickOption.RENAME) { renameApp(app) }
        if (app != null && (app.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) {
            opts += com.oneuiclone.home.ui.QuickOption("Uninstall", com.oneuiclone.home.ui.QuickOption.UNINSTALL) {
                activity.startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.fromParts("package", app.key.component.packageName, null)))
            }
        }
        val title = when { app != null -> app.label; item is HomeItem.Folder -> item.title; else -> "" }
        val p = com.oneuiclone.home.ui.QuickOptionPopup(activity, dp, iconFactory, title,
            app?.let { shortcutLoader.get(it) } ?: emptyList(), opts, { popup = null },
            app?.let { a -> { launcherApps.startAppDetailsActivity(a.key.component, a.user, null, null) } },
            { rect, done -> snapshotBehind(rect, done) })
        overlay.addView(p, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val r = Rect(); v.iconBoundsOnScreen(r)
        p.showFor(r, v.parent === hotseat)
        popup = p
    }

    private val backdropThread = android.os.HandlerThread("backdrop").apply { start() }
    private val backdropHandler = android.os.Handler(backdropThread.looper)

    fun snapshotBehind(rect: Rect, done: (android.graphics.Bitmap) -> Unit, scale: Float = 0.25f, holes: List<Rect> = emptyList()) {
        if (rect.isEmpty) return
        val r = Rect(rect).apply { intersect(0, 0, root.width, root.height) }
        if (r.isEmpty) return
        val w = maxOf(1, (r.width() * scale).toInt())
        val h = maxOf(1, (r.height() * scale).toInt())
        val shot = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(activity.window, r, shot, { result ->
                if (result != android.view.PixelCopy.SUCCESS) return@request
                val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(out)
                c.save()
                c.scale(w / r.width().toFloat(), h / r.height().toFloat())
                c.translate(-r.left.toFloat(), -r.top.toFloat())
                if (!wallpaper.drawSnapshot(c)) c.drawColor(0xFF2A2A30.toInt())
                c.restore()
                c.drawBitmap(shot, 0f, 0f, null)
                for (hole in holes) {
                    c.save()
                    c.scale(w / r.width().toFloat(), h / r.height().toFloat())
                    c.translate(-r.left.toFloat(), -r.top.toFloat())
                    c.clipRect(hole)
                    if (!wallpaper.drawSnapshot(c)) c.drawColor(0xFF2A2A30.toInt())
                    c.restore()
                }
                root.post { done(out) }
            }, backdropHandler)
        } catch (_: Exception) {}
    }

    private fun toggleLargeFolder(f: HomeItem.Folder) {
        val m = model ?: return
        if (f.large) { f.large = false; m.save(); bindWorkspace(); return }
        if (f.container != Container.WORKSPACE) { Toast.makeText(activity, "Only folders on the Home screen can be enlarged.", Toast.LENGTH_SHORT).show(); return }
        val cands = listOf(f.x to f.y, f.x - 1 to f.y, f.x to f.y - 1, f.x - 1 to f.y - 1)
            .filter { (x, y) -> x >= 0 && y >= 0 && x + 2 <= dp.homeCols && y + 2 <= dp.homeRows }
        val spot = cands.firstOrNull { (x, y) -> !m.isOccupied(f.screen, x, y, 2, 2, f) }
        if (spot == null) { Toast.makeText(activity, "Not enough space to enlarge this folder.", Toast.LENGTH_SHORT).show(); return }
        f.x = spot.first; f.y = spot.second; f.large = true
        m.save(); bindWorkspace()
    }

    private fun renameApp(app: AppInfo) {
        val r = repo ?: return
        val field = android.widget.EditText(activity).apply {
            setText(app.label)
            hint = app.systemLabel
            setSelectAllOnFocus(true)
            isSingleLine = true
        }
        val box = FrameLayout(activity).apply {
            setPadding(dp.dpi(24f), dp.dpi(8f), dp.dpi(24f), 0)
            addView(field)
        }
        val builder = android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Rename app")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = field.text.toString().trim()
                r.names.set(app.key, if (name.isEmpty() || name == app.systemLabel) null else name)
                r.reload()
            }
            .setNegativeButton("Cancel", null)
        if (r.names.get(app.key) != null) builder.setNeutralButton("Reset") { _, _ -> r.names.set(app.key, null); r.reload() }
        val dialog = builder.create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        field.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        field.setOnEditorActionListener { _, _, _ -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.performClick(); true }
        dialog.show()
        field.requestFocus()
    }

    private fun renameFolder(f: HomeItem.Folder) {
        val field = android.widget.EditText(activity).apply {
            setText(f.title)
            setSelectAllOnFocus(true)
            isSingleLine = true
        }
        val box = FrameLayout(activity).apply {
            setPadding(dp.dpi(24f), dp.dpi(8f), dp.dpi(24f), 0)
            addView(field)
        }
        val dialog = android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Rename folder")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                f.title = field.text.toString().trim()
                model?.save(); bindWorkspace()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        field.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        field.setOnEditorActionListener { _, _, _ -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.performClick(); true }
        dialog.show()
        field.requestFocus()
    }

    private fun pickFolderColor(f: HomeItem.Folder) {
        val names = arrayOf("White", "Blue", "Green", "Orange", "Yellow")
        android.app.AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Folder color")
            .setItems(names) { _, i ->
                f.color = if (i == 0) 0 else com.oneuiclone.home.ui.FolderIconView.PALETTE[i]
                model?.save(); bindWorkspace()
            }.show()
    }

    private fun addToHome(app: AppInfo, rebind: Boolean = true, save: Boolean = true) {
        val m = model ?: return
        for (page in 0 until m.pageCount) for (y in 0 until dp.homeRows) for (x in 0 until dp.homeCols) {
            if (!m.isOccupied(page, x, y)) {
                m.items += HomeItem.App(m.newId(), app.key, Container.WORKSPACE, page, x, y)
                if (save) m.save()
                if (rebind) bindWorkspace()
                return
            }
        }
        m.pageCount++
        m.items += HomeItem.App(m.newId(), app.key, Container.WORKSPACE, m.pageCount - 1, 0, 0)
        if (save) m.save()
        if (rebind) bindWorkspace()
    }

    private fun fillHome(m: WorkspaceModel, apps: List<AppInfo>, first: HomeItem? = null) {
        if (!prefs.homeOnly) {
            if (m.hasBackup() && m.restoreBackup()) m.prune()
            return
        }
        if (!m.hasBackup()) {
            m.backup()
            m.items.removeAll { it.container == Container.WORKSPACE && it.screen >= 1 }
            m.pageCount = 2
        }

        val pm = activity.packageManager
        fun isSystem(app: AppInfo) =
            (app.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        fun installTime(app: AppInfo) = try {
            pm.getPackageInfo(app.key.component.packageName, 0).firstInstallTime
        } catch (_: Exception) { 0L }
        fun isExtraGoogleApp(app: AppInfo): Boolean {
            val pkg = app.key.component.packageName
            return pkg.startsWith("com.google.android.") ||
                pkg == "com.android.vending" ||
                pkg == "com.android.chrome"
        }

        val onHome = m.items.mapNotNullTo(HashSet()) { (it as? HomeItem.App)?.key }
        if (m.pageCount < 2) m.pageCount = 2

        val extraGoogle = apps.filter { it.key !in onHome && isExtraGoogleApp(it) }
            .sortedBy { it.label.toString().lowercase() }
        if (extraGoogle.isNotEmpty()) {
            var googleFolder = m.items.filterIsInstance<HomeItem.Folder>()
                .firstOrNull { it.title.equals("Google", ignoreCase = true) }
            if (googleFolder == null) {
                val spot = m.findSpace(1, 1, 1, dp.homeCols, dp.homeRows) ?: (0 to 0)
                googleFolder = HomeItem.Folder(m.newId(), "Google", Container.WORKSPACE, 1, spot.first, spot.second)
                m.items += googleFolder
            }
            var rank = m.appsInFolder(googleFolder.id).size
            for (gApp in extraGoogle) {
                m.items += HomeItem.App(m.newId(), gApp.key, Container.FOLDER, 0, 0, 0, googleFolder.id, rank++)
                onHome += gApp.key
            }
        }

        val sortedRemaining = apps.filter { it.key !in onHome }.sortedWith(
            compareBy<AppInfo> { !isSystem(it) }
                .thenBy { if (isSystem(it)) 0L else installTime(it) }
                .thenBy { it.label.toString().lowercase() }
        )

        for (app in sortedRemaining) {
            var placed = false
            for (page in 1 until m.pageCount) {
                val pos = m.findSpace(page, 1, 1, dp.homeCols, dp.homeRows)
                if (pos != null) {
                    m.items += HomeItem.App(m.newId(), app.key, Container.WORKSPACE, page, pos.first, pos.second)
                    placed = true
                    break
                }
            }
            if (!placed) {
                val newPage = m.pageCount++
                m.items += HomeItem.App(m.newId(), app.key, Container.WORKSPACE, newPage, 0, 0)
            }
        }

        val moved = prefs.fillEmptyCells && m.compactHome(dp.homeCols, dp.homeRows, first)
        if (extraGoogle.isNotEmpty() || sortedRemaining.isNotEmpty() || moved) m.save()
    }

    private val appsScreenThemed get() = (prefs.themedIcons || prefs.darkIcons) && prefs.themeAppsScreen

    private fun setAppsIcon(v: IconView, app: AppInfo) {
        val themed = appsScreenThemed
        v.icon = if (themed) app.icon else app.drawerIcon
        if (themed && prefs.iconGlass) v.plateGlass = widgetGlass
    }

    private var shownSuggested: List<com.oneuiclone.home.model.AppKey> = emptyList()

    private fun suggestedApps(apps: List<AppInfo>): List<AppInfo> {
        val scores = searchSources.snapshot.scores
        return apps.filter { (scores[it.key] ?: 0.0) > 0.0 }.sortedByDescending { scores[it.key] ?: 0.0 }.take(dp.appsCols)
    }

    private fun refreshSuggested() {
        val r = repo ?: return
        if (::dp.isInitialized && suggestedApps(r.apps).map { it.key } != shownSuggested) bindApps()
    }

    fun bindApps() {
        val r = repo ?: return
        val apps = r.apps
        val suggested = suggestedApps(apps)
        shownSuggested = suggested.map { it.key }
        val lead = if (suggested.isNotEmpty()) 1 else 0
        val rows = maxOf(1, (apps.size + dp.appsCols - 1) / dp.appsCols) + lead
        appsGrid.removeAllViews()
        appsGrid.rows = rows
        appsGrid.gapAfterRow = if (lead == 1) 0 else -1
        appsGrid.gapPx = if (lead == 1) dp.dpi(com.oneuiclone.home.ui.SuggestedRowBackground.GAP_DP) else 0
        val topGap = dp.appsGrid.top - dp.appsListTop
        val bottomGap = insets.bottom + dp.dpi(16f) + dp.appsBottomPad
        appsGrid.setPadding(dp.appsGrid.left, topGap, dp.widthPx - dp.appsGrid.right, bottomGap)
        appsGrid.layoutParams = appsGrid.layoutParams.apply { height = topGap + rows * (dp.appsGrid.height() / dp.appsRows) + appsGrid.gapPx + bottomGap }
        val sheetDark = com.oneuiclone.home.ui.DrawerInk.darkLabels(activity)
        fun appIcon(app: AppInfo) = IconView(activity, dp).apply {
            darkText = sheetDark                                  // the sheet is light by day: dark labels
            setAppsIcon(this, app)
            label = app.label
            contentDescription = app.label
            appInfo = app
            showLabel = prefs.appsLabels
            setOnClickListener { launch(this, app) }
            attachItem(this, null, app, true) { appsSiblings() }
        }
        if (lead == 1) {
            appsGrid.addCell(com.oneuiclone.home.ui.SuggestedRowBackground(activity, dp), 0, 0, dp.appsCols, 1)
            suggested.forEachIndexed { i, app -> appsGrid.addCell(appIcon(app), i, 0) }
        }
        apps.forEachIndexed { i, app -> appsGrid.addCell(appIcon(app), i % dp.appsCols, i / dp.appsCols + lead) }
        updateBadges()
    }

    // ---------------------------------------------------------------------------------------------
    // launching
    // ---------------------------------------------------------------------------------------------

    fun launch(v: IconView, app: AppInfo) {
        if (AppBubbles.inSingleTaskBubble(activity, app)) {
            AnimLog.log("tap ${app.label}: single-task app in a bubble, opening the bubble")
            usage.launched(app.key)
            AppBubbles.show(activity, app) { start(v, app) }
            return
        }
        start(v, app)
    }

    private fun start(v: IconView, app: AppInfo) {
        val bounds = Rect()
        v.iconBoundsOnScreen(bounds)
        usage.launched(app.key)
        open(app.label.toString(), app, "Can't open ${app.label}", v) { launcherApps.startMainActivity(app.key.component, app.user, bounds, null) }
    }

    private fun bubble(v: IconView, app: AppInfo) {
        if (!AppBubbles.ready(activity)) {
            AppBubbles.missing(activity)?.let { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() }
            AppBubbles.setUp(activity)
            return
        }
        usage.launched(app.key)
        AppBubbles.show(activity, app) {
            Toast.makeText(activity, "${app.label} can't open in a bubble", Toast.LENGTH_SHORT).show()
            launch(v, app)
        }
    }

    private fun launchFrom(v: View, start: (Rect, android.os.Bundle?) -> Unit) {
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val bounds = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        open("shortcut/contact", null, "Can't open that") { start(bounds, null) }
    }

    private fun launchFromWidget(appWidgetId: Int, sender: android.content.IntentSender, send: () -> Unit): Boolean {
        val activityLaunch = opensActivity(sender)
        AnimLog.log("widget click from ${sender.creatorPackage}: " + if (activityLaunch) "opens an app" else "not an app launch, left alone")
        if (!activityLaunch) return false
        val provider = widgets.appWidgetManager.getAppWidgetInfo(appWidgetId)?.provider?.packageName
        val packages = setOfNotNull(sender.creatorPackage, provider)
        val launch = WidgetLaunch(appWidgetId, packages)
        val widget = (widgetTarget(launch)?.view as? com.oneuiclone.home.ui.WidgetContainer)?.getChildAt(0)
        val owner = repo?.apps?.let { apps ->
            apps.firstOrNull { it.key.component.packageName == sender.creatorPackage && it.user == sender.creatorUserHandle }
                ?: apps.firstOrNull { it.key.component.packageName == provider }
        }
        open("widget ${sender.creatorPackage}", null, "Can't open that", widget = widget, widgetApp = owner) { send() }
        lastWidgetLaunch = launch
        return true
    }

    private fun opensActivity(sender: android.content.IntentSender): Boolean {
        val p = android.os.Parcel.obtain()
        return try {
            sender.writeToParcel(p, 0)
            p.setDataPosition(0)
            android.app.PendingIntent.readPendingIntentOrNullFromParcel(p)?.isActivity == true
        } catch (_: Exception) {
            false
        } finally {
            p.recycle()
        }
    }

    private val closeState = activity.getSharedPreferences("close_state", android.content.Context.MODE_PRIVATE)
    private var contractSeen = closeState.getBoolean(KEY_CONTRACT_SEEN, false)
    private var homeCovered = false
    private var returnWaiting = false
    private val returnWaitCap = Runnable { homeShown("wait cap") }

    private fun homeShown(source: String) {
        homeCovered = false
        if (!returnWaiting) return
        returnWaiting = false
        root.removeCallbacks(returnWaitCap)
        AnimLog.log("Home shown ($source): return animation")
        playReturnAnimation(shown = true)
    }

    private var closingUntil = 0L
    private var pendingLaunch: (() -> Unit)? = null
    private val launchFallback = Runnable { closeFinished("wait cap") }

    private fun markClosing(why: String) {
        closingUntil = android.os.SystemClock.uptimeMillis() + CLOSE_MAX_MS
        AnimLog.log("closing window opened ($why)")
    }

    fun closeFinished(source: String = "enter complete") {
        AnimLog.log("close finished ($source) heldLaunch=${pendingLaunch != null}")
        closingUntil = 0L
        root.removeCallbacks(launchFallback)
        if (source != "wait cap") homeShown("enter complete")
        val start = pendingLaunch ?: return
        pendingLaunch = null
        start()
    }

    private fun open(label: String, app: AppInfo?, failure: String, icon: IconView? = null,
                     widget: View? = null, widgetApp: AppInfo? = null, start: () -> Unit) {
        val tapAt = android.os.SystemClock.uptimeMillis()
        val closingFor = closingUntil - tapAt
        val hold = closingFor > 0
        val look = when {
            !prefs.zoomInOpen && !prefs.wallpaperOpen -> null
            icon != null && app != null -> timedLook(app)
            widget != null && widgetApp != null -> widgetLook(widgetApp).also {
                AnimLog.log("zoom in: widget card colour #%06X from %s".format(it.color and 0xFFFFFF, it.source))
            }
            else -> null
        }
        val effect: com.oneuiclone.home.ui.LiveWallpaper.Kind? = null
        val zoomIn = look != null && !look.translucent && effect == null
        AnimLog.log("tap $label " + (if (hold) "during close (window ${closingFor}ms left) -> hold" else "-> start now") +
            if (zoomIn) " (zoom in)" else "")
        launched(app) {
            if (zoomIn && icon != null) zoomIn(icon, app!!, look!!.color)
            else if (zoomIn) zoomInFromWidget(widget!!, widgetApp!!, look!!.color)
            else zoomOpen(if (hold) OPEN_HOLD_SCALE else OPEN_SCALE, if (hold) LAUNCH_WAIT_MS else OPEN_ZOOM_MS)
        }
        logFrames()
        val go = {
            try {
                AnimLog.log("start $label after ${android.os.SystemClock.uptimeMillis() - tapAt}ms")
                if (hold && !zoomIn) zoomOpen(OPEN_SCALE, OPEN_ZOOM_MS)
                start()
                if (launchCard != null) { root.removeCallbacks(cardTimeout); root.postDelayed(cardTimeout, CARD_TIMEOUT_MS) }
                if (effectContent != null) { root.removeCallbacks(effectTimeout); root.postDelayed(effectTimeout, CARD_TIMEOUT_MS) }
            } catch (e: Exception) {
                AnimLog.log("start $label failed: $e")
                Toast.makeText(activity, failure, Toast.LENGTH_SHORT).show()
                cancelOpen()
            }
        }
        if (hold) {
            pendingLaunch = go
            root.removeCallbacks(launchFallback)
            root.postDelayed(launchFallback, LAUNCH_WAIT_MS)
        } else {
            pendingLaunch = null
            go()
        }
    }

    private fun logFrames() {
        val t0 = android.os.SystemClock.uptimeMillis()
        val sb = StringBuilder("frames after tap (ms:scale):")
        var maxGap = 0L
        var prev = t0
        android.view.Choreographer.getInstance().postFrameCallback(object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val now = android.os.SystemClock.uptimeMillis()
                maxGap = maxOf(maxGap, now - prev); prev = now
                sb.append(' ').append(now - t0).append(':').append("%.2f".format(openContent().scaleX)).append(if (folders.isOpen) "F" else "")
                effectContent?.let { sb.append("/a %.2f".format(it.alpha)) }
                launchCard?.let { sb.append("/card %.2f".format(it.progress)) }
                if (now - t0 < 800) android.view.Choreographer.getInstance().postFrameCallback(this)
                else AnimLog.log("$sb | longest frame gap ${maxGap}ms")
            }
        })
    }

    /** What the open zoom scales: an open search or folder, otherwise the current layer. */
    private fun openContent(): View = when {
        search?.isOpen == true -> search!!
        folders.isOpen -> overlay
        state.state == StateController.State.APPS -> appsLayer
        else -> homeLayer
    }

    private var launchCard: com.oneuiclone.home.ui.LaunchCard? = null
    private var cardIcon: IconView? = null
    private var cardAnim: ValueAnimator? = null
    private val splashLook = com.oneuiclone.home.icons.SplashLook(activity)
    private val cardTimeout = Runnable {
        AnimLog.log("zoom in: Home still not covered ${CARD_TIMEOUT_MS}ms after the start, card faded")
        dropLaunchCard(fade = true)
    }

    private fun timedLook(app: AppInfo): com.oneuiclone.home.icons.SplashLook.Look {
        val cached = splashLook.isCached(app.activityInfo)
        val t0 = System.nanoTime()
        val look = splashLook.look(app.activityInfo)
        AnimLog.log("zoom in: splash colour #%06X from %s%s (%s, %.1fms)".format(look.color and 0xFFFFFF, look.source,
            if (look.translucent) ", translucent -> smooth" else "", if (cached) "cached" else "resolved now", (System.nanoTime() - t0) / 1e6))
        return look
    }

    private fun widgetLook(app: AppInfo): com.oneuiclone.home.icons.SplashLook.Look {
        val night = iconFactory.isNight()
        fun fits(c: Int) = !night || android.graphics.Color.luminance(c) < 0.35f
        val surface = splashLook.surfaceLook(app.activityInfo)
        if (fits(surface.color)) return surface
        val splash = splashLook.look(app.activityInfo)
        if (fits(splash.color)) return com.oneuiclone.home.icons.SplashLook.Look(splash.color, false,
            "${splash.source} (${surface.source} doesn't fit the mode)")
        val neutral = activity.getColor(if (night) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_10)
        return com.oneuiclone.home.icons.SplashLook.Look(neutral, false, "neutral surface (neither ${surface.source} nor ${splash.source} fits the mode)")
    }

    private fun zoomIn(v: IconView, app: AppInfo, splashColor: Int) {
        val icon = Rect()
        v.iconBoundsOnScreen(icon)
        val glass = v.plateGlass != null && v.icon === app.icon && app.icon !== app.drawerIcon
        val minimal = prefs.themedIcons && prefs.minimalIcons && v.icon === app.icon && app.icon !== app.drawerIcon
        val dark = prefs.darkIcons && v.icon === app.icon && app.icon !== app.drawerIcon
        val look = prefs.themedIcons && prefs.iconLook.isNotEmpty() && v.icon === app.icon && app.icon !== app.drawerIcon
        val fromAlpha = when {
            minimal -> com.oneuiclone.home.ui.LaunchCard.MINIMAL_FROM_ALPHA
            look && prefs.iconLook == com.oneuiclone.home.icons.IconFactory.LOOK_STENCIL -> com.oneuiclone.home.ui.LaunchCard.STENCIL_FROM_ALPHA
            look && prefs.iconLook in com.oneuiclone.home.icons.IconFactory.PLAYFUL -> com.oneuiclone.home.ui.LaunchCard.PLAYFUL_FROM_ALPHA
            glass -> com.oneuiclone.home.ui.LaunchCard.GLASS_FROM_ALPHA
            else -> 1f
        }
        zoomIn(icon, v.displayedIcon(), app, splashColor, v, fromAlpha,
            when {
                dark -> com.oneuiclone.home.icons.IconFactory.DARK_PLATE
                look -> com.oneuiclone.home.icons.IconFactory.lookPlate(prefs.iconLook, com.oneuiclone.home.icons.IconFactory.lookNight(prefs.iconMode, iconFactory.isNight()))
                else -> null
            })
    }

    private fun zoomInFromWidget(widget: View, app: AppInfo, splashColor: Int) {
        val loc = IntArray(2)
        widget.getLocationOnScreen(loc)
        val from = Rect(loc[0], loc[1], loc[0] + (widget.width * homeLayer.scaleX).toInt(), loc[1] + (widget.height * homeLayer.scaleY).toInt())
        zoomIn(from, null, app, splashColor, null)
    }

    private fun zoomIn(from: Rect, iconBitmap: android.graphics.Bitmap?, app: AppInfo, splashColor: Int, v: IconView?,
                       fromAlpha: Float = 1f, fromColor: Int? = null) {
        dropLaunchCard(fade = false)
        val content = openContent()
        val icon = Rect(from)
        val rootLoc = IntArray(2)
        root.getLocationInWindow(rootLoc)
        icon.offset(-rootLoc[0], -rootLoc[1])
        val pivot = if (content.scaleX == 1f) {
            val loc = IntArray(2)
            content.getLocationInWindow(loc)
            android.graphics.PointF(icon.exactCenterX() + rootLoc[0] - loc[0], icon.exactCenterY() + rootLoc[1] - loc[1])
        } else null
        val card = com.oneuiclone.home.ui.LaunchCard(activity).apply {
            this.from.set(icon)
            color = splashColor
            this.fromAlpha = fromAlpha
            this.fromColor = fromColor
            this.icon = iconBitmap
            if (v != null) {
                startRadius = icon.width() * com.oneuiclone.home.icons.IconShape.corner(com.oneuiclone.home.ui.LaunchCard.ICON_RADIUS)
                if (!com.oneuiclone.home.icons.IconShape.isSquircle) fromShape = com.oneuiclone.home.icons.IconShape.current
            } else {
                startRadius = 26f * activity.resources.displayMetrics.density
                fromIconPx = dp.iconSizePx.toFloat()
                fadeIn = true
            }
            endRadius = root.rootWindowInsets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: 0f
            endScale = ZOOM_IN_CARD_SCALE
            splashIconPx = 160f * activity.resources.displayMetrics.density
        }
        AnimLog.log("zoom in: card from ${if (v == null) "widget " else ""}${icon.toShortString()}" + if (fromAlpha < 1f) ", glass or minimal icon: from $fromAlpha opacity" else "")
        root.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        launchCard = card
        v?.iconVisible = false
        cardIcon = v
        val loadAt = android.os.SystemClock.uptimeMillis()
        if (v != null) splashLook.loadIcon(app.activityInfo) { d ->
            AnimLog.log("zoom in: full icon after ${android.os.SystemClock.uptimeMillis() - loadAt}ms" + if (launchCard === card) "" else " (card gone)")
            if (launchCard === card) card.splashIcon = d
        }

        val spring = Easing.spring(ZOOM_IN_STIFFNESS, ZOOM_IN_DAMPING, ZOOM_IN_MS)
        animateContentScale(content, ZOOM_IN_SCALE, ZOOM_IN_MS, spring, pivot)
        animateWallpaperZoom(wallpaperOpenZoom, ZOOM_IN_MS, spring)
        var barsSet = false
        cardAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ZOOM_IN_MS
            interpolator = spring
            addUpdateListener {
                card.progress = it.animatedValue as Float
                if (!barsSet && card.progress > 0.6f) { barsSet = true; setLightBars(card.isLight) }
            }
            start()
        }
    }

    @Suppress("DEPRECATION")
    private fun setLightBars(light: Boolean) {
        val bars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val decor = activity.window.decorView
        decor.systemUiVisibility = if (light) decor.systemUiVisibility or bars else decor.systemUiVisibility and bars.inv()
    }

    private fun dropLaunchCard(fade: Boolean) {
        root.removeCallbacks(cardTimeout)
        val card = launchCard ?: return
        launchCard = null
        cardAnim?.cancel()
        cardAnim = null
        cardIcon?.iconVisible = true
        cardIcon = null
        if (fade) card.animate().alpha(0f).setDuration(150).withEndAction { root.removeView(card) }.start()
        else root.removeView(card)
        applyTextMode()
    }

    private var effectContent: View? = null
    private var effectIcon: IconView? = null
    private var effectBarsLight = false
    private val effectTimeout = Runnable {
        AnimLog.log("open effect: Home still not covered ${CARD_TIMEOUT_MS}ms after the start, Home brought back")
        endEffect(animate = true)
    }
    private val effectBars = Runnable { if (effectContent != null) setLightBars(effectBarsLight) }

    private fun endEffect(animate: Boolean) {
        root.removeCallbacks(effectTimeout)
        com.oneuiclone.home.ui.LiveWallpaper.endEffect()
        root.removeCallbacks(effectBars)
        effectIcon?.iconVisible = true
        effectIcon = null
        val content = effectContent ?: return
        effectContent = null
        applyTextMode()
        content.animate().cancel()
        if (animate) content.animate().alpha(1f).setStartDelay(0).setDuration(OPEN_ZOOM_MS).start()
        else content.alpha = 1f
    }

    private fun zoomOpen(scale: Float, duration: Long) {
        val content = openContent()
        val fraction = (1f - scale) / (1f - OPEN_SCALE)
        animateContentScale(content, scale, duration, Easing.CUBIC_OUT)
        animateWallpaperZoom(1f + (wallpaperOpenZoom - 1f) * fraction, duration, Easing.CUBIC_OUT)
    }

    private fun cancelOpen() {
        pendingReturnAnim = false
        lastLaunchedApp = null
        dropLaunchCard(fade = true)
        endEffect(animate = true)
        animateContentScale(openContent(), 1f, OPEN_ZOOM_MS, Easing.CUBIC_OUT)
        animateWallpaperZoom(1f, OPEN_ZOOM_MS, Easing.CUBIC_OUT)
    }

    private fun launched(app: AppInfo?, zoom: () -> Unit) {
        finishContract()
        returnRipple?.let { root.removeCallbacks(it); returnRipple = null }
        returnWaiting = false
        root.removeCallbacks(returnWaitCap)
        zoom()
        lastWidgetLaunch = null
        pendingReturnAnim = true
        lastLaunchedApp = app
    }

    private var zoomAnim: ValueAnimator? = null
    private var returnRipple: Runnable? = null
    private var wallpaperZoom = 1f
    private val wallpaperOpenZoom = 1.06f

    private fun animateWallpaperZoom(to: Float, duration: Long, interp: android.view.animation.Interpolator) {
        val from = wallpaperZoom
        zoomAnim?.cancel()
        wallpaperZoom = from
        wallpaper.setZoom(from)
        zoomAnim = ValueAnimator.ofFloat(wallpaperZoom, to).apply {
            this.duration = duration
            interpolator = interp
            addUpdateListener { wallpaperZoom = it.animatedValue as Float; wallpaper.setZoom(wallpaperZoom) }
            start()
        }
    }

    private fun animateContentScale(v: View, to: Float, duration: Long, interp: android.view.animation.Interpolator,
                                    pivot: android.graphics.PointF? = null) {
        contentAnim?.cancel()
        if (pivot != null) { v.pivotX = pivot.x; v.pivotY = pivot.y }
        else if (v.scaleX == 1f) centrePivot(v)
        val from = v.scaleX
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        contentAnim = ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            interpolator = interp
            addUpdateListener { val s = it.animatedValue as Float; v.scaleX = s; v.scaleY = s }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    v.setLayerType(View.LAYER_TYPE_NONE, null)
                    if (to == 1f) centrePivot(v)
                }
                override fun onAnimationCancel(animation: android.animation.Animator) = v.setLayerType(View.LAYER_TYPE_NONE, null)
            })
            start()
        }
    }

    private fun centrePivot(v: View) {
        v.pivotX = v.width / 2f
        v.pivotY = v.height / 2f
    }

    fun onPause() {
        leftHomeAt = System.currentTimeMillis()
        BadgeService.removeListener(badgeListener)
    }

    /** Closes and removes the search overlay immediately (used after an app launch covers or finishes). */
    private fun dismissSearchImmediately() {
        val s = search ?: return
        s.animate().cancel()
        s.scaleX = 1f; s.scaleY = 1f; centrePivot(s)
        s.visibility = View.INVISIBLE
        s.close()
        searchPill.animate().cancel(); searchPill.alpha = 1f
        homeSearch.visibility = View.VISIBLE
        if (state.state == StateController.State.APPS) state.setImmediate(StateController.State.HOME)
        restoreAppsChrome()
    }

    fun onStop() {
        com.oneuiclone.home.widgets.SplitLaunch.homeStopped()
        searchSources.refresh { refreshSuggested() }
        quickOpen?.close(animate = false)
        if (pendingLaunch != null) AnimLog.log("stopped with a held launch: dropped")
        pendingLaunch = null
        root.removeCallbacks(launchFallback)
        if (launchCard != null) AnimLog.log("zoom in: Home covered, card removed")
        dropLaunchCard(fade = false)
        effectIcon?.let { it.iconVisible = true; effectIcon = null }
        effectContent?.let {
            root.removeCallbacks(effectTimeout)
            root.removeCallbacks(effectBars)
            it.animate().cancel()
            it.alpha = 1f
            effectContent = null
            applyTextMode()
        }
        if (RECENTS_CLOSE_ZOOM && !pendingReturnAnim && !edit.isActive && search?.isOpen != true && awake() &&
            !activity.isChangingConfigurations) {
            pendingReturnAnim = true
            lastLaunchedApp = null
            lastWidgetLaunch = null
            AnimLog.log("stopped without a launch: return animation armed")
        }
        if (pendingReturnAnim) {
            contentAnim?.cancel()
            dismissSearchImmediately()
            if (folders.isOpen) folders.close(animate = false)
            overlay.scaleX = 1f; overlay.scaleY = 1f; centrePivot(overlay)
            if (state.state == StateController.State.APPS) state.setImmediate(StateController.State.HOME)
            homeLayer.alpha = 1f
            homeLayer.scaleX = 1f; homeLayer.scaleY = 1f; centrePivot(homeLayer)
            appsLayer.alpha = 1f
            appsLayer.scaleX = 1f; appsLayer.scaleY = 1f; centrePivot(appsLayer)
            zoomAnim?.cancel()
            wallpaperZoom = 1f
            wallpaper.setZoom(1f)
            if (com.oneuiclone.home.ui.LiveWallpaper.drives) com.oneuiclone.home.ui.LiveWallpaper.park(1f)
            homeCovered = true
        }
    }

    private fun awake(): Boolean =
        activity.getSystemService(android.os.PowerManager::class.java).isInteractive &&
            !activity.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked

    private fun placeUpdatePill() {
        updatePill.translationX = homeSearch.translationX - dp.dp(6f) - updatePill.layoutParams.width
    }

    private fun syncUpdatePill() {
        if (!::updatePill.isInitialized) return
        val p = updatePill
        if (AppUpdate.available == null) { p.animate().cancel(); p.visibility = View.GONE; return }
        p.label = when (AppUpdate.phase) {
            AppUpdate.Phase.Ready -> "Update"
            AppUpdate.Phase.Downloading -> "${(AppUpdate.progress * 100).toInt()}%"
            AppUpdate.Phase.Installing -> "Installing"
            AppUpdate.Phase.Failed -> "Retry"
        }
        p.progress = if (AppUpdate.phase == AppUpdate.Phase.Downloading) AppUpdate.progress else -1f
        val width = if (AppUpdate.phase == AppUpdate.Phase.Downloading) maxOf(p.pillWidth, p.pillWidth("100%")) else p.pillWidth
        if (p.layoutParams.width != width) { p.layoutParams = p.layoutParams.apply { this.width = width } }
        placeUpdatePill()
        if (p.visibility == View.VISIBLE) return
        p.visibility = if (edit.isActive) View.INVISIBLE else View.VISIBLE
        if (!updateAnnounced) {
            updateAnnounced = true
            p.alpha = 0f; p.scaleX = 0.6f; p.scaleY = 0.6f
            p.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()
        }
    }

    fun onResume() {
        applyGlassPrefs()
        searchSources.refresh { refreshSuggested() }
        AppUpdate.onResumed(activity)
        dropLaunchCard(fade = true)
        endEffect(animate = true)
        if (pendingReturnAnim && search != null) dismissSearchImmediately()
        if (prefs.zoomInOpen || prefs.wallpaperOpen) repo?.let { r -> splashLook.prefetch(r.apps.map { it.activityInfo }) }
        centerWallpaper()
        BadgeService.addListener(badgeListener)
        updateBadges()
        Thread { quickOpenRecents.scan(usage) }.start()
        if (::dp.isInitialized && prefsSnapshot.isNotEmpty() && snapshot() != prefsSnapshot) {
            edit.reset()
            setupProfile(root.width, root.height)
        }
       wallpaper.reloadIfChanged()
        if (!wallpaper.available && (android.os.Environment.isExternalStorageManager() || com.oneuiclone.home.ui.WallpaperLayer.hasOwnCopy(activity))) {
            wallpaper.reload()
        }
        if (state.state == StateController.State.HOME && !state.isTransitioning && search == null && !edit.isActive && !folders.isOpen && quickOpen == null) {
            background.set(BackgroundController.Kind.NONE, 0f)
        }
        if (!pendingReturnAnim && com.oneuiclone.home.ui.LiveWallpaper.drives) com.oneuiclone.home.ui.LiveWallpaper.setZoom(wallpaperZoom)
        playReturnAnimation()
    }

    private fun syncBlurredWallpaper() {
        // Keep the user's existing device wallpaper untouched when Launcher You becomes default Home
    }

    private fun centerWallpaper() {
        val token = root.windowToken ?: return
        android.app.WallpaperManager.getInstance(activity).setWallpaperOffsets(token, 0.5f, 0.5f)
    }

    private fun settleWallpaper() {
        wallpaperZoom = 1f
        wallpaper.holdCopy = false
        wallpaper.setZoom(1f)
        wallpaper.copyAlpha = 1f
    }

    private fun playReturnAnimation(landing: CloseTarget? = lastLaunchedApp?.let { closeTargetFor(it.key.component, it.user) }
        ?: lastWidgetLaunch?.let { widgetTarget(it) }, shown: Boolean = false) {
        if (!pendingReturnAnim) return
        if (homeCovered && !contractSeen) {
            if (!returnWaiting) {
                returnWaiting = true
                root.removeCallbacks(returnWaitCap)
                root.postDelayed(returnWaitCap, RETURN_WAIT_MS)
                AnimLog.log("return animation waits for Home to show")
            }
            return
        }
        pendingReturnAnim = false
        if (!shown) markClosing("return animation")
        AnimLog.log("return animation to ${(landing?.view as? com.oneuiclone.home.ui.WidgetContainer)?.let { "widget " + it.item.provider.packageName } ?: landing?.view?.contentDescription ?: "screen centre"}")
        contentAnim?.cancel()
        if (folders.isOpen) {
            overlay.scaleX = 1f; overlay.scaleY = 1f; centrePivot(overlay)
        } else {
            homeLayer.alpha = 1f
            homeLayer.scaleX = 1f; homeLayer.scaleY = 1f; centrePivot(homeLayer)
        }
        centerWallpaper()
        zoomAnim?.cancel()
        settleWallpaper()
        if (com.oneuiclone.home.ui.LiveWallpaper.drives) {
            com.oneuiclone.home.ui.LiveWallpaper.setZoom(1f)
        }
        returnRipple?.let { root.removeCallbacks(it); returnRipple = null }
        lastLaunchedApp = null
    }

    // ---------------------------------------------------------------------------------------------
    // intents / back
    // ---------------------------------------------------------------------------------------------

    fun onNewIntent(intent: Intent, alreadyOnHome: Boolean) {
        android.util.Log.i("OneUiHome", "onNewIntent extras=" + intent.extras?.keySet() + " onHome=" + alreadyOnHome)
        if (!alreadyOnHome) {
            markClosing("home intent")
            edit.exitNow()
        }
        val contract = GestureNavContract.fromIntent(intent)
        if (contract != null) {
            android.util.Log.i("OneUiHome", "gesture contract for " + contract.componentName)
            if (!contractSeen) {
                contractSeen = true
                closeState.edit().putBoolean(KEY_CONTRACT_SEEN, true).apply()
            }
            playReturnAnimation(handleContract(contract))
        } else {
            playReturnAnimation()
        }
        popup?.dismiss()
        resizeFrame?.dismiss()
        drag.cancel()
        quickOpen?.close(animate = alreadyOnHome)
        if (alreadyOnHome) {
            when {
                search?.isOpen == true -> closeSearch(toHome = true)
                edit.isActive -> edit.exit()
                folders.isOpen -> folders.close()
                state.state == StateController.State.APPS -> state.goTo(StateController.State.HOME)
                workspace.currentPage != 0 -> workspace.snapToPage(0)
            }
        }
    }

    private var iconSurface: android.view.SurfaceView? = null
    private var contractTarget: CloseTarget? = null
    private var leftHomeAt = 0L

    private inner class CloseTarget(val view: View, val slot: Int = -1) {
        private val widget get() = (view as? com.oneuiclone.home.ui.WidgetContainer)?.getChildAt(0)
        val isWidget get() = widget != null

        fun part(): Rect? = if (slot < 0) null else Rect().also { (view as LargeFolderIconView).slotRect(slot, it) }

        fun rect(): Rect {
            widget?.let { w -> return Rect(0, 0, w.width, w.height).also { homeLayer.offsetDescendantRectToMyCoords(w, it) } }
            val icon = view as IconView
            val r = part() ?: Rect(0, 0, icon.iconSizePx(), icon.iconSizePx())
            r.offset(icon.iconLeft(), icon.iconTop())
            homeLayer.offsetDescendantRectToMyCoords(icon, r)
            return r
        }

        fun boundsOnScreen(out: Rect) {
            val w = widget ?: return (view as IconView).iconBoundsOnScreen(out, part())
            val loc = IntArray(2); w.getLocationOnScreen(loc)
            out.set(loc[0], loc[1], loc[0] + (w.width * homeLayer.scaleX).toInt(), loc[1] + (w.height * homeLayer.scaleY).toInt())
        }

        fun snapshot(): ((android.graphics.Canvas) -> Unit)? {
            val bmp = when (view) {
                is LargeFolderIconView -> view.slotBitmap(slot)
                is FolderIconView -> view.renderIcon()
                else -> (view as IconView).displayedIcon()
            } ?: return null
            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            return { c -> c.drawBitmap(bmp, null, Rect(0, 0, c.width, c.height), paint) }
        }

        fun setHidden(hidden: Boolean) {
            if (view is LargeFolderIconView) view.hiddenSlot = if (hidden) slot else -1
            else (view as IconView).iconVisible = !hidden
        }
    }

    private fun widgetTarget(launch: WidgetLaunch): CloseTarget? {
        val page = workspace.pageAt(workspace.currentPage) as? ViewGroup ?: return null
        for (i in 0 until page.childCount) {
            val c = page.getChildAt(i) as? com.oneuiclone.home.ui.WidgetContainer ?: continue
            if (c.item.appWidgetId == launch.appWidgetId) return CloseTarget(c)
        }
        return null
    }

    private fun closeTargetFor(component: android.content.ComponentName, user: android.os.UserHandle): CloseTarget? {
        lastWidgetLaunch?.takeIf { component.packageName in it.packages && !folders.isOpen }?.let { w ->
            widgetTarget(w)?.let { return it }
        }
        val m = model ?: return null
        val r = repo ?: return null
        val serial = r.serialOf(user)
        val apps = m.items.filterIsInstance<HomeItem.App>()
            .filter { it.key.userSerial == serial && it.key.component.packageName == component.packageName }
            .sortedByDescending { it.key.component == component }
        fun onScreen(it: HomeItem) =
            it.container == Container.HOTSEAT || (it.container == Container.WORKSPACE && it.screen == workspace.currentPage)
        apps.firstOrNull { onScreen(it) }?.let { return CloseTarget(findView(it) ?: return null) }
        if (folders.isOpen) return null
        for (a in apps) {
            if (a.container != Container.FOLDER) continue
            val folder = m.items.firstOrNull { it is HomeItem.Folder && it.id == a.folderId && onScreen(it) } ?: continue
            val view = findView(folder) ?: continue
            if (view !is LargeFolderIconView) return CloseTarget(view)
            val slot = view.slotFor(view.apps.indexOfFirst { (it.second as? AppInfo)?.key == a.key })
            if (slot >= 0) return CloseTarget(view, slot)
        }
        return null
    }

    private fun handleContract(c: GestureNavContract): CloseTarget? {
        AnimLog.log("gesture contract for ${c.componentName.packageName}")
        val t0 = android.os.SystemClock.uptimeMillis()
        val front = usage.frontApps(leftHomeAt - 1000)
        if (front != null && front.size >= 2 && c.componentName.packageName in front) {
            AnimLog.log("split screen ($front, ${android.os.SystemClock.uptimeMillis() - t0} ms): no landing icon")
            return null
        }
        layOutNow()
        val target = closeTargetFor(c.componentName, c.user) ?: return null
        android.util.Log.i("OneUiHome", "contract target found: " + target.view + " slot " + target.slot)
        AnimLog.log("close target: " + ((target.view as? com.oneuiclone.home.ui.WidgetContainer)?.let { "widget ${it.item.provider.packageName}" }
            ?: target.view.contentDescription))
        val rect = target.rect()
        AnimLog.log("close rect $rect")
        if (target.isWidget) {
            val side = minOf(rect.width(), rect.height())
            val sq = Rect(rect.centerX() - side / 2, rect.centerY() - side / 2, rect.centerX() + side / 2, rect.centerY() + side / 2)
            finishContract()
            c.sendEndPosition(RectF(sq), null) { AnimLog.log("gesture contract finished") }
            return target
        }
        val draw = target.snapshot() ?: return null
        val pos = RectF(rect)
        finishContract()
        contractTarget = target
        target.setHidden(true)
        val sv = android.view.SurfaceView(activity)
        sv.setZOrderOnTop(true)
        sv.holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                drawIconSurface(holder, draw)
                if (iconSurface === sv) c.sendEndPosition(pos, sv.surfaceControl) {
                    android.util.Log.i("OneUiHome", "contract finished")
                    AnimLog.log("gesture contract finished")
                    finishContract()
                }
            }
            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) = drawIconSurface(holder, draw)
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
        })
        overlay.addView(sv, FrameLayout.LayoutParams(rect.width(), rect.height()).apply { leftMargin = rect.left; topMargin = rect.top })
        iconSurface = sv
        c.sendEndPosition(pos, null) { finishContract() }
        root.postDelayed({ if (iconSurface === sv) finishContract() }, 2000)
        return target
    }

    private fun layOutNow() {
        if (!root.isLayoutRequested || root.width == 0) return
        AnimLog.log("layout pending at contract: laying out now")
        root.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
        root.layout(root.left, root.top, root.right, root.bottom)
    }

    private fun drawIconSurface(holder: android.view.SurfaceHolder, draw: (android.graphics.Canvas) -> Unit) {
        val canvas = try { holder.lockHardwareCanvas() } catch (_: Exception) { null } ?: return
        canvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        draw(canvas)
        holder.unlockCanvasAndPost(canvas)
    }

    private fun finishContract() {
        contractTarget?.setHidden(false)
        contractTarget = null
        val sv = iconSurface ?: return
        iconSurface = null
        root.postOnAnimation { overlay.removeView(sv) }
    }

    private fun findItemView(item: HomeItem): View? {
        if (item.container != Container.WORKSPACE) return findView(item)
        val page = workspace.pageAt(item.screen) as? CellLayout ?: return null
        for (i in 0 until page.childCount) {
            val c = page.getChildAt(i)
            if ((c as? IconView)?.item?.id == item.id || (c as? com.oneuiclone.home.ui.WidgetContainer)?.item?.id == item.id) return c
        }
        return null
    }

    fun findView(item: HomeItem): IconView? {
        val parent = if (item.container == Container.HOTSEAT) hotseat else workspace.pageAt(item.screen) as? CellLayout ?: return null
        for (i in 0 until parent.childCount) {
            val v = parent.getChildAt(i) as? IconView ?: continue
            if (v.item?.id == item.id) return v
        }
        return null
    }

    fun onBack(): Boolean {
        quickOpen?.takeIf { it.isOpen }?.let { it.close(); return true }
        if (search?.isOpen == true) { closeSearch(); return true }
        popup?.let { it.dismiss(); return true }
        resizeFrame?.let { it.dismiss(); return true }
        picker?.let { it.dismiss(); return true }
        if (edit.isActive) { edit.exit(); return true }
        if (folders.isOpen) { folders.close(); return true }
        if (state.state == StateController.State.APPS) {
            state.goTo(StateController.State.HOME)
            return true
        }
        return false
    }

    fun onUiModeChanged() {
        shortcutLoader.clear()
        repo?.invalidateIcons(dp.iconSizePx)
        widgets.clearViews()
        bindWorkspace()
    }

    // ---------------------------------------------------------------------------------------------
    // vertical gestures
    // ---------------------------------------------------------------------------------------------

    private val slop = ViewConfiguration.get(activity).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var trackToApps = true
    private var consumed = false
    private var vt: VelocityTracker? = null
    private var downOnWidgetScroller = false
    private var downAppsAtTop = false

    private fun interceptVertical(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; tracking = false; consumed = false
                downOnWidgetScroller = state.state == StateController.State.HOME && widgetScrollerAt(ev.rawX.toInt(), ev.rawY.toInt())
                downAppsAtTop = state.state == StateController.State.APPS &&
                    (appsScroll.scrollY <= 0 || ev.y < dp.appsListTop)   // the handle area always drags the sheet
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (tracking || consumed) return true
                if (folders.isOpen || edit.isActive || search != null || picker != null || popup != null || quickOpen != null ||
                    resizeFrame != null || drag.isDragging) return false
                if (ev.pointerCount >= 2) return checkPinch(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dy) > slop && abs(dy) > abs(dx) * 1.2f && !workspace.isDragging) {
                    if (downOnWidgetScroller) return false
                    if (state.isTransitioning) return false
                    if (state.state == StateController.State.HOME && dy < 0 && downY >= swipeUpDeadLine()) return false
                    if (state.state == StateController.State.HOME && dy < 0) {
                        if (prefs.swipeUpSearch && !prefs.swipeDownSearch) { openSearch(fromHome = true); consumed = true }
                        else if (prefs.homeOnly) return false
                        else startTracking(true)
                        return true
                    }
                    if (state.state == StateController.State.HOME && dy > 0 && prefs.swipeDownSearch) {
                        openSearch(fromHome = true, fromAbove = true); consumed = true; return true
                    }
                    if (state.state == StateController.State.HOME && dy > 0 && prefs.swipeDownNotifications) {
                        expandNotifications(); consumed = true; return true
                    }
                    if (state.state == StateController.State.APPS && dy > 0 && downAppsAtTop) {
                        startTracking(false)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount == 2) pinchStart = spread(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { vt?.recycle(); vt = null; pinchStart = 0f }
        }
        return false
    }

    private fun widgetScrollerAt(x: Int, y: Int): Boolean {
        val page = workspace.pageAt(workspace.currentPage) as? ViewGroup ?: return false
        val r = Rect()
        fun hit(v: View) = v.isShown && v.getGlobalVisibleRect(r) && r.contains(x, y)
        fun scrolls(v: View): Boolean {
            if (!hit(v)) return false
            if (v is android.widget.StackView || v.canScrollVertically(1) || v.canScrollVertically(-1)) return true
            return v is ViewGroup && (0 until v.childCount).any { scrolls(v.getChildAt(it)) }
        }
        return (0 until page.childCount).any { (page.getChildAt(it) as? com.oneuiclone.home.ui.WidgetContainer)?.let(::scrolls) == true }
    }

    private fun swipeUpDeadLine(): Float {
        val icon = (0 until hotseat.childCount).map { hotseat.getChildAt(it) }.firstOrNull { it is IconView } as? IconView
        val iconBottom = if (icon != null) hotseat.top + icon.top + icon.iconTop() + icon.iconSizePx()
            else dp.hotseatRect.top + (dp.hotseatRect.height() + dp.iconSizePx) / 2
        return iconBottom + 10f * dp.density
    }

    private var pinchStart = 0f
    private fun spread(ev: MotionEvent) = kotlin.math.hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))

    private fun checkPinch(ev: MotionEvent): Boolean {
        if (pinchStart <= 0f || state.state != StateController.State.HOME) return false
        if (spread(ev) < pinchStart * 0.8f) {
            pinchStart = 0f
            consumed = true
            edit.enter()
            return true
        }
        return false
    }

    private fun showAppsMenu(anchor: View) {
        popup?.dismiss()
        val opts = arrayListOf(
            com.oneuiclone.home.ui.QuickOption("Sort", com.oneuiclone.home.ui.QuickOption.SELECT) {
                Toast.makeText(activity, "Sorted alphabetically", Toast.LENGTH_SHORT).show()
            },
            com.oneuiclone.home.ui.QuickOption("Settings", com.oneuiclone.home.ui.QuickOption.EDIT) { onSettingsButton() },
        )
        val p = com.oneuiclone.home.ui.QuickOptionPopup(activity, dp, iconFactory, "Apps", emptyList(), opts, { popup = null }, null,
            { rect, done -> snapshotBehind(rect, done) })
        overlay.addView(p, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val loc = IntArray(2); anchor.getLocationInWindow(loc)
        p.showFor(Rect(loc[0], loc[1], loc[0] + anchor.width, loc[1] + anchor.height), true)
        popup = p
    }

    private var search: com.oneuiclone.home.ui.AppsSearch? = null
        set(v) { field = v; background.searchOpen = v != null; background.refresh() }
    private var searchFromHome = false

    private fun drawerSearchOrigin() = searchPill.origin(searchPill.left.toFloat(), searchPill.top.toFloat())
    private fun homeSearchOrigin() = homeSearch.origin(homeSearch.left + homeSearch.translationX, homeSearch.top.toFloat())

    private fun openSearch(fromHome: Boolean = false, fromAbove: Boolean = false) {
        if (search != null) return
        if (fromHome && (state.state != StateController.State.HOME || state.isTransitioning || edit.isActive ||
                folders.isOpen || drag.isDragging || popup != null)) return
        val origin = if (fromHome) homeSearchOrigin() else drawerSearchOrigin()
        lateinit var s: com.oneuiclone.home.ui.AppsSearch
        s = com.oneuiclone.home.ui.AppsSearch(activity, dp, { repo?.apps ?: emptyList() },
            onLaunch = { v, a -> recentApps.add(a.key); launch(v, a) },
            configureIcon = { v, a -> setAppsIcon(v, a); v.appInfo = a; attachItem(v, null, a, true, forFolder = true) { folderSiblings(v) } },
            sources = searchSources,
            recents = { recentApps.resolve(repo?.apps ?: emptyList()) },
            contactsEnabled = prefs.searchContacts,
            firstPage = { model?.firstPageApps() ?: emptySet() },
            onOpenIntent = { v, intent ->
                launchFrom(v) { _, opts -> activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), opts) }
            },
            onRequestContacts = {
                activity.requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), LauncherActivity.REQUEST_CONTACTS)
            },
            canSwipeHome = { !drag.isDragging && popup == null && dragArmed == null },
            onSwipeHome = { closeSearch(toHome = true) },
            onSwipeUp = { closeSearch(lift = true) },
            onDismiss = { closeSearch() },
            barGlass = barGlass,
            cardGlass = cardGlass,
            backdrop = searchBackdrop,
        ) {
            searchPill.animate().cancel(); searchPill.alpha = 1f
            homeSearch.visibility = View.VISIBLE
            removeSearch(s)
            if (search === s) search = null
            if (state.state == StateController.State.HOME && !state.isTransitioning) restoreAppsChrome()
        }
        root.addView(s, root.indexOfChild(overlay), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        search = s
        searchFromHome = fromHome
        if (fromHome) {
            searchPill.animate().cancel(); searchPill.alpha = 0f
            appsScroll.animate().cancel(); appsScroll.alpha = 0f; appsScroll.translationY = 0f
            moreButton.animate().cancel(); moreButton.alpha = 0f; moreButton.translationY = 0f
            state.goTo(StateController.State.APPS, com.oneuiclone.home.ui.AppsSearch.MORPH_MS)
        } else {
            searchPill.animate().alpha(0f).setDuration(160).start()
            moreButton.animate().alpha(0f).setDuration(150).start()
            appsScroll.animate().alpha(0f).setDuration(200).start()
        }
        s.requestApplyInsets()
        s.open(origin, fromAbove)
    }

    fun onContactsPermission(granted: Boolean) { search?.onContactsPermission(granted) }

    private fun restoreAppsChrome() {
        appsScroll.animate().cancel(); appsScroll.alpha = 1f; appsScroll.translationY = 0f
        moreButton.animate().cancel(); moreButton.alpha = 1f; moreButton.translationY = 0f
    }

    private fun removeSearch(s: View) {
        if (drag.isDragging) { s.visibility = View.INVISIBLE; root.postDelayed({ removeSearch(s) }, 100); return }
        root.removeView(s)
    }

    private fun closeSearch(toHome: Boolean = searchFromHome, lift: Boolean = false) {
        val s = search ?: return
        if (!s.isOpen) return
        if (toHome) {
            homeSearch.visibility = View.VISIBLE
            s.close(homeSearchOrigin(), lift)
            if (state.state == StateController.State.APPS || state.isTransitioning)
                state.goTo(StateController.State.HOME, com.oneuiclone.home.ui.AppsSearch.MORPH_MS)
        } else {
            appsScroll.animate().alpha(1f).setDuration(200).start()
            moreButton.animate().alpha(1f).setDuration(150).start()
            searchPill.animate().alpha(1f).setDuration(240).start()
            s.close(drawerSearchOrigin(), lift)
        }
    }

    private fun searchToApps() {
        val s = search ?: return
        if (!s.isOpen) return
        searchFromHome = false
        if (state.state != StateController.State.APPS) state.goTo(StateController.State.APPS, com.oneuiclone.home.ui.AppsSearch.MORPH_MS)
        searchPill.animate().alpha(1f).setDuration(240).start()
        for (v in listOf(appsScroll, moreButton)) {
            v.animate().cancel()
            v.alpha = 0f
            v.translationY = dp.dp(com.oneuiclone.home.ui.AppsSearch.OPEN_RISE_DP)
            v.animate().alpha(1f).translationY(0f).setDuration(com.oneuiclone.home.ui.AppsSearch.OPEN_MS)
                .setInterpolator(Easing.IME).start()
        }
        s.close(drawerSearchOrigin())
    }

    private fun onIconsButton() {
        open("Icons", null, "Can't open Icons") {
            startSettingsPage(com.oneuiclone.home.settings.IconThemeActivity::class.java)
        }
    }

    private fun onStyleButton() {
        open("Wallpaper and style", null, "Can't open Wallpaper and style") {
            startSettingsPage(com.oneuiclone.home.settings.WallpaperStyleActivity::class.java)
        }
    }

    private fun onWidgetsButton() {
        if (picker != null) return
        val page = workspace.currentPage
        edit.exit(keepBackground = true)
        background.set(BackgroundController.Kind.WIDGETS, 1f)
        homeLayer.animate().alpha(0f).setDuration(200).start()
        lateinit var wp: com.oneuiclone.home.widgets.WidgetPicker
        wp = com.oneuiclone.home.widgets.WidgetPicker(activity, dp, widgets,
            onPick = { info ->
                wp.dismiss()
                widgets.add(info) { id, i -> placeWidget(id, i, page) }
            },
            onClose = {
                picker = null
                homeLayer.animate().alpha(1f).setDuration(200).start()
                background.set(BackgroundController.Kind.WIDGETS, 0f)
            })
        overlay.addView(wp, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        wp.show()
        picker = wp
    }

    private fun startSettingsPage(page: Class<*>) {
        activity.startActivity(Intent(activity, page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    private fun onSettingsButton() {
        open("Settings", null, "Can't open settings") {
            startSettingsPage(com.oneuiclone.home.settings.SettingsActivity::class.java)
        }
    }

    private val CLOSE_REACH = 0.6f

    private fun startTracking(toApps: Boolean) {
        tracking = true
        trackToApps = toApps
        state.begin(toApps)
    }

    private fun touchVertical(ev: MotionEvent): Boolean {
        vt?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val d = if (trackToApps) downY - ev.y else ev.y - downY
                val reach = if (trackToApps) dp.swipeProgressDistance else dp.swipeProgressDistance * CLOSE_REACH
                state.drag(d / reach, d.coerceAtLeast(0f))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tracking) {
                    vt?.computeCurrentVelocity(1)
                    val vy = vt?.yVelocity ?: 0f
                    val toward = if (trackToApps) -vy else vy
                    if (!trackToApps && ev.actionMasked == MotionEvent.ACTION_UP && vy > dp.dp(1f) && ev.y - downY > dp.dp(16f))
                        state.drag(1f, (ev.y - downY).coerceAtLeast(0f))
                    state.release(if (ev.actionMasked == MotionEvent.ACTION_CANCEL) -2f else toward / dp.samsungPxScale)
                }
                tracking = false; consumed = false
                vt?.recycle(); vt = null
            }
        }
        return true
    }

    private fun expandNotifications() {
        try {
            val sb = activity.getSystemService("statusbar")
            sb.javaClass.getMethod("expandNotificationsPanel").invoke(sb)
        } catch (_: Throwable) {
        }
    }

    inner class RootLayout : FrameLayout(activity) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && h > 0 && (w != oldw || h != oldh)) post { setupProfile(w, h) }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
            interceptVertical(ev) || super.onInterceptTouchEvent(ev)

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) { interceptVertical(ev); return true }
            if (!tracking && !consumed) {
                interceptVertical(ev)
                return true
            }
            return touchVertical(ev)
        }
    }

    fun widgetSnapshot(appWidgetId: Int): android.graphics.Bitmap? {
        if (!::workspace.isInitialized) return null
        for (p in 0 until workspace.childCount) {
            val page = workspace.getChildAt(p) as? ViewGroup ?: continue
            for (i in 0 until page.childCount) {
                val wc = page.getChildAt(i) as? com.oneuiclone.home.ui.WidgetContainer ?: continue
                if (wc.item.appWidgetId != appWidgetId || wc.width == 0 || wc.height == 0) continue
                return try {
                    android.graphics.Bitmap.createBitmap(wc.width, wc.height, android.graphics.Bitmap.Config.ARGB_8888)
                        .also { wc.draw(android.graphics.Canvas(it)) }
                } catch (e: Exception) {
                    android.util.Log.w("OneUiHome", "widget snapshot failed", e)
                    null
                }
            }
        }
        return null
    }

    fun destroy() {
        if (live?.get() === this) live = null
        android.app.WallpaperManager.getInstance(activity).removeOnColorsChangedListener(wallpaperColorsListener)
        com.oneuiclone.home.widgets.BubblesWidget.prefs(activity).unregisterOnSharedPreferenceChangeListener(bubblesListener)
        shortcutLoader.destroy()
        backdropThread.quitSafely()
        wallpaper.destroy()
        glasses.forEach { it.destroy() }
        repo?.destroy()
    }

    @Suppress("unused")
    private fun myUser() = Process.myUserHandle()
}
