package eu.kanade.tachiyomi.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.ColorInt
import androidx.annotation.IntRange
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chimahon.ocr.OcrBitmapDecoder
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.reader.stats.MangaStatsEstimate
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import chimahon.ocr.effectiveScanResolution
import chimahon.ocr.isOcrAllowedForLanguage
import eu.kanade.tachiyomi.data.ocr.retryWithBackoff
import eu.kanade.tachiyomi.data.ocr.LocalOcrBridge
import eu.kanade.tachiyomi.data.ocr.ModelDownloader
import eu.kanade.tachiyomi.data.ocr.OcrEngineType
import eu.kanade.tachiyomi.data.ocr.PaddleOcrBridge
import eu.kanade.tachiyomi.data.ocr.recognizePage
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.saver.Location
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.all.MergedSource
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.toScreenLookupBlocks
import eu.kanade.tachiyomi.ui.reader.viewer.OcrCoordinateMapper
import eu.kanade.tachiyomi.ui.reader.viewer.OcrPageRegion
import eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import eu.kanade.tachiyomi.ui.reader.loader.ChapterLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOcrSource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.fullText
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import eu.kanade.tachiyomi.util.chapter.filterDownloaded
import eu.kanade.tachiyomi.util.chapter.removeDuplicates
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.lang.byteSize
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.DiskUtil.MAX_FILE_NAME_BYTES
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.MERGED_SOURCE_ID
import exh.source.getMainSource
import exh.source.isEhBasedManga
import exh.util.defaultReaderType
import exh.util.mangaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min
import logcat.LogPriority

import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.core.common.preference.toggle
import tachiyomi.core.common.storage.UniFileTempFileManager
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetMergedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.model.HistoryUpdate
import tachiyomi.domain.history.model.ReadingSession
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMergedMangaById
import tachiyomi.domain.manga.interactor.GetMergedReferencesById
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import tachiyomi.source.local.io.Archive
import mihon.domain.panel.repository.PanelDetectionRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.Instant
import java.util.Collections.emptyList
import java.util.Date
import java.util.LinkedHashMap

/**
 * Presenter used by the activity to perform background operations.
 */
class ReaderViewModel @JvmOverloads constructor(
    private val savedState: SavedStateHandle,
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val tempFileManager: UniFileTempFileManager = Injekt.get(),
    private val imageSaver: ImageSaver = Injekt.get(),
    val readerPreferences: ReaderPreferences = Injekt.get(),
    private val basePreferences: BasePreferences = Injekt.get(),
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    private val trackChapter: TrackChapter = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val upsertHistory: UpsertHistory = Injekt.get(),
    private val historyRepository: HistoryRepository = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val setMangaViewerFlags: SetMangaViewerFlags = Injekt.get(),
    private val getIncognitoState: GetIncognitoState = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    // SY -->
    private val syncPreferences: SyncPreferences = Injekt.get(),
    private val uiPreferences: UiPreferences = Injekt.get(),
    private val getFlatMetadataById: GetFlatMetadataById = Injekt.get(),
    private val getMergedMangaById: GetMergedMangaById = Injekt.get(),
    private val getMergedReferencesById: GetMergedReferencesById = Injekt.get(),
    private val getMergedChaptersByMangaId: GetMergedChaptersByMangaId = Injekt.get(),
    // SY <--

    // Chimahon: OCR and dictionary
    private val ocrCacheManager: chimahon.ocr.OcrCacheManager = Injekt.get(),
    private val dictionaryPreferences: DictionaryPreferences = Injekt.get(),
    private val ocrManager: eu.kanade.tachiyomi.data.ocr.OcrManager = uy.kohesive.injekt.Injekt.get(),
    private val localFileSystem: tachiyomi.source.local.io.LocalSourceFileSystem = Injekt.get(),
    private val application: Application = Injekt.get(),
    private val networkClient: okhttp3.OkHttpClient = Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client,
) : ViewModel() {

    private data class OcrCacheKey(
        val chapterId: Long,
        val pageIndex: Int,
        val ocrSource: ReaderOcrSource,
    )

    private data class MokuroChapterData(
        val mokuro: chimahon.ocr.MokuroVolume,
        val imageFiles: List<chimahon.ocr.ImageFileInfo>,
    )

    private val ocrCacheMutex = Mutex()
    private val ocrCache = LinkedHashMap<OcrCacheKey, List<eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock>>()
    private val ocrInFlight =
        mutableMapOf<OcrCacheKey, Deferred<List<eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock>>>()
    private val ocrDispatcher = Dispatchers.IO.limitedParallelism(2)
    private var ocrScanJob: Job? = null
    private var ocrScanChapterId: Long? = null
    private val ocrScannedChapterIds = mutableSetOf<Long>()
    private val maxOcrCacheEntries = 120

    private val mokuroChapterCache = mutableMapOf<Long, MokuroChapterData>()
    private val mokuroLoadMutex = mutableMapOf<Long, Mutex>()

    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    private val eventChannel = Channel<Event>()
    val eventFlow = eventChannel.receiveAsFlow()

    /**
     * The manga loaded in the reader. It can be null when instantiated for a short time.
     */
    val manga: Manga?
        get() = state.value.manga

    val currentChapter: Chapter?
        get() = state.value.currentChapter?.chapter?.toDomainChapter()

    /**
     * The chapter id of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterId = savedState.get<Long>("chapter_id") ?: -1L
        set(value) {
            savedState["chapter_id"] = value
            field = value
        }

    /**
     * The visible page index of the currently loaded chapter. Used to restore from process kill.
     */
    private var chapterPageIndex = savedState.get<Int>("page_index") ?: -1
        set(value) {
            savedState["page_index"] = value
            field = value
        }

    private var lastMangaStatsTime: Long = SystemClock.elapsedRealtime()
    private var currentMangaStatsPage: ReaderPage? = null
    private val consumedMangaStatsPages = mutableSetOf<Int>()

    var mangaStatsSessionCharacters: Int = 0
        private set
    var mangaStatsSessionTimeMs: Long = 0
        private set
    var mangaStatsTracking by mutableStateOf(true)
        private set
    var mangaStatsEstimate by mutableStateOf(MangaStatsEstimate())
        private set
    var showMangaStats by mutableStateOf(false)
        private set

    // KMK -->
    fun handleDownloadAction(chapter: Chapter, action: ChapterDownloadAction) {
        when (action) {
            ChapterDownloadAction.START -> downloadChapter(chapter)
            ChapterDownloadAction.START_NOW -> downloadManager.startDownloadNow(chapter.id)
            ChapterDownloadAction.CANCEL -> cancelDownload(chapter.id)
            ChapterDownloadAction.DELETE -> deleteChapter(chapter)
            ChapterDownloadAction.OCR -> runOcrForChapter(chapter)
            ChapterDownloadAction.DELETE_OCR -> deleteOcrForChapter(chapter)
        }
    }

    /**
     * @param chapter the chapter to download.
     */
    private fun downloadChapter(chapter: Chapter) {
        viewModelScope.launch {
            val manga = manga?.let {
                if (it.source == MERGED_SOURCE_ID) {
                    state.value.mergedManga?.get(chapter.mangaId) ?: return@launch
                } else {
                    it
                }
            } ?: return@launch
            downloadManager.downloadChapters(manga, listOf(chapter))
            downloadManager.startDownloads()
        }
    }

    private fun cancelDownload(chapterId: Long) {
        viewModelScope.launch {
            val activeDownload = downloadManager.getQueuedDownloadOrNull(chapterId) ?: return@launch
            downloadManager.cancelQueuedDownloads(listOf(activeDownload))
            // TODO: updateDownloadState(activeDownload.apply { status = Download.State.NOT_DOWNLOADED })
        }
    }

    private fun deleteChapter(chapter: Chapter) {
        viewModelScope.launchNonCancellable {
            try {
                val manga = if (manga?.source == MERGED_SOURCE_ID) {
                    state.value.mergedManga?.get(chapter.mangaId) ?: return@launchNonCancellable
                } else {
                    manga ?: return@launchNonCancellable
                }
                val source = sourceManager.get(manga.source) ?: return@launchNonCancellable
                downloadManager.deleteChapters(
                    listOf(chapter),
                    manga,
                    source,
                    ignoreCategoryExclusion = true,
                )
//                // KMK -->
//                if (source.isLocal()) {
//                    // TODO: Refresh chapters state for Local source
//                    fetchChaptersFromSource()
//                }
//                // KMK <--
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }
    // KMK <--

    /**
     * The chapter loader for the loaded manga. It'll be null until [manga] is set.
     */
    private var loader: ChapterLoader? = null

    /**
     * The time the chapter was started reading
     */
    private var chapterReadStartTime: Long? = null

    private var chapterToDownload: Download? = null

    private val unfilteredChapterList by lazy {
        val manga = manga!!
        runBlocking {
            // KMK -->
            if (manga.source == MERGED_SOURCE_ID) {
                getMergedChaptersByMangaId.await(manga.id, dedupe = false, applyFilter = false)
            } else {
                getChaptersByMangaId.await(manga.id, applyFilter = false)
            }
            // KMK <--
        }
    }

    /**
     * Chapter list for the active manga. It's retrieved lazily and should be accessed for the first
     * time in a background thread to avoid blocking the UI.
     */
    private val chapterList by lazy {
        val manga = manga!!
        // SY -->
        val (chapters, mangaMap) = runBlocking {
            if (manga.source == MERGED_SOURCE_ID) {
                getMergedChaptersByMangaId.await(manga.id, applyFilter = true) to
                    state.value.mergedManga
            } else {
                getChaptersByMangaId.await(manga.id, applyFilter = true) to null
            }
        }
        fun isChapterDownloaded(chapter: Chapter): Boolean {
            val chapterManga = mangaMap?.get(chapter.mangaId) ?: manga
            return downloadManager.isChapterDownloaded(
                chapterName = chapter.name,
                chapterScanlator = chapter.scanlator,
                chapterUrl = chapter.url,
                mangaTitle = chapterManga.ogTitle,
                sourceId = chapterManga.source,
            )
        }
        // SY <--

        val selectedChapter = chapters.find { it.id == chapterId }
            ?: error("Requested chapter of id $chapterId not found in chapter list")

        val chaptersForReader = when {
            (readerPreferences.skipRead().get() || readerPreferences.skipFiltered().get()) -> {
                val filteredChapters = chapters.filterNot {
                    when {
                        readerPreferences.skipRead().get() && it.read -> true
                        readerPreferences.skipFiltered().get() -> {
                            (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_READ && !it.read) ||
                                (manga.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD && it.read) ||
                                // SY -->
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_DOWNLOADED &&
                                        !isChapterDownloaded(it)
                                    ) ||
                                (
                                    manga.downloadedFilterRaw == Manga.CHAPTER_SHOW_NOT_DOWNLOADED &&
                                        isChapterDownloaded(it)
                                    ) ||
                                // SY <--
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED && !it.bookmark) ||
                                (manga.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_NOT_BOOKMARKED && it.bookmark)
                        }
                        else -> false
                    }
                }

                if (filteredChapters.any { it.id == chapterId }) {
                    filteredChapters
                } else {
                    filteredChapters + listOf(selectedChapter)
                }
            }
            else -> chapters
        }

        chaptersForReader
            .sortedWith(getChapterSort(manga, sortDescending = false))
            .run {
                if (readerPreferences.skipDupe().get()) {
                    removeDuplicates(selectedChapter)
                } else {
                    this
                }
            }
            .run {
                if (basePreferences.downloadedOnly().get()) {
                    filterDownloaded(manga, mangaMap)
                } else {
                    this
                }
            }
            .map { it.toDbChapter() }
            .map(::ReaderChapter)
    }

    val incognitoMode: Boolean by lazy { getIncognitoState.await(manga?.source) }
    private val downloadAheadAmount = downloadPreferences.autoDownloadWhileReading().get()

    init {
        // To save state
        state.map { it.viewerChapters?.currChapter }
            .distinctUntilChanged()
            .filterNotNull()
            // SY -->
            .drop(1) // allow the loader to set the first page and chapter id
            // SY <-
            .onEach { currentChapter ->
                if (chapterPageIndex >= 0) {
                    // Restore from SavedState
                    currentChapter.requestedPage = chapterPageIndex
                } else if (!currentChapter.chapter.read) {
                    currentChapter.requestedPage = currentChapter.chapter.last_page_read
                }
                chapterId = currentChapter.chapter.id!!
            }
            .launchIn(viewModelScope)

        // SY -->
        state.mapLatest { it.ehAutoscrollFreq }
            .distinctUntilChanged()
            .drop(1)
            .onEach { text ->
                val parsed = text.toDoubleOrNull()

                if (parsed == null || parsed <= 0 || parsed > 9999) {
                    readerPreferences.autoscrollInterval().set(-1f)
                    mutableState.update { it.copy(isAutoScrollEnabled = false) }
                } else {
                    readerPreferences.autoscrollInterval().set(parsed.toFloat())
                    mutableState.update { it.copy(isAutoScrollEnabled = true) }
                }
            }
            .launchIn(viewModelScope)

        readerPreferences.ocrOverlayEnabled().changes()
            .onEach { enabled ->
                if (!enabled) {
                    cancelOcrScan()
                } else {
                    getSelectedReaderPage()?.let { scanOcrPages(it) }
                }
            }
            .launchIn(viewModelScope)
        // SY <--
    }

    override fun onCleared() {
        val currentChapters = state.value.viewerChapters
        if (currentChapters != null) {
            currentChapters.unref()
            chapterToDownload?.let {
                downloadManager.addDownloadsToStartOfQueue(listOf(it))
            }
        }
        mokuroChapterCache.clear()
        mokuroLoadMutex.clear()
        Injekt.get<PanelDetectionRepository>().cleanup()
    }

    /**
     * Called when the user pressed the back button and is going to leave the reader. Used to
     * trigger deletion of the downloaded chapters.
     */
    fun onActivityFinish() {
        trackMangaStats(null)
        deletePendingChapters()
    }

    /**
     * Whether this presenter is initialized yet.
     */
    fun needsInit(): Boolean {
        return manga == null
    }

    /**
     * Initializes this presenter with the given [mangaId] and [initialChapterId]. This method will
     * fetch the manga from the database and initialize the initial chapter.
     */
    suspend fun init(mangaId: Long, initialChapterId: Long /* SY --> */, page: Int?/* SY <-- */): Result<Boolean> {
        if (!needsInit()) return Result.success(true)
        return withIOContext {
            try {
                val manga = getManga.await(mangaId)
                if (manga != null) {
                    // SY -->
                    sourceManager.isInitialized.first { it }
                    val source = sourceManager.getOrStub(manga.source)
                    val metadataSource = source.getMainSource<MetadataSource<*, *>>()
                    val metadata = if (metadataSource != null) {
                        getFlatMetadataById.await(mangaId)?.raise(metadataSource.metaClass)
                    } else {
                        null
                    }
                    val mergedReferences = if (source is MergedSource) {
                        runBlocking {
                            getMergedReferencesById.await(manga.id)
                        }
                    } else {
                        emptyList()
                    }
                    val mergedManga = if (source is MergedSource) {
                        runBlocking {
                            getMergedMangaById.await(manga.id)
                        }.associateBy { it.id }
                    } else {
                        null
                    }
                    val relativeTime = uiPreferences.relativeTime().get()
                    val autoScrollFreq = readerPreferences.autoscrollInterval().get()
                    // SY <--
                    mutableState.update {
                        it.copy(
                            manga = manga,
                            ocrSource = readerPreferences.ocrSource(manga.id).get(),
                            // SY -->
                            meta = metadata,
                            mergedManga = mergedManga,
                            dateRelativeTime = relativeTime,
                            ehAutoscrollFreq = if (autoScrollFreq == -1f) {
                                ""
                            } else {
                                autoScrollFreq.toString()
                            },
                            isAutoScrollEnabled = autoScrollFreq != -1f,
                            // SY <--
                        )
                    }
                    if (chapterId == -1L) chapterId = initialChapterId

                    val context = Injekt.get<Application>()
                    // val source = sourceManager.getOrStub(manga.source)
                    loader = ChapterLoader(
                        context = context,
                        downloadManager = downloadManager,
                        downloadProvider = downloadProvider,
                        manga = manga,
                        source = source,
                        // SY -->
                        sourceManager = sourceManager,
                        readerPrefs = readerPreferences,
                        mergedReferences = mergedReferences,
                        mergedManga = mergedManga,
                        // SY <--
                    )

                    loadChapter(
                        loader!!,
                        chapterList.first { chapterId == it.chapter.id },
                        // SY -->
                        page,
                        // SY <--
                    )

                    val chapter = chapterList.first { chapterId == it.chapter.id }
                    chapter.chapter.toDomainChapter()?.let { domainChapter ->
                        val mokuroAvailable = checkMokuroFileExists(domainChapter, source)
                        mutableState.update { it.copy(mokuroAvailable = mokuroAvailable) }
                    }

                    Result.success(true)
                } else {
                    // Unlikely but okay
                    Result.success(false)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                Result.failure(e)
            }
        }
    }

    // SY -->
    fun getChapters(): List<ReaderChapterItem> {
        // KMK -->
        val manga = manga ?: return emptyList()
        val mangaList = state.value.mergedManga?.takeIf { it.isNotEmpty() } ?: mapOf(manga.id to manga)
        // KMK <--

        val currentChapter = getCurrentChapter()

        return chapterList.map {
            ReaderChapterItem(
                chapter = it.chapter.toDomainChapter()!!,
                // KMK -->
                manga = mangaList[it.chapter.manga_id] ?: manga,
                // KMK <--
                isCurrent = it.chapter.id == currentChapter?.chapter?.id,
                dateFormat = UiPreferences.dateFormat(uiPreferences.dateFormat().get()),
            )
        }
    }
    // SY <--

    /**
     * Loads the given [chapter] with this [loader] and updates the currently active chapters.
     * Callers must handle errors.
     */
    private suspend fun loadChapter(
        loader: ChapterLoader,
        chapter: ReaderChapter,
        // SY -->
        page: Int? = null,
        // SY <--
    ): ViewerChapters {
        loader.loadChapter(chapter /* SY --> */, page/* SY <-- */)

        val chapterPos = chapterList.indexOf(chapter)
        val newChapters = ViewerChapters(
            chapter,
            chapterList.getOrNull(chapterPos - 1),
            chapterList.getOrNull(chapterPos + 1),
        )

        withUIContext {
            mutableState.update {
                // Add new references first to avoid unnecessary recycling
                newChapters.ref()
                it.viewerChapters?.unref()

                chapterToDownload = cancelQueuedDownloads(newChapters.currChapter)
                it.copy(
                    viewerChapters = newChapters,
                    bookmarked = newChapters.currChapter.chapter.bookmark,
                )
            }
        }
        return newChapters
    }

    /**
     * Called when the user changed to the given [chapter] when changing pages from the viewer.
     * It's used only to set this chapter as active.
     */
    private fun loadNewChapter(chapter: ReaderChapter) {
        val loader = loader ?: return

        viewModelScope.launchIO {
            logcat { "Loading ${chapter.chapter.url}" }

            updateHistory()
            restartReadTimer()
            trackMangaStats(null)

            try {
                loadChapter(loader, chapter)

                val manga = manga ?: return@launchIO
                val source = sourceManager.getOrStub(manga.source)
                val domainChapter = chapter.chapter.toDomainChapter() ?: return@launchIO
                val mokuroAvailable = checkMokuroFileExists(domainChapter, source)
                mutableState.update { it.copy(mokuroAvailable = mokuroAvailable) }
            } catch (e: Throwable) {
                if (e is CancellationException) {
                    throw e
                }
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    fun loadNewChapterFromDialog(chapter: Chapter) {
        viewModelScope.launchIO {
            val newChapter = chapterList.firstOrNull { it.chapter.id == chapter.id } ?: return@launchIO
            loadAdjacent(newChapter)
        }
    }

    /**
     * Called when the user is going to load the prev/next chapter through the toolbar buttons.
     */
    private suspend fun loadAdjacent(chapter: ReaderChapter) {
        val loader = loader ?: return

        logcat { "Loading adjacent ${chapter.chapter.url}" }

        mutableState.update { it.copy(isLoadingAdjacentChapter = true) }
        try {
            withIOContext {
                loadChapter(loader, chapter)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            logcat(LogPriority.ERROR, e)
        } finally {
            mutableState.update { it.copy(isLoadingAdjacentChapter = false) }
        }
    }

    /**
     * Called when the viewers decide it's a good time to preload a [chapter] and improve the UX so
     * that the user doesn't have to wait too long to continue reading.
     */
    suspend fun preload(chapter: ReaderChapter) {
        if (chapter.state is ReaderChapter.State.Loaded || chapter.state == ReaderChapter.State.Loading) {
            return
        }

        /*
         * This code is likely deprecated since once `chapter.pageLoader` is initialized with [HttpPageLoader],
         * it would set `chapter.state` to `Loading` or `Loaded` and return early already.
         */
        if (chapter.pageLoader?.isLocal == false) {
            val manga = state.value.mergedManga?.get(chapter.chapter.manga_id) ?: manga ?: return
            val dbChapter = chapter.chapter
            val isDownloaded = downloadManager.isChapterDownloaded(
                dbChapter.name,
                dbChapter.scanlator,
                dbChapter.url,
                // SY -->
                manga.ogTitle,
                // SY <--
                manga.source,
                skipCache = true,
            )
            if (isDownloaded) {
                chapter.state = ReaderChapter.State.Wait
            }
        }

        if (chapter.state != ReaderChapter.State.Wait && chapter.state !is ReaderChapter.State.Error) {
            return
        }

        val loader = loader ?: return
        try {
            logcat { "Preloading ${chapter.chapter.url}" }
            loader.loadChapter(chapter)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            return
        }
        eventChannel.trySend(Event.ReloadViewerChapters)
    }

    fun onViewerLoaded(viewer: Viewer?) {
        mutableState.update {
            it.copy(viewer = viewer)
        }
        // Chimahon: Pre-initialize OCR resources if enabled
        if (viewer != null && isOcrEnabled()) {
            eventChannel.trySend(Event.InitializeOcrResources)
        }
    }

    /**
     * Called every time a page changes on the reader. Used to mark the flag of chapters being
     * read, update tracking services, enqueue downloaded chapter deletion, and updating the active chapter if this
     * [page]'s chapter is different from the currently active.
     */
    fun onPageSelected(page: ReaderPage, currentPageText: String /* SY --> */, hasExtraPage: Boolean /* SY <-- */) {
        // InsertPage doesn't change page progress
        if (page is InsertPage) {
            return
        }

        // SY -->
        mutableState.update { it.copy(currentPageText = currentPageText) }
        // SY <--

        val selectedChapter = page.chapter
        val pages = selectedChapter.pages ?: return

        // Save last page read and mark as read if needed
        viewModelScope.launchNonCancellable {
            updateChapterProgress(selectedChapter, page/* SY --> */, hasExtraPage/* SY <-- */)
        }

        trackMangaStats(page)

        if (selectedChapter != getCurrentChapter()) {
            logcat { "Setting ${selectedChapter.chapter.url} as active" }
            loadNewChapter(selectedChapter)
        }

        val inDownloadRange = page.number.toDouble() / pages.size > 0.25
        if (inDownloadRange) {
            downloadNextChapters()
        }

        if (isOcrEnabled()) scanOcrPages(page)

        eventChannel.trySend(Event.PageChanged)
    }

    private fun downloadNextChapters() {
        if (downloadAheadAmount == 0) return
        val manga = manga ?: return

        // Only download ahead if current + next chapter is already downloaded too to avoid jank
        if (getCurrentChapter()?.pageLoader !is DownloadPageLoader) return
        val nextChapter = state.value.viewerChapters?.nextChapter?.chapter ?: return

        // KMK -->
        val mangas = state.value.mergedManga ?: mapOf(manga.id to manga)
        val nextChapterManga = mangas[nextChapter.manga_id] ?: return
        // KMK <--

        viewModelScope.launchIO {
            val isNextChapterDownloaded = downloadManager.isChapterDownloaded(
                nextChapter.name,
                nextChapter.scanlator,
                nextChapter.url,
                // KMK -->
                nextChapterManga.ogTitle,
                nextChapterManga.source,
                // KMK <--
            )
            if (!isNextChapterDownloaded) return@launchIO

            val chaptersToDownload = getNextChapters.await(manga.id, nextChapter.id!!).run {
                if (readerPreferences.skipDupe().get()) {
                    removeDuplicates(nextChapter.toDomainChapter()!!)
                } else {
                    this
                }
            }.take(downloadAheadAmount)

            // KMK -->
            chaptersToDownload.groupBy { it.mangaId }.forEach { (mangaId, chapters) ->
                val chapterManga = mangas[mangaId] ?: return@forEach
                downloadManager.downloadChapters(
                    chapterManga,
                    chapters,
                )
            }
            // KMK <--
        }
    }

    /**
     * Removes [currentChapter] from download queue
     * if setting is enabled and [currentChapter] is queued for download
     */
    private fun cancelQueuedDownloads(currentChapter: ReaderChapter): Download? {
        return downloadManager.getQueuedDownloadOrNull(currentChapter.chapter.id!!)?.also {
            downloadManager.cancelQueuedDownloads(listOf(it))
        }
    }

    /**
     * Determines if deleting option is enabled and nth to last chapter actually exists.
     * If both conditions are satisfied enqueues chapter for delete.
     *
     * This deletes chapters from reading list (filtered, unduplicated if any set).
     *
     * @param currentChapter current chapter, which is going to be marked as read.
     */
    private fun deleteChapterIfNeeded(currentChapter: ReaderChapter) {
        val removeAfterReadSlots = downloadPreferences.removeAfterReadSlots().get()
        if (removeAfterReadSlots == -1) return

        // Determine which chapter should be deleted and enqueue
        val currentChapterPosition = chapterList.indexOf(currentChapter)
        val chapterToDelete = chapterList.getOrNull(currentChapterPosition - removeAfterReadSlots)

        // If chapter is completely read, no need to download it
        chapterToDownload = null

        if (chapterToDelete != null) {
            enqueueDeleteReadChapters(chapterToDelete)
        }
    }

    // KMK -->
    /**
     * Deletes duplicate chapters when `removeAfterReadSlots` = "Last read chapter" (0).
     *
     * Ignore the case where `removeAfterReadSlots` > 0 while `skipDupe` = true as we don't know
     * where the chapters to be deleted are in the filtered [chapterList].
     *
     * For the case where `skipDupe` = false, chapters at should be deleted normally by [deleteChapterIfNeeded]
     * based on the `removeAfterReadSlots` offset while the user is reading sequentially.
     */
    private fun deleteDupChapterIfNeeded(chapterToDelete: ReaderChapter) {
        val removeAfterReadSlots = downloadPreferences.removeAfterReadSlots().get()
        if (removeAfterReadSlots != 0) return
        enqueueDeleteReadChapters(chapterToDelete)
    }
    // KMK <--

    /**
     * Saves the chapter progress (last read page and whether it's read)
     * if incognito mode isn't on.
     */
    private suspend fun updateChapterProgress(
        readerChapter: ReaderChapter,
        page: Page,
        // SY -->
        hasExtraPage: Boolean,
        // SY <--
    ) {
        val pageIndex = page.index
        val syncTriggerOpt = syncPreferences.getSyncTriggerOptions()
        val isSyncEnabled = syncPreferences.isSyncEnabled()

        mutableState.update {
            it.copy(currentPage = pageIndex + 1)
        }
        readerChapter.requestedPage = pageIndex
        chapterPageIndex = pageIndex

        if (!incognitoMode && page.status !is Page.State.Error) {
            readerChapter.chapter.last_page_read = pageIndex

            if (readerChapter.pages?.lastIndex == pageIndex ||
                // SY -->
                (hasExtraPage && readerChapter.pages?.lastIndex?.minus(1) == page.index)
                // SY <--
            ) {
                updateChapterProgressOnComplete(readerChapter)

                // SY -->
                // Check if syncing is enabled for chapter read:
                if (isSyncEnabled && syncTriggerOpt.syncOnChapterRead) {
                    SyncDataJob.startNow(Injekt.get<Application>())
                }
                // SY <--
            }

            updateChapter.await(
                ChapterUpdate(
                    id = readerChapter.chapter.id!!,
                    read = readerChapter.chapter.read,
                    lastPageRead = readerChapter.chapter.last_page_read.toLong(),
                ),
            )

            // SY -->
            // Check if syncing is enabled for chapter open:
            if (isSyncEnabled && syncTriggerOpt.syncOnChapterOpen && readerChapter.chapter.last_page_read == 0) {
                SyncDataJob.startNow(Injekt.get<Application>())
            }
            // SY <--
        }
    }

    private suspend fun updateChapterProgressOnComplete(readerChapter: ReaderChapter) {
        readerChapter.chapter.read = true
        // SY -->
        if (manga?.isEhBasedManga() == true) {
            viewModelScope.launchNonCancellable {
                val chapterUpdates = unfilteredChapterList
                    .filter { it.sourceOrder > readerChapter.chapter.source_order }
                    .map { chapter ->
                        ChapterUpdate(id = chapter.id, read = true)
                    }
                updateChapter.awaitAll(chapterUpdates)
            }
        }
        // SY <--

        updateTrackChapterRead(readerChapter)
        deleteChapterIfNeeded(readerChapter)

        val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead().get()
            .contains(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_EXISTING)
        if (!markDuplicateAsRead) return

        val duplicateUnreadChapters = unfilteredChapterList
            .mapNotNull { chapter ->
                if (
                    !chapter.read &&
                    chapter.isRecognizedNumber &&
                    chapter.chapterNumber.toFloat() == readerChapter.chapter.chapter_number
                ) {
                    ChapterUpdate(id = chapter.id, read = true)
                        // KMK -->
                        .also { deleteDupChapterIfNeeded(ReaderChapter(chapter.copy(read = true))) }
                    // KMK <--
                } else {
                    null
                }
            }
        updateChapter.awaitAll(duplicateUnreadChapters)
    }

    fun restartReadTimer() {
        chapterReadStartTime = Instant.now().toEpochMilli()
    }

    /**
     * Saves the chapter last read history if incognito mode isn't on.
     */
    suspend fun updateHistory() {
        getCurrentChapter()?.let { readerChapter ->
            if (incognitoMode) return@let

            val chapterId = readerChapter.chapter.id!!
            val endTime = Date()
            val sessionReadDuration = chapterReadStartTime?.let { endTime.time - it } ?: 0

            if (sessionReadDuration > 0) {
                upsertHistory.await(HistoryUpdate(chapterId, endTime, sessionReadDuration))
                historyRepository.insertSession(ReadingSession(0, chapterId, endTime, sessionReadDuration))
            }
            chapterReadStartTime = null
        }
    }

    /**
     * Called from the activity to load and set the next chapter as active.
     */
    suspend fun loadNextChapter() {
        val nextChapter = state.value.viewerChapters?.nextChapter ?: return
        loadAdjacent(nextChapter)
    }

    /**
     * Called from the activity to load and set the previous chapter as active.
     */
    suspend fun loadPreviousChapter() {
        val prevChapter = state.value.viewerChapters?.prevChapter ?: return
        loadAdjacent(prevChapter)
    }

    /**
     * Returns the currently active chapter.
     */
    private fun getCurrentChapter(): ReaderChapter? {
        return state.value.currentChapter
    }

    fun getSource() = manga?.source?.let { sourceManager.getOrStub(it) } as? HttpSource

    fun getChapterUrl(): String? {
        val sChapter = getCurrentChapter()?.chapter ?: return null
        val source = if (manga?.source == MERGED_SOURCE_ID) {
            state.value.mergedManga?.get(sChapter.manga_id)?.source?.let { sourceId ->
                sourceManager.getOrStub(sourceId) as? HttpSource
            }
        } else {
            getSource()
        } ?: return null

        return try {
            source.getChapterUrl(sChapter)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    /**
     * Bookmarks the currently active chapter.
     */
    fun toggleChapterBookmark() {
        val chapter = getCurrentChapter()?.chapter ?: return
        val bookmarked = !chapter.bookmark
        chapter.bookmark = bookmarked

        viewModelScope.launchNonCancellable {
            updateChapter.await(
                ChapterUpdate(
                    id = chapter.id!!,
                    bookmark = bookmarked,
                ),
            )
        }

        mutableState.update {
            it.copy(
                bookmarked = bookmarked,
            )
        }
    }

    // SY -->
    fun toggleBookmark(chapterId: Long, bookmarked: Boolean) {
        val chapter = chapterList.find { it.chapter.id == chapterId }?.chapter ?: return
        chapter.bookmark = bookmarked
        viewModelScope.launchNonCancellable {
            updateChapter.await(
                ChapterUpdate(
                    id = chapterId,
                    bookmark = bookmarked,
                ),
            )
        }
    }
    // SY <--

    /**
     * Returns the viewer position used by this manga or the default one.
     */
    fun getMangaReadingMode(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultReadingMode().get()
        val manga = manga ?: return default
        val readingMode = ReadingMode.fromPreference(manga.readingMode.toInt())
        // SY -->
        return when {
            resolveDefault && readingMode == ReadingMode.DEFAULT && readerPreferences.useAutoWebtoon().get() -> {
                manga.defaultReaderType(manga.mangaType(sourceName = sourceManager.get(manga.source)?.name))
                    ?: default
            }
            resolveDefault && readingMode == ReadingMode.DEFAULT -> default
            else -> manga.readingMode.toInt()
        }
        // SY <--
    }

    /**
     * Updates the viewer position for the open manga.
     */
    fun setMangaReadingMode(readingMode: ReadingMode) {
        val manga = manga ?: return
        runBlocking(Dispatchers.IO) {
            setMangaViewerFlags.awaitSetReadingMode(manga.id, readingMode.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    /**
     * Returns the orientation type used by this manga or the default one.
     */
    fun getMangaOrientation(resolveDefault: Boolean = true): Int {
        val default = readerPreferences.defaultOrientationType().get()
        val orientation = ReaderOrientation.fromPreference(manga?.readerOrientation?.toInt())
        return when {
            resolveDefault && orientation == ReaderOrientation.DEFAULT -> default
            else -> manga?.readerOrientation?.toInt() ?: default
        }
    }

    /**
     * Updates the orientation type for the open manga.
     */
    fun setMangaOrientationType(orientation: ReaderOrientation) {
        val manga = manga ?: return
        viewModelScope.launchIO {
            setMangaViewerFlags.awaitSetOrientation(manga.id, orientation.flagValue.toLong())
            val currChapters = state.value.viewerChapters
            if (currChapters != null) {
                // Save current page
                val currChapter = currChapters.currChapter
                currChapter.requestedPage = currChapter.chapter.last_page_read

                mutableState.update {
                    it.copy(
                        manga = getManga.await(manga.id),
                        viewerChapters = currChapters,
                    )
                }
                eventChannel.send(Event.SetOrientation(getMangaOrientation()))
                eventChannel.send(Event.ReloadViewerChapters)
            }
        }
    }

    // SY -->
    fun toggleCropBorders(): Boolean {
        val readingMode = getMangaReadingMode()
        val isPagerType = ReadingMode.isPagerType(readingMode)
        val isWebtoon = ReadingMode.WEBTOON.flagValue == readingMode
        return if (isPagerType) {
            readerPreferences.cropBorders().toggle()
        } else if (isWebtoon) {
            readerPreferences.cropBordersWebtoon().toggle()
        } else {
            readerPreferences.cropBordersContinuousVertical().toggle()
        }
    }
    // SY <--

    /**
     * Generate a filename for the given [manga] and [page]
     */
    private fun generateFilename(
        manga: Manga,
        page: ReaderPage,
    ): String {
        val chapter = page.chapter.chapter
        val filenameSuffix = " - ${page.number}"
        return DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}",
            MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
        ) + filenameSuffix
    }

    fun showMenus(visible: Boolean) {
        mutableState.update { it.copy(menuVisible = visible) }
    }

    // SY -->
    fun showEhUtils(visible: Boolean) {
        mutableState.update { it.copy(ehUtilsVisible = visible) }
    }

    fun setIndexChapterToShift(index: Long?) {
        mutableState.update { it.copy(indexChapterToShift = index) }
    }

    fun setIndexPageToShift(index: Int?) {
        mutableState.update { it.copy(indexPageToShift = index) }
    }

    fun openChapterListDialog() {
        mutableState.update { it.copy(dialog = Dialog.ChapterList) }
    }

    fun openMangaStatsSheet() {
        showMangaStats = true
        refreshMangaStatsEstimate()
    }

    fun closeMangaStatsSheet() {
        showMangaStats = false
    }

    fun toggleMangaStatsTracking() {
        mangaStatsTracking = !mangaStatsTracking
    }

    fun setDoublePages(doublePages: Boolean) {
        mutableState.update { it.copy(doublePages = doublePages) }
    }

    fun openAutoScrollHelpDialog() {
        mutableState.update { it.copy(dialog = Dialog.AutoScrollHelp) }
    }

    fun openBoostPageHelp() {
        mutableState.update { it.copy(dialog = Dialog.BoostPageHelp) }
    }

    fun openRetryAllHelp() {
        mutableState.update { it.copy(dialog = Dialog.RetryAllHelp) }
    }

    fun toggleAutoScroll(enabled: Boolean) {
        mutableState.update { it.copy(autoScroll = enabled) }
    }

    fun setAutoScrollFrequency(frequency: String) {
        mutableState.update { it.copy(ehAutoscrollFreq = frequency) }
    }
    // SY <--

    // Chimahon: OCR methods
    fun isOcrEnabled(): Boolean = (readerPreferences.ocrOverlayEnabled().get() || state.value.regionOcrEnabled) && isOcrAllowedForCurrentManga()

    private fun isRegionOnlyOcr(): Boolean = state.value.regionOcrEnabled && !readerPreferences.ocrOverlayEnabled().get()

    fun isOcrAllowedForCurrentManga(): Boolean {
        val manga = manga ?: return true
        val source = sourceManager.getOrStub(manga.source)
        if (source.isLocal()) return true

        val profile = dictionaryPreferences.profileResolver.resolve(
            mangaId = manga.id,
            sourceId = manga.source,
            sourceLang = source.lang,
        )

        return isOcrAllowedForLanguage(source.lang, profile.languageCode)
    }

    /**
     * Effective scan resolution for the resolved dictionary profile. Used by
     * the OCR viewer to decide whole-word vs character tap expansion.
     */
    fun getOcrScanResolution(): String {
        val profile = resolveOcrProfile()
        return effectiveScanResolution(profile.scanResolution, profile.languageCode)
    }

    private fun resolveOcrProfile(): chimahon.anki.AnkiProfile {
        val currentManga = manga ?: return dictionaryPreferences.profileStore.getActiveProfile()
        val source = sourceManager.getOrStub(currentManga.source)
        return dictionaryPreferences.profileResolver.resolve(
            mangaId = currentManga.id,
            sourceId = currentManga.source,
            sourceLang = source.lang,
        )
    }

    fun isOcrOutlineVisible(): Boolean = readerPreferences.ocrOutlineVisible().get()

    fun getOcrBoxScaleX(): Float = dictionaryPreferences.ocrBoxScaleX().get()

    fun getOcrBoxScaleY(): Float = dictionaryPreferences.ocrBoxScaleY().get()

    fun getOcrBoxOpacity(): Float = dictionaryPreferences.ocrBoxOpacity().get()

    fun getActiveOcrTextOpacity(): Float = dictionaryPreferences.activeOcrTextOpacity().get()

    fun getActiveOcrBgOpacity(): Float = dictionaryPreferences.activeOcrBgOpacity().get()

    fun isMokuroAvailable(): Boolean = state.value.mokuroAvailable

    fun setOcrSource(source: ReaderOcrSource): Boolean {
        val manga = manga ?: return false
        if (state.value.ocrSource == source) return false

        readerPreferences.ocrSource(manga.id).set(source)
        cancelOcrScan()
        ocrScannedChapterIds.clear()
        mutableState.update { it.copy(ocrSource = source) }
        // Re-run OCR with the newly selected source right away instead of waiting for the
        // next page change to restart the chapter scan.
        if (isOcrEnabled()) {
            getSelectedReaderPage()?.let { scanOcrPages(it) }
        }
        return true
    }

    fun toggleOcrEnabled(): Boolean {
        val pref = readerPreferences.ocrOverlayEnabled()
        val enabled = !isOcrEnabled()
        mutableState.update { it.copy(regionOcrEnabled = false) }
        if (enabled && !isOcrAllowedForCurrentManga()) {
            cancelOcrScan()
            pref.set(false)
            return false
        }
        pref.set(enabled)
        if (!enabled) {
            cancelOcrScan()
        }
        return enabled
    }

    fun runOcrForChapter(chapter: Chapter) {
        val manga = this.manga ?: return
        viewModelScope.launch {
            downloadManager.downloadChaptersWithOcr(manga, listOf(chapter))
        }
    }

    fun deleteOcrForChapter(chapter: Chapter) {
        val manga = this.manga ?: return
        viewModelScope.launchIO {
            ocrManager.deleteOcrForChapter(manga, chapter)
        }
    }

    fun showLoadingDialog() {
        mutableState.update { it.copy(dialog = Dialog.Loading) }
    }

    fun openReadingModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.ReadingModeSelect) }
    }

    fun openOrientationModeSelectDialog() {
        mutableState.update { it.copy(dialog = Dialog.OrientationModeSelect) }
    }

    fun openPageDialog(page: ReaderPage/* SY --> */, extraPage: ReaderPage? = null/* SY <-- */) {
        mutableState.update { it.copy(dialog = Dialog.PageActions(page, extraPage)) }
    }

    fun openSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.Settings) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun setBrightnessOverlayValue(value: Int) {
        mutableState.update { it.copy(brightnessOverlayValue = value) }
    }

    /**
     * Saves the image of the selected page on the pictures directory and notifies the UI of the result.
     * There's also a notification to allow sharing the image somewhere else or deleting it.
     */
    // KMK -->
    // Get current page bitmap for crop/full screenshot (same pattern as saveImage)
    fun getCurrentPageBitmap(sourcePage: ReaderPage? = null): Bitmap? {
        val viewer = state.value.viewer ?: return null

        val readerPage = sourcePage ?: when (viewer) {
            is PagerViewer -> viewer.currentPage as? ReaderPage
            is WebtoonViewer -> viewer.currentPage as? ReaderPage
            else -> null
        }

        if (readerPage == null || readerPage.status != Page.State.Ready) return null

        return try {
            readerPage.stream?.invoke()?.use { inputStream ->
                val bytes = inputStream.readBytes()
                if (bytes.isNotEmpty()) {
                    OcrBitmapDecoder.decode(bytes)
                } else {
                    null
                }
            } ?: null
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to decode page image" }
            null
        }
    }
    // KMK <--

    fun saveImage(useExtraPage: Boolean) {
        // SY -->
        val page = if (useExtraPage) {
            (state.value.dialog as? Dialog.PageActions)?.extraPage
        } else {
            (state.value.dialog as? Dialog.PageActions)?.page
        }
        // SY <--
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        val filename = generateFilename(manga, page)

        // Pictures directory.
        val relativePath = if (readerPreferences.folderPerManga().get()) {
            DiskUtil.buildValidFilename(manga.title)
        } else {
            ""
        }

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Pictures.create(relativePath),
                    ),
                )
                withUIContext {
                    notifier.onComplete(uri)
                    eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
                }
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    // SY -->
    fun saveImages() {
        val (firstPage, secondPage) = (state.value.dialog as? Dialog.PageActions ?: return)
        val viewer = state.value.viewer as? PagerViewer ?: return
        val isLTR = (viewer !is R2LPagerViewer) xor (viewer.config.invertDoublePages)
        val bg = viewer.config.pageCanvasColor

        if (firstPage.status != Page.State.Ready) return
        if (secondPage?.status != Page.State.Ready) return

        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val notifier = SaveImageNotifier(context)
        notifier.onClear()

        // Copy file in background.
        viewModelScope.launchNonCancellable {
            try {
                val uri = saveImages(
                    page1 = firstPage,
                    page2 = secondPage,
                    isLTR = isLTR,
                    bg = bg,
                    location = Location.Pictures.create(DiskUtil.buildValidFilename(manga.title)),
                    manga = manga,
                )
                eventChannel.send(Event.SavedImage(SaveImageResult.Success(uri)))
            } catch (e: Throwable) {
                notifier.onError(e.message)
                eventChannel.send(Event.SavedImage(SaveImageResult.Error(e)))
            }
        }
    }

    private fun saveImages(
        page1: ReaderPage,
        page2: ReaderPage,
        isLTR: Boolean,
        @ColorInt bg: Int,
        location: Location,
        manga: Manga,
    ): Uri {
        val stream1 = page1.stream!!
        ImageUtil.findImageType(stream1) ?: throw Exception("Not an image")
        val stream2 = page2.stream!!
        ImageUtil.findImageType(stream2) ?: throw Exception("Not an image")
        val imageBitmap = ImageDecoder.newInstance(stream1())?.decode()!!
        val imageBitmap2 = ImageDecoder.newInstance(stream2())?.decode()!!

        val chapter = page1.chapter.chapter

        // Build destination file.
        val filenameSuffix = " - ${page1.number}-${page2.number}.jpg"
        val filename = DiskUtil.buildValidFilename(
            "${manga.title} - ${chapter.name}",
            MAX_FILE_NAME_BYTES - filenameSuffix.byteSize(),
        ) + filenameSuffix

        return imageSaver.save(
            image = Image.Page(
                inputStream = { ImageUtil.mergeBitmaps(imageBitmap, imageBitmap2, isLTR, 0, bg).inputStream() },
                name = filename,
                location = location,
            ),
        )
    }
    // SY <--

    /**
     * Shares the image of the selected page and notifies the UI with the path of the file to share.
     * The image must be first copied to the internal partition because there are many possible
     * formats it can come from, like a zipped chapter, in which case it's not possible to directly
     * get a path to the file and it has to be decompressed somewhere first. Only the last shared
     * image will be kept so it won't be taking lots of internal disk space.
     */
    fun shareImage(
        copyToClipboard: Boolean,
        // SY -->
        useExtraPage: Boolean,
        // SY <--
    ) {
        // SY -->
        val page = if (useExtraPage) {
            (state.value.dialog as? Dialog.PageActions)?.extraPage
        } else {
            (state.value.dialog as? Dialog.PageActions)?.page
        }
        // SY <--
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val destDir = context.cacheImageDir

        val filename = generateFilename(manga, page)

        try {
            viewModelScope.launchNonCancellable {
                destDir.deleteRecursively()
                val uri = imageSaver.save(
                    image = Image.Page(
                        inputStream = page.stream!!,
                        name = filename,
                        location = Location.Cache,
                    ),
                )
                eventChannel.send(if (copyToClipboard) Event.CopyImage(uri) else Event.ShareImage(uri, page))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }

    // SY -->
    fun shareImages(copyToClipboard: Boolean) {
        val (firstPage, secondPage) = (state.value.dialog as? Dialog.PageActions ?: return)
        val viewer = state.value.viewer as? PagerViewer ?: return
        val isLTR = (viewer !is R2LPagerViewer) xor (viewer.config.invertDoublePages)
        val bg = viewer.config.pageCanvasColor

        if (firstPage.status != Page.State.Ready) return
        if (secondPage?.status != Page.State.Ready) return
        val manga = manga ?: return

        val context = Injekt.get<Application>()
        val destDir = context.cacheImageDir

        try {
            viewModelScope.launchNonCancellable {
                destDir.deleteRecursively()
                val uri = saveImages(
                    page1 = firstPage,
                    page2 = secondPage,
                    isLTR = isLTR,
                    bg = bg,
                    location = Location.Cache,
                    manga = manga,
                )
                eventChannel.send(if (copyToClipboard) Event.CopyImage(uri) else Event.ShareImage(uri, firstPage, secondPage))
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
        }
    }
    // SY <--

    /**
     * Sets the image of the selected page as cover and notifies the UI of the result.
     */
    fun setAsCover(useExtraPage: Boolean) {
        // SY -->
        val page = if (useExtraPage) {
            (state.value.dialog as? Dialog.PageActions)?.extraPage
        } else {
            (state.value.dialog as? Dialog.PageActions)?.page
        }
        // SY <--
        if (page?.status != Page.State.Ready) return
        val manga = manga ?: return
        val stream = page.stream ?: return

        viewModelScope.launchNonCancellable {
            val result = try {
                manga.editCover(Injekt.get(), stream())
                if (manga.isLocal() || manga.favorite) {
                    SetAsCoverResult.Success
                } else {
                    SetAsCoverResult.AddToLibraryFirst
                }
            } catch (_: Exception) {
                SetAsCoverResult.Error
            }
            eventChannel.send(Event.SetCoverResult(result))
        }
    }

    enum class SetAsCoverResult {
        Success,
        AddToLibraryFirst,
        Error,
    }

    sealed interface SaveImageResult {
        class Success(val uri: Uri) : SaveImageResult
        class Error(val error: Throwable) : SaveImageResult
    }

    /**
     * Starts the service that updates the last chapter read in sync services. This operation
     * will run in a background thread and errors are ignored.
     */
    private fun updateTrackChapterRead(readerChapter: ReaderChapter) {
        if (incognitoMode) return
        if (!trackPreferences.autoUpdateTrack().get()) return

        val manga = manga ?: return
        val context = Injekt.get<Application>()

        viewModelScope.launchNonCancellable {
            trackChapter.await(context, manga.id, readerChapter.chapter.chapter_number.toDouble())
        }
    }

    /**
     * Enqueues this [chapter] to be deleted when [deletePendingChapters] is called. The download
     * manager handles persisting it across process deaths.
     */
    private fun enqueueDeleteReadChapters(chapter: ReaderChapter) {
        if (!chapter.chapter.read) return
        val mergedManga = state.value.mergedManga
        // SY -->
        val manga = if (mergedManga.isNullOrEmpty()) {
            manga
        } else {
            mergedManga[chapter.chapter.manga_id]
        } ?: return
        // SY <--

        viewModelScope.launchNonCancellable {
            downloadManager.enqueueChaptersToDelete(listOf(chapter.chapter.toDomainChapter()!!), manga)
        }
    }

    /**
     * Deletes all the pending chapters. This operation will run in a background thread and errors
     * are ignored.
     */
    private fun deletePendingChapters() {
        viewModelScope.launchNonCancellable {
            downloadManager.deletePendingChapters()
            tempFileManager.deleteTempFiles()
        }
    }

    // ==================== OCR Methods ====================

    /**
     * Get OCR blocks for a given page via Lens OCR API.
     *
     * Calls LensClient (already registered in AppModule) to run OCR on the page image,
     * then converts the result from OcrResult format (normalized 0..1 coords, single text string)
     * to OcrTextBlock format (normalized coords, lines list, orientation flag).
     *
     * Blocks on network I/O — call via withContext(Dispatchers.IO).
     * Errors are logged and handled gracefully (returns empty list).
     *
     * @param page The page to get OCR blocks for
     * @return List of OcrTextBlock with normalized coordinates (0.0–1.0), or empty list on error
     */
    suspend fun getOcrBlocks(page: ReaderPage): List<eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock> {
        val chapterId = page.chapter.chapter.id ?: return emptyList()
        val ocrSource = state.value.ocrSource
        val cacheKey = OcrCacheKey(
            chapterId = chapterId,
            pageIndex = page.index,
            ocrSource = ocrSource,
        )

        ocrCacheMutex.withLock {
            ocrCache[cacheKey]?.let { cached ->
                return cached
            }
        }

        if (ocrSource.persistsOcrResults) {
            loadCachedOcrBlocks(page, ocrSource)?.let { cached ->
                return cached
            }
        }

        if (isRegionOnlyOcr()) return emptyList()

        val deferred = ocrCacheMutex.withLock {
            ocrCache[cacheKey]?.let { return it }
            ocrInFlight[cacheKey] ?: viewModelScope.async(ocrDispatcher) {
                withTimeoutOrNull(10_000) {
                    page.statusFlow.first { it == Page.State.Ready }
                } ?: run {
                    logcat(LogPriority.WARN) { "OCR skipped for page ${page.index}: not Ready within 10s" }
                    return@async emptyList()
                }

                fetchOcrBlocks(page, ocrSource)
            }.also { created ->
                ocrInFlight[cacheKey] = created
                created.invokeOnCompletion {
                    viewModelScope.launch {
                        ocrCacheMutex.withLock {
                            if (ocrInFlight[cacheKey] === created) ocrInFlight.remove(cacheKey)
                        }
                    }
                }
            }
        }

        return try {
            deferred.await()
        } finally {
            ocrCacheMutex.withLock {
                if (ocrInFlight[cacheKey] === deferred && deferred.isCompleted) {
                    ocrInFlight.remove(cacheKey)
                }
            }
        }
    }

    /** Recognizes every crop before replacing regions in the existing page cache. */
    suspend fun rescanOcrRegions(regions: List<OcrPageRegion>) = withContext(Dispatchers.IO) {
        require(regions.isNotEmpty())
        check(isOcrAllowedForCurrentManga())
        val manga = state.value.manga ?: error("No manga loaded")
        val ocrSource = state.value.ocrSource
        val source = sourceManager.getOrStub(manga.source)
        val profile = resolveOcrProfile()
        val engine = ocrSource.recognitionEngine ?: OcrEngineType.fromPreference(dictionaryPreferences.ocrEngine().get())
        // Missing models normally yield an empty result. A rescan must keep the old blocks in that case.
        when (engine) {
            OcrEngineType.LOCAL -> {
                val models = Injekt.get<ModelDownloader>()
                if (!models.isDownloaded) models.triggerDownload()
                val bridge = Injekt.get<LocalOcrBridge>()
                check(models.isDownloaded && bridge.isAvailable && bridge.init()) { "Local OCR is not ready" }
            }
            OcrEngineType.PADDLE -> {
                val models = Injekt.get<ModelDownloader>()
                if (!models.isPaddleDownloaded) models.triggerPaddleDownload()
                val bridge = Injekt.get<PaddleOcrBridge>()
                check(models.isPaddleDownloaded && bridge.isAvailable && bridge.init()) { "Paddle OCR is not ready" }
            }
            OcrEngineType.CLOUD -> Unit
        }
        val language = chimahon.ocr.OcrLanguage.entries.find {
            it.bcp47.equals(profile.languageCode, ignoreCase = true)
        } ?: chimahon.ocr.OcrLanguage.JAPANESE
        val originals = mutableMapOf<OcrCacheKey, List<OcrTextBlock>>()
        val changes = regions.groupBy { it.page }.map { (page, selections) ->
            check(page.status == Page.State.Ready)
            val chapter = page.chapter.chapter.toDomainChapter() ?: error("No chapter loaded")
            val key = OcrCacheKey(chapter.id ?: error("No chapter ID"), page.index, ocrSource)
            originals[key] = getCachedOcrBlocks(page)
            val bitmap = getCurrentPageBitmap(page) ?: error("Could not decode page")
            val replacements = try {
                selections.map { selection ->
                    currentCoroutineContext().ensureActive()
                    val left = kotlin.math.floor(selection.bounds.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                    val top = kotlin.math.floor(selection.bounds.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                    val right = kotlin.math.ceil(selection.bounds.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
                    val bottom = kotlin.math.ceil(selection.bounds.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
                    val bounds = RectF(left.toFloat() / bitmap.width, top.toFloat() / bitmap.height, right.toFloat() / bitmap.width, bottom.toFloat() / bitmap.height)
                    val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                    val blocks = try {
                        retryWithBackoff(times = 3) {
                            recognizePage(crop, language, engineType = engine)
                        }.toScreenLookupBlocks(language.bcp47)
                    } finally {
                        if (crop !== bitmap) crop.recycle()
                    }
                    bounds to OcrCoordinateMapper.blocksFromRegion(blocks, bounds)
                }
            } finally {
                bitmap.recycle()
            }
            Triple(key, chapter, replacements)
        }
        var updated: Map<OcrCacheKey, List<OcrTextBlock>>? = null
        while (updated == null) {
            val pending = ocrCacheMutex.withLock { changes.mapNotNull { ocrInFlight[it.first] } }
            pending.awaitAll()
            updated = ocrCacheMutex.withLock {
                currentCoroutineContext().ensureActive()
                if (state.value.ocrSource != ocrSource || state.value.manga?.id != manga.id) throw CancellationException()
                if (changes.any { ocrInFlight[it.first]?.isActive == true }) return@withLock null
                changes.associate { (key, _, replacements) ->
                    key to replacements.fold(ocrCache[key] ?: originals.getValue(key)) { current, (bounds, scanned) ->
                        OcrCoordinateMapper.replaceRegion(current, bounds, scanned)
                    }
                }.also { result ->
                    ocrCache.putAll(result)
                    trimOcrCacheLocked()
                }
            }
        }
        mutableState.update { it.copy(regionOcrEnabled = true) }
        val completed = checkNotNull(updated)
        changes.forEach { (key, chapter, _) ->
            savePersistentOcrBlocks(ocrSource, manga, chapter, source, key.pageIndex, completed.getValue(key).map { it.toCacheBlock() }, language.bcp47)
        }
    }

    suspend fun getCachedOcrBlocks(page: ReaderPage): List<eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock> {
        val chapterId = page.chapter.chapter.id ?: return emptyList()
        val ocrSource = state.value.ocrSource
        val cacheKey = OcrCacheKey(chapterId = chapterId, pageIndex = page.index, ocrSource = ocrSource)

        ocrCacheMutex.withLock {
            ocrCache[cacheKey]?.let { return it }
        }

        return if (ocrSource.persistsOcrResults) {
            loadCachedOcrBlocks(page, ocrSource).orEmpty()
        } else {
            emptyList()
        }
    }

    private suspend fun loadCachedOcrBlocks(
        page: ReaderPage,
        ocrSource: ReaderOcrSource,
    ): List<eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock>? {
        val domainChapter = page.chapter.chapter.toDomainChapter() ?: return null
        val chapterId = domainChapter.id ?: return null
        val manga = state.value.manga ?: return null
        val mangaSource = sourceManager.getOrStub(manga.source)
        val cacheKey = OcrCacheKey(chapterId = chapterId, pageIndex = page.index, ocrSource = ocrSource)

        val diskBlocks = ocrCacheManager.loadOcrBlocks(
            manga = manga,
            chapter = domainChapter,
            source = mangaSource,
            pageIndex = page.index,
            cacheVariant = ocrSource.persistentCacheVariant,
        )
            ?.takeIf { it.isNotEmpty() }
            ?.map { it.toViewerBlock() }
            ?: return null

        return ocrCacheMutex.withLock {
            val blocks = ocrCache.getOrPut(cacheKey) { diskBlocks }
            trimOcrCacheLocked()
            blocks
        }
    }

    private fun scanOcrPages(currentPage: ReaderPage) {
        if (!isOcrEnabled() || isRegionOnlyOcr()) return
        val chapter = currentPage.chapter
        val chapterId = chapter.chapter.id ?: return
        if (ocrScanChapterId == chapterId) {
            if (ocrScanJob?.isActive == true || chapterId in ocrScannedChapterIds) {
                return
            }
        }

        ocrScanJob?.cancel()
        ocrScanChapterId = chapterId
        ocrScannedChapterIds.remove(chapterId)
        ocrScanJob = viewModelScope.launch {
            val pages = chapter.pages ?: return@launch
            val scanPages = buildOcrScanPages(pages, currentPage.index)
            if (scanPages.isEmpty()) return@launch

            var completed = 0
            mutableState.update {
                it.copy(
                    ocrScanProgress = OcrScanProgress(
                        completedPages = completed,
                        totalPages = scanPages.size,
                        activeWorkers = 0,
                    ),
                )
            }

            supervisorScope {
                scanPages.chunked(OCR_SCAN_WORKERS).forEach { batch ->
                    mutableState.update {
                        it.copy(
                            ocrScanProgress = it.ocrScanProgress?.copy(activeWorkers = batch.size),
                        )
                    }

                    batch.map { page ->
                        async { getOcrBlocks(page) }
                    }.awaitAll()

                    completed += batch.size
                    mutableState.update {
                        it.copy(
                            ocrScanProgress = it.ocrScanProgress?.copy(
                                completedPages = completed.coerceAtMost(scanPages.size),
                                activeWorkers = 0,
                            ),
                        )
                    }
                }
            }
            ocrScannedChapterIds.add(chapterId)
            delay(800)
            mutableState.update { it.copy(ocrScanProgress = null) }
        }
    }

    private fun buildOcrScanPages(
        pages: List<ReaderPage>,
        startIndex: Int,
    ): List<ReaderPage> {
        if (pages.isEmpty()) return emptyList()
        val start = startIndex.coerceIn(pages.indices)
        return pages.subList(start, pages.size) + pages.subList(0, start)
    }

    private fun cancelOcrScan() {
        ocrScanJob?.cancel()
        ocrScanJob = null
        ocrScanChapterId = null
        mutableState.update { it.copy(ocrScanProgress = null) }
    }

    private fun getSelectedReaderPage(): ReaderPage? {
        val currentChapter = state.value.currentChapter ?: return null
        val pages = currentChapter.pages ?: return null
        val pageIndex = (state.value.currentPage - 1).coerceAtLeast(0)
        return pages.getOrNull(pageIndex)
    }

    private data class ChapterFileInfo(
        val chapterFile: com.hippo.unifile.UniFile,
        val baseDir: com.hippo.unifile.UniFile,
        val chapterName: String,
    )

    private fun resolveChapterFile(chapter: Chapter, source: Source): ChapterFileInfo? {
        return if (source.isLocal()) {
            val parts = chapter.url.split('/', limit = 2)
            if (parts.size != 2) return null
            val (mangaDirName, chapterName) = parts

            val baseDir = localFileSystem.getBaseDirectory()
                ?.findFile(mangaDirName) ?: return null

            val chapterFile = baseDir.findFile(chapterName) ?: return null

            ChapterFileInfo(chapterFile, baseDir, chapterName)
        } else {
            val manga = state.value.manga ?: return null
            val chapterFile = downloadProvider.findChapterDir(
                chapter.name,
                chapter.scanlator,
                chapter.url,
                manga.ogTitle,
                source,
            ) ?: return null

            ChapterFileInfo(
                chapterFile,
                chapterFile.parentFile ?: return null,
                chapterFile.name ?: return null,
            )
        }
    }

    private suspend fun loadMokuroChapter(
        chapter: Chapter,
        source: Source,
    ): MokuroChapterData? {
        val chapterId = chapter.id ?: return null

        mokuroChapterCache[chapterId]?.let { return it }

        val mutex = synchronized(mokuroLoadMutex) {
            mokuroLoadMutex.getOrPut(chapterId) { Mutex() }
        }

        return mutex.withLock {
            mokuroChapterCache[chapterId]?.let { return@withLock it }

            val file = resolveChapterFile(chapter, source) ?: return@withLock null
            val (chapterFile, baseDir, chapterName) = file

            val isArchive = !chapterFile.isDirectory && (
                chapterName.endsWith(".epub", ignoreCase = true) ||
                    Archive.isSupported(chapterFile)
            )
            val content = readMokuroContent(chapterFile, chapterName, baseDir, isArchive)
                ?: return@withLock null

            val imageFiles = resolveChapterImageFiles(chapterFile, chapterName)

            val mokuro = chimahon.ocr.Mokuro.parseMokuro(content)
                ?: return@withLock null

            val data = MokuroChapterData(mokuro, imageFiles)
            mokuroChapterCache[chapterId] = data
            logcat { "Mokuro: cached chapter $chapterId with ${mokuro.pages.size} pages, ${imageFiles.size} images" }
            data
        }
    }

    private fun getMokuroBlocksForPage(
        chapterData: MokuroChapterData,
        pageIndex: Int,
    ): List<eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock>? {
        val mokuroPage = chimahon.ocr.Mokuro.resolveMokuroPage(
            chapterData.mokuro,
            chapterData.imageFiles,
            pageIndex,
        ) ?: return null

        return chimahon.ocr.Mokuro.convertMokuroBlocks(mokuroPage).map { block ->
            eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock(
                xmin = block.xmin,
                ymin = block.ymin,
                xmax = block.xmax,
                ymax = block.ymax,
                lines = block.lines,
                vertical = block.vertical,
                lineGeometries = block.lineGeometries?.map { lg ->
                    eu.kanade.tachiyomi.ui.reader.viewer.OcrLineGeometry(lg.xmin, lg.ymin, lg.xmax, lg.ymax, lg.rotation)
                },
            )
        }
    }

    private suspend fun prewarmMokuroOcr(
        manga: Manga,
        chapter: Chapter,
        source: Source,
        totalPages: Int,
        startPage: Int = 0,
        count: Int = 4,
    ) {
        val chapterId = chapter.id ?: return
        val chapterData = loadMokuroChapter(chapter, source) ?: return

        val endPage = minOf(startPage + count, totalPages)
        for (pageIndex in startPage until endPage) {
            val cacheKey = OcrCacheKey(chapterId, pageIndex, ReaderOcrSource.AUTOMATIC)
            if (ocrCacheMutex.withLock { ocrCache.containsKey(cacheKey) }) continue

            getMokuroBlocksForPage(chapterData, pageIndex)?.let { rawBlocks ->
                val blocks = rawBlocks.map { it.copy(language = chimahon.ocr.OcrLanguage.JAPANESE.bcp47) }
                ocrCacheMutex.withLock {
                    if (ocrCache.containsKey(cacheKey)) return@let
                    ocrCache[cacheKey] = blocks
                    trimOcrCacheLocked()
                    ocrCacheManager.saveOcrBlocks(
                        manga = manga,
                        chapter = chapter,
                        source = source,
                        pageIndex = pageIndex,
                        blocks = blocks.map { it.toCacheBlock() },
                        language = chimahon.ocr.OcrLanguage.JAPANESE.bcp47,
                    )
                }
            }
        }
        logcat { "Mokuro: prewarmed OCR for pages $startPage-${endPage - 1} of chapter $chapterId" }
    }

    private fun buildMokuroExtensionUrl(manga: Manga, chapter: Chapter, source: Source): String? {
        if (source !is eu.kanade.tachiyomi.source.online.HttpSource) return null
        if (!source.name.equals("Mokuro", ignoreCase = true)) return null

        val parts = chapter.url.split("|", limit = 2)
        if (parts.size != 2) return null
        val (seriesPath, volumeName) = parts

        return "https://mokuro.moe/mokuro-reader".toHttpUrl().newBuilder()
            .addPathSegment(seriesPath)
            .addPathSegment("$volumeName.mokuro")
            .build()
            .toString()
    }

    private suspend fun tryLoadMokuroFromUrl(
        mokuroUrl: String,
        manga: Manga,
        chapter: Chapter,
        source: Source,
        pageIndex: Int,
        totalPages: Int,
    ): List<eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock>? {
        return runCatching {
            val request = okhttp3.Request.Builder()
                .url(mokuroUrl)
                .header("Referer", "https://mokuro.moe/catalog")
                .build()

            val content = networkClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logcat(LogPriority.ERROR) { "Mokuro fetch failed: ${response.code}" }
                    return@runCatching null
                }
                response.body?.string() ?: return@runCatching null
            }

            val mokuro = chimahon.ocr.Mokuro.parseMokuro(content)
                ?: run {
                    logcat(LogPriority.ERROR) { "Mokuro: failed to parse JSON" }
                    return@runCatching null
                }

            val imageFiles = resolveChapterImageFiles(chapter, source)
            val mokuroPage = if (imageFiles.isEmpty()) {
                mokuro.pages.getOrNull(pageIndex)
            } else {
                chimahon.ocr.Mokuro.resolveMokuroPage(mokuro, imageFiles, pageIndex)
            } ?: return@runCatching null

            chimahon.ocr.Mokuro.convertMokuroBlocks(mokuroPage).map { block ->
                eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock(
                    xmin = block.xmin,
                    ymin = block.ymin,
                    xmax = block.xmax,
                    ymax = block.ymax,
                    lines = block.lines,
                    vertical = block.vertical,
                    lineGeometries = block.lineGeometries?.map { lg ->
                        eu.kanade.tachiyomi.ui.reader.viewer.OcrLineGeometry(lg.xmin, lg.ymin, lg.xmax, lg.ymax, lg.rotation)
                    },
                )
            }
        }.getOrNull()
    }

    private fun resolveChapterImageFiles(chapter: Chapter, source: Source): List<chimahon.ocr.ImageFileInfo> {
        if (source.isLocal() == false) return emptyList()

        val parts = chapter.url.split('/', limit = 2)
        if (parts.size != 2) return emptyList()
        val (mangaDirName, chapterName) = parts

        val mangaDir = localFileSystem.getBaseDirectory()
            ?.findFile(mangaDirName)
            ?: return emptyList()

        val chapterFile = mangaDir.findFile(chapterName)
            ?: return emptyList()

        return resolveChapterImageFiles(chapterFile, chapterName)
    }

    private fun resolveChapterImageFiles(
        chapterFile: com.hippo.unifile.UniFile,
        chapterName: String,
    ): List<chimahon.ocr.ImageFileInfo> {
        if (chapterFile.isDirectory) {
            return chapterFile.listFiles()
                ?.filter { it.isFile && isImageExtension(it.name) }
                ?.sortedWith { f1, f2 ->
                    f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty())
                }
                ?.map { f -> f.name.orEmpty().toMokuroImageFileInfo() }
                .orEmpty()
        }

        if (chapterName.endsWith(".epub", ignoreCase = true)) {
            return runCatching {
                chapterFile.epubReader(application).use { epub ->
                    epub.getImagesFromPages().map { it.toMokuroImageFileInfo() }
                }
            }.getOrElse { e ->
                logcat(LogPriority.ERROR, e) { "Mokuro: failed to read EPUB image list for $chapterName" }
                emptyList()
            }
        }

        if (Archive.isSupported(chapterFile)) {
            return runCatching {
                chapterFile.archiveReader(application).use { reader ->
                    reader.useEntries { entries ->
                        entries
                            .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                            .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                            .map { it.name.toMokuroImageFileInfo() }
                            .toList()
                    }
                }
            }.getOrElse { e ->
                logcat(LogPriority.ERROR, e) { "Mokuro: failed to read archive image list for $chapterName" }
                emptyList()
            }
        }

        return emptyList()
    }

    private fun String.toMokuroImageFileInfo(): chimahon.ocr.ImageFileInfo {
        val fileName = substringAfterLast('/').substringAfterLast('\\')
        return chimahon.ocr.ImageFileInfo(
            name = fileName,
            relativePath = this,
            basename = fileName.substringBeforeLast('.'),
        )
    }

    private suspend fun tryLoadMokuroBlocks(
        manga: Manga,
        chapter: Chapter,
        source: Source,
        pageIndex: Int,
    ): List<eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock>? {
        val chapterData = loadMokuroChapter(chapter, source) ?: return null
        return getMokuroBlocksForPage(chapterData, pageIndex)
    }

    private fun readMokuroContent(
        chapterFile: com.hippo.unifile.UniFile,
        chapterName: String,
        parentDir: com.hippo.unifile.UniFile,
        isArchive: Boolean,
    ): String? {
        val mokuroBaseName = if (isArchive) {
            chapterName.substringBeforeLast('.')
        } else {
            chapterName
        }

        if (chapterFile.isDirectory) {
            val insideFile = chapterFile.listFiles()?.firstOrNull {
                it.name?.endsWith(".mokuro", ignoreCase = true) == true
            }
            if (insideFile != null) {
                logcat { "Mokuro: found inside chapter folder: ${insideFile.name}" }
                return insideFile.openInputStream().use { it.bufferedReader().readText() }
            }
        }

        mokuroBaseName.mokuroSidecarBaseNames().forEach { baseName ->
            val siblingFile = parentDir.findFile("$baseName.mokuro")
            if (siblingFile != null && siblingFile.isFile == true) {
                logcat { "Mokuro: found as sibling: $baseName.mokuro" }
                return siblingFile.openInputStream().use { it.bufferedReader().readText() }
            }
        }

        if (isArchive) {
            val archiveContent = runCatching {
                chapterFile.archiveReader(application).use { reader ->
                    val entry = reader.useEntries { entries ->
                        entries.firstOrNull {
                            it.isFile && it.name.endsWith(".mokuro", ignoreCase = true)
                        }
                    } ?: return@runCatching null

                    reader.getInputStream(entry.name)!!.bufferedReader().use {
                        val text = it.readText()
                        logcat { "Mokuro: found inside archive file: ${chapterFile.name}/${entry.name}" }
                        text
                    }
                }
            }.getOrElse { e ->
                logcat(LogPriority.ERROR, e) { "Mokuro: failed to read .mokuro file in archive for $chapterName" }
                null
            }

            if (archiveContent != null)
                return archiveContent
        }

        logcat { "Mokuro: no .mokuro file found for $chapterName (tried inside folder, inside archive and siblings)" }
        return null
    }

    private fun String.mokuroSidecarBaseNames(): List<String> {
        val hashless = replace(Regex("_[A-Za-z0-9]{6}$"), "")
        return if (hashless == this) listOf(this) else listOf(this, hashless)
    }

    private fun checkMokuroFileExists(chapter: Chapter, source: Source): Boolean {
        if (source.name.equals("Mokuro", ignoreCase = true)) return true

        val file = resolveChapterFile(chapter, source) ?: return false
        val (chapterFile, baseDir, chapterName) = file

        val isArchive = !chapterFile.isDirectory && (
            chapterName.endsWith(".epub", ignoreCase = true) ||
                Archive.isSupported(chapterFile)
        )

        val mokuroBaseName = if (isArchive) {
            chapterName.substringBeforeLast('.')
        } else {
            chapterName
        }

        if (chapterFile.isDirectory) {
            val insideFile = chapterFile.listFiles()?.firstOrNull {
                it.name?.endsWith(".mokuro", ignoreCase = true) == true
            }
            if (insideFile != null) return true
        }

        mokuroBaseName.mokuroSidecarBaseNames().forEach { baseName ->
            val siblingFile = baseDir.findFile("$baseName.mokuro")
            if (siblingFile != null && siblingFile.isFile == true) return true
        }

        if (isArchive) {
            val hasMokuro = runCatching {
                chapterFile.archiveReader(application).use { reader ->
                    reader.useEntries { entries ->
                        entries.any { it.isFile && it.name.endsWith(".mokuro", ignoreCase = true) }
                    }
                }
            }.getOrDefault(false)

            if (hasMokuro) return true
        }

        return false
    }

    private fun isImageExtension(name: String?): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heif", "heic", "jxl")
    }

    private suspend fun fetchOcrBlocks(
        page: ReaderPage,
        ocrSource: ReaderOcrSource,
    ): List<eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock> {
        if (!isOcrEnabled()) return emptyList()
        val startMs = SystemClock.elapsedRealtime()
        val dbChapter = page.chapter.chapter
        val domainChapter = dbChapter.toDomainChapter() ?: return emptyList()
        val chapterId = domainChapter.id ?: return emptyList()
        val cacheKey = OcrCacheKey(chapterId = chapterId, pageIndex = page.index, ocrSource = ocrSource)

        val manga = state.value.manga ?: return emptyList()
        val source = sourceManager.getOrStub(manga.source)

        val ocrLang = run {
            val profile = dictionaryPreferences.profileResolver.resolve(
                mangaId = manga.id,
                sourceId = manga.source,
                sourceLang = source.lang,
            )
            chimahon.ocr.OcrLanguage.entries.find {
                it.bcp47.equals(profile.languageCode, ignoreCase = true)
            } ?: chimahon.ocr.OcrLanguage.JAPANESE
        }

        if (
            ocrSource.usesMokuro &&
            (
                source.isLocal() ||
                    downloadProvider.findChapterDir(
                        domainChapter.name,
                        domainChapter.scanlator,
                        domainChapter.url,
                        manga.ogTitle,
                        source,
                    ) != null
                )
        ) {
            tryLoadMokuroBlocks(manga, domainChapter, source, page.index)?.let { rawBlocks ->
                val blocks = rawBlocks.map { it.copy(language = ocrLang.bcp47) }
                ocrCacheMutex.withLock {
                    ocrCache[cacheKey] = blocks
                    trimOcrCacheLocked()
                }
                savePersistentOcrBlocks(
                    ocrSource = ocrSource,
                    manga = manga,
                    chapter = domainChapter,
                    source = source,
                    pageIndex = page.index,
                    blocks = blocks.map { it.toCacheBlock() },
                    language = ocrLang.bcp47,
                )
                val elapsedMs = SystemClock.elapsedRealtime() - startMs
                logcat { "OCR mokuro path: chapter=${page.chapter.chapter.id} page=${page.index} blocks=${blocks.size} time=${elapsedMs}ms" }

                if (ocrSource == ReaderOcrSource.AUTOMATIC && page.index < 3) {
                    val totalPages = page.chapter.pages?.size ?: 0
                    if (totalPages > 0) {
                        viewModelScope.launchIO {
                            prewarmMokuroOcr(manga, domainChapter, source, totalPages, startPage = page.index + 1)
                        }
                    }
                }

                return blocks
            }
        }

        val mokuroUrl = if (ocrSource.usesMokuro) {
            buildMokuroExtensionUrl(manga, domainChapter, source)
        } else {
            null
        }
        if (mokuroUrl != null) {
            tryLoadMokuroFromUrl(mokuroUrl, manga, domainChapter, source, page.index, page.chapter.pages?.size ?: 0)?.let { rawBlocks ->
                val mokuroLang = chimahon.ocr.OcrLanguage.JAPANESE.bcp47
                val blocks = rawBlocks.map { it.copy(language = mokuroLang) }
                ocrCacheMutex.withLock {
                    ocrCache[cacheKey] = blocks
                    while (ocrCache.size > maxOcrCacheEntries) {
                        val firstKey = ocrCache.keys.firstOrNull() ?: break
                        ocrCache.remove(firstKey)
                    }
                }
                savePersistentOcrBlocks(
                    ocrSource = ocrSource,
                    manga = manga,
                    chapter = domainChapter,
                    source = source,
                    pageIndex = page.index,
                    blocks = blocks.map { it.toCacheBlock() },
                    language = mokuroLang,
                )
                val elapsedMs = SystemClock.elapsedRealtime() - startMs
                logcat { "OCR mokuro extension fetch: chapter=$chapterId page=${page.index} blocks=${blocks.size} time=${elapsedMs}ms" }
                return blocks
            }
        }

        if (ocrSource == ReaderOcrSource.MOKURO) return emptyList()

        if (ocrSource.persistsOcrResults) {
            val diskBlocks = ocrCacheManager.loadOcrBlocks(
                manga = manga,
                chapter = domainChapter,
                source = source,
                pageIndex = page.index,
                cacheVariant = ocrSource.persistentCacheVariant,
            )
            if (diskBlocks != null && diskBlocks.isNotEmpty()) {
                ocrCacheMutex.withLock {
                    ocrCache[cacheKey] = diskBlocks.map { it.toViewerBlock() }
                    trimOcrCacheLocked()
                }
                logcat { "OCR disk hit: chapter=$chapterId page=${page.index} blocks=${diskBlocks.size}" }
                return diskBlocks.map { it.toViewerBlock() }
            }
        }

        return try {
            val imageBytes = withIOContext {
                page.stream?.invoke()?.use { it.readBytes() }
            } ?: run {
                logcat { "OCR: No stream for page ${page.index}" }
                return emptyList()
            }

            // ── Seam-aware augmentation ──────────────────────────
            var originalImageHeight = 0
            var ocrBytes = imageBytes
            val pages = page.chapter.pages
            val nextIdx = page.index + 1
            if (pages != null && nextIdx < pages.size) {
                val nextBytes = try {
                    withIOContext {
                        pages[nextIdx].stream?.invoke()?.use { it.readBytes() }
                    }
                } catch (_: Exception) {
                    null
                }
                if (nextBytes != null) {
                    val augmented = ImageUtil.appendTopOverlap(imageBytes, nextBytes)
                    if (augmented != null) {
                        originalImageHeight = ImageUtil.getImageHeight(imageBytes)
                        ocrBytes = augmented
                    }
                }
            }

            val ocrResults = retryWithBackoff(times = 3) {
                eu.kanade.tachiyomi.data.ocr.recognizePage(
                    bytes = ocrBytes,
                    language = ocrLang,
                    engineType = ocrSource.recognitionEngine,
                )
            }

            val blocks = ocrResults.mapNotNull { result ->
                val bbox = result.tightBoundingBox
                val xmin = bbox.x.toFloat().coerceIn(0f, 1f)
                val ymin = bbox.y.toFloat().coerceIn(0f, 1f)
                val xmax = (bbox.x + bbox.width).toFloat().coerceIn(0f, 1f)
                val ymax = (bbox.y + bbox.height).toFloat().coerceIn(0f, 1f)

                val lineGeometries = result.constituentBoxes?.map { lineBox ->
                    eu.kanade.tachiyomi.ui.reader.viewer.OcrLineGeometry(
                        xmin = lineBox.x.toFloat().coerceIn(0f, 1f),
                        ymin = lineBox.y.toFloat().coerceIn(0f, 1f),
                        xmax = (lineBox.x + lineBox.width).toFloat().coerceIn(0f, 1f),
                        ymax = (lineBox.y + lineBox.height).toFloat().coerceIn(0f, 1f),
                        rotation = (lineBox.rotation ?: 0.0).toFloat(),
                    )
                }

                if (xmax <= xmin || ymax <= ymin) {
                    null
                } else {
                    eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock(
                        xmin = xmin,
                        ymin = ymin,
                        xmax = xmax,
                        ymax = ymax,
                        lines = result.text.split("\n"),
                        vertical = result.forcedOrientation == "vertical",
                        lineGeometries = lineGeometries,
                        language = ocrLang.bcp47,
                    )
                }
            }

            // ── Remap augmented coordinates ───────────────────────
            val finalBlocks = if (originalImageHeight > 0) {
                val augmentedHeight = originalImageHeight + ImageUtil.SEAM_OVERLAP_PX
                eu.kanade.tachiyomi.ui.reader.viewer.OcrCoordinateMapper.remapSeamAugmented(
                    blocks = blocks,
                    originalHeight = originalImageHeight,
                    augmentedHeight = augmentedHeight,
                )
            } else {
                blocks
            }

            ocrCacheMutex.withLock {
                ocrCache[cacheKey] = finalBlocks
                trimOcrCacheLocked()
            }

            savePersistentOcrBlocks(
                ocrSource = ocrSource,
                manga = manga,
                chapter = domainChapter,
                source = source,
                pageIndex = page.index,
                blocks = finalBlocks.map { it.toCacheBlock() },
                language = ocrLang.bcp47,
            )

            val elapsedMs = SystemClock.elapsedRealtime() - startMs
            if (elapsedMs >= 1200) {
                logcat(LogPriority.WARN) {
                    "OCR slow path: chapter=${page.chapter.chapter.id} page=${page.index} blocks=${finalBlocks.size} time=${elapsedMs}ms"
                }
            } else {
                logcat {
                    "OCR success: chapter=${page.chapter.chapter.id} page=${page.index} blocks=${finalBlocks.size} time=${elapsedMs}ms"
                }
            }
            finalBlocks
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val elapsedMs = SystemClock.elapsedRealtime() - startMs
            logcat(LogPriority.WARN, e) {
                "OCR pipeline failed: chapter=${page.chapter.chapter.id} page=${page.index} after=${elapsedMs}ms"
            }
            emptyList()
        }
    }

    private suspend fun savePersistentOcrBlocks(
        ocrSource: ReaderOcrSource,
        manga: Manga,
        chapter: Chapter,
        source: Source,
        pageIndex: Int,
        blocks: List<chimahon.ocr.OcrTextBlock>,
        language: String,
    ) {
        if (!ocrSource.persistsOcrResults) return
        try {
            ocrCacheManager.saveOcrBlocks(
                manga = manga,
                chapter = chapter,
                source = source,
                pageIndex = pageIndex,
                blocks = blocks,
                language = language,
                cacheVariant = ocrSource.persistentCacheVariant,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) {
                "OCR cache write failed: chapter=${chapter.id} page=$pageIndex source=$ocrSource"
            }
        }
    }

    private fun trimOcrCacheLocked() {
        while (ocrCache.size > maxOcrCacheEntries) {
            val firstKey = ocrCache.keys.firstOrNull() ?: break
            ocrCache.remove(firstKey)
        }
    }

    @Immutable
    data class OcrScanProgress(
        val completedPages: Int,
        val totalPages: Int,
        val activeWorkers: Int,
    )

    @Immutable
    data class State(
        val manga: Manga? = null,
        val viewerChapters: ViewerChapters? = null,
        val bookmarked: Boolean = false,
        val isLoadingAdjacentChapter: Boolean = false,
        val currentPage: Int = -1,

        /**
         * Viewer used to display the pages (pager, webtoon, ...).
         */
        val viewer: Viewer? = null,
        val dialog: Dialog? = null,
        val menuVisible: Boolean = false,
        @field:IntRange(from = -100, to = 100) val brightnessOverlayValue: Int = 0,
        val ocrScanProgress: OcrScanProgress? = null,
        val ocrSource: ReaderOcrSource = ReaderOcrSource.AUTOMATIC,
        val regionOcrEnabled: Boolean = false,
        val mokuroAvailable: Boolean = false,

        // SY -->
        /** for display page number in double-page mode */
        val currentPageText: String = "",
        val meta: RaisedSearchMetadata? = null,
        val mergedManga: Map<Long, Manga>? = null,
        val ehUtilsVisible: Boolean = false,
        val lastShiftDoubleState: Boolean? = null,
        val indexPageToShift: Int? = null,
        val indexChapterToShift: Long? = null,
        val doublePages: Boolean = false,
        val dateRelativeTime: Boolean = true,
        val autoScroll: Boolean = false,
        val isAutoScrollEnabled: Boolean = false,
        val ehAutoscrollFreq: String = "",
        // SY <--
    ) {
        val currentChapter: ReaderChapter?
            get() = viewerChapters?.currChapter

        val totalPages: Int
            get() = currentChapter?.pages?.size ?: -1
    }

    sealed interface Dialog {
        data object Loading : Dialog
        data object Settings : Dialog
        data object ReadingModeSelect : Dialog
        data object OrientationModeSelect : Dialog

        // SY -->
        data object ChapterList : Dialog
        // SY <--

        data class PageActions(
            val page: ReaderPage,
            // SY -->
            val extraPage: ReaderPage? = null,
            // SY <--
        ) : Dialog

        // SY -->
        data object AutoScrollHelp : Dialog
        data object RetryAllHelp : Dialog
        data object BoostPageHelp : Dialog
        // SY <--
    }

    sealed interface Event {
        data object ReloadViewerChapters : Event
        data object PageChanged : Event
        data object InitializeOcrResources : Event
        data class SetOrientation(val orientation: Int) : Event
        data class SetCoverResult(val result: SetAsCoverResult) : Event

        data class SavedImage(val result: SaveImageResult) : Event
        data class ShareImage(
            val uri: Uri,
            val page: ReaderPage,
            // SY -->
            val secondPage: ReaderPage? = null,
            // SY <--
        ) : Event
        data class CopyImage(val uri: Uri) : Event
    }

    private fun trackMangaStats(newPage: ReaderPage?) {
        if (newPage == null) consumedMangaStatsPages.clear()

        val now = SystemClock.elapsedRealtime()
        val prevPage = currentMangaStatsPage
        val rawTime = now - lastMangaStatsTime
        val timeSpent = min(rawTime, 120_000L)

        if (prevPage != null && !incognitoMode && timeSpent > 500 && prevPage.index !in consumedMangaStatsPages) {
            consumedMangaStatsPages.add(prevPage.index)
            viewModelScope.launchIO {
                val blocks = getCachedOcrBlocks(prevPage)
                val chars = if (blocks.isNotEmpty()) {
                    blocks.sumOf { block -> block.fullText.length }
                } else {
                    0
                }
                com.canopus.chimareader.data.MangaStatsStorage.addStats(application, chars, timeSpent, manga?.id ?: 0)
                if (mangaStatsTracking) {
                    mangaStatsSessionCharacters += chars
                    mangaStatsSessionTimeMs += timeSpent
                }
                if (showMangaStats) {
                    refreshMangaStatsEstimate()
                }
            }
        }

        currentMangaStatsPage = newPage
        lastMangaStatsTime = now
        if (showMangaStats) {
            refreshMangaStatsEstimate()
        }
    }

    private fun refreshMangaStatsEstimate() {
        viewModelScope.launchIO {
            val estimate = buildMangaStatsEstimate()
            withUIContext {
                mangaStatsEstimate = estimate
            }
        }
    }

    private suspend fun buildMangaStatsEstimate(): MangaStatsEstimate {
        val viewerChapters = state.value.viewerChapters ?: return MangaStatsEstimate()
        val currentChapter = viewerChapters.currChapter
        val currentPages = currentChapter.pages.orEmpty()
        if (currentPages.isEmpty()) return MangaStatsEstimate()

        val currentPageIndex = (
            state.value.currentPage
                .takeIf { it > 0 }
                ?.minus(1)
                ?: currentMangaStatsPage?.index
                ?: 0
            ).coerceIn(currentPages.indices)

        val averageCharsPerPage = averageMangaCharsPerPage(currentPages)
        val remainingChapterCharacters = estimateMangaPageCharacters(
            pages = currentPages,
            startIndex = currentPageIndex,
            fallbackCharsPerPage = averageCharsPerPage,
        )

        val currentChapterIndex = chapterList.indexOf(currentChapter)
        val loadedPageCounts = chapterList.mapNotNull { it.pages?.size }.filter { it > 0 }
        val fallbackPagesPerChapter = when {
            loadedPageCounts.isNotEmpty() -> loadedPageCounts.average()
            currentPages.isNotEmpty() -> currentPages.size.toDouble()
            else -> 0.0
        }
        val remainingFuturePages = if (currentChapterIndex >= 0) {
            chapterList
                .drop(currentChapterIndex + 1)
                .sumOf { chapter ->
                    chapter.pages?.size?.takeIf { it > 0 }?.toDouble() ?: fallbackPagesPerChapter
                }
        } else {
            0.0
        }
        val remainingBookCharacters = remainingChapterCharacters +
            (remainingFuturePages * averageCharsPerPage).toInt()

        // Page-based estimation calculation
        val remainingChapterPages = (currentPages.size - currentPageIndex).coerceAtLeast(0)
        val remainingBookPages = remainingChapterPages + remainingFuturePages

        val sessionPagesRead = consumedMangaStatsPages.size
        val avgTimePerPageMs = if (sessionPagesRead > 0 && mangaStatsSessionTimeMs > 0) {
            val calculatedAvg = mangaStatsSessionTimeMs.toDouble() / sessionPagesRead.toDouble()
            calculatedAvg.coerceIn(5000.0, 120000.0) // Clamp between 5s and 120s
        } else {
            20000.0 // Default fallback of 20 seconds per page
        }

        val remainingChapterSeconds = (remainingChapterPages * avgTimePerPageMs) / 1000.0
        val remainingBookSeconds = (remainingBookPages * avgTimePerPageMs) / 1000.0

        return MangaStatsEstimate(
            remainingBookCharacters = remainingBookCharacters,
            remainingChapterCharacters = remainingChapterCharacters,
            remainingBookSeconds = remainingBookSeconds,
            remainingChapterSeconds = remainingChapterSeconds,
        )
    }

    private suspend fun averageMangaCharsPerPage(currentPages: List<ReaderPage>): Double {
        val consumedPageCount = consumedMangaStatsPages.size
        if (mangaStatsSessionCharacters > 0 && consumedPageCount > 0) {
            return mangaStatsSessionCharacters.toDouble() / consumedPageCount.toDouble()
        }

        val knownPageCharacters = currentPages.mapNotNull { page ->
            getCachedOcrBlocks(page)
                .sumOf { block -> block.fullText.length }
                .takeIf { it > 0 }
        }
        val avg = knownPageCharacters.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        return if (avg > 0.0) avg else 100.0
    }

    private suspend fun estimateMangaPageCharacters(
        pages: List<ReaderPage>,
        startIndex: Int,
        fallbackCharsPerPage: Double,
    ): Int {
        if (pages.isEmpty() || startIndex !in pages.indices) return 0
        return pages.drop(startIndex).sumOf { page ->
            val knownChars = getCachedOcrBlocks(page).sumOf { block -> block.fullText.length }
            if (knownChars > 0) knownChars else fallbackCharsPerPage.toInt()
        }
    }
}

private fun chimahon.ocr.OcrTextBlock.toViewerBlock(): eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock {
    return eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock(
        xmin = xmin,
        ymin = ymin,
        xmax = xmax,
        ymax = ymax,
        lines = lines,
        vertical = vertical,
        lineGeometries = lineGeometries?.map { lg ->
            eu.kanade.tachiyomi.ui.reader.viewer.OcrLineGeometry(lg.xmin, lg.ymin, lg.xmax, lg.ymax, lg.rotation)
        },
        language = language,
    )
}

private const val OCR_SCAN_WORKERS = 2

private fun OcrTextBlock.toCacheBlock() = chimahon.ocr.OcrTextBlock(
    xmin = xmin,
    ymin = ymin,
    xmax = xmax,
    ymax = ymax,
    lines = lines,
    vertical = vertical,
    lineGeometries = lineGeometries?.map { chimahon.ocr.OcrLineGeometry(it.xmin, it.ymin, it.xmax, it.ymax, it.rotation) },
    language = language,
)
