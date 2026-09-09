package eu.kanade.tachiyomi.ui.updates

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.zIndex
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.NavigatorAdaptiveSheet
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.entries.anime.EpisodeOptionsDialogScreen
import eu.kanade.presentation.updates.UpdateScreen
import eu.kanade.presentation.updates.UpdatesDeleteConfirmationDialog
import eu.kanade.presentation.updates.UpdatesFilterDialog
import eu.kanade.presentation.updates.anime.AnimeUpdateScreen
import eu.kanade.presentation.updates.novel.NovelUpdatesScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.updates.UpdatesScreenModel.Event
import eu.kanade.tachiyomi.ui.updates.anime.AnimeUpdatesScreenModel
import eu.kanade.tachiyomi.ui.updates.novel.NovelUpdatesScreenModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import mihon.feature.upcoming.UpcomingScreen
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active
import tachiyomi.presentation.core.util.collectAsState

data object UpdatesTab : Tab {
    @Suppress("unused")
    private fun readResolve(): Any = UpdatesTab

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_updates_enter)
            return TabOptions(
                index = 1u,
                title = stringResource(MR.strings.label_recent_updates),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(DownloadQueueScreen)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { UpdatesScreenModel() }
        val animeScreenModel = rememberScreenModel { AnimeUpdatesScreenModel() }
        val novelScreenModel = rememberScreenModel { NovelUpdatesScreenModel() }
        val settingsScreenModel = rememberScreenModel { UpdatesSettingsScreenModel() }
        val state by screenModel.state.collectAsState()
        val animeState by animeScreenModel.state.collectAsState()
        val scope = rememberCoroutineScope()
        val pagerState = rememberPagerState(initialPage = TAB_MANGA) { TAB_COUNT }
        val selectedTab = pagerState.currentPage

        // KMK -->
        val usePanoramaCover by settingsScreenModel.updatesPreferences.usePanoramaCover().collectAsState()
        // KMK <--

        Scaffold(
            topBar = { scrollBehavior ->
                if (selectedTab == TAB_MANGA && state.selectionMode) {
                    UpdatesSelectionToolbar(
                        selectedCount = state.selected.size,
                        onCancelActionMode = { screenModel.toggleAllSelection(false) },
                        onClickSelectAll = { screenModel.toggleAllSelection(true) },
                        onClickInvertSelection = screenModel::invertSelection,
                    )
                } else if (selectedTab == TAB_ANIME && animeState.selectionMode) {
                    UpdatesSelectionToolbar(
                        selectedCount = animeState.selected.size,
                        onCancelActionMode = { animeScreenModel.toggleAllSelection(false) },
                        onClickSelectAll = { animeScreenModel.toggleAllSelection(true) },
                        onClickInvertSelection = animeScreenModel::invertSelection,
                    )
                } else {
                    SearchToolbar(
                        titleContent = { AppBarTitle(stringResource(MR.strings.label_recent_updates)) },
                        searchQuery = null,
                        onChangeSearchQuery = {},
                        actions = {
                            if (selectedTab == TAB_ANIME) {
                                AppBarActions(
                                    persistentListOf(
                                        AppBar.Action(
                                            title = stringResource(MR.strings.action_update_library),
                                            icon = Icons.Outlined.Refresh,
                                            onClick = { animeScreenModel.updateLibrary() },
                                        ),
                                    ),
                                )
                            } else {
                                AppBarActions(
                                    persistentListOf(
                                        AppBar.Action(
                                            title = stringResource(MR.strings.action_filter),
                                            icon = Icons.Outlined.FilterList,
                                            iconTint = if (state.hasActiveFilters) {
                                                MaterialTheme.colorScheme.active
                                            } else {
                                                LocalContentColor.current
                                            },
                                            onClick = screenModel::showFilterDialog,
                                        ),
                                        AppBar.Action(
                                            title = stringResource(MR.strings.action_view_upcoming),
                                            icon = Icons.Outlined.CalendarMonth,
                                            onClick = { navigator.push(UpcomingScreen()) },
                                        ),
                                        AppBar.Action(
                                            title = stringResource(MR.strings.action_update_library),
                                            icon = Icons.Outlined.Refresh,
                                            onClick = { screenModel.updateLibrary() },
                                        ),
                                    ),
                                )
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
        ) { contentPadding ->
            val layoutDirection = LocalLayoutDirection.current
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        top = contentPadding.calculateTopPadding(),
                        start = contentPadding.calculateStartPadding(layoutDirection),
                        end = contentPadding.calculateEndPadding(layoutDirection),
                    ),
            ) {
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.zIndex(1f),
                ) {
                    Tab(
                        selected = selectedTab == TAB_MANGA,
                        onClick = { scope.launch { pagerState.animateScrollToPage(TAB_MANGA) } },
                        text = { TabText(text = stringResource(MR.strings.manga_singular)) },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                    Tab(
                        selected = selectedTab == TAB_ANIME,
                        onClick = { scope.launch { pagerState.animateScrollToPage(TAB_ANIME) } },
                        text = { TabText(text = stringResource(MR.strings.label_anime)) },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                    Tab(
                        selected = selectedTab == TAB_NOVEL,
                        onClick = { scope.launch { pagerState.animateScrollToPage(TAB_NOVEL) } },
                        text = { TabText(text = stringResource(MR.strings.novel_singular)) },
                        unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                    )
                }

                HorizontalPager(
                    modifier = Modifier.fillMaxSize(),
                    state = pagerState,
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    when (page) {
                        TAB_ANIME -> AnimeUpdateScreen(
                            state = animeState,
                            snackbarHostState = animeScreenModel.snackbarHostState,
                            lastUpdated = animeScreenModel.lastUpdated,
                            onClickCover = { item -> navigator.push(AnimeScreen(item.update.animeId)) },
                            onSelectAll = animeScreenModel::toggleAllSelection,
                            onUpdateLibrary = animeScreenModel::updateLibrary,
                            onDownloadEpisode = animeScreenModel::downloadEpisodes,
                            onMultiBookmarkClicked = animeScreenModel::bookmarkUpdates,
                            onMultiFillermarkClicked = animeScreenModel::fillermarkUpdates,
                            onMultiMarkAsSeenClicked = animeScreenModel::markUpdatesSeen,
                            onMultiDeleteClicked = animeScreenModel::showConfirmDeleteEpisodes,
                            onUpdateSelected = animeScreenModel::toggleSelection,
                            onOpenEpisode = { _, _ ->
                                // TODO: wire up episode player for anime updates
                            },
                        )
                        TAB_NOVEL -> NovelUpdatesScreen(
                            isLoading = novelScreenModel.state.collectAsState().value.isLoading,
                            items = novelScreenModel.state.collectAsState().value.items,
                            onClickItem = { update ->
                                navigator.push(
                                    chimahon.novel.ui.detail.NovelDetailScreen(
                                        novel = eu.kanade.tachiyomi.sourcenovel.model.SNNovel(
                                            url = update.novelUrl,
                                            title = update.novelTitle,
                                            source = update.sourceId,
                                        ),
                                        sourceId = update.sourceId,
                                    ),
                                )
                            },
                        )
                        else -> UpdateScreen(
                            state = state,
                            snackbarHostState = screenModel.snackbarHostState,
                            lastUpdated = screenModel.lastUpdated,
                            // SY -->
                            preserveReadingPosition = screenModel.preserveReadingPosition,
                            // SY <--
                            onClickCover = { item -> navigator.push(MangaScreen(item.update.mangaId)) },
                            onSelectAll = screenModel::toggleAllSelection,
                            onUpdateLibrary = screenModel::updateLibrary,
                            onDownloadChapter = screenModel::downloadChapters,
                            onMultiBookmarkClicked = screenModel::bookmarkUpdates,
                            onMultiMarkAsReadClicked = screenModel::markUpdatesRead,
                            onMultiDeleteClicked = screenModel::showConfirmDeleteChapters,
                            // KMK -->
                            updateSwipeStartAction = screenModel.chapterSwipeStartAction,
                            updateSwipeEndAction = screenModel.chapterSwipeEndAction,
                            onUpdateSwipe = screenModel::updateSwipe,
                            // KMK <--
                            onUpdateSelected = screenModel::toggleSelection,
                            onOpenChapter = {
                                val intent = ReaderActivity.newIntent(context, it.update.mangaId, it.update.chapterId)
                                context.startActivity(intent)
                            },
                            // KMK -->
                            usePanoramaCover = usePanoramaCover,
                            collapseToggle = screenModel::toggleExpandedState,
                            // KMK <--
                        )
                    }
                }
            }
        }

        val onDismissDialog = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is UpdatesScreenModel.Dialog.DeleteConfirmation -> {
                UpdatesDeleteConfirmationDialog(
                    onDismissRequest = onDismissDialog,
                    onConfirm = { screenModel.deleteChapters(dialog.toDelete) },
                )
            }
            is UpdatesScreenModel.Dialog.FilterSheet -> {
                UpdatesFilterDialog(
                    onDismissRequest = onDismissDialog,
                    screenModel = settingsScreenModel,
                )
            }
            null -> {}
        }

        val animeOnDismissDialog = { animeScreenModel.setDialog(null) }
        when (val dialog = animeState.dialog) {
            is AnimeUpdatesScreenModel.Dialog.DeleteConfirmation -> {
                UpdatesDeleteConfirmationDialog(
                    onDismissRequest = animeOnDismissDialog,
                    onConfirm = { animeScreenModel.deleteEpisodes(dialog.toDelete) },
                    isManga = false,
                )
            }
            is AnimeUpdatesScreenModel.Dialog.ShowQualities -> {
                EpisodeOptionsDialogScreen.onDismissDialog = animeOnDismissDialog
                NavigatorAdaptiveSheet(
                    screen = EpisodeOptionsDialogScreen(
                        useExternalDownloader = animeScreenModel.useExternalDownloader,
                        episodeTitle = dialog.episodeTitle,
                        episodeId = dialog.episodeId,
                        animeId = dialog.animeId,
                        sourceId = dialog.sourceId,
                    ),
                    onDismissRequest = animeOnDismissDialog,
                )
            }
            null -> {}
        }

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    Event.InternalError -> screenModel.snackbarHostState.showSnackbar(
                        context.stringResource(MR.strings.internal_error),
                    )
                    is Event.LibraryUpdateTriggered -> {
                        val msg = if (event.started) {
                            MR.strings.updating_library
                        } else {
                            MR.strings.update_already_running
                        }
                        screenModel.snackbarHostState.showSnackbar(context.stringResource(msg))
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            animeScreenModel.events.collectLatest { event ->
                when (event) {
                    AnimeUpdatesScreenModel.Event.InternalError -> animeScreenModel.snackbarHostState.showSnackbar(
                        context.stringResource(
                            MR.strings.internal_error,
                        ),
                    )
                    is AnimeUpdatesScreenModel.Event.LibraryUpdateTriggered -> {
                        val msg = if (event.started) {
                            MR.strings.updating_library
                        } else {
                            MR.strings.update_already_running
                        }
                        animeScreenModel.snackbarHostState.showSnackbar(context.stringResource(msg))
                    }
                }
            }
        }

        LaunchedEffect(state.selectionMode) {
            HomeScreen.showBottomNav(!state.selectionMode)
        }

        LaunchedEffect(animeState.selectionMode) {
            HomeScreen.showBottomNav(!animeState.selectionMode)
        }

        LaunchedEffect(state.isLoading) {
            if (!state.isLoading) {
                (context as? MainActivity)?.ready = true

                // AM (DISCORD) -->
                with(DiscordRPCService) {
                    discordScope.launchIO { setScreen(context, DiscordScreen.UPDATES) }
                }
                // <-- AM (DISCORD)
            }
        }

        LaunchedEffect(animeState.isLoading) {
            if (!animeState.isLoading) {
                (context as? MainActivity)?.ready = true
            }
        }

        DisposableEffect(Unit) {
            screenModel.resetNewUpdatesCount()
            animeScreenModel.resetNewUpdatesCount()

            onDispose {
                screenModel.resetNewUpdatesCount()
                animeScreenModel.resetNewUpdatesCount()
            }
        }
    }
}

@Composable
private fun UpdatesSelectionToolbar(
    selectedCount: Int,
    onCancelActionMode: () -> Unit,
    onClickSelectAll: () -> Unit,
    onClickInvertSelection: () -> Unit,
) {
    AppBar(
        titleContent = { AppBarTitle(title = "$selectedCount") },
        actions = {
            AppBarActions(
                persistentListOf(
                    AppBar.Action(
                        title = stringResource(MR.strings.action_select_all),
                        icon = Icons.Outlined.SelectAll,
                        onClick = onClickSelectAll,
                    ),
                    AppBar.Action(
                        title = stringResource(MR.strings.action_select_inverse),
                        icon = Icons.Outlined.FlipToBack,
                        onClick = onClickInvertSelection,
                    ),
                ),
            )
        },
        isActionMode = true,
        onCancelActionMode = onCancelActionMode,
    )
}

private const val TAB_MANGA = 0
private const val TAB_ANIME = 1
private const val TAB_NOVEL = 2
private const val TAB_COUNT = 3
