package eu.kanade.tachiyomi.ui.library.novels

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import chimahon.novel.data.BookImporter
import chimahon.novel.data.BookMetadata
import chimahon.novel.data.BookStorage
import chimahon.novel.data.NovelCategory
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.tachiyomi.util.system.toast
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelCategory as DbNovelCategory
import tachiyomi.domain.novel.model.NovelUpdate
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelCategoryRepository
import tachiyomi.domain.novel.repository.NovelHistoryRepository
import tachiyomi.domain.novel.repository.NovelReadingStatsRepository
import tachiyomi.domain.novel.repository.NovelRepository
import chimahon.novel.interactor.MigrateNovelJsonData
import chimahon.novel.interactor.RegisterLocalNovelHome
import chimahon.novel.source.LocalNovelFiles
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.NovelLibraryPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelLibraryScreenModel(
    private val app: Application = Injekt.get(),
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
    private val novelHistoryRepository: NovelHistoryRepository = Injekt.get(),
    private val libraryPreferences: NovelLibraryPreferences = Injekt.get(),
    private val novelRepository: NovelRepository = Injekt.get(),
    private val novelCategoryRepository: NovelCategoryRepository = Injekt.get(),
    private val migrateNovelJsonData: MigrateNovelJsonData = Injekt.get(),
) : StateScreenModel<NovelLibraryScreenModel.State>(State()) {

    private val _searchQuery = MutableStateFlow<String?>(null)

    init {
        screenModelScope.launch { migrateNovelJsonData.await() }
        // Sort choice survives restarts (manga parity); unknown stored
        // values fall back to the default. Applied before the first load so
        // the precomputed index already uses it.
        mutableState.update {
            it.copy(
                sortMode = runCatching { SortMode.valueOf(libraryPreferences.sortMode().get()) }
                    .getOrDefault(SortMode.DateAdded),
                sortDescending = libraryPreferences.sortDescending().get(),
            )
        }
        // The screen's LaunchedEffect(Unit) owns the first load and re-entry
        // refresh; loading here as well scanned twice on first display.

        screenModelScope.launch {
            _searchQuery
                .debounce(250)
                .collect { query ->
                    applyIndexChange { it.copy(searchQuery = query) }
                }
        }

        libraryPreferences.showHiddenCategories().changes()
            .onEach { showHiddenCategories ->
                applyIndexChange { it.copy(showHiddenCategories = showHiddenCategories) }
            }
            .launchIn(screenModelScope)
    }

    /** Row id for a local folder (reader identity threading); null when unregistered. */
    suspend fun getLocalNovelId(folder: String): Long? {
        return runCatching { novelRepository.getNovelByLocalFolder(folder)?.id }.getOrNull()
    }

    private val indexMutex = Mutex()
    private var loadJob: Job? = null
    private var reloadRequested = false

    /**
     * Applies a preference change and rebuilds the index against the newest
     * data. The mutex stops a load from publishing an index built from older
     * state (and vice versa) when both run at once.
     */
    private suspend fun applyIndexChange(transform: (State) -> State) {
        indexMutex.withLock {
            val current = mutableState.value
            val updated = transform(current)
            if (updated == current) return@withLock
            val index = withContext(Dispatchers.Default) { updated.buildIndex() }
            mutableState.update { live ->
                live.copy(
                    searchQuery = updated.searchQuery,
                    sortMode = updated.sortMode,
                    sortDescending = updated.sortDescending,
                    showHiddenCategories = updated.showHiddenCategories,
                    index = index,
                )
            }
        }
    }

    fun loadLibrary() {
        // A scan can take a while; a refresh requested during one used to be
        // dropped. Remember it and rerun once the current pass finishes.
        if (loadJob?.isActive == true) {
            reloadRequested = true
            return
        }
        loadJob = screenModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { readSnapshot() }
                indexMutex.withLock {
                    // Read the UI state after the scan: a query or sort change
                    // made while it ran must not be overwritten by a stale index.
                    val current = mutableState.value
                    val candidate = current.copy(
                        isLoading = false,
                        categories = snapshot.categories.toImmutableList(),
                        books = snapshot.books.toImmutableList(),
                        sourceNovels = snapshot.sourceNovels.toImmutableList(),
                        unreadCounts = snapshot.unreadCounts.toImmutableMap(),
                        downloadCounts = snapshot.downloadCounts.toImmutableMap(),
                        lastReadTimestamps = snapshot.lastReadTimestamps.toImmutableMap(),
                        novelCategoryIds = snapshot.novelCategoryIds.toImmutableMap(),
                        booksWithoutContent = snapshot.booksWithoutContent,
                        localNovelIds = snapshot.localNovelIds,
                        linkedSourceNovelIds = snapshot.linkedSourceNovelIds,
                    )
                    // Grouping and sorting are done once here, not per composition.
                    val index = withContext(Dispatchers.Default) { candidate.buildIndex() }
                    mutableState.update { live ->
                        // Resolve against the live page: a swipe made while the
                        // index was building must win over the page read before it.
                        val activeCategoryId = live.activeCategory?.id
                        val activeCategoryIndex = index.displayedCategories
                            .indexOfFirst { category -> category.id == activeCategoryId }
                            .takeIf { it >= 0 }
                            ?: live.activeCategoryIndex.coerceIn(0, (index.displayedCategories.size - 1).coerceAtLeast(0))
                        live.copy(
                            isLoading = false,
                            categories = candidate.categories,
                            books = candidate.books,
                            sourceNovels = candidate.sourceNovels,
                            unreadCounts = candidate.unreadCounts,
                            downloadCounts = candidate.downloadCounts,
                            lastReadTimestamps = candidate.lastReadTimestamps,
                            novelCategoryIds = candidate.novelCategoryIds,
                            booksWithoutContent = candidate.booksWithoutContent,
                            localNovelIds = candidate.localNovelIds,
                            linkedSourceNovelIds = candidate.linkedSourceNovelIds,
                            activeCategoryIndex = activeCategoryIndex,
                            index = index,
                        )
                    }
                }
                screenModelScope.launch { registerMissingBooks(snapshot) }
            } finally {
                loadJob = null
                if (reloadRequested) {
                    reloadRequested = false
                    loadLibrary()
                }
            }
        }
    }

    private suspend fun readSnapshot(): NovelLibrarySnapshot {
        val categories = novelCategoryRepository.getAll().map { it.toUiCategory() }
        // Source-built reader-cache books (src_*) are not library entries:
        // only explicit imports and favorites appear here.
        val books = BookStorage.loadAllBooks(app)
            .filterNot { it.id.startsWith("src_") }
        val sourceNovels = novelRepository.getFavorites()
        val bookIds = books.map { it.id }.toSet()
        val sourceManager = Injekt.get<chimahon.novel.manager.NovelSourceManager>()
        val linkedSourceNovelIds = sourceNovels.filterNot { it.source == Novel.LOCAL_SOURCE_ID }
            .mapNotNull { novel ->
                val folder = novel.localFolder?.takeIf { it in bookIds }
                    ?: libraryPreferences.downloadedBookFolder(novel.id).get().takeIf { it in bookIds }
                    ?: runCatching {
                        (sourceManager.getNovelSource(novel.source) as? eu.kanade.tachiyomi.sourcenovel.NovelFileSource)
                            ?.downloadFolderId(novel.url)?.takeIf { it in bookIds }
                    }.getOrNull()
                folder?.let { it to novel.id }
            }.groupBy({ it.first }, { it.second })
            .mapNotNull { (folder, ids) -> ids.singleOrNull()?.let { folder to it } }.toMap()
        linkedSourceNovelIds.forEach { (folder, novelId) ->
            libraryPreferences.downloadedBookFolder(novelId).set(folder)
        }
        val localRows = novelRepository.getNovelsBySourceId(Novel.LOCAL_SOURCE_ID)
        val localNovelIds = localRows
            .mapNotNull { row -> row.localFolder?.takeIf { it.isNotBlank() }?.let { it to row.id } }
            .toMap()
        val unreadCounts = novelChapterRepository
            .getUnreadCountsByNovelIds(sourceNovels.map { it.id })
        val lastReadTimestamps = novelHistoryRepository
            .getLatestLastReadByNovelIds(sourceNovels.map { it.id })
        val categoryMap = novelCategoryRepository
            .getCategoryIdsByNovelIds(sourceNovels.map { it.id } + localNovelIds.values)
        val downloadCounts = try {
            val dm = Injekt.get<chimahon.novel.download.NovelDownloadManager>()
            sourceNovels.associate { novel ->
                val chapters = novelChapterRepository.getChaptersByNovelId(novel.id)
                novel.id to dm.getDownloadedCount(novel.id, chapters).toLong()
            }
        } catch (_: Exception) { emptyMap() }
        // Empty local entries (folder without readable content): derived
        // per load, never stored. Readability is one shared check, so the
        // badge and the tap handler always agree.
        val booksWithoutContent = books
            .filter { !BookStorage.hasReadableBookContent(app, it.id) }
            .map { it.id }
            .toSet()
        return NovelLibrarySnapshot(
            categories = categories,
            books = books,
            sourceNovels = sourceNovels,
            unreadCounts = unreadCounts,
            downloadCounts = downloadCounts,
            lastReadTimestamps = lastReadTimestamps,
            novelCategoryIds = categoryMap,
            booksWithoutContent = booksWithoutContent,
            localNovelIds = localNovelIds,
            linkedSourceNovelIds = linkedSourceNovelIds,
            localRows = localRows,
        )
    }

    private suspend fun registerMissingBooks(snapshot: NovelLibrarySnapshot) {
        withContext(Dispatchers.IO) {
            runCatching {
                // Cache hygiene first: externally-deleted books orphan
                // their extraction cache; crashed imports leave temps.
                // Live = scan presence + rows (re-extract self-heals).
                BookStorage.pruneCaches(
                    app,
                    (snapshot.books.map { it.id } + snapshot.localNovelIds.keys).toSet(),
                )
                val registrar = Injekt.get<RegisterLocalNovelHome>()
                val epubFolders = chimahon.novel.source.LocalNovelFiles.foldersWithEpub(app)
                val coverless = snapshot.localRows
                    .filter { it.thumbnailUrl.isNullOrBlank() }
                    .mapNotNull { it.localFolder?.takeIf { f -> f.isNotBlank() } }
                    .toSet()
                snapshot.books.filter {
                    // Readable = extracted content stages/can-stage, or a
                    // loose public `.epub` (either scheme — register()
                    // extracts on demand). Empty dirs stay out.
                    (it.id !in snapshot.localNovelIds || it.id in coverless) &&
                        (it.id !in snapshot.booksWithoutContent || it.id in epubFolders)
                }.forEach { runCatching { registrar.register(it.id) } }
            }
        }
    }

    private fun DbNovelCategory.toUiCategory(): chimahon.novel.data.NovelCategory {
        return chimahon.novel.data.NovelCategory(
            id = if (id == 0L) "default" else id.toString(),
            name = name,
            order = order,
            flags = flags,
            hidden = hidden,
        )
    }

    fun search(query: String?) {
        _searchQuery.value = query
    }

    fun updateActiveCategoryIndex(index: Int) {
        mutableState.update { it.copy(activeCategoryIndex = index) }
    }

    fun toggleSelection(bookId: String) {
        mutableState.update { state ->
            val selection = state.selection.toMutableList()
            if (selection.contains(bookId)) {
                selection.remove(bookId)
            } else {
                selection.add(bookId)
            }
            state.copy(selection = selection.toImmutableList())
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selection = persistentListOf()) }
    }

    fun selectAll() {
        mutableState.update { state ->
            val ids = state.activeCategory?.let { state.getItemsForCategory(it).map { item -> item.id } }.orEmpty()
            state.copy(selection = (state.selection + ids).distinct().toImmutableList())
        }
    }

    fun invertSelection() {
        mutableState.update { state ->
            val ids = state.activeCategory?.let { state.getItemsForCategory(it).map { item -> item.id } }.orEmpty().toSet()
            val newSelection = (state.selection.filterNot { it in ids } + (ids - state.selection.toSet())).toImmutableList()
            state.copy(selection = newSelection)
        }
    }

    fun deleteSelected(deleteFromLibrary: Boolean = true, deleteDownloadedFiles: Boolean = false) {
        screenModelScope.launch {
            val state = mutableState.value
            val selected = state.selection.toSet()
            if (deleteDownloadedFiles) {
                state.books.filter { it.id in selected }.forEach { book ->
                    BookStorage.deleteBook(app, book.id)
                }
                // Deleting with downloads checked also wipes online novel
                // download dirs + index entries.
                val downloadManager = runCatching { Injekt.get<chimahon.novel.download.NovelDownloadManager>() }.getOrNull()
                val sourceManager = runCatching { Injekt.get<chimahon.novel.manager.NovelSourceManager>() }.getOrNull()
                if (downloadManager != null && sourceManager != null) {
                    state.sourceNovels.filter { sourceNovelLibraryItemId(it.id) in selected }.forEach { novel ->
                        val source = runCatching { sourceManager.getNovelSource(novel.source) }.getOrNull()
                        if (source != null) {
                            runCatching { downloadManager.deleteNovel(novel, source) }
                        }
                    }
                }
            }
            if (deleteFromLibrary) {
                val updateNovel = Injekt.get<chimahon.novel.interactor.UpdateNovel>()
                state.sourceNovels.filter { sourceNovelLibraryItemId(it.id) in selected }.forEach { novel ->
                    runCatching { updateNovel.awaitUpdateFavorite(novel.id, false) }
                }
                state.books.filter { it.id in selected }.forEach { book ->
                    val localNovel = novelRepository.getNovelByUrlAndSourceId("local://${book.id}", Novel.LOCAL_SOURCE_ID)
                    if (localNovel != null) {
                        runCatching { updateNovel.awaitUpdateFavorite(localNovel.id, false) }
                    }
                }
            }
            clearSelection()
            loadLibrary()
        }
    }

    fun importBooks(uris: List<Uri>) {
        screenModelScope.launch {
            mutableState.update { it.copy(isImporting = true) }
            val currentCategory = mutableState.value.activeCategory
            val categoryIds = if (currentCategory != null && !currentCategory.isSystemCategory) {
                listOf(currentCategory.id)
            } else {
                null
            }
            var imported = 0
            var errors = 0
            uris.forEach { uri ->
                // UniFile-first import target (public localnovel on any
                // scheme); null root falls back to the private dir inside.
                val result = BookImporter.importEpub(
                    app,
                    uri,
                    categoryIds,
                    targetRootUni = chimahon.novel.source.LocalNovelFiles.publicRootUni(app),
                )
                val metadata = result.metadata
                if (metadata != null) {
                    imported++
                    runCatching {
                        val novelId = Injekt.get<RegisterLocalNovelHome>()
                            .register(metadata.id)
                        // UI string ids are DB ids stringified ("default" = system 0L).
                        if (novelId != null && categoryIds != null) {
                            val longIds = categoryIds
                                .filter { it.isNotBlank() }
                                .mapNotNull { if (it == "default") 0L else it.toLongOrNull() }
                            if (longIds.isNotEmpty()) {
                                Injekt.get<chimahon.novel.interactor.SetNovelCategories>()
                                    .await(novelId, longIds)
                            }
                        }
                    }
                } else errors++
            }
            loadLibrary()
            mutableState.update { it.copy(isImporting = false, importResult = Pair(imported, errors)) }
        }
    }

    fun resetStatsForSelected() {
        screenModelScope.launch {
            val state = mutableState.value
            state.selection.forEach { bookId ->
                runCatching {
                    val novelId = novelRepository.getNovelByLocalFolder(bookId)?.id ?: return@forEach
                    // Stats + history go; chapter read marks stay (manga keeps
                    // library state on stats reset too).
                    Injekt.get<NovelReadingStatsRepository>().deleteByNovelId(novelId)
                    novelHistoryRepository.deleteHistoryByNovelIds(listOf(novelId))
                }
            }
            clearSelection()
            loadLibrary()
        }
    }

    fun showChangeCategoryDialog() {
        val s = mutableState.value
        val userCategories = s.categories.filterNot { it.isSystemCategory }
        screenModelScope.launch {
            val novelIds = mutableSetOf<Long>()
            for (id in s.selection.distinct()) {
                val novelId = s.linkedSourceNovelIds[id]
                    ?: s.sourceNovels.firstOrNull { sourceNovelLibraryItemId(it.id) == id }?.id
                    ?: novelRepository.getNovelByLocalFolder(id)?.id
                    ?: Injekt.get<RegisterLocalNovelHome>().register(id)
                if (novelId == null) {
                    app.toast(MR.strings.internal_error)
                    return@launch
                }
                novelIds += novelId
            }
            val allSets = novelIds.map { novelCategoryRepository.getByNovelId(it).map { category -> category.id }.toSet() }
            val commonCategoryIds = if (allSets.isEmpty()) {
                emptySet()
            } else {
                allSets.reduce { set1, set2 -> set1.intersect(set2) }
            }
            val commonCategories = userCategories.filter { it.id.toLongOrNull() in commonCategoryIds }

            val mixCategoryIds = if (allSets.isEmpty()) {
                emptySet()
            } else {
                allSets.flatMap { it }.distinct().toSet() - commonCategoryIds
            }
            showChangeCategoryDialog(novelIds.toList(), userCategories, commonCategories, mixCategoryIds)
        }
    }

    private fun showChangeCategoryDialog(
        novelIds: List<Long>,
        userCategories: List<NovelCategory>,
        commonCategories: List<NovelCategory>,
        mixCategoryIds: Set<Long>,
    ) {
        val mixCategories = userCategories.filter { it.id.toLongOrNull() in mixCategoryIds }

        val preselected = userCategories.map { cat ->
            val mapped = Category(
                id = cat.id.toLong(),
                name = cat.name,
                order = cat.order.toLong(),
                flags = cat.flags,
                hidden = false,
            )
            when (cat) {
                in commonCategories -> CheckboxState.State.Checked(mapped)
                in mixCategories -> CheckboxState.TriState.Exclude(mapped)
                else -> CheckboxState.State.None(mapped)
            }
        }.toImmutableList()

        mutableState.update {
            it.copy(dialog = Dialog.ChangeCategory(novelIds.toImmutableList(), preselected))
        }
    }

    fun setNovelsCategories(
        novelIds: List<Long>,
        addCategories: List<Long>,
        removeCategories: List<Long>,
    ) {
        screenModelScope.launchNonCancellable {
            novelIds.distinct().forEach { novelId ->
                val current = novelCategoryRepository.getByNovelId(novelId).map { it.id }.toSet()
                Injekt.get<chimahon.novel.interactor.SetNovelCategories>()
                    .await(novelId, (current - removeCategories.toSet() + addCategories).toList())
            }
            loadLibrary()
        }
    }

    fun showDeleteConfirmDialog() {
        mutableState.update { it.copy(dialog = Dialog.DeleteConfirm, deleteDialogDownloadFiles = false) }
    }

    fun toggleDeleteDialogDownloadFiles() {
        mutableState.update { it.copy(deleteDialogDownloadFiles = !it.deleteDialogDownloadFiles) }
    }

    fun showEditDialog() {
        val state = mutableState.value
        if (state.selection.size == 1) {
            val bookId = state.selection.first()
            val book = state.books.find { it.id == bookId }
            if (book != null) {
                mutableState.update { it.copy(dialog = Dialog.EditBook(book)) }
            }
        }
    }

    fun updateBookMetadata(book: BookMetadata, selectedOverride: String) {
        screenModelScope.launch {
            runCatching {
                novelRepository.getNovelByLocalFolder(book.id)?.let { novel ->
                    Injekt.get<chimahon.novel.interactor.UpdateNovel>().await(
                        NovelUpdate(
                            id = novel.id,
                            title = book.title,
                            author = book.author,
                            lang = book.lang,
                        )
                    )
                }
            }

            // Save override
            val dictPrefs = Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>()
            val novelOverrideKey = chimahon.dictionary.DictionaryProfileResolver.novelOverrideKey(book.id)
            dictPrefs.rawProfileOverride(novelOverrideKey).set(selectedOverride)

            loadLibrary()
            closeDialog()
        }
    }

    fun clearImportResult() {
        mutableState.update { it.copy(importResult = null) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    enum class SortMode {
        Alphabetical, DateAdded, LastRead
    }

    sealed interface Dialog {
        data class ChangeCategory(
            val novelIds: ImmutableList<Long>,
            val initialSelection: ImmutableList<CheckboxState<Category>>,
        ) : Dialog
        data object DeleteConfirm : Dialog
        data object SortFilter : Dialog
        data object Settings : Dialog
        data class EditBook(val book: BookMetadata) : Dialog
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val categories: ImmutableList<NovelCategory> = persistentListOf(),
        val books: ImmutableList<BookMetadata> = persistentListOf(),
        val sourceNovels: ImmutableList<Novel> = persistentListOf(),
        val searchQuery: String? = null,
        val selection: ImmutableList<String> = persistentListOf(),
        val activeCategoryIndex: Int = 0,
        val dialog: Dialog? = null,
        val sortMode: SortMode = SortMode.DateAdded,
        val sortDescending: Boolean = true,
        val isImporting: Boolean = false,
        val importResult: Pair<Int, Int>? = null,
        val showHiddenCategories: Boolean = false,
        val unreadCounts: Map<Long, Long> = emptyMap(),
        val downloadCounts: Map<Long, Long> = emptyMap(),
        val lastReadTimestamps: Map<Long, Long> = emptyMap(),
        val novelCategoryIds: Map<Long, List<Long>> = emptyMap(),
        val booksWithoutContent: Set<String> = emptySet(),
        val localNovelIds: Map<String, Long> = emptyMap(),
        val linkedSourceNovelIds: Map<String, Long> = emptyMap(),
        val index: NovelLibraryIndex = NovelLibraryIndex.Empty,
        val deleteDialogDownloadFiles: Boolean = false,
    ) {
        val hasActiveFilters: Boolean
            get() = searchQuery != null || sortMode != SortMode.DateAdded || !sortDescending
        val isLibraryEmpty: Boolean = books.isEmpty() && sourceNovels.isEmpty()
        val selectionMode: Boolean = selection.isNotEmpty()

        val displayedCategories: List<NovelCategory>
            get() = index.displayedCategories

        val coercedActiveCategoryIndex: Int
            get() = activeCategoryIndex.coerceIn(0, (displayedCategories.size - 1).coerceAtLeast(0))

        val activeCategory: NovelCategory?
            get() = displayedCategories.getOrNull(coercedActiveCategoryIndex)

        fun getItemsForCategory(category: NovelCategory): List<NovelLibraryItem> {
            return index.itemsFor(category)
        }

        fun getItemCountForCategory(category: NovelCategory): Int {
            return index.countFor(category)
        }

        fun getToolbarTitle(
            defaultTitle: String,
            defaultCategoryTitle: String,
            showTabs: Boolean,
            showCount: Boolean,
        ): LibraryToolbarTitle {
            val category = activeCategory
            val categoryName = when {
                category == null -> defaultTitle
                category.isSystemCategory -> defaultCategoryTitle
                else -> category.name
            }
            val title = if (showTabs) defaultTitle else categoryName
            val count = when {
                !showCount -> null
                !showTabs && category != null -> getItemCountForCategory(category)
                else -> books.size + sourceNovels.size
            }
            return LibraryToolbarTitle(title, count)
        }
    }
    
    private fun State.buildIndex(
        searchQuery: String? = this.searchQuery,
        sortMode: SortMode = this.sortMode,
        sortDescending: Boolean = this.sortDescending,
        showHiddenCategories: Boolean = this.showHiddenCategories,
    ): NovelLibraryIndex {
        return buildNovelLibraryIndex(
            NovelLibraryIndexInput(
                categories = categories,
                books = books,
                sourceNovels = sourceNovels,
                searchQuery = searchQuery,
                sortMode = sortMode,
                sortDescending = sortDescending,
                showHiddenCategories = showHiddenCategories,
                unreadCounts = unreadCounts,
                downloadCounts = downloadCounts,
                lastReadTimestamps = lastReadTimestamps,
                novelCategoryIds = novelCategoryIds,
                localNovelIds = localNovelIds,
                linkedSourceNovelIds = linkedSourceNovelIds,
            ),
        )
    }

    fun setSort(mode: SortMode, descending: Boolean) {
        libraryPreferences.sortMode().set(mode.name)
        libraryPreferences.sortDescending().set(descending)
        screenModelScope.launch {
            applyIndexChange { it.copy(sortMode = mode, sortDescending = descending) }
        }
    }
    
    fun showSortDialog() {
        mutableState.update { it.copy(dialog = Dialog.SortFilter) }
    }

    fun showSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.Settings) }
    }

    fun getColumnsPreferenceForOrientation(isLandscape: Boolean) = if (isLandscape) {
        libraryPreferences.landscapeColumns()
    } else {
        libraryPreferences.portraitColumns()
    }

    fun getDisplayMode() = libraryPreferences.displayMode()

    fun setDisplayMode(mode: LibraryDisplayMode) {
        libraryPreferences.displayMode().set(mode)
    }

    fun showTabs() = libraryPreferences.categoryTabs()

    fun showNumberOfItems() = libraryPreferences.categoryNumberOfItems()

    fun showHiddenCategories() = libraryPreferences.showHiddenCategories()

    fun getRandomBookForCurrentCategory(): BookMetadata? {
        val s = mutableState.value
        val category = s.activeCategory ?: return null
        val items = s.getItemsForCategory(category)
        return items.filterIsInstance<NovelLibraryItem.LocalBook>().randomOrNull()?.metadata
    }
}
