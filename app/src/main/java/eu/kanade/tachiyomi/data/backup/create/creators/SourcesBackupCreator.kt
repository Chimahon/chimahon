package eu.kanade.tachiyomi.data.backup.create.creators

import chimahon.novel.manager.NovelSourceManager
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourceNovel
import eu.kanade.tachiyomi.source.Source
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SourcesBackupCreator(
    private val sourceManager: SourceManager = Injekt.get(),
    private val novelSourceManager: NovelSourceManager = Injekt.get(),
) {

    operator fun invoke(
        mangas: List<BackupManga>,
        novels: List<BackupSourceNovel> = emptyList(),
    ): List<BackupSource> {
        return (mangas.asSequence().map(BackupManga::source) + novels.asSequence().map(BackupSourceNovel::source))
            .distinct()
            .map { sourceId ->
                novelSourceManager.getNovelSource(sourceId)?.let {
                    BackupSource(name = it.name, sourceId = it.id)
                } ?: sourceManager.getOrStub(sourceId).toBackupSource()
            }
            .toList()
    }
}

private fun Source.toBackupSource() =
    BackupSource(
        name = this.name,
        sourceId = this.id,
    )
