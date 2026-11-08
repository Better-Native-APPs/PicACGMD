package app.picacgmd.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.picacgmd.core.designsystem.R
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice

/**
 * The 「详情」 list row of board 08: 84dp cover, title, author line, tags and a likes / views /
 * pages row pinned to the bottom of the cover.
 *
 * @param subtitle usually 「作者 · 汉化组」.
 * @param finished adds a success-colored 「完结」 pill after [tags].
 * @param pages e.g. 「214P · 18 话」.
 * @param progressLabel the 「已读」 mark on the cover; with [progressDetail] / [progressDetailShort]
 *   it works as in [ComicCover]. The 84dp cover fits a short detail up to two digits, e.g.
 *   「已读 | 12 话」; longer ones leave the bare 「已读」 (TalkBack still hears the chapter).
 * @param selected highlights the row, for the list pane of the large-screen list/detail layout.
 * @param leadingContent e.g. a rank number (board 07); vertically centered before the cover.
 * @param trailingContent e.g. a download state or overflow button; vertically centered.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ComicListItem(
    title: String,
    coverUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tags: List<String> = emptyList(),
    finished: Boolean = false,
    likes: String? = null,
    views: String? = null,
    pages: String? = null,
    progressLabel: String? = null,
    badge: String? = null,
    selected: Boolean = false,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    progressDetail: String? = null,
    progressDetailShort: String? = null,
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "comicListItemContainer",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .background(containerColor)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingContent != null) {
            Box(Modifier.align(Alignment.CenterVertically)) { leadingContent() }
        }
        ComicCover(
            coverUrl = coverUrl,
            contentDescription = null,
            modifier = Modifier.width(84.dp),
            progressLabel = progressLabel,
            progressDetail = progressDetail,
            progressDetailShort = progressDetailShort,
            badge = badge,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 118.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (tags.isNotEmpty() || finished) {
                    FlowRow(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        maxLines = 2,
                    ) {
                        tags.forEach { TagPill(it) }
                        if (finished) {
                            TagPill(
                                text = stringResource(R.string.designsystem_finished),
                                containerColor = PikapikaTheme.extendedColors.successContainer,
                                contentColor = PikapikaTheme.extendedColors.onSuccessContainer,
                            )
                        }
                    }
                }
            }
            if (likes != null || views != null || pages != null) {
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (likes != null) {
                        MetaInfo(
                            MetaIcons.Likes,
                            likes,
                            semanticsLabel = stringResource(R.string.designsystem_likes, likes),
                        )
                    }
                    if (views != null) {
                        MetaInfo(
                            MetaIcons.Views,
                            views,
                            semanticsLabel = stringResource(R.string.designsystem_views, views),
                        )
                    }
                    if (pages != null) MetaInfo(MetaIcons.Pages, pages)
                }
            }
        }
        if (trailingContent != null) {
            Box(Modifier.align(Alignment.CenterVertically)) { trailingContent() }
        }
    }
}

@Preview(showBackground = true, widthDp = 400)
@Composable
private fun ComicListItemPreview() {
    PikapikaTheme(themeChoice = ThemeChoice.Brand) {
        Column(Modifier.padding(vertical = 8.dp)) {
            ComicListItem(
                title = "星屑列车",
                coverUrl = null,
                onClick = {},
                subtitle = "白川凪 · 晨光汉化组",
                tags = listOf("奇幻", "长篇"),
                finished = true,
                likes = "12.4k",
                views = "86.2k",
                pages = "214P · 18 话",
                progressLabel = "已读",
                progressDetail = "第 12 话",
                progressDetailShort = "12 话",
            )
            ComicListItem(
                title = "午后猫咖物语",
                coverUrl = null,
                onClick = {},
                subtitle = "栗原千夏 · 猫尾草",
                tags = listOf("日常", "治愈"),
                likes = "8.1k",
                views = "41.0k",
                pages = "96P · 6 话",
                selected = true,
            )
        }
    }
}
