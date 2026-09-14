package mihon.domain.animemigration.usecases

import eu.kanade.domain.entries.anime.interactor.UpdateAnime
import eu.kanade.domain.entries.anime.interactor.SyncSeasonsWithSource
import eu.kanade.domain.entries.anime.model.hasCustomBackground
import eu.kanade.domain.entries.anime.model.hasCustomCover
import eu.kanade.domain.entries.anime.model.toSAnime
import eu.kanade.domain.episode.interactor.SyncEpisodesWithSource
import eu.kanade.domain.track.anime.model.toDbTrack
import eu.kanade.domain.track.anime.model.toDomainTrack
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.data.animedownload.AnimeDownloadManager
import eu.kanade.tachiyomi.data.cache.AnimeBackgroundCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.track.EnhancedAnimeTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CancellationException
import mihon.domain.animemigration.models.AnimeMigrationFlag
import tachiyomi.domain.category.interactor.GetAnimeCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeUpdate
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.episode.interactor.UpdateEpisode
import tachiyomi.domain.episode.model.toEpisodeUpdate
import tachiyomi.domain.history.interactor.GetAnimeHistory
import tachiyomi.domain.history.interactor.UpsertAnimeHistory
import tachiyomi.domain.history.model.AnimeHistoryUpdate
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack
import java.time.Instant

class MigrateAnimeUseCase(
    private val trackerManager: TrackerManager,
    private val sourceManager: AnimeSourceManager,
    private val downloadManager: AnimeDownloadManager,
    private val updateAnime: UpdateAnime,
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId,
    private val syncEpisodesWithSource: SyncEpisodesWithSource,
    private val syncSeasonsWithSource: SyncSeasonsWithSource,
    private val updateEpisode: UpdateEpisode,
    private val getAnimeHistory: GetAnimeHistory,
    private val upsertAnimeHistory: UpsertAnimeHistory,
    private val getCategories: GetAnimeCategories,
    private val setAnimeCategories: SetAnimeCategories,
    private val getTracks: GetAnimeTracks,
    private val insertTrack: InsertAnimeTrack,
    private val coverCache: CoverCache,
    private val backgroundCache: AnimeBackgroundCache,
) {
    private val enhancedServices by lazy { trackerManager.loggedInAnimeTrackers().filterIsInstance<EnhancedAnimeTracker>() }

    suspend operator fun invoke(
        current: Anime,
        target: Anime,
        replace: Boolean,
        flags: Set<AnimeMigrationFlag>,
    ): Boolean {
        val targetSource = sourceManager.get(target.source) ?: return false
        val currentSource = sourceManager.get(current.source)

        try {
            when (target.fetchType) {
                FetchType.Seasons -> {
                    val seasons = targetSource.getSeasonList(target.toSAnime())

                    try {
                        syncSeasonsWithSource.await(seasons, target, targetSource)
                    } catch (_: Exception) {
                        // Worst case, seasons won't be synced before flags are copied.
                    }
                }
                FetchType.Episodes -> {
                    val episodes = targetSource.getEpisodeList(target.toSAnime())

                    try {
                        syncEpisodesWithSource.await(episodes, target, targetSource)
                    } catch (_: Exception) {
                        // Worst case, episodes won't be synced before flags are copied.
                    }
                }
            }

            if (AnimeMigrationFlag.EPISODE in flags && target.fetchType == FetchType.Episodes) {
                val previousEpisodes = getEpisodesByAnimeId.await(current.id)
                val targetEpisodes = getEpisodesByAnimeId.await(target.id)
                val historyUpdates = mutableListOf<AnimeHistoryUpdate>()
                val previousHistory = getAnimeHistory.await(current.id)
                    .associateBy { it.episodeId }

                val maxEpisodeSeen = previousEpisodes
                    .filter { it.seen }
                    .maxOfOrNull { it.episodeNumber }

                val updatedEpisodes = targetEpisodes.map { targetEpisode ->
                    var updatedEpisode = targetEpisode
                    if (updatedEpisode.isRecognizedNumber) {
                        val previousEpisode = previousEpisodes
                            .find { it.isRecognizedNumber && it.episodeNumber == updatedEpisode.episodeNumber }

                        if (previousEpisode != null) {
                            updatedEpisode = updatedEpisode.copy(
                                seen = previousEpisode.seen,
                                dateFetch = previousEpisode.dateFetch,
                                bookmark = previousEpisode.bookmark,
                                lastSecondSeen = previousEpisode.lastSecondSeen,
                            )
                            previousHistory[previousEpisode.id]?.let { history ->
                                historyUpdates += AnimeHistoryUpdate(
                                    episodeId = updatedEpisode.id,
                                    watchedAt = history.watchedAt ?: return@let,
                                    sessionWatchDuration = history.watchDuration,
                                )
                            }
                        } else if (maxEpisodeSeen != null && updatedEpisode.episodeNumber <= maxEpisodeSeen) {
                            updatedEpisode = updatedEpisode.copy(seen = true)
                        }
                    }

                    updatedEpisode
                }

                updateEpisode.awaitAll(updatedEpisodes.map { it.toEpisodeUpdate() })
                historyUpdates.forEach { upsertAnimeHistory.await(it) }
            }

            if (AnimeMigrationFlag.CATEGORY in flags) {
                val categoryIds = getCategories.await(current.id).map { it.id }
                setAnimeCategories.await(target.id, categoryIds)
            }

            if (AnimeMigrationFlag.TRACK in flags) {
                getTracks.await(current.id).mapNotNull { track ->
                    val updatedTrack = track.copy(animeId = target.id)
                    val dbTrack = updatedTrack.toDbTrack()
                    val service = enhancedServices
                        .firstOrNull { it.isTrackFrom(dbTrack, current, currentSource) }

                    if (service != null) {
                        service.migrateTrack(dbTrack, target, targetSource)
                            ?.toDomainTrack(idRequired = false)
                    } else {
                        updatedTrack
                    }
                }
                    .takeIf { it.isNotEmpty() }
                    ?.let { insertTrack.awaitAll(it) }
            }

            if (AnimeMigrationFlag.REMOVE_DOWNLOAD in flags && currentSource != null &&
                current.fetchType == FetchType.Episodes
            ) {
                downloadManager.deleteAnime(current, currentSource)
            }

            if (AnimeMigrationFlag.CUSTOM_COVER in flags && current.hasCustomCover(coverCache)) {
                coverCache.setCustomCoverToCache(target, coverCache.getCustomCoverFile(current.id).inputStream())
            }

            if (AnimeMigrationFlag.CUSTOM_BACKGROUND in flags && current.hasCustomBackground(backgroundCache)) {
                backgroundCache.setCustomBackgroundToCache(
                    target,
                    backgroundCache.getCustomBackgroundFile(current.id).inputStream(),
                )
            }

            val currentAnimeUpdate = AnimeUpdate(
                id = current.id,
                favorite = false,
                dateAdded = 0,
            )
                .takeIf { replace }
            val targetAnimeUpdate = AnimeUpdate(
                id = target.id,
                favorite = true,
                episodeFlags = current.episodeFlags.takeIf { AnimeMigrationFlag.EXTRA in flags },
                viewerFlags = current.viewerFlags.takeIf { AnimeMigrationFlag.EXTRA in flags },
                dateAdded = if (replace) current.dateAdded else Instant.now().toEpochMilli(),
            )

            updateAnime.awaitAll(listOfNotNull(currentAnimeUpdate, targetAnimeUpdate))
            return true
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            return false
        }
    }
}
