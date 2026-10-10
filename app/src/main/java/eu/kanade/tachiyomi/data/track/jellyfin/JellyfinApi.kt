package eu.kanade.tachiyomi.data.track.jellyfin

import eu.kanade.tachiyomi.data.database.models.anime.AnimeTrack
import eu.kanade.tachiyomi.data.track.jellyfin.dto.JFItem
import eu.kanade.tachiyomi.data.track.jellyfin.dto.JFItemList
import eu.kanade.tachiyomi.data.track.model.AnimeTrackSearch
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class JellyfinApi(
    private val trackId: Long,
    private val client: OkHttpClient,
    private val json: Json = Injekt.get(),
) {
    suspend fun getTrackSearch(url: String): AnimeTrackSearch = withIOContext {
        val itemUrl = JellyfinItemUrl(url)
        val item = getItem(url)
        AnimeTrackSearch.create(trackId).apply {
            title = item.name
            tracking_url = url
            when {
                itemUrl.isContainer -> {
                    // Containers enable tracking for their children; they have no episode counter.
                    total_episodes = 0
                    last_episode_seen = 0.0
                    status = Jellyfin.UNSEEN
                }
                itemUrl.isMovie -> {
                    total_episodes = 1
                    last_episode_seen = if (item.userData.played) 1.0 else 0.0
                    status = if (item.userData.played) Jellyfin.COMPLETED else Jellyfin.UNSEEN
                }
                else -> applyEpisodes(getEpisodes(itemUrl))
            }
        }
    }

    private suspend fun getItem(url: String): JFItem = with(json) {
        client.newCall(GET(url, cache = CacheControl.FORCE_NETWORK)).awaitSuccess().parseAs<JFItem>()
    }

    private suspend fun getEpisodes(url: JellyfinItemUrl): List<JFItem> = with(json) {
        client.newCall(GET(url.episodesUrl(), cache = CacheControl.FORCE_NETWORK)).awaitSuccess().parseAs<JFItemList>().items
    }

    private fun AnimeTrackSearch.applyEpisodes(items: List<JFItem>) {
        val episodes = items.sortedBy { it.indexNumber ?: Long.MAX_VALUE }
        total_episodes = episodes.mapNotNull { it.indexNumber }.maxOrNull() ?: episodes.size.toLong()
        last_episode_seen = episodes.takeWhile { it.userData.played }
            .mapNotNull { it.indexNumber }.lastOrNull()?.toDouble() ?: 0.0
        status = when {
            episodes.isEmpty() || episodes.none { it.userData.played } -> Jellyfin.UNSEEN
            episodes.all { it.userData.played } -> Jellyfin.COMPLETED
            else -> Jellyfin.WATCHING
        }
    }

    private suspend fun setPlayed(url: JellyfinItemUrl, itemId: String, played: Boolean) {
        val request = if (played) {
            POST(url.playedUrl(itemId).toString())
        } else {
            Request.Builder().url(url.playedUrl(itemId)).delete().build()
        }
        client.newCall(request).awaitSuccess().close()
    }

    /** Reports the actual episode, including rewatches and episodes before a gap. */
    suspend fun updateEpisode(track: AnimeTrack, episodeUrl: String): AnimeTrack = withIOContext {
        val url = JellyfinItemUrl(track.tracking_url)
        val episodeId = url.episodeId(episodeUrl)
        if (url.isMovie) {
            if (episodeId != url.itemId) throw IOException("Jellyfin movie does not match the episode")
        } else if (getEpisodes(url).none { it.id == episodeId }) {
            throw IOException("Jellyfin episode does not belong to this season")
        }
        setPlayed(url, episodeId, true)
        getTrackSearch(track.tracking_url)
    }

    /** Explicit counter edits set the watched range; playback uses updateEpisode instead. */
    suspend fun updateProgress(track: AnimeTrack, didWatchEpisode: Boolean = false): AnimeTrack = withIOContext {
        val url = JellyfinItemUrl(track.tracking_url)
        if (url.isContainer) throw IOException("Update a Jellyfin season or movie instead of its container")
        if (url.isMovie) {
            val played = track.last_episode_seen >= 1
            if (played || !didWatchEpisode) setPlayed(url, url.itemId, played)
        } else {
            val episodes = getEpisodes(url)
            val numbered = episodes.filter { it.indexNumber != null }
            if (track.last_episode_seen > 0 && numbered.none { it.indexNumber!!.toDouble() == track.last_episode_seen }) {
                throw IOException("Episode ${track.last_episode_seen} was not found in this Jellyfin season")
            }
            if (didWatchEpisode) {
                // Legacy numeric playback reports identify one episode, not a watched range.
                numbered.firstOrNull { it.indexNumber!!.toDouble() == track.last_episode_seen }?.let {
                    if (!it.userData.played) setPlayed(url, it.id, true)
                }
            } else {
                numbered.forEach { episode ->
                    val played = episode.indexNumber!!.toDouble() <= track.last_episode_seen
                    if (episode.userData.played != played) setPlayed(url, episode.id, played)
                }
            }
        }
        getTrackSearch(track.tracking_url)
    }

    /** Merge individual watched items, preserving gaps rather than inferring a watched range. */
    suspend fun syncPlayedEpisodes(trackUrl: String, localSeenUrls: List<String>): Set<String> = withIOContext {
        val url = JellyfinItemUrl(trackUrl)
        if (url.isContainer) return@withIOContext emptySet()
        val episodes = if (url.isMovie) {
            listOf(getItem(trackUrl))
        } else {
            getEpisodes(url)
        }
        val localSeenIds = localSeenUrls.map { url.episodeId(it) }.toSet()
        episodes.filter { it.id in localSeenIds && !it.userData.played }.forEach {
            setPlayed(url, it.id, true)
        }
        episodes.filter { it.userData.played || it.id in localSeenIds }.map { it.id }.toSet()
    }
}
