package eu.kanade.tachiyomi.data.track.jellyfin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

internal class JellyfinItemUrl(val url: HttpUrl) {
    constructor(url: String) : this(url.toHttpUrl())

    private val usersIndex = url.pathSegments.indexOfLast { it == "Users" }
    val userId: String
    val itemId: String
    val seriesId: String?
    val isMovie: Boolean
    val isContainer: Boolean

    init {
        if (usersIndex < 0 || url.pathSegments.size != usersIndex + 4 ||
            url.pathSegments[usersIndex + 2] != "Items"
        ) {
            throw IOException("Invalid Jellyfin item URL")
        }
        userId = url.pathSegments[usersIndex + 1]
        itemId = url.pathSegments.last()
        val fragment = url.fragment.orEmpty()
        isMovie = fragment == "movie"
        isContainer = fragment == "series" || fragment == "boxSet"
        seriesId = when {
            fragment.startsWith("season,") || fragment.startsWith("seriesId,") ->
                fragment.substringAfter(',').takeIf { it.isNotBlank() && it != "null" }
            else -> null
        }
    }

    private fun root() = url.newBuilder().apply {
        fragment(null)
        encodedQuery(null)
        repeat(url.pathSegments.size - usersIndex) { removePathSegment(usersIndex) }
    }

    fun episodesUrl(): HttpUrl {
        val series = seriesId ?: throw IOException("Jellyfin episode tracking requires a season URL")
        return root()
            .addPathSegment("Shows")
            .addPathSegment(series)
            .addPathSegment("Episodes")
            .addQueryParameter("seasonId", itemId)
            .addQueryParameter("userId", userId)
            .build()
    }

    fun playedUrl(episodeId: String): HttpUrl = root()
        .addPathSegment("Users")
        .addPathSegment(userId)
        .addPathSegment("PlayedItems")
        .addPathSegment(episodeId)
        .build()

    fun episodeId(episodeUrl: String): String {
        val episode = JellyfinItemUrl(episodeUrl)
        if (episode.url.scheme != url.scheme || episode.url.host != url.host ||
            episode.url.port != url.port || episode.userId != userId ||
            episode.url.pathSegments.take(episode.usersIndex) != url.pathSegments.take(usersIndex)
        ) {
            throw IOException("Jellyfin episode belongs to a different server or user")
        }
        return episode.itemId
    }
}
