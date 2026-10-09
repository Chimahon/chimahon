package tachiyomi.domain.novel.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelUpdate

interface NovelRepository {

    suspend fun getNovelById(id: Long): Novel

    fun getNovelByIdAsFlow(id: Long): Flow<Novel>

    suspend fun getNovelByUrlAndSourceId(url: String, sourceId: Long): Novel?

    suspend fun getNovelByLocalFolder(folder: String): Novel?

    fun getNovelByUrlAndSourceIdAsFlow(url: String, sourceId: Long): Flow<Novel?>

    suspend fun getFavorites(): List<Novel>

    fun getFavoritesAsFlow(): Flow<List<Novel>>

    fun getFavoritesBySourceId(sourceId: Long): Flow<List<Novel>>

    suspend fun getNovelsBySourceId(sourceId: Long): List<Novel>

suspend fun getAll(): List<Novel>

    /** Novels in the library, through novel_libraryView. */
    suspend fun getLibraryNovels(): List<Novel>

    /**
     * Novels outside the library that still have reading history, through
     * novel_libraryView, for the "all read" statistics view. Manga's counterpart
     * is getReadMangaNotInLibraryView.
     */
    suspend fun getReadNovelsNotInLibraryView(): List<Novel>

    suspend fun insert(novel: Novel): Long

    suspend fun update(update: NovelUpdate): Boolean

    suspend fun updateAll(updates: List<NovelUpdate>): Boolean

    suspend fun deleteNovel(novelId: Long)

    suspend fun deleteNonLibraryNovelsByIds(ids: List<Long>, keepRead: Boolean)

    suspend fun setFavorite(novelId: Long, favorite: Boolean): Boolean
}
