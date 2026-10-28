package chimahon.novel.download

import android.content.Context
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.ContentItem
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelChapter
import tachiyomi.domain.novel.repository.NovelChapterRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class NovelDownload(
    val chapterId: Long,
    val novelId: Long,
    val state: State,
    val progress: Int = 0,
) {
    enum class State { NOT_DOWNLOADED, QUEUED, DOWNLOADING, DOWNLOADED, ERROR }
}

class NovelDownloadManager(
    private val context: Context,
    private val provider: NovelDownloadProvider = Injekt.get(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _queueState = MutableStateFlow<List<NovelDownload>>(emptyList())
    val queueState: StateFlow<List<NovelDownload>> = _queueState.asStateFlow()

    data class Error(val novelId: Long, val message: String)
    private val _errors = MutableSharedFlow<Error>(extraBufferCapacity = 16)
    val errors = _errors.asSharedFlow()
    private val queueMutex = Mutex()
    private val chapterJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    private var workerJob: Job? = null
    private val persistLock = Any()

    // Thread-safe set: read on Main (composition), mutated on IO. No lock needed.
    private val downloadedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val persistFile by lazy { java.io.File(context.filesDir, "novel_downloads.json") }

    init {
        loadPersisted()
    }

    private fun loadPersisted() {
        try {
            if (!persistFile.exists()) return
            val json = persistFile.readText()
            kotlinx.serialization.json.Json.decodeFromString<Set<Long>>(json).let { downloadedIds.addAll(it) }
        } catch (_: Exception) {}
    }

    private fun persist() = synchronized(persistLock) {
        try {
            persistFile.writeText(kotlinx.serialization.json.Json.encodeToString(downloadedIds.toSet()))
        } catch (_: Exception) {}
    }

    fun isDownloaded(chapterId: Long): Boolean = downloadedIds.contains(chapterId)

    fun getDownloadedCount(novelId: Long, chapters: List<NovelChapter>): Int {
        return chapters.count { isDownloaded(it.id) }
    }

    fun deleteNovel(novel: Novel, source: NovelSource) {
        scope.launch {
            queueMutex.withLock {
                try {
                    _queueState.value.filter { it.novelId == novel.id }.forEach {
                        chapterJobs[it.chapterId]?.cancelAndJoin()
                    }
                    val dbChapters = Injekt.get<NovelChapterRepository>().getChaptersByNovelId(novel.id)
                    val novelDir = provider.findNovelDir(novel.title, source)
                    check(novelDir == null || novelDir.delete()) { "Could not delete novel downloads" }
                    dbChapters.forEach { downloadedIds.remove(it.id) }
                    persist()
                    _queueState.update { queue ->
                        queue.filterNot { it.novelId == novel.id } + dbChapters.map {
                            NovelDownload(it.id, novel.id, NovelDownload.State.NOT_DOWNLOADED)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _queueState.update { queue ->
                        queue.map {
                            if (it.novelId == novel.id && !downloadedIds.contains(it.chapterId)) {
                                it.copy(state = NovelDownload.State.NOT_DOWNLOADED)
                            } else {
                                it
                            }
                        }
                    }
                    reportError(novel.id, e)
                }
            }
        }
    }

    fun deleteChapter(novel: Novel, chapter: NovelChapter, source: NovelSource) {
        scope.launch {
            queueMutex.withLock {
                try {
                    chapterJobs[chapter.id]?.cancelAndJoin()
                    val chapterDir = provider.findChapterDir(chapter.name, novel.title, source)
                    check(chapterDir == null || chapterDir.delete()) { "Could not delete chapter download" }
                    downloadedIds.remove(chapter.id)
                    persist()
                    // Publish the deletion too: the chapter row observes queueState.
                    updateState(NovelDownload(chapter.id, novel.id, NovelDownload.State.NOT_DOWNLOADED))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    updateState(
                        NovelDownload(
                            chapter.id,
                            novel.id,
                            if (isDownloaded(chapter.id)) NovelDownload.State.DOWNLOADED else NovelDownload.State.NOT_DOWNLOADED,
                        ),
                    )
                    reportError(novel.id, e)
                }
            }
        }
    }

    fun cancelChapter(chapterId: Long) {
        scope.launch {
            queueMutex.withLock {
                chapterJobs[chapterId]?.cancel()
                _queueState.update { queue -> queue.filterNot { it.chapterId == chapterId } }
            }
        }
    }

    fun startNow(chapterId: Long) {
        scope.launch {
            queueMutex.withLock {
                prioritize(chapterId)
                startWorker()
            }
        }
    }

    private fun prioritize(chapterId: Long) {
        _queueState.update { queue ->
            val item = queue.firstOrNull { it.chapterId == chapterId && it.state == NovelDownload.State.QUEUED }
            if (item == null) queue else listOf(item) + queue.filterNot { it.chapterId == chapterId }
        }
    }

    // Called with queueMutex held. One worker keeps all JS chapter fetches sequential.
    private fun startWorker() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            while (true) {
                val next = queueMutex.withLock {
                    _queueState.value.firstOrNull { it.state == NovelDownload.State.QUEUED }
                        ?.let { chapterJobs[it.chapterId] }
                        ?.also { it.start() }
                        ?: run { workerJob = null; null }
                } ?: break
                next.join()
            }
        }
    }

    fun downloadChapters(
        novel: Novel,
        chapters: List<SNChapter>,
        source: NovelSource,
        snNovel: SNNovel,
        startNow: Boolean = false,
    ) {
        scope.launch {
            val dbChapters = try {
                Injekt.get<NovelChapterRepository>().getChaptersByNovelId(novel.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportError(novel.id, e)
                return@launch
            }
            val dbByUrl = dbChapters.associateBy { it.url }
            // DB rows only: hash fallbacks create phantom ids that can never match.
            val toDownload = chapters.mapNotNull { ch ->
                dbByUrl[ch.url]?.takeIf { !isDownloaded(it.id) }?.let { ch to it.id }
            }
            if (toDownload.isEmpty()) return@launch
            queueMutex.withLock {
                toDownload.forEach { (chapter, dbId) ->
                    if (isDownloaded(dbId) || chapterJobs.containsKey(dbId)) return@forEach
                    val download = NovelDownload(dbId, novel.id, NovelDownload.State.QUEUED)
                    val job = scope.launch(start = CoroutineStart.LAZY) {
                        updateState(download.copy(state = NovelDownload.State.DOWNLOADING))
                        try {
                            val snChapter = SNChapter(
                                url = chapter.url,
                                name = chapter.name,
                                scanlator = chapter.scanlator,
                                chapter_number = chapter.chapter_number,
                                date_upload = chapter.date_upload,
                            )
                            val content = source.getChapterContent(snChapter)
                            ensureActive()
                            val rawContent = when (content) {
                                is ChapterContent.Text -> content.text
                                is ChapterContent.Html -> content.html
                                is ChapterContent.Images -> content.urls.joinToString("\n") { "<img src=\"$it\" />" }
                                is ChapterContent.Mixed -> content.items.joinToString("\n") { item ->
                                    when (item) {
                                        is ContentItem.Html -> item.html
                                        is ContentItem.Text -> item.text
                                        is ContentItem.Image -> "<img src=\"${item.url}\" />"
                                        is ContentItem.Images -> item.urls.joinToString("\n") { "<img src=\"$it\" />" }
                                        is ContentItem.Mixed -> ""
                                    }
                                }
                            }
                            // Atomic write through the shared provider writer (manga
                            // tmp+rename): a crash never leaves torn content.
                            val chapterDir = provider.getChapterDir(chapter.name, novel.title, source)
                            if (!provider.writeDownloadContent(
                                    chapterDir,
                                    "content.html",
                                    rawContent,
                                    content is ChapterContent.Text,
                                )
                            ) {
                                throw IllegalStateException("Cannot write download file")
                            }
                            downloadedIds.add(dbId)
                            persist()
                            updateState(download.copy(state = NovelDownload.State.DOWNLOADED, progress = 100))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logcat(LogPriority.WARN, e) { "Novel download failed: ${novel.title} / ${chapter.name}" }
                            updateState(download.copy(state = NovelDownload.State.ERROR))
                            reportError(novel.id, e)
                        }
                    }
                    chapterJobs[dbId] = job
                    job.invokeOnCompletion { chapterJobs.remove(dbId, job) }
                    _queueState.update { queue -> queue.filterNot { it.chapterId == dbId } + download }
                }
                if (startNow) toDownload.asReversed().forEach { (_, id) -> prioritize(id) }
                startWorker()
            }
        }
    }

    private fun reportError(novelId: Long, error: Exception) {
        _errors.tryEmit(Error(novelId, error.message ?: error.toString()))
    }

    private fun updateState(download: NovelDownload) {
        _queueState.update { queue ->
            if (queue.any { it.chapterId == download.chapterId }) {
                queue.map { if (it.chapterId == download.chapterId) download else it }
            } else {
                queue + download
            }
        }
    }

    fun getDownloadState(chapterId: Long): NovelDownload.State {
        return _queueState.value.find { it.chapterId == chapterId }?.state
            ?: if (isDownloaded(chapterId)) NovelDownload.State.DOWNLOADED else NovelDownload.State.NOT_DOWNLOADED
    }

    companion object {
        fun getInstance(context: Context): NovelDownloadManager {
            return Injekt.get()
        }
    }
}
