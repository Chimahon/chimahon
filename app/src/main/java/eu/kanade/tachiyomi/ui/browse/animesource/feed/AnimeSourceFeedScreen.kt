package eu.kanade.tachiyomi.ui.browse.animesource.feed

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifAnimeSourcesLoaded
import eu.kanade.presentation.browse.anime.AnimeFeedActionsDialog
import eu.kanade.presentation.browse.anime.AnimeSourceFeedOrderScreen
import eu.kanade.presentation.browse.anime.AnimeSourceFeedScreen
import eu.kanade.presentation.browse.anime.MissingSourceScreen
import eu.kanade.presentation.browse.anime.components.AnimeBulkFavoriteDialogs
import eu.kanade.presentation.browse.components.SourceFeedDeleteDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteAnimeScreenModel
import eu.kanade.tachiyomi.ui.browse.animeextension.details.AnimeSourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.animesource.AnimeSourceScreenProvider
import eu.kanade.tachiyomi.ui.browse.animesource.browse.AnimeSourceFilterDialog
import eu.kanade.tachiyomi.ui.browse.animesource.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.toast
import exh.util.nullIfBlank
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.source.anime.model.StubAnimeSource
import tachiyomi.presentation.core.screens.LoadingScreen

class AnimeSourceFeedScreen(val sourceId: Long) : Screen() {

    @Composable
    override fun Content() {
        if (!ifAnimeSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val screenModel = rememberScreenModel { AnimeSourceFeedScreenModel(sourceId) }
        val state by screenModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current

        screenModel.source.let {
            if (it is StubAnimeSource) {
                MissingSourceScreen(
                    source = it,
                    navigateUp = navigator::pop,
                )
                return
            }
        }

        LaunchedEffect(navigator.lastItem) {
            // Reset filters when screen is navigated back to
            screenModel.resetFilters()
        }

        val bulkFavoriteScreenModel = rememberScreenModel { BulkFavoriteAnimeScreenModel() }
        val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()
        var showingFeedOrderScreen by rememberSaveable { mutableStateOf(false) }

        val haptic = LocalHapticFeedback.current

        BackHandler(enabled = bulkFavoriteState.selectionMode || showingFeedOrderScreen) {
            when {
                bulkFavoriteState.selectionMode -> bulkFavoriteScreenModel.backHandler()
                showingFeedOrderScreen -> showingFeedOrderScreen = false
            }
        }
        Crossfade(
            targetState = showingFeedOrderScreen,
            label = "feed_order_crossfade",
        ) { targetState ->
            if (targetState) {
                AnimeSourceFeedOrderScreen(
                    state = state,
                    onClickDelete = screenModel::openDeleteFeed,
                    onChangeOrder = screenModel::changeOrder,
                    navigateUp = { showingFeedOrderScreen = false },
                )
            } else {
                AnimeSourceFeedScreen(
                    name = screenModel.source.name,
                    isLoading = state.isLoading,
                    items = state.items,
                    hasFilters = state.filters.isNotEmpty(),
                    onFabClick = screenModel::openFilterSheet,
                    onClickBrowse = { onBrowseClick(navigator, screenModel.source.id) },
                    onClickLatest = { onLatestClick(navigator, screenModel.source.id) },
                    onClickSavedSearch = { onSavedSearchClick(navigator, screenModel.source.id, it.savedSearch) },
                    onLongClickFeed = screenModel::openActionsDialog,
                    onClickAnime = { anime ->
                        if (bulkFavoriteState.selectionMode) {
                            bulkFavoriteScreenModel.toggleSelection(anime)
                        } else {
                            onAnimeClick(navigator, anime)
                        }
                    },
                    onClickSearch = { onSearchClick(navigator, screenModel.source.id, it) },
                    searchQuery = state.searchQuery,
                    onSearchQueryChange = screenModel::search,
                    getAnimeState = { screenModel.getAnime(initialAnime = it) },
                    navigateUp = { navigator.pop() },
                    onWebViewClick = {
                        val source = screenModel.source as? AnimeHttpSource ?: return@AnimeSourceFeedScreen
                        navigator.push(
                            WebViewScreen(
                                url = source.baseUrl,
                                initialTitle = source.name,
                                sourceId = source.id,
                            ),
                        )
                    },
                    onSourceSettingClick = {
                        val source = screenModel.source as? eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
                            ?: return@AnimeSourceFeedScreen null
                        navigator.push(AnimeSourcePreferencesScreen(screenModel.source.id))
                    }.takeIf { screenModel.source is eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource },
                    onSortFeedClick = { showingFeedOrderScreen = true }
                        .takeIf {
                            screenModel.state.value.items
                                .filterIsInstance<AnimeSourceFeedUI.AnimeSourceSavedSearch>()
                                .isNotEmpty()
                        },
                    onLongClickAnime = { anime ->
                        if (!bulkFavoriteState.selectionMode) {
                            bulkFavoriteScreenModel.addRemoveAnime(anime, haptic)
                        } else {
                            navigator.push(AnimeScreen(anime.id, true))
                        }
                    },
                    bulkFavoriteScreenModel = bulkFavoriteScreenModel,
                )
            }
        }

        val onDismissRequest = screenModel::dismissDialog
        when (val dialog = state.dialog) {
            is AnimeSourceFeedScreenModel.Dialog.AddFeed -> {
                eu.kanade.presentation.browse.components.SourceFeedAddDialog(
                    onDismissRequest = onDismissRequest,
                    name = dialog.name,
                    addFeed = {
                        screenModel.createFeed(dialog.feedId)
                        onDismissRequest()
                    },
                )
            }
            is AnimeSourceFeedScreenModel.Dialog.DeleteFeed -> {
                SourceFeedDeleteDialog(
                    onDismissRequest = onDismissRequest,
                    deleteFeed = {
                        screenModel.deleteFeed(dialog.feed)
                        onDismissRequest()
                    },
                )
            }
            is AnimeSourceFeedScreenModel.Dialog.FeedActions -> {
                AnimeFeedActionsDialog(
                    feed = dialog.feedItem.feed,
                    title = dialog.feedItem.title,
                    onDismissRequest = screenModel::dismissDialog,
                    onClickDelete = { screenModel.openDeleteFeed(it) },
                )
            }
            is AnimeSourceFeedScreenModel.Dialog.Filter -> {
                AnimeSourceFilterDialog(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    onReset = screenModel::resetFilters,
                    onFilter = {
                        screenModel.onFilter { query, filters ->
                            onBrowseClick(
                                navigator = navigator,
                                sourceId = sourceId,
                                search = query,
                                filters = filters,
                            )
                        }
                    },
                    onUpdate = screenModel::setFilters,
                    startExpanded = screenModel.startExpanded,
                )
            }
            null -> Unit
        }

        AnimeBulkFavoriteDialogs(
            bulkFavoriteScreenModel = bulkFavoriteScreenModel,
            dialog = bulkFavoriteState.dialog,
        )
    }

    private fun onAnimeClick(navigator: cafe.adriel.voyager.navigator.Navigator, anime: Anime) {
        navigator.push(AnimeScreen(anime.id, true))
    }

    private fun onBrowseClick(navigator: cafe.adriel.voyager.navigator.Navigator, sourceId: Long, search: String? = null, savedSearch: Long? = null, filters: String? = null) {
        navigator.push(BrowseAnimeSourceScreen(sourceId, search))
    }

    private fun onLatestClick(navigator: cafe.adriel.voyager.navigator.Navigator, sourceId: Long) {
        navigator.push(BrowseAnimeSourceScreen(sourceId, tachiyomi.domain.source.anime.interactor.GetRemoteAnime.QUERY_LATEST))
    }

    private fun onSavedSearchClick(navigator: cafe.adriel.voyager.navigator.Navigator, sourceId: Long, savedSearch: tachiyomi.domain.source.anime.model.AnimeSavedSearch) {
        navigator.push(BrowseAnimeSourceScreen(sourceId, null, savedSearchId = savedSearch.id))
    }

    private fun onSearchClick(navigator: cafe.adriel.voyager.navigator.Navigator, sourceId: Long, query: String) {
        onBrowseClick(navigator, sourceId, query.nullIfBlank())
    }
}
