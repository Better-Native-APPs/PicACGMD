package app.picacgmd.feature.comments

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import app.picacgmd.R
import app.picacgmd.testing.ComposeHostRule
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals

/** The real composer, edited through its text field while a send is on its way. */
@RunWith(RobolectricTestRunner::class)
class CommentComposerEditTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private val sent = mutableListOf<String>()
    private val completions = mutableListOf<() -> Unit>()

    @Test
    fun anUntouchedDraftIsClearedWhenTheSendHasGoneOut() {
        composeAndSend("你好")
        complete(0)
        assertEquals("", draft())
    }

    @Test
    fun textTypedBehindTheSentTextStaysWithoutIt() {
        composeAndSend("你好")
        field().performTextInput("再来一条")
        assertEquals("你好再来一条", draft())
        complete(0)
        assertEquals("再来一条", draft())
    }

    @Test
    fun aDraftClearedAndWrittenAnewStaysWholeAlthoughItBeginsWithTheSentText() {
        composeAndSend("你好")
        field().performTextClearance()
        field().performTextInput("你好，补充一句")
        complete(0)
        assertEquals("你好，补充一句", draft())
    }

    @Test
    fun aSelectionOverTheSentTextReplacedByTextThatBeginsTheSameStaysWhole() {
        composeAndSend("你好")
        field().performTextInputSelection(TextRange(0, 2))
        field().performTextInput("你好，补充一句")
        complete(0)
        assertEquals("你好，补充一句", draft())
    }

    @Test
    fun textInsertedInsideTheSentTextStaysWhole() {
        composeAndSend("你好")
        field().performTextInputSelection(TextRange(1))
        field().performTextInput("们")
        complete(0)
        assertEquals("你们好", draft())
    }

    @Test
    fun movingTheCursorIntoTheSentTextWithoutEditingStillClearsIt() {
        composeAndSend("你好")
        field().performTextInputSelection(TextRange(1))
        complete(0)
        assertEquals("", draft())
    }

    @Test
    fun deletingBackwardsIntoTheSentTextKeepsTheDraftWholeAlthoughItStillBeginsTheSame() {
        composeAndSend("哈")
        field().performTextInput("哈，下一条")
        field().performTextInputSelection(TextRange(1))
        field().performKeyInput { pressKey(Key.Backspace) }
        assertEquals("哈，下一条", draft())
        complete(0)
        assertEquals("哈，下一条", draft())
    }

    @Test
    fun theSentTextSelectedAndReplacedByTheSameWordsStaysWhole() {
        composeAndSend("你好")
        field().performTextInputSelection(TextRange(0, 2))
        field().performTextInput("你好")
        field().performTextInput("，补充一句")
        assertEquals("你好，补充一句", draft())
        complete(0)
        assertEquals("你好，补充一句", draft())
    }

    @Test
    fun aSelectionMadeBeforeSendingAndReplacedByTheSameWordsStaysWhole() {
        compose()
        field().performTextInput("你好")
        field().performTextInputSelection(TextRange(0, 2))
        send()
        assertEquals(listOf("你好"), sent)
        field().performTextInput("你好")
        field().performTextInput("，补充一句")
        assertEquals("你好，补充一句", draft())
        complete(0)
        assertEquals("你好，补充一句", draft())
    }

    @Test
    fun pastedTextComesInWithoutItsStyles() {
        val styled = AnnotatedString("粗体", listOf(AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), 0, 2)))
        composeRule.setContent {
            MaterialTheme {
                Column {
                    BasicTextField(TextFieldValue(styled, TextRange(0, 2)), onValueChange = {}, modifier = Modifier.testTag("source"))
                    CommentComposer(hint = "hint", sending = false) { _, _ -> }
                }
            }
        }
        // The library's own copy puts the styles on the clipboard the way a paste reads them.
        composeRule.onNodeWithTag("source").performSemanticsAction(SemanticsActions.CopyText)
        composeRule.waitForIdle()
        val composer = composeRule.onNode(hasSetTextAction() and !hasTestTag("source"))
        composer.performSemanticsAction(SemanticsActions.PasteText)
        composeRule.waitForIdle()

        val pasted = composer.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)
        assertEquals("粗体", pasted?.text)
        assertEquals(emptyList(), pasted?.spanStyles)
    }

    @Test
    fun aSendFollowedByAnotherLeavesTheDraftAloneWhenItCompletesLate() {
        composeAndSend("你好")
        field().performTextInput("啊")
        send()
        assertEquals(listOf("你好", "你好啊"), sent)
        // Written after the second send, so the first one never saw it.
        field().performTextClearance()
        field().performTextInput("你好，新的")

        complete(0)
        assertEquals("你好，新的", draft())
        complete(1)
        assertEquals("你好，新的", draft())
    }

    @Test
    fun aRestoredDraftKeepsItsTextAndStartsWithTheCursorOfANewField() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            MaterialTheme { CommentComposer(hint = "hint", sending = false) { _, _ -> } }
        }
        field().performTextInput("你好啊")
        field().performTextInputSelection(TextRange(2))

        restoration.emulateSavedInstanceStateRestore()

        assertEquals("你好啊", draft())
        assertEquals(TextRange.Zero, field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange))
    }

    @Test
    fun aSendThatNeverCompletesLeavesTheDraftAndTheNextSendStartsAfresh() {
        composeAndSend("你好")
        field().performTextClearance()
        field().performTextInput("你好啊")
        assertEquals("你好啊", draft())

        send()
        assertEquals(listOf("你好", "你好啊"), sent)
        field().performTextInput("第二条")
        complete(1)
        assertEquals("第二条", draft())
    }

    private fun compose() {
        composeRule.setContent {
            MaterialTheme {
                CommentComposer(hint = "hint", sending = false) { content, done ->
                    sent += content
                    completions += done
                }
            }
        }
    }

    private fun composeAndSend(text: String) {
        compose()
        field().performTextInput(text)
        send()
        assertEquals(listOf(text), sent)
    }

    private fun send() {
        val label = RuntimeEnvironment.getApplication().getString(R.string.comments_send)
        composeRule.onNode(hasContentDescription(label)).performClick()
        composeRule.waitForIdle()
    }

    private fun complete(index: Int) {
        composeRule.runOnIdle { completions[index]() }
        composeRule.waitForIdle()
    }

    private fun field(): SemanticsNodeInteraction = composeRule.onNode(hasSetTextAction())

    private fun draft(): String =
        field().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
}
