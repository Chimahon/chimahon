package tachiyomi.domain.source.anime.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.source.anime.model.AnimeFeedSavedSearch
import tachiyomi.domain.source.anime.model.AnimeFeedSavedSearchUpdate
import tachiyomi.domain.source.anime.model.AnimeSavedSearch

interface AnimeFeedSavedSearchRepository {

    suspend fun getGlobal(): List<AnimeFeedSavedSearch>

    fun getGlobalAsFlow(): Flow<List<AnimeFeedSavedSearch>>

    suspend fun getGlobalFeedSavedSearch(): List<AnimeSavedSearch>

    suspend fun countGlobal(): Long

    suspend fun getBySourceId(sourceId: Long): List<AnimeFeedSavedSearch>

    fun getBySourceIdAsFlow(sourceId: Long): Flow<List<AnimeFeedSavedSearch>>

    suspend fun getBySourceIdFeedSavedSearch(sourceId: Long): List<AnimeSavedSearch>

    suspend fun countBySourceId(sourceId: Long): Long

    suspend fun delete(feedSavedSearchId: Long)

    suspend fun insert(feedSavedSearch: AnimeFeedSavedSearch): Long?

    suspend fun insertAll(feedSavedSearch: List<AnimeFeedSavedSearch>)

    suspend fun updatePartial(update: AnimeFeedSavedSearchUpdate)

    suspend fun updatePartial(updates: List<AnimeFeedSavedSearchUpdate>)
}
