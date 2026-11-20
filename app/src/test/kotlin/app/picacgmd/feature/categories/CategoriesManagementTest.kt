package app.picacgmd.feature.categories

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.data.settings.PinnableHomeChannel
import app.picacgmd.core.data.settings.SettingsRepository
import app.picacgmd.core.designsystem.theme.DarkMode
import app.picacgmd.core.designsystem.theme.PicaTheme
import app.picacgmd.core.designsystem.theme.ThemeChoice
import app.picacgmd.core.domain.di.AppContainer
import app.picacgmd.core.domain.repository.ALL_COMICS_CATEGORY
import app.picacgmd.core.domain.repository.GAMES_CATEGORY
import app.picacgmd.core.domain.repository.RANDOM_CATEGORY
import app.picacgmd.core.domain.repository.buildHomeModel
import app.picacgmd.core.network.Category
import app.picacgmd.di.LocalAppContainer
import app.picacgmd.testing.ComposeHostRule
import app.picacgmd.testing.MemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sun.misc.Unsafe

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h1200dp")
class CategoriesManagementTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private val first = Category(id = "first", title = "甲")
    private val second = Category(id = "second", title = "乙")
    private val initialSettings = AppSettings(pinnedHomeCategories = emptyList(), hiddenCategories = listOf(second.title))
    private val preferences = MemoryPreferencesStore()
    private val repository = SettingsRepository(preferences, initialSettings)
    // No category thumbnail is supplied, so image routing and Android assembly are not exercised.
    private val container = (Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe)
        .allocateInstance(AppContainer::class.java) as AppContainer
    private var settings by mutableStateOf(initialSettings)
    private val pins = mutableListOf<Pair<PinnableHomeChannel, Boolean>>()
    private var allComicsOpened = 0

    @Test
    fun everyBuiltInHasAHandlePinToggleAndVisibilitySwitchInTheDefaultOrder() {
        compose()
        composeRule.onNodeWithContentDescription("固定「全分类」到首页").assertIsOff()
        composeRule.onNodeWithContentDescription("取消固定「随机」").assertIsOn()
        composeRule.onNodeWithContentDescription("取消固定「游戏专区」").assertIsOn()
        assertTrue(top(ALL_COMICS_CATEGORY.title) < top("随机"))
        assertTrue(top("随机") < top("游戏专区"))
        assertTrue(top("游戏专区") < top(first.title))
        for (name in listOf("全分类", "随机", "游戏专区")) {
            composeRule.onNodeWithContentDescription("拖动调整「$name」顺序", useUnmergedTree = true).assertExists()
            composeRule.onNodeWithContentDescription("在首页显示「$name」").assertIsOn()
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
    fun theCaptionExplainsThatVisibilityDoesNotChangePinnedShortcuts() {
        compose()
        composeRule.onNodeWithText("分类网格每行显示几列").assertDoesNotExist()
        composeRule.onNodeWithText("拖动把手调整顺序").assertDoesNotExist()
        composeRule.onNodeWithText(
            "隐藏只影响首页的分类网格，不影响固定到首页的入口",
        ).assertExists()
    }

    @Test
    fun hidingGamesPersistsItsIdDimsTheRowAndLeavesItsPinSelected() {
        compose()
        assertNotEquals(textColor(GAMES_CATEGORY.title), textColor(second.title))
        composeRule.onNodeWithContentDescription("在首页显示「游戏专区」").performClick().assertIsOff()

        val restored = readSettings()
        assertEquals(listOf(second.title, GAMES_CATEGORY.id), restored.hiddenCategories)
        assertEquals(initialSettings.pinnedHomeChannels, restored.pinnedHomeChannels)
        assertEquals(textColor(second.title), textColor(GAMES_CATEGORY.title))
        composeRule.onNodeWithContentDescription("取消固定「游戏专区」").assertIsOn()
        composeRule.onNodeWithText(GAMES_CATEGORY.title).assertExists()
        val home = buildHomeModel(listOf(first, second), restored)
        assertTrue(home.categories.none { it.id == GAMES_CATEGORY.id })
        assertTrue(home.channels.any { it.title == GAMES_CATEGORY.title })
    }

    @Test
    fun movingGamesBelowAServerCategoryIsStoredAndReturnsAfterSettingsReload() {
        compose()
        val actions = composeRule.onNodeWithContentDescription("拖动调整「游戏专区」顺序")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        composeRule.runOnIdle { assertTrue(actions.single { it.label == "下移" }.action()) }
        val restored = readSettings()
        assertEquals(listOf(ALL_COMICS_CATEGORY.id, RANDOM_CATEGORY.id, first.title, GAMES_CATEGORY.id, second.title), restored.categoryOrder)
        assertTrue(top(first.title) < top(GAMES_CATEGORY.title))

        composeRule.runOnIdle { settings = initialSettings }
        composeRule.waitForIdle()
        assertTrue(top(GAMES_CATEGORY.title) < top(first.title))
        composeRule.runOnIdle { settings = restored }
        composeRule.waitForIdle()
        assertTrue(top(first.title) < top(GAMES_CATEGORY.title))
        assertEquals(listOf(ALL_COMICS_CATEGORY, RANDOM_CATEGORY, first, GAMES_CATEGORY), buildHomeModel(listOf(first, second), restored).categories)
    }

    @Test
    fun allComicsStillOpensTheAllComicsList() {
        compose()
        composeRule.onNodeWithText(ALL_COMICS_CATEGORY.title).performClick()
        assertEquals(1, allComicsOpened)
    }

    private fun top(name: String): Float = composeRule.onNodeWithText(name).fetchSemanticsNode().boundsInRoot.top

    private fun textColor(name: String): Color {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(name).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single().layoutInput.style.color
    }

    private fun readSettings(): AppSettings = runBlocking { SettingsRepository(preferences, initialSettings).settings.first() }

    private fun updateSettings(update: suspend SettingsRepository.() -> Unit) {
        runBlocking { repository.update() }
        settings = readSettings()
    }

    private fun compose() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                PicaTheme(themeChoice = ThemeChoice.Brand, darkMode = DarkMode.Light) {
                    ManageContent(
                        categories = listOf(ALL_COMICS_CATEGORY, first, second),
                        settings = settings,
                        onPin = { name, pinned ->
                            val current = settings.pinnedHomeCategories - name
                            updateSettings { setPinnedHomeCategories(if (pinned) current + name else current) }
                        },
                        onChannelPin = { channel, pinned ->
                            pins += channel to pinned
                            val current = settings.pinnedHomeChannels
                            updateSettings { setPinnedHomeChannels(if (pinned) current + channel.id else current - channel.id) }
                        },
                        onVisible = { key, visible ->
                            val current = settings.hiddenCategories - key
                            updateSettings { setHiddenCategories(if (visible) current else current + key) }
                        },
                        onReorderPinned = { names -> updateSettings { setPinnedHomeCategories(names) } },
                        onReorderCategories = { keys -> updateSettings { setCategoryOrder(keys) } },
                        onColumnCount = {},
                        onOpenAllComics = { allComicsOpened++ },
                    )
                }
            }
        }
    }
}
