package tachiyomi.domain.source.anime.interactor

import tachiyomi.domain.source.anime.repository.AnimeFeedSavedSearchRepository

class CountAnimeFeedSavedSearchBySourceId(
    private val feedSavedSearchRepository: AnimeFeedSavedSearchRepository,
) {

    suspend fun await(sourceId: Long): Long {
        return feedSavedSearchRepository.countBySourceId(sourceId)
    }
}
