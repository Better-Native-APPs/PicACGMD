package app.picacgmd.feature.detail

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import app.picacgmd.R
import app.picacgmd.core.designsystem.component.AppIconButton
import app.picacgmd.core.designsystem.component.ComicCoverAspectRatio
import app.picacgmd.core.designsystem.component.SoftBackdrop
import app.picacgmd.core.designsystem.component.loadingSheen
import coil3.compose.AsyncImage
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlinx.coroutines.launch
import app.picacgmd.core.designsystem.R as DesignSystemR

/** How far a pinch may enlarge the cover, and how far a double tap does. */
internal const val COVER_MAX_SCALE = 5f
internal const val COVER_DOUBLE_TAP_SCALE = 2.5f

/** The picture starts a little small and grows into place while the viewer fades in. */
private const val APPEAR_SCALE = 0.92f

// A back swipe moves the viewer the way it moves a page of this app: down to 90 % with rounded
// corners and a slight lean towards the finger, over a dimmed page, on the system's back curve.
private val BackSwipeEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
private const val BACK_SWIPE_SCALE = 0.9f
private val BackSwipeCorner = 28.dp
private val BackSwipeLean = 12.dp
private const val BACK_SWIPE_DIM_LIGHT = 0.32f
private const val BACK_SWIPE_DIM_DARK = 0.5f

/** How strongly the page colour covers the blurred cover behind the picture. */
private const val BACKDROP_VEIL = 0.72f

/** A scale and an offset from the centre, see [CoverZoom]. */
internal data class CoverPlacement(val scale: Float, val offset: Offset)

/**
 * Where the opened cover sits: how much it is enlarged and how far its centre is from the centre of
 * the screen. The picture never leaves a gap it could fill: on an axis where it is no larger than
 * the screen it stays centred, and where it is larger its edges stop at the screen's edges.
 */
@Stable
internal class CoverZoom {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** The area the picture is fitted into, in pixels. */
    var viewport: Size = Size.Zero

    /** The picture's own size in pixels; empty until it has loaded. */
    var picture: Size = Size.Zero

    val placement: CoverPlacement get() = CoverPlacement(scale, offset)

    /** One step of a pinch or drag: the fingers, around [centroid], moved by [pan] and spread by [zoom]. */
    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val target = (scale * zoom).coerceIn(1f, COVER_MAX_SCALE)
        place(target, anchored(centroid, target) + pan)
    }

    /** Where a double tap at [point] leads: enlarged around that point, or back to fitted. */
    fun afterDoubleTap(point: Offset): CoverPlacement = if (scale > 1f) {
        CoverPlacement(1f, Offset.Zero)
    } else {
        CoverPlacement(COVER_DOUBLE_TAP_SCALE, bounded(anchored(point, COVER_DOUBLE_TAP_SCALE), COVER_DOUBLE_TAP_SCALE))
    }

    fun place(scale: Float, offset: Offset) {
        this.scale = scale.coerceIn(1f, COVER_MAX_SCALE)
        this.offset = bounded(offset, this.scale)
    }

    /** The offset that keeps the part of the picture under [point] under it when the scale becomes [target]. */
    private fun anchored(point: Offset, target: Float): Offset {
        val fromCentre = point - viewport.center
        return fromCentre - (fromCentre - offset) * (target / scale)
    }

    private fun bounded(offset: Offset, scale: Float): Offset {
        val fitted = if (picture.isEmpty() || viewport.isEmpty()) {
            viewport
        } else {
            picture * min(viewport.width / picture.width, viewport.height / picture.height)
        }
        return Offset(
            offset.x.within((fitted.width * scale - viewport.width) / 2f),
            offset.y.within((fitted.height * scale - viewport.height) / 2f),
        )
    }

    private fun Float.within(reach: Float): Float = if (reach > 0f) coerceIn(-reach, reach) else 0f
}

/**
 * The comic's cover on its own, over the whole page: fitted to the screen, enlarged by a pinch or a
 * double tap, moved by a drag while it is enlarged. A tap, the close button or back puts it away; a
 * back swipe first shrinks it under the finger and shows the page behind.
 *
 * Lay it over the page it belongs to; it draws nothing while not [visible].
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CoverViewer(
    visible: Boolean,
    coverUrl: String?,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible && coverUrl != null,
        modifier = modifier,
        enter = fadeIn(motion.defaultEffectsSpec()),
        exit = fadeOut(motion.defaultEffectsSpec()),
    ) {
        if (coverUrl != null) CoverViewerContent(open = visible, coverUrl = coverUrl, title = title, onClose = onClose)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AnimatedVisibilityScope.CoverViewerContent(open: Boolean, coverUrl: String, title: String, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val scope = rememberCoroutineScope()
    // How far a back swipe has taken the viewer away, already eased; 0 at rest.
    val away = remember { Animatable(0f) }
    var lean by remember { mutableFloatStateOf(0f) }
    val settle = motion.defaultEffectsSpec<Float>()
    PredictiveBackHandler(enabled = open) { events ->
        try {
            events.collect { event ->
                lean = when (event.swipeEdge) {
                    BackEventCompat.EDGE_LEFT -> 1f
                    BackEventCompat.EDGE_RIGHT -> -1f
                    else -> 0f
                }
                away.snapTo(BackSwipeEasing.transform(event.progress))
            }
            onClose()
        } catch (cancelled: CancellationException) {
            // Let go without going back. This coroutine is over, so the way back is animated outside it.
            scope.launch { away.animateTo(0f, settle) }
            throw cancelled
        }
    }
    val appear by transition.animateFloat(
        transitionSpec = { motion.defaultSpatialSpec() },
        label = "coverAppear",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    val density = LocalDensity.current
    val cornerPx = with(density) { BackSwipeCorner.toPx() }
    val leanPx = with(density) { BackSwipeLean.toPx() }
    val dim = if (colors.surface.luminance() < 0.5f) BACK_SWIPE_DIM_DARK else BACK_SWIPE_DIM_LIGHT
    val viewLabel = stringResource(R.string.detail_cover_view)
    val closeLabel = stringResource(R.string.detail_cover_close)
    Box(Modifier.fillMaxSize().semantics { paneTitle = viewLabel }) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = dim * away.value }
                .background(colors.scrim),
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val gone = away.value
                    val shrink = 1f - (1f - BACK_SWIPE_SCALE) * gone
                    scaleX = shrink
                    scaleY = shrink
                    translationX = lean * leanPx * gone
                    shape = if (gone > 0f) RoundedCornerShape(cornerPx * gone) else RectangleShape
                    clip = true
                }
                .background(colors.surface),
        ) {
            // The cover's own colours, out of focus, fill the screen around it.
            SoftBackdrop(coverUrl, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(colors.surface.copy(alpha = BACKDROP_VEIL)))
            // First for TalkBack, on top for the eye; it sits where the page has its back arrow.
            Box(
                Modifier
                    .zIndex(1f)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .height(64.dp)
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                AppIconButton(onClick = onClose, colors = IconButtonDefaults.filledTonalIconButtonColors()) {
                    Icon(Icons.Outlined.Close, contentDescription = closeLabel)
                }
            }
            ZoomableCover(
                coverUrl = coverUrl,
                title = title,
                closeLabel = closeLabel,
                appearScale = { lerp(APPEAR_SCALE, 1f, appear) },
                onClose = onClose,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ZoomableCover(
    coverUrl: String,
    title: String,
    closeLabel: String,
    appearScale: () -> Float,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val zoom = remember { CoverZoom() }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val doubleTap = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { zoom.viewport = it.toSize() }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { point ->
                        if (loaded) {
                            val from = zoom.placement
                            val to = zoom.afterDoubleTap(point)
                            scope.launch {
                                animate(0f, 1f, animationSpec = doubleTap) { fraction, _ ->
                                    zoom.place(lerp(from.scale, to.scale, fraction), lerp(from.offset, to.offset, fraction))
                                }
                            }
                        }
                    },
                    onTap = { onClose() },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, spread, _ -> if (loaded) zoom.transform(centroid, pan, spread) }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    Icons.Outlined.BrokenImage,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    stringResource(DesignSystemR.string.designsystem_cover_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
            // The outline of the cover that is on its way; the sheen only shows when it takes a while.
            !loaded -> Box(
                Modifier
                    .fillMaxWidth(0.62f)
                    .aspectRatio(ComicCoverAspectRatio)
                    .clip(MaterialTheme.shapes.large)
                    .background(colors.surfaceContainerHigh)
                    .loadingSheen(active = true, highlight = colors.surfaceBright),
            )
        }
        if (!failed) {
            AsyncImage(
                model = coverUrl,
                contentDescription = title,
                contentScale = ContentScale.Fit,
                onSuccess = { success ->
                    zoom.picture = Size(success.result.image.width.toFloat(), success.result.image.height.toFloat())
                    loaded = true
                },
                onError = { failed = true },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = zoom.scale * appearScale()
                        scaleX = scale
                        scaleY = scale
                        translationX = zoom.offset.x
                        translationY = zoom.offset.y
                    }
                    .semantics { onClick(label = closeLabel) { onClose(); true } },
            )
        }
    }
}
