package app.picacgmd.core.data.settings

private val CopyNameToken = Regex("\\{(author|title|id)\\}")

/** Single-pass replacement: braces in metadata remain literal, unknown tokens are preserved. */
fun renderCopyName(template: String, author: String, title: String, id: String): String =
    CopyNameToken.replace(template) { match ->
        when (match.groupValues[1]) {
            "author" -> author
            "title" -> title
            else -> id
        }
    }
