package chimahon.novel.download

import android.content.Context
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.ContentItem
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    // Thread-safe set: read on Main (composition), mutated on IO. No lock needed.
    private val downloadedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
        .also { loadPersisted() }
    private val persistFile by lazy { java.io.File(context.filesDir, "novel_downloads.json") }

    private fun loadPersisted() {
        try {
            if (!persistFile.exists()) return
            val json = persistFile.readText()
            kotlinx.serialization.json.Json.decodeFromString<Set<Long>>(json).let { downloadedIds.addAll(it) }
        } catch (_: Exception) {}
    }

    private fun persist() {
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
            val novelDir = provider.findNovelDir(novel.title, source)
            novelDir?.delete()
            val dbChapters = runCatching {
                Injekt.get<NovelChapterRepository>().getChaptersByNovelId(novel.id)
            }.getOrDefault(emptyList())
            dbChapters.forEach {
                downloadedIds.remove(it.id)
            }
            persist()
        }
    }

    fun deleteChapter(novel: Novel, chapter: NovelChapter, source: NovelSource) {
        scope.launch {
            val chapterDir = provider.findChapterDir(chapter.name, novel.title, source)
            chapterDir?.delete()
            downloadedIds.remove(chapter.id)
            persist()
        }
    }

    fun downloadChapters(
        novel: Novel,
        chapters: List<SNChapter>,
        source: NovelSource,
        snNovel: SNNovel,
    ) {
        scope.launch {
            val dbChapters = try {
                Injekt.get<NovelChapterRepository>().getChaptersByNovelId(novel.id)
            } catch (_: Exception) { emptyList() }
            val dbByUrl = dbChapters.associateBy { it.url }
            // DB rows only: hash fallbacks create phantom ids that can never match.
            val toDownload = chapters.mapNotNull { ch ->
                dbByUrl[ch.url]?.takeIf { !isDownloaded(it.id) }?.let { ch to it.id }
            }
            if (toDownload.isEmpty()) return@launch
            // Sequential on purpose: JS plugin runtimes are single-threaded,
            // so parallel fetches would corrupt them.
            toDownload.forEach { (chapter, dbId) ->
                val download = NovelDownload(dbId, novel.id, NovelDownload.State.QUEUED)
                _queueState.value = _queueState.value + download
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
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Novel download failed: ${novel.title} / ${chapter.name}" }
                    updateState(download.copy(state = NovelDownload.State.ERROR))
                }
            }
        }
    }

    private fun updateState(download: NovelDownload) {
        _queueState.value = _queueState.value.map { if (it.chapterId == download.chapterId) download else it }
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
