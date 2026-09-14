package tachiyomi.data.source.anime

import tachiyomi.domain.source.anime.model.AnimeFeedSavedSearch

object AnimeFeedSavedSearchMapper {
    fun map(
        id: Long,
        source: Long,
        savedSearch: Long?,
        global: Boolean,
        feedOrder: Long,
    ): AnimeFeedSavedSearch {
        return AnimeFeedSavedSearch(
            id = id,
            source = source,
            savedSearch = savedSearch,
            global = global,
            feedOrder = feedOrder,
        )
    }
}
