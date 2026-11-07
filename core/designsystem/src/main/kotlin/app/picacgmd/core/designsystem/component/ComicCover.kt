package app.picacgmd.core.designsystem.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import app.picacgmd.core.designsystem.R
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.core.designsystem.theme.withTabularFigures

/** Width : height of every comic cover (1 : 1.4). */
const val ComicCoverAspectRatio: Float = 1f / 1.4f

/** Scrim behind the white marks drawn on top of covers. */
private val CoverMarkScrim = Color.Black.copy(alpha = 0.62f)

/**
 * A comic cover image with plain rounded corners (never a decorative shape).
 *
 * Sized by [modifier]; when only the width is constrained the height follows
 * [ComicCoverAspectRatio]. While the image loads the tile shows `surfaceContainerHigh`; a missing or
 * broken image shows a quiet book / broken-image glyph instead.
 *
 * @param progressLabel reading progress mark in the top-left corner, e.g. 「12 话」 (board 08).
 * @param badge bottom-right label such as 「18 话」 or 「已下载」.
 */
@Composable
fun ComicCover(
    coverUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    progressLabel: String? = null,
    badge: String? = null,
) {
    var failed by remember(coverUrl) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .aspectRatio(ComicCoverAspectRatio)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
            AsyncImage(
                model = coverUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onSuccess = { failed = false },
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (progressLabel != null) {
            val spoken = stringResource(R.string.designsystem_read_progress, progressLabel)
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .height(20.dp)
                    .background(CoverMarkScrim, CircleShape)
                    .padding(start = 4.dp, end = 7.dp)
                    .semantics(mergeDescendants = true) { this.contentDescription = spoken },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    Icons.Outlined.History,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
                CoverMarkText(progressLabel)
            }
        }
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .height(18.dp)
                    .background(CoverMarkScrim, CircleShape)
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                CoverMarkText(badge)
            }
        }
    }
}

@Composable
private fun CoverMarkText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.withTabularFigures(),
        color = Color.White,
        maxLines = 1,
    )
}

/**
 * Grid cell: cover + up to two title lines, with optional author and likes (boards 04 / 08 / 21).
 * Width comes from the grid cell; the cover keeps the 1 : 1.4 ratio.
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
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button),
    ) {
        ComicCover(
            coverUrl = coverUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
            progressLabel = progressLabel,
            badge = badge,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.2.sp),
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
                progressLabel = "12",
                badge = "18 话",
                modifier = Modifier.width(120.dp),
            )
            ComicCoverCard(
                title = "午后猫咖物语：第二季特别篇",
                coverUrl = null,
                onClick = {},
                author = "栗原千夏",
                likes = "8.1k",
                badge = "已下载",
                modifier = Modifier.width(120.dp),
            )
        }
    }
}
