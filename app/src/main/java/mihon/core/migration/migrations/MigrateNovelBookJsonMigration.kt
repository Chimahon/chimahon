package mihon.core.migration.migrations

import android.app.Application
import chimahon.novel.interactor.SyncNovelChapters
import chimahon.novel.data.BookStorage
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelChapterUpdate
import tachiyomi.domain.novel.model.NovelUpdate
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelHistoryRepository
import tachiyomi.domain.novel.repository.NovelReadingStatsRepository
import tachiyomi.domain.novel.repository.NovelRepository
import java.io.File

class MigrateNovelBookJsonMigration : Migration {
    override val version: Float = 85f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        val context = migrationContext.get<Application>() ?: return@withIOContext false
        val novels = migrationContext.get<NovelRepository>() ?: return@withIOContext false
        val chapters = migrationContext.get<NovelChapterRepository>() ?: return@withIOContext false
        val history = migrationContext.get<NovelHistoryRepository>() ?: return@withIOContext false
        val stats = migrationContext.get<NovelReadingStatsRepository>() ?: return@withIOContext false
        val booksDir = BookStorage.getBooksDirectory(context)
        if (!booksDir.isDirectory) return@withIOContext true
        val sync = SyncNovelChapters()
        booksDir.listFiles()
            ?.filter { it.isDirectory && !it.name.endsWith(".bak") && !it.name.endsWith(".tmp") }
            .orEmpty()
            .forEach { dir ->
                runCatching { migrateBookDir(dir, novels, chapters, history, stats, sync) }
                    .onFailure { logcat(LogPriority.WARN, it) { "Novel JSON migration skipped ${dir.name}" } }
            }
        true
    }

    private suspend fun migrateBookDir(
        dir: File,
        novels: NovelRepository,
        chapters: NovelChapterRepository,
        history: NovelHistoryRepository,
        stats: NovelReadingStatsRepository,
        sync: SyncNovelChapters,
    ) {
        val metadata = BookStorage.loadMetadata(dir) ?: return
        // Local/imported books are owned by the local home (v5), not this migration.
        val sourceId = metadata.novelSourceId ?: return
        val novelUrl = metadata.novelUrl ?: return
        val now = System.currentTimeMillis()

        val existing = novels.getNovelByUrlAndSourceId(novelUrl, sourceId)
        val novelId = if (existing == null) {
            novels.insert(
                Novel.fromSourceNovel(
                    SNNovel(
                        url = novelUrl,
                        title = metadata.title.orEmpty(),
                        author = metadata.author,
                        thumbnail_url = metadata.cover,
                    ),
                    sourceId,
                ).copy(
                    favorite = false,
                    dateAdded = metadata.dateAdded,
                    lastUpdate = now,
                    initialized = true,
                ),
            )
        } else {
            existing.id
        }

        val sidecar = readSidecarChapters(dir)
        if (sidecar.isNotEmpty()) {
            val sourceChapters = sidecar.map { (url, name, number) ->
                SNChapter(url = url, name = name, chapter_number = number)
            }
            sync.await(novelId, sourceChapters)
            novels.update(NovelUpdate(id = novelId, totalChapters = sidecar.size))
        }

        val bookmark = BookStorage.loadBookmark(dir)
        if (bookmark != null) {
            // chapterNumber order: bookmark indices are written against the
            // number-sorted spine everywhere (open, sync, loader).
            val ordered = chapters.getChaptersByNovelId(novelId).sortedBy { it.chapterNumber }
            ordered.getOrNull(bookmark.chapterIndex)?.let { target ->
                history.upsertHistory(
                    chapterId = target.id,
                    lastRead = bookmark.lastModified ?: now,
                    timeRead = 0L,
                )
                if (bookmark.progress >= READ_COMPLETE_EPSILON) {
                    chapters.update(NovelChapterUpdate(id = target.id, read = true, lastPageRead = 0))
                }
            }
        }

        BookStorage.loadStatistics(dir)?.forEach { entry ->
            runCatching {
                stats.upsert(
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
        }
    }

    private fun readSidecarChapters(dir: File): List<Triple<String, String, Float>> {
        val file = File(dir, CHAPTER_LIST_FILE)
        if (!file.isFile) return emptyList()
        return try {
            json.parseToJsonElement(file.readText()).jsonArray.mapNotNull { element ->
                val obj = element.jsonObject
                val url = obj["url"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
                val number = obj["number"]?.jsonPrimitive?.float ?: 0f
                Triple(url, name, number)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val CHAPTER_LIST_FILE = "source_chapters.json"
        private const val READ_COMPLETE_EPSILON = 0.999
    }
}
