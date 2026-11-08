package app.picacgmd.feature.reader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.picacgmd.feature.reader.logic.ForwardSwipe
import app.picacgmd.feature.reader.logic.fingerMovement
import app.picacgmd.feature.reader.logic.forwardComponent
import app.picacgmd.feature.reader.logic.pullAfterLeftover
import app.picacgmd.feature.reader.logic.pullGivenBack
import app.picacgmd.feature.reader.logic.shouldLeaveChapter

private val PullDistance = 96.dp
private val FlickSpeed = 1200.dp

/**
 * 继续上滑直接进入下一话: watches what a drag has left over once the list cannot move any further.
 * Attach it with `Modifier.nestedScroll` above the list or pager, whose own overscroll effect must be
 * off: that effect would take a backward drag for itself and the pull could never be taken back. Only
 * finger drags count, so a fling that runs into the end of the chapter stops there and leaves the end
 * card on screen.
 */
@Stable
internal class ChapterPullState(
    private val threshold: Float,
    private val flickVelocity: Float,
    private val onLeave: () -> Unit,
    private val onArmed: () -> Unit,
) : NestedScrollConnection {
    var swipe: ForwardSwipe = ForwardSwipe.Up

    /** Pixels pulled past the end during the current drag. */
    var distance by mutableFloatStateOf(0f)
        private set

    /** 0..1 towards the distance at which letting go leaves the chapter. */
    val progress: Float get() = (distance / threshold).coerceIn(0f, 1f)

    /** Letting go now leaves the chapter. */
    val armed: Boolean get() = distance >= threshold

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || distance <= 0f) return Offset.Zero
        val givenBack = pullGivenBack(distance, forwardComponent(available.x, available.y, swipe))
        if (givenBack <= 0f) return Offset.Zero
        update(distance - givenBack)
        val (x, y) = fingerMovement(-givenBack, swipe)
        return Offset(x, y)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput) {
            update(pullAfterLeftover(distance, forwardComponent(available.x, available.y, swipe)))
        }
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        val leave = shouldLeaveChapter(
            pull = distance,
            threshold = threshold,
            velocity = forwardComponent(available.x, available.y, swipe),
            flickVelocity = flickVelocity,
        )
        distance = 0f
        if (leave) onLeave()
        return Velocity.Zero
    }

    private fun update(value: Float) {
        val wasArmed = armed
        distance = value
        if (!wasArmed && armed) onArmed()
    }
}

/** A [ChapterPullState] going forward along [swipe], calling [onLeave] when a drag leaves the chapter. */
@Composable
internal fun rememberChapterPull(swipe: ForwardSwipe, onLeave: () -> Unit): ChapterPullState {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val latestOnLeave by rememberUpdatedState(onLeave)
    val state = remember(density, haptics) {
        ChapterPullState(
            threshold = with(density) { PullDistance.toPx() },
            flickVelocity = with(density) { FlickSpeed.toPx() },
            onLeave = { latestOnLeave() },
            onArmed = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
        )
    }
    SideEffect { state.swipe = swipe }
    return state
}
