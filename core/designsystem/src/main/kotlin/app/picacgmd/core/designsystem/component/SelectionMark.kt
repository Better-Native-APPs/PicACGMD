package app.picacgmd.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.picacgmd.core.designsystem.theme.PikapikaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice

/** Diameter of [SelectionMark]; the cover grids leave one corner of this size clear while selecting. */
val SelectionMarkSize = 28.dp

/**
 * The one selection mark of the app (audit §A5): an outlined circle while the item is off, which
 * fills in as the Cookie9Sided check of board 23 while it is on. The two shapes the app used to
 * draw by hand — a 26dp outlined circle in the comic lists and a 28dp Cookie9Sided in the shelf —
 * are the same component now, so a cover checks in the same way wherever it is shown.
 *
 * It is decoration: the surrounding row carries the state for TalkBack (`semantics { selected }`,
 * see [ComicListItem] / [ComicCoverCard]), so the mark itself reads as nothing.
 *
 * The fill springs in on the press-fast spatial spec; nothing animates while it is left alone.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SelectionMark(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val fill by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "selectionMark",
    )
    Box(modifier.size(SelectionMarkSize), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(26.dp)
                .background(colors.surface.copy(alpha = 0.85f), CircleShape)
                .border(2.dp, colors.outline, CircleShape),
        )
        if (fill > 0.01f) {
            Box(
                modifier = Modifier
                    .size(SelectionMarkSize)
                    .graphicsLayer {
                        scaleX = fill
                        scaleY = fill
                    }
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(colors.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = colors.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SelectionMarkPreview() {
    PikapikaTheme(themeChoice = ThemeChoice.Brand) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            SelectionMark(selected = true)
        }
    }
}
