@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.animesource.model

import eu.kanade.tachiyomi.animesource.model.FetchType
import kotlinx.serialization.json.JsonObject
import java.io.Serializable

interface SAnime : Serializable {

    var url: String

    var title: String

    var artist: String?

    var author: String?

    var description: String?

    var genre: String?

    var status: Int

    var thumbnail_url: String?

    var background_url: String?

    var fetch_type: FetchType

    var season_number: Double

    var update_strategy: AnimeUpdateStrategy

    var initialized: Boolean

    /**
     * Extra metadata associated with the anime.
     *
     * The JSON object is not visible to users and intended for internal or source-specific
     * purposes. Apps may define their own namespaced keys (e.g. `"aniyomi.*"`) for sources to
     * populate.
     *
     * Defaults to a no-op so implementations outside this module do not have to change;
     * [SAnimeImpl] stores the real value.
     *
     * @since extensions-lib 17
     */
    var memo: JsonObject
        get() = JsonObject.EMPTY
        set(@Suppress("UNUSED_PARAMETER") _) = Unit

    fun getGenres(): List<String>? {
        if (genre.isNullOrBlank()) return null
        return genre?.split(", ")?.map { it.trim() }?.filterNot { it.isBlank() }?.distinct()
    }

    fun copy() = create().also {
        it.url = url
        it.title = title
        it.artist = artist
        it.author = author
        it.description = description
        it.genre = genre
        it.status = status
        it.thumbnail_url = thumbnail_url
        it.background_url = background_url
        it.fetch_type = fetch_type
        it.season_number = season_number
        it.update_strategy = update_strategy
        it.initialized = initialized
        it.memo = memo
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SAnime {
            return SAnimeImpl()
        }
    }
}
