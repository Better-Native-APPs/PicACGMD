package app.picacgmd.feature.categories

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.PinnableHomeChannel
import app.picacgmd.core.designsystem.theme.DarkMode
import app.picacgmd.core.designsystem.theme.PicaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.core.domain.repository.ALL_COMICS_CATEGORY
import app.picacgmd.testing.ComposeHostRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Built-in entries share category pin controls but never get grid visibility or drag controls. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h1200dp")
class CategoriesManagementTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private var settings by mutableStateOf(AppSettings(pinnedHomeCategories = emptyList()))
    private val pins = mutableListOf<Pair<PinnableHomeChannel, Boolean>>()

    @Test
    fun builtInsAppearBeforeCategoriesWithTheSamePinLabelsAndNoReorderOrVisibilityControls() {
        compose()
        composeRule.onNodeWithContentDescription("取消固定「随机」").assertIsOn()
        composeRule.onNodeWithContentDescription("取消固定「游戏专区」").assertIsOn()
        assertTrue(top("随机") < top("游戏专区"))
        assertTrue(top("游戏专区") < top(ALL_COMICS_CATEGORY.title))
        for (name in listOf("随机", "游戏专区")) {
            composeRule.onNodeWithContentDescription("拖动调整「$name」顺序").assertDoesNotExist()
            composeRule.onNodeWithContentDescription("在首页显示「$name」").assertDoesNotExist()
        }
        composeRule.onNodeWithText("暂无固定分类，点图钉添加").assertExists()
    }

    @Test
    fun bothBuiltInsCanBeUnpinnedAndRepinnedWithoutLeavingTheManagementList() {
        compose()
        composeRule.onNodeWithContentDescription("取消固定「随机」").performClick()
        composeRule.onNodeWithContentDescription("取消固定「游戏专区」").performClick()
        composeRule.onNodeWithContentDescription("固定「随机」到首页").assertIsOff()
        composeRule.onNodeWithContentDescription("固定「游戏专区」到首页").assertIsOff()
        assertEquals(emptySet(), settings.pinnedHomeChannels)

        composeRule.onNodeWithContentDescription("固定「随机」到首页").performClick()
        composeRule.onNodeWithContentDescription("取消固定「随机」").assertIsOn()
        assertEquals(setOf("random"), settings.pinnedHomeChannels)
        assertEquals(
            listOf(PinnableHomeChannel.RANDOM to false, PinnableHomeChannel.GAMES to false, PinnableHomeChannel.RANDOM to true),
            pins,
        )
        assertEquals(emptyList(), settings.pinnedHomeCategories)
    }

    @Test
    fun removedCaptionsAreAbsentAndTheRemainingCaptionIncludesAllFixedGridEntries() {
        compose()
        composeRule.onNodeWithText("分类网格每行显示几列").assertDoesNotExist()
        composeRule.onNodeWithText("拖动把手调整顺序").assertDoesNotExist()
        composeRule.onNodeWithText(
            "全分类、随机、游戏专区固定在前，其余分类可拖动排序或隐藏；隐藏仅影响分类网格、保留固定入口",
        ).assertExists()
    }

    private fun top(name: String): Float = composeRule.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.top

    private fun compose() {
        composeRule.setContent {
            PicaTheme(themeChoice = ThemeChoice.Brand, darkMode = DarkMode.Light) {
                ManageContent(
                    categories = listOf(ALL_COMICS_CATEGORY),
                    settings = settings,
                    onPin = { _, _ -> error("Unexpected category pin") },
                    onChannelPin = { channel, pinned ->
                        pins += channel to pinned
                        val current = settings.pinnedHomeChannels
                        settings = settings.copy(pinnedHomeChannels = if (pinned) current + channel.id else current - channel.id)
                    },
                    onVisible = { _, _ -> error("Built-ins must not have visibility controls") },
                    onReorderPinned = { error("Built-ins must not be reordered") },
                    onReorderCategories = { error("No reorderable categories in this fixture") },
                    onColumnCount = {},
                    onOpenAllComics = {},
                )
            }
        }
    }
}
