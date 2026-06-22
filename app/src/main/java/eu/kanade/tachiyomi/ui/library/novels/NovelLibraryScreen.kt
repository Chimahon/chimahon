package eu.kanade.tachiyomi.ui.library.novels

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.canopus.chimareader.data.BookMetadata
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.LibraryPagerBoundary
import eu.kanade.presentation.library.components.LibraryTabs
import eu.kanade.presentation.library.components.LibraryToolbar
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.presentation.library.components.libraryModeBoundarySwipe
import eu.kanade.presentation.library.components.libraryPagerBoundarySwipe
import eu.kanade.presentation.manga.components.Button as BottomMenuButton
import com.canopus.chimareader.ttusync.TtuSyncManager
import eu.kanade.tachiyomi.data.SyncStatus
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.library.LibraryModeTitleContent
import eu.kanade.tachiyomi.ui.library.LibraryViewMode
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.components.SliderItem
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.util.plus
import kotlin.time.Duration.Companion.seconds
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen.NovelLibraryScreen(
    libraryMode: LibraryViewMode? = null,
    showModeDropdown: Boolean = false,
    onToggleDropdown: () -> Unit = {},
    onDismissDropdown: () -> Unit = {},
    onModeSelected: (LibraryViewMode) -> Unit = {},
    requestSortEvent: Channel<Unit>? = null,
    entryTarget: LibraryPagerBoundary? = null,
    onEntryTargetConsumed: () -> Unit = {},
    onBoundarySwipe: (LibraryPagerBoundary) -> Unit = {},
) {
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = rememberScreenModel { NovelLibraryScreenModel() }
    val state: NovelLibraryScreenModel.State by screenModel.state.collectAsState()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val ttuSyncManager = remember {
        try { Injekt.get<TtuSyncManager>() } catch (_: Exception) { null }
    }
    val syncStatus = remember {
        try { Injekt.get<SyncStatus>() } catch (_: Exception) { null }
    }
    var ttuSyncJob by remember { mutableStateOf<Job?>(null) }

    fun syncAllTtuBooks() {
        if (ttuSyncJob?.isActive == true) {
            context.toast(SYMR.strings.sync_in_progress)
            return
        }
        val sync = ttuSyncManager
        if (sync == null) {
            context.toast("TTSU sync is not available")
            Log.w("TtuSyncUi", "Sync all requested, but TtuSyncManager is not available")
            return
        }
        if (!sync.isEnabled) {
            context.toast("TTSU sync is disabled or Drive is not connected")
            Log.w("TtuSyncUi", "Sync all requested, but sync is disabled or Drive is not connected")
            return
        }
        ttuSyncJob = coroutineScope.launch(Dispatchers.IO) {
            syncStatus?.start()
            syncStatus?.updateProgress(0f)
            try {
                Log.d("TtuSyncUi", "Manual TTSU sync all started")
                val books = com.canopus.chimareader.data.BookStorage.loadAllBooks(context)
                var imported = 0
                var exported = 0
                var synced = 0
                var skipped = 0
                var failed = 0
                books.forEachIndexed { index, book ->
                    when (val result = sync.syncBook(book)) {
                        is com.canopus.chimareader.ttusync.SyncResult.Imported -> imported++
                        is com.canopus.chimareader.ttusync.SyncResult.Exported -> exported++
                        is com.canopus.chimareader.ttusync.SyncResult.Synced -> synced++
                        is com.canopus.chimareader.ttusync.SyncResult.Skipped -> skipped++
                        is com.canopus.chimareader.ttusync.SyncResult.Failed -> {
                            failed++
                            Log.w("TtuSyncUi", "TTSU sync failed for '${result.title}': ${result.error}")
                        }
                    }
                    if (books.isNotEmpty()) {
                        syncStatus?.updateProgress((index + 1).toFloat() / books.size.toFloat())
                    }
                }
                Log.d(
                    "TtuSyncUi",
                    "Manual TTSU sync all finished: total=${books.size}, imported=$imported, exported=$exported, synced=$synced, skipped=$skipped, failed=$failed",
                )
                withContext(Dispatchers.Main) {
                    context.toast("TTSU sync: $imported imported, $exported exported, $synced synced, $skipped skipped, $failed failed")
                }
            } finally {
                syncStatus?.stop()
            }
        }
    }

    LaunchedEffect(Unit) {
        requestSortEvent?.receiveAsFlow()?.collectLatest {
            screenModel.showSortDialog()
        }
    }

    val epubPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents(),
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            screenModel.importBooks(uris)
        }
    }

    // Mirror manga library: back exits selection or clears search
    BackHandler(enabled = state.selectionMode || state.searchQuery != null) {
        when {
            state.selectionMode -> screenModel.clearSelection()
            state.searchQuery != null -> screenModel.search(null)
        }
    }

    val showTabs by screenModel.showTabs().collectAsState()
    val showNumberOfItems by screenModel.showNumberOfItems().collectAsState()

    Scaffold(
        topBar = { scrollBehavior ->
            val title = state.getToolbarTitle(
                defaultTitle = stringResource(MR.strings.label_novels),
                defaultCategoryTitle = stringResource(MR.strings.label_default),
                showTabs = showTabs,
                showCount = showNumberOfItems,
            )
            LibraryToolbar(
                hasActiveFilters = state.hasActiveFilters,
                selectedCount = state.selection.size,
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
                title = title,
                onClickUnselectAll = screenModel::clearSelection,
                onClickSelectAll = screenModel::selectAll,
                onClickInvertSelection = screenModel::invertSelection,
                onClickFilter = screenModel::showSortDialog,
                    onClickRefresh = {
                        if (!SyncDataJob.isRunning(context)) {
                            SyncDataJob.startNow(context, manual = true)
                        } else {
                            context.toast(SYMR.strings.sync_in_progress)
                        }
                    },
                    onClickSyncNow = null,
                    onClickSyncTtu = { syncAllTtuBooks() }.takeIf { ttuSyncManager?.isEnabled == true },
                onClickGlobalUpdate = null,
                onClickOpenRandomManga = {
                    val randomBook = screenModel.getRandomBookForCurrentCategory()
                    if (randomBook != null) {
                        val bookDir = com.canopus.chimareader.data.BookStorage.getBookDirectory(context, randomBook.id)
                        com.canopus.chimareader.ui.reader.NovelReaderActivity.launch(context, bookDir)
                    }
                },
                onClickSyncExh = null,
                isSyncEnabled = false,
                searchQuery = state.searchQuery,
                onSearchQueryChange = screenModel::search,
                scrollBehavior = scrollBehavior,
                onInvalidateDownloadCache = null,
                onClickEditCategories = { navigator.push(CategoryScreen(CategoryScreen.Tab.NOVELS)) },
                editCategoriesTitle = stringResource(MR.strings.action_edit_novel_categories),
                updateCategoryTitle = stringResource(SYMR.strings.label_sync),
                onClickNovelDefaultCategory = screenModel::showNovelDefaultCategoryDialog,
                novelDefaultCategoryTitle = stringResource(MR.strings.default_category) + ": " + screenModel.getDefaultCategoryDisplayName(),
            )
        },
        bottomBar = {
            val singleBookId = if (state.selection.size == 1) state.selection.first() else null
            val ttuSyncSettings by remember(ttuSyncManager) {
                ttuSyncManager?.settingsFlow ?: kotlinx.coroutines.flow.flowOf(null)
            }.collectAsState(initial = ttuSyncManager?.loadSettings())
            val isAutoSyncMode = ttuSyncSettings?.mode == com.canopus.chimareader.ttusync.SyncMode.Auto

            NovelLibraryBottomActionMenu(
                visible = state.selectionMode,
                onEditClicked = screenModel::showEditDialog,
                canEdit = state.selection.size == 1,
                onChangeCategoryClicked = screenModel::showChangeCategoryDialog,
                onDeleteClicked = screenModel::showDeleteConfirmDialog,
                onResetClicked = screenModel::resetStatsForSelected,
                isAutoSyncMode = isAutoSyncMode,
                onSyncImport = if (ttuSyncManager?.isEnabled == true && singleBookId != null) {
                    {
                        coroutineScope.launch(Dispatchers.IO) {
                            syncStatus?.start()
                            syncStatus?.updateProgress(0f)
                            try {
                                val bookDir = com.canopus.chimareader.data.BookStorage.getBookDirectory(context, singleBookId)
                                val metadata = com.canopus.chimareader.data.BookStorage.loadMetadata(bookDir)
                                if (metadata != null) {
                                    val result = ttuSyncManager.syncBook(metadata, com.canopus.chimareader.ttusync.SyncDirection.IMPORT)
                                    withContext(Dispatchers.Main) {
                                        when (result) {
                                            is com.canopus.chimareader.ttusync.SyncResult.Imported -> {
                                                context.toast("Imported successfully: ${result.title}")
                                                screenModel.clearSelection()
                                            }
                                            is com.canopus.chimareader.ttusync.SyncResult.Synced -> {
                                                context.toast("Already synced: ${result.title}")
                                                screenModel.clearSelection()
                                            }
                                            is com.canopus.chimareader.ttusync.SyncResult.Failed -> {
                                                context.toast("Import failed: ${result.error}")
                                            }
                                            else -> {}
                                        }
                                    }
                                }
                            } finally {
                                syncStatus?.updateProgress(1f)
                                syncStatus?.stop()
                            }
                        }
                    }
                } else {
                    null
                },
                onSyncExport = if (ttuSyncManager?.isEnabled == true && singleBookId != null) {
                    {
                        coroutineScope.launch(Dispatchers.IO) {
                            syncStatus?.start()
                            syncStatus?.updateProgress(0f)
                            try {
                                val bookDir = com.canopus.chimareader.data.BookStorage.getBookDirectory(context, singleBookId)
                                val metadata = com.canopus.chimareader.data.BookStorage.loadMetadata(bookDir)
                                if (metadata != null) {
                                    val result = ttuSyncManager.syncBook(metadata, com.canopus.chimareader.ttusync.SyncDirection.EXPORT)
                                    withContext(Dispatchers.Main) {
                                        when (result) {
                                            is com.canopus.chimareader.ttusync.SyncResult.Exported -> {
                                                context.toast("Exported successfully: ${result.title}")
                                                screenModel.clearSelection()
                                            }
                                            is com.canopus.chimareader.ttusync.SyncResult.Synced -> {
                                                context.toast("Already synced: ${result.title}")
                                                screenModel.clearSelection()
                                            }
                                            is com.canopus.chimareader.ttusync.SyncResult.Failed -> {
                                                context.toast("Export failed: ${result.error}")
                                            }
                                            else -> {}
                                        }
                                    }
                                }
                            } finally {
                                syncStatus?.updateProgress(1f)
                                syncStatus?.stop()
                            }
                        }
                    }
                } else {
                    null
                },
                onSyncAuto = if (ttuSyncManager?.isEnabled == true && singleBookId != null) {
                    {
                        coroutineScope.launch(Dispatchers.IO) {
                            syncStatus?.start()
                            syncStatus?.updateProgress(0f)
                            try {
                                val bookDir = com.canopus.chimareader.data.BookStorage.getBookDirectory(context, singleBookId)
                                val metadata = com.canopus.chimareader.data.BookStorage.loadMetadata(bookDir)
                                if (metadata != null) {
                                    val result = ttuSyncManager.syncBook(metadata, com.canopus.chimareader.ttusync.SyncDirection.AUTO)
                                    withContext(Dispatchers.Main) {
                                        when (result) {
                                            is com.canopus.chimareader.ttusync.SyncResult.Imported -> {
                                                context.toast("Imported successfully: ${result.title}")
                                                screenModel.clearSelection()
                                            }
                                            is com.canopus.chimareader.ttusync.SyncResult.Exported -> {
                                                context.toast("Exported successfully: ${result.title}")
                                                screenModel.clearSelection()
                                            }
                                            is com.canopus.chimareader.ttusync.SyncResult.Synced -> {
                                                context.toast("Sync completed: ${result.title}")
                                                screenModel.clearSelection()
                                            }
                                            is com.canopus.chimareader.ttusync.SyncResult.Failed -> {
                                                context.toast("Sync failed: ${result.error}")
                                            }
                                            else -> {}
                                        }
                                    }
                                }
                            } finally {
                                syncStatus?.updateProgress(1f)
                                syncStatus?.stop()
                            }
                        }
                    }
                } else {
                    null
                },
            )
        },
        floatingActionButton = {
            androidx.compose.animation.AnimatedVisibility(
                visible = !state.selectionMode,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
                FloatingActionButton(
                    onClick = { epubPicker.launch("application/epub+zip") },
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(MR.strings.action_add),
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        if (state.isLoading) {
            LoadingScreen(Modifier.padding(contentPadding))
        } else if (state.isLibraryEmpty) {
            EmptyScreen(
                stringRes = MR.strings.information_empty_library,
                modifier = Modifier
                    .padding(contentPadding)
                    .libraryModeBoundarySwipe(
                        enabled = state.selection.isEmpty(),
                        onBoundarySwipe = onBoundarySwipe,
                    ),
            )
        } else {
            NovelLibraryContent(
                state = state,
                screenModel = screenModel,
                contentPadding = contentPadding,
                context = context,
                snackbarHostState = snackbarHostState,
                onCategoryChange = screenModel::updateActiveCategoryIndex,
                entryTarget = entryTarget,
                onEntryTargetConsumed = onEntryTargetConsumed,
                onBoundarySwipe = onBoundarySwipe,
                onClickBook = { item ->
                    when (item) {
                        is NovelLibraryItem.LocalBook -> {
                            val meta = item.metadata
                            if (meta.isGhost) {
                                epubPicker.launch("application/epub+zip")
                            } else {
                                val bookDir = com.canopus.chimareader.data.BookStorage.getBookDirectory(context, meta.id)
                                com.canopus.chimareader.ui.reader.NovelReaderActivity.launch(context, bookDir)
                            }
                        }
                        is NovelLibraryItem.SourceNovel -> {
                            context.toast("Opening source novels not yet implemented")
                        }
                    }
                },
            )
        }
    }

    // Hide bottom nav while in selection mode — mirrors manga library
    LaunchedEffect(state.selectionMode, state.dialog) {
        HomeScreen.showBottomNav(!state.selectionMode)
    }

    // Show snackbar when batch import finishes
    LaunchedEffect(state.importResult) {
        val result = state.importResult ?: return@LaunchedEffect
        val (imported, errors) = result
        val msg = if (errors == 0) {
            context.stringResource(MR.strings.imported_n_books, imported)
        } else {
            context.stringResource(MR.strings.imported_n_books_with_errors, imported, errors)
        }
        snackbarHostState.showSnackbar(msg)
        screenModel.clearImportResult()
    }

    // Reload whenever the screen is (re-)entered — catches books imported via + button
    LaunchedEffect(Unit) {
        screenModel.loadLibrary()
    }

    val onDismissRequest = screenModel::closeDialog
    when (val dialog = state.dialog) {
        is NovelLibraryScreenModel.Dialog.ChangeCategory -> {
            eu.kanade.presentation.category.components.ChangeCategoryDialog(
                initialSelection = dialog.initialSelection,
                onDismissRequest = onDismissRequest,
                onEditCategories = { navigator.push(CategoryScreen(CategoryScreen.Tab.NOVELS)) },
                onConfirm = { include, exclude ->
                    screenModel.clearSelection()
                    screenModel.setBooksCategories(dialog.books, include, exclude)
                },
            )
        }

        is NovelLibraryScreenModel.Dialog.DeleteConfirm -> {
            AlertDialog(
                onDismissRequest = onDismissRequest,
                title = { Text(stringResource(MR.strings.action_delete)) },
                text = { Text(stringResource(MR.strings.action_delete)) },
                confirmButton = {
                    TextButton(onClick = {
                        screenModel.deleteSelected()
                        screenModel.closeDialog()
                    }) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }

        is NovelLibraryScreenModel.Dialog.EditBook -> {
            val book = dialog.book
            val dictPrefs = remember { Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>() }
            val profiles = remember { dictPrefs.profileStore.getProfiles() }
            val novelOverrideKey = chimahon.dictionary.DictionaryProfileResolver.novelOverrideKey(book.id)
            val initialOverride = remember { dictPrefs.rawProfileOverride(novelOverrideKey).get() }

            var editTitle by remember { mutableStateOf(book.title ?: "") }
            var editAuthor by remember { mutableStateOf(book.author ?: "") }
            var editLang by remember { mutableStateOf(book.lang ?: "") }
            var selectedOverride by remember { mutableStateOf(initialOverride) }

            val enabledLanguages = remember {
                listOf(
                    "ja", "ko", "ar", "zh", "en", "de", "fr", "ru", "es", "it"
                ).sortedWith { a, b ->
                    eu.kanade.tachiyomi.util.system.LocaleHelper.getDisplayName(a)
                        .compareTo(eu.kanade.tachiyomi.util.system.LocaleHelper.getDisplayName(b))
                }
            }

            val resolvedAutoProfile = remember(editLang) {
                dictPrefs.profileResolver.resolve(
                    sourceId = 0L,
                    sourceLang = editLang,
                    novelId = ""
                )
            }
            val autoLabel = "Auto (${resolvedAutoProfile.name})"

            AlertDialog(
                onDismissRequest = onDismissRequest,
                title = { Text(stringResource(MR.strings.action_edit)) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        androidx.compose.material3.TextField(
                            value = editTitle,
                            onValueChange = { editTitle = it },
                            label = { Text("Title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        androidx.compose.material3.TextField(
                            value = editAuthor,
                            onValueChange = { editAuthor = it },
                            label = { Text("Author") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        var langExpanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = langExpanded,
                            onExpandedChange = { langExpanded = it }
                        ) {
                            val langDisplayName = if (editLang.isEmpty()) "None" else eu.kanade.tachiyomi.util.system.LocaleHelper.getDisplayName(editLang)
                            androidx.compose.material3.TextField(
                                value = langDisplayName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Language") },
                                trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = langExpanded) },
                                modifier = Modifier.menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = langExpanded,
                                onDismissRequest = { langExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("None") },
                                    onClick = {
                                        editLang = ""
                                        langExpanded = false
                                    }
                                )
                                enabledLanguages.forEach { lang ->
                                    DropdownMenuItem(
                                        text = { Text(eu.kanade.tachiyomi.util.system.LocaleHelper.getDisplayName(lang)) },
                                        onClick = {
                                            editLang = lang
                                            langExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        var profileExpanded by remember { mutableStateOf(false) }
                        val selectedName = if (selectedOverride.isEmpty()) {
                            autoLabel
                        } else {
                            profiles.firstOrNull { it.id == selectedOverride }?.name ?: autoLabel
                        }

                        ExposedDropdownMenuBox(
                            expanded = profileExpanded,
                            onExpandedChange = { profileExpanded = it }
                        ) {
                            androidx.compose.material3.TextField(
                                value = selectedName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Anki Profile Override") },
                                trailingIcon = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = profileExpanded) },
                                modifier = Modifier.menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = profileExpanded,
                                onDismissRequest = { profileExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(autoLabel) },
                                    onClick = {
                                        selectedOverride = ""
                                        profileExpanded = false
                                    }
                                )
                                profiles.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p.name) },
                                        onClick = {
                                            selectedOverride = p.id
                                            profileExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val updatedBook = book.copy(
                                title = editTitle.ifBlank { null },
                                author = editAuthor.ifBlank { null },
                                lang = editLang.ifBlank { null }
                            )
                            screenModel.updateBookMetadata(updatedBook, selectedOverride)
                        }
                    ) {
                        Text(stringResource(MR.strings.action_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(MR.strings.action_cancel))
                    }
                }
            )
        }

        is NovelLibraryScreenModel.Dialog.SortFilter, NovelLibraryScreenModel.Dialog.Settings -> {
            TabbedDialog(
                onDismissRequest = onDismissRequest,
                tabTitles = persistentListOf(
                    stringResource(MR.strings.action_sort),
                    stringResource(MR.strings.action_display),
                ),
            ) { page ->
                Column(
                    modifier = Modifier
                        .padding(vertical = TabbedDialogPaddings.Vertical)
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (page) {
                        0 -> { // Sort Tab
                            val options = listOf(
                                MR.strings.action_sort_alpha to NovelLibraryScreenModel.SortMode.Alphabetical,
                                MR.strings.action_sort_date_added to NovelLibraryScreenModel.SortMode.DateAdded,
                                MR.strings.action_sort_last_read to NovelLibraryScreenModel.SortMode.LastRead,
                            )

                            options.map { (titleRes, mode) ->
                                SortItem(
                                    label = stringResource(titleRes),
                                    sortDescending = if (state.sortMode == mode) state.sortDescending else null,
                                    onClick = {
                                        val isTogglingDirection = state.sortMode == mode
                                        val newDescending = if (isTogglingDirection) !state.sortDescending else state.sortDescending
                                        screenModel.setSort(mode, newDescending)
                                    },
                                )
                            }
                        }

                        1 -> { // Display Tab
                            val displayMode by screenModel.getDisplayMode().collectAsState()

                            SettingsChipRow(MR.strings.action_display_mode) {
                                listOf(
                                    MR.strings.action_display_list to LibraryDisplayMode.List,
                                    MR.strings.action_display_cover_only_grid to LibraryDisplayMode.CoverOnlyGrid,
                                    MR.strings.action_display_grid to LibraryDisplayMode.CompactGrid,
                                    MR.strings.action_display_comfortable_grid to LibraryDisplayMode.ComfortableGrid,
                                ).map { (titleRes, mode) ->
                                    FilterChip(
                                        selected = displayMode == mode,
                                        onClick = { screenModel.setDisplayMode(mode) },
                                        label = { Text(stringResource(titleRes)) },
                                    )
                                }
                            }

                            if (displayMode != LibraryDisplayMode.List) {
                                val configuration = LocalConfiguration.current
                                val isLandscape = configuration.orientation ==
                                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                                val columnPreference = screenModel.getColumnsPreferenceForOrientation(isLandscape)
                                val columns by columnPreference.collectAsState()

                                SliderItem(
                                    value = columns,
                                    valueRange = 0..10,
                                    label = stringResource(MR.strings.pref_library_columns),
                                    valueString = if (columns > 0) columns.toString() else stringResource(MR.strings.label_auto),
                                    onChange = columnPreference::set,
                                )
                            }

                            HeadingItem(MR.strings.tabs_header)
                            CheckboxItem(
                                label = stringResource(MR.strings.action_display_show_tabs),
                                pref = screenModel.showTabs(),
                            )
                            CheckboxItem(
                                label = stringResource(KMR.strings.action_show_hidden_categories),
                                pref = screenModel.showHiddenCategories(),
                            )
                            CheckboxItem(
                                label = stringResource(MR.strings.action_display_show_number_of_items),
                                pref = screenModel.showNumberOfItems(),
                            )
                        }
                    }
                }
            }
        }

        is NovelLibraryScreenModel.Dialog.SetDefaultCategory -> {
            val currentDefault = remember { screenModel.getNovelDefaultCategory().get() }
            AlertDialog(
                onDismissRequest = onDismissRequest,
                title = { Text(stringResource(MR.strings.default_category)) },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { screenModel.setNovelDefaultCategory("") }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = currentDefault.isEmpty(),
                                onClick = { screenModel.setNovelDefaultCategory("") },
                            )
                            Text(
                                text = stringResource(MR.strings.default_category_summary),
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                        state.categories.forEach { cat ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { screenModel.setNovelDefaultCategory(cat.id) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = currentDefault == cat.id,
                                    onClick = { screenModel.setNovelDefaultCategory(cat.id) },
                                )
                                Text(
                                    text = if (cat.isSystemCategory) stringResource(MR.strings.label_default) else cat.name,
                                    modifier = Modifier.padding(start = 12.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismissRequest) {
                        Text(stringResource(MR.strings.action_close))
                    }
                },
            )
        }

        null -> {}
    }
}

// ---------------------------------------------------------------------------

@Composable
fun NovelLibraryContent(
    state: NovelLibraryScreenModel.State,
    screenModel: NovelLibraryScreenModel,
    contentPadding: PaddingValues,
    context: android.content.Context,
    snackbarHostState: SnackbarHostState,
    onCategoryChange: (Int) -> Unit,
    entryTarget: LibraryPagerBoundary? = null,
    onEntryTargetConsumed: () -> Unit = {},
    onBoundarySwipe: (LibraryPagerBoundary) -> Unit = {},
    onClickBook: (NovelLibraryItem) -> Unit,
) {
    val pagerState = rememberPagerState(state.coercedActiveCategoryIndex) { state.displayedCategories.size }
    val scope = rememberCoroutineScope()
    var isRefreshing by remember(pagerState.currentPage) { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val columns by screenModel.getColumnsPreferenceForOrientation(isLandscape).collectAsState()
    val displayMode by screenModel.getDisplayMode().collectAsState()

    val showTabs by screenModel.showTabs().collectAsState()
    val showNumberOfItems by screenModel.showNumberOfItems().collectAsState()

    LaunchedEffect(entryTarget, state.displayedCategories) {
        if (entryTarget != null && state.displayedCategories.isNotEmpty()) {
            val targetPage = when (entryTarget) {
                LibraryPagerBoundary.Start -> 0
                LibraryPagerBoundary.End -> state.displayedCategories.lastIndex
            }
            pagerState.scrollToPage(targetPage)
            onCategoryChange(targetPage)
            onEntryTargetConsumed()
        }
    }

    Column(modifier = Modifier.padding(top = contentPadding.calculateTopPadding())) {
        if (showTabs && state.displayedCategories.isNotEmpty()) {
            LibraryTabs(
                categories = state.displayedCategories.map {
                    val name = if (it.isSystemCategory) {
                        stringResource(MR.strings.label_default)
                    } else {
                        it.name
                    }
                    tachiyomi.domain.category.model.Category(
                        id = if (it.isSystemCategory) 0L else it.id.hashCode().toLong(),
                        name = name,
                        order = it.order.toLong(),
                        flags = it.flags,
                        hidden = it.hidden,
                    )
                },
                pagerState = pagerState,
                getItemCountForCategory = { cat ->
                    if (showNumberOfItems) {
                        state.displayedCategories.find {
                            (if (it.isSystemCategory) 0L else it.id.hashCode().toLong()) == cat.id
                        }?.let { state.getItemCountForCategory(it) }
                    } else {
                        null
                    }
                },
                onTabItemClick = { index ->
                    scope.launch { pagerState.animateScrollToPage(index) }
                },
            )
        }

        PullRefresh(
            refreshing = isRefreshing,
            enabled = state.selection.isEmpty(),
            onRefresh = {
                SyncDataJob.startNow(context, manual = true)
                scope.launch {
                    isRefreshing = true
                    delay(1.seconds)
                    isRefreshing = false
                }
            },
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .libraryPagerBoundarySwipe(
                        state = pagerState,
                        enabled = state.selection.isEmpty(),
                        onBoundarySwipe = onBoundarySwipe,
                    ),
                verticalAlignment = Alignment.Top,
            ) { page ->
                val category = state.displayedCategories[page]
                val items = state.getItemsForCategory(category)

                if (items.isEmpty() && !state.searchQuery.isNullOrBlank()) {
                    EmptyScreen(
                        stringRes = MR.strings.no_results_found,
                        modifier = Modifier
                            .padding(bottom = contentPadding.calculateBottomPadding())
                            .padding(8.dp),
                    )
                } else if (items.isEmpty()) {
                    EmptyScreen(
                        stringRes = MR.strings.information_no_manga_category,
                        modifier = Modifier
                            .padding(bottom = contentPadding.calculateBottomPadding())
                            .padding(8.dp),
                    )
                } else if (displayMode == LibraryDisplayMode.List) {
                    NovelLibraryList(
                        items = items,
                        state = state,
                        screenModel = screenModel,
                        onClickBook = onClickBook,
                        contentPadding = contentPadding,
                    )
                } else {
                    NovelLibraryGrid(
                        items = items,
                        displayMode = displayMode,
                        columns = columns,
                        state = state,
                        screenModel = screenModel,
                        onClickBook = onClickBook,
                        contentPadding = contentPadding,
                    )
                }
            }
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        onCategoryChange(pagerState.currentPage)
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun NovelLibraryList(
    items: List<NovelLibraryItem>,
    state: NovelLibraryScreenModel.State,
    screenModel: NovelLibraryScreenModel,
    onClickBook: (NovelLibraryItem) -> Unit,
    contentPadding: PaddingValues,
) {
    FastScrollLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()) + PaddingValues(vertical = 8.dp),
    ) {
        items(items, key = { it.id }) { book ->
            val isSelected = state.selection.contains(book.id)
            val coverData = tachiyomi.domain.manga.model.MangaCover(
                mangaId = book.id.hashCode().toLong(),
                sourceId = -1L,
                isMangaFavorite = true,
                ogUrl = book.coverUrl,
                lastModified = 0L,
            )
            val onLongClick: () -> Unit = { screenModel.toggleSelection(book.id) }
            val onClick: () -> Unit = {
                if (state.selectionMode) {
                    screenModel.toggleSelection(book.id)
                } else {
                    onClickBook(book)
                }
            }

            val bookLang = when (book) {
                is NovelLibraryItem.LocalBook -> book.metadata.lang
                is NovelLibraryItem.SourceNovel -> null
            }

            eu.kanade.presentation.library.components.MangaListItem(
                isSelected = isSelected,
                title = book.title,
                coverData = coverData,
                badge = {
                    if (!bookLang.isNullOrBlank()) {
                        eu.kanade.presentation.library.components.LanguageBadge(
                            isLocal = true,
                            sourceLanguage = bookLang,
                        )
                    }
                    if (book is NovelLibraryItem.LocalBook && book.metadata.isGhost) {
                        androidx.compose.material3.Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer,
                        ) {
                            androidx.compose.material3.Text(
                                text = "MISSING",
                                modifier = androidx.compose.ui.Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                },
                onLongClick = onLongClick,
                onClick = onClick,
            )
        }
    }
}

@Composable
private fun NovelLibraryGrid(
    items: List<NovelLibraryItem>,
    displayMode: LibraryDisplayMode,
    columns: Int,
    state: NovelLibraryScreenModel.State,
    screenModel: NovelLibraryScreenModel,
    onClickBook: (NovelLibraryItem) -> Unit,
    contentPadding: PaddingValues,
) {
    FastScrollLazyVerticalGrid(
        modifier = Modifier.fillMaxSize(),
        columns = if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()) + PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
    ) {
        items(items, key = { it.id }) { book ->
            val isSelected = state.selection.contains(book.id)
            val coverData = tachiyomi.domain.manga.model.MangaCover(
                mangaId = book.id.hashCode().toLong(),
                sourceId = -1L,
                isMangaFavorite = true,
                ogUrl = book.coverUrl,
                lastModified = 0L,
            )
            val onLongClick: () -> Unit = { screenModel.toggleSelection(book.id) }
            val onClick: () -> Unit = {
                if (state.selectionMode) {
                    screenModel.toggleSelection(book.id)
                } else {
                    onClickBook(book)
                }
            }

            val bookLang = when (book) {
                is NovelLibraryItem.LocalBook -> book.metadata.lang
                is NovelLibraryItem.SourceNovel -> null
            }
            val bookTitle = if (displayMode == LibraryDisplayMode.CoverOnlyGrid) null else book.title

            val ghostBadge: @Composable (androidx.compose.foundation.layout.RowScope.() -> Unit)? =
                if (book is NovelLibraryItem.LocalBook && book.metadata.isGhost) {
                    {
                        androidx.compose.material3.Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.errorContainer,
                        ) {
                            androidx.compose.material3.Text(
                                text = "MISSING",
                                modifier = androidx.compose.ui.Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                } else {
                    null
                }

            if (displayMode == LibraryDisplayMode.ComfortableGrid) {
                eu.kanade.presentation.library.components.MangaComfortableGridItem(
                    isSelected = isSelected,
                    title = bookTitle ?: "",
                    coverData = coverData,
                    coverBadgeStart = {
                        if (!bookLang.isNullOrBlank()) {
                            eu.kanade.presentation.library.components.LanguageBadge(
                                isLocal = true,
                                sourceLanguage = bookLang,
                            )
                        }
                    },
                    coverBadgeEnd = ghostBadge,
                    onLongClick = onLongClick,
                    onClick = onClick,
                    usePanoramaCover = false,
                )
            } else {
                eu.kanade.presentation.library.components.MangaCompactGridItem(
                    isSelected = isSelected,
                    title = bookTitle,
                    coverData = coverData,
                    coverBadgeStart = {
                        if (!bookLang.isNullOrBlank()) {
                            eu.kanade.presentation.library.components.LanguageBadge(
                                isLocal = true,
                                sourceLanguage = bookLang,
                            )
                        }
                    },
                    coverBadgeEnd = ghostBadge,
                    onLongClick = onLongClick,
                    onClick = onClick,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
fun NovelLibraryBottomActionMenu(
    visible: Boolean,
    onEditClicked: () -> Unit,
    canEdit: Boolean,
    onChangeCategoryClicked: () -> Unit,
    onDeleteClicked: () -> Unit,
    onResetClicked: () -> Unit,
    onSyncImport: (() -> Unit)? = null,
    onSyncExport: (() -> Unit)? = null,
    onSyncAuto: (() -> Unit)? = null,
    isAutoSyncMode: Boolean = true,
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(expandFrom = Alignment.Bottom),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom),
    ) {
        val scope = rememberCoroutineScope()
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large.copy(
                bottomEnd = ZeroCornerSize,
                bottomStart = ZeroCornerSize,
            ),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            val haptic = LocalHapticFeedback.current
            val confirm = remember { mutableStateListOf(false, false, false, false, false) }
            var resetJob by remember { mutableStateOf<Job?>(null) }
            val onLongClickItem: (Int) -> Unit = { toConfirmIndex ->
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                confirm.indices.forEach { i -> confirm[i] = i == toConfirmIndex }
                resetJob?.cancel()
                resetJob = scope.launch {
                    delay(1.seconds)
                    if (isActive) confirm[toConfirmIndex] = false
                }
            }

            Row(
                modifier = Modifier
                    .windowInsetsPadding(
                        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                    )
                    .padding(horizontal = 8.dp, vertical = 12.dp),
            ) {
                BottomMenuButton(
                    title = stringResource(MR.strings.action_move_category),
                    icon = Icons.AutoMirrored.Outlined.Label,
                    toConfirm = confirm[0],
                    onLongClick = { onLongClickItem(0) },
                    onClick = onChangeCategoryClicked,
                )
                BottomMenuButton(
                    title = stringResource(MR.strings.action_mark_as_unread),
                    icon = Icons.Outlined.RestartAlt,
                    toConfirm = confirm[1],
                    onLongClick = { onLongClickItem(1) },
                    onClick = onResetClicked,
                )
                if (canEdit) {
                    BottomMenuButton(
                        title = stringResource(MR.strings.action_edit),
                        icon = Icons.Outlined.Edit,
                        toConfirm = confirm[2],
                        onLongClick = { onLongClickItem(2) },
                        onClick = onEditClicked,
                    )
                }
                BottomMenuButton(
                    title = stringResource(MR.strings.action_delete),
                    icon = Icons.Outlined.Delete,
                    toConfirm = confirm[3],
                    onLongClick = { onLongClickItem(3) },
                    onClick = onDeleteClicked,
                )
                if (onSyncImport != null && onSyncExport != null && onSyncAuto != null) {
                    var expanded by remember { mutableStateOf(false) }
                    BottomMenuButton(
                        title = stringResource(SYMR.strings.label_sync),
                        icon = Icons.Outlined.Sync,
                        toConfirm = confirm[4],
                        onLongClick = { onLongClickItem(4) },
                        onClick = {
                            if (isAutoSyncMode) {
                                onSyncAuto()
                            } else {
                                expanded = true
                            }
                        },
                    ) {
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Import from Drive") },
                                onClick = {
                                    expanded = false
                                    onSyncImport()
                                },
                                leadingIcon = { Icon(Icons.Outlined.Download, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Export to Drive") },
                                onClick = {
                                    expanded = false
                                    onSyncExport()
                                },
                                leadingIcon = { Icon(Icons.Outlined.Upload, null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Auto") },
                                onClick = {
                                    expanded = false
                                    onSyncAuto()
                                },
                                leadingIcon = { Icon(Icons.Outlined.Sync, null) },
                            )
                        }
                    }
                }
            }
        }
    }
}
