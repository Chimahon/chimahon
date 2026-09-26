package eu.kanade.domain.track.interactor

import eu.kanade.domain.track.anime.model.toDbTrack
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.EnhancedAnimeTracker
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.episode.interactor.UpdateEpisode
import tachiyomi.domain.episode.model.toEpisodeUpdate
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack
import tachiyomi.domain.track.anime.model.AnimeTrack
import kotlin.math.max

class SyncEpisodeProgressWithTrack(
    private val updateEpisode: UpdateEpisode,
    private val insertTrack: InsertAnimeTrack,
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId,
) {

    /**
     * Sync episode progress with the [EnhancedAnimeTracker]
     */
    suspend fun await(
        animeId: Long,
        remoteTrack: AnimeTrack,
        service: AnimeTracker,
    ): Int? {
        if (service !is EnhancedAnimeTracker) {
            return null
        }
        // KMK -->
        return sync(animeId, remoteTrack, service)
        // KMK <--
    }

    /**
     * Sync episode progress with all trackers.
     */
    suspend fun sync(
        animeId: Long,
        remoteTrack: AnimeTrack,
        service: AnimeTracker,
    ): Int? {
        // KMK <--
        // Sort by source order first because the database order is not reliable, then drop
        // unrecognised numbers so a 0/negative special can't halt the continuity check below.
        val dbEpisodes = getEpisodesByAnimeId.await(animeId)
            .sortedByDescending { it.sourceOrder }
            .filter { it.isRecognizedNumber }

        val sortedEpisodes = dbEpisodes
            .sortedBy { it.episodeNumber }

        // AY -->
        // Only take continuous, incremental episodes: any out-of-order number stops the run, so a
        // non-contiguous entry (absolute numbering, "Season 2" entries, 0/negative specials) cannot
        // cause a whole unrelated range to be marked seen. Mirrors SyncChapterProgressWithTrack.
        var lastCheckEpisode: Double
        var checkingEpisode = 0.0

        val episodeUpdates = dbEpisodes
            .takeWhile { episode ->
                lastCheckEpisode = checkingEpisode
                checkingEpisode = episode.episodeNumber.toDouble()
                episode.episodeNumber >= lastCheckEpisode &&
                    episode.episodeNumber <= remoteTrack.lastEpisodeSeen
            }
            .filter { episode -> !episode.seen }
            .map { it.copy(seen = true).toEpisodeUpdate() }
        // <-- AY

        // only take into account continuous watching
        val localLastSeen = sortedEpisodes.takeWhile { it.seen }.lastOrNull()?.episodeNumber ?: 0F
        val lastSeen = max(remoteTrack.lastEpisodeSeen, localLastSeen.toDouble())
        val updatedTrack = remoteTrack.copy(lastEpisodeSeen = lastSeen)

        try {
            // Update Tracker to localLastSeen if needed
            if (lastSeen > remoteTrack.lastEpisodeSeen) {
                service.update(updatedTrack.toDbTrack())
                // update Track in database
                insertTrack.await(updatedTrack)
            }
            // KMK -->
            // Always update local episodes following Tracker even past episodes
            if (episodeUpdates.isNotEmpty() && !service.hasNotStartedWatching(remoteTrack.status)) {
                updateEpisode.awaitAll(episodeUpdates)
                return lastSeen.toInt()
            }
            // KMK <--
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e)
        }
        // KMK -->
        return null
        // KMK <--
    }
}
