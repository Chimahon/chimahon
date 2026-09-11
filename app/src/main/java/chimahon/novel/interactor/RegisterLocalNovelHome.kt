package chimahon.novel.interactor

import android.app.Application
import chimahon.novel.source.NovelLocalSource
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelUpdate
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Database home for imported books (source [Novel.LOCAL_SOURCE_ID]): novel row
 * plus real spine chapters, so progress, history, updates and the detail screen
 * work per chapter. Idempotent (upserts by unique keys), never throws.
 * The reader keeps opening the EPUB file directly; this only registers state.
 */
class RegisterLocalNovelHome(
    private val novelRepository: NovelRepository = Injekt.get(),
    private val syncNovelChapters: SyncNovelChapters = Injekt.get(),
    private val app: Application = Injekt.get(),
) {

    suspend fun register(stableId: String): Long? = runCatching {
        if (stableId.isBlank()) return@runCatching null
        val source = NovelLocalSource(app.applicationContext)
        val snNovel = source.getNovelDetails(SNNovel(url = NovelLocalSource.novelUrl(stableId)))
        if (snNovel.title.isBlank()) return@runCatching null

        val now = System.currentTimeMillis()
        val existing = novelRepository.getNovelByUrlAndSourceId(snNovel.url, Novel.LOCAL_SOURCE_ID)
        val novelId = if (existing == null) {
            novelRepository.insert(
                Novel.fromSourceNovel(snNovel, Novel.LOCAL_SOURCE_ID).copy(
                    favorite = true,
                    dateAdded = now,
                    lastUpdate = now,
                    initialized = true,
                    isLocal = true,
                    localFolder = stableId,
                ),
            )
        } else {
            novelRepository.update(
                NovelUpdate(
                    id = existing.id,
                    title = snNovel.title,
                    author = snNovel.author,
                    thumbnailUrl = snNovel.thumbnail_url,
                    initialized = true,
                    isLocal = true,
                    localFolder = stableId,
                    // Re-registering (e.g. re-download after a library
                    // delete, which keeps the row unfavorited) must put the
                    // book back in the library, mirroring manga re-add.
                    favorite = true,
                    dateAdded = now,
                    // Seed once: never overwrites an EPUB/import lang.
                    lang = snNovel.lang?.takeIf { it.isNotBlank() && existing.lang.isNullOrBlank() },
                ),
            )
            existing.id
        }

        val chapters = source.getChapterList(snNovel)
        val synced = syncNovelChapters.await(novelId, chapters).chapters
        novelRepository.update(NovelUpdate(id = novelId, totalChapters = synced.size))
        novelId
    }.getOrNull()
}
