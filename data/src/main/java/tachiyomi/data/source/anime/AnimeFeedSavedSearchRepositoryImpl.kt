package tachiyomi.data.source.anime

import kotlinx.coroutines.flow.Flow
import tachiyomi.mi.data.AnimeDatabase
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.domain.source.anime.model.AnimeFeedSavedSearch
import tachiyomi.domain.source.anime.model.AnimeFeedSavedSearchUpdate
import tachiyomi.domain.source.anime.model.AnimeSavedSearch
import tachiyomi.domain.source.anime.repository.AnimeFeedSavedSearchRepository

class AnimeFeedSavedSearchRepositoryImpl(
    private val handler: AnimeDatabaseHandler,
) : AnimeFeedSavedSearchRepository {

    override suspend fun getGlobal(): List<AnimeFeedSavedSearch> {
        return handler.awaitList { anime_feed_saved_searchQueries.selectAllGlobal(AnimeFeedSavedSearchMapper::map) }
    }

    override fun getGlobalAsFlow(): Flow<List<AnimeFeedSavedSearch>> {
        return handler.subscribeToList { anime_feed_saved_searchQueries.selectAllGlobal(AnimeFeedSavedSearchMapper::map) }
    }

    override suspend fun getGlobalFeedSavedSearch(): List<AnimeSavedSearch> {
        return handler.awaitList { anime_feed_saved_searchQueries.selectGlobalFeedSavedSearch(AnimeSavedSearchMapper::map) }
    }

    override suspend fun countGlobal(): Long {
        return handler.awaitOne { anime_feed_saved_searchQueries.countGlobal() }
    }

    override suspend fun getBySourceId(sourceId: Long): List<AnimeFeedSavedSearch> {
        return handler.awaitList { anime_feed_saved_searchQueries.selectBySource(sourceId, AnimeFeedSavedSearchMapper::map) }
    }

    override fun getBySourceIdAsFlow(sourceId: Long): Flow<List<AnimeFeedSavedSearch>> {
        return handler.subscribeToList { anime_feed_saved_searchQueries.selectBySource(sourceId, AnimeFeedSavedSearchMapper::map) }
    }

    override suspend fun getBySourceIdFeedSavedSearch(sourceId: Long): List<AnimeSavedSearch> {
        return handler.awaitList {
            anime_feed_saved_searchQueries.selectSourceFeedSavedSearch(sourceId, AnimeSavedSearchMapper::map)
        }
    }

    override suspend fun countBySourceId(sourceId: Long): Long {
        return handler.awaitOne { anime_feed_saved_searchQueries.countSourceFeedSavedSearch(sourceId) }
    }

    override suspend fun delete(feedSavedSearchId: Long) {
        handler.await { anime_feed_saved_searchQueries.deleteById(feedSavedSearchId) }
    }

    override suspend fun insert(feedSavedSearch: AnimeFeedSavedSearch): Long? {
        return handler.await(true) {
            val currentFeeds = handler.awaitList {
                anime_feed_saved_searchQueries.selectAll(AnimeFeedSavedSearchMapper::map)
            }
            val existedFeedId = currentFeeds.find { currentFeed ->
                currentFeed.source == feedSavedSearch.source &&
                    currentFeed.savedSearch == feedSavedSearch.savedSearch &&
                    currentFeed.global == feedSavedSearch.global
            }?.id

            existedFeedId
                ?: handler.awaitOneExecutable(true) {
                    anime_feed_saved_searchQueries.insert(
                        feedSavedSearch.source,
                        feedSavedSearch.savedSearch,
                        feedSavedSearch.global,
                    )
                    anime_feed_saved_searchQueries.selectLastInsertedRowId()
                }
        }
    }

    override suspend fun insertAll(feedSavedSearch: List<AnimeFeedSavedSearch>) {
        return handler.await(true) {
            feedSavedSearch.forEach {
                anime_feed_saved_searchQueries.insert(
                    it.source,
                    it.savedSearch,
                    it.global,
                )
            }
        }
    }

    override suspend fun updatePartial(update: AnimeFeedSavedSearchUpdate) {
        handler.await {
            updatePartialBlocking(update)
        }
    }

    override suspend fun updatePartial(updates: List<AnimeFeedSavedSearchUpdate>) {
        handler.await(inTransaction = true) {
            for (update in updates) {
                updatePartialBlocking(update)
            }
        }
    }

    private fun AnimeDatabase.updatePartialBlocking(update: AnimeFeedSavedSearchUpdate) {
        anime_feed_saved_searchQueries.update(
            source = update.source,
            savedSearch = update.savedSearch,
            global = update.global,
            feedOrder = update.feedOrder,
            id = update.id,
        )
    }
}
