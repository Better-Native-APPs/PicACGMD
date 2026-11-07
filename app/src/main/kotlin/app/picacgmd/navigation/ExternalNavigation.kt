package app.picacgmd.navigation

import android.content.Intent
import android.net.Uri
import app.picacgmd.core.domain.download.DownloadManager

/** Only text and comic links are accepted. No file/stream import entry exists. */
data class ExternalNavigation(val comicId: String? = null, val downloads: Boolean = false)

fun Intent.externalNavigation(): ExternalNavigation? {
    if (action == DownloadManager.ACTION_OPEN_DOWNLOADS) return ExternalNavigation(downloads = true)
    if (action == Intent.ACTION_VIEW) return data?.comicId()?.let { ExternalNavigation(comicId = it) }
    if (action != Intent.ACTION_SEND || type != "text/plain") return null
    val text = getStringExtra(Intent.EXTRA_TEXT).orEmpty()
    val id = Regex("(?i)(?:pika://comic/|https?://pika/comic/|https?://(?:www\\.)?picacomic\\.com/comics?/)([a-f0-9]{24})(?:[/\\s?#]|$)")
        .find(text)?.groupValues?.get(1)
        ?: text.trim().takeIf { it.matches(Regex("[a-fA-F0-9]{24}")) }
    return id?.let { ExternalNavigation(comicId = it) }
}

private fun Uri.comicId(): String? {
    val segments = pathSegments
    val id = when {
        scheme == "pika" && host == "comic" -> segments.singleOrNull()
        scheme in setOf("https", "http") && host == "pika" && segments.size == 2 && segments[0] == "comic" -> segments[1]
        scheme in setOf("https", "http") && host in setOf("picacomic.com", "www.picacomic.com") &&
            segments.size == 2 && segments[0] in setOf("comic", "comics") -> segments[1]
        else -> null
    }
    return id?.takeIf { it.matches(Regex("[a-fA-F0-9]{24}")) }
}
