package app.picacgmd.feature.reader.logic

import app.picacgmd.feature.reader.TapMode

/** Share of the paging axis taken by each outer zone (30 / 40 / 30). */
internal const val TAP_EDGE_ZONE: Float = 0.3f

/** One of the three tap zones, already resolved to its meaning. */
internal enum class TapZone { Previous, Menu, Next }

/** Axis along which the current mode turns pages. */
internal enum class PagingAxis { Horizontal, Vertical }

/**
 * Zone hit by a tap at ([x], [y]) inside a [width] × [height] area.
 *
 * Horizontal paging: left 30% = 上一页, right 30% = 下一页, mirrored for right-to-left reading unless
 * [alwaysRightIsNext]. Vertical paging (strip, top-to-bottom pager): top 30% = 上一屏, bottom 30% =
 * 下一屏. The middle 40% is always 菜单.
 */
internal fun tapZoneAt(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    axis: PagingAxis,
    rightToLeft: Boolean,
    alwaysRightIsNext: Boolean,
): TapZone {
    val extent = if (axis == PagingAxis.Horizontal) width else height
    if (extent <= 0f) return TapZone.Menu
    val ratio = (if (axis == PagingAxis.Horizontal) x else y) / extent
    val startSide = ratio < TAP_EDGE_ZONE
    val endSide = ratio > 1f - TAP_EDGE_ZONE
    if (!startSide && !endSide) return TapZone.Menu
    val mirrored = axis == PagingAxis.Horizontal && rightToLeft && !alwaysRightIsNext
    return when {
        startSide != mirrored -> TapZone.Previous
        else -> TapZone.Next
    }
}

/** One zone of the three-zone layout, with its bounds as fractions of the screen. */
internal data class TapZoneArea(
    val zone: TapZone,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** The three zones in physical order, resolved exactly as [tapZoneAt] resolves a tap. */
internal fun threeZoneAreas(axis: PagingAxis, rightToLeft: Boolean, alwaysRightIsNext: Boolean): List<TapZoneArea> {
    fun zoneAt(fraction: Float) = tapZoneAt(fraction, fraction, 1f, 1f, axis, rightToLeft, alwaysRightIsNext)
    val first = TAP_EDGE_ZONE
    val last = 1f - TAP_EDGE_ZONE
    return if (axis == PagingAxis.Horizontal) {
        listOf(
            TapZoneArea(zoneAt(first / 2f), 0f, 0f, first, 1f),
            TapZoneArea(TapZone.Menu, first, 0f, last, 1f),
            TapZoneArea(zoneAt((last + 1f) / 2f), last, 0f, 1f, 1f),
        )
    } else {
        listOf(
            TapZoneArea(zoneAt(first / 2f), 0f, 0f, 1f, first),
            TapZoneArea(TapZone.Menu, 0f, first, 1f, last),
            TapZoneArea(zoneAt((last + 1f) / 2f), 0f, last, 1f, 1f),
        )
    }
}

/** What a tap or double tap does. */
internal enum class TapAction { None, ToggleControls, NextPage, PreviousPage, ToggleZoom }

/** Single tap in [zone] under [mode]. */
internal fun singleTapAction(mode: TapMode, zone: TapZone): TapAction = when (mode) {
    TapMode.TapToToggle -> TapAction.ToggleControls
    TapMode.Controller -> TapAction.None
    TapMode.DoubleTapToToggle -> TapAction.None
    TapMode.DoubleTapToggleTapNext -> TapAction.NextPage
    TapMode.ThreeZones -> when (zone) {
        TapZone.Previous -> TapAction.PreviousPage
        TapZone.Menu -> TapAction.ToggleControls
        TapZone.Next -> TapAction.NextPage
    }
}

/** Double tap under [mode]. Modes that spend the double tap on the controls zoom by pinch only. */
internal fun doubleTapAction(mode: TapMode): TapAction = when (mode) {
    TapMode.DoubleTapToToggle, TapMode.DoubleTapToggleTapNext -> TapAction.ToggleControls
    TapMode.TapToToggle, TapMode.Controller, TapMode.ThreeZones -> TapAction.ToggleZoom
}

/** Whether a single tap needs its zone resolved (only three-zone mode cares). */
internal fun TapMode.usesZones(): Boolean = this == TapMode.ThreeZones
