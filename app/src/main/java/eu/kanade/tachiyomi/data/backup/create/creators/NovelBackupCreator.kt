package eu.kanade.tachiyomi.data.backup.create.creators

import android.content.Context
import android.util.Log
import chimahon.novel.data.BookMetadata
import chimahon.novel.data.BookStorage
import chimahon.novel.data.NovelCategory
import chimahon.novel.manager.NovelSourceManager
import chimahon.novel.ui.detail.SourceChapterBookBuilder
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelHistory
import eu.kanade.tachiyomi.data.backup.models.BackupStatEntry
import eu.kanade.tachiyomi.data.backup.models.toBackupChapter
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.toSNNovel
import java.io.File
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelBackupCreator(
    private val context: Context,
    private val novelRepository: tachiyomi.domain.novel.repository.NovelRepository = Injekt.get(),
    private val novelChapterRepository: tachiyomi.domain.novel.repository.NovelChapterRepository = Injekt.get(),
    private val novelHistoryRepository: tachiyomi.domain.novel.repository.NovelHistoryRepository = Injekt.get(),
    private val novelReadingStatsRepository: tachiyomi.domain.novel.repository.NovelReadingStatsRepository = Injekt.get(),
    private val novelCategoryRepository: tachiyomi.domain.novel.repository.NovelCategoryRepository = Injekt.get(),
) {

    private val TAG = "NovelBackupCreator"

    suspend fun backupNovels(): List<BackupNovel> {
        val backupNovelsById = linkedMapOf<String, BackupNovel>()
        // Union of files present and DB favorites: rows win on conflict so
        // row-backed books never drop out once sidecars stop being written.
        // Keyed by dir name; sidecar content reads hit the same dir either way.
        val entries = linkedMapOf<String, BookEntry>()
        BookStorage.loadAllBooks(context).mapNotNull { metadata ->
            val bookDir = BookStorage.getBookDirectory(context, metadata.id)
            if (!bookDir.isDirectory) return@mapNotNull null
            BookEntry(bookDir, metadata)
        }.forEach { entries[it.directory.name] = it }
        runCatching {
            val manager = Injekt.get<NovelSourceManager>()
            novelRepository.getFavorites().forEach { novel ->
                val dirName = if (novel.isLocal || novel.source == Novel.LOCAL_SOURCE_ID) {
                    novel.localFolder?.takeIf { it.isNotBlank() }
                } else {
                    val source = runCatching { manager.getNovelSource(novel.source) }.getOrNull()
                        ?: return@forEach
                    SourceChapterBookBuilder.bookId(source, novel.toSNNovel())
                } ?: return@forEach
                val bookDir = BookStorage.getBookDirectory(context, dirName)
                entries[dirName] = BookEntry(bookDir, BookMetadata.fromRow(novel, dirName))
            }
        }

        for (entry in entries.values) {
            try {
                val backupNovel = createBackupNovel(entry)
                val existing = backupNovelsById[backupNovel.id]
                backupNovelsById[backupNovel.id] = if (existing == null) {
                    backupNovel
                } else {
                    mergeBackupNovel(existing, backupNovel)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to backup novel in ${entry.directory.name}", e)
            }
        }

        return backupNovelsById.values.toList()
    }

    suspend fun backupCategories(): List<BackupNovelCategory> {
        val categories = runCatching { novelCategoryRepository.getAll() }.getOrNull().orEmpty()
        return categories.map {
            BackupNovelCategory(
                id = it.id.toString(),
                name = it.name,
                order = it.order.toLong(),
                flags = it.flags
            )
        }
    }

    private fun normalizeCategoryIds(categoryIds: List<String>): List<String> {
        val distinctIds = categoryIds
            .filter { it.isNotBlank() }
            .distinct()

        return if (distinctIds.any { it != NovelCategory.UNCATEGORIZED_ID }) {
            distinctIds.filterNot { it == NovelCategory.UNCATEGORIZED_ID }
        } else {
            distinctIds
        }
    }

    private fun stableIdFor(metadata: BookMetadata): String {
        val title = (metadata.title ?: "").trim().lowercase()
        val author = (metadata.author ?: "").trim().lowercase()
        return if (title.isNotEmpty() || author.isNotEmpty()) {
            Hash.md5("$title|$author")
        } else {
            metadata.id
        }
    }

    private suspend fun createBackupNovel(entry: BookEntry): BackupNovel {
        val bookDir = entry.directory
        val metadata = entry.metadata
        val row = findNovelRow(entry.metadata)
        // Resume position, stats and categories come from the rows; the
        // sidecar is only a legacy fallback for never-registered books.
        val dbContent = row?.let { novel ->
            runCatching {
                val chapters = novelChapterRepository.getChaptersByNovelId(novel.id)
                    .sortedBy { it.chapterNumber }
                val latest = novelHistoryRepository.getHistoryByNovelId(novel.id)
                    .maxByOrNull { it.lastRead }
                val resumeChapter = chapters.firstOrNull { it.id == latest?.chapterId }
                val resumeIndex = chapters.indexOf(resumeChapter).takeIf { it >= 0 } ?: 0
                val stats = novelReadingStatsRepository.getByNovelId(novel.id).map {
                    BackupStatEntry(
                        dateKey = it.dateKey,
                        charactersRead = it.charactersRead,
                        readingTime = it.readingTime,
                        minReadingSpeed = it.minReadingSpeed,
                        altMinReadingSpeed = it.altMinReadingSpeed,
                        lastReadingSpeed = it.lastReadingSpeed,
                        maxReadingSpeed = it.maxReadingSpeed,
                        lastStatisticModified = 0L,
                    )
                }
                val categoryIds = novelCategoryRepository.getByNovelId(novel.id).map {
                    if (it.id == 0L) NovelCategory.UNCATEGORIZED_ID else it.id.toString()
                }
                DbBackupContent(
                    chapterIndex = resumeIndex,
                    progress = resumeChapter?.progress ?: 0.0,
                    characterCount = resumeChapter?.lastPageRead?.toInt() ?: 0,
                    lastModified = latest?.lastRead ?: 0L,
                    stats = stats,
                    categoryIds = categoryIds,
                )
            }.getOrNull()
        }
        val bookmark = BookStorage.loadBookmark(bookDir)
        val stats = BookStorage.loadStatistics(bookDir)
        val stableId = stableIdFor(metadata)

        val backupStats = dbContent?.stats ?: stats?.map {
            BackupStatEntry(
                dateKey = it.dateKey,
                charactersRead = it.charactersRead,
                readingTime = it.readingTime,
                minReadingSpeed = it.minReadingSpeed,
                altMinReadingSpeed = it.altMinReadingSpeed,
                lastReadingSpeed = it.lastReadingSpeed,
                maxReadingSpeed = it.maxReadingSpeed,
                lastStatisticModified = it.lastStatisticModified,
            )
        } ?: emptyList()

        return BackupNovel(
            id = stableId,
            title = metadata.title ?: "",
            author = metadata.author,
            cover = metadata.cover,
            chapterIndex = dbContent?.chapterIndex ?: bookmark?.chapterIndex ?: 0,
            progress = dbContent?.progress ?: bookmark?.progress ?: 0.0,
            characterCount = dbContent?.characterCount ?: bookmark?.characterCount ?: 0,
            lastModified = dbContent?.lastModified ?: bookmark?.lastModified ?: 0L,
            stats = mergeBackupStats(backupStats),
            categoryIds = normalizeCategoryIds(
                dbContent?.categoryIds ?: metadata.categoryIds
            ),
            lang = metadata.lang,
            chapters = backupChapters(entry),
            history = backupHistory(entry),
        )
    }

    private data class DbBackupContent(
        val chapterIndex: Int,
        val progress: Double,
        val characterCount: Int,
        val lastModified: Long,
        val stats: List<BackupStatEntry>,
        val categoryIds: List<String>,
    )

    /** DB-backed chapters/history (manga payload parity); empty when never registered. */
    private suspend fun backupChapters(entry: BookEntry): List<BackupChapter> {
        return try {
            val novel = findNovelRow(entry.metadata) ?: return emptyList()
            novelChapterRepository.getChaptersByNovelId(novel.id)
                .map { it.toBackupChapter() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun backupHistory(entry: BookEntry): List<BackupNovelHistory> {
        return try {
            val novel = findNovelRow(entry.metadata) ?: return emptyList()
            val urlById = novelChapterRepository.getChaptersByNovelId(novel.id)
                .associate { it.id to it.url }
            novelHistoryRepository.getHistoryByNovelId(novel.id).mapNotNull { h ->
                val url = urlById[h.chapterId] ?: return@mapNotNull null
                BackupNovelHistory(
                    chapterUrl = url,
                    lastRead = h.lastRead,
                    timeRead = h.timeRead,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun findNovelRow(metadata: BookMetadata): tachiyomi.domain.novel.model.Novel? {
        return runCatching {
            val sourceId = metadata.novelSourceId
            val novelUrl = metadata.novelUrl
            if (sourceId != null && novelUrl != null) {
                novelRepository.getNovelByUrlAndSourceId(novelUrl, sourceId)
            } else {
                novelRepository.getNovelByUrlAndSourceId(
                    "local://${metadata.id}",
                    tachiyomi.domain.novel.model.Novel.LOCAL_SOURCE_ID,
                )
            }
        }.getOrNull()
    }

    private fun mergeBackupNovel(first: BackupNovel, second: BackupNovel): BackupNovel {
        val latest = if (first.lastModified >= second.lastModified) first else second
        val fallback = if (latest == first) second else first

        return latest.copy(
            id = first.id,
            author = latest.author ?: fallback.author,
            cover = latest.cover ?: fallback.cover,
            stats = mergeBackupStats(first.stats + second.stats),
            categoryIds = normalizeCategoryIds(first.categoryIds + second.categoryIds),
            lang = latest.lang ?: fallback.lang,
        )
    }

    private fun mergeBackupStats(stats: List<BackupStatEntry>): List<BackupStatEntry> {
        return stats
            .groupBy { it.dateKey }
            .map { (_, entries) ->
                entries.reduce { latest, candidate ->
                    if (candidate.lastStatisticModified > latest.lastStatisticModified) candidate else latest
                }
            }
    }

    private data class BookEntry(
        val directory: File,
        val metadata: BookMetadata,
    )

}
