package app.picacgmd.feature.reader.logic

import kotlin.test.Test
import kotlin.test.assertEquals

class EInkScrollTest {
    @Test fun distanceIsScreenFractionAtEveryZoom() {
        assertEquals(800f, eInkStepDistance(1_000, 0.8f))
        assertEquals(400f, eInkStepDistance(1_000, 0.8f, 2f))
        assertEquals(100f, eInkStepDistance(1_000, 0f))
        assertEquals(1_000f, eInkStepDistance(1_000, 2f))
    }

    @Test fun swipesUseCentimetersNotVelocity() {
        // At 254 dpi, 0.3 cm is exactly 30 pixels.
        assertEquals(0, eInkSwipeStep(-29f, 254f, 0.3f))
        assertEquals(1, eInkSwipeStep(-31f, 254f, 0.3f))
        assertEquals(-1, eInkSwipeStep(31f, 254f, 0.3f))
        assertEquals(0, eInkSwipeStep(-31f, 508f, 0.3f))
    }

    @Test fun rightToLeftReversesHorizontalGesture() {
        assertEquals(1, eInkSwipeStep(31f, 254f, 0.3f, forwardSign = 1))
        assertEquals(-1, eInkSwipeStep(-31f, 254f, 0.3f, forwardSign = 1))
    }
}
