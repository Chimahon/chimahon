package tachiyomi.data.source.anime

import kotlinx.coroutines.flow.Flow
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.domain.source.anime.model.AnimeSavedSearch
import tachiyomi.domain.source.anime.repository.AnimeSavedSearchRepository

class AnimeSavedSearchRepositoryImpl(
    private val handler: AnimeDatabaseHandler,
) : AnimeSavedSearchRepository {

    override suspend fun getById(savedSearchId: Long): AnimeSavedSearch? {
        return handler.awaitOneOrNull { anime_saved_searchQueries.selectById(savedSearchId, AnimeSavedSearchMapper::map) }
    }

    override suspend fun getBySourceId(sourceId: Long): List<AnimeSavedSearch> {
        return handler.awaitList { anime_saved_searchQueries.selectBySource(sourceId, AnimeSavedSearchMapper::map) }
    }

    override fun getBySourceIdAsFlow(sourceId: Long): Flow<List<AnimeSavedSearch>> {
        return handler.subscribeToList { anime_saved_searchQueries.selectBySource(sourceId, AnimeSavedSearchMapper::map) }
    }

    override suspend fun delete(savedSearchId: Long) {
        handler.await { anime_saved_searchQueries.deleteById(savedSearchId) }
    }

    override suspend fun insert(savedSearch: AnimeSavedSearch): Long? {
        return handler.await(true) {
            val currentSavedSearches = handler.awaitList {
                anime_saved_searchQueries.selectAll(AnimeSavedSearchMapper::map)
            }
            val existedSavedSearchId = currentSavedSearches.find { currentSavedSearch ->
                currentSavedSearch.source == savedSearch.source &&
                    currentSavedSearch.name == savedSearch.name &&
                    currentSavedSearch.query == savedSearch.query &&
                    currentSavedSearch.filtersJson == savedSearch.filtersJson
            }?.id

            existedSavedSearchId
                ?: handler.awaitOneExecutable(true) {
                    anime_saved_searchQueries.insert(
                        savedSearch.source,
                        savedSearch.name,
                        savedSearch.query,
                        savedSearch.filtersJson,
                    )
                    anime_saved_searchQueries.selectLastInsertedRowId()
                }
        }
    }

    override suspend fun insertAll(savedSearch: List<AnimeSavedSearch>) {
        handler.await(true) {
            savedSearch.forEach {
                anime_saved_searchQueries.insert(
                    it.source,
                    it.name,
                    it.query,
                    it.filtersJson,
                )
            }
        }
    }
}
