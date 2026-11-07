package app.picacgmd.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner scale from board 01. MaterialShapes (Cookie9Sided, Sunny, …) are decorative only:
 * avatars, entry badges, empty states and loading. Covers and page images always use the plain
 * [Shapes.small] / [Shapes.medium] corners.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val PikapikaShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), // badges
    small = RoundedCornerShape(8.dp), // chips, text fields, small covers
    medium = RoundedCornerShape(12.dp), // cards, covers
    large = RoundedCornerShape(16.dp), // FAB
    largeIncreased = RoundedCornerShape(20.dp), // ButtonGroup, segmented list ends, category tiles
    extraLarge = RoundedCornerShape(28.dp), // search bar, dialogs, sheets, carousel items
    extraLargeIncreased = RoundedCornerShape(32.dp), // large cards, banners
    extraExtraLarge = RoundedCornerShape(48.dp), // large-screen side panes
)
