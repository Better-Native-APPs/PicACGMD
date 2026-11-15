package app.picacgmd.feature.sync

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.picacgmd.core.network.webdav.WebDavError
import app.picacgmd.core.network.webdav.WebDavResult
import app.picacgmd.testing.ComposeHostRule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Sizes on a 360 dp wide screen at font scale 1.3, with text measured by the real text engine. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h1600dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebDavSyncPanelLayoutTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private val actions = FakeSyncActions()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun everyActionKeepsItsWholeLabelOnOneLineAndA48DpTarget() {
        val viewModel = WebDavSyncViewModel(actions, scope)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
                MaterialTheme { WebDavSyncPanel(viewModel, onBack = {}) }
            }
        }
        actions.connectionResult = WebDavResult.Failure(WebDavError.CHANGED_ELSEWHERE)
        click("测试连接")
        composeRule.onNodeWithContentDescription("关闭提示").assertTouchTarget()

        click("修改")
        composeRule.onNodeWithTag(WebDavPanelTags.Folder).performTextReplacement("阅读记录")
        listOf("立即同步", "测试连接", "保存", "取消").forEach { label ->
            assertWholeLabel(label)
            action(label).assertTouchTarget()
        }
        composeRule.onNodeWithContentDescription("显示密码").assertTouchTarget()
        composeRule.onNodeWithContentDescription("返回").assertTouchTarget()
        click("取消")
        listOf("修改", "用本机覆盖云端", "用云端覆盖本机", "自动同步", "仅在 Wi-Fi 下自动同步").forEach { action(it).assertTouchTarget() }

        click("用云端覆盖本机")
        listOf("覆盖本机", "取消").forEach { label ->
            assertWholeLabel(label)
            action(label).assertTouchTarget()
        }
    }

    private fun action(label: String): SemanticsNodeInteraction = composeRule.onNode(hasText(label) and hasClickAction())

    private fun click(label: String) {
        action(label).performClick()
        composeRule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.assertTouchTarget() {
        val node = fetchSemanticsNode()
        val bounds = node.touchBoundsInRoot
        with(node.layoutInfo.density) {
            assertTrue(bounds.width.toDp() >= 47.5.dp && bounds.height.toDp() >= 47.5.dp, "touch target ${bounds.width.toDp()} x ${bounds.height.toDp()}")
        }
    }

    /** The label is on one line, not shortened, and its button lies wholly inside the screen. */
    private fun assertWholeLabel(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNode(hasText(label) and !hasClickAction(), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val lineWidth = layout.getLineRight(0) - layout.getLineLeft(0)
        assertEquals(1, layout.lineCount, label)
        assertFalse(layout.isLineEllipsized(0), label)
        assertFalse(layout.didOverflowHeight, label)
        assertTrue(lineWidth <= layout.size.width + 1f, "$label is ${lineWidth}px wide in ${layout.size}")
        // The engine really measured the glyphs: a character is about as wide as it is tall.
        assertTrue(lineWidth > label.length * layout.size.height / 2f, "$label was not measured: ${layout.size}")
        val button = action(label).fetchSemanticsNode()
        val screenWidth = with(button.layoutInfo.density) { 360.dp.toPx() }
        assertTrue(button.boundsInRoot.left >= 0f && button.boundsInRoot.right <= screenWidth, "$label ${button.boundsInRoot} in $screenWidth")
    }
}
