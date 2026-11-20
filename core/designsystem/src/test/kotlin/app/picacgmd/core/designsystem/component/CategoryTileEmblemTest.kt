package app.picacgmd.core.designsystem.component

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The icon of a channel tile makes room for the tile's name, whatever the name's size. */
class CategoryTileEmblemTest {
    private val tile = CategoryTileDefaults.Height

    /** Where the icon ends, measured from the top of the tile. */
    private fun iconBottom(scale: Float): Dp =
        ((CategoryTileDefaults.EmblemSize + CategoryTileDefaults.EmblemIconSize) / 2 - CategoryTileDefaults.EmblemBleed) * scale

    /** Where a name [height] high starts: it sits 8dp above the bottom of the tile. */
    private fun nameTop(height: Dp): Dp = tile - 8.dp - height

    @Test
    fun aOneLineNameLeavesTheIconItsFullSizeAlsoAtLargeFont() {
        assertEquals(1f, emblemScale(tile, nameHeight = 20.dp))
        assertEquals(1f, emblemScale(tile, nameHeight = 26.dp))
    }

    @Test
    fun aNameOnTwoLinesGetsASmallerIconThatEndsAboveIt() {
        for (name in listOf(40.dp, 52.dp)) {
            val scale = emblemScale(tile, nameHeight = name)
            assertTrue(scale > 0f && scale < 1f, "Two lines $name high: $scale")
            assertTrue(iconBottom(scale) < nameTop(name), "The icon runs into a name $name high")
        }
    }

    @Test
    fun aNameThatFillsTheTileGetsNoIcon() {
        assertEquals(0f, emblemScale(tile, nameHeight = 64.dp))
        assertEquals(0f, emblemScale(tile, nameHeight = 96.dp))
    }
}
