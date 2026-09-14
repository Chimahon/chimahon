package tachiyomi.data.source.anime

import tachiyomi.domain.source.anime.model.AnimeSavedSearch

object AnimeSavedSearchMapper {
    fun map(
        id: Long,
        source: Long,
        name: String,
        query: String?,
        filtersJson: String?,
    ): AnimeSavedSearch {
        return AnimeSavedSearch(
            id = id,
            source = source,
            name = name,
            query = query,
            filtersJson = filtersJson,
        )
    }
}
