package tachiyomi.domain.source.anime.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.source.anime.model.AnimeSavedSearch
import tachiyomi.domain.source.anime.repository.AnimeSavedSearchRepository

class GetAnimeSavedSearchBySourceId(
    private val savedSearchRepository: AnimeSavedSearchRepository,
) {

    suspend fun await(sourceId: Long): List<AnimeSavedSearch> {
        return savedSearchRepository.getBySourceId(sourceId)
    }

    fun subscribe(sourceId: Long): Flow<List<AnimeSavedSearch>> {
        return savedSearchRepository.getBySourceIdAsFlow(sourceId)
    }
}
