package eu.kanade.tachiyomi.ui.browse.animesource.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifAnimeSourcesLoaded
import eu.kanade.presentation.browse.anime.BrowseAnimeSourceContent
import eu.kanade.presentation.browse.anime.MissingSourceScreen
import eu.kanade.presentation.browse.anime.components.ChangeAnimeCategoryDialog
import eu.kanade.presentation.browse.anime.components.BrowseAnimeSourceToolbar
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.entries.anime.DuplicateAnimeDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import tachiyomi.core.common.Constants
import eu.kanade.tachiyomi.ui.browse.animeextension.details.AnimeSourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.animesource.AnimeSourceScreenProvider
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import mihon.feature.animemigration.dialog.MigrateAnimeDialog
import eu.kanade.tachiyomi.ui.browse.animemigration.season.MigrateSeasonSelectScreen
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.source.anime.model.StubAnimeSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.ExtendedFloatingActionButton
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.entries.anime.LocalAnimeSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow

data class BrowseAnimeSourceScreen(
    val sourceId: Long,
    private val listingQuery: String?,
    private val migrateFromAnimeId: Long? = null,
    private val savedSearchId: Long? = null,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        if (!ifAnimeSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val screenModel = rememberScreenModel { BrowseAnimeSourceScreenModel(sourceId, listingQuery, savedSearchId) }
        val state by screenModel.state.collectAsState()
        val migrationMode = migrateFromAnimeId != null
        val migrateFromAnime by produceState<Anime?>(initialValue = null, migrateFromAnimeId) {
            value = migrateFromAnimeId?.let { Injekt.get<GetAnime>().await(it) }
        }

        val navigator = LocalNavigator.currentOrThrow
        val navigateUp: () -> Unit = {
            when {
                !state.isUserQuery && state.toolbarQuery != null -> screenModel.setToolbarQuery(null)
                else -> navigator.pop()
            }
        }

        if (screenModel.source is StubAnimeSource) {
            MissingSourceScreen(
                source = screenModel.source,
                navigateUp = navigateUp,
            )
            return
        }

        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val uriHandler = LocalUriHandler.current
        val snackbarHostState = remember { SnackbarHostState() }

        val onHelpClick = { uriHandler.openUri(LocalAnimeSource.HELP_URL) }
        val onWebViewClick = f@{
            val source = screenModel.source ?: return@f

            val animeHttpSource = source as? AnimeHttpSource
            val animeSourceScreenProvider = source as? AnimeSourceScreenProvider
            if (animeHttpSource == null && animeSourceScreenProvider == null)
                return@f

            navigator.push(
                if (animeSourceScreenProvider != null)
                {
                    animeSourceScreenProvider.createBrowseScreen(null, null)
                }
                else
                {
                    WebViewScreen(
                        url = animeHttpSource!!.baseUrl,
                        initialTitle = animeHttpSource!!.name,
                        sourceId = animeHttpSource!!.id,
                    )
                }
            )
        }

        LaunchedEffect(screenModel.source) {
            assistUrl = (screenModel.source as? AnimeHttpSource)?.baseUrl
        }

        LaunchedEffect(Unit) {
            queryEvent.receiveAsFlow()
                .collectLatest {
                    when (it) {
                        is SearchType.Genre -> screenModel.searchGenre(it.txt)
                        is SearchType.Text -> screenModel.search(it.txt)
                    }
                }
        }

        var topBarHeight by remember { mutableIntStateOf(0) }
        val animeList = screenModel.animePagerFlowFlow.collectAsLazyPagingItems()
        Scaffold(
            topBar = { scrollBehavior ->
                if (migrationMode) {
                    SearchToolbar(
                        searchQuery = state.toolbarQuery ?: "",
                        onChangeSearchQuery = screenModel::setToolbarQuery,
                        onClickCloseSearch = navigator::pop,
                        onSearch = screenModel::search,
                        scrollBehavior = scrollBehavior,
                        modifier = Modifier.onSizeChanged { topBarHeight = it.height },
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .pointerInput(Unit) {}
                            .onSizeChanged { topBarHeight = it.height },
                    ) {
                        BrowseAnimeSourceToolbar(
                            searchQuery = state.toolbarQuery,
                            onSearchQueryChange = screenModel::setToolbarQuery,
                            source = screenModel.source,
                            displayMode = screenModel.displayMode ?: LibraryDisplayMode.default,
                            onDisplayModeChange = { screenModel.displayMode = it },
                            navigateUp = navigateUp,
                            onWebViewClick = onWebViewClick,
                            onHelpClick = onHelpClick,
                            onSettingsClick = { navigator.push(AnimeSourcePreferencesScreen(sourceId)) },
                            onSearch = screenModel::search,
                        )

                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = MaterialTheme.padding.small),
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        ) {
                            FilterChip(
                                selected = state.listing == BrowseAnimeSourceScreenModel.Listing.Popular,
                                onClick = {
                                    screenModel.resetFilters()
                                    screenModel.setListing(BrowseAnimeSourceScreenModel.Listing.Popular)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Favorite,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = { Text(stringResource(MR.strings.popular)) },
                            )
                            if ((screenModel.source as AnimeCatalogueSource).supportsLatest) {
                                FilterChip(
                                    selected = state.listing == BrowseAnimeSourceScreenModel.Listing.Latest,
                                    onClick = {
                                        screenModel.resetFilters()
                                        screenModel.setListing(BrowseAnimeSourceScreenModel.Listing.Latest)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.NewReleases,
                                            contentDescription = null,
                                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                                        )
                                    },
                                    label = { Text(stringResource(MR.strings.latest)) },
                                )
                            }
                            if (state.filters.isNotEmpty()) {
                                FilterChip(
                                    selected = state.listing is BrowseAnimeSourceScreenModel.Listing.Search,
                                    onClick = screenModel::openFilterSheet,
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.FilterList,
                                            contentDescription = null,
                                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                                        )
                                    },
                                    label = { Text(stringResource(MR.strings.action_filter)) },
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            },
            floatingActionButton = {
                if (migrationMode) {
                    AnimatedVisibility(visible = state.filters.isNotEmpty()) {
                        ExtendedFloatingActionButton(
                            text = { Text(text = stringResource(MR.strings.action_filter)) },
                            icon = {
                                Icon(
                                    imageVector = Icons.Outlined.FilterList,
                                    contentDescription = null,
                                )
                            },
                            onClick = screenModel::openFilterSheet,
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { paddingValues ->
            BrowseAnimeSourceContent(
                source = screenModel.source,
                animeList = animeList,
                columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
                entries = screenModel.getColumnsPreferenceForCurrentOrientation(LocalConfiguration.current.orientation),
                topBarHeight = topBarHeight,
                displayMode = screenModel.displayMode ?: LibraryDisplayMode.default,
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                onWebViewClick = onWebViewClick,
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalAnimeSourceHelpClick = onHelpClick,
                onAnimeClick = { anime ->
                    val oldAnime = migrateFromAnime
                    if (oldAnime != null) {
                        screenModel.setDialog(BrowseAnimeSourceScreenModel.Dialog.Migrate(anime, oldAnime))
                    } else {
                        navigator.push(AnimeScreen(anime.id, true))
                    }
                },
                onAnimeLongClick = { anime ->
                    if (migrationMode) {
                        navigator.push(AnimeScreen(anime.id, true))
                    } else {
                        scope.launchIO {
                            val duplicateAnime = screenModel.getDuplicateAnimelibAnime(anime)
                            when {
                                anime.favorite -> screenModel.setDialog(
                                    BrowseAnimeSourceScreenModel.Dialog.RemoveAnime(anime),
                                )
                                duplicateAnime != null -> screenModel.setDialog(
                                    BrowseAnimeSourceScreenModel.Dialog.AddDuplicateAnime(
                                        anime,
                                        duplicateAnime,
                                    ),
                                )
                                else -> screenModel.addFavorite(anime)
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                },
            )
        }

        val onDismissRequest = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is BrowseAnimeSourceScreenModel.Dialog.Filter -> {
                AnimeSourceFilterDialog(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    onReset = screenModel::resetFilters,
                    onFilter = { screenModel.search(filters = state.filters) },
                    onUpdate = screenModel::setFilters,
                )
            }
            is BrowseAnimeSourceScreenModel.Dialog.AddDuplicateAnime -> {
                DuplicateAnimeDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = { screenModel.addFavorite(dialog.anime) },
                    onOpenAnime = { navigator.push(AnimeScreen(dialog.duplicate.id)) },
                    onMigrate = {
                        screenModel.setDialog(
                            BrowseAnimeSourceScreenModel.Dialog.Migrate(dialog.anime, dialog.duplicate),
                        )
                    },
                )
            }
            is BrowseAnimeSourceScreenModel.Dialog.RemoveAnime -> {
                RemoveAnimeDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        screenModel.changeAnimeFavorite(dialog.anime)
                    },
                    animeToRemove = dialog.anime,
                )
            }
            is BrowseAnimeSourceScreenModel.Dialog.ChangeAnimeCategory -> {
                ChangeAnimeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen(CategoryScreen.Tab.ANIME)) },
                    onConfirm = { include, _ ->
                        screenModel.changeAnimeFavorite(dialog.anime)
                        screenModel.moveAnimeToCategories(dialog.anime, include)
                    },
                )
            }
            is BrowseAnimeSourceScreenModel.Dialog.Migrate -> {
                MigrateAnimeDialog(
                    current = dialog.oldAnime,
                    target = dialog.newAnime,
                    onClickTitle = { navigator.push(AnimeScreen(dialog.newAnime.id, true)) },
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
            else -> {}
        }
    }

    suspend fun search(query: String) = queryEvent.send(SearchType.Text(query))
    suspend fun searchGenre(name: String) = queryEvent.send(SearchType.Genre(name))

    companion object {
        private val queryEvent = Channel<SearchType>()
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}

@Composable
private fun RemoveAnimeDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    animeToRemove: Anime,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                    onConfirm()
                },
            ) {
                Text(text = stringResource(MR.strings.action_remove))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.are_you_sure))
        },
        text = {
            Text(text = animeToRemove.title)
        },
    )
}
