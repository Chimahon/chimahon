package eu.kanade.tachiyomi.ui.library.novels

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.canopus.chimareader.data.BookImporter
import com.canopus.chimareader.data.BookMetadata
import com.canopus.chimareader.data.BookStorage
import com.canopus.chimareader.data.NovelCategory
import com.canopus.chimareader.data.NovelCategoryStorage
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.repository.NovelRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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
    private val categoryStorage: NovelCategoryStorage = Injekt.get(),
    private val libraryPreferences: NovelLibraryPreferences = Injekt.get(),
    private val novelRepository: NovelRepository = Injekt.get(),
) : StateScreenModel<NovelLibraryScreenModel.State>(State()) {

    private val _searchQuery = MutableStateFlow<String?>(null)

    init {
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

    fun loadLibrary() {
        screenModelScope.launch {
            val categories = categoryStorage.loadAllCategories()
            val books = BookStorage.loadAllBooks(app)
            val sourceNovels = novelRepository.getFavorites()
            
            mutableState.update { 
                it.copy(
                    isLoading = false,
                    categories = categories.toImmutableList(),
                    books = books.toImmutableList(),
                    sourceNovels = sourceNovels.toImmutableList(),
                )
            }
        }
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

    fun deleteSelected() {
        screenModelScope.launch {
            val state = mutableState.value
            val selected = state.selection.toSet()
            state.books.filter { it.id in selected }.forEach { book ->
                BookStorage.deleteBook(app, book.id)
            }
            state.sourceNovels.filter { sourceNovelLibraryItemId(it.id) in selected }.forEach { novel ->
                novelRepository.deleteNovel(novel.id)
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
                val result = BookImporter.importEpub(app, uri, categoryIds)
                if (result.metadata != null) imported++ else errors++
            }
            loadLibrary()
            mutableState.update { it.copy(isImporting = false, importResult = Pair(imported, errors)) }
        }
    }

    fun resetStatsForSelected() {
        screenModelScope.launch {
            val state = mutableState.value
            state.selection.forEach { bookId ->
                val bookDir = BookStorage.getBookDirectory(app, bookId)
                // Delete statistics file — BookStorage.save will recreate it fresh on next read
                val statsFile = java.io.File(bookDir, com.canopus.chimareader.data.FileNames.statistics)
                if (statsFile.exists()) statsFile.delete()
                // Also delete bookmark so reading position resets
                val bookmarkFile = java.io.File(bookDir, com.canopus.chimareader.data.FileNames.bookmark)
                if (bookmarkFile.exists()) bookmarkFile.delete()
            }
            clearSelection()
        }
    }

    fun showChangeCategoryDialog() {
        val s = mutableState.value
        val selectedLocalBooks = s.books.filter { it.id in s.selection.toSet() }
        val userCategories = s.categories.filterNot { it.isSystemCategory }

        val commonCategoryIds = if (selectedLocalBooks.isEmpty()) {
            emptySet()
        } else {
            selectedLocalBooks.map { it.categoryIds.toSet() }
                .reduce { set1, set2 -> set1.intersect(set2) }
        }
        val commonCategories = userCategories.filter { it.id in commonCategoryIds }

        val mixCategoryIds = if (selectedLocalBooks.isEmpty()) {
            emptySet()
        } else {
            selectedLocalBooks.flatMap { it.categoryIds }.distinct().toSet() - commonCategoryIds
        }
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
            books.forEach { book ->
                val bookDir = BookStorage.getBookDirectory(app, book.id)
                val currentIds = book.categoryIds.toSet()
                val addIds = addCategories.mapNotNull { id ->
                    s.categories.find { it.id.hashCode().toLong() == id }?.id
                }.toSet()
                val removeIds = removeCategories.mapNotNull { id ->
                    s.categories.find { it.id.hashCode().toLong() == id }?.id
                }.toSet()
                var newIds = (currentIds - removeIds + addIds)
                newIds = if (newIds.any { it != NovelCategory.UNCATEGORIZED_ID }) {
                    newIds - setOf(NovelCategory.UNCATEGORIZED_ID)
                } else {
                    setOf(NovelCategory.UNCATEGORIZED_ID)
                }
                BookStorage.saveMetadata(book.copy(categoryIds = newIds.toList()), bookDir)
            }
            loadLibrary()
        }
    }

    fun showDeleteConfirmDialog() {
        mutableState.update { it.copy(dialog = Dialog.DeleteConfirm) }
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
            val bookDir = BookStorage.getBookDirectory(app, book.id)
            BookStorage.saveMetadata(book, bookDir)
            
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
    ) {
        val hasActiveFilters: Boolean = false
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
                .filter { query == null || it.title.contains(query, ignoreCase = true) }
                .map { NovelLibraryItem.SourceNovel(it) }

            val knownCategoryIds = categories.map { it.id }.toSet()
            val allItems = (localItems + sourceItems).filter { item ->
                when (item) {
                    is NovelLibraryItem.LocalBook -> {
                        val ids = item.metadata.normalizedCategoryIds(knownCategoryIds)
                        if (category.isSystemCategory) {
                            ids.isEmpty() || ids.contains(NovelCategory.UNCATEGORIZED_ID)
                        } else {
                            ids.contains(category.id)
                        }
                    }
                    is NovelLibraryItem.SourceNovel -> category.isSystemCategory
                }
            }
            val comparator = compareBy<NovelLibraryItem>({ it.title.lowercase() }, { it.id })
            return if (sortDescending) allItems.sortedWith(comparator.reversed()) else allItems.sortedWith(comparator)
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