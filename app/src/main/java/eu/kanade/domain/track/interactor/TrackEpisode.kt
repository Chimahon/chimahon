package eu.kanade.domain.track.interactor

import android.content.Context
import eu.kanade.domain.track.anime.interactor.awaitForAnime
import eu.kanade.domain.track.anime.model.toDbTrack
import eu.kanade.domain.track.anime.model.toDomainTrack
import eu.kanade.domain.track.service.DelayedAnimeTrackingUpdateJob
import eu.kanade.domain.track.store.DelayedAnimeTrackingStore
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.jellyfin.Jellyfin
import eu.kanade.tachiyomi.util.system.isOnline
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TrackEpisode(
    private val getTracks: GetAnimeTracks,
    private val getAnime: GetAnime,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertAnimeTrack,
    private val delayedTrackingStore: DelayedAnimeTrackingStore,
) {

    suspend fun await(
        context: Context,
        animeId: Long,
        episodeNumber: Double,
        setupJobOnFailure: Boolean = true,
        episodeUrl: String? = null,
        trackerId: Long? = null,
    ) {
        withNonCancellableContext {
            val tracks = getTracks.awaitForAnime(animeId, getAnime, insertTrack)
                .filter { trackerId == null || it.trackerId == trackerId }
            if (tracks.isEmpty()) return@withNonCancellableContext

            tracks.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                val tracker = service as? AnimeTracker
                val reportEpisode = tracker is Jellyfin && episodeUrl != null
                if (service == null || tracker == null || !service.isLoggedIn ||
                    (tracker !is Jellyfin && episodeNumber <= track.lastEpisodeSeen)
                ) {
                    return@mapNotNull null
                }

                async {
                    runCatching {
                        try {
                            if (!context.isOnline()) {
                                error("No network available")
                            }
                            val updatedTrack = if (tracker is Jellyfin && episodeUrl != null) {
                                val result = tracker.api.updateEpisode(track.toDbTrack(), episodeUrl)
                                track.toDbTrack().apply {
                                    copyPersonalFrom(result)
                                    total_episodes = result.total_episodes
                                }.toDomainTrack()!!
                            } else if (tracker is Jellyfin) {
                                val localSeen = Injekt.get<GetEpisodesByAnimeId>().await(animeId).filter { it.seen }
                                if (localSeen.isNotEmpty()) {
                                    tracker.api.syncPlayedEpisodes(track.remoteUrl, localSeen.map { it.url })
                                    tracker.refresh(track.toDbTrack()).toDomainTrack()!!
                                } else {
                                    val dbTrack = track.toDbTrack().apply { last_episode_seen = episodeNumber }
                                    tracker.update(dbTrack, true).toDomainTrack()!!
                                }
                            } else {
                                val refreshed = tracker.refresh(track.toDbTrack())
                                    .toDomainTrack(idRequired = true)!!
                                    .copy(lastEpisodeSeen = episodeNumber)
                                tracker.update(refreshed.toDbTrack(), true)
                                refreshed
                            }
                            insertTrack.await(updatedTrack)
                            delayedTrackingStore.remove(
                                track.id,
                                if (reportEpisode) episodeUrl else null,
                                if (tracker is Jellyfin) animeId else null,
                            )
                        } catch (e: Exception) {
                            delayedTrackingStore.add(
                                track.id,
                                episodeNumber,
                                if (reportEpisode) episodeUrl else null,
                                if (tracker is Jellyfin) animeId else null,
                            )
                            if (setupJobOnFailure) {
                                DelayedAnimeTrackingUpdateJob.setupTask(context)
                            }
                            throw e
                        }
                    }
                }
            }
                .awaitAll()
                .mapNotNull { it.exceptionOrNull() }
                .forEach { logcat(LogPriority.INFO, it) }
        }
    }
}
