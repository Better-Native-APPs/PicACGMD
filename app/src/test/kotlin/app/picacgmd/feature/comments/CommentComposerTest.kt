package app.picacgmd.feature.comments

import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

/** [DraftInFlight] fed with the changes a text field reports: the text and the selection, before and after. */
internal class CommentComposerTest {
    @Test
    fun anUntouchedDraftIsCleared() {
        assertEquals("", Field("hello world").sent().remaining())
    }

    @Test
    fun textTypedBehindTheSentTextIsKeptWithoutIt() {
        val field = Field("hello world").sent()
        field.to("hello world a", 13)
        field.to("hello world again", 17)
        assertEquals(" again", field.remaining())
    }

    @Test
    fun textTypedAndDeletedAgainBehindTheSentTextStillClearsTheDraft() {
        val field = Field("hello world").sent()
        field.to("hello world!", 12)
        field.to("hello world", 11)
        assertEquals("", field.remaining())
    }

    @Test
    fun deletingForwardsRightBehindTheSentTextKeepsWhatFollows() {
        val field = Field("hello").sent()
        field.to("hello again", 11)
        field.to("hello again", 5)
        field.to("helloagain", 5)
        assertEquals("again", field.remaining())
    }

    @Test
    fun theCursorMovedIntoTheSentTextWithoutAnEditStillClearsIt() {
        val field = Field("hello").sent()
        field.to("hello", 2)
        field.to("hello", 5)
        assertEquals("", field.remaining())
    }

    @Test
    fun aDraftEditedInsideTheSentTextIsKeptWhole() {
        val field = Field("hello world").sent()
        field.to("hello world", 6)
        field.to("hello new world", 10)
        assertEquals("hello new world", field.remaining())
    }

    @Test
    fun aShortenedDraftIsKeptWhole() {
        val field = Field("hello world").sent()
        field.to("hello", 5)
        assertEquals("hello", field.remaining())
    }

    @Test
    fun aDraftClearedAndWrittenAnewIsKeptWholeAlsoWhenItBeginsWithTheSentText() {
        val field = Field("hello").sent()
        field.to("", 0)
        field.to("hello, one more thing", 21)
        assertEquals("hello, one more thing", field.remaining())
    }

    @Test
    fun deletingBackwardsIntoTheSentTextKeepsTheDraftWholeAlthoughItStillBeginsTheSame() {
        // 哈 was sent, 哈，下一条 typed behind it, then the first 哈 deleted with the cursor right behind it.
        val field = Field("哈").sent()
        field.to("哈哈，下一条", 6)
        field.to("哈哈，下一条", 1)
        field.to("哈，下一条", 0)
        assertEquals("哈，下一条", field.remaining())
    }

    @Test
    fun theSentTextSelectedAndReplacedByTheSameWordsIsKeptWhole() {
        val field = Field("hello").sent()
        field.to("hello", 0, 5)
        field.to("hello", 5)
        field.to("hello, one more thing", 21)
        assertEquals("hello, one more thing", field.remaining())
    }

    @Test
    fun aSelectionThatWasThereWhenTheSendBeganAndIsReplacedByTheSameWordsIsKeptWhole() {
        val field = Field("hello", 0, 5).sent()
        field.to("hello", 5)
        field.to("hello, one more thing", 21)
        assertEquals("hello, one more thing", field.remaining())
    }

    @Test
    fun aSelectionBehindTheSentTextThatIsReplacedKeepsOnlyWhatFollows() {
        val field = Field("hello").sent()
        field.to("hello again", 11)
        field.to("hello again", 5, 11)
        field.to("hello anew", 10)
        assertEquals(" anew", field.remaining())
    }

    @Test
    fun aSelectionOverTheSentTextReplacedByTextThatBeginsTheSameIsKeptWhole() {
        val field = Field("hello").sent()
        field.to("hello", 0, 5)
        field.to("hello, one more thing", 21)
        assertEquals("hello, one more thing", field.remaining())
    }

    @Test
    fun aSelectionThatStartsBehindTheSentTextLeavesItUntouched() {
        val field = Field("hello").sent()
        field.to("hello again", 11)
        field.to("hello again", 5, 11)
        field.to("hello!", 6)
        assertEquals("!", field.remaining())
    }

    @Test
    fun anEditInsideTheSentTextThatIsUndoneStillKeepsTheDraftWhole() {
        val field = Field("hello").sent()
        field.to("hello", 2)
        field.to("hexllo", 3)
        field.to("hello", 2)
        field.to("hello", 5)
        field.to("hello again", 11)
        assertEquals("hello again", field.remaining())
    }

    /** A text field with the cursor at the end of [text], reporting each change to the send in flight. */
    private class Field(private var text: String, start: Int = text.length, end: Int = start) {
        private var selection = TextRange(start, end)
        private lateinit var flight: DraftInFlight

        fun sent(): Field = apply { flight = DraftInFlight(text) }

        fun to(next: String, start: Int, end: Int = start) {
            val range = TextRange(start, end)
            flight.changed(next, next != text, selection, range)
            text = next
            selection = range
        }

        fun remaining(): String = flight.remaining(text)
    }
}
