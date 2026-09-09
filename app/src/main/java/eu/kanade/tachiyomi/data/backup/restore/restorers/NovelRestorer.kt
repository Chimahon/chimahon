package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import chimahon.novel.interactor.RegisterLocalNovelHome
import chimahon.novel.data.BookStorage
import chimahon.novel.data.NovelCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.models.toNovelChapter
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelUpdate
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelHistoryRepository
import tachiyomi.domain.novel.repository.NovelReadingStatsRepository
import tachiyomi.domain.novel.repository.NovelCategoryRepository
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelRestorer(
    private val context: Context,
) {

    suspend fun restore(
        backupNovels: List<BackupNovel>,
        categoryIdMap: Map<String, String> = emptyMap(),
    ) {
        for (backupNovel in backupNovels) {
            restoreNovel(backupNovel, categoryIdMap)
        }
    }

    suspend fun restoreNovel(
        backupNovel: BackupNovel,
        categoryIdMap: Map<String, String> = emptyMap(),
    ) {
        // DB category longs for the payload string ids ("default" and legacy
        // uuids that never mapped simply drop to the default bucket).
        val dbCategoryIds = normalizeCategoryIds(
            backupNovel.categoryIds.map { categoryIdMap[it] ?: it },
        ).mapNotNull {
            if (it == NovelCategory.UNCATEGORIZED_ID) null else it.toLongOrNull()
        }

        // Rows first, always: files present or not, the payload wins into
        // the DB. EPUB content itself is never in backups.
        restoreNovelRow(backupNovel, dbCategoryIds)

        // Files present (Title folder — the same dir restoreNovelRow links via
        // localFolder): register the home now so the spine syncs straight
        // away instead of waiting for the next library scan. Readability is
        // content-or-epub (epub-only public dirs extract on demand here).
        // No files: rows alone are enough.
        val folder = BookStorage.sanitizeFileName(backupNovel.title.ifBlank { "Unknown" })
        val readableDir = chimahon.novel.source.LocalNovelFiles.ensureReadableDir(context, folder)
        if (readableDir != null) {
            runCatching {
                RegisterLocalNovelHome().register(folder)
            }.onFailure {
                logcat(LogPriority.WARN, it) { "Novel restore home registration failed for $folder" }
            }
        }
    }

    private suspend fun restoreNovelRow(backupNovel: BackupNovel, dbCategoryIds: List<Long>) {
        val novelRepository: NovelRepository = Injekt.get()
        val novelChapterRepository: NovelChapterRepository = Injekt.get()
        val novelHistoryRepository: NovelHistoryRepository = Injekt.get()
        val novelReadingStatsRepository: NovelReadingStatsRepository = Injekt.get()

        val folder = BookStorage.sanitizeFileName(backupNovel.title.ifBlank { "Unknown" })
        val url = "local://$folder"
        val now = System.currentTimeMillis()
        val existing = novelRepository.getNovelByUrlAndSourceId(
            url,
            Novel.LOCAL_SOURCE_ID,
        ) ?: novelRepository.getNovelsBySourceId(Novel.LOCAL_SOURCE_ID)
            .firstOrNull {
                it.title.equals(backupNovel.title, ignoreCase = true) &&
                    it.author.orEmpty() == backupNovel.author.orEmpty()
            }
        val novelId = if (existing == null) {
            novelRepository.insert(
                Novel.create().copy(
                    source = Novel.LOCAL_SOURCE_ID,
                    url = url,
                    title = backupNovel.title,
                    author = backupNovel.author,
                    thumbnailUrl = backupNovel.cover,
                    favorite = true,
                    dateAdded = now,
                    lastUpdate = now,
                    initialized = true,
                    isLocal = true,
                    localFolder = folder,
                ),
            )
        } else {
            novelRepository.update(
                NovelUpdate(
                    id = existing.id,
                    title = backupNovel.title,
                    author = backupNovel.author,
                    thumbnailUrl = backupNovel.cover,
                ),
            )
            existing.id
        }
        if (dbCategoryIds.isNotEmpty()) {
            runCatching {
                Injekt.get<chimahon.novel.interactor.SetNovelCategories>().await(novelId, dbCategoryIds)
            }
        }

        if (backupNovel.chapters.isNotEmpty() &&
            novelChapterRepository.getChaptersByNovelId(novelId).isEmpty()
        ) {
            novelChapterRepository.insertAll(
                backupNovel.chapters.map { it.toNovelChapter(novelId) },
            )
            novelRepository.update(
                NovelUpdate(
                    id = novelId,
                    totalChapters = backupNovel.chapters.size,
                ),
            )
        }

        val idByUrl = novelChapterRepository.getChaptersByNovelId(novelId)
            .associate { it.url to it.id }
        backupNovel.history.forEach { entry ->
            val chapterId = idByUrl[entry.chapterUrl] ?: return@forEach
            runCatching {
                val current = novelHistoryRepository.getHistoryByChapterId(chapterId)
                if (current == null || entry.lastRead > current.lastRead) {
                    // The upsert accumulates, so restore only the delta —
                    // re-restoring must not double-count time_read.
                    val delta = maxOf(0L, entry.timeRead - (current?.timeRead ?: 0L))
                    novelHistoryRepository.upsertHistory(
                        chapterId = chapterId,
                        lastRead = entry.lastRead,
                        timeRead = delta,
                    )
                }
            }.onFailure {
                logcat(LogPriority.WARN, it) { "Novel restore history failed for ${backupNovel.title}" }
            }
        }

        if (backupNovel.lastModified > 0) {
            runCatching {
                            // chapterNumber order: bookmark indices are written against the
                            // number-sorted spine everywhere (open, sync, loader).
                val ordered = novelChapterRepository.getChaptersByNovelId(novelId)
                    .sortedBy { it.chapterNumber }
                val target = ordered.getOrNull(backupNovel.chapterIndex) ?: return@runCatching
                if (novelHistoryRepository.getHistoryByChapterId(target.id) == null) {
                    novelHistoryRepository.upsertHistory(
                        chapterId = target.id,
                        lastRead = backupNovel.lastModified,
                        timeRead = 0L,
                    )
                }
            }.onFailure {
                logcat(LogPriority.WARN, it) { "Novel restore bookmark failed for ${backupNovel.title}" }
            }
        }

        backupNovel.stats.forEach { stat ->
            runCatching {
                novelReadingStatsRepository.upsert(
                    novelId = novelId,
                    dateKey = stat.dateKey,
                    charactersRead = stat.charactersRead,
                    readingTime = stat.readingTime,
                    minReadingSpeed = stat.minReadingSpeed,
                    altMinReadingSpeed = stat.altMinReadingSpeed,
                    lastReadingSpeed = stat.lastReadingSpeed,
                    maxReadingSpeed = stat.maxReadingSpeed,
                    completedBook = null,
                )
            }.onFailure {
                logcat(LogPriority.WARN, it) { "Novel restore stats failed for ${backupNovel.title}" }
            }
        }
    }

    suspend fun restoreCategories(backupCategories: List<BackupNovelCategory>): Map<String, String> {
        val novelCategoryRepository: NovelCategoryRepository = Injekt.get()
        val categoryIdMap = mutableMapOf(
            NovelCategory.UNCATEGORIZED_ID to NovelCategory.UNCATEGORIZED_ID,
        )
        if (backupCategories.isEmpty()) return categoryIdMap

        val currentCategories = runCatching { novelCategoryRepository.getAll() }.getOrNull().orEmpty()
        backupCategories.forEach { backupCategory ->
            val existing = currentCategories.firstOrNull {
                it.id.toString() == backupCategory.id || categoryKey(it.name) == categoryKey(backupCategory.name)
            }
            if (existing == null) {
                val id = runCatching {
                    novelCategoryRepository.create(
                        backupCategory.name,
                        backupCategory.order.toInt(),
                        false,
                    )
                }.getOrNull() ?: return@forEach
                categoryIdMap[backupCategory.id] = id.toString()
            } else {
                categoryIdMap[backupCategory.id] = existing.id.toString()
                runCatching {
                    novelCategoryRepository.update(
                        existing.copy(
                            name = backupCategory.name,
                            order = backupCategory.order.toInt(),
                            flags = backupCategory.flags,
                        )
                    )
                }
            }
        }

        return categoryIdMap
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

    private fun categoryKey(name: String): String {
        return name.trim().lowercase()
    }
}
