package app.picacgmd.feature.reader.logic

import java.util.Locale

/** Image formats a saved or shared page can have. */
internal enum class PageFileType(val mimeType: String, val extension: String) {
    Jpeg("image/jpeg", "jpg"),
    Png("image/png", "png"),
    Webp("image/webp", "webp"),
    Gif("image/gif", "gif"),
}

/** Bytes needed by [sniffPageFileType]. */
internal const val PAGE_FILE_HEADER_SIZE: Int = 12

/** The format named by a file's first bytes. Comic pages are mostly JPEG, so that is the fallback. */
internal fun sniffPageFileType(header: ByteArray): PageFileType {
    fun matches(offset: Int, vararg expected: Int): Boolean =
        header.size >= offset + expected.size &&
            expected.indices.all { header[offset + it].toInt() and 0xFF == expected[it] }
    return when {
        matches(0, 0x89, 0x50, 0x4E, 0x47) -> PageFileType.Png
        matches(0, 0x47, 0x49, 0x46, 0x38) -> PageFileType.Gif
        matches(0, 0x52, 0x49, 0x46, 0x46) && matches(8, 0x57, 0x45, 0x42, 0x50) -> PageFileType.Webp
        else -> PageFileType.Jpeg
    }
}

private const val MAX_FILE_BASE_LENGTH = 100
private val UNSAFE_FILE_CHARACTERS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
private val REPEATED_SPACE = Regex("""\s+""")

/** File name for a saved page, such as `星屑海 - 第 12 话 - 014.jpg`. */
internal fun pageFileName(
    comicTitle: String,
    chapterTitle: String,
    pageNumber: Int,
    extension: String,
    suffix: String = "",
): String {
    val base = listOf(comicTitle, chapterTitle)
        .map { title -> title.replace(UNSAFE_FILE_CHARACTERS, " ").replace(REPEATED_SPACE, " ").trim(' ', '.') }
        .filter { it.isNotEmpty() }
        .joinToString(" - ")
        .take(MAX_FILE_BASE_LENGTH)
        .trimEnd(' ', '-', '.')
    val number = String.format(Locale.ROOT, "%03d", pageNumber) + suffix
    return (if (base.isEmpty()) number else "$base - $number") + "." + extension
}

/** 「382 KB」, 「1.2 MB」. */
internal fun formatFileSize(bytes: Long): String {
    val size = bytes.coerceAtLeast(0L)
    return when {
        size < 1024L -> "$size B"
        size < 1024L * 1024L -> "${size / 1024L} KB"
        else -> String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024.0))
    }
}
