package tachiyomi.domain.source.anime.model

data class AnimeSavedSearch(
    // Tag identifier, unique
    val id: Long,

    // The source the saved search is for
    val source: Long,

    // If false the anime will not grab episode updates
    val name: String,

    // The query if there is any
    val query: String?,

    // The filter list
    val filtersJson: String?,
)
