package app.picacgmd.feature.discover

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.designsystem.theme.DarkMode
import app.picacgmd.core.designsystem.theme.PicaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.core.domain.repository.buildHomeModel
import app.picacgmd.core.network.Category
import app.picacgmd.testing.ComposeHostRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Local grid entry identity and navigation remain independent of the optional home-row pins. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h800dp")
class DiscoverCategoryGridTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private var pinnedChannels by mutableStateOf(AppSettings.DEFAULT.pinnedHomeChannels)
    private val serverRandom = Category(id = "server-random", title = "随机")
    private val opened = mutableListOf<String>()

    @Test
    fun gridEntriesRemainAfterUnpinningAndOpenTheSameChannelDestinations() {
        compose()
        entry("channels", "随机").assertExists()
        entry("channels", "游戏专区").assertExists()
        entry("grid-row-0", "随机").performClick()
        entry("grid-row-0", "游戏专区").performClick()

        pinnedChannels = emptySet()
        composeRule.waitForIdle()
        entry("channels", "随机").assertDoesNotExist()
        entry("channels", "游戏专区").assertDoesNotExist()
        entry("channels", "排行榜").assertExists()
        entry("grid-row-0", "排行榜").assertDoesNotExist()
        entry("grid-row-0", "随机").performClick()
        entry("grid-row-0", "游戏专区").performClick()
        entry("grid-row-0", "全分类").performClick()
        assertEquals(listOf("channel:Random", "channel:Games", "channel:Random", "channel:Games", "all"), opened)
    }

    @Test
    fun aSameNamedServerCategoryStaysOrdinaryAndUsesTheCategoryCallback() {
        val grid = homeCategories()
        assertEquals(
            listOf(HomeCategoryKind.ALL_COMICS, HomeCategoryKind.RANDOM, HomeCategoryKind.GAMES, HomeCategoryKind.CATEGORY),
            grid.map { it.kind },
        )
        assertEquals(listOf("全分类", "随机", "游戏专区", "随机"), grid.map { it.name })
        assertNull(grid[1].thumbUrl)
        assertNull(grid[2].thumbUrl)
        compose()
        entry("grid-row-0", "随机").performClick()
        entry("grid-row-1", "随机").performClick()
        assertEquals(listOf("channel:Random", "category:随机"), opened)
    }

    private fun homeCategories(): List<HomeCategory> = buildHomeModel(
        listOf(serverRandom), AppSettings(pinnedHomeChannels = pinnedChannels, pinnedHomeCategories = emptyList()),
    ).categories.map { it.toHomeCategory() }

    private fun entry(scope: String, label: String) = composeRule.onNode(
        hasText(label) and hasClickAction() and hasAnyAncestor(hasTestTag(scope)),
    )

    private fun compose() {
        composeRule.setContent {
            PicaTheme(themeChoice = ThemeChoice.Brand, darkMode = DarkMode.Light) {
                Column {
                    Box(Modifier.testTag("channels")) {
                        ChannelRow(
                            pinned = emptyList(),
                            pinnedChannels = pinnedChannels,
                            onChannelClick = { opened += "channel:${it.name}" },
                            onPinnedClick = { opened += "category:$it" },
                            onAllComicsClick = { opened += "all" },
                        )
                    }
                    homeCategories().chunked(3).forEachIndexed { index, row ->
                        Box(Modifier.testTag("grid-row-$index")) {
                            CategoryRow(
                                row = row,
                                columns = 3,
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
