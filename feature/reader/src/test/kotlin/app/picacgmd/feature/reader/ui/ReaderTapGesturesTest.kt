package app.picacgmd.feature.reader.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ReaderTapGesturesTest {
    private val origin = Offset(300f, 1200f)

    @Test
    fun slowHorizontalDragDoesNotOpenImageActionsOrConsumeTheDrag() {
        val result = gesture(
            Sample(0, origin),
            Sample(100, Offset(400f, 1200f)),
            Sample(600, Offset(800f, 1200f)),
            Sample(700, Offset(800f, 1200f), pressed = false),
        )

        assertEquals(emptyList(), result.longPresses)
        assertEquals(emptyList(), result.taps)
        result.events.drop(1).forEach { event -> assertFalse(event.changes.single().isConsumed) }
    }

    @Test
    fun crossingSlopInAnyDirectionCancelsLongPressEvenJustBeforeTheTimeout() {
        for (delta in listOf(Offset(-20f, 0f), Offset(0f, 20f), Offset(0f, -20f), Offset(6f, 6f))) {
            val result = gesture(
                Sample(0, origin),
                Sample(499, origin + delta),
                Sample(600, origin + delta),
                Sample(700, origin + delta, pressed = false),
            )

            assertEquals(emptyList(), result.longPresses, "Drag by $delta")
        }
    }

    @Test
    fun returningInsideSlopDoesNotRestoreLongPress() {
        val result = gesture(
            Sample(0, origin),
            Sample(100, origin + Offset(20f, 0f)),
            Sample(400, origin),
            Sample(600, origin),
            Sample(700, origin, pressed = false),
        )

        assertEquals(emptyList(), result.longPresses)
    }

    @Test
    fun stationaryHoldOpensImageActionsExactlyOnceWithoutATap() {
        val result = gesture(
            Sample(0, origin),
            Sample(600, origin),
            Sample(900, origin),
            Sample(1000, origin, pressed = false),
        )

        assertEquals(listOf(origin), result.longPresses)
        assertEquals(emptyList(), result.taps)
        assertEquals(emptyList(), result.doubleTaps)
    }

    @Test
    fun movementWithinSlopStillAllowsLongPressAtTheOriginalPosition() {
        val result = gesture(
            Sample(0, origin),
            Sample(200, origin + Offset(3f, 4f)),
            Sample(600, origin + Offset(3f, 4f)),
            Sample(700, origin + Offset(3f, 4f), pressed = false),
        )

        assertEquals(listOf(origin), result.longPresses)
    }

    @Test
    fun tapAndDoubleTapKeepTheirSeparateCallbacks() {
        val tap = gesture(Sample(0, origin), Sample(100, origin, pressed = false))
        assertEquals(listOf(origin), tap.taps)
        assertEquals(emptyList(), tap.doubleTaps)
        assertEquals(emptyList(), tap.longPresses)

        val doubleTap = gesture(
            Sample(0, origin),
            Sample(100, origin, pressed = false),
            Sample(200, origin),
            Sample(300, origin, pressed = false),
        )
        assertEquals(emptyList(), doubleTap.taps)
        assertEquals(listOf(origin), doubleTap.doubleTaps)
        assertEquals(emptyList(), doubleTap.longPresses)
    }

    private fun gesture(vararg samples: Sample): GestureResult = runBlocking {
        val input = ScriptedPointerInputScope(samples.toList())
        val pointerInput: PointerInputScope = input
        val result = GestureResult(events = input.events)
        val detector = launch(start = CoroutineStart.UNDISPATCHED) {
            pointerInput.detectReaderTapGestures(
                onTap = result.taps::add,
                onDoubleTap = result.doubleTaps::add,
                onLongPress = result.longPresses::add,
            )
        }
        yield()
        detector.cancelAndJoin()
        result
    }

    private data class Sample(val time: Long, val position: Offset, val pressed: Boolean = true)

    private class GestureResult(val events: List<PointerEvent>) {
        val taps = mutableListOf<Offset>()
        val doubleTaps = mutableListOf<Offset>()
        val longPresses = mutableListOf<Offset>()
    }

    private class ScriptedPointerInputScope(samples: List<Sample>) : PointerInputScope, AwaitPointerEventScope {
        override val density = 1f
        override val fontScale = 1f
        override val size = IntSize(1000, 2000)
        override val extendedTouchPadding = Size.Zero
        override val viewConfiguration = object : ViewConfiguration {
            override val longPressTimeoutMillis = 500L
            override val doubleTapTimeoutMillis = 300L
            override val doubleTapMinTimeMillis = 40L
            override val touchSlop = 8f
        }
        val events = samples.mapIndexed { index, sample ->
            val previous = samples.getOrNull(index - 1)
            PointerEvent(
                listOf(
                    PointerInputChange(
                        id = PointerId(1),
                        uptimeMillis = sample.time,
                        position = sample.position,
                        pressed = sample.pressed,
                        previousUptimeMillis = previous?.time ?: 0,
                        previousPosition = previous?.position ?: sample.position,
                        previousPressed = previous?.pressed ?: false,
                        isInitiallyConsumed = false,
                    ),
                ),
            )
        }
        override var currentEvent = PointerEvent(emptyList())
            private set
        private var nextEvent = 0
        private var lastPass = PointerEventPass.Final
        private var clock = 0L
        private var deadline: Long? = null
        private var pendingEvent: CancellableContinuation<PointerEvent>? = null

        override suspend fun <R> awaitPointerEventScope(block: suspend AwaitPointerEventScope.() -> R): R =
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { pendingEvent?.cancel(it) }
                block.startCoroutine(this, object : Continuation<R> {
                    override val context = EmptyCoroutineContext
                    override fun resumeWith(result: Result<R>) = continuation.resumeWith(result)
                })
            }

        override suspend fun awaitPointerEvent(pass: PointerEventPass): PointerEvent {
            if (pass.ordinal <= lastPass.ordinal) {
                val next = events.getOrNull(nextEvent)
                val timeout = deadline
                if (timeout != null && (next == null || next.changes.first().uptimeMillis >= timeout)) {
                    clock = timeout
                    throw PointerEventTimeoutCancellationException(timeout)
                }
                if (next == null) return suspendCancellableCoroutine { pendingEvent = it }
                currentEvent = next
                nextEvent++
                clock = next.changes.first().uptimeMillis
            }
            lastPass = pass
            return currentEvent
        }

        override suspend fun <T> withTimeout(timeMillis: Long, block: suspend AwaitPointerEventScope.() -> T): T {
            val previousDeadline = deadline
            deadline = clock + timeMillis
            return try {
                block(this)
            } finally {
                deadline = previousDeadline
            }
        }

        override suspend fun <T> withTimeoutOrNull(timeMillis: Long, block: suspend AwaitPointerEventScope.() -> T): T? =
            try {
                withTimeout(timeMillis, block)
            } catch (_: PointerEventTimeoutCancellationException) {
                null
            }
    }
}
