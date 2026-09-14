package tachiyomi.domain.source.anime.interactor

import tachiyomi.domain.source.anime.repository.AnimeSavedSearchRepository

class DeleteAnimeSavedSearchById(
    private val savedSearchRepository: AnimeSavedSearchRepository,
) {

    suspend fun await(savedSearchId: Long) {
        savedSearchRepository.delete(savedSearchId)
    }
}
