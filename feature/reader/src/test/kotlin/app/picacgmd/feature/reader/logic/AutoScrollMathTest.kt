package app.picacgmd.feature.reader.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoScrollMathTest {
    @Test
    fun speedStepsRiseAndClamp() {
        val speeds = (1..5).map(AutoScrollSpeed::dpPerSecond)
        assertEquals(speeds.sorted(), speeds)
        assertEquals(speeds.toSet().size, speeds.size)
        assertEquals(AutoScrollSpeed.dpPerSecond(1), AutoScrollSpeed.dpPerSecond(0))
        assertEquals(AutoScrollSpeed.dpPerSecond(5), AutoScrollSpeed.dpPerSecond(9))
    }

    @Test
    fun deltaFollowsFrameTime() {
        assertEquals(1.6f, autoScrollDelta(100f, 16_000_000L, Float.MAX_VALUE), 1e-4f)
    }

    @Test
    fun longFramesAreCapped() {
        assertEquals(5f, autoScrollDelta(100f, 2_000_000_000L, Float.MAX_VALUE), 1e-4f)
    }

    @Test
    fun stopsAtTheEnd() {
        assertEquals(0.5f, autoScrollDelta(100f, 16_000_000L, 0.5f), 1e-4f)
        assertEquals(0f, autoScrollDelta(100f, 16_000_000L, 0f))
        assertEquals(0f, autoScrollDelta(100f, 16_000_000L, -3f))
        assertTrue(autoScrollDelta(0f, 16_000_000L, 10f) == 0f)
    }
}
