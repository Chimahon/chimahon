package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import chimahon.ocr.LensClient
import chimahon.ocr.OcrCacheManager
import chimahon.ocr.OcrLanguage
import chimahon.ocr.OcrResult
import chimahon.ocr.isOcrAllowedForLanguage
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.source.local.isLocal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import chimahon.ocr.OcrTextBlock as ChimahonOcrTextBlock

class OcrManager(
    private val context: Context,
    private val localOcrBridge: LocalOcrBridge? = null,
    private val modelDownloader: ModelDownloader? = null,
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val ocrStore: OcrStore = Injekt.get(),
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
) {
    private val ocrCacheManager: OcrCacheManager = Injekt.get()
    private val lensClient: LensClient = Injekt.get()

    private val downloadManager: DownloadManager by lazy { Injekt.get() }

    private val downloadProvider: DownloadProvider by lazy { Injekt.get() }

    private val _queueState = MutableStateFlow<List<OcrQueueItem>>(emptyList())
    val queueState: StateFlow<List<OcrQueueItem>> = _queueState.asStateFlow()
    private val _isPaused = MutableStateFlow(ocrStore.isPaused())
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()
    private val queueMutex = Mutex()
    private val runningJobs = java.util.concurrent.ConcurrentHashMap<Long, kotlinx.coroutines.Job>()
    private val triggerChannel = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    init {
        scope.launch {
            ocrStore.normalizeForRestore()
            promoteDownloadedWaitingTasks()
            refreshQueueState()
            if (_isPaused.value) {
                OcrNotifier(context).showPaused(_queueState.value)
            } else if (ocrStore.hasRunnableTasks()) {
                OcrJob.start(context)
            }
        }
    }

    val isRunning: Boolean
        get() = _queueState.value.any { it.status.isActionable() }

    fun isChapterRunning(chapterId: Long): Boolean {
        return _queueState.value.any {
            it.chapter.id == chapterId && it.status == OcrQueueStatus.PROCESSING
        }
    }

    fun isChapterQueued(chapterId: Long): Boolean = ocrStore.get(chapterId) != null

    internal fun hasRunnableTasks(): Boolean = ocrStore.hasRunnableTasks()

    fun queueChapters(manga: Manga, chapters: List<Chapter>, waitForDownload: Boolean = true) {
        if (chapters.isEmpty()) return
        val changed = ocrStore.enqueue(
            chapters.map { chapter ->
                OcrEnqueueRequest(
                    mangaId = manga.id,
                    chapterId = chapter.id,
                    waitForDownload = waitForDownload,
                )
            },
        )

        scope.launch {
            _queueState.update { current ->
                val existingIds = current.map { it.chapter.id }.toSet()
                val queued = chapters
                    .filterNot { it.id in existingIds }
                    .map { chapter ->
                        OcrQueueItem(
                            manga = manga,
                            chapter = chapter,
                            progress = 0f,
                            currentPage = 0,
                            totalPages = 0,
                            status = if (waitForDownload) OcrQueueStatus.WAITING_DOWNLOAD else OcrQueueStatus.PENDING,
                        )
                    }
                current + queued
            }
            refreshQueueState()
        }

        if (changed || ocrStore.hasRunnableTasks()) {
            triggerChannel.trySend(Unit)
            if (!_isPaused.value) OcrJob.start(context)
        }
    }

    fun markChapterReadyForOcr(manga: Manga, chapter: Chapter): Boolean {
        var changed = false
        ocrStore.update(chapter.id) { current ->
            if (current.status != OcrQueueStatus.WAITING_DOWNLOAD) {
                current
            } else {
                changed = true
                current.copy(
                    mangaId = manga.id,
                    waitForDownload = false,
                    status = OcrQueueStatus.PENDING,
                    currentPage = 0,
                    totalPages = 0,
                )
            }
        }
        if (!changed) return false

        scope.launch {
            refreshQueueState()
        }
        triggerChannel.trySend(Unit)
        if (!_isPaused.value) OcrJob.start(context)
        return true
    }

    fun markChapterDownloadFailed(chapterId: Long): Boolean {
        var changed = false
        ocrStore.update(chapterId) { current ->
            if (current.status != OcrQueueStatus.WAITING_DOWNLOAD) {
                current
            } else {
                changed = true
                current.copy(
                    waitForDownload = false,
                    status = OcrQueueStatus.ERROR,
                    currentPage = 0,
                    totalPages = 0,
                )
            }
        }
        if (!changed) return false

        scope.launch {
            refreshQueueState()
            if (_isPaused.value) {
                OcrNotifier(context).showPaused(_queueState.value)
            } else if (_queueState.value.none { it.status.isRunnable() }) {
                OcrNotifier(context).showResult(_queueState.value, completed = 0)
            }
        }
        return true
    }

    fun retryChapter(chapterId: Long): Boolean {
        var changed = false
        ocrStore.update(chapterId) { current ->
            if (current.status != OcrQueueStatus.ERROR) {
                current
            } else {
                changed = true
                current.copy(status = OcrQueueStatus.PENDING)
            }
        }
        if (!changed) return false
        scope.launch {
            refreshQueueState()
        }
        triggerChannel.trySend(Unit)
        if (!_isPaused.value) OcrJob.start(context)
        return true
    }

    fun retryAllErrors() {
        val errorTasks = ocrStore.getAll().filter { it.status == OcrQueueStatus.ERROR }
        if (errorTasks.isEmpty()) return
        errorTasks.forEach { task ->
            ocrStore.update(task.chapterId) { current ->
                if (current.status == OcrQueueStatus.ERROR) current.copy(status = OcrQueueStatus.PENDING) else current
            }
        }
        scope.launch {
            refreshQueueState()
        }
        triggerChannel.trySend(Unit)
        resumeQueue()
    }

    suspend fun pauseQueue() {
        ocrStore.setPaused(true)
        _isPaused.value = true
        val jobs = runningJobs.values.toList()
        jobs.forEach { it.cancel() }
        triggerChannel.trySend(Unit)
        OcrJob.stop(context)
        refreshQueueState()
        OcrNotifier(context).showPaused(_queueState.value)
    }

    fun resumeQueue() {
        ocrStore.setPaused(false)
        _isPaused.value = false
        OcrNotifier(context).dismissStatus()
        triggerChannel.trySend(Unit)
        // Queue behind a worker that may still be finishing a pause/cancel request.
        OcrJob.start(context, append = true)
    }

    suspend fun cancelAll() {
        ocrStore.removeAll(ocrStore.getAll().map { it.chapterId })
        val jobs = runningJobs.values.toList()
        jobs.forEach { it.cancel() }
        OcrJob.stop(context)
        triggerChannel.trySend(Unit)
        ocrStore.setPaused(false)
        _isPaused.value = false
        refreshQueueState()
        OcrNotifier(context).dismissStatus()
    }

    suspend fun cancelChapter(chapterId: Long) {
        ocrStore.remove(chapterId)
        runningJobs[chapterId]?.cancel()
        stateMutex.withLock {
            _queueState.update { current ->
                current.filterNot { it.chapter.id == chapterId }
            }
        }
        if (_queueState.value.isEmpty()) {
            OcrNotifier(context).dismissStatus()
        } else if (_isPaused.value) {
            OcrNotifier(context).dismissStatus()
            OcrNotifier(context).showPaused(_queueState.value)
        }
    }

    suspend fun deleteOcrForChapter(manga: Manga, chapter: Chapter) {
        val source = sourceManager.get(manga.source) ?: return
        ocrCacheManager.deleteOcrForChapter(manga, chapter, source)
        chapterRepository.update(ChapterUpdate(id = chapter.id, isOcrReady = false))
    }

    suspend fun deleteOcrForManga(manga: Manga) {
        val source = sourceManager.get(manga.source) ?: return
        ocrCacheManager.deleteOcrForManga(manga, source)
        // Note: Individual chapter isOcrReady flags will be stale but will be
        // corrected when chapters are re-fetched or when OCR is run again
    }

    suspend fun reindexDownloadedOcrStatus(
        manga: Manga,
        source: Source,
        chapters: List<Chapter>,
    ): Set<Long> {
        val readyChapterIds = mutableSetOf<Long>()
        chapters
            .filterNot { it.isOcrReady }
            .forEach { chapter ->
                val isDownloaded = downloadManager.isChapterDownloaded(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    manga.ogTitle,
                    source.id,
                )
                if (isDownloaded && ocrCacheManager.hasOcrData(manga, chapter, source)) {
                    readyChapterIds += chapter.id
                }
            }

        if (readyChapterIds.isNotEmpty()) {
            chapterRepository.updateAll(
                readyChapterIds.map { chapterId ->
                    ChapterUpdate(id = chapterId, isOcrReady = true)
                },
            )
        }

        return readyChapterIds
    }

    suspend fun getStorageSize(): String = ocrCacheManager.getReadableSize()

    suspend fun clearCache() {
        ocrCacheManager.clear()
    }

    fun clearPendingChapters(chapterIds: Collection<Long>) {
        if (chapterIds.isEmpty()) return
        ocrStore.removeAll(chapterIds)
        scope.launch {
            refreshQueueState()
        }
    }

    suspend fun runPendingQueue(
        onChapterCompleted: () -> Unit = {},
        stopRequested: () -> Boolean,
    ) = queueMutex.withLock {
        runQueue(stopRequested, onChapterCompleted)
    }

    private suspend fun runQueue(
        stopRequested: () -> Boolean,
        onChapterCompleted: () -> Unit,
    ) = kotlinx.coroutines.supervisorScope {
        refreshQueueState()
        val dictPrefs = Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>()
        
        val prefJob = launch {
            dictPrefs.parallelOcrLimit().changes().collect {
                triggerChannel.trySend(Unit)
            }
        }

        try {
            while (!stopRequested() && !_isPaused.value) {
                val parallelLimit = dictPrefs.parallelOcrLimit().get().coerceAtLeast(1)
                
                val runnableTasks = ocrStore.getAll().filter { it.status.isRunnable() }
                val nextTask = runnableTasks.firstOrNull { !runningJobs.containsKey(it.chapterId) }

                if (nextTask == null) {
                    if (runningJobs.isEmpty()) {
                        break
                    }
                    kotlinx.coroutines.withTimeoutOrNull(5000) {
                        triggerChannel.receive()
                    }
                    continue
                }

                if (runningJobs.size >= parallelLimit) {
                    kotlinx.coroutines.withTimeoutOrNull(5000) {
                        triggerChannel.receive()
                    }
                    continue
                }

                val manga = mangaRepository.getMangaById(nextTask.mangaId)
                val chapter = chapterRepository.getChapterById(nextTask.chapterId)
                if (stopRequested() || _isPaused.value) break
                if (ocrStore.get(nextTask.chapterId) == null) continue

                if (manga == null || chapter == null) {
                    ocrStore.remove(nextTask.chapterId)
                    refreshQueueState()
                    continue
                }

                val job = launch(ioDispatcher, start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    try {
                        val result = processTask(nextTask, manga, chapter, stopRequested)
                        when (result) {
                            OcrTaskResult.SUCCESS -> {
                                onChapterCompleted()
                                ocrStore.remove(nextTask.chapterId)
                            }
                            OcrTaskResult.CANCELLED -> {
                                ocrStore.remove(nextTask.chapterId)
                            }
                            OcrTaskResult.ERROR -> updateStoredTask(nextTask.chapterId) {
                                it.copy(status = OcrQueueStatus.ERROR)
                            }
                            OcrTaskResult.STOPPED -> {
                                if (ocrStore.get(nextTask.chapterId) != null) {
                                    updateStoredTask(nextTask.chapterId) { current ->
                                        current.copy(status = OcrQueueStatus.PENDING)
                                    }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        if (ocrStore.get(nextTask.chapterId) != null) {
                            updateStoredTask(nextTask.chapterId) { current ->
                                current.copy(status = OcrQueueStatus.PENDING)
                            }
                        }
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.ERROR, e) { "Error processing OCR task ${nextTask.chapterId}" }
                        try {
                            updateStoredTask(nextTask.chapterId) { it.copy(status = OcrQueueStatus.ERROR) }
                        } catch (_: Exception) {}
                    } finally {
                        runningJobs.remove(nextTask.chapterId)
                        withContext(NonCancellable) { refreshQueueState() }
                        triggerChannel.trySend(Unit)
                    }
                }
                runningJobs[nextTask.chapterId] = job
                job.invokeOnCompletion {
                    // A cancelled lazy job may never enter the body's finally block.
                    runningJobs.remove(nextTask.chapterId, job)
                    triggerChannel.trySend(Unit)
                }
                job.start()
            }
        } finally {
            prefJob.cancel()
            val jobs = runningJobs.values.toList()
            jobs.forEach { it.cancel() }
            withContext(NonCancellable) { jobs.joinAll() }
        }
    }

    private suspend fun promoteDownloadedWaitingTasks() {
        ocrStore.getAll()
            .filter { it.status == OcrQueueStatus.WAITING_DOWNLOAD }
            .forEach { task ->
                val manga = mangaRepository.getMangaById(task.mangaId) ?: return@forEach
                val chapter = chapterRepository.getChapterById(task.chapterId) ?: return@forEach
                val isDownloaded = downloadManager.isChapterDownloaded(
                    chapter.name,
                    chapter.scanlator,
                    chapter.url,
                    manga.ogTitle,
                    manga.source,
                )

                if (isDownloaded) {
                    ocrStore.update(task.chapterId) { current ->
                        if (current.status == OcrQueueStatus.WAITING_DOWNLOAD) {
                            current.copy(
                                waitForDownload = false,
                                status = OcrQueueStatus.PENDING,
                                currentPage = 0,
                                totalPages = 0,
                            )
                        } else {
                            current
                        }
                    }
                }
            }
    }

    private suspend fun refreshQueueState() {
        stateMutex.withLock {
            val tasks = ocrStore.getAll()
            val invalidChapterIds = mutableListOf<Long>()
            val mangaCache = mutableMapOf<Long, Manga?>()
            val items = tasks.mapNotNull { task ->
                val manga = mangaCache.getOrPut(task.mangaId) { mangaRepository.getMangaById(task.mangaId) }
                val chapter = chapterRepository.getChapterById(task.chapterId)

                if (manga == null || chapter == null) {
                    invalidChapterIds += task.chapterId
                    return@mapNotNull null
                }

                OcrQueueItem(
                    manga = manga,
                    chapter = chapter,
                    progress = if (task.totalPages > 0) task.currentPage.toFloat() / task.totalPages else 0f,
                    currentPage = task.currentPage,
                    totalPages = task.totalPages,
                    status = task.status,
                )
            }

            if (invalidChapterIds.isNotEmpty()) {
                ocrStore.removeAll(invalidChapterIds)
            }

            _queueState.value = items
        }
    }

    private fun updateStoredTask(chapterId: Long, transform: (OcrTask) -> OcrTask) {
        val updated = ocrStore.update(chapterId, transform) ?: return
        _queueState.update { current ->
            current.map { item ->
                if (item.chapter.id != chapterId) {
                    item
                } else {
                    item.copy(
                        currentPage = updated.currentPage,
                        totalPages = updated.totalPages,
                        progress = if (updated.totalPages >
                            0
                        ) {
                            updated.currentPage.toFloat() / updated.totalPages
                        } else {
                            0f
                        },
                        status = updated.status,
                    )
                }
            }
        }
    }

    internal suspend fun processTask(
        _task: OcrTask,
        manga: Manga,
        chapter: Chapter,
        stopRequested: () -> Boolean,
    ): OcrTaskResult {
        val chapterId = chapter.id
        logcat { "OcrManager: processing chapter ${chapter.name}" }

        try {
            val source = sourceManager.get(manga.source)
            if (source == null) {
                logcat(LogPriority.WARN) { "OcrManager: source not found for manga ${manga.id}" }
                chapterRepository.update(ChapterUpdate(id = chapterId, isOcrReady = false))
                updateStoredTask(chapterId) { it.copy(status = OcrQueueStatus.ERROR) }
                return OcrTaskResult.ERROR
            }

            val isLocalSource = source.isLocal()
            val sourceLang = if (isLocalSource) "other" else (source as? HttpSource)?.lang ?: "other"

            val dictPrefs = Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>()
            val profile = dictPrefs.profileResolver.resolve(
                mangaId = manga.id,
                sourceId = manga.source,
                sourceLang = sourceLang,
            )
            if (!isOcrAllowedForLanguage(sourceLang, profile.languageCode)) {
                logcat { "OcrManager: skipping OCR for source language $sourceLang and profile language ${profile.languageCode}" }
                chapterRepository.update(ChapterUpdate(id = chapterId, isOcrReady = false))
                updateStoredTask(chapterId) { it.copy(status = OcrQueueStatus.COMPLETED) }
                return OcrTaskResult.SUCCESS
            }
            val ocrLang = OcrLanguage.entries.find { it.bcp47.equals(profile.languageCode, ignoreCase = true) }
                ?: OcrLanguage.JAPANESE

            if (stopRequested()) return OcrTaskResult.STOPPED
            if (ocrStore.get(chapterId) == null) return OcrTaskResult.CANCELLED

            val isDownloaded = isLocalSource || downloadManager.isChapterDownloaded(
                chapter.name,
                chapter.scanlator,
                chapter.url,
                manga.ogTitle,
                manga.source,
            )

            if (!isDownloaded) {
                logcat(LogPriority.WARN) { "OcrManager: chapter not downloaded, skipping OCR" }
                chapterRepository.update(ChapterUpdate(id = chapterId, isOcrReady = false))
                updateStoredTask(chapterId) { it.copy(status = OcrQueueStatus.ERROR) }
                return OcrTaskResult.ERROR
            }

            val imageProvider = if (isLocalSource) {
                getLocalChapterImageProvider(manga, chapter)
            } else {
                getChapterImageProvider(manga, chapter, source)
            }
            if (imageProvider == null) {
                logcat(LogPriority.WARN) { "OcrManager: no images found in downloaded chapter" }
                chapterRepository.update(ChapterUpdate(id = chapterId, isOcrReady = false))
                updateStoredTask(chapterId) { it.copy(status = OcrQueueStatus.ERROR) }
                return OcrTaskResult.ERROR
            }

            var failedPages = 0
            val pageCount = imageProvider.pageCount
            if (pageCount <= 0) {
                imageProvider.close()
                logcat(LogPriority.WARN) { "OcrManager: no pages available for chapter ${chapter.name}" }
                chapterRepository.update(ChapterUpdate(id = chapterId, isOcrReady = false))
                updateStoredTask(chapterId) { it.copy(status = OcrQueueStatus.ERROR) }
                return OcrTaskResult.ERROR
            }

            try {
                updateStoredTask(chapterId) {
                    it.copy(
                        status = OcrQueueStatus.PROCESSING,
                        totalPages = pageCount,
                    )
                }

                for (pageIndex in 0 until pageCount) {
                    if (stopRequested()) {
                        updateStoredTask(chapterId) { current ->
                            current.copy(status = OcrQueueStatus.PENDING)
                        }
                        return OcrTaskResult.STOPPED
                    }
                    if (ocrStore.get(chapterId) == null) {
                        logcat { "OcrManager: cancelled chapter ${chapter.name}" }
                        return OcrTaskResult.CANCELLED
                    }

                    val existingBlocks = ocrCacheManager.loadOcrBlocks(manga, chapter, source, pageIndex)
                    if (existingBlocks != null) {
                        logcat { "OcrManager: page $pageIndex already cached for chapter ${chapter.name}" }
                        updateStoredTask(chapterId) {
                            it.copy(
                                currentPage = pageIndex + 1,
                                totalPages = pageCount,
                                status = OcrQueueStatus.PROCESSING,
                            )
                        }
                        continue
                    }

                    val bytes = try {
                        imageProvider.getPageBytes(pageIndex)
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "OcrManager: failed to read image for page $pageIndex" }
                        failedPages++
                        updateStoredTask(chapterId) {
                            it.copy(
                                currentPage = pageIndex + 1,
                                totalPages = pageCount,
                                status = OcrQueueStatus.PROCESSING,
                            )
                        }
                        continue
                    }

                    if (bytes == null) {
                        logcat(LogPriority.WARN) { "OcrManager: null bytes for page $pageIndex" }
                        failedPages++
                        updateStoredTask(chapterId) {
                            it.copy(
                                currentPage = pageIndex + 1,
                                totalPages = pageCount,
                                status = OcrQueueStatus.PROCESSING,
                            )
                        }
                        continue
                    }

                    val blocks = try {
                        val result = retryWithBackoff(times = 3) {
                            recognizePage(bytes = bytes, language = ocrLang)
                        }
                        result.mapNotNull { r ->
                            val bbox = r.tightBoundingBox
                            val xmin = bbox.x.toFloat().coerceIn(0f, 1f)
                            val ymin = bbox.y.toFloat().coerceIn(0f, 1f)
                            val xmax = (bbox.x + bbox.width).toFloat().coerceIn(0f, 1f)
                            val ymax = (bbox.y + bbox.height).toFloat().coerceIn(0f, 1f)

                            val lineGeometries = r.constituentBoxes?.map { lineBox ->
                                OcrLineGeometry(
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
                                OcrTextBlock(
                                    xmin = xmin,
                                    ymin = ymin,
                                    xmax = xmax,
                                    ymax = ymax,
                                    lines = r.text.split("\n"),
                                    vertical = r.forcedOrientation == "vertical",
                                    lineGeometries = lineGeometries,
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "OcrManager: OCR failed for page $pageIndex after retries" }
                        failedPages++
                        updateStoredTask(chapterId) {
                            it.copy(
                                currentPage = pageIndex + 1,
                                totalPages = pageCount,
                                status = OcrQueueStatus.PROCESSING,
                            )
                        }
                        continue
                    }

                    try {
                        ocrCacheManager.saveOcrBlocks(
                            manga = manga,
                            chapter = chapter,
                            source = source,
                            pageIndex = pageIndex,
                            blocks = blocks.map {
                                ChimahonOcrTextBlock(
                                    xmin = it.xmin,
                                    ymin = it.ymin,
                                    xmax = it.xmax,
                                    ymax = it.ymax,
                                    lines = it.lines,
                                    vertical = it.vertical,
                                    lineGeometries = it.lineGeometries?.map { lg ->
                                        chimahon.ocr.OcrLineGeometry(lg.xmin, lg.ymin, lg.xmax, lg.ymax, lg.rotation)
                                    }
                                )
                            },
                            language = ocrLang.bcp47,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "OcrManager: failed to cache OCR for page $pageIndex" }
                        failedPages++
                    }

                    updateStoredTask(chapterId) {
                        it.copy(currentPage = pageIndex + 1, totalPages = pageCount, status = OcrQueueStatus.PROCESSING)
                    }
                }

                // Pass 2: Automatic retry pass for any pages that failed during Pass 1
                if (failedPages > 0 && !stopRequested() && ocrStore.get(chapterId) != null) {
                    logcat(LogPriority.INFO) { "OcrManager: retrying $failedPages failed pages for chapter ${chapter.name}" }
                    for (pageIndex in 0 until pageCount) {
                        if (stopRequested() || ocrStore.get(chapterId) == null) break
                        if (ocrCacheManager.loadOcrBlocks(manga, chapter, source, pageIndex) != null) continue

                        val bytes = try {
                            imageProvider.getPageBytes(pageIndex)
                        } catch (_: Exception) { null } ?: continue

                        try {
                            val result = retryWithBackoff(times = 3) {
                                recognizePage(bytes = bytes, language = ocrLang)
                            }
                            val blocks = result.mapNotNull { r ->
                                val bbox = r.tightBoundingBox
                                val xmin = bbox.x.toFloat().coerceIn(0f, 1f)
                                val ymin = bbox.y.toFloat().coerceIn(0f, 1f)
                                val xmax = (bbox.x + bbox.width).toFloat().coerceIn(0f, 1f)
                                val ymax = (bbox.y + bbox.height).toFloat().coerceIn(0f, 1f)

                                val lineGeometries = r.constituentBoxes?.map { lineBox ->
                                    OcrLineGeometry(
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
                                    OcrTextBlock(
                                        xmin = xmin,
                                        ymin = ymin,
                                        xmax = xmax,
                                        ymax = ymax,
                                        lines = r.text.split("\n"),
                                        vertical = r.forcedOrientation == "vertical",
                                        lineGeometries = lineGeometries,
                                    )
                                }
                            }

                            ocrCacheManager.saveOcrBlocks(
                                manga = manga,
                                chapter = chapter,
                                source = source,
                                pageIndex = pageIndex,
                                blocks = blocks.map {
                                    ChimahonOcrTextBlock(
                                        xmin = it.xmin,
                                        ymin = it.ymin,
                                        xmax = it.xmax,
                                        ymax = it.ymax,
                                        lines = it.lines,
                                        vertical = it.vertical,
                                        lineGeometries = it.lineGeometries?.map { lg ->
                                            chimahon.ocr.OcrLineGeometry(lg.xmin, lg.ymin, lg.xmax, lg.ymax, lg.rotation)
                                        },
                                    )
                                },
                                language = ocrLang.bcp47,
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logcat(LogPriority.WARN, e) { "OcrManager: retry failed for page $pageIndex" }
                        }
                    }
                }
            } finally {
                imageProvider.close()
            }

            val cachedPageCount = countCachedPages(manga, chapter, source, pageCount)
            if (cachedPageCount == pageCount) {
                chapterRepository.update(ChapterUpdate(id = chapterId, isOcrReady = true))
                logcat { "OcrManager: completed chapter ${chapter.name}" }
                return OcrTaskResult.SUCCESS
            }

            chapterRepository.update(ChapterUpdate(id = chapterId, isOcrReady = false))
            updateStoredTask(chapterId) { it.copy(status = OcrQueueStatus.ERROR) }
            logcat(LogPriority.WARN) {
                "OcrManager: incomplete chapter ${chapter.name} cachedPages=$cachedPageCount failedPages=$failedPages totalPages=$pageCount"
            }
            return OcrTaskResult.ERROR
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "OcrManager: failed chapter ${chapter.name}" }
            updateStoredTask(chapterId) { it.copy(status = OcrQueueStatus.ERROR) }
            chapterRepository.update(ChapterUpdate(id = chapterId, isOcrReady = false))
            return OcrTaskResult.ERROR
        }
    }

    private suspend fun countCachedPages(
        manga: Manga,
        chapter: Chapter,
        source: Source,
        pageCount: Int,
    ): Int {
        var cachedPages = 0
        for (pageIndex in 0 until pageCount) {
            if (ocrCacheManager.loadOcrBlocks(manga, chapter, source, pageIndex) != null) {
                cachedPages++
            }
        }
        return cachedPages
    }

    private fun getChapterImageProvider(
        manga: Manga,
        chapter: Chapter,
        source: Source,
    ): ChapterImageProvider? {
        val chapterDir = downloadProvider.findChapterDir(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.ogTitle,
            source,
        )

        return when {
            chapterDir == null -> null
            chapterDir.isDirectory -> DirectoryImageProvider(chapterDir)
            chapterDir.name?.endsWith(".cbz") == true -> CbzImageProvider(context, chapterDir)
            else -> null
        }
    }

    private fun getLocalChapterImageProvider(
        manga: Manga,
        chapter: Chapter,
    ): ChapterImageProvider? {
        val splitUrl = chapter.url.split('/', limit = 2)
        if (splitUrl.size < 2) return null
        val (mangaDirName, chapterDirName) = splitUrl
        val storageManager: StorageManager = Injekt.get()
        val chapterFile = storageManager.getLocalSourceDirectory()
            ?.findFile(mangaDirName)
            ?.findFile(chapterDirName)
            ?: return null
        return when {
            chapterFile.isDirectory -> DirectoryImageProvider(chapterFile)
            chapterFile.name?.endsWith(".cbz") == true -> CbzImageProvider(context, chapterFile)
            else -> null
        }
    }

    data class OcrLineGeometry(
        val xmin: Float,
        val ymin: Float,
        val xmax: Float,
        val ymax: Float,
        val rotation: Float = 0f,
    )

    data class OcrTextBlock(
        val xmin: Float,
        val ymin: Float,
        val xmax: Float,
        val ymax: Float,
        val lines: List<String>,
        val vertical: Boolean = false,
        val lineGeometries: List<OcrLineGeometry>? = null,
    )
}

interface ChapterImageProvider : AutoCloseable {
    val pageCount: Int
    fun getPageBytes(pageIndex: Int): ByteArray?
}

class DirectoryImageProvider(private val chapterDir: UniFile) : ChapterImageProvider {
    private val imageFiles: List<UniFile> = chapterDir.listFiles()
        ?.filter { it.isFile && ImageUtil.isImage(it.name) { it.openInputStream() } }
        ?.sortedWith { f1, f2 -> f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(f2.name.orEmpty()) }
        ?: emptyList()

    override val pageCount: Int = imageFiles.size

    override fun getPageBytes(pageIndex: Int): ByteArray? {
        if (pageIndex !in imageFiles.indices) return null
        return try {
            imageFiles[pageIndex].openInputStream().use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {}
}

class CbzImageProvider(
    private val context: Context,
    private val cbzFile: UniFile,
) : ChapterImageProvider {
    private val archiveReader = cbzFile.archiveReader(context)
    private val imageEntries: List<String>

    init {
        imageEntries = archiveReader.useEntries { entries ->
            entries
                .filter { it.isFile && ImageUtil.isImage(it.name) { archiveReader.getInputStream(it.name)!! } }
                .sortedWith { e1, e2 ->
                    e1.name.compareToCaseInsensitiveNaturalOrder(e2.name)
                }
                .map { it.name }
                .toList()
        }
    }

    override val pageCount: Int = imageEntries.size

    override fun getPageBytes(pageIndex: Int): ByteArray? {
        if (pageIndex !in imageEntries.indices) return null
        return try {
            archiveReader.getInputStream(imageEntries[pageIndex])?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        archiveReader.close()
    }
}

internal enum class OcrTaskResult {
    SUCCESS,
    ERROR,
    CANCELLED,
    STOPPED,
}

data class OcrQueueItem(
    val manga: Manga,
    val chapter: Chapter,
    val progress: Float,
    val currentPage: Int,
    val totalPages: Int,
    val status: OcrQueueStatus = OcrQueueStatus.PENDING,
)

enum class OcrQueueStatus {
    PENDING,
    WAITING_DOWNLOAD,
    PROCESSING,
    COMPLETED,
    ERROR,
    CANCELLED,
}

suspend fun <T> retryWithBackoff(
    times: Int = 3,
    initialDelayMs: Long = 1000,
    maxDelayMs: Long = 10000,
    factor: Double = 2.0,
    block: suspend () -> T,
): T {
    require(times > 0)
    var currentDelay = initialDelayMs
    repeat(times - 1) {
        try {
            return block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logcat.logcat("OcrManager", LogPriority.WARN) { "Retry failed, retrying in ${currentDelay}ms: ${e.message}" }
        }
        delay(currentDelay)
        currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelayMs)
    }
    return block()
}
