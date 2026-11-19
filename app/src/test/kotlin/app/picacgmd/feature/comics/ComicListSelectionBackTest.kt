package app.picacgmd.feature.comics

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import app.picacgmd.testing.ComposeHostRule
import org.junit.After
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import app.picacgmd.core.designsystem.R as DesignSystemR

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], qualifiers = "w360dp-h860dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicListSelectionBackTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private val fixture = SelectionBackTestFixture()

    @After fun closeFixture() = fixture.close()

    @Test
    fun backClearsSelectionWithoutLeavingOrMovingTheListThenTheNextBackLeaves() {
        composeRule.setContent {
            fixture.Content { onBack ->
                ComicListScreen(
                    category = SELECTION_BACK_CATEGORY,
                    tag = null,
                    author = null,
                    onBack = onBack,
                    onComicClick = {},
                    onBatchDownload = {},
                )
            }
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(selectionBackComics.first().title).fetchSemanticsNodes().isNotEmpty()
        }
        val title = selectionBackComics[15].title
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(title))
        val top = composeRule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top
        composeRule.onNodeWithText(title).performTouchInput { longClick() }
        composeRule.onNodeWithText(title).assertIsSelected()
        val selectedCount = selectionBackString(DesignSystemR.string.designsystem_selected_count, 1)
        composeRule.onNodeWithText(selectedCount).assertIsDisplayed()

        composeRule.runOnIdle { fixture.activity.onBackPressedDispatcher.onBackPressed() }

        assertEquals(0, fixture.leaves)
        // The list's scroll offset can keep the normal app bar collapsed.
        composeRule.onNodeWithText(SELECTION_BACK_CATEGORY).assertExists()
        composeRule.onNodeWithText(selectedCount).assertDoesNotExist()
        composeRule.onNodeWithText(title).assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        assertEquals(top, composeRule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top)

        composeRule.runOnIdle { fixture.activity.onBackPressedDispatcher.onBackPressed() }

        assertEquals(1, fixture.leaves)
        composeRule.onNodeWithText(SELECTION_BACK_PREVIOUS_PAGE).assertIsDisplayed()
    }
}
