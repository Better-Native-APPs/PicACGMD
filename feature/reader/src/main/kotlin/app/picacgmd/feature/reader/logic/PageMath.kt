package app.picacgmd.feature.reader.logic

import kotlin.math.abs
import kotlin.math.roundToInt

/** Clamps [page] into `0 until pageCount`; 0 for an empty chapter. */
internal fun clampPage(page: Int, pageCount: Int): Int =
    if (pageCount <= 0) 0 else page.coerceIn(0, pageCount - 1)

/**
 * Pages to keep loading around [current], most urgent first: the current page, then the pages
 * ahead (nearest first), then the pages behind (nearest first).
 */
internal fun preloadOrder(current: Int, pageCount: Int, ahead: Int, behind: Int): List<Int> {
    if (pageCount <= 0) return emptyList()
    val anchor = clampPage(current, pageCount)
    val result = ArrayList<Int>(1 + ahead.coerceAtLeast(0) + behind.coerceAtLeast(0))
    result += anchor
    for (step in 1..ahead) if (anchor + step < pageCount) result += anchor + step
    for (step in 1..behind) if (anchor - step >= 0) result += anchor - step
    return result
}

/** Main-axis position of one laid-out strip item, relative to the start of the viewport. */
internal data class StripItem(val index: Int, val offset: Int, val size: Int)

/**
 * The page the reader is "on" in a continuous strip: the item crossing the probe line at
 * [probeFraction] of the viewport. At the very start the first visible item wins and at the very
 * end the last one does, so short first or last pages are still reported.
 */
internal fun stripCurrentPage(
    visible: List<StripItem>,
    viewportSize: Int,
    atStart: Boolean,
    atEnd: Boolean,
    probeFraction: Float = 0.5f,
): Int {
    if (visible.isEmpty()) return 0
    if (atEnd && !atStart) return visible.maxOf { it.index }
    if (atStart && !atEnd) return visible.minOf { it.index }
    val probe = viewportSize * probeFraction
    visible.firstOrNull { probe >= it.offset && probe < it.offset + it.size }?.let { return it.index }
    // Probe falls in a gap (or outside every item): take the item closest to it.
    return visible.minBy { item ->
        val start = item.offset.toFloat()
        val end = (item.offset + item.size).toFloat()
        minOf(abs(probe - start), abs(probe - end))
    }.index
}

/** The laid-out item under position [y] of the list, or null in a gap or past the end. */
internal fun stripItemAt(visible: List<StripItem>, y: Float): Int? =
    visible.firstOrNull { y >= it.offset && y < it.offset + it.size }?.index

/** Pixels one 上一屏 / 下一屏 step scrolls the strip, in list coordinates. */
internal fun stripStepDistance(viewportSize: Int, fraction: Float, scale: Float = 1f): Float =
    viewportSize * fraction.coerceIn(0.1f, 1f) / scale.coerceAtLeast(0.01f)

/** Upper bound for the pixels of one decoded strip page (about 48 MB as ARGB_8888). */
internal const val MAX_STRIP_PAGE_PIXELS: Int = 12_000_000

/** Tallest page (height / width) still decoded at the full viewport width. */
internal const val MAX_FULL_WIDTH_ASPECT: Int = 8

/** Largest edge used for a decode box; also guards against zero or unbounded constraints. */
internal const val MAX_DECODE_EDGE: Int = 8192

/** A layout extent made safe for a decode box (Coil needs a positive, finite size). */
internal fun decodeEdge(extent: Int): Int = extent.coerceIn(1, MAX_DECODE_EDGE)

/**
 * Height of the box a strip page is decoded into (with the viewport width as its width, fit
 * inside). Ordinary pages fit by width, so they decode at the viewport width; only extremely tall
 * images are decoded narrower, which keeps every page bitmap under [maxPagePixels].
 */
internal fun stripDecodeHeight(viewportWidth: Int, maxPagePixels: Int = MAX_STRIP_PAGE_PIXELS): Int {
    val width = viewportWidth.coerceAtLeast(1)
    return minOf(width.toLong() * MAX_FULL_WIDTH_ASPECT, maxPagePixels.toLong() / width)
        .coerceAtLeast(1L)
        .toInt()
}

/** Target page for a relative move, or null when it falls outside the chapter. */
internal fun stepTarget(current: Int, delta: Int, pageCount: Int): Int? {
    val target = current + delta
    return if (target in 0 until pageCount) target else null
}

/**
 * Whole percent for a download fraction (0..1) as shown on a loading page, or null when the
 * fraction is not a number. Out-of-range fractions are clamped.
 */
internal fun loadPercent(fraction: Float): Int? =
    if (fraction.isNaN()) null else (fraction.coerceIn(0f, 1f) * 100f).roundToInt()

/** Slider value (0..1) for [page]; a single-page chapter sits at 0. */
internal fun pageToSlider(page: Int, pageCount: Int): Float =
    if (pageCount <= 1) 0f else clampPage(page, pageCount).toFloat() / (pageCount - 1)

/** Page index for a slider value (0..1). */
internal fun sliderToPage(value: Float, pageCount: Int): Int =
    if (pageCount <= 1) 0 else clampPage((value.coerceIn(0f, 1f) * (pageCount - 1)).roundToInt(), pageCount)

/**
 * Two-page spreads. With [coverAlone] the first page has a spread to itself and the rest are paired
 * (1 | 2-3 | 4-5 …); otherwise pages are paired from the start (1-2 | 3-4 …).
 */
internal fun spreadCount(pageCount: Int, coverAlone: Boolean): Int = when {
    pageCount <= 0 -> 0
    coverAlone -> 1 + pageCount / 2
    else -> (pageCount + 1) / 2
}

/** The spread containing [page]. */
internal fun spreadOfPage(page: Int, pageCount: Int, coverAlone: Boolean): Int {
    val p = clampPage(page, pageCount)
    return if (coverAlone) (if (p == 0) 0 else (p + 1) / 2) else p / 2
}

/** Pages shown in [spread], in reading order (one or two pages). */
internal fun pagesOfSpread(spread: Int, pageCount: Int, coverAlone: Boolean): IntRange {
    if (pageCount <= 0) return IntRange.EMPTY
    val first = if (coverAlone) (if (spread <= 0) 0 else spread * 2 - 1) else spread * 2
    if (first !in 0 until pageCount) return IntRange.EMPTY
    if (coverAlone && spread <= 0) return 0..0
    return first..minOf(first + 1, pageCount - 1)
}
