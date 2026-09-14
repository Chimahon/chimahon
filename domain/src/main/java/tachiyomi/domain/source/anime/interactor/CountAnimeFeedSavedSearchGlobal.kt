package tachiyomi.domain.source.anime.interactor

import tachiyomi.domain.source.anime.repository.AnimeFeedSavedSearchRepository

class CountAnimeFeedSavedSearchGlobal(
    private val feedSavedSearchRepository: AnimeFeedSavedSearchRepository,
) {

    suspend fun await(): Long {
        return feedSavedSearchRepository.countGlobal()
    }
}
