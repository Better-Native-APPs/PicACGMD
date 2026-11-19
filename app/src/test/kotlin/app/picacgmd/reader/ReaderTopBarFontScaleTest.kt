package app.picacgmd.reader

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasAnyChild
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.picacgmd.feature.reader.PageImage
import app.picacgmd.feature.reader.ReaderChapter
import app.picacgmd.feature.reader.ReaderConfig
import app.picacgmd.feature.reader.ReaderPage
import app.picacgmd.feature.reader.ReaderProgress
import app.picacgmd.feature.reader.ReaderRouteControls
import app.picacgmd.feature.reader.ReaderScreen
import app.picacgmd.feature.reader.ReaderSource
import app.picacgmd.testing.ComposeHostRule
import org.junit.Rule
import java.io.File
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import app.picacgmd.core.designsystem.R as DesignR
import app.picacgmd.feature.reader.R as ReaderR

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderTopBarFontScaleTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private val chapterTitle = "第 1 话：一个在窄屏幕上一定放不下、需要省略显示的很长的章节标题"
    private val nextChapterTitle = "第 2 话"
    private val comicTitle = "测试漫画：一个在窄屏幕上一定放不下、需要省略显示的很长的漫画标题"

    private val source = object : ReaderSource {
        override val comicTitle = this@ReaderTopBarFontScaleTest.comicTitle

        override suspend fun loadChapters(): List<ReaderChapter> = listOf(
            ReaderChapter(id = "1", title = chapterTitle),
            ReaderChapter(id = "2", title = nextChapterTitle),
        )

        override suspend fun loadPages(chapter: ReaderChapter): List<ReaderPage> =
            listOf(ReaderPage(PageImage.Local(File("missing-page-${chapter.id}.jpg"))))

        override fun saveProgress(progress: ReaderProgress) = Unit
    }

    @Test
    fun barKeepsItsSizeAtTheDefaultFontScale() {
        reader(fontScale = 1f)

        bar().assertHeightIsEqualTo(56.dp).assertWidthIsEqualTo(336.dp).assertLeftPositionInRootIsEqualTo(12.dp)
        assertTitlesAndButtonsFit()
    }

    @Test
    fun barGrowsAroundBothTitleLinesAtTheLargestFontScale() {
        reader(fontScale = 2f)

        assertTitlesAndButtonsFit()
    }

    @Test
    fun barGrowsAroundBothTitleLinesAtALargeFontScale() {
        reader(fontScale = 1.5f)

        assertTitlesAndButtonsFit()
    }

    @Test
    fun chapterNameOverThePageStartsBelowTheGrownBar() {
        reader(fontScale = 2f)
        val bar = bar().fetchSemanticsNode().boundsInRoot

        // Invoked directly: a card over the last page lies where a tap on the button would land.
        composeRule.onNodeWithContentDescription(string(ReaderR.string.reader_next_chapter))
            .performSemanticsAction(SemanticsActions.OnClick)

        // The pill that names the new chapter is the only other place where its name is shown.
        val hints = composeRule
            .onAllNodes(hasText(nextChapterTitle) and !hasParent(hasAnyChild(hasText(comicTitle))), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.boundsInRoot }
        assertEquals(1, hints.size, "Chapter hints near the bar $bar: $hints")
        assertTrue(hints.single().top >= bar.bottom, "The chapter's name ${hints.single()} is under the bar $bar")
    }

    private fun assertTitlesAndButtonsFit() {
        val bar = bar().fetchSemanticsNode().boundsInRoot
        val buttons = listOf(
            composeRule.onNodeWithContentDescription(string(DesignR.string.back)),
            composeRule.onNodeWithContentDescription(string(ReaderR.string.reader_routes)),
            composeRule.onNodeWithContentDescription(string(ReaderR.string.reader_more)),
        )
        buttons.forEach { button ->
            button.assertIsDisplayed().assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
            val bounds = button.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= bar.left && bounds.right <= bar.right, "Button $bounds leaves the bar $bar")
            assertTrue(abs(bounds.center.y - bar.center.y) <= 1f, "Button $bounds is not centred in the bar $bar")
        }
        val titlesEnd = buttons[1].fetchSemanticsNode().boundsInRoot.left

        listOf(chapterTitle, comicTitle).forEach { title ->
            val node = title(title).assertIsDisplayed().fetchSemanticsNode()
            // The bounds are what is left after clipping: a smaller height means a cut line.
            assertEquals(node.size.height.toFloat(), node.boundsInRoot.height, "\"$title\" is cut vertically")
            assertTrue(
                node.boundsInRoot.top >= bar.top && node.boundsInRoot.bottom <= bar.bottom,
                "\"$title\" ${node.boundsInRoot} leaves the bar $bar",
            )
            assertTrue(node.boundsInRoot.right <= titlesEnd, "\"$title\" runs under the buttons")
            val layout = node.textLayout()
            assertEquals(1, layout.lineCount)
            assertTrue(layout.isLineEllipsized(0), "\"$title\" is not ellipsized")
            assertTrue(layout.getLineBottom(0) <= node.size.height, "\"$title\" needs ${layout.getLineBottom(0)} px and has ${node.size.height}")
        }
        val chapter = title(chapterTitle).fetchSemanticsNode().boundsInRoot
        val comic = title(comicTitle).fetchSemanticsNode().boundsInRoot
        assertTrue(chapter.bottom <= comic.top, "The two title lines overlap")
    }

    private fun bar(): SemanticsNodeInteraction = composeRule.onNode(hasAnyChild(hasText(comicTitle)), useUnmergedTree = true)

    /** The chapter's name is also announced over the page, so only the bar's own text counts. */
    private fun title(text: String): SemanticsNodeInteraction =
        composeRule.onNode(hasText(text) and hasParent(hasAnyChild(hasText(comicTitle))), useUnmergedTree = true)

    private fun SemanticsNode.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.single()
    }

    private fun reader(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ReaderScreen(
                    source = source,
                    config = ReaderConfig(reduceMotion = true),
                    startChapter = 0,
                    startPage = 0,
                    onConfigChange = {},
                    onBack = {},
                    routes = ReaderRouteControls(
                        apiLabel = "分流 2",
                        imageLabel = "不分流",
                        onOpenApiRoutes = {},
                        onOpenImageRoutes = {},
                    ),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun string(resource: Int): String = RuntimeEnvironment.getApplication().getString(resource)
}
