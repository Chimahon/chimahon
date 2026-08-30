package eu.kanade.domain.track.interactor

import eu.kanade.domain.entries.anime.interactor.UpdateAnime
import eu.kanade.domain.entries.anime.model.toSAnime
import eu.kanade.domain.episode.interactor.SyncEpisodesWithSource
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.track.anime.interactor.AddAnimeTracks
import eu.kanade.domain.track.anime.model.toDbTrack
import eu.kanade.domain.track.anime.model.toDomainTrack
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.anilist.dto.ALMediaListEntry
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.online.LoginSource
import logcat.LogPriority
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.source.NoResultsException
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.episode.interactor.UpdateEpisode
import tachiyomi.domain.episode.model.toEpisodeUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack
import tachiyomi.domain.track.anime.model.AnimeTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SyncAniListToLibrary {

    private val trackerManager: TrackerManager = Injekt.get()
    private val getTracks: GetTracks = Injekt.get()
    private val getAnimeTracks: GetAnimeTracks = Injekt.get()
    private val insertTrack: InsertTrack = Injekt.get()
    private val insertAnimeTrack: InsertAnimeTrack = Injekt.get()
    private val syncChapterProgressWithTrack: SyncChapterProgressWithTrack = Injekt.get()
    private val getManga: GetManga = Injekt.get()
    private val getAnime: GetAnime = Injekt.get()
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get()
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get()
    private val updateManga: UpdateManga = Injekt.get()
    private val updateAnime: UpdateAnime = Injekt.get()
    private val updateMangaFromRemote: UpdateMangaFromRemote = Injekt.get()
    private val setMangaCategories: SetMangaCategories = Injekt.get()
    private val setAnimeCategories: SetAnimeCategories = Injekt.get()
    private val addTracks: AddTracks = Injekt.get()
    private val addAnimeTracks: AddAnimeTracks = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()
    private val animeSourceManager: AnimeSourceManager = Injekt.get()
    private val syncEpisodesWithSource: SyncEpisodesWithSource = Injekt.get()
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId = Injekt.get()
    private val updateEpisode: UpdateEpisode = Injekt.get()

    /**
     * Pulls progress from AniList into all tracked library entries (manga & anime),
     * and adds new AniList list entries to the library when an exact title match
     * is found in an installed source.
     */
    suspend fun await() = withIOContext {
        val aniList = trackerManager.aniList
        if (!aniList.isLoggedIn) return@withIOContext

        val mangaTracks = getTracks.await().filter { it.trackerId == TrackerManager.ANILIST }
        val animeTracks = getAnimeTracks.awaitAll().filter { it.trackerId == TrackerManager.ANILIST }

        // Pull progress for already tracked entries
        mangaTracks.forEach { track ->
            try {
                val updated = aniList.refresh(track.toDbTrack())
                insertTrack.await(updated.toDomainTrack()!!)
                syncChapterProgressWithTrack.sync(track.mangaId, updated.toDomainTrack()!!, aniList)
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Could not refresh AniList track for manga ${track.mangaId}" }
            }
        }
        animeTracks.forEach { track ->
            try {
                val updated = aniList.refresh(track.toDbTrack())
                insertAnimeTrack.await(updated.toDomainTrack(idRequired = false)!!)
                markEpisodesSeen(track.animeId, updated.toDomainTrack(idRequired = false)!!, aniList)
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Could not refresh AniList track for anime ${track.animeId}" }
            }
        }

        // Auto-add new AniList entries found in sources by exact title
        try {
            val boundMangaIds = mangaTracks.map { it.remoteId }.toSet()
            aniList.getFullMangaList()
                .filter { it.media.id !in boundMangaIds }
                .forEach { entry ->
                    try {
                        addMangaEntry(entry, aniList)
                    } catch (e: Throwable) {
                        logcat(LogPriority.WARN, e) { "Could not add AniList manga ${entry.media.title.userPreferred}" }
                    }
                }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Could not fetch AniList manga list" }
        }
        try {
            val boundAnimeIds = animeTracks.map { it.remoteId }.toSet()
            aniList.getFullAnimeList()
                .filter { it.media.id !in boundAnimeIds }
                .forEach { entry ->
                    try {
                        addAnimeEntry(entry, aniList)
                    } catch (e: Throwable) {
                        logcat(LogPriority.WARN, e) { "Could not add AniList anime ${entry.media.title.userPreferred}" }
                    }
                }
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Could not fetch AniList anime list" }
        }
    }

    private suspend fun addMangaEntry(entry: ALMediaListEntry, aniList: Anilist) {
        val title = entry.media.title.userPreferred
        val (networkManga, source) = findMangaInSources(title) ?: return

        val existing = getManga.await(networkManga.url, source.id)
        val isNew = existing == null
        val wasUnfavorited = existing?.favorite == false
        val dbManga = when {
            existing == null -> networkToLocalManga(networkManga)
            wasUnfavorited -> {
                updateManga.awaitUpdateFavorite(existing.id, true)
                getManga.await(networkManga.url, source.id) ?: existing
            }
            else -> existing
        }

        if (isNew || wasUnfavorited) {
            setMangaCategories.await(dbManga.id, emptyList())
        }

        updateMangaFromRemote(
            manga = dbManga,
            fetchDetails = true,
            fetchChapters = true,
            manualFetch = true,
        )

        val item = aniList.searchById(entry.media.id.toString()) ?: return
        addTracks.bind(aniList, item, dbManga.id)

        val track = getTracks.await(dbManga.id)
            .firstOrNull { it.trackerId == TrackerManager.ANILIST } ?: return
        syncChapterProgressWithTrack.sync(dbManga.id, track, aniList)
        logcat(LogPriority.INFO) { "Synced AniList manga \"$title\" with source ${source.name}" }
    }

    private suspend fun addAnimeEntry(entry: ALMediaListEntry, aniList: Anilist) {
        val title = entry.media.title.userPreferred
        val (networkAnime, source) = findAnimeInSources(title) ?: return

        val existing = getAnime.await(networkAnime.url, source.id)
        val isNew = existing == null
        val wasUnfavorited = existing?.favorite == false
        val dbAnime = when {
            existing == null -> networkToLocalAnime(networkAnime)
            wasUnfavorited -> {
                updateAnime.awaitUpdateFavorite(existing.id, true)
                getAnime.await(networkAnime.url, source.id) ?: existing
            }
            else -> existing
        }

        if (isNew || wasUnfavorited) {
            setAnimeCategories.await(dbAnime.id, emptyList())
        }

        try {
            val details = source.getAnimeDetails(dbAnime.toSAnime())
            updateAnime.awaitUpdateFromSource(dbAnime, details, manualFetch = true)
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Could not fetch details for AniList anime \"$title\"" }
        }
        val initialized = getAnime.await(networkAnime.url, source.id) ?: dbAnime

        try {
            val rawEpisodes = source.getEpisodeList(initialized.toSAnime())
            if (rawEpisodes.isNotEmpty()) {
                syncEpisodesWithSource.await(rawEpisodes, initialized, source, manualFetch = false)
            }
        } catch (e: NoResultsException) {
            // No episodes available yet
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Could not fetch episodes for AniList anime \"$title\"" }
        }

        val item = aniList.searchAnimeById(entry.media.id) ?: return
        item.anime_id = initialized.id
        addAnimeTracks.bind(aniList, item, initialized.id)

        val track = getAnimeTracks.await(initialized.id)
            .firstOrNull { it.trackerId == TrackerManager.ANILIST } ?: return
        markEpisodesSeen(initialized.id, track, aniList)
        logcat(LogPriority.INFO) { "Synced AniList anime \"$title\" with source ${source.name}" }
    }

    private suspend fun findMangaInSources(title: String): Pair<Manga, Source>? {
        for (source in sourceManager.getOnlineSources()) {
            if (source is LoginSource && source.requiresLogin && !source.isLogged()) continue
            val results = try {
                source.getSearchManga(1, title, FilterList())
            } catch (e: Throwable) {
                continue
            }
            val match = results.mangas
                .firstOrNull { AniListTitleMatcher.matches(title, it.title) }
                ?: continue
            val manga = Manga.create().copy(
                url = match.url,
                ogTitle = match.title,
                source = source.id,
                favorite = true,
                dateAdded = System.currentTimeMillis(),
            )
            return manga to source
        }
        return null
    }

    private suspend fun findAnimeInSources(title: String): Pair<Anime, AnimeSource>? {
        for (source in animeSourceManager.getCatalogueSources()) {
            val results = try {
                source.getSearchAnime(1, title, AnimeFilterList())
            } catch (e: Throwable) {
                continue
            }
            val match = results.animes
                .firstOrNull { AniListTitleMatcher.matches(title, it.title) }
                ?: continue
            val anime = Anime.create().copy(
                url = match.url,
                ogTitle = match.title,
                source = source.id,
                favorite = true,
                dateAdded = System.currentTimeMillis(),
            )
            return anime to source
        }
        return null
    }

    private suspend fun markEpisodesSeen(animeId: Long, remoteTrack: AnimeTrack, aniList: Anilist) {
        if (remoteTrack.status == AniList.PLAN_TO_WATCH || remoteTrack.status == AniList.PLAN_TO_READ) return

        val dbEpisodes = getEpisodesByAnimeId.await(animeId)
            .sortedByDescending { it.sourceOrder }
            .filter { it.isRecognizedNumber }

        var lastCheckChapter: Double
        var checkingChapter = 0.0

        // Only continuous incremental episodes, abnormal numbering stops propagation
        val episodeUpdates = dbEpisodes
            .takeWhile { episode ->
                lastCheckChapter = checkingChapter
                checkingChapter = episode.episodeNumber
                episode.episodeNumber >= lastCheckChapter && episode.episodeNumber <= remoteTrack.lastEpisodeSeen
            }
            .filter { !it.seen }
            .map { it.copy(seen = true).toEpisodeUpdate() }

        if (episodeUpdates.isNotEmpty()) {
            updateEpisode.awaitAll(episodeUpdates)
        }
    }
}
