package app.picacgmd.feature.reader.logic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageFileMathTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun formatsAreRecognisedFromTheirHeaders() {
        assertEquals(PageFileType.Jpeg, sniffPageFileType(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(PageFileType.Png, sniffPageFileType(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertEquals(PageFileType.Gif, sniffPageFileType(bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)))
        assertEquals(
            PageFileType.Webp,
            sniffPageFileType(bytes(0x52, 0x49, 0x46, 0x46, 1, 2, 3, 4, 0x57, 0x45, 0x42, 0x50)),
        )
    }

    @Test
    fun unknownOrShortHeadersFallBackToJpeg() {
        assertEquals(PageFileType.Jpeg, sniffPageFileType(ByteArray(0)))
        assertEquals(PageFileType.Jpeg, sniffPageFileType(bytes(0x89, 0x50)))
        // A RIFF file that is not WebP (for example a WAV).
        assertEquals(
            PageFileType.Jpeg,
            sniffPageFileType(bytes(0x52, 0x49, 0x46, 0x46, 1, 2, 3, 4, 0x57, 0x41, 0x56, 0x45)),
        )
    }

    @Test
    fun fileNamesCarryTheComicChapterAndPage() {
        assertEquals("星屑海 - 第 12 话 - 014.jpg", pageFileName("星屑海", "第 12 话", 14, "jpg"))
    }

    @Test
    fun unsafeCharactersAreRemoved() {
        val name = pageFileName("a/b\\c:d*e?f\"g<h>i|j", "  第\n12\t话..  ", 1, "png")
        assertFalse(name.any { it in "/\\:*?\"<>|\n\t" }, name)
        assertEquals("a b c d e f g h i j - 第 12 话 - 001.png", name)
    }

    @Test
    fun anEmptyTitleDoesNotLeaveASeparator() {
        assertEquals("014.jpg", pageFileName("", "...", 14, "jpg"))
        assertEquals("星屑海 - 014.jpg", pageFileName("星屑海", " ", 14, "jpg"))
    }

    @Test
    fun longTitlesAreCutButKeepThePageAndExtension() {
        val name = pageFileName("长".repeat(400), "第 1 话", 7, "webp", suffix = "-crop")
        assertTrue(name.length <= 120, "too long: ${name.length}")
        assertTrue(name.endsWith(" - 007-crop.webp"), name)
    }

    @Test
    fun fileSizesAreReadable() {
        assertEquals("0 B", formatFileSize(-5))
        assertEquals("1023 B", formatFileSize(1023))
        assertEquals("382 KB", formatFileSize(382L * 1024L + 12))
        assertEquals("1.5 MB", formatFileSize(1024L * 1024L * 3 / 2))
    }
}
