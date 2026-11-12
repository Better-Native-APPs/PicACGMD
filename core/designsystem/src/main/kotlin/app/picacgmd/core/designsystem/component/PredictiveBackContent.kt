package app.picacgmd.core.designsystem.component

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/*
 * Back preview, in the manner of the system one: the leaving page follows the finger. The further
 * the drag, the smaller the page (down to 90 % around its centre), the rounder its corners, the
 * darker the page it uncovers, and the further it leans the way the finger goes. Letting go either
 * finishes the back (the small page fades away together with the dimming) or brings everything
 * back.
 *
 * How it is driven
 *
 * The host's transition is the only clock. Navigation seeks it with the raw gesture progress,
 * continues it to the end when the back is committed and seeks it back to 0 when it is cancelled;
 * a plain back press simply plays it. Each page reads that clock through one linear track and
 * derives every visual from it in a layer block:
 *
 *   t      = host timeline, 0..1 (the gesture progress while the finger is down)
 *   eased  = BackGestureEasing(t)        raw progress never reaches the screen
 *   scale  = 1 - 0.1 * eased             leaving page, centre origin
 *   corner = 28 dp * eased               leaving page
 *   shift  = +-12 dp * eased             leaving page, see PREVIEW_FOLLOW_GESTURE
 *   scrim  = max * eased * (1 - gone)    page underneath
 *   alpha  = 1 - gone                    leaving page
 *   gone   = 0 while the finger is down (and on the way back after a cancel); after the release
 *            at t = p it is (t - p) / (1 - p), so fading takes exactly what is left of the timeline
 *
 * The host itself adds no motion of its own on a pop (see [predictiveBackExit]): there is one
 * mapping, in one place, and no second set of tweens that could drift away from it.
 *
 * Everything that moves per frame is a layer property (scale, translation, alpha, outline). A
 * gesture never recomposes, remeasures or redraws page content. No shadow: an elevated full-screen
 * layer whose outline changes every frame has its shadow rebuilt every frame.
 */

/**
 * Google's back-gesture interpolator, the one SystemUI uses. Progress is linear in the drag
 * distance and must be eased before it is shown: most of the movement happens early in the drag
 * and it settles softly, which is what makes the page feel attached to the finger.
 */
private val BackGestureEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

/**
 * Length of the pop timeline. It only matters when the timeline plays by itself (back press, or
 * the rest of it after a release); a gesture seeks it. The tracks below are linear over exactly
 * this length, so their value is the timeline fraction: nothing longer may be attached to a pop
 * transition, or the tracks would run out before the gesture does.
 */
private const val BACK_PREVIEW_MILLIS = 300

/** Google's exit scale for a full-screen surface: 100 % to 90 %. */
private const val EXIT_SCALE = 0.9f

/** Our own value; Google publishes none. */
private val PreviewCorner = 28.dp
private const val SCRIM_ALPHA_LIGHT = 0.32f
private const val SCRIM_ALPHA_DARK = 0.5f

/**
 * The one-line switch for the sideways lean: false keeps the page exactly centred.
 *
 * On by the user's decision of 2026-11-12 ("centre scale plus a slight follow"), which deliberately
 * reverses the 2026-11-10 "no sideways movement" decision. It is kept slight on purpose: at most
 * [PreviewFollowMax], a quarter of Google's shared-element shift ((screen width / 20) - 8 dp, about
 * 46 dp on a 1080 px screen), which was the kind of movement that got rejected before.
 */
private const val PREVIEW_FOLLOW_GESTURE = true
private val PreviewFollowMax = 12.dp

/**
 * Which way the page leans: 1 right, -1 left, 0 not at all. It goes with the finger, as the system
 * preview does: a swipe from the left edge travels right. A back that is not a swipe (button,
 * keyboard) has no edge and no lean. Swap the two signs to lean towards the starting edge instead.
 *
 * Not mirrored for right-to-left layouts, and it must not be: the swipe edge is a physical edge of
 * the screen and a layer's `translationX` is in physical pixels, so the two already agree.
 */
private fun followDirection(swipeEdge: Int): Float = when (swipeEdge) {
    NavigationEvent.EDGE_LEFT -> 1f
    NavigationEvent.EDGE_RIGHT -> -1f
    else -> 0f
}

/**
 * What the host contributes to a pop: nothing but time. The leaving page stays composed until the
 * host's transition ends, and [BackPreviewFrame] moves it. A `scaleOut`/`fadeOut` here would be a
 * second copy of the mapping with its own tweens, seeked alongside the frame's.
 */
fun predictiveBackExit(): ExitTransition = ExitTransition.None

/**
 * What every [BackPreviewFrame] of one host needs to know about the back in flight. All of it is
 * read in layer blocks only.
 */
@Stable
class BackPreviewState internal constructor() {
    /**
     * Set by every transition spec of the host: true for a pop, false for anything else, so that
     * forward navigation and tab switches leave the frames idle. Specs are built in the composition
     * that follows a target change, which is before the first frame of the transition whether it
     * is played or seeked. The value goes stale once a transition is over (a cancelled gesture
     * leaves it true); that is harmless, a page at rest sits at t = 0.
     */
    var popping: Boolean by mutableStateOf(false)

    /** A finger is holding a back gesture right now. */
    var held: Boolean by mutableStateOf(false)
        internal set

    /** [followDirection] of the latest gesture; it outlives the release so the page settles where it leans. */
    var follow: Float by mutableFloatStateOf(0f)
        internal set

    internal fun onGesture(gesture: NavigationEventTransitionState) {
        if (gesture is NavigationEventTransitionState.InProgress &&
            gesture.direction == NavigationEventTransitionState.TRANSITIONING_BACK
        ) {
            follow = followDirection(gesture.latestEvent.swipeEdge)
            held = true
        } else {
            held = false
        }
    }
}

/**
 * A [BackPreviewState] that knows about the finger. Navigation 2.9 tells a host neither that a
 * gesture is being held nor which edge it came from, so both are taken from the activity's
 * navigation event dispatcher. Every back gesture passes through it whoever handles it, and
 * watching it does not take the gesture away from its handler.
 */
@Composable
fun rememberBackPreviewState(): BackPreviewState {
    val state = remember { BackPreviewState() }
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    LaunchedEffect(state, dispatcher) {
        dispatcher?.transitionState?.collect(state::onGesture)
    }
    return state
}

/**
 * Remembers where on the timeline the finger let go, which is where fading starts. A layer block
 * feeds it on every pass; it is plain memory, not snapshot state, so it invalidates nothing and
 * can never be a frame behind the values it is used with.
 */
private class ReleasePoint {
    private var at = 0f
    private var following = true

    /** A finger has held the pop this page is in. */
    var gestured = false
        private set

    /** The `gone` of the file comment for timeline position [t]. */
    fun gone(held: Boolean, t: Float): Float {
        when {
            held -> {
                at = t
                following = true
                gestured = true
            }
            // At rest, also after a cancelled gesture: the next back starts from scratch.
            t <= 0f -> {
                at = 0f
                following = true
                gestured = false
            }
            // First pass after the release, or the first frame of a back that was never held.
            following -> {
                at = t
                following = false
            }
        }
        // A drag that used up the whole timeline leaves nothing to fade over.
        return if (held || at >= 1f) 0f else ((t - at) / (1f - at)).coerceIn(0f, 1f)
    }
}

/**
 * Wraps one page of an animated host and gives it its part in the back preview: the page that is
 * leaving shrinks, rounds, leans and finally fades; the page that is being uncovered is dimmed.
 * The page itself is transformed and clipped, not the host, so the page underneath stays full-size
 * and in place.
 *
 * [state] and the animated values are only read inside layer blocks: a back gesture updates layer
 * properties and never recomposes or redraws the page content.
 */
@Composable
fun AnimatedVisibilityScope.BackPreviewFrame(state: BackPreviewState, content: @Composable () -> Unit) {
    // Linear on purpose: these are the host timeline as seen from this page, not a motion curve.
    val leaving = transition.animateFloat(
        transitionSpec = { tween(BACK_PREVIEW_MILLIS, easing = LinearEasing) },
        label = "backLeaving",
    ) { if (it == EnterExitState.PostExit) 1f else 0f }
    val uncovering = transition.animateFloat(
        transitionSpec = { tween(BACK_PREVIEW_MILLIS, easing = LinearEasing) },
        label = "backUncovering",
    ) { if (it == EnterExitState.PreEnter) 0f else 1f }
    val pageRelease = remember { ReleasePoint() }
    val scrimRelease = remember { ReleasePoint() }
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val cornerPx = with(density) { PreviewCorner.toPx() }
    val followPx = with(density) { PreviewFollowMax.toPx() }
    val scrimAlpha = if (colors.surface.luminance() < 0.5f) SCRIM_ALPHA_DARK else SCRIM_ALPHA_LIGHT
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val t = if (state.popping) leaving.value.coerceIn(0f, 1f) else 0f
                val gone = pageRelease.gone(state.held, t)
                val eased = BackGestureEasing.transform(t)
                val scale = 1f - (1f - EXIT_SCALE) * eased
                scaleX = scale
                scaleY = scale
                translationX =
                    if (PREVIEW_FOLLOW_GESTURE && pageRelease.gestured) state.follow * followPx * eased else 0f
                alpha = 1f - gone
                shape = if (eased > 0f) RoundedCornerShape(cornerPx * eased) else RectangleShape
                clip = true
            }
            .background(colors.surface),
    ) {
        content()
        // Its own layer: dimming changes one property instead of redrawing the page under it.
        Box(
            Modifier.matchParentSize()
                .graphicsLayer {
                    // PreEnter until the pop is over: a page at rest is never dimmed, and neither
                    // is the leaving page, whatever the timeline says.
                    val uncovered = state.popping && transition.currentState == EnterExitState.PreEnter
                    val t = if (uncovered) uncovering.value.coerceIn(0f, 1f) else 0f
                    val gone = scrimRelease.gone(state.held, t)
                    alpha = scrimAlpha * BackGestureEasing.transform(t) * (1f - gone)
                }
                .background(colors.scrim),
        )
    }
}

/** A settle animation of [PredictiveBackContent] that is still running. */
private class Settle {
    var job: Job? = null

    /** The running settle finishes a back, as opposed to undoing a cancelled one. */
    var committing = false
}

/**
 * Cancelled: back along the timeline to where the gesture started, at the pace the nav host uses
 * for the same thing, then idle on the page that never left.
 */
private suspend fun <T> SeekableTransitionState<T>.seekBack() {
    val from = fraction
    val nanos = from * BACK_PREVIEW_MILLIS * 1_000_000f
    if (nanos > 0f) {
        val start = withFrameNanos { it }
        while (true) {
            val elapsed = withFrameNanos { it } - start
            if (elapsed >= nanos) break
            seekTo((from * (1f - FastOutSlowInEasing.transform(elapsed / nanos))).coerceIn(0f, 1f))
        }
    }
    snapTo(currentState)
}

/**
 * Predictive navigation for pages that live inside one navigation destination. The seekable
 * transition keeps the old page alive, reveals its predecessor, and restores it on cancellation.
 * Keys must be saveable (for example enum names); this also preserves each page's scroll/form state.
 *
 * This and the nav host never work on the same gesture. A back gesture is delivered to exactly one
 * handler, the innermost enabled one: this one while [enabled], the nav host's otherwise. Each
 * seeks only its own transition, and a [BackPreviewFrame] listens only to the host it sits in, so
 * a page here and the destination around it cannot both move. What the two do share is the
 * behaviour, so this host drives its timeline exactly as the nav host drives its own: seek with
 * the raw progress while the finger is down, play the rest on commit, seek back to 0 on cancel.
 */
@Composable
fun <T : Any> PredictiveBackContent(
    current: T,
    previous: T,
    onBack: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (page: T, back: () -> Unit) -> Unit,
) {
    val state = remember { SeekableTransitionState(current) }
    val transition = rememberTransition(state, label = "internalBack")
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val latestBack by rememberUpdatedState(onBack)
    val preview = rememberBackPreviewState()
    var popping by remember { mutableStateOf(false) }
    val settle = remember { Settle() }
    // Forward moves match the nav host: slide a short distance on the spatial spring, fade on the
    // effects spring (the host's enter/exit specs).
    val motion = MaterialTheme.motionScheme
    val slideSpec = motion.defaultSpatialSpec<IntOffset>()
    val fadeSpec = motion.defaultEffectsSpec<Float>()
    val fastFadeSpec = motion.fastEffectsSpec<Float>()

    LaunchedEffect(current) {
        if (state.currentState != current) {
            popping = false
            state.animateTo(current)
        }
    }

    // The back is decided: the rest of the timeline plays out, then the caller hears about it.
    val commit: () -> Unit = {
        settle.committing = true
        settle.job = scope.launch {
            try {
                state.animateTo(previous)
                latestBack()
            } finally {
                settle.committing = false
                popping = false
            }
        }
    }
    val back: () -> Unit = {
        if (!enabled) {
            latestBack()
        } else if (!popping) {
            popping = true
            commit()
        }
    }
    PredictiveBackHandler(enabled = enabled) { events ->
        if (settle.committing) {
            // The previous back is still playing out and is not this gesture's to undo.
            events.collect {}
            return@PredictiveBackHandler
        }
        settle.job?.cancel()
        popping = true
        var completed = false
        try {
            events.collect { state.seekTo(it.progress, previous) }
            completed = true
        } finally {
            // Settling runs in this composable's scope, not here: a cancelled gesture cancels
            // this coroutine, which then cannot animate anything back.
            if (completed) {
                commit()
            } else {
                settle.job = scope.launch {
                    try {
                        if (state.targetState == previous) state.seekBack()
                    } finally {
                        popping = false
                    }
                }
            }
        }
    }
    transition.AnimatedContent(
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            preview.popping = popping
            if (popping) {
                (EnterTransition.None togetherWith predictiveBackExit()).apply { targetContentZIndex = -1f }.using(null)
            } else {
                (
                    (slideInHorizontally(slideSpec) { it / 8 } + fadeIn(fadeSpec)) togetherWith
                        (slideOutHorizontally(slideSpec) { -it / 16 } + fadeOut(fastFadeSpec))
                    ).using(null)
            }
        },
    ) { page ->
        BackPreviewFrame(preview) {
            holder.SaveableStateProvider(page) { content(page, back) }
        }
    }
}
