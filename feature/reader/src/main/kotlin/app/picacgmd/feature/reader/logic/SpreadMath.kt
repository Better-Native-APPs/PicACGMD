package app.picacgmd.feature.reader.logic

import app.picacgmd.feature.reader.TwoPageArrangement

/**
 * How a pager groups the pages of a chapter into screens ("slots"): one page each, or two-page
 * spreads as described at [spreadCount].
 */
internal data class PageSlots(val pageCount: Int, val spreads: Boolean, val coverAlone: Boolean = false) {
    /** Most pages a slot holds. */
    val perSlot: Int get() = if (spreads) 2 else 1

    val count: Int get() = if (spreads) spreadCount(pageCount, coverAlone) else pageCount.coerceAtLeast(0)

    /** The slot showing [page]. */
    fun slotOf(page: Int): Int = if (spreads) spreadOfPage(page, pageCount, coverAlone) else clampPage(page, pageCount)

    /** Pages shown in [slot] in reading order; empty outside the chapter. */
    fun pagesOf(slot: Int): IntRange = when {
        spreads -> pagesOfSpread(slot, pageCount, coverAlone)
        slot in 0 until pageCount -> slot..slot
        else -> IntRange.EMPTY
    }

    /**
     * Every page of the slots within [radius] of [slot]: what a pager keeping [radius] slots composed
     * on each side has on hand. A [slot] past the end (the chapter-end page) counts as the last slot.
     */
    fun pagesAround(slot: Int, radius: Int): IntRange {
        if (count <= 0) return IntRange.EMPTY
        val first = pagesOf((slot - radius).coerceIn(0, count - 1))
        val last = pagesOf((slot + radius).coerceIn(0, count - 1))
        if (first.isEmpty() || last.isEmpty()) return IntRange.EMPTY
        return first.first..last.last
    }
}

/** The two pages of a spread as they sit on screen. */
internal data class SpreadSides(val left: Int, val right: Int)

/**
 * Left and right page of a spread whose pages are [first] and [second] in reading order: reading
 * right to left puts the first page on the right.
 */
internal fun spreadSides(first: Int, second: Int, rightToLeft: Boolean): SpreadSides =
    if (rightToLeft) SpreadSides(left = second, right = first) else SpreadSides(left = first, right = second)

/** Where a page sits inside its half of the screen. */
internal enum class HalfAnchor { Left, Center, Right }

/**
 * 靠近 pushes both pages against the fold in the middle, 远离 against the outer edges of the screen,
 * and 各自居中 centres each page in its half.
 */
internal fun spreadAnchor(arrangement: TwoPageArrangement, leftHalf: Boolean): HalfAnchor = when (arrangement) {
    TwoPageArrangement.CloseTo -> if (leftHalf) HalfAnchor.Right else HalfAnchor.Left
    TwoPageArrangement.PullAway -> if (leftHalf) HalfAnchor.Left else HalfAnchor.Right
    TwoPageArrangement.EachCentered -> HalfAnchor.Center
}

/** The page of [sides] under [contentX], in unzoomed content coordinates of a [width] wide screen. */
internal fun spreadPageAt(contentX: Float, width: Float, sides: SpreadSides): Int =
    if (contentX < width / 2f) sides.left else sides.right
