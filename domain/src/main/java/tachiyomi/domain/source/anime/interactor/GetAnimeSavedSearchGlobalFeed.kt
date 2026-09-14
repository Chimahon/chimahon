package tachiyomi.domain.source.anime.interactor

import tachiyomi.domain.source.anime.model.AnimeSavedSearch
import tachiyomi.domain.source.anime.repository.AnimeFeedSavedSearchRepository

class GetAnimeSavedSearchGlobalFeed(
    private val feedSavedSearchRepository: AnimeFeedSavedSearchRepository,
) {

    suspend fun await(): List<AnimeSavedSearch> {
        return feedSavedSearchRepository.getGlobalFeedSavedSearch()
    }
}
