package tachiyomi.domain.source.anime.interactor

import tachiyomi.domain.source.anime.model.AnimeSavedSearch
import tachiyomi.domain.source.anime.repository.AnimeSavedSearchRepository

class GetAnimeSavedSearchById(
    private val savedSearchRepository: AnimeSavedSearchRepository,
) {

    suspend fun await(savedSearchId: Long): AnimeSavedSearch? {
        return savedSearchRepository.getById(savedSearchId)
    }
}
