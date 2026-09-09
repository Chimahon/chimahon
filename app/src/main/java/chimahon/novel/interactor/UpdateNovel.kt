package chimahon.novel.interactor

import tachiyomi.domain.novel.model.NovelUpdate
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Named row writes instead of hand-rolled `NovelUpdate`s at every call site.
 * Favorite carries dateAdded discipline (now on add, zero on remove) so
 * unfavorite/refavorite cycles can't pollute date-added ordering.
 */
class UpdateNovel(
    private val novelRepository: NovelRepository = Injekt.get(),
) {

    suspend fun await(update: NovelUpdate): Boolean {
        return novelRepository.update(update)
    }

    suspend fun awaitAll(updates: List<NovelUpdate>): Boolean {
        return novelRepository.updateAll(updates)
    }

    suspend fun awaitUpdateLastUpdate(novelId: Long): Boolean {
        return novelRepository.update(
            NovelUpdate(id = novelId, lastUpdate = System.currentTimeMillis()),
        )
    }

    suspend fun awaitUpdateFavorite(novelId: Long, favorite: Boolean): Boolean {
        return novelRepository.update(
            NovelUpdate(
                id = novelId,
                favorite = favorite,
                dateAdded = if (favorite) System.currentTimeMillis() else 0L,
            ),
        )
    }
}
