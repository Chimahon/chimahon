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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.NovelLibraryPreferences
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
        loadLibrary()

        screenModelScope.launch {
            _searchQuery
                .debounce(250)
                .collect { query ->
                    mutableState.update { it.copy(searchQuery = query) }
                }
        }

        libraryPreferences.showHiddenCategories().changes()
            .onEach { showHiddenCategories ->
                mutableState.update { it.copy(showHiddenCategories = showHiddenCategories) }
            }
            .launchIn(screenModelScope)
    }

    /** Row id for a local folder (reader identity threading); null when unregistered. */
    suspend fun getLocalNovelId(folder: String): Long? {
        return runCatching { novelRepository.getNovelByLocalFolder(folder)?.id }.getOrNull()
    }

    fun loadLibrary() {
        screenModelScope.launch {
            val categories = novelCategoryRepository.getAll().map { it.toUiCategory() }
            // Source-built reader-cache books (src_*) are not library entries:
            // only explicit imports and favorites appear here.
            val books = BookStorage.loadAllBooks(app)
                .filterNot { it.id.startsWith("src_") }
            val sourceNovels = novelRepository.getFavorites()
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
            // Empty local entries (folder without EPUB content): derived per load,
            // never stored — the ghost flag is gone.
            val booksWithoutContent = books
                .filter { !BookStorage.hasImportedBookContent(BookStorage.getBookDirectory(app, it.id)) }
                .map { it.id }
                .toSet()

            mutableState.update {
                it.copy(
                    isLoading = false,
                    categories = categories.toImmutableList(),
                    books = books.toImmutableList(),
                    sourceNovels = sourceNovels.toImmutableList(),
                    unreadCounts = unreadCounts.toImmutableMap(),
                    downloadCounts = downloadCounts.toImmutableMap(),
                    lastReadTimestamps = lastReadTimestamps.toImmutableMap(),
                    novelCategoryIds = categoryMap.toImmutableMap(),
                    booksWithoutContent = booksWithoutContent,
                    localNovelIds = localNovelIds,
                )
            }

            // Orphan content dirs (files, no row — manually copied folders):
            // register silently so history and resume work like any import.
            // Books with a loose EPUB but nothing extracted yet count too —
            // register() extracts on demand through the source. Rows missing a
            // cover re-register as well (self-heal for the pre-EPUB-cover era).
            // No reload; cards already render from the scan.
            screenModelScope.launch {
                runCatching {
                    // Cache hygiene first: externally-deleted books orphan
                    // their extraction cache; crashed imports leave temps.
                    // Live = scan presence + rows (re-extract self-heals).
                    BookStorage.pruneCaches(
                        app,
                        (books.map { it.id } + localNovelIds.keys).toSet(),
                    )
                    val registrar = Injekt.get<RegisterLocalNovelHome>()
                    val epubFolders = chimahon.novel.source.LocalNovelFiles.foldersWithEpub(app)
                    val coverless = localRows
                        .filter { it.thumbnailUrl.isNullOrBlank() }
                        .mapNotNull { it.localFolder?.takeIf { f -> f.isNotBlank() } }
                        .toSet()
                    books.filter {
                        // Readable = extracted content stages/can-stage, or a
                        // loose public `.epub` (either scheme — register()
                        // extracts on demand). Empty dirs stay out.
                        (it.id !in localNovelIds || it.id in coverless) &&
                            (it.id !in booksWithoutContent || it.id in epubFolders)
                    }.forEach { runCatching { registrar.register(it.id) } }
                }
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
            val allIds = state.displayedCategories.flatMap { cat ->
                state.getItemsForCategory(cat).map { it.id }
            }.toImmutableList()
            state.copy(selection = allIds)
        }
    }

    fun invertSelection() {
        mutableState.update { state ->
            val allIds = state.displayedCategories.flatMap { cat ->
                state.getItemsForCategory(cat).map { it.id }
            }.toSet()
            val newSelection = allIds.minus(state.selection).toList().toImmutableList()
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
        val selectedLocalBooks = s.books.filter { it.id in s.selection.toSet() }
        val userCategories = s.categories.filterNot { it.isSystemCategory }
        screenModelScope.launch {
            // Category sets from the DB; sidecar fills gaps for
            // never-registered books. Long DB ids surface as UI strings.
            val dbSets = selectedLocalBooks.mapNotNull { book ->
                s.localNovelIds[book.id]?.let { novelId ->
                    runCatching { novelCategoryRepository.getByNovelId(novelId) }.getOrNull()
                        ?.map { if (it.id == 0L) NovelCategory.UNCATEGORIZED_ID else it.id.toString() }
                        ?.toSet()
                }
            }
            val sidecarSets = selectedLocalBooks
                .filter { book -> s.localNovelIds[book.id] == null }
                .map { it.categoryIds.toSet() }
            val allSets = dbSets + sidecarSets
            val commonCategoryIds = if (allSets.isEmpty()) {
                emptySet()
            } else {
                allSets.reduce { set1, set2 -> set1.intersect(set2) }
            }
            val commonCategories = userCategories.filter { it.id in commonCategoryIds }

            val mixCategoryIds = if (allSets.isEmpty()) {
                emptySet()
            } else {
                allSets.flatMap { it }.distinct().toSet() - commonCategoryIds
            }
            showChangeCategoryDialog(selectedLocalBooks, userCategories, commonCategories, mixCategoryIds)
        }
    }

    private fun showChangeCategoryDialog(
        selectedLocalBooks: List<BookMetadata>,
        userCategories: List<NovelCategory>,
        commonCategories: List<NovelCategory>,
        mixCategoryIds: Set<String>,
    ) {
        val mixCategories = userCategories.filter { it.id in mixCategoryIds }

        val preselected = userCategories.map { cat ->
            val mapped = Category(
                id = cat.id.hashCode().toLong(),
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
            it.copy(dialog = Dialog.ChangeCategory(selectedLocalBooks.toImmutableList(), preselected))
        }
    }

    fun setBooksCategories(
        books: List<BookMetadata>,
        addCategories: List<Long>,
        removeCategories: List<Long>,
    ) {
        screenModelScope.launch {
            val s = mutableState.value
            // Dialog ids are hashCode-based; map back through the UI list to
            // DB longs ("default" = system 0L, absence = default bucket).
            fun mapIds(ids: List<Long>): Set<Long> = ids.mapNotNull { id ->
                s.categories.find { it.id.hashCode().toLong() == id }?.let {
                    if (it.id == NovelCategory.UNCATEGORIZED_ID) 0L else it.id.toLongOrNull()
                }
            }.toSet()
            val addIds = mapIds(addCategories)
            val removeIds = mapIds(removeCategories)
            books.forEach { book ->
                val novelId = runCatching { novelRepository.getNovelByLocalFolder(book.id)?.id }.getOrNull()
                    ?: return@forEach
                val current = runCatching { novelCategoryRepository.getByNovelId(novelId) }
                    .getOrNull().orEmpty().map { it.id }.toSet()
                runCatching {
                    Injekt.get<chimahon.novel.interactor.SetNovelCategories>()
                        .await(novelId, (current - removeIds + addIds).toList())
                }
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
            val books: ImmutableList<BookMetadata>,
            val initialSelection: ImmutableList<CheckboxState<Category>>,
        ) : Dialog
        data object DeleteConfirm : Dialog
        data object SortFilter : Dialog
        data object Settings : Dialog
        data class EditBook(val book: BookMetadata) : Dialog
        data object SetDefaultCategory : Dialog
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
        val deleteDialogDownloadFiles: Boolean = false,
    ) {
        val hasActiveFilters: Boolean
            get() = searchQuery != null || sortMode != SortMode.DateAdded || !sortDescending
        val isLibraryEmpty: Boolean = books.isEmpty() && sourceNovels.isEmpty()
        val selectionMode: Boolean = selection.isNotEmpty()

        val displayedCategories: List<NovelCategory>
            get() = categories.filterNot {
                it.isSystemCategory && getItemsForCategory(it).isEmpty()
            }.filterNot {
                it.hidden && !showHiddenCategories
            }

        val coercedActiveCategoryIndex: Int
            get() = activeCategoryIndex.coerceIn(0, (displayedCategories.size - 1).coerceAtLeast(0))
        
        val activeCategory: NovelCategory?
            get() = displayedCategories.getOrNull(coercedActiveCategoryIndex)

        fun getItemsForCategory(category: NovelCategory): List<NovelLibraryItem> {
            val query = searchQuery
            val localItems = books
                .filter { query == null || it.title?.contains(query, ignoreCase = true) == true }
                .map { NovelLibraryItem.LocalBook(it) }
            val sourceItems = sourceNovels
                // Local-source rows are represented by the file-scan cards above
                // (LocalBook owns MISSING/add-files/folder routing); rendering both
                // showed every import twice.
                .filterNot { it.source == Novel.LOCAL_SOURCE_ID }
                .filter { query == null || it.title.contains(query, ignoreCase = true) }
                .map { NovelLibraryItem.SourceNovel(it, unreadCount = unreadCounts[it.id]?.toInt() ?: 0, downloadCount = downloadCounts[it.id]?.toInt() ?: 0) }

            val knownCategoryIds = categories.map { it.id }.toSet()
            val allItems = (localItems + sourceItems).filter { item ->
                when (item) {
                    is NovelLibraryItem.LocalBook -> {
                        // Row joins win; the sidecar survives only for
                        // never-registered books.
                        val rowIds = localNovelIds[item.metadata.id]?.let { novelCategoryIds[it] }
                        if (rowIds != null) {
                            if (category.isSystemCategory) {
                                rowIds.isEmpty()
                            } else {
                                val dbId = category.id.toLongOrNull()
                                dbId != null && rowIds.contains(dbId)
                            }
                        } else {
                            val ids = item.metadata.normalizedCategoryIds(knownCategoryIds)
                            if (category.isSystemCategory) {
                                ids.isEmpty() || ids.contains(NovelCategory.UNCATEGORIZED_ID)
                            } else {
                                ids.contains(category.id)
                            }
                        }
                    }
                    is NovelLibraryItem.SourceNovel -> {
                        // Absence of join rows = Default/system category (manga convention)
                        val ids = novelCategoryIds[item.novel.id].orEmpty()
                        if (category.isSystemCategory) {
                            ids.isEmpty()
                        } else {
                            val dbId = category.id.toLongOrNull()
                            dbId != null && ids.contains(dbId)
                        }
                    }
                }
            }
            val comparator: Comparator<NovelLibraryItem> = when (sortMode) {
                SortMode.Alphabetical -> compareBy({ it.title.lowercase() }, { it.id })
                SortMode.DateAdded -> compareBy<NovelLibraryItem> { item ->
                    when (item) {
                        is NovelLibraryItem.LocalBook -> item.metadata.dateAdded
                        is NovelLibraryItem.SourceNovel -> item.novel.dateAdded
                    }
                }
                SortMode.LastRead -> compareBy<NovelLibraryItem> { item ->
                    when (item) {
                        is NovelLibraryItem.LocalBook -> item.metadata.lastAccess
                        is NovelLibraryItem.SourceNovel ->
                            lastReadTimestamps[item.novel.id] ?: 0L
                    }
                }
            }
            val tieBreaker = compareBy<NovelLibraryItem>({ it.title.lowercase() }, { it.id })
            return if (sortDescending) {
                allItems.sortedWith(comparator.reversed().then(tieBreaker))
            } else {
                allItems.sortedWith(comparator.then(tieBreaker))
            }
        }

        private fun BookMetadata.normalizedCategoryIds(knownCategoryIds: Set<String>): List<String> {
            val distinctIds = categoryIds
                .filter { it.isNotBlank() }
                .distinct()

            val nonDefaultIds = distinctIds.filterNot { it == NovelCategory.UNCATEGORIZED_ID }
            return if (nonDefaultIds.isNotEmpty()) {
                nonDefaultIds.filter { it in knownCategoryIds }
            } else {
                distinctIds
            }
        }

        fun getItemCountForCategory(category: NovelCategory): Int {
            return getItemsForCategory(category).size
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
    
    fun setSort(mode: SortMode, descending: Boolean) {
        mutableState.update { it.copy(sortMode = mode, sortDescending = descending) }
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

    fun showNovelDefaultCategoryDialog() {
        mutableState.update { it.copy(dialog = Dialog.SetDefaultCategory) }
    }

    fun setNovelDefaultCategory(categoryId: String) {
        libraryPreferences.defaultCategory().set(categoryId)
        closeDialog()
    }

    fun getNovelDefaultCategory() = libraryPreferences.defaultCategory()

    fun getDefaultCategoryDisplayName(): String {
        val defaultId = libraryPreferences.defaultCategory().get()
        if (defaultId.isEmpty()) return "None"
        val cat = mutableState.value.categories.find { it.id == defaultId }
        return cat?.name ?: "None"
    }

    fun getRandomBookForCurrentCategory(): BookMetadata? {
        val s = mutableState.value
        val category = s.activeCategory ?: return null
        val items = s.getItemsForCategory(category)
        return items.filterIsInstance<NovelLibraryItem.LocalBook>().randomOrNull()?.metadata
    }
}