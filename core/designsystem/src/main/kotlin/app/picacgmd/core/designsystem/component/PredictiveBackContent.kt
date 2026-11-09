package app.picacgmd.core.designsystem.component

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Navigation seeks these timelines with the gesture progress. The system reports progress slowly
 * (a long swipe is still well below 1), so a decelerating curve makes the page visibly shrink,
 * shift and round its corners as soon as the finger starts moving, like the system back preview.
 */
private val BackPreviewEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private const val BACK_PREVIEW_MILLIS = 300
private val LateFadeEasing = Easing { if (it < 0.9f) 0f else (it - 0.9f) / 0.1f }

fun predictiveBackExit() = scaleOut(tween(BACK_PREVIEW_MILLIS, easing = BackPreviewEasing), targetScale = 0.85f) +
    slideOutHorizontally(tween(BACK_PREVIEW_MILLIS, easing = BackPreviewEasing)) { it / 12 } +
    // Stays opaque while the finger drags; only the committed pop fades the small page away.
    fadeOut(tween(BACK_PREVIEW_MILLIS, easing = LateFadeEasing))

/** Clip the page itself, not the whole host: the previous page stays full-size underneath. */
@Composable
fun AnimatedVisibilityScope.BackPreviewFrame(roundCorners: Boolean = true, content: @Composable () -> Unit) {
    val rounding by transition.animateFloat(
        transitionSpec = { tween(BACK_PREVIEW_MILLIS, easing = BackPreviewEasing) },
        label = "backCorners",
    ) { if (it == EnterExitState.PostExit) 1f else 0f }
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                val amount = rounding.coerceIn(0f, 1f)
                shape = if (roundCorners) RoundedCornerShape(32.dp * amount) else RectangleShape
                clip = true
                // Lift the shrinking card off the page underneath so its edge reads clearly.
                shadowElevation = 12.dp.toPx() * amount
            }
            .background(MaterialTheme.colorScheme.surface),
    ) { content() }
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
    var popping by remember { mutableStateOf(false) }

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
            if (popping) {
                (EnterTransition.None togetherWith predictiveBackExit()).apply { targetContentZIndex = -1f }.using(null)
            } else {
                ((slideInHorizontally { it / 8 } + fadeIn()) togetherWith fadeOut()).using(null)
            }
        },
    ) { page ->
        BackPreviewFrame {
            holder.SaveableStateProvider(page) { content(page, back) }
        }
    }
}
