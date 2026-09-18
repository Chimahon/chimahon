package chimahon.novel.sync.ttu

import android.content.Context
import chimahon.novel.data.BookStorage
import chimahon.novel.data.Bookmark
import chimahon.novel.data.Statistics
import tachiyomi.domain.novel.model.NovelChapterUpdate
import tachiyomi.domain.novel.model.isNovelReadComplete
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelHistoryRepository
import tachiyomi.domain.novel.repository.NovelReadingStatsRepository
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

data class TtuBookRef(
    val folder: String,
    val title: String,
)

data class TtuLocalState(
    val ref: TtuBookRef,
    val novelId: Long?,
    val chapterIndex: Int,
    val progress: Double,
    val characterCount: Int,
    val totalCharacters: Long,
    val chapterCount: Int,
    /** Per-chapter sizes for position mapping; null for virtual books. */
    val chapterSizes: List<Int>?,
    val lastModified: Long?,
    val statistics: List<Statistics>,
    val coverBytes: ByteArray?,
)

/**
 * Local side of TTU sync. Registered books live in the DB, unregistered
 * books in sidecars. The manager only talks through here.
 */
interface TtuLocalStore {
    suspend fun listBooks(): List<TtuBookRef>
    suspend fun read(ref: TtuBookRef): TtuLocalState?
    suspend fun writePosition(
        ref: TtuBookRef,
        chapterIndex: Int,
        progress: Double,
        characterCount: Int,
        lastModified: Long,
    ): Boolean
    suspend fun writeStatistics(ref: TtuBookRef, stats: List<Statistics>, replace: Boolean): Boolean
}

class TtuLocalStoreImpl(
    private val context: Context,
) : TtuLocalStore {

    private val appContext = context.applicationContext
    private val novelRepository: NovelRepository? by lazy {
        runCatching { Injekt.get<NovelRepository>() }.getOrNull()
    }
    private val chapterRepository: NovelChapterRepository? by lazy {
        runCatching { Injekt.get<NovelChapterRepository>() }.getOrNull()
    }
    private val historyRepository: NovelHistoryRepository? by lazy {
        runCatching { Injekt.get<NovelHistoryRepository>() }.getOrNull()
    }
    private val statsRepository: NovelReadingStatsRepository? by lazy {
        runCatching { Injekt.get<NovelReadingStatsRepository>() }.getOrNull()
    }

    override suspend fun listBooks(): List<TtuBookRef> {
        return runCatching { BookStorage.loadAllBooks(appContext) }
            .getOrNull().orEmpty()
            .filter { !it.title.isNullOrBlank() }
            .map { TtuBookRef(folder = it.id, title = it.title!!) }
    }

    override suspend fun read(ref: TtuBookRef): TtuLocalState? {
        val dir = BookStorage.getBookDirectory(appContext, ref.folder)
        val novelId = runCatching {
            novelRepository?.getNovelByLocalFolder(ref.folder)?.id
        }.getOrNull()
        if (novelId == null) return readSidecar(ref, dir)
        return readDb(ref, novelId, dir)
    }

    override suspend fun writePosition(
        ref: TtuBookRef,
        chapterIndex: Int,
        progress: Double,
        characterCount: Int,
        lastModified: Long,
    ): Boolean = runCatching {
        val novelId = novelRepository?.getNovelByLocalFolder(ref.folder)?.id
        if (novelId == null) {
            val dir = BookStorage.getBookDirectory(appContext, ref.folder)
            BookStorage.saveBookmark(
                Bookmark(
                    chapterIndex = chapterIndex,
                    progress = progress.coerceIn(0.0, 1.0),
                    characterCount = characterCount,
                    lastModified = lastModified,
                ),
                dir,
            )
            return true
        }
        val chapters = chapterRepository?.getChaptersByNovelId(novelId)
            ?.sortedBy { it.chapterNumber }.orEmpty()
        if (chapters.isEmpty()) return false
        val chapter = chapters.getOrNull(chapterIndex.coerceIn(0, chapters.size - 1)) ?: return false
        val safeProgress = progress.coerceIn(0.0, 1.0)
        chapterRepository?.update(
            NovelChapterUpdate(
                id = chapter.id,
                read = if (safeProgress.isNovelReadComplete() && !chapter.read) true else null,
                lastPageRead = maxOf(characterCount.toLong().coerceAtLeast(0), chapter.lastPageRead),
                progress = safeProgress,
            ),
        )
        historyRepository?.upsertHistory(
            chapterId = chapter.id,
            lastRead = lastModified,
            timeRead = 0L,
        )
        true
    }.getOrDefault(false)

    override suspend fun writeStatistics(
        ref: TtuBookRef,
        stats: List<Statistics>,
        replace: Boolean,
    ): Boolean = runCatching {
        val novelId = novelRepository?.getNovelByLocalFolder(ref.folder)?.id
        if (novelId == null) {
            val dir = BookStorage.getBookDirectory(appContext, ref.folder)
            BookStorage.saveStatistics(stats, dir)
            return true
        }
        val repo = statsRepository ?: return false
        if (replace) {
            // Absolute assignment through an additive upsert is impossible.
            repo.deleteByNovelId(novelId)
            stats.forEach { entry ->
                repo.upsert(
                    novelId = novelId,
                    dateKey = entry.dateKey,
                    charactersRead = entry.charactersRead,
                    readingTime = entry.readingTime,
                    minReadingSpeed = entry.minReadingSpeed,
                    altMinReadingSpeed = entry.altMinReadingSpeed,
                    lastReadingSpeed = entry.lastReadingSpeed,
                    maxReadingSpeed = entry.maxReadingSpeed,
                    completedBook = entry.completedBook,
                )
            }
            return true
        }
        // Merge mode: only the gap vs the current row (idempotent).
        val currentByDate = repo.getByNovelId(novelId).associateBy { it.dateKey }
        stats.forEach { entry ->
            val current = currentByDate[entry.dateKey]
            val charDelta = maxOf(0, entry.charactersRead - (current?.charactersRead ?: 0))
            val timeDelta = maxOf(0.0, entry.readingTime - (current?.readingTime ?: 0.0))
            if (charDelta == 0 && timeDelta == 0.0 && current != null) return@forEach
            repo.upsert(
                novelId = novelId,
                dateKey = entry.dateKey,
                charactersRead = charDelta,
                readingTime = timeDelta,
                minReadingSpeed = entry.minReadingSpeed,
                altMinReadingSpeed = entry.altMinReadingSpeed,
                lastReadingSpeed = entry.lastReadingSpeed,
                maxReadingSpeed = maxOf(entry.maxReadingSpeed, current?.maxReadingSpeed ?: 0),
                completedBook = current?.completedBook ?: entry.completedBook,
            )
        }
        true
    }.getOrDefault(false)

    private fun readSidecar(ref: TtuBookRef, dir: File): TtuLocalState? {
        if (!dir.isDirectory) return null
        val bookmark = BookStorage.loadBookmark(dir)
        val stats = BookStorage.loadStatistics(dir).orEmpty()
        val sizes = epubChapterSizes(dir)
        val totals = sizes?.let { it.sumOf { c -> c.toLong() } to it.size }
        return TtuLocalState(
            ref = ref,
            novelId = null,
            chapterIndex = bookmark?.chapterIndex ?: 0,
            progress = bookmark?.progress ?: 0.0,
            characterCount = bookmark?.characterCount ?: 0,
            totalCharacters = totals?.first ?: 0L,
            chapterCount = totals?.second ?: 0,
            chapterSizes = sizes,
            lastModified = bookmark?.lastModified,
            statistics = stats,
            coverBytes = epubCover(dir),
        )
    }

    private suspend fun readDb(ref: TtuBookRef, novelId: Long, dir: File): TtuLocalState? {
        val chapters = runCatching {
            chapterRepository?.getChaptersByNovelId(novelId)?.sortedBy { it.chapterNumber }
        }.getOrNull().orEmpty()
        val history = runCatching {
            historyRepository?.getLatestHistoryByNovelId(novelId)
        }.getOrNull()
        val index = history?.let { h -> chapters.indexOfFirst { it.id == h.chapterId }.takeIf { it >= 0 } } ?: 0
        val chapter = chapters.getOrNull(index)
        val stats = runCatching { statsRepository?.getByNovelId(novelId) }
            .getOrNull().orEmpty()
            .map {
                Statistics(
                    title = ref.title,
                    dateKey = it.dateKey,
                    charactersRead = it.charactersRead,
                    readingTime = it.readingTime,
                    minReadingSpeed = it.minReadingSpeed,
                    altMinReadingSpeed = it.altMinReadingSpeed,
                    lastReadingSpeed = it.lastReadingSpeed,
                    maxReadingSpeed = it.maxReadingSpeed,
                    lastStatisticModified = 0L,
                    completedBook = it.completedBook,
                )
            }
        val sizes = epubChapterSizes(dir)
        val totals = sizes?.let { it.sumOf { c -> c.toLong() } to it.size }
        return TtuLocalState(
            ref = ref,
            novelId = novelId,
            chapterIndex = index,
            progress = chapter?.progress?.coerceIn(0.0, 1.0) ?: 0.0,
            characterCount = chapter?.lastPageRead?.toInt() ?: 0,
            totalCharacters = totals?.first ?: 0L,
            chapterCount = totals?.second ?: chapters.size,
            chapterSizes = sizes,
            lastModified = history?.lastRead,
            statistics = stats,
            coverBytes = epubCover(dir),
        )
    }

    private fun epubChapterSizes(dir: File): List<Int>? = runCatching {
        val epub = BookStorage.loadEpub(dir)
        List(epub.linearSpineItems.size) { i -> epub.getChapterCharacters(i) }
    }.getOrNull()

    private fun epubCover(dir: File): ByteArray? = runCatching {
        val epub = BookStorage.loadEpub(dir)
        val coverPath = epub.coverPath ?: return null
        File(dir, coverPath).takeIf { it.isFile }?.readBytes()
    }.getOrNull()
}
