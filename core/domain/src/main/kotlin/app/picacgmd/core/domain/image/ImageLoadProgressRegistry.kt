package app.picacgmd.core.domain.image

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Only [observe] registers a reader-requested URL; image-client traffic cannot create entries. */
class ImageLoadProgressRegistry {
    private class Entry {
        val id = Any()
        val progress = MutableStateFlow<Float?>(null)
        val flow = progress.asStateFlow()
        var transfer: Any? = null
    }

    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()

    fun observe(url: String): StateFlow<Float?>? {
        val key = normalize(url) ?: return null
        return synchronized(lock) { entries.getOrPut(key, ::Entry).flow }
    }

    internal val trackedUrlCount: Int get() = synchronized(lock) { entries.size }

    internal fun awaiting(url: String): Awaiting? {
        val key = normalize(url) ?: return null
        return synchronized(lock) {
            val entry = entries[key] ?: return null
            if (entry.transfer != null) return null
            Awaiting(this, key, entry.id)
        }
    }

    internal fun begin(url: String, contentLength: Long): Transfer? {
        val key = normalize(url) ?: return null
        return synchronized(lock) {
            val entry = entries[key] ?: return null
            val transfer = Any()
            entry.transfer = transfer
            entry.progress.value = if (contentLength >= 0) 0f else null
            Transfer(this, key, entry.id, transfer, contentLength)
        }
    }

    private fun report(key: String, entryId: Any, transfer: Any, bytesRead: Long, contentLength: Long) {
        synchronized(lock) {
            val entry = entries[key] ?: return
            if (entry.id !== entryId || entry.transfer !== transfer) return
            entry.progress.value = if (contentLength > 0) {
                (bytesRead.toDouble() / contentLength).coerceIn(0.0, 1.0).toFloat()
            } else null
        }
    }

    private fun finish(key: String, entryId: Any, transfer: Any?, completed: Boolean) {
        synchronized(lock) {
            val entry = entries[key] ?: return
            if (entry.id !== entryId || entry.transfer !== transfer) return
            entry.progress.value = if (completed) 1f else null
            entries.remove(key)
        }
    }

    internal fun clear() {
        synchronized(lock) {
            entries.values.forEach { it.progress.value = null }
            entries.clear()
        }
    }

    internal class Awaiting(
        private val registry: ImageLoadProgressRegistry,
        private val key: String,
        private val entryId: Any,
    ) {
        fun finish(completed: Boolean) = registry.finish(key, entryId, null, completed)
    }

    internal class Transfer(
        private val registry: ImageLoadProgressRegistry,
        private val key: String,
        private val entryId: Any,
        private val token: Any,
        private val contentLength: Long,
    ) {
        fun report(bytesRead: Long) = registry.report(key, entryId, token, bytesRead, contentLength)
        fun finish(completed: Boolean) = registry.finish(key, entryId, token, completed)
    }

    private fun normalize(url: String): String? = url.toHttpUrlOrNull()?.toString()
}
