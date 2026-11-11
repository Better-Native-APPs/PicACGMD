package app.picacgmd.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice

/**
 * User avatar clipped to Cookie9Sided on the tertiary container (board 01: Cookie9Sided = avatar).
 * Shows [imageUrl] when it loads, otherwise [initial], otherwise a person glyph.
 *
 * With [onClick] the avatar becomes a button: the ripple stays inside the shape and the touch
 * target grows to the 48dp minimum.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShapedAvatar(
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    initial: String? = null,
    size: Dp = 36.dp,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
) {
    var loaded by remember(imageUrl) { mutableStateOf(false) }
    val content = MaterialTheme.colorScheme.onTertiaryContainer
    Box(
        modifier = modifier
            .then(if (onClick != null) Modifier.minimumInteractiveComponentSize() else Modifier)
            .size(size)
            .clip(MaterialShapes.Cookie9Sided.toShape())
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick, role = Role.Button) else Modifier)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            if (!initial.isNullOrEmpty()) {
                Text(
                    text = initial.take(1),
                    color = content,
                    // Emphasized weight from the theme; the size follows the avatar it sits in.
                    style = MaterialTheme.typography.labelLargeEmphasized.copy(fontSize = (size.value * 0.42f).sp),
                    maxLines = 1,
                )
            } else {
                Icon(Icons.Outlined.Person, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.6f))
            }
        }
        if (!imageUrl.isNullOrEmpty()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                onError = { loaded = false },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Collapsed search bar that opens the search page when tapped (board 04 top). 56dp pill on
 * `surfaceContainerHigh`; [trailingContent] usually holds the [ShapedAvatar] that leads to 「我的」.
 */
@Composable
fun SearchEntryBar(
    placeholder: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = if (trailingContent != null) 6.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null)
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            trailingContent?.invoke()
        }
    }
}

@Preview(showBackground = true, widthDp = 412)
@Composable
private fun SearchEntryBarPreview() {
    PikapikaTheme(themeChoice = ThemeChoice.Brand) {
        SearchEntryBar(
            placeholder = "搜索漫画、作者、标签",
            onClick = {},
            modifier = Modifier.padding(16.dp),
            trailingContent = { ShapedAvatar(initial = "凪") },
        )
    }
}
