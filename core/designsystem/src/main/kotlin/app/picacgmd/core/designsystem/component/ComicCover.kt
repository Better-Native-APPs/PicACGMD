package app.picacgmd.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import app.picacgmd.core.designsystem.R
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.core.designsystem.theme.withTabularFigures

/** Width : height of every comic cover (1 : 1.4). */
const val ComicCoverAspectRatio: Float = 1f / 1.4f

enum class ImageFailureReloadAction { Tap, PullDown }

/** Supplied by the app; shared image components do not depend on stored preferences. */
val LocalImageFailureReloadAction = staticCompositionLocalOf { ImageFailureReloadAction.Tap }

@Composable
fun Modifier.reloadFailedImage(onReload: () -> Unit): Modifier = when (LocalImageFailureReloadAction.current) {
    ImageFailureReloadAction.Tap -> clickable(onClickLabel = "重试", onClick = onReload)
    ImageFailureReloadAction.PullDown -> semantics {
        customActions = listOf(CustomAccessibilityAction("重试") { onReload(); true })
    }.pointerInput(onReload) {
        var distance = 0f
        detectVerticalDragGestures(
            onDragStart = { distance = 0f },
            onVerticalDrag = { change, delta -> change.consume(); distance += delta },
            onDragEnd = { if (distance >= 48.dp.toPx()) onReload() },
            onDragCancel = { distance = 0f },
        )
    }
}

/** Scrim behind the white marks drawn on top of covers. */
private val CoverMarkScrim = Color.Black.copy(alpha = 0.62f)

/** A read mark arriving this long after its cover appeared is animated in; earlier ones just show. */
private const val MarkStampAfterNanos = 600_000_000L

/**
 * A comic cover image with plain rounded corners (never a decorative shape).
 *
 * Sized by [modifier]; when only the width is constrained the height follows
 * [ComicCoverAspectRatio]. While the image loads the tile shows `surfaceContainerHigh` with the
 * [loadingSheen] passing over it; a missing or broken image shows a quiet book / broken-image glyph
 * instead, and a broken one can be loaded again ([reloadFailedImage]).
 *
 * @param progressLabel the read mark in the top-left corner, e.g. 「已读」 (board 08); see [ComicReadMark].
 *   A mark known when the cover appears just shows; one that turns up while the cover has been in
 *   view for a moment (the comic was opened beside the list) is stamped in with a spring.
 * @param badge bottom-right label such as 「完结」 or 「已下载」.
 * @param progressDetail where reading stopped, e.g. 「第 3 话」, joined to the mark when it fits.
 * @param progressDetailShort narrower fallback for [progressDetail], e.g. 「3 话」.
 */
@Composable
fun ComicCover(
    coverUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    progressLabel: String? = null,
    badge: String? = null,
    progressDetail: String? = null,
    progressDetailShort: String? = null,
) {
    var failed by remember(coverUrl) { mutableStateOf(false) }
    var loaded by remember(coverUrl) { mutableStateOf(false) }
    var attempt by remember(coverUrl) { mutableStateOf(0) }
    val loading = !coverUrl.isNullOrEmpty() && !failed && !loaded
    val composedAt = remember { System.nanoTime() }
    Box(
        modifier = modifier
            .aspectRatio(ComicCoverAspectRatio)
            .clip(shape)
            .then(if (failed) Modifier.reloadFailedImage { failed = false; loaded = false; attempt++ } else Modifier)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .loadingSheen(loading, MaterialTheme.colorScheme.surfaceBright),
    ) {
        if (coverUrl.isNullOrEmpty() || failed) {
            Icon(
                imageVector = if (failed) Icons.Outlined.BrokenImage else Icons.AutoMirrored.Outlined.MenuBook,
                contentDescription = if (failed) stringResource(R.string.designsystem_cover_unavailable) else null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxSize(0.3f),
            )
        }
        if (!coverUrl.isNullOrEmpty()) {
            key(attempt) {
            AsyncImage(
                model = coverUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onSuccess = { failed = false; loaded = true },
                onError = { failed = true; loaded = false },
                modifier = Modifier.fillMaxSize(),
            )
            }
        }
        if (progressLabel != null) {
            // The history lookup for a fresh page lands a beat after its cards; those marks stay still,
            // so a page of read comics never pops all at once.
            val arrivedLate = remember { System.nanoTime() - composedAt > MarkStampAfterNanos }
            val pop = rememberPopIn(animate = arrivedLate)
            ComicReadMark(
                label = progressLabel,
                detail = progressDetail,
                detailShort = progressDetailShort,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .graphicsLayer {
                        val value = pop.value
                        scaleX = value
                        scaleY = value
                        alpha = value.coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(0f, 0f)
                    },
            )
        }
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .heightIn(min = 18.dp)
                    .background(CoverMarkScrim, CircleShape)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall.withTabularFigures(),
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 0 → 1 with the expressive fast spring (a small overshoot) when [animate]; otherwise starts settled. */
@Composable
private fun rememberPopIn(animate: Boolean): Animatable<Float, AnimationVector1D> {
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(progress) { progress.animateTo(1f, spec) }
    return progress
}

/**
 * The 「已读」 mark of board 08, drawn over cover art: [label] on a solid primary pill, continued by
 * [detail] on primaryContainer when the width allows, else by the narrower [detailShort], else the
 * pill stands alone. Both halves are opaque, so the mark reads the same on light and dark artwork,
 * and its brand colors keep the reader's own state apart from the neutral scrim of comic facts
 * (「完结」). TalkBack hears 「已读，看到 第 3 话」 even when the detail is left out for space.
 *
 * Public so artwork that is not a [ComicCover] (the discover carousel) can carry the same mark.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ComicReadMark(
    label: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    detailShort: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val spoken = if (detail == null) label else "$label，${stringResource(R.string.designsystem_read_progress, detail)}"
    // No tracking: Chinese labels don't take it, and the 84dp list cover then still fits 「已读 | 12 话」.
    val labelStyle = MaterialTheme.typography.labelSmallEmphasized.copy(letterSpacing = 0.sp)
    val detailStyle = MaterialTheme.typography.labelSmall.withTabularFigures().copy(letterSpacing = 0.sp)
    Layout(
        content = {
            MarkSegment(label, labelStyle, colors.onPrimary, colors.primary, LabelPadding)
            if (detail != null) MarkSegment(detail, detailStyle, colors.onPrimaryContainer, colors.primaryContainer, DetailPadding)
            if (detailShort != null) MarkSegment(detailShort, detailStyle, colors.onPrimaryContainer, colors.primaryContainer, DetailPadding)
        },
        modifier = modifier
            // A floor, not a fixed height: large font scales grow the capsule instead of clipping it.
            .heightIn(min = 20.dp)
            .clip(CircleShape)
            .clearAndSetSemantics { contentDescription = spoken },
    ) { measurables, constraints ->
        // Each segment is measured at most once: the label always, then details until one fits.
        val loose = constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)
        val lead = measurables.first().measure(loose)
        val tailConstraints = loose.copy(minHeight = lead.height, maxHeight = lead.height)
        val room = if (constraints.hasBoundedWidth) constraints.maxWidth - lead.width else Int.MAX_VALUE
        var tail: Placeable? = null
        for (candidate in measurables.drop(1)) {
            val placeable = candidate.measure(tailConstraints)
            if (placeable.width <= room) {
                tail = placeable
                break
            }
        }
        layout(constraints.constrainWidth(lead.width + (tail?.width ?: 0)), lead.height) {
            lead.placeRelative(0, 0)
            tail?.placeRelative(lead.width, 0)
        }
    }
}

// Symmetric, so a lone 「已读」 sits centered; the detail keeps a little more room at the rounded end.
private val LabelPadding = PaddingValues(horizontal = 5.dp)
private val DetailPadding = PaddingValues(start = 4.dp, end = 6.dp)

@Composable
private fun MarkSegment(text: String, style: TextStyle, color: Color, background: Color, padding: PaddingValues) {
    Box(Modifier.background(background).padding(padding), contentAlignment = Alignment.Center) {
        Text(text = text, style = style, color = color, maxLines = 1, softWrap = false)
    }
}

/**
 * Grid cell: cover + up to two title lines, with optional author and likes (boards 04 / 08 / 21).
 * Width comes from the grid cell; the cover keeps the 1 : 1.4 ratio.
 *
 * @param selectionMode true while the surrounding grid is in multi-select; the card then reports
 *   [selected] to accessibility services (TalkBack says 「已选中」 / 「未选中」).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComicCoverCard(
    title: String,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    author: String? = null,
    progressLabel: String? = null,
    badge: String? = null,
    likes: String? = null,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    progressDetail: String? = null,
    progressDetailShort: String? = null,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .then(
                // In multi-select the tick is decoration; the card itself says whether it is picked.
                if (selectionMode) Modifier.semantics { this.selected = selected } else Modifier,
            ),
    ) {
        ComicCover(
            coverUrl = coverUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
            progressLabel = progressLabel,
            progressDetail = progressDetail,
            progressDetailShort = progressDetailShort,
            badge = badge,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 6.dp),
        )
        if (author != null) {
            Text(
                text = author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
        if (likes != null) {
            MetaInfo(
                icon = MetaIcons.Likes,
                text = likes,
                semanticsLabel = stringResource(R.string.designsystem_likes, likes),
                modifier = Modifier.padding(start = 2.dp, top = 2.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun ComicCoverCardPreview() {
    PikapikaTheme(themeChoice = ThemeChoice.Brand) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ComicCoverCard(
                title = "星屑列车",
                coverUrl = null,
                onClick = {},
                progressLabel = "已读",
                progressDetail = "第 12 话",
                progressDetailShort = "12 话",
                badge = "完结",
                modifier = Modifier.width(120.dp),
            )
            ComicCoverCard(
                title = "午后猫咖物语：第二季特别篇",
                coverUrl = null,
                onClick = {},
                author = "栗原千夏",
                likes = "8.1k",
                progressLabel = "已读",
                badge = "已下载",
                modifier = Modifier.width(120.dp),
            )
        }
    }
}
