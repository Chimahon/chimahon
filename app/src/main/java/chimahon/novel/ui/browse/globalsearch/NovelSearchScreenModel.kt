package chimahon.novel.ui.browse.globalsearch

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import chimahon.novel.manager.NovelSourceManager
import chimahon.novel.plugin.isNovelLanguageEnabled
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tachiyomi.core.common.preference.toggle
import tachiyomi.domain.novel.model.Novel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

abstract class NovelSearchScreenModel(
    initialState: State = State(),
    private val preferences: SourcePreferences = Injekt.get(),
    private val novelSourceManager: NovelSourceManager = Injekt.get(),
) : StateScreenModel<NovelSearchScreenModel.State>(initialState) {

    private val searchSemaphore = Semaphore(5)
    @Volatile
    private var searchGeneration = 0
    private var searchJob: Job? = null

    protected val pinnedSources = preferences.pinnedNovelSources().get()

    private var lastQuery: String? = null
    private var lastSourceFilter: NovelSourceFilter? = null

    private val sortComparator = { map: Map<NovelsPageSource, NovelSearchItemResult> ->
        compareBy<NovelsPageSource>(
            { (map[it] as? NovelSearchItemResult.Success)?.isEmpty ?: true },
            { "${it.id}" !in pinnedSources },
            { "${it.name.lowercase()} (${it.lang})" },
        )
    }

    init {
        if (pinnedSources.isEmpty()) {
            mutableState.update { it.copy(sourceFilter = NovelSourceFilter.All) }
        }
        screenModelScope.launch {
            preferences.globalSearchFilterState().changes().collectLatest { state ->
                mutableState.update { it.copy(onlyShowHasResults = state) }
            }
        }
    }

    open fun getEnabledSources(): List<NovelsPageSource> {
        val enabledLanguages = preferences.enabledNovelLanguages().get()
        val disabledSources = preferences.disabledNovelSources().get()
        return novelSourceManager.getCatalogueSources()
            .filter {
                it.id == Novel.LOCAL_SOURCE_ID || it.id == chimahon.novel.opds.OpdsSource.ID ||
                    isNovelLanguageEnabled(it.lang, enabledLanguages)
            }
            .filter { "${it.id}" !in disabledSources }
            .sortedWith(
                compareBy(
                    { "${it.id}" !in pinnedSources },
                    { "${it.name.lowercase()} (${it.lang})" },
                ),
            )
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun setSourceFilter(filter: NovelSourceFilter) {
        mutableState.update { it.copy(sourceFilter = filter) }
        search()
    }

    fun toggleFilterResults() {
        preferences.globalSearchFilterState().toggle()
    }

    fun search(force: Boolean = false) {
        val query = state.value.searchQuery?.trim().orEmpty()
        val sourceFilter = state.value.sourceFilter
        val sameQuery = lastQuery == query
        if (!force && sameQuery && lastSourceFilter == sourceFilter) return

        searchJob?.cancel()
        val generation = ++searchGeneration
        lastQuery = query
        lastSourceFilter = sourceFilter
        if (query.isBlank()) {
            updateItems(persistentMapOf())
            return
        }

        val sources = getEnabledSources().distinctBy { it.id }
        val existingResults = state.value.items
        val items = sources.associateWith { source ->
            if (sameQuery && !force) {
                existingResults[source] as? NovelSearchItemResult.Success ?: NovelSearchItemResult.Loading
            } else {
                NovelSearchItemResult.Loading
            }
        }
        updateItems(items.toPersistentMap())

        searchJob = screenModelScope.launch(Dispatchers.IO) {
            sources.map { source ->
                async {
                    if (items[source] !is NovelSearchItemResult.Loading) return@async
                    try {
                        val page = searchSemaphore.withPermit {
                            source.getSearchNovels(1, query, source.getFilterList())
                        }
                        if (isActive) {
                            updateItem(source, NovelSearchItemResult.Success(page.novels.distinctBy { it.url }), generation)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (isActive) updateItem(source, NovelSearchItemResult.Error(e), generation)
                    } catch (e: LinkageError) {
                        if (isActive) updateItem(source, NovelSearchItemResult.Error(e), generation)
                    }
                }
            }.awaitAll()
        }
    }

    private fun updateItems(items: PersistentMap<NovelsPageSource, NovelSearchItemResult>) {
        mutableState.update {
            it.copy(
                items = items
                    .toSortedMap(sortComparator(items))
                    .toPersistentMap(),
            )
        }
    }

    private fun updateItem(source: NovelsPageSource, result: NovelSearchItemResult, generation: Int) {
        mutableState.update { current ->
            if (generation != searchGeneration || source !in current.items) return@update current
            val items = current.items.put(source, result)
            current.copy(items = items.toSortedMap(sortComparator(items)).toPersistentMap())
        }
    }

    @Immutable
    data class State(
        val searchQuery: String? = null,
        val sourceFilter: NovelSourceFilter = NovelSourceFilter.PinnedOnly,
        val onlyShowHasResults: Boolean = false,
        val items: PersistentMap<NovelsPageSource, NovelSearchItemResult> = persistentMapOf(),
    ) {
        val progress: Int = items.count { it.value !is NovelSearchItemResult.Loading }
        val total: Int = items.size
        val filteredItems = items.filter { (_, result) -> result.isVisible(onlyShowHasResults) }
    }
}

enum class NovelSourceFilter {
    All,
    PinnedOnly,
}

sealed interface NovelSearchItemResult {
    data object Loading : NovelSearchItemResult

    data class Error(
        val throwable: Throwable,
    ) : NovelSearchItemResult

    data class Success(
        val result: List<SNNovel>,
    ) : NovelSearchItemResult {
        val isEmpty: Boolean
            get() = result.isEmpty()
    }

    fun isVisible(onlyShowHasResults: Boolean): Boolean {
        return !onlyShowHasResults || this !is Success || !isEmpty
    }
}
