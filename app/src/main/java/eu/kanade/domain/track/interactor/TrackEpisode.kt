package eu.kanade.domain.track.interactor

import android.content.Context
import eu.kanade.domain.track.anime.model.toDbTrack
import eu.kanade.domain.track.anime.model.toDomainTrack
import eu.kanade.domain.track.service.DelayedAnimeTrackingUpdateJob
import eu.kanade.domain.track.store.DelayedAnimeTrackingStore
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.system.isOnline
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack

class TrackEpisode(
    private val getTracks: GetAnimeTracks,
    private val getAnime: GetAnime,
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertAnimeTrack,
    private val delayedTrackingStore: DelayedAnimeTrackingStore,
) {

    suspend fun await(context: Context, animeId: Long, episodeNumber: Double, setupJobOnFailure: Boolean = true) {
        withNonCancellableContext {
            // AY -->
            // A season's track lives on the parent series, not on the season row.
            val trackAnimeId = getAnime.await(animeId)?.parentId ?: animeId
            // <-- AY
            val tracks = getTracks.await(trackAnimeId)
            if (tracks.isEmpty()) return@withNonCancellableContext

            tracks.mapNotNull { track ->
                val service = trackerManager.get(track.trackerId)
                val tracker = service as? AnimeTracker
                if (service == null || tracker == null || !service.isLoggedIn || episodeNumber <= track.lastEpisodeSeen) {
                    return@mapNotNull null
                }

                async {
                    runCatching {
                        try {
                            if (!context.isOnline()) {
                                error("No network available")
                            }
                            val updatedTrack = tracker.refresh(track.toDbTrack())
                                .toDomainTrack(idRequired = true)!!
                                .copy(lastEpisodeSeen = episodeNumber)
                            tracker.update(updatedTrack.toDbTrack(), true)
                            insertTrack.await(updatedTrack)
                            delayedTrackingStore.remove(track.id)
                        } catch (e: Exception) {
                            delayedTrackingStore.add(track.id, episodeNumber)
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
