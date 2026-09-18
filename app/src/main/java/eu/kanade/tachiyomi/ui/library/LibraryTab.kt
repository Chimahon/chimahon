package eu.kanade.tachiyomi.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.graphics.res.animatedVectorResource
import dev.icerock.moko.resources.StringResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.library.DeleteLibraryMangaDialog
import eu.kanade.presentation.library.LibrarySettingsDialog
import eu.kanade.presentation.library.components.LibraryContent
import eu.kanade.presentation.library.components.LibraryPagerBoundary
import eu.kanade.presentation.library.components.LibraryToolbar
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.presentation.library.components.SyncFavoritesConfirmDialog
import eu.kanade.presentation.library.components.libraryModeBoundarySwipe
import eu.kanade.presentation.library.components.SyncFavoritesProgressDialog
import eu.kanade.presentation.library.components.SyncFavoritesWarningDialog
import eu.kanade.presentation.manga.components.LibraryBottomActionMenu
import eu.kanade.presentation.more.onboarding.GETTING_STARTED_URL
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.ui.browse.source.LocalMangaImportDialogs
import eu.kanade.tachiyomi.ui.browse.source.SourcesScreen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.browse.source.rememberLocalMangaImportState
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.entries.anime.AnimeLibraryPanel
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.library.novels.NovelLibraryScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.toast
import exh.favorites.FavoritesSyncStatus
import exh.recs.RecommendsScreen
import exh.recs.batch.RecommendationSearchBottomSheetDialog
import exh.recs.batch.RecommendationSearchProgressDialog
import exh.recs.batch.SearchStatus
import exh.source.MERGED_SOURCE_ID
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlin.jvm.Volatile
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import mihon.feature.migration.config.MigrationConfigScreen
import mihon.feature.trackadd.TrackAddScreen
import mihon.feature.trackadd.components.TrackAddTrackerPicker
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

enum class LibraryViewMode(val labelRes: StringResource) {
    Manga(MR.strings.manga_singular),
    Anime(MR.strings.label_anime),
    Novels(MR.strings.label_novels),
}

data object LibraryTab : Tab {
    @Suppress("unused")
    private fun readResolve(): Any = LibraryTab

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_library_enter)
            val label = if (Injekt.get<UiPreferences>().useConsolidatedLibrary().get()) {
                MR.strings.label_library
            } else {
                MR.strings.manga_singular
            }
            return TabOptions(
                index = 0u,
                title = stringResource(label),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    @Volatile
    private var currentReselectMode: LibraryViewMode = LibraryViewMode.Manga

    override suspend fun onReselect(navigator: Navigator) {
        if (!Injekt.get<UiPreferences>().useConsolidatedLibrary().get()) {
            mangaSettingsEvent.send(Unit)
            return
        }
        when (currentReselectMode) {
            LibraryViewMode.Manga -> mangaSettingsEvent.send(Unit)
            LibraryViewMode.Anime -> animeSettingsEvent.send(Unit)
            LibraryViewMode.Novels -> novelSortEvent.send(Unit)
        }
    }

    @Composable
    override fun Content() {
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val useConsolidatedLibrary = remember { uiPreferences.useConsolidatedLibrary().get() }

        if (!useConsolidatedLibrary) {
            MangaLibraryContent()
            return
        }

        val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
        var libraryMode by remember {
            mutableStateOf(LibraryViewMode.entries.getOrElse(libraryPreferences.lastUsedLibraryMode().get()) { LibraryViewMode.Manga })
        }
        var showModeDropdown by remember { mutableStateOf(false) }
        var entryTarget by remember { mutableStateOf<LibraryPagerBoundary?>(null) }

        fun selectMode(mode: LibraryViewMode, target: LibraryPagerBoundary? = null) {
            showModeDropdown = false
            entryTarget = target
            libraryMode = mode
            libraryPreferences.lastUsedLibraryMode().set(mode.ordinal)
        }

        val onBoundarySwipe: (LibraryPagerBoundary) -> Unit = { boundary ->
            val targetMode = when (boundary) {
                LibraryPagerBoundary.Start -> LibraryViewMode.entries.getOrNull(libraryMode.ordinal - 1)
                LibraryPagerBoundary.End -> LibraryViewMode.entries.getOrNull(libraryMode.ordinal + 1)
            }
            if (targetMode != null) {
                selectMode(
                    targetMode,
                    target = if (boundary == LibraryPagerBoundary.Start) {
                        LibraryPagerBoundary.End
                    } else {
                        LibraryPagerBoundary.Start
                    },
                )
            }
        }

        LaunchedEffect(Unit) {
            modeSelectionEvent.receiveAsFlow().collectLatest { mode -> selectMode(mode) }
        }

        currentReselectMode = libraryMode

        when (libraryMode) {
            LibraryViewMode.Manga -> MangaLibraryContent(
                libraryMode = libraryMode,
                showModeDropdown = showModeDropdown,
                onToggleDropdown = { showModeDropdown = true },
                onDismissDropdown = { showModeDropdown = false },
                onModeSelected = { mode -> selectMode(mode) },
                entryTarget = entryTarget,
                onEntryTargetConsumed = { entryTarget = null },
                onBoundarySwipe = onBoundarySwipe,
            )
            LibraryViewMode.Anime -> AnimeLibraryPanel(
                libraryMode = libraryMode,
                showModeDropdown = showModeDropdown,
                onToggleDropdown = { showModeDropdown = true },
                onDismissDropdown = { showModeDropdown = false },
                onModeSelected = { mode -> selectMode(mode) },
                settingsEvent = animeSettingsEvent,
                entryTarget = entryTarget,
                onEntryTargetConsumed = { entryTarget = null },
                onBoundarySwipe = onBoundarySwipe,
            )
            LibraryViewMode.Novels -> NovelLibraryScreen(
                libraryMode = libraryMode,
                showModeDropdown = showModeDropdown,
                onToggleDropdown = { showModeDropdown = true },
                onDismissDropdown = { showModeDropdown = false },
                onModeSelected = { mode -> selectMode(mode) },
                requestSortEvent = novelSortEvent,
                entryTarget = entryTarget,
                onEntryTargetConsumed = { entryTarget = null },
                onBoundarySwipe = onBoundarySwipe,
            )
        }
    }

    @Composable
    private fun MangaLibraryContent(
        libraryMode: LibraryViewMode? = null,
        showModeDropdown: Boolean = false,
        onToggleDropdown: () -> Unit = {},
        onDismissDropdown: () -> Unit = {},
        onModeSelected: (LibraryViewMode) -> Unit = {},
        entryTarget: LibraryPagerBoundary? = null,
        onEntryTargetConsumed: () -> Unit = {},
        onBoundarySwipe: (LibraryPagerBoundary) -> Unit = {},
    ) {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current

        val screenModel = rememberScreenModel { LibraryScreenModel() }
        val settingsScreenModel = rememberScreenModel { LibrarySettingsScreenModel() }
        val state by screenModel.state.collectAsState()

        val snackbarHostState = remember { SnackbarHostState() }
        var showTrackerPicker by remember { mutableStateOf(false) }
        var selectedMangaIdsForTracker by remember { mutableStateOf<List<Long>>(emptyList()) }
        val importState = rememberLocalMangaImportState()
        LocalMangaImportDialogs(state = importState, includeNovelOption = false)

        val onClickRefresh: (Category?) -> Boolean = { category ->
            // SY -->
            val started = LibraryUpdateJob.startNow(
                context = context,
                category = if (state.groupType == LibraryGroup.BY_DEFAULT) category else null,
                group = state.groupType,
                groupExtra = when (state.groupType) {
                    LibraryGroup.BY_DEFAULT -> null
                    LibraryGroup.BY_SOURCE, LibraryGroup.BY_TRACK_STATUS -> category?.id?.toString()
                    LibraryGroup.BY_STATUS -> category?.id?.minus(1)?.toString()
                    else -> null
                },
            )
            // SY <--
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

        Scaffold(
            topBar = { scrollBehavior ->
                val title = state.getToolbarTitle(
                    defaultTitle = stringResource(libraryMode?.labelRes ?: MR.strings.manga_singular),
                    defaultCategoryTitle = stringResource(MR.strings.label_default),
                    page = state.coercedActiveCategoryIndex,
                )
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
                    onClickSelectAll = screenModel::selectAll,
                    onClickInvertSelection = screenModel::invertSelection,
                    onClickFilter = screenModel::showSettingsDialog,
                    onClickRefresh = { onClickRefresh(state.activeCategory) },
                    onClickGlobalUpdate = { onClickRefresh(null) },
                    onClickOpenRandomManga = {
                        scope.launch {
                            val randomItem = screenModel.getRandomLibraryItemForCurrentCategory()
                            if (randomItem != null) {
                                navigator.push(MangaScreen(randomItem.libraryManga.manga.id))
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
                    // SY -->
                    onClickSyncExh = screenModel::openFavoritesSyncDialog.takeIf { state.showSyncExh },
                    isSyncEnabled = state.isSyncEnabled,
                    // SY <--
                    searchQuery = state.searchQuery,
                    onSearchQueryChange = screenModel::search,
                    onInvalidateDownloadCache = { context ->
                        Injekt.get<DownloadCache>().invalidateCache()
                        context.toast(MR.strings.download_cache_invalidated)
                    },
                    // For scroll overlay when no tab
                    scrollBehavior = scrollBehavior.takeIf { !state.showCategoryTabs },
                )
            },
            bottomBar = {
                LibraryBottomActionMenu(
                    visible = state.selectionMode,
                    onChangeCategoryClicked = screenModel::openChangeCategoryDialog,
                    onMarkAsReadClicked = { screenModel.markReadSelection(true) },
                    onMarkAsUnreadClicked = { screenModel.markReadSelection(false) },
                    onDownloadClicked = screenModel::performDownloadAction
                        .takeIf { state.selectedManga.fastAll { !it.isLocal() } },
                    onDeleteClicked = screenModel::openDeleteMangaDialog,
                    onMigrateClicked = {
                        val selection = state
                            // KMK -->
                            .selectedManga
                            .filterNot { it.source == MERGED_SOURCE_ID }
                            .map { it.id }
                        // KMK <--
                        screenModel.clearSelection()
                        // KMK -->
                        if (selection.isEmpty()) {
                            context.toast(SYMR.strings.no_valid_entry)
                        } else {
                            // KMK <--
                            navigator.push(MigrationConfigScreen(selection))
                        }
                    },
                    // KMK -->
                    onMergeClicked = {
                        if (state.selection.size == 1) {
                            val manga = state.selectedManga.first()
                            // Invoke merging for this manga
                            screenModel.clearSelection()
                            val smartSearchConfig = SourcesScreen.SmartSearchConfig(manga.title, manga.id)
                            navigator.push(SourcesScreen(smartSearchConfig))
                        } else if (state.selection.isNotEmpty()) {
                            // Invoke multiple merge
                            val selectedManga = state.selectedManga
                            screenModel.clearSelection()
                            scope.launchIO {
                                val mergingMangas = selectedManga.filterNot { it.source == MERGED_SOURCE_ID }
                                val mergedMangaId = screenModel.smartSearchMerge(selectedManga.toPersistentList())
                                snackbarHostState.showSnackbar(context.stringResource(SYMR.strings.entry_merged))
                                if (mergedMangaId != null) {
                                    val result = snackbarHostState.showSnackbar(
                                        message = context.stringResource(KMR.strings.action_remove_merged),
                                        actionLabel = context.stringResource(MR.strings.action_remove),
                                        withDismissAction = true,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) {
                                        screenModel.removeMangas(
                                            mangas = mergingMangas,
                                            deleteFromLibrary = true,
                                            deleteChapters = false,
                                        )
                                    }
                                    navigator.push(MangaScreen(mergedMangaId))
                                } else {
                                    snackbarHostState.showSnackbar(context.stringResource(SYMR.strings.merged_references_invalid))
                                }
                            }
                        } else {
                            screenModel.clearSelection()
                            context.toast(SYMR.strings.no_valid_entry)
                        }
                    },
                    onSelectionUpdateClicked = {
                        val started = screenModel.updateSelectedManga()
                        scope.launch {
                            val msgRes = if (started) {
                                KMR.strings.updating
                            } else {
                                MR.strings.update_already_running
                            }
                            if (started) {
                                screenModel.clearSelection()
                            }
                            snackbarHostState.showSnackbar(context.stringResource(msgRes))
                        }
                    },
                    // KMK <--
                    // SY -->
                    onClickCleanTitles = screenModel::cleanTitles.takeIf { state.showCleanTitles },
                    onClickCollectRecommendations = screenModel::showRecommendationSearchDialog.takeIf { state.selection.size > 1 },
                    onClickAddToMangaDex = screenModel::syncMangaToDex.takeIf { state.showAddToMangadex },
                    onClickResetInfo = screenModel::resetInfo.takeIf { state.showResetInfo },
                    // SY <--
                    onTrackAddClicked = {
                        selectedMangaIdsForTracker = state.selectedManga.map { it.id }
                        showTrackerPicker = true
                    }.takeIf {
                        state.selectionMode &&
                            Injekt.get<TrackerManager>().loggedInTrackers().any { it !is EnhancedTracker }
                    },
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = !state.selectionMode && state.showFloatingAddButton,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    FloatingActionButton(
                        onClick = { importState.showImportDialog = true },
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = stringResource(MR.strings.action_add),
                        )
                    }
                }
            },
        ) { contentPadding ->
            when {
                state.isLoading -> {
                    LoadingScreen(Modifier.padding(contentPadding))
                }
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
                    LibraryContent(
                        categories = state.displayedCategories,
                        // KMK -->
                        activeCategoryIndex = state.coercedActiveCategoryIndex,
                        // KMK <--
                        searchQuery = state.searchQuery,
                        selection = state.selection,
                        contentPadding = contentPadding,
                        currentPage = state.coercedActiveCategoryIndex,
                        hasActiveFilters = state.hasActiveFilters,
                        showPageTabs = state.showCategoryTabs || !state.searchQuery.isNullOrEmpty(),
                        onChangeCurrentPage = screenModel::updateActiveCategoryIndex,
                        onClickManga = { navigator.push(MangaScreen(it)) },
                        onContinueReadingClicked = { it: LibraryManga ->
                            scope.launchIO {
                                val chapter = screenModel.getNextUnreadChapter(it.manga)
                                if (chapter != null) {
                                    context.startActivity(
                                        ReaderActivity.newIntent(context, chapter.mangaId, chapter.id),
                                    )
                                } else {
                                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                                }
                            }
                            Unit
                        }.takeIf { state.showMangaContinueButton },
                        onToggleSelection = screenModel::toggleSelection,
                        onToggleRangeSelection = { category, manga ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            screenModel.toggleRangeSelection(category, manga)
                        },
                        onRefresh = { onClickRefresh(state.activeCategory) },
                        onGlobalSearchClicked = {
                            navigator.push(GlobalSearchScreen(screenModel.state.value.searchQuery ?: ""))
                        },
                        getItemCountForCategory = { state.getItemCountForCategory(it) },
                        getDisplayMode = { screenModel.getDisplayMode() },
                        getColumnsForOrientation = { screenModel.getColumnsForOrientation(it) },
                        getItemsForCategory = { state.getItemsForCategory(it) },
                        entryTarget = entryTarget,
                        onEntryTargetConsumed = onEntryTargetConsumed,
                        onBoundarySwipe = onBoundarySwipe,
                    )
                }
            }
        }

        val onDismissRequest = screenModel::closeDialog
        when (val dialog = state.dialog) {
            is LibraryScreenModel.Dialog.SettingsSheet -> run {
                LibrarySettingsDialog(
                    onDismissRequest = onDismissRequest,
                    screenModel = settingsScreenModel,
                    category = state.activeCategory,
                    // SY -->
                    hasCategories = state.libraryData.categories.fastAny { !it.isSystemCategory },
                    // SY <--
                    // KMK -->
                    categories = state.libraryData.categories.filterNot(Category::isSystemCategory),
                    // KMK <--
                )
            }
            is LibraryScreenModel.Dialog.ChangeCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = {
                        // KMK -->
                        // screenModel.clearSelection()
                        // KMK <--
                        navigator.push(CategoryScreen())
                    },
                    onConfirm = { include, exclude ->
                        screenModel.clearSelection()
                        screenModel.setMangaCategories(dialog.manga, include, exclude)
                    },
                )
            }
            is LibraryScreenModel.Dialog.DeleteManga -> {
                DeleteLibraryMangaDialog(
                    containsLocalManga = dialog.manga.any(Manga::isLocal),
                    onDismissRequest = onDismissRequest,
                    onConfirm = { deleteManga, deleteChapter ->
                        screenModel.removeMangas(dialog.manga, deleteManga, deleteChapter)
                        screenModel.clearSelection()
                    },
                )
            }
            // SY -->
            LibraryScreenModel.Dialog.SyncFavoritesWarning -> {
                SyncFavoritesWarningDialog(
                    onDismissRequest = onDismissRequest,
                    onAccept = {
                        onDismissRequest()
                        screenModel.onAcceptSyncWarning()
                    },
                )
            }
            LibraryScreenModel.Dialog.SyncFavoritesConfirm -> {
                SyncFavoritesConfirmDialog(
                    onDismissRequest = onDismissRequest,
                    onAccept = {
                        onDismissRequest()
                        screenModel.runSync()
                    },
                )
            }
            is LibraryScreenModel.Dialog.RecommendationSearchSheet -> {
                RecommendationSearchBottomSheetDialog(
                    onDismissRequest = onDismissRequest,
                    onSearchRequest = {
                        onDismissRequest()
                        screenModel.clearSelection()
                        screenModel.runRecommendationSearch(dialog.manga)
                    },
                )
            }
            // SY <--
            null -> {}
        }

        // SY -->
        SyncFavoritesProgressDialog(
            status = screenModel.favoritesSync.status.collectAsState().value,
            setStatusIdle = { screenModel.favoritesSync.status.value = FavoritesSyncStatus.Idle },
            openManga = { navigator.push(MangaScreen(it)) },
        )

        RecommendationSearchProgressDialog(
            status = screenModel.recommendationSearch.status.collectAsState().value,
            setStatusIdle = { screenModel.recommendationSearch.status.value = SearchStatus.Idle },
            setStatusCancelling = { screenModel.recommendationSearch.status.value = SearchStatus.Cancelling },
        )
        // SY <--

        if (showTrackerPicker) {
            TrackAddTrackerPicker(
                trackers = Injekt.get<TrackerManager>().loggedInTrackers()
                    .filter { it !is EnhancedTracker },
                onSelect = { tracker ->
                    showTrackerPicker = false
                    screenModel.clearSelection()
                    navigator.push(TrackAddScreen(selectedMangaIdsForTracker, tracker.id))
                },
                onDismiss = {
                    showTrackerPicker = false
                    screenModel.clearSelection()
                },
            )
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

                // AM (DISCORD) -->
                with(DiscordRPCService) {
                    discordScope.launchIO { setScreen(context, DiscordScreen.LIBRARY) }
                }
                // <-- AM (DISCORD)
            }
        }

        // SY -->
        val recSearchState by screenModel.recommendationSearch.status.collectAsState()
        LaunchedEffect(recSearchState) {
            when (val current = recSearchState) {
                is SearchStatus.Finished.WithResults -> {
                    RecommendsScreen.Args.MergedSourceMangas(current.results)
                        .let(::RecommendsScreen)
                        .let(navigator::push)

                    screenModel.recommendationSearch.status.value = SearchStatus.Idle
                }
                is SearchStatus.Finished.WithoutResults -> {
                    context.toast(SYMR.strings.rec_no_results)
                    screenModel.recommendationSearch.status.value = SearchStatus.Idle
                }
                is SearchStatus.Cancelling -> {
                    screenModel.cancelRecommendationSearch()
                    screenModel.recommendationSearch.status.value = SearchStatus.Idle
                }
                else -> {}
            }
        }
        // SY <--

        LaunchedEffect(Unit) {
            launch { queryEvent.receiveAsFlow().collect(screenModel::search) }
            launch { mangaSettingsEvent.receiveAsFlow().collectLatest { screenModel.showSettingsDialog() } }
        }
    }

    // For invoking search from other screen
    private val queryEvent = Channel<String>(Channel.BUFFERED)
    suspend fun search(query: String) = queryEvent.send(query)

    private val mangaSettingsEvent = Channel<Unit>(Channel.BUFFERED)
    private val animeSettingsEvent = Channel<Unit>(Channel.BUFFERED)
    private val novelSortEvent = Channel<Unit>(Channel.BUFFERED)
    private val modeSelectionEvent = Channel<LibraryViewMode>(Channel.BUFFERED)

    suspend fun selectLibraryMode(mode: LibraryViewMode) {
        modeSelectionEvent.send(mode)
    }
}

@Composable
internal fun LibraryModeTitleContent(
    title: LibraryToolbarTitle,
    showModeDropdown: Boolean,
    onToggleDropdown: () -> Unit,
    onDismissDropdown: () -> Unit,
    libraryMode: LibraryViewMode,
    onModeSelected: (LibraryViewMode) -> Unit,
) {
    val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
    Row(
        modifier = Modifier.clickable { onToggleDropdown() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (title.numberOfManga != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Pill(
                    text = "${title.numberOfManga}",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = pillAlpha),
                    fontSize = 14.sp,
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Default.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
        )
        DropdownMenu(
            expanded = showModeDropdown,
            onDismissRequest = onDismissDropdown,
        ) {
            LibraryViewMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(mode.labelRes),
                            fontWeight = if (mode == libraryMode) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    onClick = { onModeSelected(mode) },
                )
            }
        }
    }
}
