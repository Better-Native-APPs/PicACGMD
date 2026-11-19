package app.picacgmd.feature.search

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
import app.picacgmd.feature.comics.SELECTION_BACK_KEYWORD
import app.picacgmd.feature.comics.SELECTION_BACK_PREVIOUS_PAGE
import app.picacgmd.feature.comics.SelectionBackTestFixture
import app.picacgmd.feature.comics.selectionBackComics
import app.picacgmd.feature.comics.selectionBackString
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
class SearchSelectionBackTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private val fixture = SelectionBackTestFixture()

    @After fun closeFixture() = fixture.close()

    @Test
    fun backClearsSelectionWithoutLeavingOrMovingTheResultsThenTheNextBackLeaves() {
        composeRule.setContent {
            fixture.Content { onBack ->
                SearchScreen(
                    initialKeyword = SELECTION_BACK_KEYWORD,
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
        composeRule.onNodeWithText(SELECTION_BACK_KEYWORD).assertIsDisplayed()
        composeRule.onNodeWithText(selectedCount).assertDoesNotExist()
        composeRule.onNodeWithText(title).assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        assertEquals(top, composeRule.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top)

        composeRule.runOnIdle { fixture.activity.onBackPressedDispatcher.onBackPressed() }

        assertEquals(1, fixture.leaves)
        composeRule.onNodeWithText(SELECTION_BACK_PREVIOUS_PAGE).assertIsDisplayed()
    }
}
