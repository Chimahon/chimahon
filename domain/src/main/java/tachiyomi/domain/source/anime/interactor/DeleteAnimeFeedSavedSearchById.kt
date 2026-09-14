package tachiyomi.domain.source.anime.interactor

import tachiyomi.domain.source.anime.repository.AnimeFeedSavedSearchRepository

class DeleteAnimeFeedSavedSearchById(
    private val feedSavedSearchRepository: AnimeFeedSavedSearchRepository,
) {

    suspend fun await(feedSavedSearchId: Long) {
        feedSavedSearchRepository.delete(feedSavedSearchId)
    }
}
