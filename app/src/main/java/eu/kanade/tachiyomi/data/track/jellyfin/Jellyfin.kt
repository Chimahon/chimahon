package eu.kanade.tachiyomi.data.track.jellyfin

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.database.models.anime.AnimeTrack
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.EnhancedAnimeTracker
import eu.kanade.tachiyomi.data.track.model.AnimeTrackSearch
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import okhttp3.Dns
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.i18n.MR
import tachiyomi.domain.track.anime.model.AnimeTrack as DomainTrack
import tachiyomi.domain.track.model.Track as DomainMangaTrack

class Jellyfin(id: Long) : BaseTracker(id, "Jellyfin"), EnhancedAnimeTracker, AnimeTracker {

    companion object {
        const val UNSEEN = 1L
        const val WATCHING = 2L
        const val COMPLETED = 3L
    }

    override val client by lazy {
        networkService.client.newBuilder()
            .addInterceptor(JellyfinInterceptor())
            .dns(Dns.SYSTEM) // don't use DNS over HTTPS as it breaks IP addressing
            .build()
    }

    val api by lazy { JellyfinApi(id, client) }

    override fun getLogo() = R.drawable.ic_tracker_jellyfin

    override fun getStatusListAnime(): List<Long> = listOf(UNSEEN, WATCHING, COMPLETED)

    override fun getStatusList(): List<Long> = getStatusListAnime()

    override fun getStatusForAnime(status: Long): StringResource? = when (status) {
        UNSEEN -> MR.strings.unseen
        WATCHING -> MR.strings.watching
        COMPLETED -> MR.strings.completed
        else -> null
    }

    override fun getStatus(status: Long): StringResource? = getStatusForAnime(status)

    override fun getReadingStatus(): Long = WATCHING

    override fun getWatchingStatus(): Long = WATCHING

    override fun getRereadingStatus(): Long = -1

    override fun getRewatchingStatus(): Long = -1

    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): ImmutableList<String> = persistentListOf()

    override fun indexToScore(index: Int): Double = index.toDouble()

    override fun displayScore(track: DomainMangaTrack): String = ""

    override fun displayScore(track: DomainTrack): String = ""

    override suspend fun update(track: AnimeTrack, didWatchEpisode: Boolean): AnimeTrack {
        return api.updateProgress(track)
    }

    override suspend fun bind(track: AnimeTrack, hasSeenEpisodes: Boolean): AnimeTrack {
        return track
    }

    override suspend fun searchAnime(query: String): List<AnimeTrackSearch> =
        throw Exception("Not used")

    override suspend fun search(query: String): List<TrackSearch> =
        throw Exception("Not used")

    override suspend fun refresh(track: AnimeTrack): AnimeTrack {
        val remoteTrack = api.getTrackSearch(track.tracking_url)
        track.copyPersonalFrom(remoteTrack)
        track.total_episodes = remoteTrack.total_episodes
        return track
    }

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        throw Exception("Not used")
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        throw Exception("Not used")
    }

    override suspend fun refresh(track: Track): Track {
        throw Exception("Not used")
    }

    override suspend fun login(username: String, password: String) {
        saveCredentials("user", "pass")
    }

    override fun loginNoop() {
        saveCredentials("user", "pass")
    }

    override fun getAcceptedSources() = listOf("eu.kanade.tachiyomi.animeextension.all.jellyfin.Jellyfin")

    override suspend fun match(anime: Anime): AnimeTrackSearch? =
        try {
            api.getTrackSearch(anime.url)
        } catch (e: Exception) {
            null
        }

    override suspend fun matchSeason(anime: Anime): AnimeTrackSearch {
        return AnimeTrackSearch.create(id).apply {
            title = anime.title
            tracking_url = anime.url
        }
    }

    override fun isTrackFrom(track: AnimeTrack, anime: Anime, source: AnimeSource?): Boolean =
        track.tracking_url == anime.url && source?.let { accept(it) } == true

    override fun migrateTrack(track: AnimeTrack, anime: Anime, newSource: AnimeSource): AnimeTrack? {
        return if (accept(newSource)) {
            track.apply { tracking_url = anime.url }
        } else {
            null
        }
    }

    override fun hasNotStartedReading(status: Long): Boolean = status == UNSEEN
}
