package tachiyomi.domain.source.anime.interactor

import logcat.LogPriority
import logcat.asLog
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.anime.model.AnimeSavedSearch
import tachiyomi.domain.source.anime.repository.AnimeSavedSearchRepository

class InsertAnimeSavedSearch(
    private val savedSearchRepository: AnimeSavedSearchRepository,
) {

    suspend fun await(savedSearch: AnimeSavedSearch): Long? {
        return try {
            savedSearchRepository.insert(savedSearch)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { e.asLog() }
            null
        }
    }

    suspend fun awaitAll(savedSearch: List<AnimeSavedSearch>) {
        try {
            savedSearchRepository.insertAll(savedSearch)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { e.asLog() }
        }
    }
}
