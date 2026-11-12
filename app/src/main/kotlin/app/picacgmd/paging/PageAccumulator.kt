package app.picacgmd.paging

internal enum class PageWrite { REPLACE, APPEND }
internal enum class Dedupe { NONE, AGAINST_OLD, ALL }

internal data class PageData<T, M>(
    val items: List<T>,
    val page: Int,
    val pages: Int,
    val total: Int,
    val meta: M,
)

internal data class PageCommit<T, M>(
    val data: PageData<T, M>,
    val resetProjection: Boolean,
    val projectionDelta: List<T>,
)

/**
 * Merges page content without owning loading, error, or end-of-list decisions.
 *
 * Not thread-safe: callers must confine access and mutation to the main thread.
 * Null keys identify structural rows, which are never deduplicated. Published item
 * lists are never mutated by this accumulator; every commit allocates a new list.
 * Key indexing is incremental on append, except after an update or when ALL must
 * first clean duplicate keys out of old content. List concatenation still copies.
 */
internal class PageAccumulator<T, K : Any, M>(
    initial: PageData<T, M>,
    private val keyOf: (T) -> K?,
    private val replaceDedupe: Dedupe,
    private val appendDedupe: Dedupe,
    private val appendMeta: (M, M) -> M,
) {
    private var data = initial
    private var keyIndex: HashSet<K>? = null
    private var knownDistinct = false

    val current: PageData<T, M> get() = data

    fun commit(page: PageData<T, M>, write: PageWrite): PageCommit<T, M> =
        when (write) {
            PageWrite.REPLACE -> replace(page)
            PageWrite.APPEND -> append(page)
        }

    fun update(transform: (PageData<T, M>) -> PageData<T, M>): PageData<T, M> {
        data = transform(current)
        keyIndex = null
        knownDistinct = false
        return current
    }

    private fun replace(page: PageData<T, M>): PageCommit<T, M> {
        val keys = if (replaceDedupe == Dedupe.ALL || appendDedupe != Dedupe.NONE) HashSet<K>() else null
        var distinct = true
        val items = ArrayList<T>(page.items.size)
        for (item in page.items) {
            val first = keys?.let { index ->
                val key = keyOf(item)
                key == null || index.add(key)
            } ?: true
            if (!first) distinct = false
            if (replaceDedupe != Dedupe.ALL || first) items.add(item)
        }
        keyIndex = if (appendDedupe == Dedupe.NONE) null else keys
        knownDistinct = replaceDedupe == Dedupe.ALL || (keys != null && distinct)
        val next = page.copy(items = items)
        data = next
        return PageCommit(next, resetProjection = true, projectionDelta = items)
    }

    private fun append(page: PageData<T, M>): PageCommit<T, M> {
        val old = current
        val meta = appendMeta(old.meta, page.meta)
        var base = old.items
        var resetProjection = false
        val added = when (appendDedupe) {
            Dedupe.NONE -> ArrayList(page.items)
            Dedupe.AGAINST_OLD -> {
                val keys = keysForCurrent()
                val incomingKeys = HashSet<K>()
                val rows = ArrayList<T>(page.items.size)
                for (item in page.items) {
                    val key = keyOf(item)
                    if (key == null || key !in keys) {
                        rows.add(item)
                        if (key != null) incomingKeys.add(key)
                    }
                }
                // Delay indexing new keys so duplicates within this page survive.
                keys.addAll(incomingKeys)
                rows
            }
            Dedupe.ALL -> {
                val keys = keyIndex?.takeIf { knownDistinct } ?: HashSet<K>().also { index ->
                    base = distinctRows(old.items, index)
                    resetProjection = base.size != old.items.size
                    keyIndex = index
                }
                distinctRows(page.items, keys)
            }
        }
        knownDistinct = appendDedupe == Dedupe.ALL
        val items = ArrayList<T>(base.size + added.size)
        items.addAll(base)
        items.addAll(added)
        val next = page.copy(items = items, meta = meta)
        data = next
        return PageCommit(next, resetProjection, if (resetProjection) items else added)
    }

    private fun keysForCurrent(): HashSet<K> = keyIndex ?: HashSet<K>().also { keys ->
        for (item in current.items) keyOf(item)?.let { keys.add(it) }
        keyIndex = keys
    }

    private fun distinctRows(rows: List<T>, keys: MutableSet<K>): List<T> {
        val items = ArrayList<T>(rows.size)
        for (item in rows) {
            val key = keyOf(item)
            if (key == null || keys.add(key)) items.add(item)
        }
        return items
    }
}
