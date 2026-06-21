package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupSourceNovel
import eu.kanade.tachiyomi.data.backup.models.toBackupChapter
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourceNovelBackupCreator(
    private val novelRepository: NovelRepository = Injekt.get(),
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
) {

    suspend operator fun invoke(options: BackupOptions): List<BackupSourceNovel> {
        if (!options.sourceNovelLibrary) return emptyList()

        return novelRepository.getFavorites()
            .map { backupNovel(it, options) }
    }

    private suspend fun backupNovel(novel: Novel, options: BackupOptions): BackupSourceNovel {
        return novel.toBackupSourceNovel().also { backupNovel ->
            if (options.chapters) {
                backupNovel.chapters = novelChapterRepository.getChaptersByNovelId(novel.id)
                    .map { it.toBackupChapter() }
            }
        }
    }
}

private fun Novel.toBackupSourceNovel() =
    BackupSourceNovel(
        source = source,
        url = url,
        title = title,
        artist = artist,
        author = author,
        description = description,
        genre = genre,
        status = status,
        thumbnailUrl = thumbnailUrl,
        favorite = favorite,
        lastUpdate = lastUpdate,
        nextUpdate = nextUpdate,
        fetchInterval = fetchInterval,
        dateAdded = dateAdded,
        coverLastModified = coverLastModified,
        initialized = initialized,
        totalChapters = totalChapters,
        lastModifiedAt = lastModifiedAt,
        favoriteModifiedAt = favoriteModifiedAt,
        version = version,
        notes = notes,
    )
