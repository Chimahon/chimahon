package eu.kanade.tachiyomi.ui.entries.anime

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Immutable
import androidx.palette.graphics.Palette
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import coil3.Image
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.util.addOrRemove
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.entries.anime.interactor.SetAnimeViewerFlags
import eu.kanade.domain.entries.anime.interactor.SyncSeasonsWithSource
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.entries.anime.interactor.UpdateAnime
import eu.kanade.domain.entries.anime.model.downloadedFilter
import eu.kanade.domain.entries.anime.model.episodesFiltered
import eu.kanade.domain.entries.anime.model.seasonBookmarkedFilter
import eu.kanade.domain.entries.anime.model.seasonCompletedFilter
import eu.kanade.domain.entries.anime.model.seasonDownloadedFilter
import eu.kanade.domain.entries.anime.model.seasonFillermarkedFilter
import eu.kanade.domain.entries.anime.model.seasonStartedFilter
import eu.kanade.domain.entries.anime.model.seasonUnseenFilter
import eu.kanade.domain.entries.anime.model.seasonsFiltered
import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.domain.entries.anime.model.toSAnime
import eu.kanade.domain.episode.interactor.GetAvailableAnimeScanlators
import eu.kanade.domain.episode.interactor.GetExcludedAnimeScanlators
import eu.kanade.domain.episode.interactor.SetSeenStatus
import eu.kanade.domain.episode.interactor.SetExcludedAnimeScanlators
import eu.kanade.domain.episode.interactor.SyncEpisodesWithSource
import eu.kanade.domain.track.anime.interactor.AddAnimeTracks
import eu.kanade.domain.track.anime.interactor.RefreshAnimeTracks
import eu.kanade.domain.track.interactor.TrackEpisode
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.presentation.entries.DownloadAction
import eu.kanade.presentation.entries.anime.components.EpisodeDownloadAction
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.animedownload.AnimeDownloadCache
import eu.kanade.tachiyomi.data.animedownload.AnimeDownloadManager
import eu.kanade.tachiyomi.data.animedownload.model.AnimeDownload
import eu.kanade.tachiyomi.data.coil.getBestColor
import eu.kanade.tachiyomi.data.torrentServer.service.TorrentServerService
import eu.kanade.tachiyomi.data.track.EnhancedAnimeTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.source.isSourceForTorrents
import eu.kanade.tachiyomi.torrentServer.TorrentServerUtils
import eu.kanade.tachiyomi.ui.entries.anime.RelatedAnime.Companion.isLoading
import eu.kanade.tachiyomi.ui.entries.anime.RelatedAnime.Companion.removeDuplicates
import eu.kanade.tachiyomi.ui.entries.anime.RelatedAnime.Companion.sorted
import eu.kanade.tachiyomi.ui.entries.anime.track.TrackItem
import eu.kanade.tachiyomi.ui.player.settings.GesturePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import eu.kanade.tachiyomi.util.AniChartApi
import eu.kanade.tachiyomi.util.episode.getNextUnseen
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import eu.kanade.tachiyomi.util.system.toast
import exh.util.nullIfEmpty
import exh.util.trimOrNull
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.domain.episode.interactor.FilterEpisodesForDownload
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.source.NoResultsException
import tachiyomi.domain.history.interactor.UpsertAnimeHistory
import tachiyomi.domain.history.model.AnimeHistoryUpdate
import tachiyomi.domain.entries.anime.interactor.GetAnimeWithEpisodes
import tachiyomi.domain.entries.anime.interactor.GetDuplicateLibraryAnime
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.anime.interactor.SetAnimeEpisodeFlags
import tachiyomi.domain.entries.anime.interactor.SetAnimeSeasonFlags
import tachiyomi.domain.entries.anime.interactor.SetCustomAnimeInfo
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeCover
import tachiyomi.domain.entries.anime.model.AnimeUpdate
import tachiyomi.domain.entries.anime.model.CustomAnimeInfo
import tachiyomi.domain.entries.anime.model.NoSeasonsException
import tachiyomi.domain.entries.anime.model.SeasonAnime
import tachiyomi.domain.entries.anime.model.SeasonDisplayMode
import tachiyomi.domain.entries.anime.model.applyFilter
import tachiyomi.domain.entries.anime.model.asAnimeCover
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.category.interactor.GetAnimeCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.AnimeCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.episode.interactor.SetAnimeDefaultEpisodeFlags
import tachiyomi.domain.season.interactor.SetAnimeDefaultSeasonFlags
import tachiyomi.domain.episode.interactor.UpdateEpisode
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.episode.model.EpisodeUpdate
import tachiyomi.domain.episode.service.calculateChapterGap
import tachiyomi.domain.episode.service.getEpisodeSort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.anime.model.StubAnimeSource
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.track.anime.repository.AnimeTrackRepository
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.source.local.entries.anime.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Calendar
import kotlin.math.floor

class AnimeScreenModel(
    private val context: Context,
    private val lifecycle: Lifecycle,
    private val animeId: Long,
    private val isFromSource: Boolean,
    private val downloadPreferences: DownloadPreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val trackPreferences: TrackPreferences = Injekt.get(),
    internal val playerPreferences: PlayerPreferences = Injekt.get(),
    internal val gesturePreferences: GesturePreferences = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val trackEpisode: TrackEpisode = Injekt.get(),
    private val animeDownloadManager: AnimeDownloadManager = Injekt.get(),
    private val animeDownloadCache: AnimeDownloadCache = Injekt.get(),
    private val getAnimeAndEpisodes: GetAnimeWithEpisodes = Injekt.get(),
    // SY -->
    private val animeSourceManager: AnimeSourceManager = Injekt.get(),
    private val setCustomAnimeInfo: SetCustomAnimeInfo = Injekt.get(),
    // SY <--
    private val getDuplicateLibraryAnime: GetDuplicateLibraryAnime = Injekt.get(),
    private val setAnimeEpisodeFlags: SetAnimeEpisodeFlags = Injekt.get(),
    private val setAnimeDefaultEpisodeFlags: SetAnimeDefaultEpisodeFlags = Injekt.get(),
    private val setAnimeDefaultSeasonFlags: SetAnimeDefaultSeasonFlags = Injekt.get(),
    private val setSeenStatus: SetSeenStatus = Injekt.get(),
    private val upsertAnimeHistory: UpsertAnimeHistory = Injekt.get(),
    private val getAvailableAnimeScanlators: GetAvailableAnimeScanlators = Injekt.get(),
    private val getExcludedAnimeScanlators: GetExcludedAnimeScanlators = Injekt.get(),
    private val setExcludedAnimeScanlators: SetExcludedAnimeScanlators = Injekt.get(),
    private val updateEpisode: UpdateEpisode = Injekt.get(),
    private val updateAnime: UpdateAnime = Injekt.get(),
    private val syncEpisodesWithSource: SyncEpisodesWithSource = Injekt.get(),
    private val syncSeasonsWithSource: SyncSeasonsWithSource = Injekt.get(),
    private val getAnimeCategories: GetAnimeCategories = Injekt.get(),
    private val addTracks: AddAnimeTracks = Injekt.get(),
    private val refreshAnimeTracks: RefreshAnimeTracks = Injekt.get(),
    private val setAnimeCategories: SetAnimeCategories = Injekt.get(),
    private val animeRepository: AnimeRepository = Injekt.get(),
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
    private val animeTrackRepository: AnimeTrackRepository = Injekt.get(),
    private val filterEpisodesForDownload: FilterEpisodesForDownload = Injekt.get(),
    private val setAnimeSeasonFlags: SetAnimeSeasonFlags = Injekt.get(),
    internal val setAnimeViewerFlags: SetAnimeViewerFlags = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
    // AM (FILE_SIZE) -->
    private val storagePreferences: StoragePreferences = Injekt.get(),
    // <-- AM (FILE_SIZE)
) : StateScreenModel<AnimeScreenModel.State>(State.Loading) {
    val themeCoverBased = Injekt.get<UiPreferences>().themeCoverBased().get()

    private val successState: State.Success?
        get() = state.value as? State.Success

    val anime: Anime?
        get() = successState?.anime

    val source: AnimeSource?
        get() = successState?.source

    private val isFavorited: Boolean
        get() = anime?.favorite ?: false

    private val processedEpisodes: List<EpisodeList.Item>?
        get() = successState?.processedEpisodes

    val episodeSwipeStartAction = libraryPreferences.swipeEpisodeEndAction().get()
    val episodeSwipeEndAction = libraryPreferences.swipeEpisodeStartAction().get()
    var autoTrackState = trackPreferences.autoUpdateTrackOnMarkRead().get()

    val showNextEpisodeAirTime = trackPreferences.showNextEpisodeAiringTime().get()
    val alwaysUseExternalPlayer = playerPreferences.alwaysUseExternalPlayer().get()
    val useExternalDownloader = downloadPreferences.useExternalDownloader().get()

    val isUpdateIntervalEnabled =
        LibraryPreferences.ANIME_OUTSIDE_RELEASE_PERIOD in libraryPreferences.autoUpdateAnimeRestrictions().get()

    private val selectedPositions: Array<Int> = arrayOf(-1, -1) // first and last selected index in list
    private val selectedEpisodeIds: HashSet<Long> = HashSet()

    internal var isFromChangeCategory: Boolean = false

    internal val autoOpenTrack: Boolean
        get() = successState?.trackingAvailable == true && trackPreferences.trackOnAddingToLibrary().get()

    // AM (FILE_SIZE) -->
    val showFileSize = storagePreferences.showEpisodeFileSize().get()
    // <-- AM (FILE_SIZE)

    /**
     * Helper function to update the UI state only if it's currently in success state
     */
    private inline fun updateSuccessState(func: (State.Success) -> State.Success) {
        mutableState.update {
            when (it) {
                State.Loading -> it
                is State.Success -> func(it)
            }
        }
    }

    init {
        screenModelScope.launchIO {
            combine(
                getAnimeAndEpisodes.subscribe(animeId, applyScanlatorFilter = true).distinctUntilChanged(),
                animeDownloadCache.changes,
                animeDownloadManager.queueState,
                animeRepository.getAnimeSeasonsByIdAsFlow(animeId),
            ) { animeAndEpisodes, _, _, seasons -> Pair(animeAndEpisodes, seasons) }
                .flowWithLifecycle(lifecycle)
                .collectLatest { (pair, seasons) ->
                    val (anime, episodes) = pair
                    updateSuccessState {
                        it.copy(
                            anime = anime,
                            episodes = episodes.toEpisodeListItems(anime),
                            seasons = seasons.toSeasonItems(anime),
                        )
                    }
                }
        }

        screenModelScope.launchIO {
            getExcludedAnimeScanlators.subscribe(animeId)
                .flowWithLifecycle(lifecycle)
                .distinctUntilChanged()
                .collectLatest { excludedScanlators ->
                    updateSuccessState {
                        it.copy(excludedScanlators = excludedScanlators.toImmutableSet())
                    }
                }
        }

        screenModelScope.launchIO {
            getAvailableAnimeScanlators.subscribe(animeId)
                .flowWithLifecycle(lifecycle)
                .distinctUntilChanged()
                .collectLatest { availableScanlators ->
                    updateSuccessState {
                        it.copy(availableScanlators = availableScanlators.toImmutableSet())
                    }
                }
        }

        observeDownloads()

        screenModelScope.launchIO {
            val anime = getAnimeAndEpisodes.awaitManga(animeId)
            val episodes = getAnimeAndEpisodes.awaitChapters(animeId, applyScanlatorFilter = true)
                .toEpisodeListItems(anime)

            if (!anime.favorite) {
                setAnimeDefaultEpisodeFlags.await(anime)
            }

            val needRefreshInfo = !anime.initialized
            val needRefreshEpisode = episodes.isEmpty() && anime.fetchType == FetchType.Episodes

            val animeSource = Injekt.get<AnimeSourceManager>().getOrStub(anime.source)
            // --> (Torrent)
            if (animeSource.isSourceForTorrents()) {
                TorrentServerService.start()
                TorrentServerService.wait(10)
                TorrentServerUtils.setTrackersList()
            }
            // <-- (Torrent)

            val seasons = animeRepository.getAnimeSeasonsById(animeId)
                .toSeasonItems(anime)
            val needRefreshSeason = seasons.isEmpty() && anime.fetchType == FetchType.Seasons

            // Show what we have earlier
            mutableState.update {
                State.Success(
                    anime = anime,
                    source = animeSource,
                    isFromSource = isFromSource,
                    episodes = episodes,
                    seasons = seasons,
                    availableScanlators = getAvailableAnimeScanlators.await(animeId).toImmutableSet(),
                    excludedScanlators = getExcludedAnimeScanlators.await(animeId).toImmutableSet(),
                    isRefreshingData = needRefreshInfo || needRefreshEpisode || needRefreshSeason,
                    dialog = null,
                )
            }
            // Start observe tracking since it only needs animeId
            observeTrackers()

            // Fetch info-episodes when needed
            if (screenModelScope.isActive) {
                val fetchFromSourceTasks = listOf(
                    async { if (needRefreshInfo) fetchAnimeFromSource() },
                    async { if (needRefreshEpisode) fetchEpisodesFromSource() },
                    async { if (needRefreshSeason) fetchSeasonsFromSource() },
                )
                fetchFromSourceTasks.awaitAll()
            }
            fetchRelatedAnimeFromSource()

            // Initial loading finished
            updateSuccessState { it.copy(isRefreshingData = false) }
        }
    }

    fun setPaletteColor(model: Any) {
        if (model is ImageRequest && model.defined.sizeResolver != null) return

        val imageRequestBuilder = if (model is ImageRequest) {
            model.newBuilder()
        } else {
            ImageRequest.Builder(context).data(model)
        }
            .allowHardware(false)

        val generatePalette: (Image) -> Unit = { image ->
            val bitmap = image.asDrawable(context.resources).getBitmapOrNull()
            if (bitmap != null) {
                Palette.from(bitmap).generate {
                    screenModelScope.launchIO {
                        if (it == null) return@launchIO
                        val animeCover = when (model) {
                            is Anime -> model.asAnimeCover()
                            is AnimeCover -> model
                            else -> return@launchIO
                        }
                        if (animeCover.isAnimeFavorite) {
                            it.dominantSwatch?.let { swatch ->
                                animeCover.dominantCoverColors = swatch.rgb to swatch.titleTextColor
                            }
                        }
                        val vibrantColor = it.getBestColor() ?: return@launchIO
                        animeCover.vibrantCoverColor = vibrantColor
                        updateSuccessState {
                            it.copy(seedColor = Color(vibrantColor))
                        }
                    }
                }
            }
        }

        context.imageLoader.enqueue(
            imageRequestBuilder
                .target(onSuccess = generatePalette)
                .build(),
        )
    }

    fun fetchAllFromSource(manualFetch: Boolean = true) {
        screenModelScope.launch {
            updateSuccessState { it.copy(isRefreshingData = true) }
            val state = successState
            val fetchFromSourceTasks = listOf(
                async { syncTrackers() },
                async { fetchAnimeFromSource(manualFetch) },
                async {
                    when (state?.anime?.fetchType) {
                        FetchType.Seasons -> fetchSeasonsFromSource(manualFetch)
                        else -> fetchEpisodesFromSource(manualFetch)
                    }
                },
            )
            fetchFromSourceTasks.awaitAll()
            updateSuccessState { it.copy(relatedAnimeCollection = null) }
            fetchRelatedAnimeFromSource()
            updateSuccessState { it.copy(isRefreshingData = false) }
            successState?.let { updateAiringTime(it.anime, it.trackItems, manualFetch) }
        }
    }

    private suspend fun syncTrackers() {
        if (!trackPreferences.autoSyncProgressFromTrackers().get()) return

        val state = successState ?: return
        when (state.anime.fetchType) {
            FetchType.Seasons -> {
                if (trackPreferences.smartTrackerSync().get()) {
                    seasons@ for (s in state.seasons) {
                        refreshAnimeTracks.await(s.seasonAnime.id)
                        // Stop cascading once a season still has unseen episodes.
                        // Uses pre-refresh counts as a heuristic; refreshed progress
                        // lands via the track sync inside the refresh call.
                        if (s.seasonAnime.unseenCount > 0) {
                            break@seasons
                        }
                    }
                } else {
                    state.seasons.chunked(5).forEach { ss ->
                        supervisorScope {
                            ss.map { season ->
                                async { refreshAnimeTracks.await(season.seasonAnime.id) }
                            }.awaitAll()
                        }
                    }
                }
            }
            FetchType.Episodes -> {
                refreshAnimeTracks.await(state.anime.id)
            }
        }
    }

    // Anime info - start

    /**
     * Fetch anime information from source.
     */
    private suspend fun fetchAnimeFromSource(manualFetch: Boolean = false) {
        val state = successState ?: return
        try {
            withIOContext {
                val networkAnime = state.source.getAnimeDetails(state.anime.toSAnime())
                updateAnime.awaitUpdateFromSource(state.anime, networkAnime, manualFetch)
            }
        } catch (e: Throwable) {
            // Ignore early hints "errors" that aren't handled by OkHttp
            if (e is HttpException && e.code == 103) return

            logcat(LogPriority.ERROR, e)
            screenModelScope.launch {
                snackbarHostState.showSnackbar(message = with(context) { e.formattedMessage })
            }
        }
    }

    private fun setRelatedAnimeFetchedStatus(state: Boolean) {
        updateSuccessState { it.copy(isRelatedAnimeFetched = state) }
    }

    internal suspend fun fetchRelatedAnimeFromSource() {
        val state = successState ?: return
        if (state.anime.isLocal() || state.source is StubAnimeSource) {
            setRelatedAnimeFetchedStatus(true)
            return
        }

        setRelatedAnimeFetchedStatus(false)

        fun exceptionHandler(e: Throwable) {
            if (e is UnsupportedOperationException) return
            logcat(LogPriority.ERROR, e)
        }

        try {
            state.source.getRelatedAnimeList(
                anime = state.anime.toSAnime(),
                exceptionHandler = ::exceptionHandler,
            ) { pair, _ ->
                val relatedAnime = RelatedAnime.Success.fromPair(pair) { animeList ->
                    animeList
                        .map { it.toDomainAnime(state.source.id) }
                        .distinctBy { it.url }
                        .map { networkToLocalAnime.await(it) }
                }

                updateSuccessState { successState ->
                    val relatedAnimeCollection =
                        successState.relatedAnimeCollection
                            ?.toMutableList()
                            ?.apply { add(relatedAnime) }
                            ?: listOf(relatedAnime)
                    successState.copy(relatedAnimeCollection = relatedAnimeCollection)
                }
            }
        } catch (e: UnsupportedOperationException) {
            // Source does not provide related anime.
        } catch (e: Throwable) {
            exceptionHandler(e)
        } finally {
            setRelatedAnimeFetchedStatus(true)
        }
    }

    // SY -->
    fun updateAnimeInfo(
        title: String?,
        author: String?,
        artist: String?,
        thumbnailUrl: String?,
        description: String?,
        tags: List<String>?,
        status: Long?,
    ) {
        val state = successState ?: return
        var anime = state.anime
        if (state.anime.isLocal()) {
            val newTitle = if (title.isNullOrBlank()) anime.url else title.trim()
            val newAuthor = author?.trimOrNull()
            val newArtist = artist?.trimOrNull()
            val newDesc = description?.trimOrNull()
            anime = anime.copy(
                ogTitle = newTitle,
                ogAuthor = author?.trimOrNull(),
                ogArtist = artist?.trimOrNull(),
                ogDescription = description?.trimOrNull(),
                ogGenre = tags?.nullIfEmpty(),
                ogStatus = status ?: 0,
                lastUpdate = anime.lastUpdate + 1,
            )
            // Local source update not available for anime
            screenModelScope.launchNonCancellable {
                updateAnime.await(
                    AnimeUpdate(
                        anime.id,
                        title = newTitle,
                        author = newAuthor,
                        artist = newArtist,
                        description = newDesc,
                        genre = tags,
                        status = status,
                    ),
                )
            }
        } else {
            val genre = if (!tags.isNullOrEmpty() && tags != state.anime.ogGenre) {
                tags
            } else {
                null
            }
            setCustomAnimeInfo.set(
                CustomAnimeInfo(
                    state.anime.id,
                    title?.trimOrNull(),
                    author?.trimOrNull(),
                    artist?.trimOrNull(),
                    thumbnailUrl?.trimOrNull(),
                    description?.trimOrNull(),
                    genre,
                    status.takeUnless { it == state.anime.ogStatus },
                ),
            )
            anime = anime.copy(lastUpdate = anime.lastUpdate + 1)
        }

        updateSuccessState { successState ->
            successState.copy(anime = anime)
        }
    }
    // SY <--

    fun toggleFavorite() {
        toggleFavorite(
            onRemoved = {
                screenModelScope.launch {
                    if (!hasDownloads()) return@launch
                    val result = snackbarHostState.showSnackbar(
                        message = context.stringResource(MR.strings.delete_downloads_for_anime),
                        actionLabel = context.stringResource(MR.strings.action_delete),
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        deleteDownloads()
                    }
                }
            },
        )
    }

    /**
     * Update favorite status of anime, (removes / adds) anime (to / from) library.
     */
    fun toggleFavorite(
        onRemoved: () -> Unit,
        checkDuplicate: Boolean = true,
    ) {
        val state = successState ?: return
        screenModelScope.launchIO {
            val anime = state.anime

            if (isFavorited) {
                // Remove from library
                if (updateAnime.awaitUpdateFavorite(anime.id, false)) {
                    updateAnime.awaitUpdateCoverLastModified(anime.id)
                    withUIContext { onRemoved() }
                }
            } else {
                // Add to library
                // First, check if duplicate exists if callback is provided
                if (checkDuplicate) {
                    val duplicates = getDuplicateLibraryAnime.await(anime)
                    if (duplicates.isNotEmpty()) {
                        updateSuccessState {
                            it.copy(
                                dialog = Dialog.DuplicateAnime(anime, duplicates),
                            )
                        }
                        return@launchIO
                    }
                }

                // Now check if user previously set categories, when available
                val animeCategories = getCategories()
                val defaultCategoryId = libraryPreferences.defaultAnimeCategory().get().toLong()
                val defaultAnimeCategory = animeCategories.find { it.id == defaultCategoryId }
                when {
                    // Default category set
                    defaultAnimeCategory != null -> {
                        val result = updateAnime.awaitUpdateFavorite(anime.id, true)
                        if (!result) return@launchIO
                        moveAnimeToCategory(
                            Category(
                                id = defaultAnimeCategory.id,
                                name = defaultAnimeCategory.name,
                                order = defaultAnimeCategory.order,
                                flags = defaultAnimeCategory.flags,
                                hidden = defaultAnimeCategory.hidden,
                            ),
                        )
                    }

                    // Automatic 'Default' or no categories
                    defaultCategoryId == 0L || animeCategories.isEmpty() -> {
                        val result = updateAnime.awaitUpdateFavorite(anime.id, true)
                        if (!result) return@launchIO
                        moveAnimeToCategory(null)
                    }

                    // Choose a category
                    else -> {
                        isFromChangeCategory = true
                        showChangeCategoryDialog()
                    }
                }

                // Finally match with enhanced tracking when available
                addTracks.bindEnhancedTrackers(anime, state.source)
                if (autoOpenTrack && !isFromChangeCategory && anime.fetchType == FetchType.Episodes) {
                    showTrackDialog()
                }
            }
        }
    }

    fun showChangeCategoryDialog() {
        val anime = successState?.anime ?: return
        screenModelScope.launch {
            val categories = getCategories()
            val selection = getAnimeCategoryIds(anime)
            val mappedCategories = categories.map { ac ->
                Category(
                    id = ac.id,
                    name = ac.name,
                    order = ac.order,
                    flags = ac.flags,
                    hidden = ac.hidden,
                )
            }
            updateSuccessState { successState ->
                successState.copy(
                    dialog = Dialog.ChangeCategory(
                        anime = anime,
                        initialSelection = mappedCategories.mapAsCheckboxState { it.id in selection }.toImmutableList(),
                    ),
                )
            }
        }
    }

    fun showSetAnimeFetchIntervalDialog() {
        val anime = successState?.anime ?: return
        updateSuccessState {
            it.copy(dialog = Dialog.SetAnimeFetchInterval(anime))
        }
    }

    fun setFetchInterval(anime: Anime, interval: Int) {
        screenModelScope.launchIO {
            if (
                updateAnime.awaitUpdateFetchInterval(
                    // Custom intervals are negative
                    anime.copy(fetchInterval = -interval),
                )
            ) {
                val updatedAnime = animeRepository.getAnimeById(anime.id)
                updateSuccessState { it.copy(anime = updatedAnime) }
            }
        }
    }

    /**
     * Returns true if the anime has any downloads.
     */
    private fun hasDownloads(): Boolean {
        val anime = successState?.anime ?: return false
        return animeDownloadManager.getDownloadCount(anime) > 0
    }

    /**
     * Deletes all the downloads for the anime.
     */
    private fun deleteDownloads() {
        val state = successState ?: return
        screenModelScope.launchNonCancellable {
            animeDownloadManager.deleteAnime(state.anime, state.source)
        }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<AnimeCategory> {
        return getAnimeCategories.await().filterNot { it.isSystemCategory }
    }

    /**
     * Gets the category id's the anime is in, if the anime is not in a category, returns the default id.
     *
     * @param anime the anime to get categories from.
     * @return Array of category ids the anime is in, if none returns default id
     */
    private suspend fun getAnimeCategoryIds(anime: Anime): List<Long> {
        return getAnimeCategories.await(anime.id)
            .map { it.id }
    }

    fun moveAnimeToCategoriesAndAddToLibrary(anime: Anime, categories: List<Long>) {
        moveAnimeToCategory(categories)
        if (anime.favorite) return

        screenModelScope.launchIO {
            updateAnime.awaitUpdateFavorite(anime.id, true)
        }
    }

    /**
     * Move the given anime to categories.
     *
     * @param categories the selected categories.
     */
    private fun moveAnimeToCategories(categories: List<Category>) {
        val categoryIds = categories.map { it.id }
        moveAnimeToCategory(categoryIds)
    }

    private fun moveAnimeToCategory(categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setAnimeCategories.await(animeId, categoryIds)
        }
    }

    /**
     * Move the given anime to the category.
     *
     * @param category the selected category, or null for default category.
     */
    private fun moveAnimeToCategory(category: Category?) {
        moveAnimeToCategories(listOfNotNull(category))
    }

    // Anime info - end

    // Episodes list - start

    private fun observeDownloads() {
        screenModelScope.launchIO {
            animeDownloadManager.queueState
                .filter { queue -> queue.any { it.anime.id == successState?.anime?.id } }
                .catch { error -> logcat(LogPriority.ERROR, error) }
                .flowWithLifecycle(lifecycle)
                .collectLatest { queue ->
                    withUIContext {
                        queue.forEach { download ->
                            updateDownloadState(download)
                        }
                    }
                }
        }
    }

    private fun updateDownloadState(download: AnimeDownload) {
        updateSuccessState { successState ->
            val modifiedIndex = successState.episodes.indexOfFirst { it.id == download.episode.id }
            if (modifiedIndex < 0) return@updateSuccessState successState

            val newEpisodes = successState.episodes.toMutableList().apply {
                val item = removeAt(modifiedIndex)
                    .copy(downloadState = download.status, downloadProgress = download.progress)
                add(modifiedIndex, item)
            }
            successState.copy(episodes = newEpisodes)
        }
    }

    private fun List<Episode>.toEpisodeListItems(anime: Anime): List<EpisodeList.Item> {
        val isLocal = anime.isLocal()
        val activeDownloads = if (isLocal) {
            emptyList()
        } else {
            animeDownloadManager.queueState.value.filter { it.episode.id in this.map { e -> e.id } }
        }
        return map { episode ->
            val activeDownload = activeDownloads.firstOrNull { it.episode.id == episode.id }
            val downloaded = if (isLocal) {
                true
            } else {
                animeDownloadManager.isEpisodeDownloaded(
                    episode.name,
                    episode.scanlator,
                    anime.title,
                    anime.source,
                )
            }
            val downloadState = when {
                activeDownload != null -> activeDownload.status
                downloaded -> AnimeDownload.State.DOWNLOADED
                else -> AnimeDownload.State.NOT_DOWNLOADED
            }

            EpisodeList.Item(
                episode = episode,
                downloadState = downloadState,
                downloadProgress = activeDownload?.progress ?: 0,
                selected = episode.id in selectedEpisodeIds,
            )
        }
    }

    private fun List<SeasonAnime>.toSeasonItems(anime: Anime): List<AnimeSeasonItem> {
        val parentId = anime.id
        return map { seasonAnime ->
            val activeDownloads = animeDownloadManager.queueState.value
                .filter { it.anime.id == seasonAnime.anime.id }
            val downloadedCount = activeDownloads.count { it.status == AnimeDownload.State.DOWNLOADED }
            val downloadingCount = activeDownloads.count {
                it.status == AnimeDownload.State.DOWNLOADING || it.status == AnimeDownload.State.QUEUE
            }
            AnimeSeasonItem(
                seasonAnime = seasonAnime,
                downloadCount = if (anime.seasonDownloadedOverlay) {
                    downloadedCount.toLong() + downloadingCount.toLong()
                } else {
                    -1L
                },
                unseenCount = if (anime.seasonUnseenOverlay) seasonAnime.unseenCount else -1L,
                isLocal = anime.seasonLocalOverlay && seasonAnime.anime.isLocal(),
                sourceLanguage = if (anime.seasonLangOverlay) {
                    animeSourceManager.getOrStub(seasonAnime.anime.source).lang
                } else {
                    ""
                },
                showContinueOverlay = anime.seasonContinueOverlay &&
                    seasonAnime.unseenCount > 0 &&
                    seasonAnime.seenCount > 0 &&
                    seasonAnime.anime.fetchType == FetchType.Episodes,
            )
        }
    }

    /**
     * Requests an updated list of episodes from the source.
     */
    private suspend fun fetchEpisodesFromSource(manualFetch: Boolean = false) {
        val state = successState ?: return
        try {
            withIOContext {
                val episodes = state.source.getEpisodeList(state.anime.toSAnime())

                val newEpisodes = syncEpisodesWithSource.await(
                    episodes,
                    state.anime,
                    state.source,
                    manualFetch,
                )

                if (manualFetch) {
                    downloadNewEpisodes(newEpisodes)
                }
            }
        } catch (e: Throwable) {
            val message = if (e is NoResultsException) {
                context.stringResource(MR.strings.no_episodes_error)
            } else {
                logcat(LogPriority.ERROR, e)
                with(context) { e.formattedMessage }
            }

            screenModelScope.launch {
                snackbarHostState.showSnackbar(message = message)
            }
            val newAnime = animeRepository.getAnimeById(animeId)
            updateSuccessState { it.copy(anime = newAnime, isRefreshingData = false) }
        }
    }

    /**
     * Requests an updated list of seasons from the source.
     * Ported from Anikku (komikku-app/anikku).
     */
    private suspend fun fetchSeasonsFromSource(manualFetch: Boolean = false) {
        val state = successState ?: return
        try {
            withIOContext {
                val seasons = state.source.getSeasonList(state.anime.toSAnime())

                syncSeasonsWithSource.await(
                    seasons,
                    state.anime,
                    state.source,
                    manualFetch,
                )
            }
        } catch (e: Throwable) {
            val message = if (e is NoSeasonsException) {
                context.stringResource(MR.strings.no_seasons_error)
            } else {
                logcat(LogPriority.ERROR, e)
                with(context) { e.formattedMessage }
            }

            screenModelScope.launch {
                snackbarHostState.showSnackbar(message = message)
            }
            val newAnime = animeRepository.getAnimeById(animeId)
            updateSuccessState { it.copy(anime = newAnime, isRefreshingData = false) }
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.EpisodeSwipeAction.Disabled]
     */
    fun episodeSwipe(episodeItem: EpisodeList.Item, swipeAction: LibraryPreferences.EpisodeSwipeAction) {
        screenModelScope.launch {
            executeEpisodeSwipeAction(episodeItem, swipeAction)
        }
    }

    /**
     * @throws IllegalStateException if the swipe action is [LibraryPreferences.EpisodeSwipeAction.Disabled]
     */
    private fun executeEpisodeSwipeAction(
        episodeItem: EpisodeList.Item,
        swipeAction: LibraryPreferences.EpisodeSwipeAction,
    ) {
        val episode = episodeItem.episode
        when (swipeAction) {
            LibraryPreferences.EpisodeSwipeAction.ToggleSeen -> {
                markEpisodesSeen(listOf(episode), !episode.seen)
            }
            LibraryPreferences.EpisodeSwipeAction.ToggleBookmark -> {
                bookmarkEpisodes(listOf(episode), !episode.bookmark)
            }
            LibraryPreferences.EpisodeSwipeAction.ToggleFillermark -> {
                fillermarkEpisodes(listOf(episode), !episode.fillermark)
            }
            LibraryPreferences.EpisodeSwipeAction.Download -> {
                val downloadAction: EpisodeDownloadAction = when (episodeItem.downloadState) {
                    AnimeDownload.State.ERROR,
                    AnimeDownload.State.NOT_DOWNLOADED,
                    -> EpisodeDownloadAction.START_NOW
                    AnimeDownload.State.QUEUE,
                    AnimeDownload.State.DOWNLOADING,
                    -> EpisodeDownloadAction.CANCEL
                    AnimeDownload.State.DOWNLOADED -> EpisodeDownloadAction.DELETE
                }
                runEpisodeDownloadActions(
                    items = listOf(episodeItem),
                    action = downloadAction,
                )
            }
            LibraryPreferences.EpisodeSwipeAction.Disabled -> throw IllegalStateException()
        }
    }

    /**
     * Returns the next unseen episode or null if everything is seen.
     */
    fun getNextUnseenEpisode(): Episode? {
        val successState = successState ?: return null
        return if (successState.anime.sortDescending()) {
            successState.processedEpisodes.findLast { !it.episode.seen }
        } else {
            successState.processedEpisodes.find { !it.episode.seen }
        }?.episode
    }

    suspend fun getNextUnseenEpisode(anime: Anime): Episode? {
        val episodes = getAnimeAndEpisodes.awaitChapters(anime.id, applyScanlatorFilter = true)
        return episodes.firstOrNull { !it.seen }
    }

    private fun getUnseenEpisodes(): List<Episode> {
        val successState = successState ?: return emptyList()
        val episodeItems = if (playerPreferences.skipFiltered().get()) {
            successState.processedEpisodes
        } else {
            successState.episodes
        }
        return episodeItems
            ?.filter { !it.episode.seen && it.downloadState == AnimeDownload.State.NOT_DOWNLOADED }
            ?.map { it.episode }
            ?.toList()
            ?: emptyList()
    }

    private fun getUnseenEpisodesSorted(): List<Episode> {
        val anime = successState?.anime ?: return emptyList()
        val episodes = getUnseenEpisodes().sortedWith(getEpisodeSort(anime))
        return if (anime.sortDescending()) episodes.reversed() else episodes
    }

    private fun startDownload(
        episodes: List<Episode>,
        startNow: Boolean,
        video: Video? = null,
    ) {
        val successState = successState ?: return

        screenModelScope.launchNonCancellable {
            if (startNow) {
                val episodeId = episodes.singleOrNull()?.id ?: return@launchNonCancellable
                animeDownloadManager.startDownloads()
            } else {
                downloadEpisodes(episodes, false, video)
            }
            if (!isFavorited && !successState.hasPromptedToAddBefore) {
                updateSuccessState { state ->
                    state.copy(hasPromptedToAddBefore = true)
                }
                val result = snackbarHostState.showSnackbar(
                    message = context.stringResource(MR.strings.snack_add_to_anime_library),
                    actionLabel = context.stringResource(MR.strings.action_add),
                    withDismissAction = true,
                )
                if (result == SnackbarResult.ActionPerformed && !isFavorited) {
                    toggleFavorite()
                }
            }
        }
    }

    fun runEpisodeDownloadActions(
        items: List<EpisodeList.Item>,
        action: EpisodeDownloadAction,
    ) {
        when (action) {
            EpisodeDownloadAction.START -> {
                startDownload(items.map { it.episode }, false)
                if (items.any { it.downloadState == AnimeDownload.State.ERROR }) {
                    animeDownloadManager.startDownloads()
                }
            }
            EpisodeDownloadAction.START_NOW -> {
                val episode = items.singleOrNull()?.episode ?: return
                startDownload(listOf(episode), true)
            }
            EpisodeDownloadAction.CANCEL -> {
                val episodeId = items.singleOrNull()?.id ?: return
                cancelDownload(episodeId)
            }
            EpisodeDownloadAction.DELETE -> {
                deleteEpisodes(items.map { it.episode })
            }
            EpisodeDownloadAction.SHOW_QUALITIES -> {
                val episode = items.singleOrNull()?.episode ?: return
                showQualitiesDialog(episode)
            }
        }
    }

    fun runDownloadAction(action: DownloadAction) {
        val episodesToDownload = when (action) {
            DownloadAction.NEXT_1_ITEM -> getUnseenEpisodesSorted().take(1)
            DownloadAction.NEXT_5_ITEMS -> getUnseenEpisodesSorted().take(5)
            DownloadAction.NEXT_10_ITEMS -> getUnseenEpisodesSorted().take(10)
            DownloadAction.NEXT_25_ITEMS -> getUnseenEpisodesSorted().take(25)

            DownloadAction.UNVIEWED_ITEMS -> getUnseenEpisodes()
        }
        if (episodesToDownload.isNotEmpty()) {
            startDownload(episodesToDownload, false)
        }
    }

    private fun cancelDownload(episodeId: Long) {
        val activeDownload = animeDownloadManager.queueState.value.firstOrNull { it.episode.id == episodeId } ?: return
        animeDownloadManager.cancelQueuedDownloads(listOf(activeDownload))
        updateDownloadState(activeDownload.apply { status = AnimeDownload.State.NOT_DOWNLOADED })
    }

    fun markPreviousEpisodeSeen(pointer: Episode) {
        val anime = successState?.anime ?: return
        val episodes = processedEpisodes.orEmpty().map { it.episode }.toList()
        val prevEpisodes = if (anime.sortDescending()) episodes.asReversed() else episodes
        val pointerPos = prevEpisodes.indexOf(pointer)
        if (pointerPos != -1) markEpisodesSeen(prevEpisodes.take(pointerPos), true)
    }

    /**
     * Mark the selected episode list as seen/unseen.
     * @param episodes the list of selected episodes.
     * @param seen whether to mark episodes as seen or unseen.
     */
    fun markEpisodesSeen(episodes: List<Episode>, seen: Boolean) {
        toggleAllSelection(false)
        screenModelScope.launchIO {
            setSeenStatus.await(
                seen = seen,
                episodes = episodes.toTypedArray(),
            )

            if (seen) {
                episodes.filter { !it.seen }.forEach { episode ->
                    upsertAnimeHistory.await(
                        AnimeHistoryUpdate(episode.id!!, java.util.Date(), 0),
                    )
                }
            }

            if (!seen || successState?.hasLoggedInTrackers == false || autoTrackState == AutoTrackState.NEVER) {
                return@launchIO
            }

            val tracks = animeTrackRepository.getTracksByAnimeId(animeId)
            val maxEpisodeNumber = episodes.maxOf { it.episodeNumber }
            val shouldPromptTrackingUpdate = tracks.any { track -> maxEpisodeNumber > track.lastEpisodeSeen }

            if (!shouldPromptTrackingUpdate) return@launchIO

            if (autoTrackState == AutoTrackState.ALWAYS) {
                trackEpisode.await(context, animeId, maxEpisodeNumber)
                withUIContext {
                    context.toast(
                        context.stringResource(MR.strings.trackers_updated_summary_anime, maxEpisodeNumber.toInt()),
                    )
                }
                return@launchIO
            }

            val result = snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.confirm_tracker_update_anime, maxEpisodeNumber.toInt()),
                actionLabel = context.stringResource(MR.strings.action_ok),
                duration = SnackbarDuration.Short,
                withDismissAction = true,
            )

            if (result == SnackbarResult.ActionPerformed) {
                trackEpisode.await(context, animeId, maxEpisodeNumber)
            }
        }
    }

    /**
     * Downloads the given list of episodes with the manager.
     * @param episodes the list of episodes to download.
     */
    fun downloadEpisodes(
        episodes: List<Episode>,
        alt: Boolean = false,
        video: Video? = null,
    ) {
        val anime = successState?.anime ?: return
        animeDownloadManager.downloadEpisodes(anime, episodes, autoStart = true, alt = alt, video = video)
        toggleAllSelection(false)
    }

    /**
     * Bookmarks the given list of episodes.
     * @param episodes the list of episodes to bookmark.
     */
    fun bookmarkEpisodes(episodes: List<Episode>, bookmarked: Boolean) {
        screenModelScope.launchIO {
            episodes
                .filterNot { it.bookmark == bookmarked }
                .map { EpisodeUpdate(id = it.id, bookmark = bookmarked) }
                .let { updateEpisode.awaitAll(it) }
        }
        toggleAllSelection(false)
    }

    // AM (FILLERMARK) -->
    /**
     * Fillermarks the given list of episodes.
     * @param episodes the list of episodes to fillermark.
     */
    fun fillermarkEpisodes(episodes: List<Episode>, fillermarked: Boolean) {
        screenModelScope.launchIO {
            episodes
                .filterNot { it.fillermark == fillermarked }
                .map { EpisodeUpdate(id = it.id, fillermark = fillermarked) }
                .let { updateEpisode.awaitAll(it) }
        }
        toggleAllSelection(false)
    }
    // <-- AM (FILLERMARK)

    /**
     * Deletes the given list of episode.
     *
     * @param episodes the list of episodes to delete.
     */
    fun deleteEpisodes(episodes: List<Episode>) {
        screenModelScope.launchNonCancellable {
            try {
                successState?.let { state ->
                    animeDownloadManager.deleteEpisodes(
                        episodes,
                        state.anime,
                        state.source,
                    )
                }
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    private fun downloadNewEpisodes(episodes: List<Episode>) {
        screenModelScope.launchNonCancellable {
            val anime = successState?.anime ?: return@launchNonCancellable
            val episodesToDownload = filterEpisodesForDownload.await(anime, episodes)

            if (episodesToDownload.isNotEmpty()) {
                downloadEpisodes(episodesToDownload)
            }
        }
    }

    /**
     * Sets the seen filter and requests an UI update.
     * @param state whether to display only unseen episodes or all episodes.
     */
    fun setUnseenFilter(state: TriState) {
        val anime = successState?.anime ?: return

        val flag = when (state) {
            TriState.DISABLED -> Anime.SHOW_ALL
            TriState.ENABLED_IS -> Anime.EPISODE_SHOW_UNSEEN
            TriState.ENABLED_NOT -> Anime.EPISODE_SHOW_SEEN
        }
        screenModelScope.launchNonCancellable {
            setAnimeEpisodeFlags.awaitSetUnreadFilter(anime, flag)
        }
    }

    /**
     * Sets the download filter and requests an UI update.
     * @param state whether to display only downloaded episodes or all episodes.
     */
    fun setDownloadedFilter(state: TriState) {
        val anime = successState?.anime ?: return

        val flag = when (state) {
            TriState.DISABLED -> Anime.SHOW_ALL
            TriState.ENABLED_IS -> Anime.EPISODE_SHOW_DOWNLOADED
            TriState.ENABLED_NOT -> Anime.EPISODE_SHOW_NOT_DOWNLOADED
        }

        screenModelScope.launchNonCancellable {
            setAnimeEpisodeFlags.awaitSetDownloadedFilter(anime, flag)
        }
    }

    /**
     * Sets the bookmark filter and requests an UI update.
     * @param state whether to display only bookmarked episodes or all episodes.
     */
    fun setBookmarkedFilter(state: TriState) {
        val anime = successState?.anime ?: return

        val flag = when (state) {
            TriState.DISABLED -> Anime.SHOW_ALL
            TriState.ENABLED_IS -> Anime.EPISODE_SHOW_BOOKMARKED
            TriState.ENABLED_NOT -> Anime.EPISODE_SHOW_NOT_BOOKMARKED
        }

        screenModelScope.launchNonCancellable {
            setAnimeEpisodeFlags.awaitSetBookmarkFilter(anime, flag)
        }
    }

    // AM (FILLERMARK) -->
    /**
     * Sets the fillermark filter and requests an UI update.
     * @param state whether to display only fillermarked episodes or all episodes.
     */
    fun setFillermarkedFilter(state: TriState) {
        val anime = successState?.anime ?: return

        val flag = when (state) {
            TriState.DISABLED -> Anime.SHOW_ALL
            TriState.ENABLED_IS -> Anime.EPISODE_SHOW_FILLERMARKED
            TriState.ENABLED_NOT -> Anime.EPISODE_SHOW_NOT_FILLERMARKED
        }

        screenModelScope.launchNonCancellable {
            setAnimeEpisodeFlags.awaitSetFillermarkFilter(anime, flag)
        }
    }
    // <-- AM (FILLERMARK)

    /**
     * Sets the active display mode.
     * @param mode the mode to set.
     */
    fun setDisplayMode(mode: Long) {
        val anime = successState?.anime ?: return

        screenModelScope.launchNonCancellable {
            setAnimeEpisodeFlags.awaitSetDisplayMode(anime, mode)
        }
    }

    /**
     * Sets the sorting method and requests an UI update.
     * @param sort the sorting mode.
     */
    fun setSorting(sort: Long) {
        val anime = successState?.anime ?: return

        screenModelScope.launchNonCancellable {
            setAnimeEpisodeFlags.awaitSetSortingModeOrFlipOrder(anime, sort)
        }
    }

    fun setCurrentSettingsAsDefault(applyToExisting: Boolean) {
        val anime = successState?.anime ?: return
        screenModelScope.launchNonCancellable {
            libraryPreferences.setEpisodeSettingsDefault(anime)
            if (applyToExisting) {
                setAnimeDefaultEpisodeFlags.awaitAll()
            }
            snackbarHostState.showSnackbar(
                message = context.stringResource(MR.strings.episode_settings_updated),
            )
        }
    }

    fun toggleSelection(
        item: EpisodeList.Item,
        selected: Boolean,
        userSelected: Boolean = false,
        fromLongPress: Boolean = false,
    ) {
        updateSuccessState { successState ->
            val newEpisodes = successState.processedEpisodes.toMutableList().apply {
                val selectedIndex = successState.processedEpisodes.indexOfFirst { it.id == item.episode.id }
                if (selectedIndex < 0) return@apply

                val selectedItem = get(selectedIndex)
                if ((selectedItem.selected && selected) || (!selectedItem.selected && !selected)) return@apply

                val firstSelection = none { it.selected }
                set(selectedIndex, selectedItem.copy(selected = selected))
                selectedEpisodeIds.addOrRemove(item.id, selected)

                if (selected && userSelected && fromLongPress) {
                    if (firstSelection) {
                        selectedPositions[0] = selectedIndex
                        selectedPositions[1] = selectedIndex
                    } else {
                        // Try to select the items in-between when possible
                        val range: IntRange
                        if (selectedIndex < selectedPositions[0]) {
                            range = selectedIndex + 1..<selectedPositions[0]
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            range = (selectedPositions[1] + 1)..<selectedIndex
                            selectedPositions[1] = selectedIndex
                        } else {
                            // Just select itself
                            range = IntRange.EMPTY
                        }

                        range.forEach {
                            val inbetweenItem = get(it)
                            if (!inbetweenItem.selected) {
                                selectedEpisodeIds.add(inbetweenItem.id)
                                set(it, inbetweenItem.copy(selected = true))
                            }
                        }
                    }
                } else if (userSelected && !fromLongPress) {
                    if (!selected) {
                        if (selectedIndex == selectedPositions[0]) {
                            selectedPositions[0] = indexOfFirst { it.selected }
                        } else if (selectedIndex == selectedPositions[1]) {
                            selectedPositions[1] = indexOfLast { it.selected }
                        }
                    } else {
                        if (selectedIndex < selectedPositions[0]) {
                            selectedPositions[0] = selectedIndex
                        } else if (selectedIndex > selectedPositions[1]) {
                            selectedPositions[1] = selectedIndex
                        }
                    }
                }
            }
            successState.copy(episodes = newEpisodes)
        }
    }

    fun toggleAllSelection(selected: Boolean) {
        updateSuccessState { successState ->
            val newEpisodes = successState.episodes.map {
                selectedEpisodeIds.addOrRemove(it.id, selected)
                it.copy(selected = selected)
            }
            selectedPositions[0] = -1
            selectedPositions[1] = -1
            successState.copy(episodes = newEpisodes)
        }
    }

    fun invertSelection() {
        updateSuccessState { successState ->
            val newEpisodes = successState.episodes.map {
                selectedEpisodeIds.addOrRemove(it.id, !it.selected)
                it.copy(selected = !it.selected)
            }
            selectedPositions[0] = -1
            selectedPositions[1] = -1
            successState.copy(episodes = newEpisodes)
        }
    }

    // Episodes list - end

    // Track sheet - start

    private fun observeTrackers() {
        val anime = successState?.anime ?: return

        screenModelScope.launchIO {
            combine(
                animeTrackRepository.getTracksByAnimeIdAsFlow(anime.id).catch { logcat(LogPriority.ERROR, it) },
                trackerManager.loggedInAnimeTrackersFlow(),
            ) { animeTracks, loggedInTrackers ->
                // Show only if the service supports this anime's source
                val supportedTrackers = loggedInTrackers.filter {
                    (it as? EnhancedAnimeTracker)?.accept(source!!) ?: true
                }
                    // For now, only enhanced trackers supports season tracking to sync the seasons.
                    // This could probably be fleshed out later.
                    .filter { anime.fetchType == FetchType.Episodes || it is EnhancedAnimeTracker }
                val supportedTrackerIds = supportedTrackers.map { it.id }.toHashSet()
                val supportedTrackerTracks = animeTracks.filter { it.trackerId in supportedTrackerIds }
                supportedTrackerTracks.size to supportedTrackers.isNotEmpty()
            }
                .flowWithLifecycle(lifecycle)
                .distinctUntilChanged()
                .collectLatest { (trackingCount, hasLoggedInTrackers) ->
                    updateSuccessState {
                        it.copy(
                            trackingCount = trackingCount,
                            hasLoggedInTrackers = hasLoggedInTrackers,
                        )
                    }
                }
        }

        screenModelScope.launchIO {
            combine(
                animeTrackRepository.getTracksByAnimeIdAsFlow(anime.id).catch { logcat(LogPriority.ERROR, it) },
                trackerManager.loggedInAnimeTrackersFlow(),
            ) { animeTracks, loggedInTrackers ->
                loggedInTrackers
                    .map { service ->
                        TrackItem(
                            animeTracks.find {
                                it.trackerId == service.id
                            },
                            service,
                        )
                    }
            }
                .distinctUntilChanged()
                .collectLatest { trackItems ->
                    updateAiringTime(anime, trackItems, manualFetch = false)
                }
        }
    }

    private suspend fun updateAiringTime(
        anime: Anime,
        trackItems: List<TrackItem>,
        manualFetch: Boolean,
    ) {
        val airingEpisodeData = AniChartApi().loadAiringTime(anime, trackItems, manualFetch)
        setAnimeViewerFlags.awaitSetNextEpisodeAiring(anime.id, airingEpisodeData)
        updateSuccessState { it.copy(nextAiringEpisode = airingEpisodeData) }
    }

    // Track sheet - end

    sealed interface Dialog {
        data class ChangeCategory(
            val anime: Anime,
            val initialSelection: ImmutableList<CheckboxState<Category>>,
        ) : Dialog
        data class DeleteEpisodes(val episodes: List<Episode>) : Dialog
        data class DuplicateAnime(val anime: Anime, val duplicates: List<Anime>) : Dialog
        data class Migrate(val newAnime: Anime, val oldAnime: Anime) : Dialog
        data class SetAnimeFetchInterval(val anime: Anime) : Dialog
        data class SetDictionaryProfile(val anime: Anime) : Dialog
        data class ShowQualities(val episode: Episode, val anime: Anime, val source: AnimeSource) : Dialog
        data object ChangeAnimeSkipIntro : Dialog
        data object EpisodeSettingsSheet : Dialog
        data object SeasonSettingsSheet : Dialog
        data object TrackSheet : Dialog
        data object FullImages : Dialog
    }

    fun dismissDialog() {
        updateSuccessState { it.copy(dialog = null) }
    }

    fun showDialog(dialog: Dialog) {
        updateSuccessState { it.copy(dialog = dialog) }
    }

    fun refreshEpisodes() {
        fetchAllFromSource()
    }

    fun deleteAnime() {
        screenModelScope.launchNonCancellable {
            val state = successState ?: return@launchNonCancellable
            animeDownloadManager.deleteAnime(state.anime, state.source)
        }
    }

    fun deleteEpisodeDownload(episode: Episode) {
        val state = successState ?: return
        screenModelScope.launchNonCancellable {
            animeDownloadManager.deleteEpisodes(listOf(episode), state.anime, state.source)
        }
    }

    fun confirmDownload(episode: Episode, video: Video) {
        startDownload(listOf(episode), false, video)
    }

    fun setBookmarkFilter(state: TriState) {
        setBookmarkedFilter(state)
    }

    fun setSortMode(mode: Long) {
        setSorting(mode)
    }

    fun toggleBookmark(episodes: List<Episode>, bookmarked: Boolean) {
        bookmarkEpisodes(episodes, bookmarked)
    }

    fun markPreviousAsSeen(episode: Episode) {
        markPreviousEpisodeSeen(episode)
    }

    fun deleteEpisodeDownloads(episodes: List<Episode>) {
        val state = successState ?: return
        screenModelScope.launchNonCancellable {
            animeDownloadManager.deleteEpisodes(episodes, state.anime, state.source)
        }
    }

    fun showDeleteEpisodeDialog(episodes: List<Episode>) {
        updateSuccessState { it.copy(dialog = Dialog.DeleteEpisodes(episodes)) }
    }

    fun showSettingsDialog() {
        updateSuccessState {
            when (it.anime.fetchType) {
                FetchType.Seasons -> it.copy(dialog = Dialog.SeasonSettingsSheet)
                FetchType.Episodes -> it.copy(dialog = Dialog.EpisodeSettingsSheet)
            }
        }
    }

    fun setExcludedScanlators(excludedScanlators: Set<String>) {
        screenModelScope.launchIO {
            setExcludedAnimeScanlators.await(animeId, excludedScanlators)
        }
    }

    fun showSeasonSettingsDialog() {
        updateSuccessState { it.copy(dialog = Dialog.SeasonSettingsSheet) }
    }

    fun showTrackDialog() {
        updateSuccessState { it.copy(dialog = Dialog.TrackSheet) }
    }

    fun showImagesDialog() {
        updateSuccessState { it.copy(dialog = Dialog.FullImages) }
    }

    fun showMigrateDialog(duplicate: Anime) {
        val anime = successState?.anime ?: return
        updateSuccessState { it.copy(dialog = Dialog.Migrate(newAnime = anime, oldAnime = duplicate)) }
    }

    fun showAnimeSkipIntroDialog() {
        updateSuccessState { it.copy(dialog = Dialog.ChangeAnimeSkipIntro) }
    }

    fun showSetDictionaryProfileDialog() {
        val anime = successState?.anime ?: return
        updateSuccessState { it.copy(dialog = Dialog.SetDictionaryProfile(anime)) }
    }

    /**
     * Persist (or clear) an anime-level dictionary profile override.
     * [profileId] == null clears the override, falling back to source/language/global resolution.
     */
    fun setAnimeDictionaryProfile(profileId: String?) {
        val prefs = Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>()
        val key = chimahon.dictionary.DictionaryProfileResolver.animeOverrideKey(animeId)
        if (profileId == null) {
            prefs.rawProfileOverride(key).delete()
        } else {
            prefs.rawProfileOverride(key).set(profileId)
        }
        dismissDialog()
    }

    fun resolveAutoProfile(sourceId: Long): chimahon.anki.AnkiProfile {
        val source = animeSourceManager.getOrStub(sourceId)
        return Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>().profileResolver.resolve(
            animeId = 0L, // 0 to avoid hitting the anime override itself
            sourceId = sourceId, // But DO check source override
            sourceLang = source.lang,
        )
    }

    private fun showQualitiesDialog(episode: Episode) {
        updateSuccessState { it.copy(dialog = Dialog.ShowQualities(episode, it.anime, it.source)) }
    }

    fun setSeasonDownloadedFilter(state: TriState) {
        val anime = successState?.anime ?: return
        val flag = when (state) {
            TriState.DISABLED -> Anime.SHOW_ALL
            TriState.ENABLED_IS -> Anime.SEASON_SHOW_DOWNLOADED
            TriState.ENABLED_NOT -> Anime.SEASON_SHOW_NOT_DOWNLOADED
        }
        screenModelScope.launchNonCancellable {
            setAnimeSeasonFlags.awaitSetDownloadedFilter(anime, flag)
        }
    }

    fun setSeasonUnseenFilter(state: TriState) {
        val anime = successState?.anime ?: return
        val flag = when (state) {
            TriState.DISABLED -> Anime.SHOW_ALL
            TriState.ENABLED_IS -> Anime.SEASON_SHOW_UNSEEN
            TriState.ENABLED_NOT -> Anime.SEASON_SHOW_SEEN
        }
        screenModelScope.launchNonCancellable {
            setAnimeSeasonFlags.awaitSetUnseenFilter(anime, flag)
        }
    }

    fun setSeasonStartedFilter(state: TriState) {
        val anime = successState?.anime ?: return
        val flag = when (state) {
            TriState.DISABLED -> Anime.SHOW_ALL
            TriState.ENABLED_IS -> Anime.SEASON_SHOW_STARTED
            TriState.ENABLED_NOT -> Anime.SEASON_SHOW_NOT_STARTED
        }
        screenModelScope.launchNonCancellable {
            setAnimeSeasonFlags.awaitSetStartedFilter(anime, flag)
        }
    }

    fun setSeasonCompletedFilter(state: TriState) {
        val anime = successState?.anime ?: return
        val flag = when (state) {
            TriState.DISABLED -> Anime.SHOW_ALL
            TriState.ENABLED_IS -> Anime.SEASON_SHOW_COMPLETED
            TriState.ENABLED_NOT -> Anime.SEASON_SHOW_NOT_COMPLETED
        }
        screenModelScope.launchNonCancellable {
            setAnimeSeasonFlags.awaitSetCompletedFilter(anime, flag)
        }
    }

    fun setSeasonBookmarkedFilter(state: TriState) {
        val anime = successState?.anime ?: return
        val flag = when (state) {
            TriState.DISABLED -> Anime.SHOW_ALL
            TriState.ENABLED_IS -> Anime.SEASON_SHOW_BOOKMARKED
            TriState.ENABLED_NOT -> Anime.SEASON_SHOW_NOT_BOOKMARKED
        }
        screenModelScope.launchNonCancellable {
            setAnimeSeasonFlags.awaitSetBookmarkedFilter(anime, flag)
        }
    }

    fun setSeasonFillermarkedFilter(state: TriState) {
        val anime = successState?.anime ?: return
        val flag = when (state) {
            TriState.DISABLED -> Anime.SHOW_ALL
            TriState.ENABLED_IS -> Anime.SEASON_SHOW_FILLERMARKED
            TriState.ENABLED_NOT -> Anime.SEASON_SHOW_NOT_FILLERMARKED
        }
        screenModelScope.launchNonCancellable {
            setAnimeSeasonFlags.awaitSetFillermarkedFilter(anime, flag)
        }
    }

    fun setSeasonSorting(sort: Long) {
        val anime = successState?.anime ?: return
        screenModelScope.launchNonCancellable {
            setAnimeSeasonFlags.awaitSetSortingModeOrFlipOrder(anime, sort)
        }
    }

    fun setSeasonDisplayGridMode(mode: SeasonDisplayMode) {
        val anime = successState?.anime ?: return
        screenModelScope.launchNonCancellable {
            setAnimeSeasonFlags.awaitSetGridMode(anime, mode)
        }
    }

    fun setSeasonDisplayGridSize(size: Int) {
        val anime = successState?.anime ?: return
        screenModelScope.launchNonCancellable {
            setAnimeSeasonFlags.awaitSetGridSize(anime, size)
        }
    }

    fun setSeasonDownloadedOverlay(value: Boolean) {
        val anime = successState?.anime ?: return
        screenModelScope.launchNonCancellable {
            setAnimeSeasonFlags.awaitSetDownloadedOverlay(anime, value)
        }
    }

    fun setSeasonUnseenOverlay(value: Boolean) {
        val anime = successState?.anime ?: return
        screenModelScope.launchNonCancellable {
            setAnimeSeasonFlags.awaitSetUnseenOverlay(anime, value)
        }
    }

    fun setSeasonLocalOverlay(value: Boolean) {
        val anime = successState?.anime ?: return
        screenModelScope.launchNonCancellable {
            setAnimeSeasonFlags.awaitSetLocalOverlay(anime, value)
        }
    }

    fun setSeasonLangOverlay(value: Boolean) {
        val anime = successState?.anime ?: return
        screenModelScope.launchNonCancellable {
            setAnimeSeasonFlags.awaitSetLangOverlay(anime, value)
        }
    }

    fun setSeasonContinueOverlay(value: Boolean) {
        val anime = successState?.anime ?: return
        screenModelScope.launchNonCancellable {
            setAnimeSeasonFlags.awaitSetContinueOverlay(anime, value)
        }
    }

    fun setSeasonDisplayMode(mode: Long) {
        val anime = successState?.anime ?: return
        screenModelScope.launchNonCancellable {
            setAnimeSeasonFlags.awaitSetDisplayMode(anime, mode)
        }
    }

    fun setSeasonSettingsAsDefault(applyToExisting: Boolean) {
        val anime = successState?.anime ?: return
        screenModelScope.launchNonCancellable {
            libraryPreferences.setSeasonSettingsDefault(anime)
            if (applyToExisting) {
                setAnimeDefaultSeasonFlags.await(anime)
            }
            snackbarHostState.showSnackbar(
                message = context.stringResource(AMR.strings.season_settings_updated),
            )
        }
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val anime: Anime,
            val source: AnimeSource,
            val isFromSource: Boolean,
            val episodes: List<EpisodeList.Item>,
            val trackingCount: Int = 0,
            val hasLoggedInTrackers: Boolean = false,
            val isRefreshingData: Boolean = false,
            val dialog: Dialog? = null,
            val availableScanlators: ImmutableSet<String> = persistentSetOf(),
            val excludedScanlators: ImmutableSet<String> = persistentSetOf(),
            val hasPromptedToAddBefore: Boolean = false,
            val trackItems: List<TrackItem> = emptyList(),
            val isRelatedAnimeFetched: Boolean? = null,
            val relatedAnimeCollection: List<RelatedAnime>? = null,
            val nextAiringEpisode: Pair<Int, Long> = Pair(
                anime.nextEpisodeToAir,
                anime.nextEpisodeAiringAt,
            ),
            val seasons: List<AnimeSeasonItem> = emptyList(),
            val seedColor: Color? = anime.asAnimeCover().vibrantCoverColor?.let { Color(it) },
        ) : State {

            val processedSeasons: List<AnimeSeasonItem> by lazy {
                seasons.applySeasonFilters(anime)
            }

            val processedEpisodes by lazy {
                episodes.applyFilters(anime).toList()
            }

            val isAnySelected: Boolean
                get() = episodes.any { it.selected }

            val allEpisodeCount: Int
                get() = episodes.size

            val episodeListItems by lazy {
                processedEpisodes.insertSeparators { before, after ->
                    val (lowerEpisode, higherEpisode) = if (anime.sortDescending()) {
                        after to before
                    } else {
                        before to after
                    }
                    if (higherEpisode == null) return@insertSeparators null

                    if (lowerEpisode == null) {
                        floor(higherEpisode.episode.episodeNumber)
                            .toInt()
                            .minus(1)
                            .coerceAtLeast(0)
                    } else {
                        calculateChapterGap(higherEpisode.episode, lowerEpisode.episode)
                    }
                        .takeIf { it > 0 }
                        ?.let { missingCount ->
                            EpisodeList.MissingCount(
                                id = "${lowerEpisode?.id}-${higherEpisode.id}",
                                count = missingCount,
                            )
                        }
                }
            }

            val trackingAvailable: Boolean
                get() = trackItems.isNotEmpty()

            val airingEpisodeNumber: Double
                get() = nextAiringEpisode.first.toDouble()

            val airingTime: Long
                get() = nextAiringEpisode.second.times(1000L).minus(
                    Calendar.getInstance().timeInMillis,
                )

            val filterActive: Boolean
                get() = when (anime.fetchType) {
                    eu.kanade.tachiyomi.animesource.model.FetchType.Seasons -> anime.seasonsFiltered()
                    eu.kanade.tachiyomi.animesource.model.FetchType.Episodes -> scanlatorFilterActive ||
                        anime.episodesFiltered()
                }

            val scanlatorFilterActive: Boolean
                get() = excludedScanlators.intersect(availableScanlators).isNotEmpty()

            val nextUnseenEpisode: Episode?
                get() = processedEpisodes.firstOrNull { !it.episode.seen }?.episode

            val showSummaries: Boolean
                get() = true

            val showPreviews: Boolean
                get() = true

            val relatedAnimeSorted: List<RelatedAnime>?
                get() = relatedAnimeCollection
                    ?.removeDuplicates(anime)
                    ?.filter { it.isVisible() }
                    ?.sorted(anime)
                    ?.isLoading(isRelatedAnimeFetched)
                    ?: if (isRelatedAnimeFetched == true) emptyList() else null

            /**
             * Applies the view filters to the list of episodes obtained from the database.
             * @return an observable of the list of episodes filtered and sorted.
             */
            private fun List<AnimeSeasonItem>.applySeasonFilters(anime: Anime): List<AnimeSeasonItem> {
                val unseenFilter = anime.seasonUnseenFilter
                val downloadedFilter = anime.seasonDownloadedFilter
                val startedFilter = anime.seasonStartedFilter
                val completedFilter = anime.seasonCompletedFilter
                val bookmarkedFilter = anime.seasonBookmarkedFilter
                val fillermarkedFilter = anime.seasonFillermarkedFilter
                return asSequence()
                .filter { applyFilter(unseenFilter) { !it.seasonAnime.seen } }
                .filter { applyFilter(downloadedFilter) { it.downloadCount > 0 || it.isLocal } }
                .filter { applyFilter(startedFilter) { it.seasonAnime.hasStarted } }
                .filter { applyFilter(completedFilter) { it.seasonAnime.anime.status == SAnime.COMPLETED.toLong() } }
                    .filter { applyFilter(bookmarkedFilter) { it.seasonAnime.hasBookmarks } }
                    .filter { applyFilter(fillermarkedFilter) { it.seasonAnime.hasFillermarks } }
                    .sortedWith(seasonSortComparator(anime))
                    .toList()
            }

            private fun seasonSortComparator(anime: Anime): Comparator<AnimeSeasonItem> {
                val sortDescending = anime.seasonSortDescending()
                val comparator: Comparator<AnimeSeasonItem> = when (anime.seasonSorting) {
                    Anime.SEASON_SORTING_SOURCE -> compareBy { it.seasonAnime.anime.source }
                    Anime.SEASON_SORTING_NUMBER -> compareBy { it.seasonAnime.anime.seasonNumber }
                    Anime.SEASON_SORTING_UPLOAD_DATE -> compareBy { it.seasonAnime.latestUpload }
                    Anime.SEASON_SORTING_ALPHABET -> compareBy { it.seasonAnime.anime.title }
                    Anime.SEASON_SORTING_UNSEEN -> compareBy { it.seasonAnime.unseenCount }
                    Anime.SEASON_SORTING_LAST_SEEN -> compareBy { it.seasonAnime.lastSeen }
                    Anime.SEASON_SORTING_EP_FETCH_DATE -> compareBy { it.seasonAnime.fetchedAt }
                    else -> compareBy { it.seasonAnime.anime.seasonSourceOrder }
                }
                return if (sortDescending) comparator.reversed() else comparator
            }

            private fun List<EpisodeList.Item>.applyFilters(anime: Anime): Sequence<EpisodeList.Item> {
                val isLocalAnime = anime.isLocal()
                val unseenFilter = anime.unseenFilter
                val downloadedFilter = anime.downloadedFilter
                val bookmarkedFilter = anime.bookmarkedFilter
                // AM (FILLERMARK) -->
                val fillermarkedFilter = anime.fillermarkedFilter
                // <-- AM (FILLERMARK)
                return asSequence()
                    .filter { applyFilter(unseenFilter) { !it.episode.seen } }
                    .filter { applyFilter(bookmarkedFilter) { it.episode.bookmark } }
                    // AM (FILLERMARK) -->
                    .filter { applyFilter(fillermarkedFilter) { it.episode.fillermark } }
                    // <-- AM (FILLERMARK)
                    .filter { applyFilter(downloadedFilter) { it.isDownloaded || isLocalAnime } }
                    .sortedWith { item1, item2 ->
                        getEpisodeSort(anime).invoke(
                            item1.episode,
                            item2.episode,
                        )
                    }
            }
        }
    }
}

@Immutable
sealed class EpisodeList {
    @Immutable
    data class MissingCount(
        val id: String,
        val count: Int,
    ) : EpisodeList()

    @Immutable
    data class Item(
        val episode: Episode,
        val downloadState: AnimeDownload.State,
        val downloadProgress: Int,
        // AM (FILE_SIZE) -->
        var fileSize: Long? = null,
        // <-- AM (FILE_SIZE)
        val selected: Boolean = false,
    ) : EpisodeList() {
        val id = episode.id
        val isDownloaded = downloadState == AnimeDownload.State.DOWNLOADED
    }
}

@Immutable
sealed interface RelatedAnime {
    data object Loading : RelatedAnime

    data class Success(
        val keyword: String,
        val animeList: List<Anime>,
    ) : RelatedAnime {
        val isEmpty: Boolean
            get() = animeList.isEmpty()

        companion object {
            suspend fun fromPair(
                pair: Pair<String, List<SAnime>>,
                toAnime: suspend (animeList: List<SAnime>) -> List<Anime>,
            ) = Success(pair.first, toAnime(pair.second))
        }
    }

    fun isVisible(): Boolean {
        return this is Loading || (this is Success && !this.isEmpty)
    }

    companion object {
        internal fun List<RelatedAnime>.sorted(anime: Anime): List<RelatedAnime> {
            val success = filterIsInstance<Success>()
            val loading = filterIsInstance<Loading>()
            val title = anime.title.lowercase()
            val ogTitle = anime.ogTitle.lowercase()
            return success.filter { it.keyword.isEmpty() } +
                success.filter { it.keyword.lowercase() == title } +
                success.filter { it.keyword.lowercase() == ogTitle && ogTitle != title } +
                success.filter { it.keyword.isNotEmpty() && it.keyword.lowercase() !in listOf(title, ogTitle) }
                    .sortedByDescending { it.keyword.length }
                    .sortedBy { it.animeList.size } +
                loading
        }

        internal fun List<RelatedAnime>.removeDuplicates(anime: Anime): List<RelatedAnime> {
            val animeIds = HashSet<Long>().apply { add(anime.id) }

            return map { relatedAnime ->
                if (relatedAnime is Success) {
                    Success(
                        relatedAnime.keyword,
                        relatedAnime.animeList.filter { animeIds.add(it.id) },
                    )
                } else {
                    relatedAnime
                }
            }
        }

        internal fun List<RelatedAnime>.isLoading(isRelatedAnimeFetched: Boolean?): List<RelatedAnime> {
            return if (isRelatedAnimeFetched == false) this + listOf(Loading) else this
        }
    }
}
