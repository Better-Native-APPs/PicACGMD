package app.picacgmd.feature.detail

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.picacgmd.R
import app.picacgmd.core.domain.repository.containsKeyword
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.Ep
import app.picacgmd.testing.ComposeHostRule
import app.picacgmd.testing.failureResponse
import app.picacgmd.testing.respondWith
import app.picacgmd.testing.successResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(application = DetailTestApplication::class, sdk = [29], qualifiers = "w360dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicDetailScreenTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private val fixture = DetailTestFixture()
    private val downloads = mutableListOf<Pair<ComicInfo, List<Ep>>>()

    @After fun closeFixture() = fixture.close()

    @Test
    fun blockingATagShowsUndoAndTheSettingsNoticeAndUndoRemovesTheWordSilently() {
        compose(hidden = listOf("kept"))
        composeRule.onNodeWithText(detailString(R.string.detail_hidden_by_rule)).assertDoesNotExist()

        chooseKeywordAction(DETAIL_TAG, R.string.detail_keyword_block)

        awaitBlocked(DETAIL_TAG, true)
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_blocked_message, DETAIL_TAG)).assertIsDisplayed()
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_undo)).assertIsDisplayed()
        composeRule.onNodeWithText(detailString(R.string.detail_hidden_by_rule)).assertIsDisplayed()
        composeRule.onNodeWithText(detailString(R.string.detail_hidden_by_rule_where)).assertIsDisplayed()

        composeRule.onNodeWithText(detailString(R.string.detail_keyword_undo)).performClick()

        awaitBlocked(DETAIL_TAG, false)
        assertEquals(listOf("kept"), hiddenKeywords())
        assertNoFurtherKeywordMessage(DETAIL_TAG)
        composeRule.onNodeWithText(detailString(R.string.detail_hidden_by_rule)).assertDoesNotExist()
    }

    @Test
    fun unblockingACategoryShowsUndoAndUndoRestoresTheBlockSilently() {
        compose(hidden = listOf(DETAIL_CATEGORY, "kept"))
        composeRule.onNodeWithText(detailString(R.string.detail_hidden_by_rule)).assertIsDisplayed()
        composeRule.onNodeWithText(detailString(R.string.detail_hidden_by_rule_where)).assertIsDisplayed()

        chooseKeywordAction(DETAIL_CATEGORY, R.string.detail_keyword_unblock)

        awaitBlocked(DETAIL_CATEGORY, false)
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_unblocked_message, DETAIL_CATEGORY)).assertIsDisplayed()
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_undo)).assertIsDisplayed().performClick()

        awaitBlocked(DETAIL_CATEGORY, true)
        assertEquals(listOf("kept", DETAIL_CATEGORY), hiddenKeywords())
        assertNoFurtherKeywordMessage(DETAIL_CATEGORY)
        composeRule.onNodeWithText(detailString(R.string.detail_hidden_by_rule)).assertIsDisplayed()
    }

    @Test
    fun newestKeywordChangeReplacesTheOldMessageAndUndoOnlyRevertsTheNewestWord() {
        compose()
        chooseKeywordAction(DETAIL_CATEGORY, R.string.detail_keyword_block)
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_blocked_message, DETAIL_CATEGORY)).assertIsDisplayed()

        chooseKeywordAction(DETAIL_TAG, R.string.detail_keyword_block)

        awaitBlocked(DETAIL_TAG, true)
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_blocked_message, DETAIL_CATEGORY)).assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_blocked_message, DETAIL_TAG)).assertIsDisplayed()
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_undo)).performClick()

        awaitBlocked(DETAIL_TAG, false)
        assertEquals(listOf(DETAIL_CATEGORY), hiddenKeywords())
        assertNoFurtherKeywordMessage(DETAIL_TAG)
        composeRule.onNodeWithText(detailString(R.string.detail_hidden_by_rule)).assertIsDisplayed()
    }

    @Test
    fun failedActionKeepsTheBottomFailureMessage() {
        compose()

        composeRule.onNodeWithContentDescription(detailString(R.string.detail_like)).performClick()

        val failure = detailString(R.string.detail_action_failed, "failure")
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithText(failure).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(failure).assertIsDisplayed()
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_undo)).assertDoesNotExist()
    }

    @Test
    fun downloadPickerKeepsSelectAllIndividualSelectionAndTheChosenChaptersCallback() {
        compose()

        composeRule.onNodeWithContentDescription(detailString(R.string.detail_download)).performClick()
        composeRule.onNodeWithText(detailString(R.string.detail_download_title)).assertIsDisplayed()
        composeRule.onNodeWithText(detailString(R.string.detail_download_confirm, 2)).assertIsDisplayed()
        composeRule.onNodeWithText(detailString(R.string.comics_select_all)).performClick()
        composeRule.onNodeWithText(detailString(R.string.detail_download_confirm, 0)).assertIsNotEnabled()
        composeRule.onNodeWithText(detailString(R.string.comics_select_all)).performClick()
        composeRule.onNode(hasText("第 2 话") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).performClick()
        composeRule.onNodeWithText(detailString(R.string.detail_download_confirm, 1)).performClick()

        assertEquals(listOf(detailComic to listOf(detailChapters(2).first())), downloads)
        composeRule.onNodeWithText(detailString(R.string.detail_download_title)).assertDoesNotExist()
    }

    private fun compose(hidden: List<String> = emptyList()) {
        fixture.pica.respondWith { request ->
            when (request.url.encodedPath) {
                "/comics/${detailComic.id}" -> successResponse("{\"comic\":${Json.encodeToString(detailComic)}}")
                "/comics/${detailComic.id}/eps" -> successResponse("{\"eps\":{\"docs\":${Json.encodeToString(detailChapters(2))},\"page\":1,\"pages\":1}}")
                "/comics/${detailComic.id}/recommendation" -> successResponse("{\"comics\":[]}")
                "/comics/${detailComic.id}/comments" -> successResponse("{\"comments\":{\"docs\":[],\"page\":1,\"pages\":1}}")
                "/comics/${detailComic.id}/like" -> failureResponse()
                else -> error("Unexpected detail request ${request.url.encodedPath}")
            }
        }
        runBlocking { fixture.environment.settings.setHiddenKeywords(hidden) }
        (RuntimeEnvironment.getApplication() as DetailTestApplication).downloadManager = fixture.environment.downloads
        composeRule.setContent {
            fixture.Content {
                ComicDetailScreen(
                    comicId = detailComic.id,
                    onBack = {},
                    onRead = { _, _, _ -> },
                    onDownloadChapters = { comic, chapters -> downloads += comic to chapters },
                    onCategoryClick = {},
                    onTagClick = {},
                    onAuthorClick = {},
                    onTeamClick = {},
                    onCreatorClick = { _, _ -> },
                    onComicClick = {},
                    onOpenComments = {},
                )
            }
        }
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(detailString(R.string.detail_chapters_forward)).fetchSemanticsNodes().size == 1
        }
        composeRule.waitForIdle()
    }

    private fun chooseKeywordAction(word: String, action: Int) {
        composeRule.onNodeWithText(word).performTouchInput { longClick() }
        composeRule.onNodeWithText(detailString(action)).performClick()
        composeRule.waitForIdle()
    }

    private fun hiddenKeywords(): List<String> = runBlocking { fixture.environment.settings.settings.first().hiddenKeywords }

    private fun awaitBlocked(word: String, expected: Boolean) {
        composeRule.waitUntil(10_000) { hiddenKeywords().containsKeyword(word) == expected }
        composeRule.waitForIdle()
    }

    private fun assertNoFurtherKeywordMessage(word: String) {
        composeRule.waitForIdle()
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_blocked_message, word)).assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_unblocked_message, word)).assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_undo)).assertDoesNotExist()
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)
        composeRule.mainClock.advanceTimeBy(5_000)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_blocked_message, word)).assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_unblocked_message, word)).assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_undo)).assertDoesNotExist()
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertCountEquals(0)
    }
}
