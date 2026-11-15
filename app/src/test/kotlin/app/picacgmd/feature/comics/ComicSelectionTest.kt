package app.picacgmd.feature.comics

import app.picacgmd.core.network.ComicSimple
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

internal class ComicSelectionTest {
    @Test
    fun keepsSelectionOrder() {
        val selection = listOf("c", "a", "b", "a")
        val comics = listOf(ComicSimple(id = "a"), ComicSimple(id = "b"), ComicSimple(id = "c"))

        assertEquals(selection, listedSelection(selection, comics))
    }

    @Test
    fun dropsIdsNotInTheList() {
        val selection = listOf("b", "missing", "a", "gone")
        val comics = listOf(ComicSimple(id = "a"), ComicSimple(id = "b"))

        assertEquals(listOf("b", "a"), listedSelection(selection, comics))
    }

    @Test
    fun unchangedSelectionKeepsTheSameInstance() {
        val selection = listOf("b", "a")
        val comics = listOf(ComicSimple(id = "a"), ComicSimple(id = "b"), ComicSimple(id = "extra"))

        assertSame(selection, listedSelection(selection, comics))
    }

    @Test
    fun emptySelectionKeepsTheSameInstance() {
        val selection = mutableListOf<String>()

        assertSame(selection, listedSelection(selection, listOf(ComicSimple(id = "a"))))
        assertSame(selection, listedSelection(selection, emptyList()))
    }

    @Test
    fun emptyComicListDropsAllSelectedIds() {
        assertEquals(emptyList(), listedSelection(listOf("a", "b"), emptyList()))
    }
}
