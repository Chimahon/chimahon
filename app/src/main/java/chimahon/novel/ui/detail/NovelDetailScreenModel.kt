package chimahon.novel.ui.detail

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import chimahon.novel.data.BookImporter
import chimahon.novel.download.NovelDownloadManager
import chimahon.novel.interactor.RegisterLocalNovelHome
import chimahon.novel.manager.NovelSourceManager
import chimahon.novel.plugin.SimpleLNReaderSource
import chimahon.novel.source.LocalNovelFiles
import eu.kanade.presentation.entries.DownloadAction
import eu.kanade.tachiyomi.network.NetworkHelper
import logcat.LogPriority
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelChapter
import tachiyomi.domain.novel.model.NovelChapterUpdate
import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.domain.novel.model.NovelUpdate
import tachiyomi.domain.novel.model.toSNNovel
import tachiyomi.domain.novel.repository.NovelCategoryRepository
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.util.system.logcat

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
    data class ChangeCategory(val initialSelection: ImmutableList<CheckboxState<Category>>) : Dialog
    data object SetDictionaryProfile : Dialog
}

sealed interface FileDownloadState {
    data object Idle : FileDownloadState
    data object Downloading : FileDownloadState
    data class Done(val title: String, val bookId: String? = null, val folder: String? = null) : FileDownloadState
    data class Error(val message: String) : FileDownloadState
}

object NovelSort {
    const val SORT_SOURCE = 0x00000000L
    const val SORT_NUMBER = 0x00000100L
    const val SORT_UPLOAD_DATE = 0x00000200L
    const val SORT_ALPHABET = 0x00000300L
    const val SORT_MASK = 0x00000300L

    const val SORT_DESC = 0x00000000L
    const val SORT_ASC = 0x00000001L
    const val SORT_DIR_MASK = 0x00000001L
}

@Immutable
data class NovelDetailState(
    val novel: SNNovel = SNNovel.create(),
    val dbNovel: Novel? = null,
    val source: NovelSource? = null,
    val chapters: List<NovelChapterItem> = emptyList(),
    val isLoading: Boolean = true,
    val isFavorite: Boolean = false,
    val detailError: String? = null,
    val chapterError: String? = null,
    val dialog: Dialog? = null,
    val selectedChapters: Set<Long> = emptySet(),
    val selectionMode: Boolean = false,
    val isRefreshingData: Boolean = false,
    val isDownloadSource: Boolean = false,
    val fileDownload: FileDownloadState = FileDownloadState.Idle,
    val sortMode: Long = NovelSort.SORT_SOURCE,
    val sortDescending: Boolean = true,
    val unreadOnly: Boolean = false,
    val bookmarkedOnly: Boolean = false,
)

class NovelDetailScreenModel(
    private val novel: SNNovel,
    sourceId: Long,
    private val novelRepository: NovelRepository = Injekt.get(),
    private val syncNovelChapters: chimahon.novel.interactor.SyncNovelChapters = Injekt.get(),
    private val setNovelReadStatus: chimahon.novel.interactor.SetNovelReadStatus = Injekt.get(),
    private val setNovelChapterFlags: chimahon.novel.interactor.SetNovelChapterFlags = Injekt.get(),
    private val getNextNovelChapters: chimahon.novel.interactor.GetNextNovelChapters = Injekt.get(),
    private val updateNovel: chimahon.novel.interactor.UpdateNovel = Injekt.get(),
    private val setNovelCategoriesInteractor: chimahon.novel.interactor.SetNovelCategories = Injekt.get(),
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
    private val novelCategoryRepository: NovelCategoryRepository = Injekt.get(),
    private val downloadManager: NovelDownloadManager = Injekt.get(),
    private val app: Application = Injekt.get(),
) : StateScreenModel<NovelDetailState>(
    NovelDetailState(
        novel = novel,
        source = Injekt.get<NovelSourceManager>().getNovelSource(sourceId),
        isLoading = false,
    ),
) {
    private val source: NovelSource = mutableState.value.source
        ?: run {
            // Source not immediately available; try again after a brief delay
            val found = Injekt.get<NovelSourceManager>().getNovelSource(sourceId)
            if (found != null) {
                mutableState.value = mutableState.value.copy(source = found)
                found
            } else {
                // Extension uninstalled: degrade to a stub so library entries still
                // render; source calls throw NovelSourceNotInstalledException which
                // the runCatching blocks below surface as screen errors.
                val stub = Injekt.get<NovelSourceManager>().getOrStub(sourceId)
                mutableState.value = mutableState.value.copy(source = stub)
                stub
            }
        }

    private var cachedDbNovel: Novel? = null
    private var cachedChapters: List<SNChapter>? = null
    private var subscribedNovelId: Long? = null
    // Online-row favorite state before a file download auto-favorited it
    // (null = no download this session); used for symmetric delete.
    private var onlineFavoriteBeforeDownload: Boolean? = null

    init {
        loadDetails()
        screenModelScope.launch {
            val downloadCapable =
                (source as? SimpleLNReaderSource)?.hasDownloadSupport() == true
            mutableState.value = mutableState.value.copy(
                isDownloadSource = downloadCapable,
            )
        }
    }

    fun resume() {
        // No-op: the reader mirrors position into the DB live, so there is
        // nothing left to sync on resume.
    }

    private fun loadDetails() {
        screenModelScope.launch {
            mutableState.value = mutableState.value.copy(
                isLoading = false,
                isRefreshingData = true,
                detailError = null,
                chapterError = null,
            )

            // Render cache instantly; network only when the DB is empty.
            val dbNovel = try {
                novelRepository.getNovelByUrlAndSourceId(novel.url, source.id)
            } catch (_: Exception) {
                null
            }
            val dbChapters = dbNovel?.let {
                try {
                    novelChapterRepository.getChaptersByNovelId(it.id)
                } catch (_: Exception) {
                    emptyList()
                }
            }.orEmpty()

            if (dbNovel != null && dbChapters.isNotEmpty()) {
                cachedDbNovel = dbNovel
                renderDbNovel(dbNovel, dbChapters)
                subscribeToDbChapters(dbNovel.id)
                return@launch
            }

            val detailsResult = runCatching { source.getNovelDetails(novel) }
            val details = detailsResult.getOrElse { error ->
                if (error is CancellationException) throw error
                novel
            }.let { mergeNovelDetails(novel, it) }

            mutableState.value = mutableState.value.copy(
                novel = details,
                dbNovel = dbNovel,
                isFavorite = dbNovel?.favorite ?: false,
                isLoading = false,
                detailError = detailsResult.exceptionOrNull()?.message ?: detailsResult.exceptionOrNull()?.let {
                    "Failed to load novel details"
                },
            )

            val chaptersResult = runCatching { source.getChapterList(details) }
            chaptersResult.exceptionOrNull()?.let {
                if (it is CancellationException) throw it
            }
            val chapters = chaptersResult.getOrDefault(emptyList())
            if (chapters.isNotEmpty()) {
                cachedChapters = chapters
                // Persist browses too (non-favorites stay out of
                // library/backup/updates).
                val synced = syncNovelToDatabase(details, chapters, favorite = dbNovel?.favorite ?: false)
                cachedDbNovel = synced
                val syncedChapters = syncSourceChapters(synced.id, chapters)
                mutableState.value = mutableState.value.copy(
                    dbNovel = synced,
                    chapters = buildChapterItems(chapters, syncedChapters),
                    isFavorite = synced.favorite,
                    isRefreshingData = false,
                    chapterError = null,
                )
                subscribeToDbChapters(synced.id)
            } else {
                // Never blank an existing list with an empty/flaky fetch
                mutableState.value = mutableState.value.copy(
                    isRefreshingData = false,
                    chapterError = chaptersResult.exceptionOrNull()?.message
                        ?: "Failed to load chapters",
                )
            }
        }
    }

    private fun renderDbNovel(dbNovel: Novel, dbChapters: List<NovelChapter>) {
        val snChapters = dbChapters.map { row ->
            SNChapter(
                url = row.url,
                name = row.name,
                chapter_number = row.chapterNumber,
                date_upload = row.dateUpload,
                scanlator = row.scanlator,
            )
        }
        // Seed the cache so library-add / bookmark sync reuse these without network.
        cachedChapters = snChapters
        // Persisted view prefs first: the item build below sorts by current state.
        mutableState.value = mutableState.value.copy(
            sortMode = dbNovel.chapterSortMode,
            sortDescending = dbNovel.chapterSortDescending,
            unreadOnly = dbNovel.chapterFilterUnread,
            bookmarkedOnly = dbNovel.chapterFilterBookmarked,
        )
        mutableState.value = mutableState.value.copy(
            novel = mergeNovelDetails(novel, dbNovel.toSNNovel()),
            dbNovel = dbNovel,
            chapters = buildChapterItems(snChapters, dbChapters),
            isFavorite = dbNovel.favorite,
            isLoading = false,
            isRefreshingData = false,
            chapterError = null,
        )
    }

    private fun subscribeToDbChapters(novelId: Long) {
        if (subscribedNovelId == novelId) return
        subscribedNovelId = novelId
        screenModelScope.launch {
            novelChapterRepository.getChaptersByNovelIdAsFlow(novelId).collect { dbChapters ->
                val merged = mutableState.value.chapters.map { item ->
                    val match = dbChapters.find { it.url == item.snChapter.url }
                    item.copy(novelChapter = match)
                }
                // Re-sort after merging DB data (in case sort depends on db fields)
                val sorted = sortItems(merged, mutableState.value.sortMode, mutableState.value.sortDescending)
                mutableState.value = mutableState.value.copy(chapters = sorted)
            }
        }
    }

    fun setSortMode(mode: Long) {
        val current = mutableState.value
        val newDescending = if (current.sortMode == mode) {
            // Flip direction when same mode is selected; the interactor below
            // lands the same flip on the row.
            !current.sortDescending
        } else {
            // New mode defaults to ascending.
            false
        }
        val sorted = sortItems(current.chapters, mode, newDescending)
        mutableState.value = current.copy(sortMode = mode, sortDescending = newDescending, chapters = sorted)
        val dbNovel = cachedDbNovel ?: return
        screenModelScope.launch {
            setNovelChapterFlags.awaitSetSortingModeOrFlipOrder(dbNovel, mode)
            refreshDbNovel(dbNovel.id)
        }
    }

    private fun sortItems(
        items: List<NovelChapterItem>,
        sortMode: Long,
        sortDescending: Boolean,
    ): List<NovelChapterItem> {
        if (items.isEmpty()) return items
        return items.sortedWith(getNovelChapterSort(sortMode, sortDescending))
    }

    private fun getNovelChapterSort(
        sortMode: Long,
        sortDescending: Boolean,
    ): Comparator<NovelChapterItem> {
        return Comparator { c1, c2 ->
            when (sortMode) {
                NovelSort.SORT_SOURCE -> when (sortDescending) {
                    true -> c1.effectiveSourceOrder.compareTo(c2.effectiveSourceOrder)
                    false -> c2.effectiveSourceOrder.compareTo(c1.effectiveSourceOrder)
                }
                NovelSort.SORT_NUMBER -> when (sortDescending) {
                    true -> c2.effectiveChapterNumber.compareTo(c1.effectiveChapterNumber)
                    false -> c1.effectiveChapterNumber.compareTo(c2.effectiveChapterNumber)
                }
                NovelSort.SORT_UPLOAD_DATE -> when (sortDescending) {
                    true -> c2.effectiveDateUpload.compareTo(c1.effectiveDateUpload)
                    false -> c1.effectiveDateUpload.compareTo(c2.effectiveDateUpload)
                }
                NovelSort.SORT_ALPHABET -> when (sortDescending) {
                    true -> c2.effectiveName.compareToWithCollator(c1.effectiveName)
                    false -> c1.effectiveName.compareToWithCollator(c2.effectiveName)
                }
                else -> throw NotImplementedError("Invalid sorting mode: $sortMode")
            }
        }
    }

    private val NovelChapterItem.effectiveSourceOrder: Long
        get() = novelChapter?.sourceOrder ?: index.toLong()

    private val NovelChapterItem.effectiveChapterNumber: Float
        get() = novelChapter?.chapterNumber ?: snChapter.chapter_number

    private val NovelChapterItem.effectiveDateUpload: Long
        get() = novelChapter?.dateUpload ?: snChapter.date_upload

    private val NovelChapterItem.effectiveName: String
        get() = novelChapter?.name ?: snChapter.name

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
        updateNovel.awaitUpdateFavorite(existing.id, false)
        cachedDbNovel = existing.copy(favorite = false, dateAdded = 0L)
        mutableState.value = mutableState.value.copy(
            dbNovel = cachedDbNovel,
            isFavorite = false,
        )
    }

    /** Next-unread FAB target: reading order, display sort ignored, the
     * novel's persisted unread/bookmarked filters applied. */
    suspend fun getNextUnreadChapter(): NovelChapterItem? {
        val dbNovel = cachedDbNovel ?: return null
        val next = getNextNovelChapters.await(dbNovel.id, onlyUnread = true).firstOrNull()
            ?: return null
        return mutableState.value.chapters.firstOrNull { it.novelChapter?.id == next.id }
    }

    fun setFetchInterval(interval: Int) {
        val dbNovel = cachedDbNovel ?: return
        screenModelScope.launch {
            novelRepository.update(NovelUpdate(id = dbNovel.id, fetchInterval = interval))
            val updated = novelRepository.getNovelById(dbNovel.id)
            cachedDbNovel = updated
            mutableState.value = mutableState.value.copy(dbNovel = updated)
        }
    }

    fun downloadChapter(chapter: NovelChapterItem) {
        val dbNovel = cachedDbNovel ?: return
        downloadManager.downloadChapters(dbNovel, listOf(chapter.snChapter), source, mutableState.value.novel)
    }

    fun deleteChapterDownload(chapter: NovelChapterItem) {
        val dbNovel = cachedDbNovel ?: return
        val dbChapter = chapter.novelChapter ?: return
        downloadManager.deleteChapter(dbNovel, dbChapter, source)
    }

    fun downloadChapters(action: DownloadAction) {        val dbNovel = cachedDbNovel ?: return
        // Next = oldest unread in reading order, independent of display sort
        val unread = mutableState.value.chapters
            .sortedBy { it.novelChapter?.chapterNumber ?: it.snChapter.chapter_number }
            .filter { !it.isRead }
        val toDownload = when (action) {
            DownloadAction.NEXT_1_ITEM -> unread.take(1)
            DownloadAction.NEXT_5_ITEMS -> unread.take(5)
            DownloadAction.NEXT_10_ITEMS -> unread.take(10)
            DownloadAction.NEXT_25_ITEMS -> unread.take(25)
            DownloadAction.UNVIEWED_ITEMS -> unread
        }.map { it.snChapter }
        if (toDownload.isEmpty()) return
        downloadManager.downloadChapters(dbNovel, toDownload, source, mutableState.value.novel)
    }

    /**
     * Whole-file download for file/download sources (no chapters): resolves
     * the file URL via the plugin, downloads it with OkHttp, and imports it
     * through the same [BookImporter] path as manual EPUB imports.
     */
    fun downloadBookFile() {
        val jsSource = source as? SimpleLNReaderSource ?: return
        if (mutableState.value.fileDownload is FileDownloadState.Downloading) return
        // IO: blocking OkHttp execute() must never run on Main
        // (NetworkOnMainThreadException).
        screenModelScope.launch(Dispatchers.IO) {
            mutableState.value = mutableState.value.copy(
                fileDownload = FileDownloadState.Downloading,
            )
            try {
                val novel = mutableState.value.novel
                val fileUrl = jsSource.getDownloadUrl(novel.url)
                if (fileUrl.isNullOrBlank()) {
                    mutableState.value = mutableState.value.copy(
                        fileDownload = FileDownloadState.Error("No downloadable file for this book"),
                    )
                    return@launch
                }
                val client = Injekt.get<NetworkHelper>().client
                val request = okhttp3.Request.Builder()
                    .url(fileUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val tmpFile = java.io.File(app.cacheDir, "book_${System.currentTimeMillis()}.epub")
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful || resp.body.contentLength() <= 0) {
                        throw IllegalStateException("Download failed (HTTP ${resp.code})")
                    }
                    resp.body.byteStream().use { input ->
                        tmpFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.provider", tmpFile)
                val result = BookImporter.importEpub(
                    app,
                    uri,
                    targetRootUni = LocalNovelFiles.publicRootUni(app),
                )
                runCatching { tmpFile.delete() }
                val metadata = result.metadata
                if (metadata != null) {
                    runCatching {
                        Injekt.get<RegisterLocalNovelHome>().register(metadata.id)
                    }
                    // Downloading is acquiring: the online novel joins the
                    // library too, so leaving and coming back still shows it
                    // favorited. Remember pre-state for symmetric delete.
                    onlineFavoriteBeforeDownload = mutableState.value.isFavorite
                    if (!mutableState.value.isFavorite) {
                        runCatching { addToLibrary() }
                    }
                    mutableState.value = mutableState.value.copy(
                        fileDownload = FileDownloadState.Done(
                            title = metadata.title ?: novel.title,
                            bookId = metadata.id,
                            folder = metadata.folder,
                        ),
                    )
                } else {
                    mutableState.value = mutableState.value.copy(
                        fileDownload = FileDownloadState.Error(result.error ?: "Import failed"),
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logcat(LogPriority.WARN, e) { "Book file download failed" }
                mutableState.value = mutableState.value.copy(
                    fileDownload = FileDownloadState.Error(e.message ?: "Download failed"),
                )
            }
        }
    }

    fun dismissFileDownload() {
        mutableState.value = mutableState.value.copy(
            fileDownload = FileDownloadState.Idle,
        )
    }

    /**
     * Deletes a book imported via [downloadBookFile] (files + library row)
     * and resets the download card. Mirrors the library delete path.
     */
    fun deleteDownloadedBook() {
        val done = mutableState.value.fileDownload as? FileDownloadState.Done ?: return
        val bookId = done.bookId ?: return
        // Only undo the auto-favorite when download created it; a prior
        // manual favorite survives the delete.
        val undoFavorite = onlineFavoriteBeforeDownload == false
        onlineFavoriteBeforeDownload = null
        screenModelScope.launch(Dispatchers.IO) {
            runCatching { chimahon.novel.data.BookStorage.deleteBook(app, bookId) }
            runCatching {
                val localNovel = novelRepository.getNovelByUrlAndSourceId(
                    "local://$bookId",
                    tachiyomi.domain.novel.model.Novel.LOCAL_SOURCE_ID,
                )
                if (localNovel != null) {
                    Injekt.get<chimahon.novel.interactor.UpdateNovel>().awaitUpdateFavorite(localNovel.id, false)
                }
            }
            if (undoFavorite) {
                runCatching { removeFromLibrary() }
            }
            mutableState.value = mutableState.value.copy(
                fileDownload = FileDownloadState.Idle,
            )
        }
    }

    fun markChapterRead(chapter: NovelChapterItem) {
        val dbChapter = chapter.novelChapter ?: return
        screenModelScope.launch {
            // Read flag only; position lives on the row untouched.
            novelChapterRepository.update(
                NovelChapterUpdate(
                    id = dbChapter.id,
                    read = true,
                )
            )
        }
    }

    fun markChapterUnread(chapter: NovelChapterItem) {
        val dbChapter = chapter.novelChapter ?: return
        screenModelScope.launch {
            setNovelReadStatus.await(read = false, chapters = listOf(dbChapter))
        }
    }

    fun markSelectedChaptersRead(read: Boolean) {
        val selected = mutableState.value.selectedChapters
        screenModelScope.launch {
            val chapters = mutableState.value.chapters
                .filter { it.id in selected }
                .mapNotNull { it.novelChapter }
            setNovelReadStatus.await(read = read, chapters = chapters)
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
            mutableState.value = mutableState.value.copy(
                isRefreshingData = true,
                detailError = null,
                chapterError = null,
            )
            val currentNovel = mutableState.value.novel
            val detailsResult = runCatching { source.getNovelDetails(currentNovel) }
            detailsResult.exceptionOrNull()?.let {
                if (it is CancellationException) throw it
            }
            val details = detailsResult.getOrDefault(currentNovel)
                .let { mergeNovelDetails(currentNovel, it) }
            mutableState.value = mutableState.value.copy(
                novel = details,
                detailError = detailsResult.exceptionOrNull()?.message ?: detailsResult.exceptionOrNull()?.let {
                    "Failed to refresh novel details"
                },
            )

            val chaptersResult = runCatching { source.getChapterList(details) }
            chaptersResult.exceptionOrNull()?.let {
                if (it is CancellationException) throw it
            }
            val chapters = chaptersResult.getOrNull()
            if (chapters.isNullOrEmpty()) {
                // Never blank an existing list with a failed/empty fetch
                mutableState.value = mutableState.value.copy(
                    isRefreshingData = false,
                    chapterError = chaptersResult.exceptionOrNull()?.message ?: "Failed to refresh chapters",
                )
                return@launch
            }
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
                dbNovel = cachedDbNovel,
                chapters = buildChapterItems(chapters, dbChapters),
                isFavorite = cachedDbNovel?.favorite ?: false,
                isRefreshingData = false,
                chapterError = null,
            )
        }
    }

    fun toggleChapterBookmark(chapter: NovelChapterItem) {
        val dbChapter = chapter.novelChapter ?: return
        screenModelScope.launch {
            novelChapterRepository.update(
                NovelChapterUpdate(id = dbChapter.id, bookmark = !dbChapter.bookmark)
            )
        }
    }

    fun markPreviousAsRead(chapter: NovelChapterItem) {
        // Previous in reading order (lower chapter_number), independent of display sort
        val currentNumber = chapter.novelChapter?.chapterNumber ?: chapter.snChapter.chapter_number
        screenModelScope.launch {
            val chapters = mutableState.value.chapters
                .mapNotNull { it.novelChapter }
                .filter { it.chapterNumber < currentNumber && !it.read }
            setNovelReadStatus.await(read = true, chapters = chapters)
        }
    }

    fun setChapterFilter(unreadOnly: Boolean, bookmarkedOnly: Boolean) {
        mutableState.value = mutableState.value.copy(
            unreadOnly = unreadOnly,
            bookmarkedOnly = bookmarkedOnly,
        )
        val dbNovel = cachedDbNovel ?: return
        screenModelScope.launch {
            setNovelChapterFlags.awaitSetUnreadFilter(dbNovel.id, unreadOnly)
            setNovelChapterFlags.awaitSetBookmarkFilter(dbNovel.id, bookmarkedOnly)
            refreshDbNovel(dbNovel.id)
        }
    }

    /** Re-reads the row after a flag write so later flips compute from truth. */
    private suspend fun refreshDbNovel(novelId: Long) {
        runCatching { novelRepository.getNovelById(novelId) }
            .getOrNull()?.let { cachedDbNovel = it }
    }

    fun downloadSelectedChapters() {
        val dbNovel = cachedDbNovel ?: return
        val toDownload = mutableState.value.chapters
            .filter { it.id in mutableState.value.selectedChapters }
            .map { it.snChapter }
        if (toDownload.isEmpty()) return
        downloadManager.downloadChapters(dbNovel, toDownload, source, mutableState.value.novel)
        clearSelection()
    }

    fun deleteSelectedDownloads() {
        val dbNovel = cachedDbNovel ?: return
        val selected = mutableState.value.selectedChapters
        screenModelScope.launch {
            mutableState.value.chapters
                .filter { it.id in selected }
                .mapNotNull { it.novelChapter }
                .forEach { dbCh ->
                    downloadManager.deleteChapter(dbNovel, dbCh, source)
                }
            clearSelection()
        }
    }

    fun markSelectedPreviousAsRead() {
        val selected = mutableState.value.chapters
            .filter { it.id in mutableState.value.selectedChapters }
        val first = selected.minByOrNull { it.novelChapter?.chapterNumber ?: it.snChapter.chapter_number } ?: return
        markPreviousAsRead(first)
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

    /** Dictionary profile override dialog (manga detail parity). */
    fun showSetDictionaryProfileDialog() {
        if (cachedDbNovel == null) return
        showDialog(Dialog.SetDictionaryProfile)
    }

    /** Persist (or clear) the novel-level override; null falls back to source/language/global. */
    fun setNovelDictionaryProfile(profileId: String?) {
        val dbNovel = cachedDbNovel ?: return
        val prefs = Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>()
        // Folder-based key, same shape the reader resolves (existing
        // overrides keep working).
        val key = chimahon.dictionary.DictionaryProfileResolver.novelOverrideKey(
            dbNovel.localFolder?.takeIf { it.isNotBlank() } ?: dbNovel.id.toString(),
        )
        if (profileId == null) {
            prefs.rawProfileOverride(key).delete()
        } else {
            prefs.rawProfileOverride(key).set(profileId)
        }
        dismissDialog()
    }

    /** Auto preview for the dialog (novel override itself excluded). */
    fun resolveAutoNovelProfile(): chimahon.anki.AnkiProfile {
        val dbNovel = cachedDbNovel
        val sourceId = dbNovel?.source ?: source.id
        val lang = dbNovel?.lang?.takeIf { it.isNotBlank() }
            ?: runCatching {
                Injekt.get<chimahon.novel.manager.NovelSourceManager>().getOrStub(sourceId).lang
            }.getOrNull().orEmpty()
        return Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>().profileResolver.resolve(
            novelId = "",
            sourceId = sourceId,
            sourceLang = lang,
        )
    }

    /** Per-novel categories for library favorites (manga detail parity). */
    fun showChangeCategoryDialog() {
        val dbNovel = cachedDbNovel ?: return
        screenModelScope.launch {
            val all = novelCategoryRepository.getAll()
            val assigned = novelCategoryRepository.getByNovelId(dbNovel.id).map { it.id }.toSet()
            val initialSelection = all.map { cat ->
                val mapped = Category(
                    id = cat.id.hashCode().toLong(),
                    name = cat.name,
                    order = cat.order.toLong(),
                    flags = cat.flags,
                    hidden = false,
                )
                if (cat.id in assigned) {
                    CheckboxState.State.Checked(mapped)
                } else {
                    CheckboxState.State.None(mapped)
                }
            }.toImmutableList()
            mutableState.value = mutableState.value.copy(
                dialog = Dialog.ChangeCategory(initialSelection),
            )
        }
    }

    fun setNovelCategories(include: List<Long>) {
        val dbNovel = cachedDbNovel ?: return
        screenModelScope.launch {
            val ids = novelCategoryRepository.getAll()
                .filter { cat -> include.any { it == cat.id.hashCode().toLong() } }
                .map { it.id }
            setNovelCategoriesInteractor.await(dbNovel.id, ids)
            dismissDialog()
        }
    }

    private suspend fun syncNovelToDatabase(
        details: SNNovel,
        chapters: List<SNChapter>,
        favorite: Boolean,
    ): Novel {
        val now = System.currentTimeMillis()
        val existing = novelRepository.getNovelByUrlAndSourceId(details.url, source.id)
        // Source lang seeds the row once: profile language-match and
        // direction inference both read Novel.lang. Never overwrites a set
        // value — EPUB/import lang wins where present.
        val seedLang = source.lang.takeIf { it.isNotBlank() && it != "all" }
        if (existing == null) {
            val id = novelRepository.insert(
                Novel.fromSourceNovel(details, source.id, lang = seedLang).copy(
                    favorite = favorite,
                    dateAdded = now,
                    initialized = true,
                    totalChapters = chapters.size,
                    lastUpdate = now,
                ),
            )
            return novelRepository.getNovelById(id)
        }

        updateNovel.await(
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
                lang = if (existing.lang.isNullOrBlank()) seedLang else null,
            ),
        )
        return novelRepository.getNovelById(existing.id)
    }

    private suspend fun syncSourceChapters(
        novelId: Long,
        sourceChapters: List<SNChapter>,
    ): List<NovelChapter> {
        return syncNovelChapters.await(novelId, sourceChapters).chapters
    }

    private fun mergeNovelDetails(current: SNNovel, fresh: SNNovel): SNNovel {
        // Flaky sources may return partial blanks on success; never regress filled fields
        return fresh.copy(
            title = fresh.title.ifBlank { current.title },
            author = fresh.author?.takeIf { it.isNotBlank() } ?: current.author,
            artist = fresh.artist?.takeIf { it.isNotBlank() } ?: current.artist,
            description = fresh.description?.takeIf { it.isNotBlank() } ?: current.description,
            genre = fresh.genre?.takeIf { it.isNotBlank() } ?: current.genre,
            status = fresh.status.takeIf { it != SNNovel.UNKNOWN } ?: current.status,
            thumbnail_url = fresh.thumbnail_url?.takeIf { it.isNotBlank() } ?: current.thumbnail_url,
        )
    }

    private fun buildChapterItems(
        sourceChapters: List<SNChapter>,
        dbChapters: List<NovelChapter> = emptyList(),
    ): List<NovelChapterItem> {
        val dbByUrl = dbChapters.associateBy { it.url }
        val items = sourceChapters.mapIndexed { index, chapter ->
            NovelChapterItem(
                index = index,
                snChapter = chapter,
                novelChapter = dbByUrl[chapter.url],
            )
        }
        return sortItems(items, mutableState.value.sortMode, mutableState.value.sortDescending)
    }
}
