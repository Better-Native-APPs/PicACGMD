package app.picacgmd.reader

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.picacgmd.feature.reader.ReaderChapter
import app.picacgmd.feature.reader.ReaderConfig
import app.picacgmd.feature.reader.ReaderPage
import app.picacgmd.feature.reader.ReaderProgress
import app.picacgmd.feature.reader.ReaderRouteControls
import app.picacgmd.feature.reader.ReaderScreen
import app.picacgmd.feature.reader.ReaderSource
import app.picacgmd.testing.ComposeHostRule
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import app.picacgmd.feature.reader.R as ReaderR

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderRouteShortcutTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private val source = object : ReaderSource {
        override val comicTitle = "测试漫画：一个需要省略显示的长标题"

        override suspend fun loadChapters(): List<ReaderChapter> =
            listOf(ReaderChapter(id = "1", title = "第 1 话：一个需要省略显示的长章节标题"))

        override suspend fun loadPages(chapter: ReaderChapter): List<ReaderPage> = emptyList()

        override fun saveProgress(progress: ReaderProgress) = Unit
    }

    @Test
    fun routeShortcutIsShownAndOpensApiPicker() {
        var apiOpens = 0
        var imageOpens = 0
        reader(
            ReaderRouteControls(
                apiLabel = "分流 2",
                imageLabel = "不分流",
                onOpenApiRoutes = { apiOpens++ },
                onOpenImageRoutes = { imageOpens++ },
            ),
        )

        val shortcut = composeRule.onNodeWithContentDescription(readerString(ReaderR.string.reader_routes))
        val more = composeRule.onNodeWithContentDescription(readerString(ReaderR.string.reader_more))
        shortcut.assertIsDisplayed().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        more.assertIsDisplayed().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        composeRule.onNodeWithText(source.comicTitle).assertIsDisplayed()
        assertEquals(shortcut.fetchSemanticsNode().boundsInRoot.right, more.fetchSemanticsNode().boundsInRoot.left)

        shortcut.performClick()
        composeRule.runOnIdle {
            assertEquals(1, apiOpens)
            assertEquals(0, imageOpens)
        }
    }

    @Test
    fun routeShortcutIsAbsentWithoutRoutes() {
        reader(routes = null)

        composeRule.onNodeWithContentDescription(readerString(ReaderR.string.reader_more)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(readerString(ReaderR.string.reader_routes)).assertDoesNotExist()
    }

    private fun reader(routes: ReaderRouteControls?) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
                ReaderScreen(
                    source = source,
                    config = ReaderConfig(reduceMotion = true),
                    startChapter = 0,
                    startPage = 0,
                    onConfigChange = {},
                    onBack = {},
                    routes = routes,
                )
            }
        }
    }

    private fun readerString(resource: Int): String = RuntimeEnvironment.getApplication().getString(resource)
}
