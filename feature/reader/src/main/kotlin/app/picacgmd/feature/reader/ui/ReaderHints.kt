package app.picacgmd.feature.reader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.East
import androidx.compose.material.icons.rounded.South
import androidx.compose.material.icons.rounded.West
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.picacgmd.feature.reader.R
import app.picacgmd.feature.reader.ReaderDirection
import app.picacgmd.feature.reader.ReaderFilter
import kotlinx.coroutines.delay

private const val HINT_SHOW_MILLIS = 1_600L

/** The translucent pill used for every short hint; readable on any page. */
@Composable
internal fun HintPill(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier = modifier
            .heightIn(min = 24.dp)
            .clip(CircleShape)
            .background(ReaderPillColors.Hint)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = ReaderPillColors.Content, modifier = Modifier.size(16.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = ReaderPillColors.Content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Shows [content] for a moment each time [value] changes, but not for the first value: the hint
 * answers a change the reader just made, and has nothing to say on a fresh open. A null value means
 * nothing is known right now (a chapter is loading) and is passed over, so that the change is still
 * noticed when the next value arrives. While [held] (a sheet covers the screen) a change waits, and a
 * change that was undone in the meantime is not announced at all.
 */
@Composable
private fun <T : Any> ShowOnChange(
    value: T?,
    modifier: Modifier,
    held: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    var shown by remember { mutableStateOf(value) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(value, held) {
        val previous = shown
        if (value == null || held) {
            visible = false
        } else if (value != previous) {
            shown = value
            if (previous != null) {
                visible = true
                delay(HINT_SHOW_MILLIS)
                visible = false
            }
        }
    }
    AnimatedVisibility(visible = visible, modifier = modifier, enter = readerFadeIn(), exit = readerFadeOut()) {
        shown?.let { content(it) }
    }
}

/** 滤镜切换时左上角短暂提示当前滤镜. */
@Composable
internal fun FilterHint(filter: ReaderFilter, modifier: Modifier = Modifier, held: Boolean = false) {
    ShowOnChange(filter, modifier, held) { current ->
        HintPill(
            text = stringResource(
                R.string.reader_filter_hint,
                stringResource(
                    when (current) {
                        ReaderFilter.Normal -> R.string.reader_filter_normal
                        ReaderFilter.Grayscale -> R.string.reader_filter_gray
                        ReaderFilter.Sepia -> R.string.reader_filter_sepia
                    },
                ),
            ),
        )
    }
}

/** 翻页模式切换方向后短暂提示一次. */
@Composable
internal fun DirectionHint(direction: ReaderDirection, modifier: Modifier = Modifier, held: Boolean = false) {
    ShowOnChange(direction, modifier, held) { current ->
        val (icon, text) = when (current) {
            ReaderDirection.TopToBottom -> Icons.Rounded.South to R.string.reader_direction_ttb
            ReaderDirection.LeftToRight -> Icons.Rounded.East to R.string.reader_direction_ltr
            ReaderDirection.RightToLeft -> Icons.Rounded.West to R.string.reader_direction_rtl
        }
        HintPill(text = stringResource(text), icon = icon)
    }
}

/**
 * 自由缩放: 「200%」 for a moment while the zoom changes. [percent] is read here, so only this pill
 * recomposes during a pinch.
 */
@Composable
internal fun ZoomHint(percent: () -> Int, modifier: Modifier = Modifier) {
    val latest by rememberUpdatedState(percent)
    val value by remember { derivedStateOf { latest() } }
    ShowOnChange(value, modifier) { current ->
        HintPill(
            text = stringResource(R.string.reader_zoom_percent, current),
            icon = if (current >= 100) Icons.Rounded.ZoomIn else Icons.Rounded.ZoomOut,
        )
    }
}

/** Names the new chapter for a moment after moving into it; null while one is loading. */
@Composable
internal fun ChapterHint(chapterTitle: String?, modifier: Modifier = Modifier, held: Boolean = false) {
    ShowOnChange(chapterTitle, modifier, held) { current -> HintPill(text = current) }
}
