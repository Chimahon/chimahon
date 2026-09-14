package tachiyomi.domain.source.anime.interactor

import logcat.LogPriority
import logcat.asLog
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.anime.model.AnimeFeedSavedSearch
import tachiyomi.domain.source.anime.repository.AnimeFeedSavedSearchRepository

class InsertAnimeFeedSavedSearch(
    private val feedSavedSearchRepository: AnimeFeedSavedSearchRepository,
) {

    suspend fun await(feedSavedSearch: AnimeFeedSavedSearch): Long? {
        return try {
            feedSavedSearchRepository.insert(feedSavedSearch)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { e.asLog() }
            null
        }
    }

    suspend fun awaitAll(feedSavedSearch: List<AnimeFeedSavedSearch>) {
        try {
            feedSavedSearchRepository.insertAll(feedSavedSearch)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { e.asLog() }
        }
    }
}
