package app.picacgmd.feature.reader.logic

import app.picacgmd.feature.reader.ReaderFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilterMatrixTest {
    private fun FloatArray.applyTo(r: Float, g: Float, b: Float): List<Float> = List(3) { row ->
        this[row * 5] * r + this[row * 5 + 1] * g + this[row * 5 + 2] * b + this[row * 5 + 4]
    }

    @Test
    fun normalHasNoMatrix() {
        assertNull(ReaderFilter.Normal.colorMatrix())
    }

    @Test
    fun grayscaleRemovesColourAndKeepsWhite() {
        val matrix = assertNotNull(ReaderFilter.Grayscale.colorMatrix())
        val (r, g, b) = matrix.applyTo(0.9f, 0.2f, 0.4f)
        assertEquals(r, g)
        assertEquals(g, b)
        matrix.applyTo(1f, 1f, 1f).forEach { assertEquals(1f, it, 0.001f) }
    }

    @Test
    fun sepiaWarmsAGrayPageAndKeepsBlack() {
        val matrix = assertNotNull(ReaderFilter.Sepia.colorMatrix())
        val (r, g, b) = matrix.applyTo(0.5f, 0.5f, 0.5f)
        assertTrue(r > g && g > b, "expected a warm tint, got $r $g $b")
        matrix.applyTo(0f, 0f, 0f).forEach { assertEquals(0f, it, 0.001f) }
    }

    @Test
    fun filtersLeaveAlphaAlone() {
        for (filter in listOf(ReaderFilter.Grayscale, ReaderFilter.Sepia)) {
            val matrix = assertNotNull(filter.colorMatrix())
            assertEquals(20, matrix.size)
            assertEquals(listOf(0f, 0f, 0f, 1f, 0f), matrix.takeLast(5))
        }
    }
}
