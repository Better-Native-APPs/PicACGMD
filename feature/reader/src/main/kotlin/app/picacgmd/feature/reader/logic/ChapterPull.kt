package app.picacgmd.feature.reader.logic

import app.picacgmd.feature.reader.ReaderDirection
import app.picacgmd.feature.reader.ReaderMode

/** The way a finger moves to go forward in the current reading mode. */
internal enum class ForwardSwipe { Up, Left, Right }

/**
 * The direction a mode actually reads in. Strips only scroll down; two-page spreads only make sense
 * side by side, so they fall back to left to right.
 */
internal fun effectiveDirection(mode: ReaderMode, direction: ReaderDirection): ReaderDirection = when (mode) {
    ReaderMode.Strip, ReaderMode.FreeZoom -> ReaderDirection.TopToBottom
    ReaderMode.Paged -> direction
    ReaderMode.DoublePage -> if (direction == ReaderDirection.TopToBottom) ReaderDirection.LeftToRight else direction
}

/** Directions a mode can read in, for the settings sheet. */
internal fun availableDirections(mode: ReaderMode): Set<ReaderDirection> = when (mode) {
    ReaderMode.Strip, ReaderMode.FreeZoom -> setOf(ReaderDirection.TopToBottom)
    ReaderMode.Paged -> ReaderDirection.entries.toSet()
    ReaderMode.DoublePage -> setOf(ReaderDirection.LeftToRight, ReaderDirection.RightToLeft)
}

internal fun forwardSwipeFor(direction: ReaderDirection): ForwardSwipe = when (direction) {
    ReaderDirection.TopToBottom -> ForwardSwipe.Up
    ReaderDirection.LeftToRight -> ForwardSwipe.Left
    ReaderDirection.RightToLeft -> ForwardSwipe.Right
}

/** The part of a scroll delta or velocity pointing forward; negative when it points backward. */
internal fun forwardComponent(x: Float, y: Float, swipe: ForwardSwipe): Float = when (swipe) {
    ForwardSwipe.Up -> -y
    ForwardSwipe.Left -> -x
    ForwardSwipe.Right -> x
}

/** The finger movement whose forward part is [forward]; the inverse of [forwardComponent]. */
internal fun fingerMovement(forward: Float, swipe: ForwardSwipe): Pair<Float, Float> = when (swipe) {
    ForwardSwipe.Up -> 0f to -forward
    ForwardSwipe.Left -> -forward to 0f
    ForwardSwipe.Right -> forward to 0f
}

/** Pull distance after the list could not take [leftover] pixels of a forward drag. */
internal fun pullAfterLeftover(pull: Float, leftover: Float): Float =
    if (leftover > 0f) pull + leftover else pull

/**
 * How much of a [delta] pixel finger move goes to giving the pull back: moving backward first undoes
 * the pull, and only what is left of the move scrolls the list again.
 */
internal fun pullGivenBack(pull: Float, delta: Float): Float =
    if (delta < 0f) minOf(pull, -delta) else 0f

/** A quick flick counts once it has pulled this share of the full distance. */
internal const val FLICK_PULL_SHARE: Float = 0.25f

/**
 * Whether releasing a drag leaves the chapter: the finger pulled far enough past the end, or flicked
 * forward quickly after pulling a little.
 */
internal fun shouldLeaveChapter(pull: Float, threshold: Float, velocity: Float, flickVelocity: Float): Boolean =
    pull >= threshold || (pull > 0f && pull >= threshold * FLICK_PULL_SHARE && velocity >= flickVelocity)
