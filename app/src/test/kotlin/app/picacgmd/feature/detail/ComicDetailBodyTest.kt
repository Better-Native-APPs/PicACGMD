package app.picacgmd.feature.detail

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import app.picacgmd.R
import app.picacgmd.core.data.settings.AppSettings
import app.picacgmd.core.domain.repository.ReadingProgress
import app.picacgmd.core.network.ComicInfo
import app.picacgmd.core.network.Comment
import app.picacgmd.core.network.Ep
import app.picacgmd.testing.ComposeHostRule
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w360dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicDetailBodyTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private val fixture = DetailTestFixture()
    private val authors = mutableListOf<String>()
    private val teams = mutableListOf<String>()
    private val creators = mutableListOf<Pair<String, String>>()
    private val categories = mutableListOf<String>()
    private val tags = mutableListOf<String>()
    private val keywordChanges = mutableListOf<String>()
    private val tagFavouriteChanges = mutableListOf<String>()
    private val reads = mutableListOf<Pair<Int, Int>>()
    private val actions = mutableListOf<String>()
    private var commentOpens = 0
    private var blockedWords by mutableStateOf<List<String>>(emptyList())
    private var favouriteTags by mutableStateOf<Set<String>>(emptySet())
    private var tagFavouritesEnabled by mutableStateOf(true)

    @After fun closeFixture() = fixture.close()

    @Test
    fun titleLongPressWithConfirmationShowsTheExactTemplatePreviewAndCopiesOnlyWhenChosen() {
        compose(preferences = AppSettings(copyNameTemplate = "{title} / {author} / {id}", confirmCopy = true))
        seedClipboard()

        title().performTouchInput { longClick() }

        composeRule.onNodeWithText(detailString(R.string.detail_copy_name)).assertIsDisplayed()
        val expected = "${detailComic.title} / ${detailComic.author} / ${detailComic.id}"
        composeRule.onNodeWithText(expected, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(CLIPBOARD_SENTINEL, clipboardText())
        composeRule.onNodeWithText(detailString(R.string.detail_copy_name)).performClick()
        assertEquals(expected, clipboardText())
        composeRule.onNodeWithText(detailString(R.string.detail_copy_name)).assertDoesNotExist()
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)).assertCountEquals(0)
    }

    @Test
    fun titleLongPressWithoutConfirmationCopiesImmediatelyWithoutAMenu() {
        compose(preferences = AppSettings(copyNameTemplate = "{id}: {title}", confirmCopy = false))
        seedClipboard()

        title().performTouchInput { longClick() }

        assertEquals("${detailComic.id}: ${detailComic.title}", clipboardText())
        composeRule.onNodeWithText(detailString(R.string.detail_copy_name)).assertDoesNotExist()
    }

    @Test
    fun tappingTheTitleDoesNothingAndThereIsNoTopRightCopyButton() {
        compose(preferences = AppSettings(confirmCopy = false))
        seedClipboard()

        title().assertHasNoClickAction().performTouchInput { click() }

        assertEquals(CLIPBOARD_SENTINEL, clipboardText())
        composeRule.onNodeWithText(detailString(R.string.detail_copy_name)).assertDoesNotExist()
        composeRule.onAllNodes(
            hasClickAction() and (hasContentDescription(detailString(R.string.detail_copy_name)) or hasContentDescription("复制标题")),
        ).assertCountEquals(0)
        assertTrue(authors.isEmpty())
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun collapsedTopBarTitleUsesTheSameConfirmationMenuAndPreview() {
        compose(comic = detailComic.copy(epsCount = 46), chapters = detailChapters(46), preferences = AppSettings(copyNameTemplate = "{author} — {title}", confirmCopy = true))
        scrollTo(detailString(R.string.detail_comments_all))
        assertEquals(1, textLayout(detailComic.title).layoutInput.maxLines)
        seedClipboard()

        title().performTouchInput { longClick() }

        val expected = "${detailComic.author} — ${detailComic.title}"
        composeRule.onNodeWithText(expected, useUnmergedTree = true).assertIsDisplayed()
        assertEquals(CLIPBOARD_SENTINEL, clipboardText())
        composeRule.onNodeWithText(detailString(R.string.detail_copy_name)).performClick()
        assertEquals(expected, clipboardText())
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-mdpi")
    fun collapsedTopBarTitleCopiesDirectlyWhenConfirmationIsOff() {
        compose(comic = detailComic.copy(epsCount = 46), chapters = detailChapters(46), preferences = AppSettings(copyNameTemplate = "{title}", confirmCopy = false))
        scrollTo(detailString(R.string.detail_comments_all))
        assertEquals(1, textLayout(detailComic.title).layoutInput.maxLines)
        seedClipboard()

        title().performTouchInput { longClick() }

        assertEquals(detailComic.title, clipboardText())
        composeRule.onNodeWithText(detailString(R.string.detail_copy_name)).assertDoesNotExist()
    }

    @Test
    fun titleCopyIsAlsoAvailableAsAnAccessibilityAction() {
        compose(preferences = AppSettings(copyNameTemplate = "{title}", confirmCopy = false))
        seedClipboard()

        customAction(title(), R.string.detail_copy_name)

        assertEquals(detailComic.title, clipboardText())
    }

    @Test
    fun peopleAreOneOrderedBlockAndEachRowOpensItsOwnPersonWithoutADuplicateAuthor() {
        compose(comic = detailComic.copy(description = "简介在人物上方。"))
        val authorLabel = composeRule.onNodeWithText(detailString(R.string.detail_author), useUnmergedTree = true).assertIsDisplayed()
        val teamLabel = composeRule.onNodeWithText(detailString(R.string.detail_team), useUnmergedTree = true).assertIsDisplayed()
        val uploaderLabel = composeRule.onNodeWithText(detailString(R.string.detail_uploader), useUnmergedTree = true).assertIsDisplayed()
        val labelBounds = listOf(authorLabel, teamLabel, uploaderLabel).map { it.fetchSemanticsNode().boundsInRoot }
        assertTrue(labelBounds[0].top < labelBounds[1].top && labelBounds[1].top < labelBounds[2].top)
        assertEquals(labelBounds[0].left, labelBounds[1].left)
        assertEquals(labelBounds[1].left, labelBounds[2].left)
        composeRule.onAllNodesWithText(detailComic.author, useUnmergedTree = true).assertCountEquals(1)
        assertTrue(labelBounds[0].top > composeRule.onNodeWithText("简介在人物上方。", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom)

        composeRule.onNodeWithText(detailComic.author).performClick()
        composeRule.onNodeWithText(detailComic.chineseTeam).performClick()
        uploader().performClick()

        assertEquals(listOf(detailComic.author), authors)
        assertEquals(listOf(detailComic.chineseTeam), teams)
        assertEquals(listOf("uploader-id" to "测试上传"), creators)
    }

    @Test
    fun authorSearchOffLeavesTheAuthorVisibleButNotClickable() {
        compose(preferences = AppSettings(showAuthorSearch = false))

        composeRule.onNodeWithText(detailComic.author).assertHasNoClickAction().performTouchInput { click() }

        assertTrue(authors.isEmpty())
        composeRule.onNodeWithText(detailComic.chineseTeam).assertHasClickAction()
        uploader().assertHasClickAction()
    }

    @Test
    fun missingTranslatorLeavesNoTranslatorRow() {
        compose(comic = detailComic.copy(chineseTeam = ""))

        composeRule.onNodeWithText(detailString(R.string.detail_team), useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText(detailComic.chineseTeam, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText(detailComic.author).assertIsDisplayed()
        uploader().assertIsDisplayed()
    }

    @Test
    fun missingUploaderLeavesNoUploaderRow() {
        compose(comic = detailComic.copy(creator = null))

        composeRule.onNodeWithText(detailString(R.string.detail_uploader), useUnmergedTree = true).assertDoesNotExist()
        uploader().assertDoesNotExist()
        composeRule.onNodeWithText(detailComic.author).assertIsDisplayed()
        composeRule.onNodeWithText(detailComic.chineseTeam).assertIsDisplayed()
    }

    @Test
    fun missingTranslatorAndUploaderLeaveOnlyTheAuthorRow() {
        compose(comic = detailComic.copy(chineseTeam = "", creator = null))

        composeRule.onNodeWithText(detailString(R.string.detail_team), useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_uploader), useUnmergedTree = true).assertDoesNotExist()
        composeRule.onAllNodesWithText(detailComic.author, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun tappingCategoriesAndTagsAlwaysOpensThemEvenWhenTheyAreBlockedOrFavourited() {
        compose(hidden = listOf(DETAIL_CATEGORY, DETAIL_TAG), favourites = setOf(DETAIL_TAG))

        chip(DETAIL_CATEGORY).performClick()
        chip(DETAIL_TAG).performClick()

        assertEquals(listOf(DETAIL_CATEGORY), categories)
        assertEquals(listOf(DETAIL_TAG), tags)
        assertTrue(keywordChanges.isEmpty())
        assertTrue(tagFavouriteChanges.isEmpty())
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_unblock)).assertDoesNotExist()
    }

    @Test
    fun categoryLongPressOffersOnlyBlockingAndChoosingItBlocksThenUnblocksThatCategory() {
        compose()

        chip(DETAIL_CATEGORY).performTouchInput { longClick() }
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_block)).assertIsDisplayed()
        composeRule.onNodeWithText(detailString(R.string.detail_tag_favourite)).assertDoesNotExist()
        assertTrue(keywordChanges.isEmpty())
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_block)).performClick()
        assertEquals(listOf(DETAIL_CATEGORY), blockedWords)
        assertEquals(detailString(R.string.detail_keyword_blocked), stateDescription(chip(DETAIL_CATEGORY)))

        chip(DETAIL_CATEGORY).performTouchInput { longClick() }
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_unblock)).performClick()
        assertTrue(blockedWords.isEmpty())
        assertEquals(listOf(DETAIL_CATEGORY, DETAIL_CATEGORY), keywordChanges)
        assertTrue(categories.isEmpty())
    }

    @Test
    fun tagLongPressOffersFavouriteAndBlockAndBothMenuEntriesActOnThatTag() {
        compose()

        chip(DETAIL_TAG).performTouchInput { longClick() }
        composeRule.onNodeWithText(detailString(R.string.detail_tag_favourite)).assertIsDisplayed().performClick()
        assertEquals(setOf(DETAIL_TAG), favouriteTags)
        assertEquals(detailString(R.string.detail_tag_favourited), stateDescription(chip(DETAIL_TAG)))

        chip(DETAIL_TAG).performTouchInput { longClick() }
        composeRule.onNodeWithText(detailString(R.string.detail_tag_unfavourite)).assertIsDisplayed()
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_block)).performClick()
        assertEquals(listOf(DETAIL_TAG), blockedWords)
        assertEquals(listOf(DETAIL_TAG), keywordChanges)
        assertEquals(listOf(DETAIL_TAG), tagFavouriteChanges)
        assertTrue(tags.isEmpty())
    }

    @Test
    fun blockedAndFavouritedTagShowsOnlyTheBlockedStateButOffersBothRemovalActions() {
        compose(hidden = listOf(DETAIL_TAG), favourites = setOf(DETAIL_TAG))
        assertEquals(detailString(R.string.detail_keyword_blocked), stateDescription(chip(DETAIL_TAG)))

        chip(DETAIL_TAG).performTouchInput { longClick() }
        composeRule.onNodeWithText(detailString(R.string.detail_tag_unfavourite)).assertIsDisplayed().performClick()
        assertTrue(favouriteTags.isEmpty())
        assertEquals(detailString(R.string.detail_keyword_blocked), stateDescription(chip(DETAIL_TAG)))

        chip(DETAIL_TAG).performTouchInput { longClick() }
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_unblock)).performClick()
        assertTrue(blockedWords.isEmpty())
        assertNull(stateDescription(chip(DETAIL_TAG)))
    }

    @Test
    fun tagFavouritesOffRemovesFavouriteMenuActionsAndMarksButKeepsBlockingAndOpening() {
        compose(preferences = AppSettings(showTagFavourites = false), favourites = setOf(DETAIL_TAG))
        assertNull(stateDescription(chip(DETAIL_TAG)))
        assertEquals(listOf(detailString(R.string.detail_keyword_block)), customActionLabels(chip(DETAIL_TAG)))

        chip(DETAIL_TAG).performTouchInput { longClick() }
        composeRule.onNodeWithText(detailString(R.string.detail_tag_favourite)).assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_tag_unfavourite)).assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_keyword_block)).performClick()
        chip(DETAIL_TAG).performClick()

        assertEquals(listOf(DETAIL_TAG), keywordChanges)
        assertEquals(listOf(DETAIL_TAG), tags)
        assertTrue(tagFavouriteChanges.isEmpty())
    }

    @Test
    fun turningOffTagFavouritesRemovesAnAlreadyVisibleFavouriteMark() {
        compose(favourites = setOf(DETAIL_TAG))
        assertEquals(detailString(R.string.detail_tag_favourited), stateDescription(chip(DETAIL_TAG)))

        composeRule.runOnIdle { tagFavouritesEnabled = false }

        assertNull(stateDescription(chip(DETAIL_TAG)))
        assertEquals(listOf(detailString(R.string.detail_keyword_block)), customActionLabels(chip(DETAIL_TAG)))
    }

    @Test
    fun categoriesAndTagsExposeTheSameBlockAndFavouriteActionsToAccessibility() {
        compose()
        assertEquals(listOf(detailString(R.string.detail_keyword_block)), customActionLabels(chip(DETAIL_CATEGORY)))
        assertEquals(
            setOf(detailString(R.string.detail_tag_favourite), detailString(R.string.detail_keyword_block)),
            customActionLabels(chip(DETAIL_TAG)).toSet(),
        )

        customAction(chip(DETAIL_CATEGORY), R.string.detail_keyword_block)
        customAction(chip(DETAIL_TAG), R.string.detail_tag_favourite)
        customAction(chip(DETAIL_TAG), R.string.detail_keyword_block)
        assertEquals(detailString(R.string.detail_keyword_blocked), stateDescription(chip(DETAIL_TAG)))
        customAction(chip(DETAIL_TAG), R.string.detail_tag_unfavourite)
        customAction(chip(DETAIL_TAG), R.string.detail_keyword_unblock)
        customAction(chip(DETAIL_CATEGORY), R.string.detail_keyword_unblock)

        assertTrue(blockedWords.isEmpty())
        assertTrue(favouriteTags.isEmpty())
        assertEquals(listOf(DETAIL_CATEGORY, DETAIL_TAG, DETAIL_TAG, DETAIL_CATEGORY), keywordChanges)
        assertEquals(listOf(DETAIL_TAG, DETAIL_TAG), tagFavouriteChanges)
        assertTrue(categories.isEmpty())
        assertTrue(tags.isEmpty())
    }

    @Test
    fun keywordMenusReplaceTheOldModeSwitchesWithoutAGestureHint() {
        compose()

        composeRule.onNodeWithText("屏蔽模式").assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_tag_favourite)).assertDoesNotExist()
        composeRule.onNodeWithText("长按可屏蔽", substring = true).assertDoesNotExist()
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState) and hasText("屏蔽模式")).assertCountEquals(0)
    }

    @Test
    fun oneLineDescriptionHasNoExpansionControlAndTappingItDoesNothing() {
        val description = "简短简介。"
        compose(comic = detailComic.copy(description = description))
        assertEquals(1, textLayout(description).lineCount)
        composeRule.onNodeWithText(description).assertHasNoClickAction().performTouchInput { click() }

        composeRule.onNodeWithText(detailString(R.string.detail_expand)).assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_collapse)).assertDoesNotExist()
        assertEquals(1, textLayout(description).lineCount)
    }

    @Test
    fun descriptionThatReallyFitsExactlyFourLinesHasNoExpansionControl() {
        val description = "这是一段普通漫画简介。".repeat(7)
        compose(comic = detailComic.copy(description = description))

        val layout = textLayout(description)
        assertEquals(4, layout.lineCount)
        composeRule.onNodeWithText(detailString(R.string.detail_expand)).assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_collapse)).assertDoesNotExist()
        assertFalse(layout.hasVisualOverflow, "height=${layout.didOverflowHeight}, width=${layout.didOverflowWidth}, size=${layout.size}, maxLines=${layout.layoutInput.maxLines}")
    }

    @Test
    fun fourShortExplicitLinesKeepAllTheirTextVisibleWithoutAnExpansionControl() {
        val lines = listOf("第一行简介", "第二行简介", "第三行简介", "第四行简介")
        val description = lines.joinToString("\n")
        compose(comic = detailComic.copy(description = description))

        val layout = textLayout(description)
        assertEquals(4, layout.lineCount)
        assertFalse(layout.didOverflowHeight)
        var end = 0
        lines.forEachIndexed { index, line ->
            end += line.length
            assertEquals(end, layout.getLineEnd(index, visibleEnd = true))
            assertFalse(layout.isLineEllipsized(index))
            end++
        }
        composeRule.onNodeWithText(description).assertHasNoClickAction().performTouchInput { click() }
        composeRule.onNodeWithText(detailString(R.string.detail_expand)).assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_collapse)).assertDoesNotExist()
    }

    @Test
    fun overflowingDescriptionExpandsFromTextOrControlAndCollapsesFromEither() {
        compose(comic = detailComic.copy(description = LONG_DESCRIPTION))
        assertEquals(4, textLayout(LONG_DESCRIPTION).lineCount)
        assertTrue(textLayout(LONG_DESCRIPTION).isLineEllipsized(3))
        composeRule.onNodeWithText(detailString(R.string.detail_expand), useUnmergedTree = true).assertIsDisplayed()

        composeRule.onNodeWithText(LONG_DESCRIPTION, useUnmergedTree = true).performTouchInput { click() }
        assertTrue(textLayout(LONG_DESCRIPTION).lineCount > 4)
        assertFalse(textLayout(LONG_DESCRIPTION).hasVisualOverflow)
        composeRule.onNodeWithText(detailString(R.string.detail_collapse)).performClick()
        assertEquals(4, textLayout(LONG_DESCRIPTION).lineCount)

        composeRule.onNodeWithText(detailString(R.string.detail_expand)).performClick()
        assertTrue(textLayout(LONG_DESCRIPTION).lineCount > 4)
        composeRule.onNodeWithText(LONG_DESCRIPTION, useUnmergedTree = true).performTouchInput { click() }
        assertEquals(4, textLayout(LONG_DESCRIPTION).lineCount)
        composeRule.onNodeWithText(detailString(R.string.detail_expand), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun absentOrBlankDescriptionHasNoDescriptionBlock() {
        compose(comic = detailComic.copy(description = " \n "))

        composeRule.onNodeWithText(" \n ", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_expand)).assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_collapse)).assertDoesNotExist()
    }

    @Test
    fun expandedDescriptionSurvivesSavedInstanceStateRestoration() {
        val restoration = StateRestorationTester(composeRule)
        compose(comic = detailComic.copy(description = LONG_DESCRIPTION), restoration = restoration)
        composeRule.onNodeWithText(detailString(R.string.detail_expand)).performClick()
        assertTrue(textLayout(LONG_DESCRIPTION).lineCount > 4)

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText(detailString(R.string.detail_collapse), useUnmergedTree = true).assertIsDisplayed()
        assertTrue(textLayout(LONG_DESCRIPTION).lineCount > 4)
    }

    @Test
    fun chapterSortBesideTheHeadingNamesTheCurrentOrderAndReordersTheRows() {
        compose()
        val heading = composeRule.onNodeWithText(detailString(R.string.detail_chapters, 2)).fetchSemanticsNode().boundsInRoot
        val forward = composeRule.onNodeWithText(detailString(R.string.detail_chapters_forward)).assertHasClickAction()
        val sort = forward.fetchSemanticsNode().boundsInRoot
        assertTrue(sort.center.y in heading.top..heading.bottom)
        assertTrue(sort.left > heading.left)
        composeRule.onNodeWithContentDescription(detailString(R.string.detail_chapters_order, detailString(R.string.detail_chapters_forward))).assertIsDisplayed()
        assertChapterAbove(1, 2)

        forward.performClick()

        composeRule.onNodeWithText(detailString(R.string.detail_chapters_reverse)).assertIsDisplayed()
        composeRule.onNodeWithText(detailString(R.string.detail_chapters_forward)).assertDoesNotExist()
        assertChapterAbove(2, 1)
        composeRule.onNodeWithText(detailString(R.string.detail_chapters_reverse)).performClick()
        assertChapterAbove(1, 2)
        composeRule.onNodeWithText(detailString(R.string.detail_chapters_forward)).assertIsDisplayed()
    }

    @Test
    fun oneChapterHasNoSortOrExpandAllControl() {
        compose(comic = detailComic.copy(epsCount = 1), chapters = detailChapters(1))

        composeRule.onNodeWithText(detailString(R.string.detail_chapters, 1)).assertIsDisplayed()
        assertNoChapterOrder()
        composeRule.onNodeWithText(detailString(R.string.detail_chapters_expand, 1)).assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_collapse)).assertDoesNotExist()
    }

    @Test
    fun chaptersNotLoadedHaveNoSortEvenWhenTheComicAdvertisesManyChapters() {
        compose(comic = detailComic.copy(epsCount = 46), chapters = null)

        composeRule.onNodeWithText(detailString(R.string.detail_chapters, 46)).assertIsDisplayed()
        assertNoChapterOrder()
        composeRule.onNodeWithText(detailString(R.string.detail_chapters_expand, 46)).assertDoesNotExist()
    }

    @Test
    fun eightChaptersHaveNoExpandAllControl() {
        compose(comic = detailComic.copy(epsCount = 8), chapters = detailChapters(8))

        composeRule.onNodeWithText(detailString(R.string.detail_chapters_expand, 8)).assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_collapse)).assertDoesNotExist()
    }

    @Test
    fun moreThanEightChaptersCanExpandAllAndCollapseBackToEight() {
        compose(comic = detailComic.copy(epsCount = 9), chapters = detailChapters(9))
        composeRule.onNodeWithText(detailChapters(9).last().title).assertDoesNotExist()
        scrollTo(detailString(R.string.detail_chapters_expand, 9))

        composeRule.onNodeWithText(detailString(R.string.detail_chapters_expand, 9)).performClick()
        scrollTo(detailChapters(9).last().title)
        composeRule.onNodeWithText(detailChapters(9).last().title).assertIsDisplayed()
        scrollTo(detailString(R.string.detail_collapse))
        composeRule.onNodeWithText(detailString(R.string.detail_collapse)).performClick()

        scrollTo(detailString(R.string.detail_chapters_expand, 9))
        composeRule.onNodeWithText(detailChapters(9).last().title).assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_chapters_expand, 9)).assertIsDisplayed()
    }

    @Test
    fun chapterOrderAndExpandedChapterListSurviveSavedInstanceStateRestoration() {
        val restoration = StateRestorationTester(composeRule)
        compose(comic = detailComic.copy(epsCount = 9), chapters = detailChapters(9), restoration = restoration)
        composeRule.onNodeWithText(detailString(R.string.detail_chapters_forward)).performClick()
        scrollTo(detailString(R.string.detail_chapters_expand, 9))
        composeRule.onNodeWithText(detailString(R.string.detail_chapters_expand, 9)).performClick()

        restoration.emulateSavedInstanceStateRestore()

        scrollTo(detailString(R.string.detail_chapters, 9))
        composeRule.onNodeWithText(detailString(R.string.detail_chapters_reverse)).assertIsDisplayed()
        assertChapterAbove(9, 8)
        scrollTo(detailChapters(1).first().title)
        composeRule.onNodeWithText(detailChapters(1).first().title).assertIsDisplayed()
        scrollTo(detailString(R.string.detail_collapse))
        composeRule.onNodeWithText(detailString(R.string.detail_collapse)).assertIsDisplayed()
        composeRule.onNodeWithText(detailString(R.string.detail_chapters_expand, 9)).assertDoesNotExist()
    }

    @Test
    fun existingBlockedComicShowsTheNoticeAndTheExactSettingsPathWithoutClippingAtLargeFontScale() {
        compose(hiddenByRule = true, fontScale = 1.3f, dark = true)

        composeRule.onNodeWithText(detailString(R.string.detail_hidden_by_rule)).assertIsDisplayed()
        val label = detailString(R.string.detail_hidden_by_rule_where)
        composeRule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        val layout = textLayout(label)
        assertTrue(layout.lineCount > 1)
        assertFalse(layout.hasVisualOverflow)
        repeat(layout.lineCount) { assertFalse(layout.isLineEllipsized(it)) }
        assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    @Test
    fun fourActionButtonsKeepTheirOwnCallbacksAndDownloadCanBeDisabled() {
        compose(comic = detailComic.copy(allowDownload = false))

        composeRule.onNodeWithContentDescription(detailString(R.string.detail_like)).performClick()
        composeRule.onNodeWithContentDescription(detailString(R.string.detail_favourite)).performClick()
        composeRule.onNodeWithContentDescription(detailString(R.string.detail_subscribe)).performClick()
        composeRule.onNodeWithContentDescription(detailString(R.string.detail_download)).assertIsNotEnabled()

        assertEquals(listOf("like", "favourite", "subscribe"), actions)
    }

    @Test
    fun allowedDownloadButtonKeepsItsCallback() {
        compose()

        composeRule.onNodeWithContentDescription(detailString(R.string.detail_download)).performClick()

        assertEquals(listOf("download"), actions)
    }

    @Test
    fun chapterAndBottomReadButtonResumeTheCurrentChapterButStartOtherChaptersAtPageZero() {
        val progress = ReadingProgress(epOrder = 2, epTitle = "第 2 话", pictureIndex = 14)
        compose(library = LibraryState(progress = progress, currentEpPages = 22, downloadedEpIds = setOf("chapter-2")))
        composeRule.onNodeWithText(detailString(R.string.detail_chapter_progress, 14, 22)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(detailString(R.string.detail_chapter_downloaded)).assertIsDisplayed()

        composeRule.onNodeWithText("第 2 话").performClick()
        composeRule.onNodeWithText("第 1 话").performClick()
        composeRule.onNodeWithText(detailString(R.string.detail_continue_reading, "第 2 话")).performClick()

        assertEquals(listOf(2 to 13, 1 to 0, 2 to 13), reads)
    }

    @Test
    fun bottomReadButtonStartsAtTheFirstAvailableChapterAndItsPickerOpensAChosenChapter() {
        compose(chapters = listOf(Ep("chapter-3", "第 3 话", 3), Ep("chapter-5", "第 5 话", 5)))
        composeRule.onNodeWithText(detailString(R.string.detail_start_reading)).performClick()
        assertEquals(listOf(3 to 0), reads)

        composeRule.onNodeWithContentDescription("选择章节").performClick()
        composeRule.onNode(hasText("第 5 话") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()

        assertEquals(listOf(3 to 0, 5 to 0), reads)
    }

    @Test
    fun commentPreviewAndAllCommentsKeepOpeningTheCommentPage() {
        compose(comments = listOf(Comment(id = "preview", content = "预览评论内容")))
        scrollTo(detailString(R.string.detail_comments_all))
        composeRule.onNodeWithText(detailString(R.string.detail_comments_all)).performClick()
        composeRule.onNodeWithText("预览评论内容").performClick()

        assertEquals(2, commentOpens)
    }

    @Test
    fun emptyLoadedCommentsKeepTheEmptyMessageAndItsNavigation() {
        compose()
        scrollTo(detailString(R.string.detail_comments_empty))

        composeRule.onNodeWithText(detailString(R.string.detail_comments_empty)).performClick()

        assertEquals(1, commentOpens)
    }

    private fun compose(
        comic: ComicInfo = detailComic,
        preferences: AppSettings = AppSettings(),
        chapters: List<Ep>? = detailChapters(2),
        library: LibraryState = LibraryState(),
        hidden: List<String> = emptyList(),
        favourites: Set<String> = emptySet(),
        hiddenByRule: Boolean = false,
        comments: List<Comment> = emptyList(),
        fontScale: Float = 1f,
        dark: Boolean = false,
        restoration: StateRestorationTester? = null,
    ) {
        blockedWords = hidden
        favouriteTags = favourites
        tagFavouritesEnabled = preferences.showTagFavourites
        val content: @Composable () -> Unit = {
            fixture.Content(fontScale = fontScale, dark = dark) {
                DetailBody(
                    comic = comic,
                    preferences = preferences,
                    state = DetailState(loading = false, comic = comic, hiddenByRule = hiddenByRule, eps = chapters, comments = comments, commentsLoaded = true),
                    library = library,
                    onBack = { actions += "back" },
                    onRead = { ep, page -> reads += ep to page },
                    onLike = { actions += "like" },
                    onFavourite = { actions += "favourite" },
                    onSubscribe = { actions += "subscribe" },
                    onDownload = { actions += "download" },
                    onCategoryClick = { categories += it },
                    onTagClick = { tags += it },
                    onAuthorClick = { authors += it },
                    onTeamClick = { teams += it },
                    onCreatorClick = { id, name -> creators += id to name },
                    onComicClick = { actions += "comic:$it" },
                    onOpenComments = { commentOpens++ },
                    onRetryEps = { actions += "retry" },
                    hiddenKeywords = blockedWords,
                    onToggleKeyword = { word ->
                        keywordChanges += word
                        blockedWords = if (word in blockedWords) blockedWords - word else blockedWords + word
                    },
                    showTagFavourites = tagFavouritesEnabled,
                    favouriteTags = favouriteTags,
                    onToggleTagFavourite = { tag ->
                        tagFavouriteChanges += tag
                        favouriteTags = if (tag in favouriteTags) favouriteTags - tag else favouriteTags + tag
                    },
                )
            }
        }
        if (restoration == null) composeRule.setContent(content) else restoration.setContent(content)
    }

    private fun title(): SemanticsNodeInteraction = composeRule.onNode(hasText(detailComic.title) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))

    private fun uploader(): SemanticsNodeInteraction =
        composeRule.onNodeWithContentDescription(detailString(R.string.detail_uploader_description, "测试上传"))

    private fun chip(text: String): SemanticsNodeInteraction = composeRule.onNode(hasText(text) and hasClickAction())

    private fun scrollTo(text: String) {
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        composeRule.waitForIdle()
    }

    private fun stateDescription(node: SemanticsNodeInteraction): String? =
        node.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun customActionLabels(node: SemanticsNodeInteraction): List<String> =
        node.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    private fun customAction(node: SemanticsNodeInteraction, label: Int) {
        val action = node.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
            .single { it.label == detailString(label) }
        composeRule.runOnIdle { assertTrue(action.action()) }
        composeRule.waitForIdle()
    }

    private fun textLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private fun assertChapterAbove(first: Int, second: Int) {
        val firstBounds = composeRule.onNodeWithText("第 $first 话").fetchSemanticsNode().boundsInRoot
        val secondBounds = composeRule.onNodeWithText("第 $second 话").fetchSemanticsNode().boundsInRoot
        assertTrue(firstBounds.top < secondBounds.top, "Chapter $first must precede chapter $second")
    }

    private fun assertNoChapterOrder() {
        composeRule.onNodeWithText(detailString(R.string.detail_chapters_forward)).assertDoesNotExist()
        composeRule.onNodeWithText(detailString(R.string.detail_chapters_reverse)).assertDoesNotExist()
    }

    private fun clipboard(): ClipboardManager = RuntimeEnvironment.getApplication().getSystemService(ClipboardManager::class.java)
    private fun seedClipboard() = clipboard().setPrimaryClip(ClipData.newPlainText("sentinel", CLIPBOARD_SENTINEL))
    private fun clipboardText(): String? = clipboard().primaryClip?.getItemAt(0)?.text?.toString()

    private companion object {
        const val CLIPBOARD_SENTINEL = "untouched clipboard"
        val LONG_DESCRIPTION = "这段漫画简介需要在实际宽度下换行，文字介绍故事背景与人物经历。".repeat(8)
    }
}
