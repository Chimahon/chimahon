package tachiyomi.domain.source.anime.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.source.anime.model.AnimeFeedSavedSearch
import tachiyomi.domain.source.anime.repository.AnimeFeedSavedSearchRepository

class GetAnimeFeedSavedSearchBySourceId(
    private val feedSavedSearchRepository: AnimeFeedSavedSearchRepository,
) {

    suspend fun await(sourceId: Long): List<AnimeFeedSavedSearch> {
        return feedSavedSearchRepository.getBySourceId(sourceId)
    }

    fun subscribe(sourceId: Long): Flow<List<AnimeFeedSavedSearch>> {
        return feedSavedSearchRepository.getBySourceIdAsFlow(sourceId)
    }
}
