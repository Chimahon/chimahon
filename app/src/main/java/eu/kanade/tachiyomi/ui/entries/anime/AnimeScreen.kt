package eu.kanade.tachiyomi.ui.entries.anime

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifAnimeSourcesLoaded
import eu.kanade.domain.entries.anime.model.hasCustomBackground
import eu.kanade.domain.entries.anime.model.hasCustomCover
import eu.kanade.domain.entries.anime.model.toSAnime
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.NavigatorAdaptiveSheet
import eu.kanade.presentation.entries.EditCoverAction
import eu.kanade.presentation.entries.anime.AnimeScreen
import eu.kanade.presentation.entries.anime.DuplicateAnimeDialog
import eu.kanade.presentation.entries.anime.EpisodeOptionsDialogScreen
import eu.kanade.presentation.entries.anime.EpisodeSettingsDialog
import eu.kanade.presentation.entries.anime.SeasonSettingsDialog
import eu.kanade.presentation.entries.anime.components.AnimeImagesDialog
import eu.kanade.presentation.entries.anime.components.AnimeScanlatorFilterDialog
import eu.kanade.presentation.entries.components.DeleteItemsDialog
import eu.kanade.presentation.entries.components.SetIntervalDialog
import eu.kanade.presentation.more.settings.screen.player.PlayerSettingsGesturesScreen.SkipIntroLengthDialog
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.formatEpisodeNumber
import exh.recs.AnimeRecommendsScreen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.browse.animesource.AnimeSourceScreenProvider
import eu.kanade.tachiyomi.ui.browse.animesource.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.animesource.globalsearch.GlobalAnimeSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.entries.anime.track.AnimeTrackInfoDialogHomeScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.player.ExternalIntents
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.feature.animemigration.dialog.MigrateAnimeDialog
import eu.kanade.tachiyomi.ui.browse.animemigration.season.MigrateSeasonSelectScreen
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.source.anime.model.StubAnimeSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.entries.anime.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeScreen(
    private val animeId: Long,
    val fromSource: Boolean = false,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        if (!ifAnimeSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current
        val scope = rememberCoroutineScope()
        val lifecycleOwner = LocalLifecycleOwner.current
        val screenModel =
            rememberScreenModel { AnimeScreenModel(context, lifecycleOwner.lifecycle, animeId, fromSource) }

        val state by screenModel.state.collectAsStateWithLifecycle()

        if (state is AnimeScreenModel.State.Loading) {
            LoadingScreen()
            return
        }

        val successState = state as AnimeScreenModel.State.Success
        val isAnimeHttpSource = remember { successState.source is AnimeHttpSource }
        val isAnimeSourceScreenProvider = remember { successState.source is AnimeSourceScreenProvider }
        var showScanlatorFilterDialog by rememberSaveable { mutableStateOf(false) }

        LaunchedEffect(successState.anime, screenModel.source) {
            if (isAnimeHttpSource) {
                try {
                    withIOContext {
                        assistUrl = getAnimeUrl(screenModel.anime, screenModel.source)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to get anime URL" }
                }
            }
        }

        TachiyomiTheme(
            seedColor = successState.seedColor.takeIf { screenModel.themeCoverBased },
        ) {
            AnimeScreen(
                state = successState,
                snackbarHostState = screenModel.snackbarHostState,
                nextUpdate = successState.anime.expectedNextUpdate,
                isTabletUi = isTabletUi(),
                episodeSwipeStartAction = screenModel.episodeSwipeStartAction,
                episodeSwipeEndAction = screenModel.episodeSwipeEndAction,
                showNextEpisodeAirTime = screenModel.showNextEpisodeAirTime,
                alwaysUseExternalPlayer = screenModel.alwaysUseExternalPlayer,
                navigateUp = navigator::pop,
                onEpisodeClicked = { episode, alt ->
                    scope.launchIO {
                        val extPlayer = screenModel.alwaysUseExternalPlayer != alt
                        openEpisode(context, episode, extPlayer)
                    }
                },
                onDownloadEpisode = screenModel::runEpisodeDownloadActions.takeIf {
                    !successState.source.isLocalOrStub() && successState.anime.fetchType == FetchType.Episodes
                },
                onAddToLibraryClicked = {
                    screenModel.toggleFavorite()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                onWebViewClicked = {
                    openAnimeInWebView(
                        navigator,
                        screenModel.anime,
                        screenModel.source,
                    )
                }.takeIf { isAnimeHttpSource || isAnimeSourceScreenProvider },
                onWebViewLongClicked = {
                    copyAnimeUrl(
                        context,
                        screenModel.anime,
                        screenModel.source,
                    )
                }.takeIf { isAnimeHttpSource },
                onTrackingClicked = {
                    if (!successState.hasLoggedInTrackers) {
                        navigator.push(SettingsScreen(SettingsScreen.Destination.Tracking))
                    } else {
                        screenModel.showTrackDialog()
                    }
                }.takeIf { successState.anime.fetchType == FetchType.Episodes },
                onTagSearch = { scope.launch { performGenreSearch(navigator, it, screenModel.source!!) } },
                onFilterButtonClicked = screenModel::showSettingsDialog,
                onRefresh = screenModel::fetchAllFromSource,
                onContinueWatching = {
                    scope.launchIO {
                        val extPlayer = screenModel.alwaysUseExternalPlayer
                        continueWatching(context, screenModel.getNextUnseenEpisode(), extPlayer)
                    }
                },
                onSearch = { query, global -> scope.launch { performSearch(navigator, query, global) } },
                onCoverClicked = screenModel::showImagesDialog,
                onCoverLoaded = {
                    if (screenModel.themeCoverBased || successState.anime.favorite) screenModel.setPaletteColor(it)
                },
                onShareClicked = {
                    shareAnime(
                        context,
                        screenModel.anime,
                        screenModel.source,
                    )
                }.takeIf { isAnimeHttpSource },
                onDownloadActionClicked = screenModel::runDownloadAction.takeIf {
                    !successState.source.isLocalOrStub() && successState.anime.fetchType == FetchType.Episodes
                },
                onEditCategoryClicked = screenModel::showChangeCategoryDialog.takeIf { successState.anime.favorite },
                onEditFetchIntervalClicked = screenModel::showSetAnimeFetchIntervalDialog.takeIf {
                    successState.anime.favorite
                },
                onMigrateClicked = {
                    navigator.push(GlobalAnimeSearchScreen(successState.anime.title))
                }.takeIf { successState.anime.favorite },
                changeAnimeSkipIntro = screenModel::showAnimeSkipIntroDialog
                    .takeIf { successState.anime.favorite && successState.anime.fetchType == FetchType.Episodes },
                onClickDictionaryProfile = screenModel::showSetDictionaryProfileDialog
                    .takeIf { successState.anime.favorite },
                onMultiBookmarkClicked = screenModel::bookmarkEpisodes,
                onMultiFillermarkClicked = screenModel::fillermarkEpisodes,
                onMultiMarkAsSeenClicked = screenModel::markEpisodesSeen,
                onMarkPreviousAsSeenClicked = screenModel::markPreviousEpisodeSeen,
                onMultiDeleteClicked = screenModel::showDeleteEpisodeDialog,
                onEpisodeSwipe = screenModel::episodeSwipe,
                onEpisodeSelected = screenModel::toggleSelection,
                onAllEpisodeSelected = screenModel::toggleAllSelection,
                onInvertSelection = screenModel::invertSelection,
                onSeasonClicked = {
                    navigator.push(AnimeScreen(it.id))
                },
                onContinueWatchingClicked = {
                    scope.launchIO {
                        val episode = screenModel.getNextUnseenEpisode(it.anime)
                        episode?.let { ep ->
                            openEpisode(context, ep, screenModel.alwaysUseExternalPlayer)
                        }
                    }
                },
                onRelatedAnimeScreenClick = {
                    scope.launchIO {
                        screenModel.fetchRelatedAnimeFromSource()
                    }
                },
                onRecommendClicked = {
                    navigator.push(
                        AnimeRecommendsScreen(
                            AnimeRecommendsScreen.Args.SingleSourceAnime(animeId, successState.source.id),
                        ),
                    )
                },
            )
        }

        val onDismissRequest = {
            screenModel.dismissDialog()
            if (screenModel.autoOpenTrack && screenModel.isFromChangeCategory) {
                screenModel.isFromChangeCategory = false
                screenModel.showTrackDialog()
            }
        }
        when (val dialog = successState.dialog) {
            null -> {}
            is AnimeScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen(CategoryScreen.Tab.ANIME)) },
                    onConfirm = { include, _ ->
                        screenModel.moveAnimeToCategoriesAndAddToLibrary(dialog.anime, include)
                    },
                )
            }
            is AnimeScreenModel.Dialog.DeleteEpisodes -> {
                DeleteItemsDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        screenModel.toggleAllSelection(false)
                        screenModel.deleteEpisodes(dialog.episodes)
                    },
                    isManga = false,
                )
            }

            is AnimeScreenModel.Dialog.DuplicateAnime -> {
                val duplicate = dialog.duplicates.firstOrNull() ?: dialog.anime
                DuplicateAnimeDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = { screenModel.toggleFavorite(onRemoved = {}, checkDuplicate = false) },
                    onOpenAnime = { navigator.push(AnimeScreen(duplicate.id)) },
                    onMigrate = {
                        screenModel.showMigrateDialog(duplicate)
                    },
                )
            }

            is AnimeScreenModel.Dialog.Migrate -> {
                MigrateAnimeDialog(
                    current = dialog.oldAnime,
                    target = dialog.newAnime,
                    onClickTitle = {
                        onDismissRequest()
                        navigator.push(AnimeScreen(dialog.newAnime.id))
                    },
                    onClickSeasons = {
                        navigator.push(MigrateSeasonSelectScreen(dialog.oldAnime, dialog.newAnime))
                    },
                    onDismissRequest = onDismissRequest,
                    onComplete = {
                        onDismissRequest()
                        navigator.replace(AnimeScreen(dialog.newAnime.id))
                    },
                )
            }
            AnimeScreenModel.Dialog.EpisodeSettingsSheet -> EpisodeSettingsDialog(
                onDismissRequest = onDismissRequest,
                anime = successState.anime,
                onDownloadFilterChanged = screenModel::setDownloadedFilter,
                onUnseenFilterChanged = screenModel::setUnseenFilter,
                onBookmarkedFilterChanged = screenModel::setBookmarkedFilter,
                onFillermarkedFilterChanged = screenModel::setFillermarkedFilter,
                scanlatorFilterActive = successState.scanlatorFilterActive,
                onScanlatorFilterClicked = { showScanlatorFilterDialog = true },
                onSortModeChanged = screenModel::setSorting,
                onDisplayModeChanged = screenModel::setDisplayMode,
                onSetAsDefault = screenModel::setCurrentSettingsAsDefault,
            )
            AnimeScreenModel.Dialog.SeasonSettingsSheet -> SeasonSettingsDialog(
                onDismissRequest = onDismissRequest,
                anime = successState.anime,
                onDownloadFilterChanged = screenModel::setSeasonDownloadedFilter,
                onUnseenFilterChanged = screenModel::setSeasonUnseenFilter,
                onStartedFilterChanged = screenModel::setSeasonStartedFilter,
                onCompletedFilterChanged = screenModel::setSeasonCompletedFilter,
                onBookmarkedFilterChanged = screenModel::setSeasonBookmarkedFilter,
                onFillermarkedFilterChanged = screenModel::setSeasonFillermarkedFilter,
                onSortModeChanged = screenModel::setSeasonSorting,
                onDisplayGridModeChanged = screenModel::setSeasonDisplayGridMode,
                onDisplayGridSizeChanged = screenModel::setSeasonDisplayGridSize,
                onOverlayDownloadedChanged = screenModel::setSeasonDownloadedOverlay,
                onOverlayUnseenChanged = screenModel::setSeasonUnseenOverlay,
                onOverlayLocalChanged = screenModel::setSeasonLocalOverlay,
                onOverlayLangChanged = screenModel::setSeasonLangOverlay,
                onOverlayContinueChanged = screenModel::setSeasonContinueOverlay,
                onDisplayModeChanged = screenModel::setSeasonDisplayMode,
                onSetAsDefault = screenModel::setSeasonSettingsAsDefault,
            )
            AnimeScreenModel.Dialog.TrackSheet -> {
                NavigatorAdaptiveSheet(
                    screen = AnimeTrackInfoDialogHomeScreen(
                        animeId = successState.anime.id,
                        animeTitle = successState.anime.title,
                        sourceId = successState.source.id,
                    ),
                    enableSwipeDismiss = { it.lastItem is AnimeTrackInfoDialogHomeScreen },
                    onDismissRequest = onDismissRequest,
                )
            }
            AnimeScreenModel.Dialog.FullImages -> {
                val sm = rememberScreenModel { AnimeImageScreenModel(successState.anime.id) }
                val anime by sm.state.collectAsState()
                if (anime != null) {
                    val getContent = rememberLauncherForActivityResult(
                        ActivityResultContracts.GetContent(),
                    ) {
                        if (it == null) return@rememberLauncherForActivityResult
                        sm.editImage(context, it)
                    }
                    AnimeImagesDialog(
                        anime = anime!!,
                        snackbarHostState = sm.snackbarHostState,
                        pagerState = sm.pagerState,
                        isCustomCover = remember(anime) { anime!!.hasCustomCover() },
                        isCustomBackground = remember(anime) { anime!!.hasCustomBackground() },
                        onShareClick = { sm.shareImage(context) },
                        onSaveClick = { sm.saveImage(context) },
                        onEditClick = {
                            when (it) {
                                EditCoverAction.EDIT -> getContent.launch("image/*")
                                EditCoverAction.DELETE -> sm.deleteCustomImage(context)
                            }
                        },
                        onDismissRequest = onDismissRequest,
                    )
                } else {
                    LoadingScreen(Modifier.systemBarsPadding())
                }
            }
            is AnimeScreenModel.Dialog.SetAnimeFetchInterval -> {
                SetIntervalDialog(
                    interval = dialog.anime.fetchInterval,
                    nextUpdate = dialog.anime.expectedNextUpdate,
                    onDismissRequest = onDismissRequest,
                    isManga = false,
                    onValueChanged = { interval: Int -> screenModel.setFetchInterval(dialog.anime, interval) }
                        .takeIf { screenModel.isUpdateIntervalEnabled },
                )
            }
            is AnimeScreenModel.Dialog.SetDictionaryProfile -> {
                val prefs = remember { Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>() }
                val profiles = remember { prefs.profileStore.getProfiles() }
                val overrideId = remember {
                    prefs.rawProfileOverride(
                        chimahon.dictionary.DictionaryProfileResolver.animeOverrideKey(dialog.anime.id),
                    ).get()
                }
                eu.kanade.presentation.manga.components.DictionaryProfileDialog(
                    profiles = profiles,
                    currentOverrideId = overrideId,
                    resolvedAutoProfile = screenModel.resolveAutoProfile(dialog.anime.source),
                    onDismissRequest = onDismissRequest,
                    onConfirm = screenModel::setAnimeDictionaryProfile,
                )
            }
            AnimeScreenModel.Dialog.ChangeAnimeSkipIntro -> {
                fun updateSkipIntroLength(newLength: Long) {
                    scope.launchIO {
                        screenModel.setAnimeViewerFlags.awaitSetSkipIntroLength(animeId, newLength)
                    }
                }
                SkipIntroLengthDialog(
                    initialSkipIntroLength = if (!successState.anime.skipIntroDisable &&
                        successState.anime.skipIntroLength == 0
                    ) {
                        screenModel.gesturePreferences.defaultIntroLength().get()
                    } else {
                        successState.anime.skipIntroLength
                    },
                    onDismissRequest = onDismissRequest,
                    onValueChanged = {
                        updateSkipIntroLength(it.toLong())
                        onDismissRequest()
                    },
                )
            }
            is AnimeScreenModel.Dialog.ShowQualities -> {
                EpisodeOptionsDialogScreen.onDismissDialog = onDismissRequest
                val episodeTitle = if (dialog.anime.displayMode == Anime.EPISODE_DISPLAY_NUMBER) {
                    stringResource(
                        MR.strings.display_mode_episode,
                        formatEpisodeNumber(dialog.episode.episodeNumber),
                    )
                } else {
                    dialog.episode.name
                }
                NavigatorAdaptiveSheet(
                    screen = EpisodeOptionsDialogScreen(
                        useExternalDownloader = screenModel.useExternalDownloader,
                        episodeTitle = episodeTitle,
                        episodeId = dialog.episode.id,
                        animeId = dialog.anime.id,
                        sourceId = dialog.source.id,
                    ),
                    onDismissRequest = onDismissRequest,
                )
            }
        }

        if (showScanlatorFilterDialog) {
            AnimeScanlatorFilterDialog(
                availableScanlators = successState.availableScanlators,
                excludedScanlators = successState.excludedScanlators,
                onDismissRequest = { showScanlatorFilterDialog = false },
                onConfirm = screenModel::setExcludedScanlators,
            )
        }
    }

    private suspend fun continueWatching(
        context: Context,
        unseenEpisode: Episode?,
        useExternalPlayer: Boolean,
    ) {
        if (unseenEpisode != null) openEpisode(context, unseenEpisode, useExternalPlayer)
    }

    private suspend fun openEpisode(context: Context, episode: Episode, useExternalPlayer: Boolean) {
        withIOContext {
            if (useExternalPlayer) {
                try {
                    val intent = ExternalIntents().getExternalIntent(context, episode.animeId, episode.id, null)
                    if (intent != null) {
                        context.startActivity(intent)
                        return@withIOContext
                    }
                } catch (e: Throwable) {
                    context.toast(e.message)
                }
            }
            context.startActivity(PlayerActivity.newIntent(context, episode.animeId, episode.id))
        }
    }

    private fun getAnimeUrl(anime_: Anime?, source_: AnimeSource?): String? {
        val anime = anime_ ?: return null
        val source = source_ as? AnimeHttpSource ?: return null

        return try {
            source.getAnimeUrl(anime.toSAnime())
        } catch (e: Exception) {
            null
        }
    }

    private fun openAnimeInWebView(navigator: Navigator, anime_: Anime?, source_: AnimeSource?) {
        getAnimeUrl(anime_, source_)?.let { url ->
            val animeSourceScreenProvider = source_ as? AnimeSourceScreenProvider
            navigator.push(
                if (animeSourceScreenProvider != null)
                {
                    animeSourceScreenProvider.createBrowseScreen(null, anime_?.url)
                }
                else
                {
                    WebViewScreen(
                        url = url,
                        initialTitle = anime_?.title,
                        sourceId = source_?.id,
                    )
                }
            )
        }
    }

    private fun shareAnime(context: Context, anime_: Anime?, source_: AnimeSource?) {
        try {
            getAnimeUrl(anime_, source_)?.let { url ->
                val intent = url.toUri().toShareIntent(context, type = "text/plain")
                context.startActivity(
                    Intent.createChooser(
                        intent,
                        null,
                    ),
                )
            }
        } catch (e: Exception) {
            context.toast(e.message)
        }
    }

    /**
     * Perform a search using the provided query.
     *
     * @param query the search query to the parent controller
     */
    private suspend fun performSearch(navigator: Navigator, query: String, global: Boolean) {
        if (global) {
            navigator.push(GlobalAnimeSearchScreen(query))
            return
        }

        if (navigator.size < 2) {
            return
        }

        when (val previousController = navigator.items[navigator.size - 2]) {
            is HomeScreen -> {
                navigator.pop()
                AnimeTab.search(query)
            }
            is BrowseAnimeSourceScreen -> {
                navigator.pop()
                previousController.search(query)
            }
        }
    }

    /**
     * Performs a genre search using the provided genre name.
     *
     * @param genreName the search genre to the parent controller
     */
    private suspend fun performGenreSearch(
        navigator: Navigator,
        genreName: String,
        source: AnimeSource,
    ) {
        if (navigator.size < 2) {
            return
        }

        val previousController = navigator.items[navigator.size - 2]
        if (previousController is BrowseAnimeSourceScreen && source is AnimeHttpSource) {
            navigator.pop()
            previousController.searchGenre(genreName)
        } else {
            performSearch(navigator, genreName, global = false)
        }
    }

    /**
     * Copy Anime URL to Clipboard
     */
    private fun copyAnimeUrl(context: Context, anime_: Anime?, source_: AnimeSource?) {
        val anime = anime_ ?: return
        val source = source_ as? AnimeHttpSource ?: return
        val url = source.getAnimeUrl(anime.toSAnime())
        context.copyToClipboard(url, url)
    }

    private fun AnimeSource.isLocalOrStub(): Boolean = isLocal() || this is StubAnimeSource
}

@Composable
private fun UnsupportedAnimeFeatureDialog(
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
        text = {
            Text(text = stringResource(MR.strings.not_applicable))
        },
    )
}
