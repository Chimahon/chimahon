package eu.kanade.domain.track.anime.interactor

import eu.kanade.domain.track.anime.model.toDbTrack
import eu.kanade.domain.track.anime.model.toDomainTrack
import eu.kanade.domain.track.interactor.SyncEpisodeProgressWithTrack
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.data.database.models.anime.AnimeTrack
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.EnhancedAnimeTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.lang.convertEpochMillisZone
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.GetAnimeSeasonsByParentId
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.history.interactor.GetAnimeHistory
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.ZoneOffset

class AddAnimeTracks(
    private val insertTrack: InsertAnimeTrack,
    private val syncEpisodeProgressWithTrack: SyncEpisodeProgressWithTrack,
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId,
    private val trackerManager: TrackerManager,
) {

    // TODO: update all trackers based on common data
    suspend fun bind(tracker: AnimeTracker, item: AnimeTrack, animeId: Long) = withNonCancellableContext {
        withIOContext {
            val allEpisodes = getEpisodesByAnimeId.await(animeId)
            val hasSeenEpisodes = allEpisodes.any { it.seen }
            tracker.bind(item, hasSeenEpisodes)

            var track = item.toDomainTrack(idRequired = false) ?: return@withIOContext

            insertTrack.await(track)

            // Update episode progress if newer episodes marked seen locally
            if (hasSeenEpisodes) {
                val latestLocalSeenEpisodeNumber = allEpisodes
                    .sortedBy { it.episodeNumber }
                    .takeWhile { it.seen }
                    .lastOrNull()
                    ?.episodeNumber ?: -1.0

                if (latestLocalSeenEpisodeNumber > track.lastEpisodeSeen) {
                    track = track.copy(
                        lastEpisodeSeen = latestLocalSeenEpisodeNumber,
                    )
                    tracker.setRemoteLastEpisodeSeen(track.toDbTrack(), latestLocalSeenEpisodeNumber.toInt())
                }

                if (track.startDate <= 0) {
                    val firstSeenEpisodeDate = Injekt.get<GetAnimeHistory>().await(animeId)
                        .sortedBy { it.watchedAt }
                        .firstOrNull()
                        ?.watchedAt

                    firstSeenEpisodeDate?.let {
                        val startDate = firstSeenEpisodeDate.time.convertEpochMillisZone(
                            ZoneOffset.systemDefault(),
                            ZoneOffset.UTC,
                        )
                        track = track.copy(
                            startDate = startDate,
                        )
                        tracker.setRemoteStartDate(track.toDbTrack(), startDate)
                    }
                }
            }

            syncEpisodeProgressWithTrack.await(animeId, track, tracker)
        }
    }

    suspend fun bindEnhancedTrackers(anime: Anime, source: AnimeSource): Unit = withNonCancellableContext {
        withIOContext {
            trackerManager.loggedInAnimeTrackers()
                .filterIsInstance<EnhancedAnimeTracker>()
                .filter { it.accept(source) }
                .forEach { service ->
                    try {
                        val match = when (anime.fetchType) {
                            FetchType.Seasons -> service.matchSeason(anime)
                            FetchType.Episodes -> service.match(anime)
                        }
                        match?.let { track ->
                            val tracker = service as AnimeTracker
                            track.anime_id = anime.id
                            tracker.bind(track)
                            insertTrack.await(track.toDomainTrack(idRequired = false)!!)

                            when (anime.fetchType) {
                                FetchType.Seasons -> {
                                    val seasons = Injekt.get<GetAnimeSeasonsByParentId>().await(anime.id)
                                        .filter { it.anime.fetchType == FetchType.Episodes }
                                    seasons.chunked(5).forEach { ss ->
                                        supervisorScope {
                                            ss.map { s ->
                                                async<Unit> { bindEnhancedTrackers(s.anime, source) }
                                            }.awaitAll()
                                        }
                                    }
                                }
                                FetchType.Episodes -> {
                                    syncEpisodeProgressWithTrack.await(
                                        anime.id,
                                        track.toDomainTrack(idRequired = false)!!,
                                        tracker,
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logcat(
                            LogPriority.WARN,
                            e,
                        ) { "Could not match anime: ${anime.title} with service $service" }
                    }
                }
        }
    }
}
