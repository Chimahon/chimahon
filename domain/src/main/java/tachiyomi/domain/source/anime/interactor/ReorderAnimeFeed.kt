package tachiyomi.domain.source.anime.interactor

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.anime.model.AnimeFeedSavedSearch
import tachiyomi.domain.source.anime.model.AnimeFeedSavedSearchUpdate
import tachiyomi.domain.source.anime.repository.AnimeFeedSavedSearchRepository

class ReorderAnimeFeed(
    private val feedSavedSearchRepository: AnimeFeedSavedSearchRepository,
) {

    private val mutex = Mutex()

    suspend fun changeOrder(feed: AnimeFeedSavedSearch, newIndex: Int, global: Boolean = true) = withNonCancellableContext {
        mutex.withLock {
            val feeds = if (global) {
                feedSavedSearchRepository.getGlobal()
                    .toMutableList()
            } else {
                feedSavedSearchRepository.getBySourceId(feed.source)
                    .toMutableList()
            }

            val currentIndex = feeds.indexOfFirst { it.id == feed.id }
            if (currentIndex == -1) {
                return@withNonCancellableContext Result.Unchanged
            }

            try {
                feeds.add(newIndex, feeds.removeAt(currentIndex))

                val updates = feeds.mapIndexed { index, feed ->
                    AnimeFeedSavedSearchUpdate(
                        id = feed.id,
                        feedOrder = index.toLong(),
                    )
                }

                feedSavedSearchRepository.updatePartial(updates)
                Result.Success
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                Result.InternalError(e)
            }
        }
    }

    sealed interface Result {
        data object Success : Result
        data object Unchanged : Result
        data class InternalError(val error: Throwable) : Result
    }
}
