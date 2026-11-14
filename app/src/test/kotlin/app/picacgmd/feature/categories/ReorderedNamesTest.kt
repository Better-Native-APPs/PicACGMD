package app.picacgmd.feature.categories

import kotlin.test.Test
import kotlin.test.assertEquals

class ReorderedNamesTest {
    @Test fun keepsUnavailableNamesInTheirSavedPositions() {
        assertEquals(
            listOf("X", "C", "A", "Y", "B"),
            reorderedNames(listOf("X", "A", "B", "Y", "C"), listOf("C", "A", "B")),
        )
    }

    @Test fun appendsShownNamesWithoutSavedPositions() {
        assertEquals(
            listOf("C", "A", "X", "B"),
            reorderedNames(listOf("A", "B", "X"), listOf("C", "A", "B")),
        )
    }

    @Test fun usesShownOrderWhenNothingIsSaved() {
        assertEquals(listOf("B", "A"), reorderedNames(emptyList(), listOf("B", "A")))
    }

    @Test fun replacesSavedAliasWithShownSpelling() {
        assertEquals(
            listOf("X", "大濕推薦", "Y"),
            reorderedNames(listOf("X", "大湿推荐", "Y"), listOf("大濕推薦")),
        )
    }

    @Test fun removesRepeatedSavedCategoryKeys() {
        assertEquals(
            listOf("B", "A", "X"),
            reorderedNames(listOf("A", "B", "A", "X"), listOf("B", "A")),
        )
        assertEquals(
            listOf("B", "大濕推薦", "X", "A"),
            reorderedNames(listOf("A", "大湿推荐", "X", "大濕推薦", "B"), listOf("B", "大濕推薦", "A")),
        )
    }

    @Test fun keepsUnchangedOrder() {
        val saved = listOf("X", "大濕推薦", "A", "B")
        assertEquals(saved, reorderedNames(saved, saved))
    }
}
