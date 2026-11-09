package app.picacgmd.feature.reader.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import app.picacgmd.feature.reader.logic.eInkSwipeStep

/** One discrete step on release. Leaves taps, child retry gestures and two-finger zoom alone. */
@Composable
internal fun Modifier.eInkSwipe(
    enabled: Boolean,
    vertical: Boolean,
    rightToLeft: Boolean = false,
    triggerCm: Float,
    onStep: (Boolean) -> Unit,
): Modifier {
    if (!enabled) return this
    val metrics = LocalContext.current.resources.displayMetrics
    val dpi = if (vertical) metrics.ydpi else metrics.xdpi
    val step by rememberUpdatedState(onStep)
    return pointerInput(vertical, rightToLeft, triggerCm, dpi) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var travel = Offset.Zero
            var claimed = false
            var cancelled = false
            do {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || event.changes.count { it.pressed } > 1 || change.isConsumed) {
                    cancelled = true
                }
                if (!cancelled && change != null) {
                    travel += change.positionChange()
                    val along = if (vertical) travel.y else travel.x
                    val across = if (vertical) travel.x else travel.y
                    if (!claimed && kotlin.math.abs(along) > viewConfiguration.touchSlop && kotlin.math.abs(along) > kotlin.math.abs(across)) claimed = true
                    if (claimed) change.consume()
                }
            } while (event.changes.any { it.pressed })
            if (claimed && !cancelled) {
                val direction = eInkSwipeStep(
                    if (vertical) travel.y else travel.x, dpi, triggerCm,
                    forwardSign = if (!vertical && rightToLeft) 1 else -1,
                )
                if (direction != 0) step(direction > 0)
            }
        }
    }
}
