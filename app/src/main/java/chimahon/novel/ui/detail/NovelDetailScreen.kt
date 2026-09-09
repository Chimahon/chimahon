package chimahon.novel.ui.detail

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import tachiyomi.presentation.core.components.material.padding
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import chimahon.novel.download.NovelDownloadManager
import chimahon.novel.ui.browse.BrowseNovelSourceScreen
import chimahon.novel.ui.browse.BrowseNovelSourceScreenModel
import chimahon.novel.data.BookStorage
import chimahon.novel.ui.reader.NovelReaderActivity
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.entries.components.EntryBottomActionMenu
import eu.kanade.presentation.entries.components.EntryToolbar
import eu.kanade.presentation.entries.components.ItemHeader
import eu.kanade.presentation.entries.novel.components.ExpandableNovelDescription
import eu.kanade.presentation.entries.novel.components.NovelActionRow
import eu.kanade.presentation.entries.novel.components.NovelCoverDialog
import eu.kanade.presentation.entries.novel.components.NovelFetchIntervalDialog
import eu.kanade.presentation.entries.novel.components.NovelInfoBox
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.MangaChapterListItem
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.util.shouldExpandFAB
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.sourcenovel.HttpNovelSource
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.launch
import tachiyomi.domain.episode.service.missingEpisodesCount
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.source.novel.model.StubNovelSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt

data class NovelDetailScreen(
    private val novel: SNNovel,
    private val sourceId: Long,
) : Screen() {

    companion object {
        fun fromSourceId(novel: SNNovel, sourceId: Long): NovelDetailScreen? {
            return NovelDetailScreen(novel, sourceId)
        }

        fun fromDbNovel(dbNovel: Novel): NovelDetailScreen {
            return NovelDetailScreen(
                SNNovel(
                    url = dbNovel.url,
                    title = dbNovel.title,
                    author = dbNovel.author,
                    artist = dbNovel.artist,
                    description = dbNovel.description,
                    genre = dbNovel.genre,
                    status = dbNovel.status.toInt(),
                    thumbnail_url = dbNovel.thumbnailUrl,
                    initialized = dbNovel.initialized,
                    id = dbNovel.id,
                    source = dbNovel.source,
                    favorite = dbNovel.favorite,
                    lastUpdate = dbNovel.lastUpdate,
                ),
                dbNovel.source,
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val sourceManager = remember { Injekt.get<chimahon.novel.manager.NovelSourceManager>() }
        val sources by sourceManager.catalogueSources.collectAsState(initial = emptyList())
        val source = remember(sources, sourceId) { sources.find { it.id == sourceId } }
        if (source == null) {
            LoadingScreen()
            return
        }
        // Local EPUBs are already on disk: no downloads, no fetch interval.
        val isLocalSource = source.id == Novel.LOCAL_SOURCE_ID
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val screenModel = rememberScreenModel { NovelDetailScreenModel(novel, sourceId) }
        val state by screenModel.state.collectAsState()
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    scope.launch { screenModel.resume() }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val chapterListState = rememberLazyListState()
        val chapters = state.chapters
        val isAnySelected = state.selectionMode
        val hasUnread = remember(chapters) { chapters.fastAny { !it.isRead } }
        val selectedChapterCount = remember(chapters, state.selectedChapters) {
            chapters.count { it.id in state.selectedChapters }
        }
        val libraryPreferences = remember { Injekt.get<LibraryPreferences>() }
        val swipeStartAction by remember { libraryPreferences.swipeToStartAction().changes() }
            .collectAsState(initial = libraryPreferences.swipeToStartAction().get())
        val swipeEndAction by remember { libraryPreferences.swipeToEndAction().changes() }
            .collectAsState(initial = libraryPreferences.swipeToEndAction().get())
        val downloadManager = remember { Injekt.get<NovelDownloadManager>() }
        val downloadQueue by downloadManager.queueState.collectAsState()
        val visibleChapters = remember(chapters, state.unreadOnly, state.bookmarkedOnly) {
            chapters.filter { (!state.unreadOnly || !it.isRead) && (!state.bookmarkedOnly || it.isBookmarked) }
        }
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        var layoutSize by remember { mutableStateOf(IntSize.Zero) }
        var fabSize by remember { mutableStateOf(IntSize.Zero) }
        var positionOnScreen by remember { mutableStateOf(Offset.Zero) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        val readButtonPosition = remember { uiPreferences.readButtonPosition() }
        val fabPosition by remember { readButtonPosition.changes() }
            .collectAsState(initial = readButtonPosition.get())
        val snackbarHostState = remember { SnackbarHostState() }
        var openingChapter by remember { mutableStateOf(false) }
        var openGeneration by remember { mutableIntStateOf(0) }
        fun openChapterWithFeedback(item: NovelChapterItem) {
            if (openingChapter) return
            val generation = openGeneration + 1
            openGeneration = generation
            openChapter(
                context, source, state.novel, item, chapters, screenModel,
                onStart = { openingChapter = true },
                onDone = { if (openGeneration == generation) openingChapter = false },
                onError = { message ->
                    if (openGeneration != generation) return@openChapter
                    openingChapter = false
                    scope.launch { snackbarHostState.showSnackbar(message) }
                },
            )
        }
        var showChapterSettings by remember { mutableStateOf(false) }
        var showIntervalDialog by remember { mutableStateOf(false) }
        if (showChapterSettings) {
            NovelChapterSettingsDialog(
                sortMode = state.sortMode,
                sortDescending = state.sortDescending,
                unreadOnly = state.unreadOnly,
                bookmarkedOnly = state.bookmarkedOnly,
                onDismiss = { showChapterSettings = false },
                onSortModeSelected = { mode ->
                    screenModel.setSortMode(mode)
                    showChapterSettings = false
                },
                onFilterChanged = { unread, bookmarked ->
                    screenModel.setChapterFilter(unread, bookmarked)
                },
            )
        }

        BackHandler(enabled = isAnySelected) {
            screenModel.clearSelection()
        }

        BackHandler(enabled = openingChapter) {
            openGeneration++
            openingChapter = false
        }

        if (showIntervalDialog) {
            state.dbNovel?.let { dbNovel ->
                NovelFetchIntervalDialog(
                    currentInterval = dbNovel.fetchInterval,
                    onDismiss = { showIntervalDialog = false },
                    onConfirm = screenModel::setFetchInterval,
                )
            }
        }

        (state.dialog as? Dialog.ChangeCategory)?.let { dialog ->
            ChangeCategoryDialog(
                initialSelection = dialog.initialSelection,
                onDismissRequest = screenModel::dismissDialog,
                onEditCategories = {
                    screenModel.dismissDialog()
                    navigator.push(CategoryScreen(CategoryScreen.Tab.NOVELS))
                },
                onConfirm = { include, _ ->
                    screenModel.setNovelCategories(include)
                },
            )
        }

        if (state.dialog is Dialog.SetDictionaryProfile) {
            val prefs = remember { Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>() }
            val profiles = remember { prefs.profileStore.getProfiles() }
            val dbNovel = state.dbNovel
            val overrideId = remember(dbNovel) {
                val key = chimahon.dictionary.DictionaryProfileResolver.novelOverrideKey(
                    dbNovel?.localFolder?.takeIf { it.isNotBlank() } ?: dbNovel?.id.toString().orEmpty(),
                )
                prefs.rawProfileOverride(key).get()
            }
            eu.kanade.presentation.manga.components.DictionaryProfileDialog(
                profiles = profiles,
                currentOverrideId = overrideId,
                resolvedAutoProfile = screenModel.resolveAutoNovelProfile(),
                onDismissRequest = screenModel::dismissDialog,
                onConfirm = screenModel::setNovelDictionaryProfile,
            )
        }

        Scaffold(
            topBar = {
                val isFirstItemVisible by remember {
                    derivedStateOf { chapterListState.firstVisibleItemIndex == 0 }
                }
                val isFirstItemScrolled by remember {
                    derivedStateOf { chapterListState.firstVisibleItemScrollOffset > 0 }
                }
                val titleAlpha by animateFloatAsState(
                    if (!isFirstItemVisible) 1f else 0f,
                    label = "Top Bar Title",
                )
                val backgroundAlpha by animateFloatAsState(
                    if (!isFirstItemVisible || isFirstItemScrolled) 1f else 0f,
                    label = "Top Bar Background",
                )
                EntryToolbar(
                    title = state.novel.title,
                    hasFilters = state.sortMode != NovelSort.SORT_SOURCE || !state.sortDescending ||
                        state.unreadOnly || state.bookmarkedOnly,
                    navigateUp = navigator::pop,
                    onClickFilter = { showChapterSettings = true },
                    onClickShare = { shareNovel(context, state.novel, source) },
                    onClickDownload = (
                        { action: eu.kanade.presentation.entries.DownloadAction ->
                            screenModel.downloadChapters(action)
                        }
                        ).takeIf { state.dbNovel != null && !isLocalSource },
                    onClickEditCategory = if (state.isFavorite) {
                        { navigator.push(CategoryScreen(CategoryScreen.Tab.NOVELS)) }
                    } else {
                        null
                    },
                    onClickRefresh = { screenModel.refresh() },
                    onClickMigrate = null,
                    onClickSettings = null,
                    changeAnimeSkipIntro = null,
                    onClickDictionaryProfile = { screenModel.showSetDictionaryProfileDialog() },
                    onClickRelatedAnime = null,
                    actionModeCounter = selectedChapterCount,
                    onCancelActionMode = screenModel::clearSelection,
                    onSelectAll = screenModel::selectAll,
                    onInvertSelection = screenModel::invertSelection,
                    titleAlphaProvider = { titleAlpha },
                    backgroundAlphaProvider = { backgroundAlpha },
                    isManga = true,
                )
            },
            bottomBar = {
                val selectedItems = remember(chapters, state.selectedChapters) {
                    chapters.filter { it.id in state.selectedChapters }
                }
                EntryBottomActionMenu(
                    visible = isAnySelected,
                    isManga = true,
                    onBookmarkClicked = {
                        screenModel.markSelectedChaptersBookmark(true)
                    }.takeIf { selectedItems.fastAny { !it.isBookmarked } },
                    onRemoveBookmarkClicked = {
                        screenModel.markSelectedChaptersBookmark(false)
                    }.takeIf { selectedItems.fastAll { it.isBookmarked } },
                    onMarkAsViewedClicked = {
                        screenModel.markSelectedChaptersRead(true)
                    }.takeIf { selectedItems.fastAny { !it.isRead } },
                    onMarkAsUnviewedClicked = {
                        screenModel.markSelectedChaptersRead(false)
                    }.takeIf { selectedItems.fastAny { it.isRead || it.lastPageRead > 0L } },
                    onMarkPreviousAsViewedClicked = {
                        screenModel.markSelectedPreviousAsRead()
                    },
                    onDownloadClicked = {
                        screenModel.downloadSelectedChapters()
                    }.takeIf { state.dbNovel != null && !isLocalSource },
                    onDeleteClicked = {
                        screenModel.deleteSelectedDownloads()
                    }.takeIf { state.dbNovel != null && !isLocalSource && selectedItems.fastAny { it.novelChapter != null } },
                )
            },
            floatingActionButton = {
                val isFABVisible = hasUnread && !isAnySelected && !openingChapter
                val isReading = remember(chapters) { chapters.fastAny { it.isRead } }
                SmallExtendedFloatingActionButton(
                    text = {
                        Text(
                            text = stringResource(if (isReading) MR.strings.action_resume else MR.strings.action_start),
                        )
                    },
                    icon = { Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null) },
                    onClick = {
                        scope.launch {
                            screenModel.getNextUnreadChapter()?.let { openChapterWithFeedback(it) }
                        }
                    },
                    expanded = chapterListState.shouldExpandFAB(),
                    modifier = Modifier.animateFloatingActionButton(
                        visible = isFABVisible,
                        alignment = Alignment.BottomEnd,
                    )
                        .offset { IntOffset(offsetX.roundToInt(), 0) }
                        .onGloballyPositioned { coordinates ->
                            fabSize = coordinates.size
                            positionOnScreen = coordinates.positionOnScreen()
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (positionOnScreen.x + fabSize.width / 2 >= layoutSize.width / 2) {
                                        readButtonPosition.set(FabPosition.End.toString())
                                    } else {
                                        readButtonPosition.set(FabPosition.Start.toString())
                                    }
                                    offsetX = 0f
                                },
                            ) { change, dragAmount ->
                                change.consume()
                                val newOffsetX = offsetX + dragAmount
                                if (!newOffsetX.isNaN()) {
                                    offsetX = newOffsetX
                                }
                            }
                        },
                    containerColor = MaterialTheme.colorScheme.primary,
                )
            },
            floatingActionButtonPosition = if (fabPosition == FabPosition.End.toString()) {
                FabPosition.End
            } else {
                FabPosition.Start
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            modifier = Modifier.onGloballyPositioned { coordinates ->
                layoutSize = coordinates.size
            },
        ) { contentPadding ->
            val topPadding = contentPadding.calculateTopPadding()
            var showCoverDialog by rememberSaveable { mutableStateOf(false) }
            if (showCoverDialog) {
                NovelCoverDialog(
                    imageUrl = state.novel.thumbnail_url,
                    title = state.novel.title,
                    onDismiss = { showCoverDialog = false },
                )
            }
            when {
                state.isLoading -> LoadingScreen(modifier = Modifier.padding(contentPadding))
                else -> {
                    PullRefresh(
                        refreshing = state.isRefreshingData,
                        onRefresh = { screenModel.refresh() },
                        enabled = !isAnySelected,
                        indicatorPadding = PaddingValues(top = contentPadding.calculateTopPadding()),
                    ) {
                        FastScrollLazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = chapterListState,
                            contentPadding = PaddingValues(
                                start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
                                top = 0.dp,
                                end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
                                bottom = contentPadding.calculateBottomPadding(),
                            ),
                        ) {
                            item(key = "novel_header") {
                                NovelInfoBox(
                                    appBarPadding = topPadding,
                                    novel = state.novel,
                                    sourceName = source.name,
                                    isStubSource = source is StubNovelSource,
                                    onCoverClick = { showCoverDialog = true },
                                    onSourceClick = if (source is NovelsPageSource) {
                                        { navigator.push(BrowseNovelSourceScreen(null, sourceId)) }
                                    } else {
                                        null
                                    },
                                )
                            }

                            item(key = "novel_action_row") {
                                val dbNovel = state.dbNovel
                                NovelActionRow(
                                    favorite = state.isFavorite,
                                    nextUpdate = dbNovel?.nextUpdate ?: 0L,
                                    isUserIntervalMode = (dbNovel?.fetchInterval ?: 0) < 0,
                                    onAddToLibraryClicked = screenModel::toggleFavorite,
                                    onEditCategory = if (state.isFavorite) {
                                        screenModel::showChangeCategoryDialog
                                    } else {
                                        null
                                    },
                                    onEditIntervalClicked = if (dbNovel != null && !isLocalSource) {
                                        { showIntervalDialog = true }
                                    } else {
                                        null
                                    },
                                    onWebViewClicked = if (source is HttpNovelSource) {
                                        {
                                            runCatching { source.getNovelUrl(state.novel) }
                                                .getOrNull()
                                                ?.takeIf { it.isNotBlank() }
                                                ?.let { url ->
                                                    navigator.push(
                                                        WebViewScreen(
                                                            url = url,
                                                            initialTitle = state.novel.title,
                                                        ),
                                                    )
                                                }
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }

                            item(key = "novel_description") {
                                ExpandableNovelDescription(
                                    description = state.novel.description,
                                    tagsProvider = {
                                        state.novel.genre
                                            ?.split(",")
                                            ?.map { it.trim() }
                                            ?.filter { it.isNotBlank() }
                                            ?.takeIf { it.isNotEmpty() }
                                    },
                                    onTagSearch = { tag ->
                                        navigator.push(
                                            BrowseNovelSourceScreen(
                                                null,
                                                sourceId,
                                                BrowseNovelSourceScreenModel.Listing.Search(tag),
                                            ),
                                        )
                                    },
                                    onCopyTagToClipboard = { context.copyToClipboard(it, it) },
                                )
                            }

                            state.detailError?.takeIf { it.isNotBlank() }?.let { error ->
                                item(key = "novel_detail_error") {
                                    Text(
                                        text = error,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                            }

                            item(key = "novel_chapter_header") {                                val missingChaptersCount = remember(visibleChapters) {
                                    visibleChapters.map { (it.novelChapter?.chapterNumber ?: it.snChapter.chapter_number).toDouble() }
                                        .missingEpisodesCount()
                                }
                                ItemHeader(
                                    enabled = !isAnySelected,
                                    itemCount = visibleChapters.size,
                                    missingItemsCount = missingChaptersCount,
                                    onClick = { showChapterSettings = true },
                                    isManga = true,
                                )
                                HorizontalDivider()
                            }

                            if (visibleChapters.isEmpty()) {
                                item(key = "novel_no_chapters") {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = state.chapterError?.takeIf { it.isNotBlank() } ?: "No chapters",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (state.chapterError != null) {
                                                    MaterialTheme.colorScheme.error
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                            )
                                        }
                                    }
                                }
                            } else {
                                items(visibleChapters, key = { "ch_${it.snChapter.url}" }) { item ->
                                    val readProgressText = if (item.lastPageRead > 0 && !item.isRead) {
                                        "${item.lastPageRead} chars"
                                    } else null

                                    MangaChapterListItem(
                                        title = item.snChapter.name,
                                        date = if (item.snChapter.date_upload > 0) {
                                            relativeDateText(item.snChapter.date_upload)
                                        } else null,
                                        readProgress = readProgressText,
                                        scanlator = item.snChapter.scanlator,
                                        sourceName = null,
                                        read = item.isRead,
                                        bookmark = item.isBookmarked,
                                        selected = item.id in state.selectedChapters,
                                        downloadIndicatorEnabled = !isLocalSource,
                                        downloadStateProvider = {
                                            val dm = try { Injekt.get<chimahon.novel.download.NovelDownloadManager>() } catch (_: Exception) { null }
                                            when (dm?.getDownloadState(item.id)) {
                                                chimahon.novel.download.NovelDownload.State.DOWNLOADED -> Download.State.DOWNLOADED
                                                chimahon.novel.download.NovelDownload.State.DOWNLOADING -> Download.State.DOWNLOADING
                                                chimahon.novel.download.NovelDownload.State.QUEUED -> Download.State.QUEUE
                                                else -> Download.State.NOT_DOWNLOADED
                                            }
                                        },
                                        downloadProgressProvider = {
                                            downloadQueue.find { it.chapterId == item.id }?.progress ?: 0
                                        },
                                        chapterSwipeStartAction = swipeStartAction,
                                        chapterSwipeEndAction = swipeEndAction,
                                        onLongClick = {
                                            if (state.isFavorite) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                screenModel.toggleChapterSelection(item.id)
                                            }
                                        },
                                        onClick = {
                                            if (state.selectionMode) {
                                                screenModel.toggleChapterSelection(item.id)
                                            } else {
                                                openChapterWithFeedback(item)
                                            }
                                        },
                                        onDownloadClick = { downloadAction: ChapterDownloadAction ->
                                            when (downloadAction) {
                                                ChapterDownloadAction.START, ChapterDownloadAction.START_NOW ->
                                                    screenModel.downloadChapter(item)
                                                ChapterDownloadAction.DELETE ->
                                                    screenModel.deleteChapterDownload(item)
                                                else -> {}
                                            }
                                        }.takeIf { state.dbNovel != null && !isLocalSource },
                                        onChapterSwipe = { swipeAction ->
                                            when (swipeAction) {
                                                LibraryPreferences.ChapterSwipeAction.ToggleRead ->
                                                    if (item.isRead) {
                                                        screenModel.markChapterUnread(item)
                                                    } else {
                                                        screenModel.markChapterRead(item)
                                                    }
                                                LibraryPreferences.ChapterSwipeAction.ToggleBookmark ->
                                                    screenModel.toggleChapterBookmark(item)
                                                LibraryPreferences.ChapterSwipeAction.Download ->
                                                    if (!isLocalSource) screenModel.downloadChapter(item)
                                                LibraryPreferences.ChapterSwipeAction.Disabled -> {}
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelChapterSettingsDialog(
    sortMode: Long,
    sortDescending: Boolean,
    unreadOnly: Boolean,
    bookmarkedOnly: Boolean,
    onDismiss: () -> Unit,
    onSortModeSelected: (Long) -> Unit,
    onFilterChanged: (unread: Boolean, bookmarked: Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.action_filter)) },
        text = {
            Column {
                // Mirrors eu.kanade.presentation.manga.ChapterSettingsDialog.SortPage (app/src/main/java/eu/kanade/presentation/manga/ChapterSettingsDialog.kt:193)
                // and domain sorting in tachiyomi.domain.chapter.service.ChapterSort.kt:14 / tachiyomi.domain.episode.service.EpisodeSort.kt:14
                SortItem(
                    label = stringResource(MR.strings.sort_by_source),
                    sortDescending = sortDescending.takeIf { sortMode == NovelSort.SORT_SOURCE },
                    onClick = { onSortModeSelected(NovelSort.SORT_SOURCE) },
                )
                SortItem(
                    label = stringResource(MR.strings.sort_by_number),
                    sortDescending = sortDescending.takeIf { sortMode == NovelSort.SORT_NUMBER },
                    onClick = { onSortModeSelected(NovelSort.SORT_NUMBER) },
                )
                SortItem(
                    label = stringResource(MR.strings.sort_by_upload_date),
                    sortDescending = sortDescending.takeIf { sortMode == NovelSort.SORT_UPLOAD_DATE },
                    onClick = { onSortModeSelected(NovelSort.SORT_UPLOAD_DATE) },
                )
                SortItem(
                    label = stringResource(MR.strings.action_sort_alpha),
                    sortDescending = sortDescending.takeIf { sortMode == NovelSort.SORT_ALPHABET },
                    onClick = { onSortModeSelected(NovelSort.SORT_ALPHABET) },
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFilterChanged(!unreadOnly, bookmarkedOnly) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = unreadOnly,
                        onCheckedChange = { onFilterChanged(it, bookmarkedOnly) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(MR.strings.action_filter_unread))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFilterChanged(unreadOnly, !bookmarkedOnly) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = bookmarkedOnly,
                        onCheckedChange = { onFilterChanged(unreadOnly, it) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(MR.strings.action_filter_bookmarked))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
    )
}

private fun openChapter(
    context: Context,
    source: NovelSource,
    novel: SNNovel,
    item: NovelChapterItem,
    chapters: List<NovelChapterItem>,
    screenModel: NovelDetailScreenModel,
    onStart: () -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
) {
    onStart()
    screenModel.screenModelScope.launch {
            try {
                // Book spine is always reading order (oldest-first), independent of display sort.
                // The book shell (metadata + chapter list) reuses or rebuilds cheaply;
                // content resolves on demand through the loader in the reader.
                val ordered = chapters.sortedBy { it.novelChapter?.chapterNumber ?: it.snChapter.chapter_number }
            val chapterIndex = ordered.indexOf(item).takeIf { it >= 0 } ?: 0
            // The reader takes identity + target from the row.
            val openNovelId = screenModel.state.value.dbNovel?.id
            val bookDir = withContext(Dispatchers.IO) {
                SourceChapterBookBuilder.ensureBookDir(
                    context,
                    SourceChapterBookBuilder.bookId(source, novel),
                )
            }
            // Tapped chapter fills in-reader; fetching here too would only contend on the book lock.
            withContext(Dispatchers.Main) {
                NovelReaderActivity.launch(context, bookDir, openNovelId, chapterIndex)
            }
            withContext(Dispatchers.Main) {
                onDone()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onError(e.message?.takeIf { it.isNotBlank() } ?: "Failed to open chapter")
            }
        }
    }
}

private fun shareNovel(context: Context, novel: SNNovel, source: NovelSource) {
    val url = (source as? HttpNovelSource)
        ?.let { runCatching { it.getNovelUrl(novel) }.getOrNull() }
        ?.takeIf { it.isNotBlank() }
    val text = listOfNotNull(
        novel.title.takeIf { it.isNotBlank() },
        url,
    ).joinToString("\n")
    if (text.isBlank()) return
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(android.content.Intent.createChooser(intent, novel.title))
}
