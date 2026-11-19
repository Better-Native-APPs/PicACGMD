package app.picacgmd.feature.reader.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isOutOfBounds

internal suspend fun PointerInputScope.detectReaderTapGestures(
    onDoubleTap: (Offset) -> Unit,
    onLongPress: (Offset) -> Unit,
    onTap: (Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        val up = when (val result = awaitReaderPress(down)) {
            is ReaderPressResult.Released -> result.up.also { it.consume() }
            ReaderPressResult.LongPressed -> {
                onLongPress(down.position)
                consumeReaderPress()
                return@awaitEachGesture
            }
            ReaderPressResult.Cancelled -> return@awaitEachGesture
        }
        val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
            val earliest = up.uptimeMillis + viewConfiguration.doubleTapMinTimeMillis
            var next = awaitFirstDown()
            while (next.uptimeMillis < earliest) next = awaitFirstDown()
            next
        }
        if (secondDown == null) {
            onTap(up.position)
            return@awaitEachGesture
        }
        secondDown.consume()
        when (val result = awaitReaderPress(secondDown)) {
            is ReaderPressResult.Released -> {
                result.up.consume()
                onDoubleTap(result.up.position)
            }
            ReaderPressResult.LongPressed -> {
                onTap(up.position)
                onLongPress(secondDown.position)
                consumeReaderPress()
            }
            ReaderPressResult.Cancelled -> onTap(up.position)
        }
    }
}

private sealed interface ReaderPressResult {
    data class Released(val up: PointerInputChange) : ReaderPressResult
    data object LongPressed : ReaderPressResult
    data object Cancelled : ReaderPressResult
}

private suspend fun AwaitPointerEventScope.awaitReaderPress(down: PointerInputChange): ReaderPressResult {
    var result: ReaderPressResult = ReaderPressResult.LongPressed
    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.singleOrNull()
            if (change == null || change.id != down.id || change.isConsumed ||
                change.isOutOfBounds(size, extendedTouchPadding) ||
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop ||
                change.historical.any { (it.position - down.position).getDistance() > viewConfiguration.touchSlop }
            ) {
                result = ReaderPressResult.Cancelled
                return@withTimeoutOrNull
            }
            if (change.changedToUp()) {
                result = ReaderPressResult.Released(change)
                return@withTimeoutOrNull
            }
            if (awaitPointerEvent(PointerEventPass.Final).changes.any { it.isConsumed }) {
                result = ReaderPressResult.Cancelled
                return@withTimeoutOrNull
            }
        }
    }
    return result
}

private suspend fun AwaitPointerEventScope.consumeReaderPress() {
    do {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}
