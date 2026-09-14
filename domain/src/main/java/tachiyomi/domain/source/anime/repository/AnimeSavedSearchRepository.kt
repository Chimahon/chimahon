package tachiyomi.domain.source.anime.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.source.anime.model.AnimeSavedSearch

interface AnimeSavedSearchRepository {

    suspend fun getById(savedSearchId: Long): AnimeSavedSearch?

    suspend fun getBySourceId(sourceId: Long): List<AnimeSavedSearch>

    fun getBySourceIdAsFlow(sourceId: Long): Flow<List<AnimeSavedSearch>>

    suspend fun delete(savedSearchId: Long)

    suspend fun insert(savedSearch: AnimeSavedSearch): Long?

    suspend fun insertAll(savedSearch: List<AnimeSavedSearch>)
}
