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
    fun isFavourite(tag: String): Flow<Boolean> = favourites.map { values -> values.any { it.tag == tag } }

    suspend fun add(tag: String) = mutex.withLock {
        require(tag.isNotBlank()) { "标签不能为空" }
        val values = dao.observeAll().first()
        val name = tag.trim()
        if (values.none { it.tag == name }) {
            dao.upsert(TagFavourite(name, sortOrder = (values.maxOfOrNull(TagFavourite::sortOrder) ?: -1) + 1, createdAt = now()))
        }
    }

    suspend fun remove(tags: List<String>): Int = dao.delete(tags)

    suspend fun reorder(tags: List<String>) = mutex.withLock {
        val values = dao.observeAll().first().associateBy(TagFavourite::tag)
        require(tags.distinct().size == tags.size && tags.toSet() == values.keys) { "排序必须包含全部标签，且不能重复" }
        tags.forEachIndexed { order, tag -> dao.upsert(values.getValue(tag).copy(sortOrder = order)) }
    }

    suspend fun clear() = dao.clear()
}
