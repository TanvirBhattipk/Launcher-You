package com.oneuiclone.home.device

import android.graphics.Rect
import kotlin.math.roundToInt

/**
 * Phone layout derived from One UI Home fractions (spec §1.1 / §15). All fractions are of the full
 * window size, exactly as PhoneCellLayoutInfo uses them.
 */
class DeviceProfile(
    val widthPx: Int,
    val heightPx: Int,
    val density: Float,
    val insets: Rect,
    val homeCols: Int = 4,
    val homeRows: Int = 6,
    val appsCols: Int = 4,
    val appsRows: Int = 6,
    val iconScale: Float = 1f,
    val labelScale: Float = 1f,
    /** Extra space (dp) above and below Home's icons and the Apps screen's (Home screen settings). */
    val homeTopPadDp: Float = 0f,
    val homeBottomPadDp: Float = 0f,
    val appsTopPadDp: Float = 0f,
    val appsBottomPadDp: Float = 0f,
) {
    fun dp(v: Float) = v * density
    fun dpi(v: Float) = (v * density).roundToInt()

    // ---- workspace (home) ----
    val pageTopPadding = insets.top + dpi(22f)
    /**
     * Symmetrical bottom padding above the gesture navigation inset so the dock sits cleanly above the handle.
     */
    val pageBottomPadding = dpi(10f) + insets.bottom
    val indicatorAreaHeight = (heightPx * 0.041f).roundToInt()     // indicator_area_height_ratio
    val hotseatHeight = (heightPx * 0.112f).roundToInt()           // compact dock bar matching reference proportions
    val pageSidePadding = (widthPx * 0.034f).roundToInt()          // aligns outer icon & widget edges symmetrically (~6.4% outer visual margin)

    /** Where the grid would end with no extra bottom padding: the page indicator and the dock stay under it. */
    private val gridBottom = heightPx - pageBottomPadding - hotseatHeight - indicatorAreaHeight
    /** Symmetrical extra space (dp) above and below Home's 6 icon rows for balanced top/bottom breathing room. */
    private val HOME_GRID_EXTRA_PAD_DP = 22f
    /** Height of the icon grid (the Home top/bottom padding settings shrink it); its position is solved below. */
    private val gridHeight = (gridBottom - dpi(homeBottomPadDp)) - (pageTopPadding + dpi(homeTopPadDp)) - 2 * dpi(HOME_GRID_EXTRA_PAD_DP)
    val cellWidth = (widthPx - 2 * pageSidePadding) / homeCols
    val cellHeight = gridHeight / homeRows

/** ~67% of cell width (~64dp on a 412dp screen), matching the reference icon-to-gutter ratio. */
    val iconSizePx = (cellWidth * 0.67f * iconScale).roundToInt().coerceAtMost((cellHeight * 0.64f).roundToInt()).let { if (it % 2 == 1) it + 1 else it }
    /**
     * The icon size of the search page's Recents row: Home's 62.7 % of the cell width made 5 % smaller, and fixed, so
     * changing Home's icon size (or its icon scale) never changes it.
     */
    val recentsIconSizePx = (cellWidth * 0.627f * 0.95f).roundToInt().let { if (it % 2 == 1) it + 1 else it }
    /**
     * Home's icon size at the default settings (4 columns, 98 % icon scale), fixed: the Apps row of the search page
     * uses it, so it matches the Home screen's icons but never changes with Home's icon size or grid settings.
     */
    val searchIconSizePx = ((widthPx - 2 * pageSidePadding) / 4f * 0.67f * 0.98f).roundToInt().let { if (it % 2 == 1) it + 1 else it }
    val labelTextSizePx = dp(13f) * 0.855f * labelScale
    val iconLabelGapPx = dp(5.5f)

    /** Keeps the dock symmetrically spaced below the Search pill. */
    private val dockDrop = dpi(8f)
    private val hotseatTop = gridBottom + indicatorAreaHeight + dockDrop
    val hotseatRect = Rect(
        pageSidePadding,                                             // the dock's icons sit in the same columns as Home's (stock: 0.036 of the width)
        hotseatTop,
        widthPx - pageSidePadding,
        hotseatTop + hotseatHeight,
    )

    // ---- even vertical spacing ----
    // Three gaps are made equal: status bar -> first icon row, last row's label -> Search pill, Search pill -> dock icons.
    /** HomeSearchButton.pillHeight. */
    private val searchPillHeight = dpi(32f)
    /** An icon + gap + label block as IconView draws it (label height ~ 1.17 x its text size), centred in its cell. */
    private val iconBlockHeight = iconSizePx + iconLabelGapPx + labelTextSizePx * 1.17f
    private val iconBlockOffset = (cellHeight - iconBlockHeight) / 2f
    /** From the grid's top to the bottom of the last row's label. */
    private val lastLabelBottomInGrid = (homeRows - 1) * cellHeight + iconBlockOffset + iconBlockHeight
    /** The dock icons are centred in their cell (no labels). */
    private val dockIconTop = hotseatTop + (hotseatHeight - iconSizePx) / 2f
    /**
     * Grid top G with  (G + offset - statusBar) = (dockIconTop - (G + lastLabelBottom) - pill) / 2  — the status-bar gap
     * equals each Search-pill gap. The Home top padding setting still moves the grid down from there.
     */
    private val gridTop = (((dockIconTop - lastLabelBottomInGrid - searchPillHeight - 2f * iconBlockOffset + 2f * insets.top) / 3f)
        .coerceAtLeast(insets.top.toFloat())).roundToInt() + dpi(homeTopPadDp)
    val workspaceGrid: Rect = Rect(
        pageSidePadding,
        gridTop,
        widthPx - pageSidePadding,
        gridTop + gridHeight,
    )

    /** The Search pill (and the page dots beside it) centred between the last row's label and the dock icons. */
    private val pillTop = workspaceGrid.top + lastLabelBottomInGrid +
        (dockIconTop - (workspaceGrid.top + lastLabelBottomInGrid) - searchPillHeight) / 2f
    private val indicatorTop = (pillTop + searchPillHeight / 2f - indicatorAreaHeight / 2f).roundToInt()
    val indicatorRect = Rect(0, indicatorTop, widthPx, indicatorTop + indicatorAreaHeight)

    // ---- page indicator (page_indicator_dot_size_sw_ratio = slot width) ----
    val indicatorSlotPx = (minOf(widthPx, heightPx) * 0.047f)
    val indicatorDotPx = dp(6f)

    // ---- apps screen ----
    val appsSidePadding = pageSidePadding                          // matches Home's horizontal icon columns symmetrically
    /** Side margin of the search bar: its ends line up with the outer edges of the first and last icon columns. */
    val searchBarSide = dpi(18f)
    val searchBarWidth = widthPx - 2 * searchBarSide
    val searchBarHeight = dpi(48f)                                 // search_bar_wrapper_height
    val searchBarRadius = dp(32f)                                   // search_bar_bg_radius
    val searchBarBottomMargin = (heightPx * 0.022f).roundToInt() + insets.bottom
    /** The Apps search bar sits at the top, a little way under the status bar. */
    val searchBarTop = insets.top + (heightPx * 0.028f).roundToInt()
    // The App drawer is a bottom sheet (Pixel style): rounded top corners, a drag handle, the suggested row, an
    // "All apps" header and the grid. It has no search bar; the search bar values above are still used by the search page.
    /** Top edge of the sheet: ~12.3 % down the screen, never closer than a little under the status bar. */
    val sheetTop = maxOf(insets.top + dpi(6f), (heightPx * 0.08f).roundToInt())
    /** Radius of the sheet's two top corners. */
    val sheetRadius = dp(36f)
    val sheetHandleWidth = dp(32f)
    val sheetHandleHeight = dp(4f)
    /** Distance from the sheet's top edge to the middle of the drag handle. */
    val sheetHandleCenterY = dp(17f)
    /** Where the apps list starts (and clips): just under the drag handle. */
    val appsListTop = sheetTop + dpi(30f)
    val appsIndicatorHeight = (heightPx * 0.044f).roundToInt()     // apps_page_indicator_height_ratio
    private val appsGridTop = appsListTop + dpi(6f) + dpi(appsTopPadDp)
    // rows apps rows fill the sheet and part of one more peeks in at the bottom, so it is clear the list scrolls
    private val appsGridHeight = ((heightPx - appsGridTop - insets.bottom - dpi(8f)) * appsRows / (appsRows + 1f)).roundToInt()
    val appsGrid = Rect(
        appsSidePadding,
        appsGridTop,
        widthPx - appsSidePadding,
        appsGridTop + appsGridHeight,
    )
    /** Space the Apps list keeps under its last row. */
    val appsBottomPad = dpi(appsBottomPadDp)
    val appsIndicatorRect = Rect(0, appsGrid.bottom, widthPx, appsGrid.bottom + appsIndicatorHeight)

    // ---- transitions ----
    /** swipe_vertical_transition_y */
    val swipeTransitionY = dp(86f)

    /** Home→Apps progress = drag / 800 px on S26 (FHD+, density 2.8125) ⇒ 284.4 dp. */
    val swipeProgressDistance = dp(284.4f)

    /** One UI Home works in raw px/ms on a 2.8125 density panel; scale its thresholds to this screen. */
    val samsungPxScale = density / 2.8125f

    val cornerRadius = dp(26f)

    /** Inset of a widget inside its cells on every side (WidgetContainer's padding). */
   /** Symmetrical horizontal & vertical insets so widget outer edges match icon outer edges on all 4 sides. */
    val widgetPadXPx = ((cellWidth - iconSizePx) / 2f).roundToInt()
    val widgetPadYPx = iconBlockOffset.roundToInt().coerceAtLeast(0)
    val widgetPaddingPx = widgetPadXPx
}
