package app.picacgmd.feature.reader.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import app.picacgmd.feature.reader.PageImage
import app.picacgmd.feature.reader.R
import app.picacgmd.feature.reader.ReaderChapter
import app.picacgmd.feature.reader.logic.formatFileSize
import app.picacgmd.feature.reader.media.PageFileInfo
import app.picacgmd.feature.reader.media.readPageFileInfo
import coil3.size.Size as CoilSize

/** Rows shown above the current chapter when the list opens, so its neighbours are in view. */
private const val CHAPTERS_ABOVE_CURRENT = 3

/** 章节: the whole list, opened on the current chapter, with read and downloaded chapters marked. */
@Composable
internal fun ChapterSheet(
    chapters: List<ReaderChapter>,
    currentIndex: Int,
    isRead: (ReaderChapter) -> Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var reversed by rememberSaveable { mutableStateOf(false) }
    val rows = remember(chapters, reversed) {
        chapters.indices.toList().let { indices -> if (reversed) indices.asReversed() else indices }
    }
    val palette = LocalReaderPalette.current
    ReaderSheet(onDismiss = onDismiss, skipPartiallyExpanded = false) { hide ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.reader_chapters_title, chapters.size),
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            OrderChip(reversed) { reversed = !reversed }
        }
        // Keyed on the order: flipping the list opens it on the current chapter again.
        key(reversed) {
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = (rows.indexOf(currentIndex) - CHAPTERS_ABOVE_CURRENT).coerceAtLeast(0),
            )
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                items(count = rows.size, key = { rows[it] }) { position ->
                    val index = rows[position]
                    val chapter = chapters[index]
                    ChapterRow(
                        chapter = chapter,
                        current = index == currentIndex,
                        read = isRead(chapter),
                        downloadedColor = palette.success,
                        onClick = { hide { if (index != currentIndex) onSelect(index) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderChip(reversed: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(start = 8.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.SwapVert,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(if (reversed) R.string.reader_order_reversed else R.string.reader_order_normal),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChapterRow(
    chapter: ReaderChapter,
    current: Boolean,
    read: Boolean,
    downloadedColor: Color,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(if (current) scheme.secondaryContainer else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = chapter.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (current) scheme.onSecondaryContainer else scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .alpha(if (read && !current) 0.6f else 1f),
        )
        when {
            current -> Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.reader_chapter_current),
                tint = scheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
            chapter.isDownloaded -> Icon(
                imageVector = Icons.Rounded.DownloadDone,
                contentDescription = stringResource(R.string.reader_downloaded),
                tint = downloadedColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 长按图片: what can be done with the page under the finger. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PageActionsSheet(
    image: PageImage,
    pageNumber: Int,
    colorFilter: ColorFilter?,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
    onShare: () -> Unit,
    onPreview: () -> Unit,
    onReloadAll: () -> Unit,
) {
    val context = LocalContext.current
    val info by produceState<PageFileInfo?>(initialValue = null, image) {
        value = readPageFileInfo(context, image)
    }
    ReaderSheet(onDismiss = onDismiss) { hide ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PageThumbnail(image, colorFilter)
            Column {
                Text(
                    text = stringResource(R.string.reader_page_number, pageNumber),
                    style = MaterialTheme.typography.titleMedium,
                )
                info?.let { file ->
                    Text(
                        text = if (file.width > 0 && file.height > 0) {
                            stringResource(R.string.reader_page_info, file.width, file.height, formatFileSize(file.bytes))
                        } else {
                            formatFileSize(file.bytes)
                        },
                        style = MaterialTheme.typography.bodyMedium.withTabularFigures(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 24.dp),
        ) {
            ActionRow(Icons.Rounded.SaveAlt, stringResource(R.string.reader_action_save)) { hide(onSave) }
            ActionRow(Icons.Rounded.Refresh, stringResource(R.string.reader_action_reload)) { hide(onReload) }
            ActionRow(Icons.Rounded.Share, stringResource(R.string.reader_action_share)) { hide(onShare) }
            ActionRow(
                icon = Icons.Rounded.ZoomOutMap,
                title = stringResource(R.string.reader_action_preview),
                supporting = stringResource(R.string.reader_action_preview_detail),
            ) { hide(onPreview) }
            ActionRow(Icons.Rounded.RestartAlt, stringResource(R.string.reader_reload_all)) { hide(onReloadAll) }
        }
    }
}

@Composable
private fun PageThumbnail(image: PageImage, colorFilter: ColorFilter?) {
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    val request = remember(image, density) {
        val width = with(density) { 56.dp.roundToPx() }
        val height = with(density) { 80.dp.roundToPx() }
        pageRequest(context, image, CoilSize(width, height))
    }
    Image(
        painter = rememberAsyncImagePainter(model = request),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        colorFilter = colorFilter,
        modifier = Modifier
            .size(width = 56.dp, height = 80.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionRow(icon: ImageVector, title: String, supporting: String? = null, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        supportingContent = supporting?.let { text -> { Text(text) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) {
        Text(title)
    }
}
