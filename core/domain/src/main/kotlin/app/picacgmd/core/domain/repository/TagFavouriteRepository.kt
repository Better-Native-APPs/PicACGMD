package app.picacgmd.core.domain.repository

import app.picacgmd.core.data.db.TagFavourite
import app.picacgmd.core.data.db.TagFavouriteDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TagFavouriteRepository(private val dao: TagFavouriteDao, private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    val favourites: Flow<List<TagFavourite>> = dao.observeAll()
    fun isFavourite(tag: String): Flow<Boolean> = favourites.map { values -> values.any { it.tag == tag.trim() } }

    suspend fun add(tag: String) = mutex.withLock {
        val name = tagName(tag)
        val values = dao.observeAll().first()
        if (values.none { it.tag == name }) {
            dao.upsert(TagFavourite(name, sortOrder = (values.maxOfOrNull(TagFavourite::sortOrder) ?: -1) + 1, createdAt = now()))
        }
    }

    /** Read and write under one lock, so successive taps toggle the stored value rather than a stale UI value. */
    suspend fun toggle(tag: String): Boolean = mutex.withLock {
        val name = tagName(tag)
        val values = dao.observeAll().first()
        if (values.any { it.tag == name }) {
            dao.delete(listOf(name))
            false
        } else {
            dao.upsert(TagFavourite(name, sortOrder = (values.maxOfOrNull(TagFavourite::sortOrder) ?: -1) + 1, createdAt = now()))
            true
        }
    }

    suspend fun remove(tags: List<String>): Int = mutex.withLock { dao.delete(tags.map(String::trim).distinct()) }

    suspend fun reorder(tags: List<String>) = mutex.withLock {
        val values = dao.observeAll().first().associateBy(TagFavourite::tag)
        require(tags.distinct().size == tags.size && tags.toSet() == values.keys) { "排序必须包含全部标签，且不能重复" }
        tags.forEachIndexed { order, tag -> dao.upsert(values.getValue(tag).copy(sortOrder = order)) }
    }

    suspend fun clear() = mutex.withLock { dao.clear() }

    private fun tagName(tag: String): String = tag.trim().also {
        require(it.isNotEmpty()) { "标签不能为空" }
    }
}
