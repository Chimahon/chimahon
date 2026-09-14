package tachiyomi.domain.source.anime.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.source.anime.model.AnimeFeedSavedSearch
import tachiyomi.domain.source.anime.repository.AnimeFeedSavedSearchRepository

class GetAnimeFeedSavedSearchGlobal(
    private val feedSavedSearchRepository: AnimeFeedSavedSearchRepository,
) {

    suspend fun await(): List<AnimeFeedSavedSearch> {
        return feedSavedSearchRepository.getGlobal()
    }

    fun subscribe(): Flow<List<AnimeFeedSavedSearch>> {
        return feedSavedSearchRepository.getGlobalAsFlow()
    }
}
