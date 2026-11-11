package app.picacgmd.core.designsystem.component

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * Back preview, in the manner of the system one: the leaving page shrinks to 90 % around its
 * centre and rounds its corners, the page underneath is dimmed, and once the back is committed the
 * small page fades away together with the dimming.
 *
 * One timeline serves both the gesture (Navigation seeks it with the gesture progress, which
 * stays well below 1 for an ordinary swipe) and a plain back press (it simply plays for
 * [BACK_PREVIEW_MILLIS]).
 *
 * Everything that moves per frame is a layer property (scale, alpha, outline). No shadow: an
 * elevated full-screen layer whose outline changes every frame has its shadow rebuilt every frame.
 */

/**
 * Close to the system's own back-gesture curve: it starts gently, so nothing jumps in the first
 * frames (when the page underneath is still being composed), and has done most of the shrinking by
 * a third of the way.
 */
private val BackGestureEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
private const val BACK_PREVIEW_MILLIS = 300

/**
 * Idle for the first half of the timeline, which a drag rarely leaves, then runs to the end: it
 * drives what happens once the back is committed, right after the shrinking has settled.
 */
private const val DISMISS_START = 0.5f
private val DismissEasing = Easing { ((it - DISMISS_START) / (1f - DISMISS_START)).coerceIn(0f, 1f) }

private val PreviewCorner = 28.dp
private const val SCRIM_ALPHA_LIGHT = 0.32f
private const val SCRIM_ALPHA_DARK = 0.5f

fun predictiveBackExit(): ExitTransition =
    scaleOut(tween(BACK_PREVIEW_MILLIS, easing = BackGestureEasing), targetScale = 0.9f) +
        fadeOut(tween(BACK_PREVIEW_MILLIS, easing = DismissEasing))

/**
 * Whether the host's running transition is a pop. Transition specs report it when they are built,
 * so that forward navigation and tab switches leave every [BackPreviewFrame] idle.
 */
@Stable
class BackPreviewState {
    /** Set by every transition spec of the host: true for a pop, false for anything else. */
    var popping: Boolean by mutableStateOf(false)
}

/**
 * Wraps one page of an animated host. During a pop it rounds the corners of a page that is leaving
 * and dims a page that is being uncovered. The page itself is clipped, not the host, so the page
 * underneath stays full-size.
 *
 * [state] and the animated values are only read inside layer blocks: a back gesture updates layer
 * properties and never recomposes or redraws the page content.
 */
@Composable
fun AnimatedVisibilityScope.BackPreviewFrame(state: BackPreviewState, content: @Composable () -> Unit) {
    val leave = transition.animateFloat(
        transitionSpec = { tween(BACK_PREVIEW_MILLIS, easing = BackGestureEasing) },
        label = "backCorners",
    ) { if (it == EnterExitState.PostExit) 1f else 0f }
    val dim = transition.animateFloat(
        transitionSpec = { tween(BACK_PREVIEW_MILLIS, easing = DismissEasing) },
        label = "backScrim",
    ) { if (it == EnterExitState.PreEnter) 1f else 0f }
    val colors = MaterialTheme.colorScheme
    val cornerPx = with(LocalDensity.current) { PreviewCorner.toPx() }
    val scrimAlpha = if (colors.surface.luminance() < 0.5f) SCRIM_ALPHA_DARK else SCRIM_ALPHA_LIGHT
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val amount = if (state.popping) leave.value.coerceIn(0f, 1f) else 0f
                shape = if (amount > 0f) RoundedCornerShape(cornerPx * amount) else RectangleShape
                clip = true
            }
            .background(colors.surface),
    ) {
        content()
        // Its own layer: fading it changes one property instead of redrawing the page under it.
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { alpha = if (state.popping) dim.value.coerceIn(0f, 1f) * scrimAlpha else 0f }
                .background(colors.scrim),
        )
    }
}

/**
 * Predictive navigation for pages that live inside one navigation destination. The seekable
 * transition keeps the old page alive, reveals its predecessor, and restores it on cancellation.
 * Keys must be saveable (for example enum names); this also preserves each page's scroll/form state.
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
    val preview = remember { BackPreviewState() }
    var popping by remember { mutableStateOf(false) }
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

    val back: () -> Unit = {
        if (enabled && !popping) {
            popping = true
            scope.launch {
                try {
                    state.animateTo(previous)
                    latestBack()
                } finally {
                    popping = false
                }
            }
        } else if (!enabled) latestBack()
    }
    PredictiveBackHandler(enabled = enabled) { events ->
        popping = true
        try {
            events.collect { state.seekTo(it.progress, previous) }
            state.animateTo(previous)
            latestBack()
        } catch (_: CancellationException) {
            state.animateTo(current)
        } finally {
            popping = false
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
