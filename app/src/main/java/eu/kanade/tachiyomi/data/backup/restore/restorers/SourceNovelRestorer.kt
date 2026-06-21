package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupSourceNovel
import eu.kanade.tachiyomi.data.backup.models.toNovelChapter
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelChapter
import tachiyomi.domain.novel.model.NovelChapterUpdate
import tachiyomi.domain.novel.model.NovelUpdate
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.max
import kotlin.math.min

class SourceNovelRestorer(
    private val isSync: Boolean = false,
    private val novelRepository: NovelRepository = Injekt.get(),
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
) {

    suspend fun sortByNew(backupNovels: List<BackupSourceNovel>): List<BackupSourceNovel> {
        val existingKeys = novelRepository.getAll()
            .map { it.source to it.url }
            .toSet()

        return backupNovels
            .sortedWith(
                compareBy<BackupSourceNovel> { (it.source to it.url) in existingKeys }
                    .then(compareByDescending { it.lastModifiedAt }),
            )
    }

    suspend fun restore(backupNovel: BackupSourceNovel) {
        val dbNovel = novelRepository.getNovelByUrlAndSourceId(backupNovel.url, backupNovel.source)
        val restoredNovel = if (dbNovel == null) {
            restoreNewNovel(backupNovel.getNovelImpl())
        } else {
            restoreExistingNovel(backupNovel.getNovelImpl(), dbNovel)
        }
        restoreChapters(restoredNovel, backupNovel.chapters)
    }

    private suspend fun restoreNewNovel(novel: Novel): Novel {
        val id = novelRepository.insert(novel.copy(favorite = true))
        return novelRepository.getNovelById(id)
    }

    private suspend fun restoreExistingNovel(backupNovel: Novel, dbNovel: Novel): Novel {
        val merged = if (backupNovel.version > dbNovel.version) {
            dbNovel.copyFrom(backupNovel)
        } else {
            backupNovel.copyFrom(dbNovel).copy(id = dbNovel.id)
        }
        novelRepository.update(merged.toUpdate())
        return novelRepository.getNovelById(dbNovel.id)
    }

    private fun Novel.copyFrom(newer: Novel): Novel {
        return copy(
            url = newer.url,
            source = newer.source,
            title = newer.title,
            artist = newer.artist,
            author = newer.author,
            description = newer.description,
            genre = newer.genre,
            status = newer.status,
            thumbnailUrl = newer.thumbnailUrl,
            favorite = favorite || newer.favorite,
            lastUpdate = max(lastUpdate, newer.lastUpdate),
            nextUpdate = newer.nextUpdate,
            fetchInterval = newer.fetchInterval,
            dateAdded = minNonZero(dateAdded, newer.dateAdded),
            coverLastModified = max(coverLastModified, newer.coverLastModified),
            initialized = initialized || newer.initialized,
            totalChapters = max(totalChapters, newer.totalChapters),
            version = newer.version,
            notes = newer.notes.ifBlank { notes },
        )
    }

    private fun Novel.toUpdate(): NovelUpdate {
        return NovelUpdate(
            id = id,
            source = source,
            favorite = favorite,
            lastUpdate = lastUpdate,
            nextUpdate = nextUpdate,
            fetchInterval = fetchInterval,
            dateAdded = dateAdded,
            coverLastModified = coverLastModified,
            url = url,
            title = title,
            author = author,
            artist = artist,
            description = description,
            genre = genre,
            status = status,
            thumbnailUrl = thumbnailUrl,
            initialized = initialized,
            totalChapters = totalChapters,
            version = version,
            notes = notes,
        )
    }

    private suspend fun restoreChapters(novel: Novel, backupChapters: List<eu.kanade.tachiyomi.data.backup.models.BackupChapter>) {
        val dbChaptersByUrl = novelChapterRepository.getChaptersByNovelId(novel.id)
            .associateBy { it.url }

        val newChapters = mutableListOf<NovelChapter>()
        val updatedChapters = mutableListOf<NovelChapterUpdate>()

        backupChapters.forEach { backupChapter ->
            val chapter = backupChapter.toNovelChapter(novel.id)
            val dbChapter = dbChaptersByUrl[chapter.url]
            if (dbChapter == null) {
                newChapters.add(chapter)
            } else {
                val updated = updateChapterBasedOnSyncState(chapter, dbChapter)
                if (updated.forComparison() != dbChapter.forComparison()) {
                    updatedChapters.add(updated.toUpdate())
                }
            }
        }

        if (newChapters.isNotEmpty()) {
            novelChapterRepository.insertAll(newChapters)
        }
        updatedChapters.forEach { novelChapterRepository.update(it) }
    }

    private fun updateChapterBasedOnSyncState(chapter: NovelChapter, dbChapter: NovelChapter): NovelChapter {
        return if (isSync) {
            chapter.copy(
                id = dbChapter.id,
                bookmark = chapter.bookmark || dbChapter.bookmark,
                sourceOrder = max(chapter.sourceOrder, dbChapter.sourceOrder),
                dateUpload = minNonZero(chapter.dateUpload, dbChapter.dateUpload),
            )
        } else {
            chapter.copyFrom(dbChapter)
                .copy(
                    id = dbChapter.id,
                    bookmark = chapter.bookmark || dbChapter.bookmark,
                    sourceOrder = max(chapter.sourceOrder, dbChapter.sourceOrder),
                    dateUpload = minNonZero(chapter.dateUpload, dbChapter.dateUpload),
                )
                .let {
                    when {
                        dbChapter.read && !it.read -> it.copy(read = true, lastPageRead = dbChapter.lastPageRead)
                        it.lastPageRead == 0L && dbChapter.lastPageRead != 0L -> it.copy(lastPageRead = dbChapter.lastPageRead)
                        else -> it
                    }
                }
        }
    }

    private fun NovelChapter.forComparison() =
        copy(
            id = 0L,
            novelId = 0L,
            dateFetch = 0L,
            lastModifiedAt = 0L,
            version = 0L,
        )

    private fun NovelChapter.toUpdate(): NovelChapterUpdate {
        return NovelChapterUpdate(
            id = id,
            novelId = novelId,
            read = read,
            bookmark = bookmark,
            lastPageRead = lastPageRead,
            dateFetch = dateFetch,
            sourceOrder = sourceOrder,
            url = url,
            name = name,
            dateUpload = dateUpload,
            chapterNumber = chapterNumber,
            scanlator = scanlator,
            version = version,
        )
    }

    private fun minNonZero(first: Long, second: Long): Long {
        return when {
            first <= 0L -> second
            second <= 0L -> first
            else -> min(first, second)
        }
    }
}
