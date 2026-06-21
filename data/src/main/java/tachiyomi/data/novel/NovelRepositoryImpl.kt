package tachiyomi.data.novel

import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelUpdate
import tachiyomi.domain.novel.repository.NovelRepository

class NovelRepositoryImpl(
    private val handler: DatabaseHandler,
) : NovelRepository {

    override suspend fun getNovelById(id: Long): Novel {
        return handler.awaitOne { novelsQueries.getNovelById(id, NovelMapper::mapNovel) }
    }

    override fun getNovelByIdAsFlow(id: Long): Flow<Novel> {
        return handler.subscribeToOne { novelsQueries.getNovelById(id, NovelMapper::mapNovel) }
    }

    override suspend fun getNovelByUrlAndSourceId(url: String, sourceId: Long): Novel? {
        return handler.awaitOneOrNull {
            novelsQueries.getNovelByUrlAndSource(url, sourceId, NovelMapper::mapNovel)
        }
    }

    override fun getNovelByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Novel?> {
        return handler.subscribeToOneOrNull {
            novelsQueries.getNovelByUrlAndSource(url, sourceId, NovelMapper::mapNovel)
        }
    }

    override suspend fun getFavorites(): List<Novel> {
        return handler.awaitList { novelsQueries.getFavorites(NovelMapper::mapNovel) }
    }

    override fun getFavoritesAsFlow(): Flow<List<Novel>> {
        return handler.subscribeToList { novelsQueries.getFavorites(NovelMapper::mapNovel) }
    }

    override fun getFavoritesBySourceId(sourceId: Long): Flow<List<Novel>> {
        return handler.subscribeToList { novelsQueries.getFavoritesBySourceId(sourceId, NovelMapper::mapNovel) }
    }

    override suspend fun getNovelsBySourceId(sourceId: Long): List<Novel> {
        return handler.awaitList { novelsQueries.getAllNovelsBySource(sourceId, NovelMapper::mapNovel) }
    }

    override suspend fun getAll(): List<Novel> {
        return handler.awaitList { novelsQueries.getAllNovels(NovelMapper::mapNovel) }
    }

    override suspend fun insert(novel: Novel): Long {
        return handler.await(inTransaction = true) {
            novelsQueries.insert(
                source = novel.source,
                url = novel.url,
                title = novel.title,
                artist = novel.artist,
                author = novel.author,
                description = novel.description,
                genre = novel.genre,
                status = novel.status,
                thumbnailUrl = novel.thumbnailUrl,
                favorite = if (novel.favorite) 1L else 0L,
                lastUpdate = novel.lastUpdate,
                nextUpdate = novel.nextUpdate,
                initialized = if (novel.initialized) 1L else 0L,
                coverLastModified = novel.coverLastModified,
                dateAdded = novel.dateAdded,
                totalChapters = novel.totalChapters.toLong(),
                version = novel.version,
                notes = novel.notes,
            )
            novelsQueries.selectLastInsertedRowId().executeAsOne()
        }
    }

    override suspend fun update(update: NovelUpdate): Boolean {
        return try {
            partialUpdate(update)
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun updateAll(updates: List<NovelUpdate>): Boolean {
        return try {
            partialUpdate(*updates.toTypedArray())
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    override suspend fun deleteNovel(novelId: Long) {
        handler.await { novelsQueries.deleteById(novelId) }
    }

    override suspend fun setFavorite(novelId: Long, favorite: Boolean): Boolean {
        return try {
            handler.await {
                novelsQueries.update(
                    novelId = novelId,
                    source = null,
                    url = null,
                    title = null,
                    artist = null,
                    author = null,
                    description = null,
                    genre = null,
                    status = null,
                    thumbnailUrl = null,
                    favorite = if (favorite) 1L else 0L,
                    lastUpdate = null,
                    nextUpdate = null,
                    initialized = null,
                    coverLastModified = null,
                    dateAdded = null,
                    totalChapters = null,
                    fetchInterval = null,
                    version = null,
                    notes = null,
                )
            }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            false
        }
    }

    private suspend fun partialUpdate(vararg updates: NovelUpdate) {
        handler.await(inTransaction = true) {
            updates.forEach { value ->
                novelsQueries.update(
                    novelId = value.id,
                    source = value.source,
                    url = value.url,
                    title = value.title,
                    artist = value.artist,
                    author = value.author,
                    description = value.description,
                    genre = value.genre,
                    status = value.status,
                    thumbnailUrl = value.thumbnailUrl,
                    favorite = value.favorite?.let { if (it) 1L else 0L },
                    lastUpdate = value.lastUpdate,
                    nextUpdate = value.nextUpdate,
                    initialized = value.initialized?.let { if (it) 1L else 0L },
                    coverLastModified = value.coverLastModified,
                    dateAdded = value.dateAdded,
                    totalChapters = value.totalChapters?.toLong(),
                    fetchInterval = value.fetchInterval?.toLong(),
                    version = value.version,
                    notes = value.notes,
                )
            }
        }
    }
}
