package app.picacgmd.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import app.picacgmd.core.designsystem.component.BackPreviewFrame
import app.picacgmd.core.designsystem.component.BackPreviewState
import app.picacgmd.core.designsystem.component.rememberBackPreviewState
import app.picacgmd.core.designsystem.component.PredictiveBackContent
import app.picacgmd.core.designsystem.theme.DarkMode
import app.picacgmd.core.designsystem.theme.PicaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.testing.ComposeHostRule
import java.util.Locale
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(application = BackTestApplication::class, sdk = [36], qualifiers = "w1080dp-h1920dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavBackPreviewTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private val fixture = Fixture()
    private val input = DirectNavigationEventInput()
    private lateinit var activity: ComponentActivity

    @Test
    fun forwardEnterTrace() {
        host()
        composeRule.runOnIdle { fixture.navController.navigate(MeGraph) }
        var previous = 0L
        for (millis in listOf(0L, 50L, 100L, 200L, 350L)) {
            composeRule.mainClock.advanceTimeBy(millis - previous, ignoreFrameDuration = true)
            previous = millis
            val sample = snapshot(millis)
            println("FORWARD_TRACE millis=$millis pages=${sample.pages} pixels=${sample.pixels} blocking=${fixture.entryBack.isBlocking}")
            val baseline = when (millis) {
                50L -> 129f to 0.13524042f
                100L -> 83f to 0.7167822f
                200L -> 11f to 0.98727185f
                350L -> -2f to 1f
                else -> null
            }
            if (baseline != null) {
                val page = sample.pages.getValue("B")
                // The old seekable clock quantizes a fraction to time; the independent clock differs by at most 1 ms.
                assertEquals(baseline.first, page.translationX, 1f)
                assertEquals(baseline.second, page.frameAlpha * page.hostAlpha, 0.009f)
            }
        }
    }

    @Test
    fun gestureStartedInsideTheHostWindowStaysIgnoredEvenAfterItSettles() {
        host()
        // Zero also pins the gesture whose handler was installed by the outgoing root page.
        for (entryMillis in listOf(0L, 48L, 100L, 150L)) {
            val control = forwardTrace(entryMillis, gesture = false)
            resetToDiscover()
            val actual = forwardTrace(entryMillis, gesture = true)
            control.zip(actual).forEach { (expected, observed) ->
                assertEquals(expected.pages, observed.pages)
                assertEquals(expected.pixels, observed.pixels)
                assertFalse(observed.popping)
            }
            val entryId = fixture.navController.currentBackStackEntry?.id
            composeRule.runOnIdle { fixture.saved.getValue("B").value = 42 }
            val changes = fixture.destinationChanges
            completeGesture()
            assertEquals(changes, fixture.destinationChanges)
            assertEquals(entryId, fixture.navController.currentBackStackEntry?.id)
            assertEquals(42, fixture.saved.getValue("B").value)
            assertFalse(fixture.entryBack.isBlocking)
            assertRest("B")
            beginBack()
            assertSettledPreview(snapshot(0))
            cancelGesture()
            resetToDiscover()
        }
    }

    @Test
    fun backKeyDuringEnterDoesNothingAndTheNextKeyAfterSettlePops() {
        host()
        for (entryMillis in listOf(0L, 48L, 100L, 150L)) {
            enterMe(entryMillis)
            assertTrue(fixture.entryBack.isBlocking)
            val entryId = fixture.navController.currentBackStackEntry?.id
            val changes = fixture.destinationChanges
            pressBackKey()
            assertEquals(changes, fixture.destinationChanges)
            assertEquals(entryId, fixture.navController.currentBackStackEntry?.id)
            assertFalse(activity.isFinishing)
            composeRule.mainClock.advanceTimeBy(1_000)
            assertFalse(fixture.entryBack.isBlocking)
            assertRest("B")
            pressBackKey()
            assertFalse(fixture.preview.held)
            composeRule.mainClock.advanceTimeBy(1_000)
            assertCurrent<DiscoverRoute>()
            assertRest("A")
        }
    }

    @Test
    fun settledPreviewIsUnchangedForEveryHeldSample() {
        host()
        enterMe(800)
        assertFalse(fixture.entryBack.isBlocking)
        beginBack()
        heldSamples().forEach(::assertSettledPreview)
        val before = pose("B")
        val changes = fixture.destinationChanges
        composeRule.runOnIdle { input.backCompleted() }
        composeRule.waitForIdle()
        assertEquals(before, pose("B"))
        composeRule.mainClock.advanceTimeBy(1_000)
        assertEquals(changes + 1, fixture.destinationChanges)
        assertCurrent<DiscoverRoute>()
        assertRest("A")
    }

    @Test
    fun acceptedGestureComposesTheSettledPreviewWithTheRemainingOwnEnter() {
        host()
        enterMe(800)
        beginBack()
        val settled = heldSamples()
        settled.forEach(::assertSettledPreview)
        cancelGesture()
        resetToDiscover()
        for (entryMillis in listOf(160L, 200L, 300L)) {
            enterMe(entryMillis)
            assertFalse(fixture.entryBack.isBlocking)
            val atStart = pose("B")
            println("ENTER_AT_GESTURE entry=$entryMillis offset=${atStart.translationX} alpha=${atStart.frameAlpha}")
            beginBack()
            val samples = heldSamples()
            samples.zip(settled).forEach { (actual, baseline) ->
                val uncovered = actual.pages.getValue("A")
                val leaving = actual.pages.getValue("B")
                val expected = baseline.pages.getValue("B")
                assertEquals(baseline.pages.getValue("A"), uncovered)
                assertEquals(expected.scaleX, leaving.scaleX, 0.000001f)
                assertEquals(expected.scaleY, leaving.scaleY, 0.000001f)
                assertEquals(expected.corner, leaving.corner)
                assertEquals(expected.scrimAlpha, leaving.scrimAlpha)
                assertEquals(expected.hostAlpha, leaving.hostAlpha)
                assertEquals(expected.leavingTrack, leaving.leavingTrack)
                assertEquals(baseline.pixels.first(), actual.pixels.first())
                val remaining = leaving.translationX - expected.translationX
                println("BACK_COMPOSED entry=$entryMillis held=${actual.millis} remainingX=$remaining ownAlpha=${leaving.frameAlpha} scale=${leaving.scaleX} corner=${leaving.corner} backdrop=${actual.pixels.first()}")
                if (actual.millis == 700L) {
                    assertEquals(0f, remaining, 0.001f)
                    assertEquals(1f, leaving.frameAlpha)
                }
            }
            cancelGesture()
            resetToDiscover()
        }
    }

    @Test
    fun acceptedBackAt160MillisCommitsWithoutAPoseJumpAndPopsOnce() {
        host()
        enterMe(160)
        beginBack()
        composeRule.waitForIdle()
        val before = pose("B")
        val changes = fixture.destinationChanges
        composeRule.runOnIdle { input.backCompleted() }
        composeRule.waitForIdle()
        assertEquals(before, pose("B"))
        println("BACK_COMMIT_160 releasePose=$before")
        composeRule.mainClock.advanceTimeBy(1_000)
        assertEquals(changes + 1, fixture.destinationChanges)
        assertCurrent<DiscoverRoute>()
        assertRest("A")
    }

    @Test
    fun acceptedBackAt160MillisCancelsWithoutReplayingEnterOrLosingSavedState() {
        host()
        enterMe(160)
        val entryId = fixture.navController.currentBackStackEntry?.id
        composeRule.runOnIdle { fixture.saved.getValue("B").value = 42 }
        beginBack()
        cancelGesture()
        assertEquals(entryId, fixture.navController.currentBackStackEntry?.id)
        assertEquals(42, fixture.saved.getValue("B").value)
        assertRest("B", allowReturnedPostExit = true)
    }

    @Test
    fun explicitDeepLinkAppearanceDoesNotPlayAnEnter() {
        host()
        composeRule.runOnIdle {
            fixture.skipReaderEnter.value = true
            fixture.navController.navigate(ReaderRoute("deep-link-fixture"))
        }
        composeRule.mainClock.advanceTimeBy(48)
        val page = pose("reader")
        assertEquals(0f, page.translationX)
        assertEquals(1f, page.frameAlpha)
        assertEquals(1f, page.hostAlpha)
        composeRule.mainClock.advanceTimeBy(1_000)
        assertRest("reader")
    }

    @Test
    fun nestedPredictiveContentAt48MillisUsesTheOriginalMapping() = assertNestedPreview(48)

    @Test
    fun nestedPredictiveContentAtRestUsesTheOriginalMapping() = assertNestedPreview(800)

    private fun assertNestedPreview(entryMillis: Long) {
        host()
        enterMe(800)
        composeRule.runOnIdle { fixture.nested.value = true }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle { fixture.nestedCurrent.value = "nestedB" }
        composeRule.mainClock.advanceTimeBy(entryMillis)
        val changes = fixture.destinationChanges
        beginBack()
        val expectedPrevious = Pose(1f, 1f, 0f, 0f, 1f, 1f, 0.25363034f, 0f,
            EnterExitState.PreEnter, EnterExitState.Visible, 0f, 0.35f)
        val expectedLeaving = Pose(0.9207404f, 0.9207405f, 9.511108f, 0f, 1f, 1f, 0f, 22.192654f,
            EnterExitState.Visible, EnterExitState.PostExit, 0.35f, 1f)
        for (sample in heldSamples()) {
            val previous = sample.pages.getValue("nestedA")
            val leaving = sample.pages.getValue("nestedB")
            println("NESTED_ORIGINAL entry=$entryMillis held=${sample.millis} previous=$previous leaving=$leaving")
            assertEquals(expectedPrevious, previous)
            assertEquals(expectedLeaving, leaving)
            assertEquals(listOf(Color(0xFF005FBE).toArgb(), PageColor.toArgb()), sample.pixels)
        }
        cancelGesture()
        val restored = pose("nestedB")
        assertEquals(1f, restored.scaleX)
        assertEquals(0f, restored.translationX)
        assertEquals(1f, restored.frameAlpha)
        assertEquals(1f, restored.hostAlpha)
        assertEquals(0f, restored.scrimAlpha)
        assertEquals(changes, fixture.destinationChanges)
        assertCurrent<MeRoute>()
    }

    @Test
    fun firstAcceptedBackFrameAt1080PixelsIsTheResumedFrame() = measureWindow(1080)

    @Test
    @Config(qualifiers = "w1440dp-h1920dp-mdpi")
    fun firstAcceptedBackFrameAt1440PixelsIsTheResumedFrame() = measureWindow(1440)

    @Test
    fun screenLocalHandlerOutranksTheEntryBlockerAndNavigationHost() {
        host()
        enterMe(48)
        composeRule.runOnIdle { fixture.localPage.value = "B" }
        composeRule.mainClock.advanceTimeByFrame()
        assertTrue(fixture.entryBack.isBlocking)
        beginBack()
        completeGesture()
        assertEquals(1, fixture.localBacks)
        assertCurrent<MeRoute>()
        composeRule.runOnIdle { fixture.localPage.value = null }
        composeRule.mainClock.advanceTimeBy(1_000)
        pressBackKey()
        composeRule.mainClock.advanceTimeBy(1_000)
        assertCurrent<DiscoverRoute>()
    }

    @Test
    fun programmaticForwardCancellationClearsTheWindowAndLeavesNoScrimAtRest() {
        host()
        enterMe(48)
        assertTrue(fixture.entryBack.isBlocking)
        composeRule.runOnIdle { assertTrue(fixture.navController.popBackStack()) }
        assertFalse(fixture.entryBack.isBlocking)
        composeRule.mainClock.advanceTimeBy(1_000)
        assertCurrent<DiscoverRoute>()
        assertRest("A", allowReturnedPostExit = true)
        val returned = snapshot(1_000)
        assertEquals(0f, returned.pages.getValue("A").scrimAlpha)
        assertEquals(listOf(PreviousColor.toArgb(), PreviousColor.toArgb()), returned.pixels)
    }

    @Test
    fun replacingAnEnteringPageTracksTheReplacementRatherThanTheRemovedEntry() {
        host()
        enterMe(48)
        composeRule.runOnIdle {
            fixture.navController.navigate(ReaderRoute("fixture")) {
                popUpTo<MeGraph> { inclusive = true }
            }
        }
        assertTrue(fixture.entryBack.isBlocking)
        pressBackKey()
        assertCurrent<ReaderRoute>()
        composeRule.mainClock.advanceTimeBy(1_000)
        assertFalse(fixture.entryBack.isBlocking)
        assertRest("reader")
        beginBack()
        assertTrue(fixture.preview.popping)
        completeGesture()
        composeRule.mainClock.advanceTimeBy(1_000)
        assertCurrent<DiscoverRoute>()
    }

    @Test
    fun changingTheDeepLinkGraphDoesNotLeaveAnOldEntryWindowEnabled() {
        host()
        enterMe(48)
        composeRule.runOnIdle { fixture.startAtMe.value = true }
        composeRule.mainClock.advanceTimeBy(1_000)
        assertCurrent<MeRoute>()
        assertEquals(null, fixture.navController.previousBackStackEntry)
        assertFalse(fixture.entryBack.isBlocking)
        assertRest("B")
    }

    @Test
    fun stoppingAndRestartingDuringEnterRearmsOnlyTheUnfinishedEntry() {
        host()
        enterMe(48)
        composeRule.runOnIdle { fixture.lifecycle.currentState = Lifecycle.State.CREATED }
        assertFalse(fixture.entryBack.isBlocking)
        composeRule.mainClock.advanceTimeBy(32)
        composeRule.runOnIdle { fixture.lifecycle.currentState = Lifecycle.State.RESUMED }
        assertTrue(fixture.entryBack.isBlocking)
        pressBackKey()
        assertCurrent<MeRoute>()
        composeRule.mainClock.advanceTimeBy(1_000)
        assertFalse(fixture.entryBack.isBlocking)
        assertRest("B")
    }

    @Test
    fun enterCompletingInTheBackgroundDoesNotWaitForeverForResumed() {
        host()
        enterMe(48)
        composeRule.runOnIdle { fixture.lifecycle.currentState = Lifecycle.State.CREATED }
        composeRule.mainClock.advanceTimeBy(1_000)
        assertFalse(fixture.entryBack.isBlocking)
        assertEquals(Lifecycle.State.CREATED, fixture.entries.getValue("B").lifecycle.currentState)
        composeRule.runOnIdle { fixture.lifecycle.currentState = Lifecycle.State.RESUMED }
        assertFalse(fixture.entryBack.isBlocking)
        assertRest("B")
        beginBack()
        assertSettledPreview(snapshot(0))
        cancelGesture()
    }

    @Test
    fun recreationRestoresPageStateWithoutRestoringAnEntryWindow() {
        val restoration = StateRestorationTester(composeRule)
        host(restoration = restoration)
        enterMe(48)
        composeRule.runOnIdle { fixture.saved.getValue("B").value = 42 }
        val old = fixture.entryBack
        // Restoration must apply the teardown composition, not coalesce false/true with a frozen clock.
        composeRule.mainClock.autoAdvance = true
        restoration.emulateSavedInstanceStateRestore()
        assertTrue(old !== fixture.entryBack)
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeByFrame()
        assertEquals(0f, pose("B").translationX)
        assertEquals(1f, pose("B").frameAlpha)
        composeRule.mainClock.advanceTimeBy(1_000)
        assertFalse(old.isBlocking)
        assertFalse(fixture.entryBack.isBlocking)
        assertCurrent<MeRoute>()
        assertEquals(42, fixture.saved.getValue("B").value)
        assertRest("B")
        beginBack()
        assertSettledPreview(snapshot(0))
        cancelGesture()
    }

    @Test
    fun twoQuickBacksDuringPopAreNotMistakenForAForwardEntry() {
        host()
        enterMe(800)
        composeRule.runOnIdle { fixture.navController.navigate(ReaderRoute("fixture")) }
        composeRule.mainClock.advanceTimeBy(800)
        val changes = fixture.destinationChanges
        beginBack()
        completeGesture()
        composeRule.mainClock.advanceTimeByFrame()
        assertFalse(fixture.entryBack.isBlocking)
        beginBack()
        assertFalse(fixture.entryBack.isBlocking)
        completeGesture()
        composeRule.mainClock.advanceTimeBy(1_000)
        assertEquals(changes + 2, fixture.destinationChanges)
        assertCurrent<DiscoverRoute>()
        assertRest("A")
    }

    @Test
    fun entryWithoutAPreviousPageDoesNotInstallAnActiveAppBackHandler() {
        host(startAtMe = true)
        composeRule.mainClock.advanceTimeBy(800)
        val entryId = fixture.navController.currentBackStackEntry?.id
        val changes = fixture.destinationChanges
        assertFalse(fixture.entryBack.isBlocking)
        pressBackKey()
        assertEquals(changes, fixture.destinationChanges)
        assertEquals(entryId, fixture.navController.currentBackStackEntry?.id)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun baselineEarlyTabBackTrace() { tabBackTrace(48, baseline = true) }

    @Test
    fun baselineSettledTabBackTrace() { tabBackTrace(800, baseline = true) }

    @Test
    fun earlyTabBackCancelReturnsToTheSameTabAtRest() { tabBackTrace(48) }

    @Test
    fun currentSettledTabBackTrace() { tabBackTrace(800) }

    @Test
    fun baselineSettledTabGestureClock() { tabGestureClock(baseline = true) }

    @Test
    fun currentSettledTabGestureClock() { tabGestureClock() }

    @Test
    fun baselineSettledTabPlainBackClock() { tabPlainBackClock(baseline = true) }

    @Test
    fun currentSettledTabPlainBackClock() { tabPlainBackClock() }

    @Test
    fun baselineForwardTabTrace() { forwardTabTrace(baseline = true) }

    @Test
    fun currentForwardTabTrace() { forwardTabTrace() }

    private fun tabGestureClock(baseline: Boolean = false) {
        host(compact = false, baselineTabs = baseline)
        enterMe(800)
        composeRule.runOnIdle { input.backStarted(backEvent(0f)) }
        composeRule.mainClock.advanceTimeByFrame()
        for (progress in listOf(0.2f, 0.35f, 0.6f, 0.9f)) {
            composeRule.runOnIdle { input.backProgressed(backEvent(progress)) }
            repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
            val sample = snapshot(0)
            println("TAB_GESTURE_${if (baseline) "BASELINE" else "CURRENT"} progress=$progress duration=${seekDurationMillis()} A=${totalAlpha(sample, "A")} B=${totalAlpha(sample, "B")} pixels=${sample.pixels}")
            assertEquals(300L, seekDurationMillis())
            val expected = when (progress) {
                0.2f -> Triple(0.69155896f, 0.116324276f, Color(0xFF629AE1).toArgb())
                0.35f -> Triple(0.919465f, 0.012179191f, Color(0xFF1788FB).toArgb())
                else -> Triple(1f, 0f, PreviousColor.toArgb())
            }
            assertTabSample(sample, expected.first, expected.second, expected.third)
        }
        cancelGesture()
        assertCurrent<MeRoute>()
    }

    private fun tabPlainBackClock(baseline: Boolean = false) {
        host(compact = false, baselineTabs = baseline)
        enterMe(800)
        val changes = fixture.destinationChanges
        pressBackKey()
        var previous = 0L
        for (millis in listOf(0L, 16L, 32L, 48L, 100L, 200L, 300L, 350L, 400L)) {
            composeRule.mainClock.advanceTimeBy(millis - previous, ignoreFrameDuration = true)
            previous = millis
            val sample = snapshot(millis)
            println("TAB_PLAIN_${if (baseline) "BASELINE" else "CURRENT"} millis=$millis duration=${seekDurationMillis()} A=${totalAlpha(sample, "A")} B=${totalAlpha(sample, "B")} pixels=${sample.pixels}")
            assertEquals(300L, seekDurationMillis())
            val expected = when (millis) {
                0L, 16L, 32L -> Triple(0f, 1f, PageColor.toArgb())
                48L -> Triple(0.13524042f, 0.7407964f, Color(0xFFF56D42).toArgb())
                100L -> Triple(0.7247951f, 0.09567459f, Color(0xFF5899E6).toArgb())
                200L -> Triple(0.98770446f, 0f, Color(0xFF0381FF).toArgb())
                else -> Triple(1f, 0f, PreviousColor.toArgb())
            }
            assertTabSample(sample, expected.first, expected.second, expected.third)
        }
        assertEquals(changes + 1, fixture.destinationChanges)
        assertCurrent<DiscoverRoute>()
        assertTabRest("A")
    }

    private fun forwardTabTrace(baseline: Boolean = false) {
        host(compact = false, baselineTabs = baseline)
        val start = composeRule.mainClock.currentTime
        composeRule.runOnIdle { fixture.navController.navigate(MeGraph) }
        var resumedFrame: Int? = null
        for (frame in 0..30) {
            if (frame > 0) composeRule.mainClock.advanceTimeByFrame()
            val entry = fixture.navController.currentBackStackEntry
            if (entry?.lifecycle?.currentState == Lifecycle.State.RESUMED && resumedFrame == null) resumedFrame = frame
            if (frame in listOf(0, 3, 6, 9, 12, 18, 21, 22, 25, 30)) {
                val elapsed = composeRule.mainClock.currentTime - start
                val sample = snapshot(elapsed)
                println("TAB_FORWARD_${if (baseline) "BASELINE" else "CURRENT"} millis=$elapsed A=${totalAlpha(sample, "A")} B=${totalAlpha(sample, "B")} lifecycle=${entry?.lifecycle?.currentState} pixels=${sample.pixels}")
                val expected = when (frame) {
                    0 -> Triple(1f, 0f, PreviousColor.toArgb())
                    3 -> Triple(0.7407964f, 0.13524042f, Color(0xFF5B93DC).toArgb())
                    6 -> Triple(0.09567459f, 0.7247951f, Color(0xFFF87146).toArgb())
                    9 -> Triple(0f, 0.9378929f, Color(0xFFFF4B0F).toArgb())
                    12 -> Triple(0f, 0.98770446f, Color(0xFFFF4203).toArgb())
                    else -> Triple(0f, 1f, PageColor.toArgb())
                }
                assertTabSample(sample, expected.first, expected.second, expected.third)
                assertEquals(if (frame >= 22) Lifecycle.State.RESUMED else Lifecycle.State.STARTED, entry?.lifecycle?.currentState)
            }
        }
        println("TAB_FORWARD_RESUMED_${if (baseline) "BASELINE" else "CURRENT"} frame=$resumedFrame millis=${resumedFrame?.times(16)}")
        assertEquals(22, resumedFrame)
        assertCurrent<MeRoute>()
        assertTabRest("B")
    }

    private fun totalAlpha(sample: Sample, name: String): Float =
        sample.pages[name]?.let { it.frameAlpha * it.hostAlpha } ?: 0f

    private fun assertTabSample(sample: Sample, a: Float, b: Float, pixel: Int) {
        assertFalse(sample.popping)
        for (page in sample.pages.values) {
            assertEquals(1f, page.scaleX)
            assertEquals(1f, page.scaleY)
            assertEquals(0f, page.translationX)
            assertEquals(0f, page.translationY)
            assertEquals(1f, page.frameAlpha)
            assertEquals(0f, page.scrimAlpha)
            assertEquals(0f, page.corner)
        }
        assertEquals(a, totalAlpha(sample, "A"))
        assertEquals(b, totalAlpha(sample, "B"))
        assertEquals(listOf(pixel, pixel), sample.pixels)
    }

    private fun assertTabRest(name: String) {
        val sample = snapshot(1_000)
        assertTabSample(sample, if (name == "A") 1f else 0f, if (name == "B") 1f else 0f,
            if (name == "A") PreviousColor.toArgb() else PageColor.toArgb())
        assertEquals(EnterExitState.Visible, sample.pages.getValue(name).target)
        assertEquals(Lifecycle.State.RESUMED, fixture.entries.getValue(name).lifecycle.currentState)
    }

    /** Read-only test inspection of the real NavHost's root clock; production never accesses it. */
    private fun seekDurationMillis(): Long = composeRule.runOnIdle {
        var root: Transition<*> = fixture.scopes.getValue("B").transition
        while (true) {
            val parent = root.javaClass.getMethod("getParentTransition").invoke(root) as? Transition<*> ?: break
            root = parent
        }
        root.totalDurationNanos / 1_000_000
    }

    private fun tabBackTrace(entryMillis: Long, baseline: Boolean = false, commit: Boolean = false): List<Sample> {
        host(compact = false, baselineTabs = baseline)
        enterMe(entryMillis)
        assertFalse(fixture.entryBack.isBlocking)
        val entryId = fixture.navController.currentBackStackEntry?.id
        val changes = fixture.destinationChanges
        beginBack()
        var previous = 0L
        val samples = listOf(0L, 16L, 48L, 100L, 300L, 700L).map { held ->
            composeRule.mainClock.advanceTimeBy(held - previous, ignoreFrameDuration = true)
            previous = held
            snapshot(held).also { sample ->
                val a = sample.pages.getValue("A")
                val b = sample.pages.getValue("B")
                println("TAB_${if (baseline) "BASELINE" else "CURRENT"} entry=$entryMillis held=$held A_alpha=${a.hostAlpha * a.frameAlpha} B_alpha=${b.hostAlpha * b.frameAlpha} pages=${sample.pages} pixels=${sample.pixels}")
                if (entryMillis == 48L) {
                    val expectedA = when (held) {
                        0L -> 0.5666444f
                        16L -> 0.55743384f
                        else -> 0.55140465f
                    }
                    val pixel = when (held) {
                        0L -> Color(0xFF6EB7FF).toArgb()
                        16L -> Color(0xFF70B8FF).toArgb()
                        else -> Color(0xFF72B9FF).toArgb()
                    }
                    assertTabSample(sample, expectedA, 0f, pixel)
                } else {
                    assertTabSample(sample, 0.919465f, 0.012179191f, Color(0xFF1788FB).toArgb())
                }
                // Interrupted tab history also changes the baseline root duration while held.
                println("TAB_DURATION_${if (baseline) "BASELINE" else "CURRENT"} entry=$entryMillis held=$held millis=${seekDurationMillis()}")
                val expectedDuration = if (entryMillis == 48L) when (held) { 0L -> 360L; 16L -> 362L; else -> 363L } else 300L
                assertEquals(expectedDuration, seekDurationMillis())
            }
        }
        if (commit) {
            completeGesture()
            composeRule.mainClock.advanceTimeBy(1_000)
            assertEquals(changes + 1, fixture.destinationChanges)
            assertCurrent<DiscoverRoute>()
            assertTabRest("A")
        } else {
            cancelGesture()
            assertEquals(changes, fixture.destinationChanges)
            assertEquals(entryId, fixture.navController.currentBackStackEntry?.id)
            assertCurrent<MeRoute>()
            assertTabRest("B")
        }
        return samples
    }

    @Test
    fun earlyTabBackCommitsOnceAndEndsAtTheStartTab() {
        // As in 1.1.0, an early Back retains the tab fade values; its intermediate alpha is not a settled pop.
        tabBackTrace(48, commit = true)
    }

    @Test
    fun railTabSwitchIsNotBlockedAndTheReadersLocalHandlerStillWins() {
        host(compact = false)
        enterMe(48)
        assertFalse(fixture.entryBack.isBlocking)
        beginBack()
        assertFalse(fixture.preview.popping)
        val page = pose("B")
        assertEquals(1f, page.scaleX)
        assertEquals(0f, page.translationX)
        assertEquals(0f, page.scrimAlpha)
        cancelGesture()
        composeRule.runOnIdle { fixture.navController.navigate(ReaderRoute("fixture")) }
        composeRule.mainClock.advanceTimeBy(48)
        assertTrue(fixture.entryBack.isBlocking)
        composeRule.runOnIdle { fixture.localPage.value = "reader" }
        composeRule.mainClock.advanceTimeByFrame()
        pressBackKey()
        assertEquals(1, fixture.localBacks)
        assertCurrent<ReaderRoute>()
        composeRule.runOnIdle { fixture.localPage.value = null }
        composeRule.mainClock.advanceTimeBy(1_000)
        assertFalse(fixture.entryBack.isBlocking)
        beginBack()
        assertTrue(fixture.preview.popping)
        assertEquals(0.920740f, pose("reader").scaleX, 0.000001f)
        cancelGesture()
    }

    private fun measureWindow(width: Int) {
        host(width = width)
        val start = composeRule.mainClock.currentTime
        composeRule.runOnIdle { fixture.navController.navigate(MeGraph) }
        assertTrue(fixture.entryBack.isBlocking)
        var frames = 0
        while (fixture.entryBack.isBlocking && frames < 80) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            frames++
        }
        assertFalse(fixture.entryBack.isBlocking)
        assertEquals(Lifecycle.State.RESUMED, fixture.entries.getValue("B").lifecycle.currentState)
        println("BACK_WINDOW width=$width firstAcceptedFrame=$frames firstAcceptedMs=${composeRule.mainClock.currentTime - start}")
        beginBack()
        assertTrue(fixture.preview.popping)
        assertEquals(EnterExitState.PreEnter, fixture.scopes.getValue("A").transition.currentState)
        assertEquals(0.920740f, pose("B").scaleX, 0.000001f)
        cancelGesture()
    }

    private fun host(
        compact: Boolean = true,
        startAtMe: Boolean = false,
        width: Int = 1080,
        restoration: StateRestorationTester? = null,
        baselineTabs: Boolean = false,
    ) {
        fixture.width = width
        fixture.compact = compact
        fixture.baselineTabs = baselineTabs
        fixture.startAtMe.value = startAtMe
        composeRule.runOnIdle { fixture.lifecycle.currentState = Lifecycle.State.RESUMED }
        val content: @Composable () -> Unit = {
            activity = LocalActivity.current as ComponentActivity
            fixture.dispatcher = requireNotNull(LocalNavigationEventDispatcherOwner.current).navigationEventDispatcher
            CompositionLocalProvider(LocalLifecycleOwner provides fixture.owner) {
                PicaTheme(themeChoice = ThemeChoice.Brand, darkMode = DarkMode.Light) {
                    fixture.navController = rememberNavController()
                    fixture.preview = rememberBackPreviewState()
                    val compactState = remember { mutableStateOf(compact) }
                    fixture.entryBack = rememberForwardEntryBackState(fixture.navController, compactState, fixture.preview)
                    val specs = if (baselineTabs) {
                        baselineTransitions(compactState, fixture.preview)
                    } else {
                        picaNavTransitions(compactState, fixture.preview, fixture.entryBack) {
                            fixture.skipReaderEnter.value && it.destination.hasRoute<ReaderRoute>()
                        }
                    }
                    DisposableEffect(fixture.navController) {
                        val listener = NavController.OnDestinationChangedListener { _, _, _ -> fixture.destinationChanges++ }
                        fixture.navController.addOnDestinationChangedListener(listener)
                        onDispose { fixture.navController.removeOnDestinationChangedListener(listener) }
                    }
                    Box(Modifier.requiredSize(width.dp, 1920.dp).background(Color.White).testTag("host")) {
                        NavHost(
                            navController = fixture.navController,
                            startDestination = if (fixture.startAtMe.value) MeGraph else DiscoverGraph,
                            modifier = Modifier.fillMaxSize(),
                            enterTransition = { record("enter", specs.enter) },
                            exitTransition = { record("exit", specs.exit) },
                            popEnterTransition = { record("popEnter", specs.popEnter) },
                            popExitTransition = { record("popExit", specs.popExit) },
                            predictivePopEnterTransition = { record("predictivePopEnter", specs.popEnter) },
                            predictivePopExitTransition = { record("predictivePopExit", specs.popExit) },
                        ) {
                            navigation<DiscoverGraph>(startDestination = DiscoverRoute) {
                                composable<DiscoverRoute> { Page("A", it, PreviousColor) }
                            }
                            navigation<MeGraph>(startDestination = MeRoute) {
                                composable<MeRoute> { Page("B", it, PageColor) }
                            }
                            composable<ReaderRoute> { Page("reader", it, Color(0xFF00AA66)) }
                        }
                    }
                }
            }
        }
        if (restoration == null) composeRule.setContent(content) else restoration.setContent(content)
        composeRule.runOnIdle { fixture.dispatcher.addInput(input) }
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeByFrame()
    }

    @Composable
    private fun AnimatedContentScope.Page(name: String, entry: NavBackStackEntry, color: Color) {
        ForwardEntryBackBlocker(fixture.entryBack)
        fixture.scopes[name] = this
        fixture.entries[name] = entry
        fixture.saved[name] = rememberSaveable { mutableStateOf(7) }
        DisposableEffect(name) {
            fixture.active += name
            onDispose { fixture.active -= name }
        }
        val pageContent: @Composable () -> Unit = {
            BackHandler(enabled = fixture.localPage.value == name) { fixture.localBacks++ }
            Box(Modifier.fillMaxSize().background(color).testTag(name).onGloballyPositioned { fixture.coordinates[name] = it }) {
                if (name == "B" && fixture.nested.value) {
                    PredictiveBackContent(
                        current = fixture.nestedCurrent.value,
                        previous = "nestedA",
                        onBack = { fixture.nestedCurrent.value = "nestedA" },
                        enabled = fixture.nestedCurrent.value == "nestedB",
                    ) { page, _ ->
                        DisposableEffect(page) {
                            fixture.active += page
                            onDispose { fixture.active -= page }
                        }
                        Box(
                            Modifier.fillMaxSize().background(if (page == "nestedA") PreviousColor else PageColor)
                                .testTag(page).onGloballyPositioned { fixture.coordinates[page] = it },
                        )
                    }
                }
            }
        }
        if (fixture.baselineTabs) BaselineTabFrame(entry.id, pageContent)
        else BackPreviewFrame(fixture.preview, entryKey = entry.id, content = pageContent)
    }

    /** The c7b87ad frame's tab branch: no preview transform, but both 300 ms tracks remain attached. */
    @Composable
    private fun AnimatedContentScope.BaselineTabFrame(entryKey: Any, content: @Composable () -> Unit) {
        transition.animateFloat(transitionSpec = { tween(300, easing = LinearEasing) }, label = "backLeaving") {
            if (it == EnterExitState.PostExit) 1f else 0f
        }
        transition.animateFloat(transitionSpec = { tween(300, easing = LinearEasing) }, label = "backUncovering") {
            if (it == EnterExitState.PreEnter) 0f else 1f
        }
        DisposableEffect(entryKey) {
            fixture.preview.clearAppearance()
            onDispose {}
        }
        val colors = MaterialTheme.colorScheme
        Box(Modifier.fillMaxSize().graphicsLayer { clip = true }.background(colors.surface)) {
            content()
            Box(Modifier.matchParentSize().graphicsLayer { alpha = 0f }.background(colors.scrim))
        }
    }

    /** Own-project baseline specs, kept separate from the candidate's transition factories. */
    @Composable
    private fun baselineTransitions(compact: State<Boolean>, preview: BackPreviewState): PicaNavTransitions {
        val motion = MaterialTheme.motionScheme
        val spatial = motion.defaultSpatialSpec<IntOffset>()
        val fade = motion.defaultEffectsSpec<Float>()
        val fastFade = motion.fastEffectsSpec<Float>()
        return PicaNavTransitions(
            enter = { if (tabSwitch(compact.value)) fadeIn(fade) else slideInHorizontally(spatial) { it / 8 } + fadeIn(fade) },
            exit = {
                preview.popping = false
                if (tabSwitch(compact.value)) fadeOut(fastFade) else slideOutHorizontally(spatial) { -it / 16 } + fadeOut(fastFade)
            },
            popEnter = { if (tabSwitch(compact.value)) fadeIn(fade) else EnterTransition.None },
            popExit = {
                preview.popping = !tabSwitch(compact.value)
                if (tabSwitch(compact.value)) fadeOut(fastFade) else ExitTransition.None
            },
        )
    }

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabSwitch(compact: Boolean): Boolean {
        val from = initialState.destination.topLevelDestination()?.takeIf { !compact || it.inBottomBar }
        val to = targetState.destination.topLevelDestination()?.takeIf { !compact || it.inBottomBar }
        return from != null && to != null && from != to
    }

    private fun <T> AnimatedContentTransitionScope<NavBackStackEntry>.record(
        name: String,
        spec: AnimatedContentTransitionScope<NavBackStackEntry>.() -> T,
    ): T {
        val result = spec(this)
        if (name == "enter" || name == "exit") {
            val from = initialState.destination.topLevelDestination()?.takeIf { !fixture.compact || it.inBottomBar }
            val to = targetState.destination.topLevelDestination()?.takeIf { !fixture.compact || it.inBottomBar }
            // Early tab backs may reselect forward specs; both directions use the same fades.
            if (from == null || to == null || from == to) {
                assertFalse(fixture.navController.currentBackStackEntry?.id == initialState.id &&
                    fixture.navController.previousBackStackEntry?.id == targetState.id)
            }
        }
        fixture.calls += name
        return result
    }

    private fun enterMe(millis: Long) {
        fixture.calls.clear()
        composeRule.runOnIdle { fixture.navController.navigate(MeGraph) }
        composeRule.mainClock.advanceTimeBy(millis, ignoreFrameDuration = true)
        assertCurrent<MeRoute>()
    }

    private fun forwardTrace(entryMillis: Long, gesture: Boolean): List<Sample> {
        enterMe(entryMillis)
        assertTrue(fixture.entryBack.isBlocking)
        if (gesture) beginBack() else repeat(3) { composeRule.mainClock.advanceTimeByFrame() }
        return heldSamples().also { samples ->
            samples.forEach { sample ->
                println("BACK_IGNORED entry=$entryMillis gesture=$gesture held=${sample.millis} pages=${sample.pages} pixels=${sample.pixels}")
            }
            assertTrue(fixture.calls.none { it.startsWith("pop") || it.startsWith("predictive") })
        }
    }

    private fun heldSamples(): List<Sample> {
        var previous = 0L
        return listOf(0L, 100L, 300L, 700L).map { millis ->
            composeRule.mainClock.advanceTimeBy(millis - previous, ignoreFrameDuration = true)
            previous = millis
            snapshot(millis)
        }
    }

    private fun beginBack() {
        composeRule.runOnIdle { input.backStarted(backEvent(0f)) }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { input.backProgressed(backEvent(0.35f)) }
        repeat(2) { composeRule.mainClock.advanceTimeByFrame() }
    }

    private fun completeGesture() {
        composeRule.runOnIdle { input.backCompleted() }
        composeRule.mainClock.advanceTimeByFrame()
    }

    private fun cancelGesture() {
        composeRule.runOnIdle { input.backCancelled() }
        composeRule.mainClock.advanceTimeBy(1_000)
    }

    private fun pressBackKey() = composeRule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }

    private fun resetToDiscover() {
        composeRule.runOnIdle { assertTrue(fixture.navController.popBackStack()) }
        composeRule.mainClock.advanceTimeBy(1_000)
        assertCurrent<DiscoverRoute>()
    }

    private inline fun <reified T : Any> assertCurrent() {
        composeRule.runOnIdle { assertTrue(fixture.navController.currentDestination?.hasRoute<T>() == true) }
    }

    private fun snapshot(millis: Long): Sample {
        composeRule.waitForIdle()
        val pages = composeRule.runOnIdle { fixture.active.associateWith(::pose) }
        val image = composeRule.onNodeWithTag("host").captureToImage().toPixelMap()
        return Sample(millis, pages, listOf(image[2, 960].toArgb(), image[fixture.width / 2, 960].toArgb()), fixture.preview.popping)
    }

    private fun assertSettledPreview(sample: Sample) {
        val previous = sample.pages.getValue("A")
        val leaving = sample.pages.getValue("B")
        assertTrue(sample.popping)
        assertEquals(EnterExitState.PreEnter, previous.current)
        assertEquals(EnterExitState.Visible, previous.target)
        assertEquals(1f, previous.scaleX)
        assertEquals(0f, previous.translationX)
        assertEquals(1f, previous.hostAlpha)
        assertEquals(0.253630f, previous.scrimAlpha, 0.000001f)
        assertEquals(0.920740f, leaving.scaleX, 0.000001f)
        assertEquals(22.1928f, leaving.corner, 0.001f)
        assertEquals(9.511f, leaving.translationX, 0.001f)
        assertEquals(1f, leaving.frameAlpha)
        assertEquals(1f, leaving.hostAlpha)
        assertEquals(0f, leaving.scrimAlpha)
        assertEquals(0.35f, leaving.leavingTrack)
        assertEquals(0.35f, previous.uncoveringTrack)
        assertEquals(listOf(Color(0xFF005FBE).toArgb(), PageColor.toArgb()), sample.pixels)
        println(String.format(Locale.ROOT, "BACK_SETTLED held=%d A_scrim=%.6f B_scale=%.6f B_x=%.3f pixels=%s", sample.millis, previous.scrimAlpha, leaving.scaleX, leaving.translationX, sample.pixels))
    }

    private fun assertRest(name: String, allowReturnedPostExit: Boolean = false) {
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val page = pose(name)
            println("BACK_REST $name pose=$page running=${fixture.scopes.getValue(name).transition.isRunning} lifecycle=${fixture.entries.getValue(name).lifecycle.currentState}")
            assertEquals(1f, page.scaleX)
            assertEquals(1f, page.scaleY)
            assertEquals(0f, page.translationX)
            assertEquals(0f, page.translationY)
            assertEquals(1f, page.frameAlpha)
            assertEquals(1f, page.hostAlpha)
            assertEquals(0f, page.scrimAlpha)
            assertEquals(0f, page.corner)
            if (allowReturnedPostExit) {
                assertTrue(page.current == EnterExitState.Visible || page.current == EnterExitState.PostExit)
            } else {
                assertEquals(EnterExitState.Visible, page.current)
            }
            assertEquals(EnterExitState.Visible, page.target)
            // A returned PostExit child can retain its label/running flag after NavHost has resumed the entry.
            if (!allowReturnedPostExit || page.current == EnterExitState.Visible) {
                assertFalse(fixture.scopes.getValue(name).transition.isRunning)
            }
            assertEquals(Lifecycle.State.RESUMED, fixture.entries.getValue(name).lifecycle.currentState)
        }
    }

    private fun pose(name: String): Pose {
        val coordinates = fixture.coordinates.getValue(name)
        val topLeft = coordinates.localToRoot(Offset.Zero)
        val bottomRight = coordinates.localToRoot(Offset(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
        val contentInfo = composeRule.onNodeWithTag(name).fetchSemanticsNode().layoutInfo
        val frameInfo = requireNotNull(contentInfo.parentInfo)
        val frameLayer = layers(frameInfo).single()
        val children = frameInfo.javaClass.methods.first { it.name.startsWith("getChildren") && it.parameterCount == 0 }
            .invoke(frameInfo) as List<*>
        val transition = fixture.scopes[name]?.transition ?: nestedTransition(frameInfo)
        return Pose(
            scaleX = (bottomRight.x - topLeft.x) / coordinates.size.width,
            scaleY = (bottomRight.y - topLeft.y) / coordinates.size.height,
            translationX = (topLeft.x + bottomRight.x) / 2f - fixture.width / 2f,
            translationY = (topLeft.y + bottomRight.y) / 2f - 960f,
            frameAlpha = frameLayer.alpha,
            hostAlpha = coordinateLayers(coordinates).filter { it !== frameLayer }.fold(1f) { alpha, layer -> alpha * layer.alpha },
            scrimAlpha = layers(children.last() as LayoutInfo).single().alpha,
            corner = (frameLayer.outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f,
            current = transition.currentState,
            target = transition.targetState,
            leavingTrack = transition.animations.first { it.label == "backLeaving" }.value as Float,
            uncoveringTrack = transition.animations.first { it.label == "backUncovering" }.value as Float,
        )
    }

    private fun layers(info: LayoutInfo): List<GraphicsLayer> = info.getModifierInfo().mapNotNull { modifier ->
        modifier.extra?.let { layer ->
            val field = layer.javaClass.declaredFields.first { it.name == "graphicsLayer" }
            field.isAccessible = true
            field.get(layer) as GraphicsLayer
        }
    }

    private fun coordinateLayers(coordinates: LayoutCoordinates): List<GraphicsLayer> =
        generateSequence(coordinates) { it.parentCoordinates }.mapNotNull { coordinator ->
            val field = generateSequence(coordinator.javaClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }.firstOrNull { it.name == "layer" } ?: return@mapNotNull null
            field.isAccessible = true
            val layer = field.get(coordinator) ?: return@mapNotNull null
            val graphics = layer.javaClass.declaredFields.first { it.name == "graphicsLayer" }
            graphics.isAccessible = true
            graphics.get(layer) as GraphicsLayer
        }.distinct().toList()

    @Suppress("UNCHECKED_CAST")
    private fun nestedTransition(frame: LayoutInfo): Transition<EnterExitState> {
        val modifier = generateSequence(frame) { it.parentInfo }.flatMap { it.getModifierInfo().asSequence() }
            .first { it.modifier.javaClass.simpleName == "EnterExitTransitionElement" }.modifier
        val field = modifier.javaClass.getDeclaredField("transition")
        field.isAccessible = true
        return field.get(modifier) as Transition<EnterExitState>
    }

    private fun backEvent(progress: Float) = NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = progress, touchY = 960f)

    private class HostLifecycleOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private class Fixture {
        lateinit var navController: NavHostController
        lateinit var dispatcher: NavigationEventDispatcher
        lateinit var preview: BackPreviewState
        lateinit var entryBack: ForwardEntryBackState
        val owner = HostLifecycleOwner()
        val lifecycle get() = owner.lifecycle
        val startAtMe = mutableStateOf(false)
        val localPage = mutableStateOf<String?>(null)
        val skipReaderEnter = mutableStateOf(false)
        val nested = mutableStateOf(false)
        val nestedCurrent = mutableStateOf("nestedA")
        var localBacks = 0
        var destinationChanges = 0
        var width = 1080
        var compact = true
        var baselineTabs = false
        val active = mutableSetOf<String>()
        val coordinates = mutableMapOf<String, LayoutCoordinates>()
        val scopes = mutableMapOf<String, AnimatedContentScope>()
        val entries = mutableMapOf<String, NavBackStackEntry>()
        val saved = mutableMapOf<String, MutableState<Int>>()
        val calls = mutableListOf<String>()
    }

    private data class Sample(val millis: Long, val pages: Map<String, Pose>, val pixels: List<Int>, val popping: Boolean)

    private data class Pose(
        val scaleX: Float,
        val scaleY: Float,
        val translationX: Float,
        val translationY: Float,
        val frameAlpha: Float,
        val hostAlpha: Float,
        val scrimAlpha: Float,
        val corner: Float,
        val current: EnterExitState,
        val target: EnterExitState,
        val leavingTrack: Float,
        val uncoveringTrack: Float,
    )

    private companion object {
        val PreviousColor = Color(0xFF0080FF)
        val PageColor = Color(0xFFFF4000)
    }
}
