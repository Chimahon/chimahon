package chimahon.novel.ui.detail

import android.content.Context
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.canopus.chimareader.data.BookStorage
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.coroutines.launch
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelChapter
import tachiyomi.domain.novel.model.NovelChapterUpdate
import tachiyomi.domain.novel.model.NovelUpdate
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.max

data class NovelChapterItem(
    val index: Int,
    val snChapter: SNChapter,
    val novelChapter: NovelChapter? = null,
    val selected: Boolean = false,
) {
    val id: Long get() = novelChapter?.id ?: -(index + 1).toLong()
    val isRead: Boolean get() = novelChapter?.read ?: false
    val lastPageRead: Long get() = novelChapter?.lastPageRead ?: 0
    val isBookmarked: Boolean get() = novelChapter?.bookmark ?: false
}

sealed interface Dialog {
    data object DeleteChapters : Dialog
}

@Immutable
data class NovelDetailState(
    val novel: SNNovel = SNNovel.create(),
    val dbNovel: Novel? = null,
    val source: NovelSource? = null,
    val chapters: List<NovelChapterItem> = emptyList(),
    val isLoading: Boolean = true,
    val isFavorite: Boolean = false,
    val error: String? = null,
    val dialog: Dialog? = null,
    val selectedChapters: Set<Long> = emptySet(),
    val selectionMode: Boolean = false,
    val isRefreshingData: Boolean = false,
)

class NovelDetailScreenModel(
    private val novel: SNNovel,
    private val source: NovelSource,
    private val novelRepository: NovelRepository = Injekt.get(),
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
) : StateScreenModel<NovelDetailState>(NovelDetailState(isLoading = true)) {

    private var cachedDbNovel: Novel? = null
    private var cachedChapters: List<SNChapter>? = null

    init {
        loadDetails()
        observeFavoriteStatus()
    }

    fun resume() {
        screenModelScope.launch {
            syncBookmarkToDb()
        }
    }

    private fun loadDetails() {
        screenModelScope.launch {
            mutableState.value = mutableState.value.copy(isLoading = true)
            try {
                val details = source.getNovelDetails(novel)
                val chapters = source.getChapterList(novel)
                cachedChapters = chapters

                val existing = novelRepository.getNovelByUrlAndSourceId(details.url, source.id)
                if (existing != null) {
                    cachedDbNovel = existing
                    subscribeToDbChapters(existing.id)
                }

                mutableState.value = mutableState.value.copy(
                    novel = details,
                    dbNovel = existing,
                    chapters = buildChapterItems(chapters),
                    isFavorite = existing?.favorite ?: false,
                    isLoading = false,
                )
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(
                    isLoading = false,
                    error = e.message,
                )
            }
        }
    }

    private fun observeFavoriteStatus() {
        screenModelScope.launch {
            val existing = novelRepository.getNovelByUrlAndSourceId(novel.url, source.id)
            if (existing != null) {
                cachedDbNovel = existing
                mutableState.value = mutableState.value.copy(
                    dbNovel = existing,
                    isFavorite = existing.favorite,
                )
                subscribeToDbChapters(existing.id)
            }
        }
    }

    private fun subscribeToDbChapters(novelId: Long) {
        screenModelScope.launch {
            novelChapterRepository.getChaptersByNovelIdAsFlow(novelId).collect { dbChapters ->
                val merged = mutableState.value.chapters.map { item ->
                    val match = dbChapters.find { it.url == item.snChapter.url }
                    item.copy(novelChapter = match)
                }
                mutableState.value = mutableState.value.copy(chapters = merged)
            }
        }
    }

    fun toggleFavorite() {
        screenModelScope.launch {
            if (mutableState.value.isFavorite) {
                removeFromLibrary()
            } else {
                addToLibrary()
            }
        }
    }

    private suspend fun addToLibrary() {
        val details = mutableState.value.novel.takeIf { it.url.isNotBlank() } ?: source.getNovelDetails(novel)
        val sourceChapters = cachedChapters ?: source.getChapterList(details)
        cachedChapters = sourceChapters

        val dbNovel = syncNovelToDatabase(details, sourceChapters, favorite = true)
        val dbChapters = syncSourceChapters(dbNovel.id, sourceChapters)

        cachedDbNovel = dbNovel
        mutableState.value = mutableState.value.copy(
            novel = details,
            dbNovel = dbNovel,
            chapters = buildChapterItems(sourceChapters, dbChapters),
            isFavorite = true,
        )
        subscribeToDbChapters(dbNovel.id)
    }

    private suspend fun removeFromLibrary() {
        val existing = novelRepository.getNovelByUrlAndSourceId(novel.url, source.id) ?: return
        novelRepository.deleteNovel(existing.id)
        cachedDbNovel = null
        val cleared = mutableState.value.chapters.map { it.copy(novelChapter = null) }
        mutableState.value = mutableState.value.copy(
            dbNovel = null,
            isFavorite = false,
            chapters = cleared,
        )
    }

    fun getNextUnreadChapter(): NovelChapterItem? {
        return mutableState.value.chapters.firstOrNull { !it.isRead }
    }

    fun markChapterRead(chapter: NovelChapterItem) {
        val dbChapter = chapter.novelChapter ?: return
        screenModelScope.launch {
            novelChapterRepository.update(
                NovelChapterUpdate(
                    id = dbChapter.id,
                    read = true,
                    lastPageRead = 0,
                )
            )
        }
    }

    fun markChapterUnread(chapter: NovelChapterItem) {
        val dbChapter = chapter.novelChapter ?: return
        screenModelScope.launch {
            novelChapterRepository.update(
                NovelChapterUpdate(
                    id = dbChapter.id,
                    read = false,
                    lastPageRead = 0,
                )
            )
        }
    }

    fun markSelectedChaptersRead(read: Boolean) {
        val selected = mutableState.value.selectedChapters
        screenModelScope.launch {
            mutableState.value.chapters
                .filter { it.id in selected }
                .mapNotNull { it.novelChapter }
                .forEach { dbCh ->
                    novelChapterRepository.update(
                        NovelChapterUpdate(
                            id = dbCh.id,
                            read = read,
                            lastPageRead = if (read) 0 else dbCh.lastPageRead,
                        )
                    )
                }
            clearSelection()
        }
    }

    fun markSelectedChaptersBookmark(bookmarked: Boolean) {
        val selected = mutableState.value.selectedChapters
        screenModelScope.launch {
            mutableState.value.chapters
                .filter { it.id in selected }
                .mapNotNull { it.novelChapter }
                .filter { it.bookmark != bookmarked }
                .forEach { dbCh ->
                    novelChapterRepository.update(
                        NovelChapterUpdate(id = dbCh.id, bookmark = bookmarked)
                    )
                }
            clearSelection()
        }
    }

    fun refresh() {
        screenModelScope.launch {
            mutableState.value = mutableState.value.copy(isRefreshingData = true)
            try {
                val details = source.getNovelDetails(mutableState.value.novel)
                val chapters = source.getChapterList(mutableState.value.novel)
                cachedChapters = chapters

                val existing = cachedDbNovel ?: novelRepository.getNovelByUrlAndSourceId(details.url, source.id)
                val dbChapters = if (existing != null) {
                    val dbNovel = syncNovelToDatabase(details, chapters, favorite = existing.favorite)
                    cachedDbNovel = dbNovel
                    syncSourceChapters(dbNovel.id, chapters)
                } else {
                    emptyList()
                }

                mutableState.value = mutableState.value.copy(
                    novel = details,
                    dbNovel = cachedDbNovel,
                    chapters = buildChapterItems(chapters, dbChapters),
                    isFavorite = cachedDbNovel?.favorite ?: false,
                    isRefreshingData = false,
                    error = null,
                )
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(
                    isRefreshingData = false,
                    error = e.message,
                )
            }
        }
    }

    fun toggleChapterSelection(chapterId: Long) {
        val current = mutableState.value.selectedChapters.toMutableSet()
        if (chapterId in current) current.remove(chapterId) else current.add(chapterId)
        mutableState.value = mutableState.value.copy(
            selectedChapters = current,
            selectionMode = current.isNotEmpty(),
        )
    }

    fun clearSelection() {
        mutableState.value = mutableState.value.copy(
            selectedChapters = emptySet(),
            selectionMode = false,
        )
    }

    fun selectAll() {
        mutableState.value = mutableState.value.copy(
            selectedChapters = mutableState.value.chapters.map { it.id }.toSet(),
            selectionMode = true,
        )
    }

    fun invertSelection() {
        val all = mutableState.value.chapters.map { it.id }.toSet()
        val current = mutableState.value.selectedChapters
        val inverted = all - current
        mutableState.value = mutableState.value.copy(
            selectedChapters = inverted,
            selectionMode = inverted.isNotEmpty(),
        )
    }

    fun showDialog(dialog: Dialog) {
        mutableState.value = mutableState.value.copy(dialog = dialog)
    }

    fun dismissDialog() {
        mutableState.value = mutableState.value.copy(dialog = null)
    }

    private suspend fun syncBookmarkToDb() {
        val sourceChapters = cachedChapters ?: run {
            try {
                source.getChapterList(mutableState.value.novel)
            } catch (_: Exception) { return }
        }

        val bookId = SourceChapterBookBuilder.bookId(source, mutableState.value.novel)
        val context: Context = Injekt.get()
        val bookDir = BookStorage.getBookDirectory(context, bookId)
        if (!bookDir.exists()) return

        val bookmark = BookStorage.loadBookmark(bookDir) ?: return
        val dbNovel = cachedDbNovel ?: return
        if (bookmark.chapterIndex < 0 || bookmark.chapterIndex >= sourceChapters.size) return

        val snChapter = sourceChapters[bookmark.chapterIndex]
        val dbChapter = novelChapterRepository.getChapterByUrlAndNovelId(snChapter.url, dbNovel.id) ?: return
        val localCharacterCount = chapterLocalCharacterCount(bookDir, bookmark)
        val completed = bookmark.progress >= 0.999

        novelChapterRepository.update(
            NovelChapterUpdate(
                id = dbChapter.id,
                read = dbChapter.read || completed,
                lastPageRead = if (completed) 0 else max(dbChapter.lastPageRead, localCharacterCount),
            )
        )
    }

    private suspend fun syncNovelToDatabase(
        details: SNNovel,
        chapters: List<SNChapter>,
        favorite: Boolean,
    ): Novel {
        val now = System.currentTimeMillis()
        val existing = novelRepository.getNovelByUrlAndSourceId(details.url, source.id)
        if (existing == null) {
            val id = novelRepository.insert(
                Novel.fromSourceNovel(details, source.id).copy(
                    favorite = favorite,
                    dateAdded = now,
                    initialized = true,
                    totalChapters = chapters.size,
                    lastUpdate = now,
                ),
            )
            return novelRepository.getNovelById(id)
        }

        novelRepository.update(
            NovelUpdate(
                id = existing.id,
                url = details.url,
                source = source.id,
                title = details.title,
                artist = details.artist,
                author = details.author,
                description = details.description,
                genre = details.genre,
                status = details.status.toLong(),
                thumbnailUrl = details.thumbnail_url,
                favorite = favorite || existing.favorite,
                dateAdded = existing.dateAdded.takeIf { it > 0 } ?: now,
                initialized = true,
                totalChapters = chapters.size,
                lastUpdate = now,
            ),
        )
        return novelRepository.getNovelById(existing.id)
    }

    private suspend fun syncSourceChapters(
        novelId: Long,
        sourceChapters: List<SNChapter>,
    ): List<NovelChapter> {
        val now = System.currentTimeMillis()
        val dedupedSourceChapters = sourceChapters.distinctBy { it.url }
        val dbByUrl = novelChapterRepository.getChaptersByNovelId(novelId).associateBy { it.url }
        val newChapters = mutableListOf<NovelChapter>()

        dedupedSourceChapters.forEachIndexed { index, snChapter ->
            val existing = dbByUrl[snChapter.url]
            if (existing == null) {
                newChapters.add(snChapter.toDbChapter(novelId, index, now))
            } else {
                val update = NovelChapterUpdate(
                    id = existing.id,
                    novelId = novelId,
                    url = snChapter.url,
                    name = snChapter.name,
                    scanlator = snChapter.scanlator,
                    sourceOrder = index.toLong(),
                    chapterNumber = snChapter.chapter_number,
                    dateFetch = now,
                    dateUpload = snChapter.date_upload,
                )
                if (existing.needsSourceMetadataUpdate(snChapter, index)) {
                    novelChapterRepository.update(update)
                }
            }
        }

        if (newChapters.isNotEmpty()) {
            novelChapterRepository.insertAll(newChapters)
        }
        return novelChapterRepository.getChaptersByNovelId(novelId)
    }

    private fun buildChapterItems(
        sourceChapters: List<SNChapter>,
        dbChapters: List<NovelChapter> = emptyList(),
    ): List<NovelChapterItem> {
        val dbByUrl = dbChapters.associateBy { it.url }
        return sourceChapters.mapIndexed { index, chapter ->
            NovelChapterItem(
                index = index,
                snChapter = chapter,
                novelChapter = dbByUrl[chapter.url],
            )
        }
    }

    private fun SNChapter.toDbChapter(novelId: Long, index: Int, now: Long): NovelChapter {
        return NovelChapter.create().copy(
            novelId = novelId,
            url = url,
            name = name,
            scanlator = scanlator,
            sourceOrder = index.toLong(),
            chapterNumber = chapter_number,
            dateFetch = now,
            dateUpload = date_upload,
        )
    }

    private fun NovelChapter.needsSourceMetadataUpdate(chapter: SNChapter, index: Int): Boolean {
        return name != chapter.name ||
            scanlator != chapter.scanlator ||
            sourceOrder != index.toLong() ||
            chapterNumber != chapter.chapter_number ||
            dateUpload != chapter.date_upload
    }

    private fun chapterLocalCharacterCount(
        bookDir: java.io.File,
        bookmark: com.canopus.chimareader.data.Bookmark,
    ): Long {
        val chapterCharacters = runCatching {
            BookStorage.loadEpub(bookDir).getChapterCharacters(bookmark.chapterIndex)
        }.getOrDefault(0)
        return if (chapterCharacters > 0) {
            (chapterCharacters * bookmark.progress.coerceIn(0.0, 1.0)).toLong()
        } else if (bookmark.chapterIndex == 0) {
            bookmark.characterCount.toLong().coerceAtLeast(0)
        } else {
            0L
        }
    }
}
