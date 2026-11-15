package app.picacgmd.feature.categories

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.picacgmd.testing.ComposeHostRule
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/** The real reorderable column, dragged by its handles while the list it shows is replaced. */
@RunWith(RobolectricTestRunner::class)
class ReorderableColumnTest {
    private val composeRule = createComposeRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(ComposeHostRule()).around(composeRule)

    private var items by mutableStateOf(listOf("a", "b", "c"))
    private val saved = mutableListOf<List<String>>()

    @Test
    fun aDragMovesTheRowAndSavesTheOrderOnceWhenTheFingerLifts() {
        compose()
        dragDownOneRow("a")
        assertEquals(emptyList(), saved)
        lift("a")
        assertEquals(listOf(listOf("b", "a", "c")), saved)
    }

    @Test
    fun aDragAfterTheItemsWereReplacedWorksOnTheNewItems() {
        compose()
        // The handle of "a" exists before the replacement, so its drag handler was started with the old list.
        dragDownOneRow("a")
        lift("a")
        saved.clear()

        items = listOf("a", "c")
        composeRule.waitForIdle()
        dragDownOneRow("a")
        lift("a")

        assertEquals(listOf(listOf("c", "a")), saved)
    }

    @Test
    fun itemsReplacedDuringADragEndItWithoutSavingTheAbandonedOrder() {
        compose()
        dragDownOneRow("a")

        items = listOf("a", "c")
        composeRule.waitForIdle()
        // The row is back in its place: nothing of the abandoned drag still moves it.
        assertEquals(0f, topInRoot("a"))
        assertEquals(topInRoot("a") + rowHeightPx(), topInRoot("c"))
        lift("a")
        assertEquals(emptyList(), saved)
        assertEquals(0f, topInRoot("a"))

        dragDownOneRow("a")
        lift("a")
        assertEquals(listOf(listOf("c", "a")), saved)
    }

    private fun compose() {
        composeRule.setContent {
            ReorderableColumn(
                items = items,
                keyOf = { it },
                nameOf = { it },
                itemHeight = RowHeight,
                onMoved = { saved += it },
            ) { item, handle ->
                Box(handle.testTag(item).fillMaxWidth().height(RowHeight))
            }
        }
    }

    /** Far enough to pass the touch slop and half a row, and not far enough for a second swap. */
    private fun dragDownOneRow(tag: String) {
        composeRule.onNodeWithTag(tag).performTouchInput {
            val step = RowHeight.toPx() * 1.25f / DragSteps
            down(center)
            repeat(DragSteps) { moveBy(Offset(0f, step)) }
        }
        composeRule.waitForIdle()
    }

    /** Where the row is drawn, the offset of a row lifted by a drag included. */
    private fun topInRoot(tag: String): Float = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun rowHeightPx(): Float = composeRule.onNodeWithTag("a").fetchSemanticsNode().size.height.toFloat()

    private fun lift(tag: String) {
        composeRule.onNodeWithTag(tag).performTouchInput { up() }
        composeRule.waitForIdle()
    }

    private companion object {
        val RowHeight = 80.dp
        const val DragSteps = 10
    }
}
