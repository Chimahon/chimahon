package eu.kanade.tachiyomi.ui.entries.anime

import androidx.activity.compose.BackHandler
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.util.fastAll
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.entries.components.LibraryBottomActionMenu
import mihon.feature.animemigration.config.AnimeMigrationConfigScreen
import eu.kanade.presentation.library.DeleteLibraryEntryDialog
import eu.kanade.presentation.entries.anime.library.AnimeLibraryContent
import eu.kanade.presentation.entries.anime.library.AnimeLibrarySettingsDialog
import eu.kanade.presentation.library.components.LibraryPagerBoundary
import eu.kanade.presentation.library.components.LibraryToolbar
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.presentation.library.components.libraryModeBoundarySwipe
import eu.kanade.presentation.more.onboarding.GETTING_STARTED_URL
import eu.kanade.tachiyomi.ui.library.LibraryModeTitleContent
import eu.kanade.tachiyomi.ui.library.LibraryViewMode
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.library.anime.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.ui.browse.animesource.globalsearch.GlobalAnimeSearchScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.entries.anime.library.AnimeLibraryScreenModel
import eu.kanade.tachiyomi.ui.entries.anime.library.AnimeLibrarySettingsScreenModel
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.AnimeCategory
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.library.model.LibraryAnime
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.entries.anime.isLocal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen.AnimeLibraryPanel(
    libraryMode: LibraryViewMode? = null,
    showModeDropdown: Boolean = false,
    onToggleDropdown: () -> Unit = {},
    onDismissDropdown: () -> Unit = {},
    onModeSelected: (LibraryViewMode) -> Unit = {},
    settingsEvent: Channel<Unit> = requestSettingsSheetEvent,
    entryTarget: LibraryPagerBoundary? = null,
    onEntryTargetConsumed: () -> Unit = {},
    onBoundarySwipe: (LibraryPagerBoundary) -> Unit = {},
) {
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val screenModel = rememberScreenModel { AnimeLibraryScreenModel() }
    val settingsScreenModel = rememberScreenModel { AnimeLibrarySettingsScreenModel() }
    val state by screenModel.state.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    val onClickRefresh: (AnimeCategory?) -> Boolean = { category ->
        val started = AnimeLibraryUpdateJob.startNow(context, category)
        scope.launch {
            val msgRes = when {
                !started -> MR.strings.update_already_running
                category != null -> MR.strings.updating_category
                else -> MR.strings.updating_library
            }
            snackbarHostState.showSnackbar(context.stringResource(msgRes))
        }
        started
    }

    suspend fun openEpisode(episode: Episode) {
        context.startActivity(PlayerActivity.newIntent(context, episode.animeId, episode.id))
    }

    val defaultTitle = stringResource(MR.strings.label_anime)

    Scaffold(
        topBar = { scrollBehavior ->
            val title = state.getToolbarTitle(
                defaultTitle = defaultTitle,
                defaultCategoryTitle = stringResource(MR.strings.label_default),
                page = state.coercedActiveCategoryIndex,
            )
            val tabVisible = state.showCategoryTabs && state.categories.size > 1
            LibraryToolbar(
                hasActiveFilters = state.hasActiveFilters,
                selectedCount = state.selection.size,
                title = title,
                titleContent = if (libraryMode != null) {
                    {
                        LibraryModeTitleContent(
                            title = title,
                            showModeDropdown = showModeDropdown,
                            onToggleDropdown = onToggleDropdown,
                            onDismissDropdown = onDismissDropdown,
                            libraryMode = libraryMode,
                            onModeSelected = onModeSelected,
                        )
                    }
                } else {
                    null
                },
                onClickUnselectAll = screenModel::clearSelection,
                onClickSelectAll = { screenModel.selectAll(state.coercedActiveCategoryIndex) },
                onClickInvertSelection = {
                    screenModel.invertSelection(
                        state.coercedActiveCategoryIndex,
                    )
                },
                onClickFilter = screenModel::showSettingsDialog,
                onClickRefresh = {
                    onClickRefresh(
                        state.displayCategories.getOrNull(state.coercedActiveCategoryIndex),
                    )
                },
                onClickGlobalUpdate = { onClickRefresh(null) },
                onClickOpenRandomManga = {
                    scope.launch {
                        val randomItem = screenModel.getRandomAnimelibItemForCurrentCategory()
                        if (randomItem != null) {
                            navigator.push(AnimeScreen(randomItem.libraryAnime.anime.id))
                        } else {
                            snackbarHostState.showSnackbar(
                                context.stringResource(MR.strings.information_no_entries_found),
                            )
                        }
                    }
                },
                onClickSyncNow = {
                    if (!SyncDataJob.isRunning(context)) {
                        SyncDataJob.startNow(context, manual = true)
                    } else {
                        context.toast(SYMR.strings.sync_in_progress)
                    }
                },
                onClickSyncExh = null,
                isSyncEnabled = state.isSyncEnabled,
                searchQuery = state.searchQuery,
                onSearchQueryChange = screenModel::search,
                scrollBehavior = scrollBehavior.takeIf { !tabVisible },
                onInvalidateDownloadCache = null,
                onClickEditCategories = {
                    navigator.push(CategoryScreen(CategoryScreen.Tab.ANIME))
                },
                editCategoriesTitle = stringResource(MR.strings.action_edit_categories),
            )
        },
        bottomBar = {
            LibraryBottomActionMenu(
                visible = state.selectionMode,
                onChangeCategoryClicked = screenModel::openChangeCategoryDialog,
                onMarkAsViewedClicked = { screenModel.markSeenSelection(true) },
                onMarkAsUnviewedClicked = { screenModel.markSeenSelection(false) },
                onDownloadClicked = screenModel::runDownloadActionSelection
                    .takeIf { state.selection.fastAll { !it.anime.isLocal() } },
                onDeleteClicked = screenModel::openDeleteAnimeDialog,
                onMigrateClicked = {
                    navigator.push(AnimeMigrationConfigScreen(state.selection.map { it.anime.id }))
                },
                isManga = false,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        when {
            state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
            state.searchQuery.isNullOrEmpty() && !state.hasActiveFilters && state.isLibraryEmpty -> {
                val handler = LocalUriHandler.current
                EmptyScreen(
                    stringRes = MR.strings.information_empty_library,
                    modifier = Modifier
                        .padding(contentPadding)
                        .libraryModeBoundarySwipe(
                            enabled = state.selection.isEmpty(),
                            onBoundarySwipe = onBoundarySwipe,
                        ),
                    actions = persistentListOf(
                        EmptyScreenAction(
                            stringRes = MR.strings.getting_started_guide,
                            icon = Icons.AutoMirrored.Outlined.HelpOutline,
                            onClick = { handler.openUri(GETTING_STARTED_URL) },
                        ),
                    ),
                )
            }
            else -> {
                AnimeLibraryContent(
                    categories = state.displayCategories,
                    searchQuery = state.searchQuery,
                    selection = state.selection,
                    contentPadding = contentPadding,
                    currentPage = state.coercedActiveCategoryIndex,
                    hasActiveFilters = state.hasActiveFilters,
                    showPageTabs = state.showCategoryTabs || !state.searchQuery.isNullOrEmpty(),
                    onChangeCurrentPage = screenModel::updateActiveCategoryIndex,
                    onAnimeClicked = { navigator.push(AnimeScreen(it)) },
                    onContinueWatchingClicked = { it: LibraryAnime ->
                        scope.launchIO {
                            val episode = screenModel.getNextUnseenEpisode(it.anime)
                            if (episode != null) openEpisode(episode)
                        }
                        Unit
                    }.takeIf { state.showAnimeContinueButton },
                    onToggleSelection = screenModel::toggleSelection,
                    onToggleRangeSelection = {
                        screenModel.toggleRangeSelection(it)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                    onRefresh = onClickRefresh,
                    onGlobalSearchClicked = {
                        navigator.push(
                            GlobalAnimeSearchScreen(screenModel.state.value.searchQuery ?: ""),
                        )
                    },
                    getNumberOfAnimeForCategory = { state.getAnimeCountForCategory(it) },
                    getDisplayMode = { screenModel.getDisplayMode() },
                    getColumnsForOrientation = {
                        screenModel.getColumnsPreferenceForCurrentOrientation(
                            it,
                        )
                    },
                    entryTarget = entryTarget,
                    onEntryTargetConsumed = onEntryTargetConsumed,
                    onBoundarySwipe = onBoundarySwipe,
                    getAnimeLibraryForPage = { state.getAnimelibItemsByPage(it) },
                )
            }
        }
    }

    val onDismissRequest = screenModel::closeDialog
    when (val dialog = state.dialog) {
        is AnimeLibraryScreenModel.Dialog.SettingsSheet -> run {
            val category = state.displayCategories.getOrNull(state.coercedActiveCategoryIndex)
            if (category == null) {
                onDismissRequest()
                return@run
            }
            AnimeLibrarySettingsDialog(
                onDismissRequest = onDismissRequest,
                screenModel = settingsScreenModel,
                category = category,
            )
        }
        is AnimeLibraryScreenModel.Dialog.ChangeCategory -> {
            ChangeCategoryDialog(
                initialSelection = dialog.initialSelection,
                onDismissRequest = onDismissRequest,
                onEditCategories = {
                    screenModel.clearSelection()
                    navigator.push(CategoryScreen(CategoryScreen.Tab.ANIME))
                },
                onConfirm = { include, exclude ->
                    screenModel.clearSelection()
                    screenModel.setAnimeCategories(dialog.anime, include, exclude)
                },
            )
        }
        is AnimeLibraryScreenModel.Dialog.DeleteAnime -> {
            DeleteLibraryEntryDialog(
                containsLocalEntry = dialog.anime.any(Anime::isLocal),
                onDismissRequest = onDismissRequest,
                onConfirm = { deleteAnime, deleteEpisode ->
                    screenModel.removeAnimes(dialog.anime, deleteAnime, deleteEpisode)
                    screenModel.clearSelection()
                },
                isManga = false,
            )
        }
        null -> {}
    }

    BackHandler(enabled = state.selectionMode || state.searchQuery != null) {
        when {
            state.selectionMode -> screenModel.clearSelection()
            state.searchQuery != null -> screenModel.search(null)
        }
    }

    LaunchedEffect(state.selectionMode, state.dialog) {
        HomeScreen.showBottomNav(!state.selectionMode)
    }

    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) {
            (context as? MainActivity)?.ready = true
        }
    }

    LaunchedEffect(Unit) {
        launch { queryEvent.receiveAsFlow().collect(screenModel::search) }
        launch { settingsEvent.receiveAsFlow().collectLatest { screenModel.showSettingsDialog() } }
    }
}

data object AnimeTab : Tab {

    @OptIn(ExperimentalAnimationGraphicsApi::class)
    override val options: TabOptions
        @Composable
        get() {
            val image = AnimatedImageVector.animatedVectorResource(
                R.drawable.anim_animelibrary_leave,
            )
            return TabOptions(
                index = 6u,
                title = stringResource(MR.strings.label_anime),
                icon = rememberAnimatedVectorPainter(image, false),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        requestAnimeSettingsSheet()
    }

    @Composable
    override fun Content() {
        AnimeLibraryPanel()
    }

    suspend fun search(query: String) = searchAnimeLibrary(query)
}

// For invoking search from other screen
private val queryEvent = Channel<String>()
suspend fun searchAnimeLibrary(query: String) = queryEvent.send(query)

// For opening settings sheet in LibraryController
private val requestSettingsSheetEvent = Channel<Unit>()
suspend fun requestAnimeSettingsSheet() = requestSettingsSheetEvent.send(Unit)
