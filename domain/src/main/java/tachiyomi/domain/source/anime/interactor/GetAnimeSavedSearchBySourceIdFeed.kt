package tachiyomi.domain.source.anime.interactor

import tachiyomi.domain.source.anime.model.AnimeSavedSearch
import tachiyomi.domain.source.anime.repository.AnimeFeedSavedSearchRepository

class GetAnimeSavedSearchBySourceIdFeed(
    private val feedSavedSearchRepository: AnimeFeedSavedSearchRepository,
) {

    suspend fun await(sourceId: Long): List<AnimeSavedSearch> {
        return feedSavedSearchRepository.getBySourceIdFeedSavedSearch(sourceId)
    }
}
