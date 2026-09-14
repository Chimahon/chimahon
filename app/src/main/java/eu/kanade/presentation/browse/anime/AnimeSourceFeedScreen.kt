package eu.kanade.presentation.browse.anime

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.browse.anime.components.GlobalAnimeSearchCardRow
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.GlobalSearchResultItem
import eu.kanade.presentation.browse.components.bulkSelectionButton
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.BulkSelectionToolbar
import eu.kanade.presentation.components.SearchToolbar
import tachiyomi.domain.history.model.SearchHistory
import tachiyomi.domain.entries.anime.model.Anime
import eu.kanade.tachiyomi.ui.browse.animesource.feed.AnimeSourceFeedUI
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteAnimeScreenModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus

@Composable
fun AnimeSourceFeedScreen(
    name: String,
    isLoading: Boolean,
    items: ImmutableList<AnimeSourceFeedUI>,
    hasFilters: Boolean,
    onFabClick: () -> Unit,
    onClickBrowse: () -> Unit,
    onClickLatest: () -> Unit,
    onClickSavedSearch: (AnimeSourceFeedUI.AnimeSourceSavedSearch) -> Unit,
    onLongClickFeed: (AnimeSourceFeedUI.AnimeSourceSavedSearch) -> Unit,
    onClickAnime: (Anime) -> Unit,
    onClickSearch: (String) -> Unit,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    getAnimeState: @Composable (Anime) -> State<Anime>,
    navigateUp: () -> Unit,
    onWebViewClick: (() -> Unit)?,
    onSourceSettingClick: (() -> Unit?)?,
    onSortFeedClick: (() -> Unit)?,
    onLongClickAnime: (Anime) -> Unit,
    bulkFavoriteScreenModel: BulkFavoriteAnimeScreenModel,
) {
    val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()

    Scaffold(
        topBar = { scrollBehavior ->
            if (bulkFavoriteState.selectionMode) {
                BulkSelectionToolbar(
                    selectedCount = bulkFavoriteState.selection.size,
                    isRunning = bulkFavoriteState.isRunning,
                    onClickClearSelection = bulkFavoriteScreenModel::toggleSelectionMode,
                    onChangeCategoryClick = bulkFavoriteScreenModel::addFavorite,
                    onSelectAll = {
                        items.mapNotNull { it.results }
                            .flatten()
                            .forEach { bulkFavoriteScreenModel.select(it) }
                    },
                    onReverseSelection = {
                        items.mapNotNull { it.results }
                            .flatten()
                            .let { bulkFavoriteScreenModel.reverseSelection(it) }
                    },
                )
            } else {
                AnimeSourceFeedToolbar(
                    title = name,
                    searchQuery = searchQuery,
                    onSearchQueryChange = onSearchQueryChange,
                    scrollBehavior = scrollBehavior,
                    onClickSearch = onClickSearch,
                    navigateUp = navigateUp,
                    onWebViewClick = onWebViewClick,
                    onSourceSettingClick = onSourceSettingClick,
                    onSortFeedClick = onSortFeedClick,
                    toggleSelectionMode = bulkFavoriteScreenModel::toggleSelectionMode,
                    isRunning = bulkFavoriteState.isRunning,
                )
            }
        },
        floatingActionButton = {
            SmallExtendedFloatingActionButton(
                text = {
                    Text(
                        text = if (hasFilters) {
                            stringResource(MR.strings.action_filter)
                        } else {
                            stringResource(SYMR.strings.saved_searches)
                        },
                    )
                },
                icon = { Icon(Icons.Outlined.FilterList, contentDescription = "") },
                onClick = onFabClick,
            )
        },
    ) { paddingValues ->
        Crossfade(targetState = isLoading, label = "source_feed") { state ->
            when (state) {
                true -> LoadingScreen()
                false -> {
                    AnimeSourceFeedList(
                        items = items,
                        paddingValues = paddingValues,
                        getAnimeState = getAnimeState,
                        onClickBrowse = onClickBrowse,
                        onClickLatest = onClickLatest,
                        onClickSavedSearch = onClickSavedSearch,
                        onLongClickFeed = onLongClickFeed,
                        onClickAnime = onClickAnime,
                        onLongClickAnime = onLongClickAnime,
                        selection = bulkFavoriteState.selection,
                    )
                }
            }
        }
    }
}

@Composable
fun AnimeSourceFeedList(
    items: ImmutableList<AnimeSourceFeedUI>,
    paddingValues: PaddingValues,
    getAnimeState: @Composable ((Anime) -> State<Anime>),
    onClickBrowse: () -> Unit,
    onClickLatest: () -> Unit,
    onClickSavedSearch: (AnimeSourceFeedUI.AnimeSourceSavedSearch) -> Unit,
    onLongClickFeed: (AnimeSourceFeedUI.AnimeSourceSavedSearch) -> Unit,
    onClickAnime: (Anime) -> Unit,
    onLongClickAnime: (Anime) -> Unit,
    selection: List<Anime>,
) {
    ScrollbarLazyColumn(
        contentPadding = paddingValues + topSmallPaddingValues,
    ) {
        items(
            items,
            key = { "source-feed-${it.id}" },
        ) { item ->
            GlobalSearchResultItem(
                modifier = Modifier.animateItem(),
                title = when (item) {
                    is AnimeSourceFeedUI.Browse -> stringResource(MR.strings.browse)
                    is AnimeSourceFeedUI.Latest -> stringResource(MR.strings.latest)
                    is AnimeSourceFeedUI.AnimeSourceSavedSearch -> item.savedSearch.name
                },
                subtitle = null,
                onLongClick = if (item is AnimeSourceFeedUI.AnimeSourceSavedSearch) {
                    {
                        onLongClickFeed(item)
                    }
                } else {
                    null
                },
                onClick = when (item) {
                    is AnimeSourceFeedUI.Browse -> onClickBrowse
                    is AnimeSourceFeedUI.Latest -> onClickLatest
                    is AnimeSourceFeedUI.AnimeSourceSavedSearch -> {
                        { onClickSavedSearch(item) }
                    }
                },
            ) {
                AnimeSourceFeedItem(
                    item = item,
                    getAnimeState = { getAnimeState(it) },
                    onClickAnime = onClickAnime,
                    onLongClickAnime = onLongClickAnime,
                )
            }
        }
    }
}

@Composable
fun AnimeSourceFeedItem(
    item: AnimeSourceFeedUI,
    getAnimeState: @Composable ((Anime) -> State<Anime>),
    onClickAnime: (Anime) -> Unit,
    onLongClickAnime: (Anime) -> Unit,
) {
    val results = item.results
    when {
        results == null -> {
            GlobalSearchLoadingResultItem()
        }
        results.isEmpty() -> {
            GlobalSearchErrorResultItem(message = stringResource(MR.strings.no_results_found))
        }
        else -> {
            GlobalAnimeSearchCardRow(
                titles = item.results.orEmpty(),
                getAnime = getAnimeState,
                onClick = onClickAnime,
                onLongClick = onLongClickAnime,
            )
        }
    }
}

@Composable
fun AnimeSourceFeedToolbar(
    title: String,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    onClickSearch: (String) -> Unit,
    navigateUp: () -> Unit,
    onWebViewClick: (() -> Unit)?,
    onSourceSettingClick: (() -> Unit?)?,
    onSortFeedClick: (() -> Unit)?,
    toggleSelectionMode: () -> Unit,
    isRunning: Boolean,
) {
    SearchToolbar(
        titleContent = { AppBarTitle(title) },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        onSearch = onClickSearch,
        navigateUp = navigateUp,
        onClickCloseSearch = navigateUp,
        scrollBehavior = scrollBehavior,
        searchHistoryScope = SearchHistory.SCOPE_ANIME_MANGA,
        actions = {
            AppBarActions(
                actions = persistentListOf<AppBar.AppBarAction>().builder().apply {
                    add(bulkSelectionButton(isRunning, toggleSelectionMode))

                    onWebViewClick?.let { func ->
                        add(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_web_view),
                                onClick = { func() },
                                icon = Icons.Outlined.Public,
                            ),
                        )
                    }

                    onSortFeedClick?.let { func ->
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(KMR.strings.action_sort_feed),
                                onClick = { func() },
                            ),
                        )
                    }

                    onSourceSettingClick?.let { func ->
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.label_settings),
                                onClick = { func() },
                            ),
                        )
                    }
                }
                    .build(),
            )
        },
    )
}
