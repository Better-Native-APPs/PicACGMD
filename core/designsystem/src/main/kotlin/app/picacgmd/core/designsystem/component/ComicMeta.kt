package app.picacgmd.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.core.designsystem.theme.withTabularFigures

/**
 * Small icon + value pair used under comic titles: likes, views, pages (board 08 「kv」).
 *
 * @param semanticsLabel full spoken label (e.g. 「1.2 万人喜欢」); the bare value is read otherwise.
 */
@Composable
fun MetaInfo(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    semanticsLabel: String? = null,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = if (semanticsLabel != null) {
            modifier.clearAndSetSemantics { contentDescription = semanticsLabel }
        } else {
            modifier
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.withTabularFigures(),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 20dp pill for tags and states such as 「完结」. */
@Composable
fun TagPill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(
        modifier = modifier
            .height(20.dp)
            .background(containerColor, CircleShape)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Icons used by [MetaInfo] across the app, so every list shows the same glyphs. */
object MetaIcons {
    val Likes: ImageVector get() = Icons.Outlined.FavoriteBorder
    val Views: ImageVector get() = Icons.Outlined.Visibility
    val Pages: ImageVector get() = Icons.Outlined.PhotoLibrary
}

@Preview(showBackground = true)
@Composable
private fun MetaPreview() {
    PikapikaTheme(themeChoice = ThemeChoice.Brand) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetaInfo(MetaIcons.Likes, "12.4k")
            MetaInfo(MetaIcons.Views, "86.2k")
            TagPill("奇幻")
            TagPill(
                "完结",
                containerColor = PikapikaTheme.extendedColors.successContainer,
                contentColor = PikapikaTheme.extendedColors.onSuccessContainer,
            )
        }
    }
}
