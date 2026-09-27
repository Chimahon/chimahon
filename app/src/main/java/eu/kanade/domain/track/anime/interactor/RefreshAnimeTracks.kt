package eu.kanade.domain.track.anime.interactor

import android.app.Application
import eu.kanade.domain.track.anime.model.toDbTrack
import eu.kanade.domain.track.anime.model.toDomainTrack
import eu.kanade.domain.track.interactor.SyncEpisodeProgressWithTrack
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack
import tachiyomi.i18n.ank.AMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class RefreshAnimeTracks(
    private val getTracks: GetAnimeTracks,
    // AY -->
    private val getAnime: GetAnime,
    // <-- AY
    private val trackerManager: TrackerManager,
    private val insertTrack: InsertAnimeTrack,
    private val syncEpisodeProgressWithTrack: SyncEpisodeProgressWithTrack,
) {

    /**
     * Fetches updated tracking data from all logged-in anime trackers.
     *
     * @return failed updates.
     */
    suspend fun await(animeId: Long): List<Pair<Tracker?, Throwable>> {
        return supervisorScope {
            // AY -->
            // Read the track from the parent series; the episodes synced below stay on the season.
            val trackAnimeId = getAnime.await(animeId)?.parentId ?: animeId
            // <-- AY
            getTracks.await(trackAnimeId)
                .map { it to trackerManager.get(it.trackerId) }
                .filter { (_, service) -> service?.isLoggedIn == true && service is AnimeTracker }
                .map { (track, service) ->
                    async {
                        try {
                            val animeService = service as AnimeTracker
                            val updatedTrack = animeService.refresh(track.toDbTrack()).toDomainTrack()!!
                            insertTrack.await(updatedTrack)
                            // KMK -->
                            syncEpisodeProgressWithTrack.sync(animeId, updatedTrack, animeService)
                                ?.let {
                                    val context = Injekt.get<Application>()
                                    withUIContext {
                                        context.toast(
                                            context.stringResource(
                                                AMR.strings.sync_progress_from_trackers_up_to_episode,
                                                it,
                                            ),
                                        )
                                    }
                                }
                            // KMK <--
                            null
                        } catch (e: Throwable) {
                            service to e
                        }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
    }
}
