package app.picacgmd.feature.discover

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.picacgmd.core.designsystem.theme.DarkMode
import app.picacgmd.core.designsystem.theme.PicaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.testing.ComposeHostRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowInstrumentation

/**
 * 随机 and 游戏专区 have no picture of their own, so their grid tiles draw an icon instead; a server
 * category without a picture stays a plain coloured tile. The tiles are judged by what is drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoverCategoryTileIconTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    // The server's category shares its name with the built-in entry, so both tiles get the same colours and
    // the same label, and it comes first: what a tile draws follows what it opens, not where it stands.
    private val rows = listOf(
        listOf(HomeCategory("随机"), AllComicsHomeCategory),
        listOf(GamesHomeCategory, RandomHomeCategory),
    )
    private val opened = mutableListOf<String>()
    private val density get() = RuntimeEnvironment.getApplication().resources.displayMetrics.density

    @Before
    fun drawTheWayADeviceDoes() {
        System.setProperty(RENDER_MODE, "hardware")
    }

    @After
    fun restoreDrawing() {
        System.clearProperty(RENDER_MODE)
    }

    @Test
    fun randomAndGamesTilesDrawTheirIconAndAServerCategoryWithoutPictureDrawsNone() {
        compose(columns = 3)

        val plain = artwork(SERVER_ROW, "随机")
        val random = artwork(BUILT_IN_ROW, "随机")
        val games = artwork(BUILT_IN_ROW, "游戏专区")

        assertEquals(1, plain.toSet().size, "A category without picture or icon is one flat colour above its name")
        assertTrue(random.toSet().size > 1, "随机 draws nothing above its name")
        assertTrue(games.toSet().size > 1, "游戏专区 draws nothing above its name")
        assertNotEquals(random, games, "随机 and 游戏专区 draw the same thing")
    }

    @Test
    fun tappingTheIconTilesStillOpensTheRandomListAndTheGamesPage() {
        compose(columns = 3)

        tile(BUILT_IN_ROW, "随机").performClick()
        tile(BUILT_IN_ROW, "游戏专区").performClick()
        tile(SERVER_ROW, "随机").performClick()

        assertEquals(listOf("channel:Random", "channel:Games", "category:随机"), opened)
    }

    @Test
    fun namesStayWholeInFourNarrowColumnsAtLargeFont() {
        compose(columns = 4, width = 360.dp, fontScale = 1.3f)

        for (name in listOf("随机", "游戏专区")) {
            val layout = nameLayout(name)
            assertFalse(layout.didOverflowHeight, "$name is clipped")
            repeat(layout.lineCount) { assertFalse(layout.isLineEllipsized(it), "$name is cut short") }
            assertEquals(name.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true), "$name is cut short")
        }
    }

    private fun tile(scope: String, label: String) = composeRule.onNode(
        hasText(label) and hasClickAction() and hasAnyAncestor(hasTestTag(scope)),
    )

    /** The pixels of a tile above its one-line name and clear of its rounded corners. */
    private fun artwork(scope: String, label: String): List<Int> {
        composeRule.waitForIdle()
        val bounds = tile(scope, label).fetchSemanticsNode().boundsInWindow
        val screen = checkNotNull(ShadowInstrumentation.getInstrumentation().uiAutomation.takeScreenshot()) { "No screenshot" }
        val left = (bounds.left + ART_INSET_SIDE.value * density).toInt()
        val right = (bounds.right - ART_INSET_SIDE.value * density).toInt()
        val top = (bounds.top + ART_TOP.value * density).toInt()
        val bottom = (bounds.top + ART_BOTTOM.value * density).toInt()
        val art = Bitmap.createBitmap(screen, left, top, right - left, bottom - top)
        return IntArray(art.width * art.height).also { art.getPixels(it, 0, art.width, 0, 0, art.width, art.height) }.toList()
    }

    private fun nameLayout(name: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNode(hasText(name) and hasAnyAncestor(hasTestTag(BUILT_IN_ROW)), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun compose(columns: Int, width: Dp = 480.dp, fontScale: Float = 1f) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                PicaTheme(themeChoice = ThemeChoice.Brand, darkMode = DarkMode.Light) {
                    Column(Modifier.width(width)) {
                        rows.forEachIndexed { index, row ->
                            Box(Modifier.testTag("grid-row-$index")) {
                                CategoryRow(
                                    row = row,
                                    columns = columns,
                                    onChannelClick = { opened += "channel:${it.name}" },
                                    onCategoryClick = { opened += "category:$it" },
                                    onAllComicsClick = { opened += "all" },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val RENDER_MODE = "robolectric.pixelCopyRenderMode"
        const val SERVER_ROW = "grid-row-0"
        const val BUILT_IN_ROW = "grid-row-1"

        // The tile is 88dp tall with 20dp corners; a one-line name starts 60dp down.
        val ART_INSET_SIDE = 6.dp
        val ART_TOP = 8.dp
        val ART_BOTTOM = 56.dp
    }
}
