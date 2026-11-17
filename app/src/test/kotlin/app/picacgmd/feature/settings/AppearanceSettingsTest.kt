package app.picacgmd.feature.settings

import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import app.picacgmd.core.data.settings.FullScreenDisplay
import app.picacgmd.testing.ComposeHostRule
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppearanceSettingsTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    @Test
    fun fullScreenChooserShowsFourOrderedLabelsWithoutTruncationAtLargeFontScale() {
        var selected: FullScreenDisplay? = null
        var dismissed = false
        lateinit var view: View
        composeRule.setContent {
            val currentView = LocalView.current
            SideEffect { view = currentView }
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1.3f)) {
                MaterialTheme {
                    ChoiceDialog(
                        title = "全屏显示",
                        description = "仅阅读漫画时生效",
                        options = FullScreenDisplay.entries,
                        selected = FullScreenDisplay.OFF,
                        label = { it.label() },
                        onSelect = { selected = it },
                        onDismiss = { dismissed = true },
                    )
                }
            }
        }
        composeRule.runOnIdle {
            assertEquals(360, view.resources.configuration.screenWidthDp)
            assertEquals(360, view.resources.displayMetrics.widthPixels)
        }
        val labels = listOf("不隐藏", "隐藏状态栏", "隐藏导航栏", "全部隐藏")
        assertEquals(labels, FullScreenDisplay.entries.map { it.label() })
        var previousTop = Float.NEGATIVE_INFINITY
        labels.forEach { label ->
            val node = composeRule.onNodeWithText(label).assertIsDisplayed()
            val layout = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
            assertEquals(1, layout.size)
            val result = layout.single()
            assertEquals(label, result.layoutInput.text.text)
            assertFalse(result.didOverflowHeight, label)
            assertEquals(label.length, result.getLineEnd(result.lineCount - 1, visibleEnd = true), label)
            // A paragraph can retain its parent's width after Text shrinks to its intrinsic width.
            repeat(result.lineCount) { line ->
                assertFalse(result.isLineEllipsized(line), label)
                assertTrue(result.getLineLeft(line) >= 0f, label)
                assertTrue(result.getLineRight(line) <= result.size.width.toFloat(), label)
            }
            val top = node.fetchSemanticsNode().boundsInRoot.top
            assertTrue(top > previousTop)
            previousTop = top
        }
        composeRule.onNodeWithText("隐藏状态栏").performClick()
        composeRule.runOnIdle {
            assertEquals(FullScreenDisplay.HIDE_STATUS_BAR, selected)
            assertTrue(dismissed)
        }
    }
}
