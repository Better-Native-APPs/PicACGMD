package app.picacgmd.feature.reader.logic

import app.picacgmd.feature.reader.ReaderDirection
import app.picacgmd.feature.reader.ReaderMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChapterPullTest {
    @Test
    fun stripsAlwaysReadDownward() {
        for (direction in ReaderDirection.entries) {
            assertEquals(ReaderDirection.TopToBottom, effectiveDirection(ReaderMode.Strip, direction))
            assertEquals(ReaderDirection.TopToBottom, effectiveDirection(ReaderMode.FreeZoom, direction))
        }
    }

    @Test
    fun spreadsCannotReadDownward() {
        assertEquals(
            ReaderDirection.LeftToRight,
            effectiveDirection(ReaderMode.DoublePage, ReaderDirection.TopToBottom),
        )
        assertEquals(
            ReaderDirection.RightToLeft,
            effectiveDirection(ReaderMode.DoublePage, ReaderDirection.RightToLeft),
        )
    }

    @Test
    fun everyEffectiveDirectionIsOfferedInSettings() {
        for (mode in ReaderMode.entries) {
            for (direction in ReaderDirection.entries) {
                assertTrue(effectiveDirection(mode, direction) in availableDirections(mode))
            }
        }
    }

    @Test
    fun forwardFollowsTheFingerForEachDirection() {
        assertEquals(40f, forwardComponent(0f, -40f, forwardSwipeFor(ReaderDirection.TopToBottom)))
        assertEquals(40f, forwardComponent(-40f, 0f, forwardSwipeFor(ReaderDirection.LeftToRight)))
        assertEquals(40f, forwardComponent(40f, 0f, forwardSwipeFor(ReaderDirection.RightToLeft)))
    }

    @Test
    fun backwardMovesAreNegative() {
        assertEquals(-25f, forwardComponent(0f, 25f, ForwardSwipe.Up))
        assertEquals(-25f, forwardComponent(25f, 0f, ForwardSwipe.Left))
        assertEquals(-25f, forwardComponent(-25f, 0f, ForwardSwipe.Right))
    }

    @Test
    fun onlyForwardLeftoversPull() {
        assertEquals(30f, pullAfterLeftover(10f, 20f))
        assertEquals(10f, pullAfterLeftover(10f, 0f))
        assertEquals(10f, pullAfterLeftover(10f, -20f))
    }

    @Test
    fun movingBackGivesThePullBackBeforeTheListMoves() {
        assertEquals(15f, pullGivenBack(20f, -15f))
        assertEquals(20f, pullGivenBack(20f, -50f))
        assertEquals(0f, pullGivenBack(20f, 15f))
        assertEquals(0f, pullGivenBack(0f, -15f))
    }

    @Test
    fun fingerMovementInvertsTheForwardComponent() {
        for (swipe in ForwardSwipe.entries) {
            val (x, y) = fingerMovement(-12f, swipe)
            assertEquals(-12f, forwardComponent(x, y, swipe))
        }
    }

    @Test
    fun releasingPastTheThresholdLeaves() {
        assertTrue(shouldLeaveChapter(pull = 300f, threshold = 300f, velocity = 0f, flickVelocity = 3000f))
        assertFalse(shouldLeaveChapter(pull = 299f, threshold = 300f, velocity = 0f, flickVelocity = 3000f))
    }

    @Test
    fun aFlickNeedsSomePullAndSomeSpeed() {
        assertTrue(shouldLeaveChapter(pull = 75f, threshold = 300f, velocity = 3000f, flickVelocity = 3000f))
        assertFalse(shouldLeaveChapter(pull = 74f, threshold = 300f, velocity = 9000f, flickVelocity = 3000f))
        assertFalse(shouldLeaveChapter(pull = 150f, threshold = 300f, velocity = 2999f, flickVelocity = 3000f))
        assertFalse(shouldLeaveChapter(pull = 150f, threshold = 300f, velocity = -9000f, flickVelocity = 3000f))
    }

    @Test
    fun aFlingWithoutPullNeverLeaves() {
        assertFalse(shouldLeaveChapter(pull = 0f, threshold = 300f, velocity = 9000f, flickVelocity = 3000f))
    }
}
