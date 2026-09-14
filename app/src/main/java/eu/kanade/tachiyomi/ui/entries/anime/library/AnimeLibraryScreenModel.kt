package eu.kanade.tachiyomi.ui.entries.anime.library

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.preference.asState
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.presentation.entries.DownloadAction
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.tachiyomi.animesource.model.SAnime
import exh.util.isLewd
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.data.animedownload.AnimeDownloadManager
import eu.kanade.tachiyomi.data.cache.AnimeBackgroundCache
import eu.kanade.tachiyomi.data.cache.AnimeCoverCache
import eu.kanade.tachiyomi.data.track.AnimeTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.episode.getNextUnseen
import eu.kanade.tachiyomi.util.removeBackgrounds
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import eu.kanade.core.util.fastFilterNot
import tachiyomi.domain.category.interactor.GetAnimeCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.AnimeCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.entries.anime.interactor.GetLibraryAnime
import tachiyomi.domain.entries.anime.interactor.UpdateAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeUpdate
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.episode.interactor.SetSeenStatus
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.history.interactor.GetNextEpisodes
import tachiyomi.domain.library.model.LibraryAnime
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.model.sort
import tachiyomi.domain.library.service.AnimeLibraryPreferences
import tachiyomi.domain.source.anime.model.AnimeSource as DomainAnimeSource
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.track.anime.interactor.GetTracksPerAnime
import tachiyomi.domain.track.anime.model.AnimeTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.Collator
import java.util.Locale

class AnimeLibraryScreenModel(
    private val getLibraryAnime: GetLibraryAnime = Injekt.get(),
    private val getAnimeCategories: GetAnimeCategories = Injekt.get(),
    private val setAnimeCategories: SetAnimeCategories = Injekt.get(),
    private val updateAnime: UpdateAnime = Injekt.get(),
    private val setSeenStatus: SetSeenStatus = Injekt.get(),
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId = Injekt.get(),
    private val getNextEpisodes: GetNextEpisodes = Injekt.get(),
    private val getTracksPerAnime: GetTracksPerAnime = Injekt.get(),
    private val preferences: AnimeLibraryPreferences = Injekt.get(),
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val downloadManager: AnimeDownloadManager = Injekt.get(),
    private val coverCache: AnimeCoverCache = Injekt.get(),
    private val backgroundCache: AnimeBackgroundCache = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    private val syncPreferences: SyncPreferences = Injekt.get(),
) : StateScreenModel<AnimeLibraryScreenModel.State>(
    State(activeCategoryIndex = preferences.lastUsedCategory().get()),
) {

    init {
        screenModelScope.launchIO {
            combine(
                getLibraryAnime.subscribe(),
                getAnimeCategories.subscribe(),
                state.map { it.searchQuery }.distinctUntilChanged().debounce(SEARCH_DEBOUNCE_MILLIS),
                preferences.sortingMode().changes(),
                preferences.filterUnseen().changes(),
                preferences.filterStarted().changes(),
                preferences.filterBookmarked().changes(),
                preferences.filterCompleted().changes(),
                preferences.filterDownloaded().changes(),
                preferences.filterFillermarked().changes(),
                preferences.groupLibraryBy().changes(),
                preferences.categorizedDisplaySettings().changes(),
                preferences.showHiddenCategories().changes(),
                getTracksPerAnime.subscribe(),
                getTrackingFiltersFlow(),
                preferences.filterLewd().changes(),
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val libraryAnime = values[0] as List<LibraryAnime>
                @Suppress("UNCHECKED_CAST")
                val categories = values[1] as List<AnimeCategory>
                val searchQuery = values[2] as String?
                val sortMode = values[3] as LibrarySort
                val filterUnseen = values[4] as TriState
                val filterStarted = values[5] as TriState
                val filterBookmarked = values[6] as TriState
                val filterCompleted = values[7] as TriState
                val filterDownloaded = values[8] as TriState
                val filterFillermarked = values[9] as TriState
                val groupType = values[10] as Int
                val categorizedDisplaySettings = values[11] as Boolean
                val showHiddenCategories = values[12] as Boolean
                @Suppress("UNCHECKED_CAST")
                val trackMap = values[13] as Map<Long, List<AnimeTrack>>
                @Suppress("UNCHECKED_CAST")
                val trackingFilters = values[14] as Map<Long, TriState>
                val filterLewd = values[15] as TriState

                val items = libraryAnime.map { anime ->
                    val apiSource = sourceManager.getOrStub(anime.anime.source)
                    AnimeLibraryItem(
                        libraryAnime = anime,
                        downloadCount = downloadManager.getDownloadCount(anime.anime).toLong(),
                        unseenCount = anime.unseenCount,
                        isLocal = anime.anime.source == LOCAL_SOURCE_ID,
                        sourceLanguage = apiSource.lang,
                        source = DomainAnimeSource(
                            id = apiSource.id,
                            lang = apiSource.lang,
                            name = apiSource.name,
                            supportsLatest = false,
                            isStub = false,
                        ),
                    )
                }

                val filtered = applyFilters(
                    items,
                    searchQuery,
                    filterUnseen,
                    filterStarted,
                    filterBookmarked,
                    filterCompleted,
                    filterDownloaded,
                    filterFillermarked,
                    filterLewd,
                ).let { applyTrackingFilters(it, trackMap, trackingFilters) }

                val grouped = applyGrouping(filtered, categories, groupType, showHiddenCategories)
                val collator = Collator.getInstance(Locale.getDefault())
                val sorted = grouped.mapValues { (category, list) ->
                    val activeSort = if (groupType == LibraryGroup.BY_DEFAULT && categorizedDisplaySettings) {
                        category.sort
                    } else {
                        sortMode
                    }
                    applySort(list, activeSort, collator, trackMap, trackingFilters.keys)
                }

                val hasActiveFilters = filterUnseen != TriState.DISABLED ||
                    filterStarted != TriState.DISABLED ||
                    filterBookmarked != TriState.DISABLED ||
                    filterCompleted != TriState.DISABLED ||
                    filterDownloaded != TriState.DISABLED ||
                    filterFillermarked != TriState.DISABLED ||
                    filterLewd != TriState.DISABLED

                State(
                    isLoading = false,
                    library = sorted,
                    categories = categories,
                    searchQuery = searchQuery,
                    hasActiveFilters = hasActiveFilters,
                    dialog = null,
                )
            }.collectLatest { newState ->
                mutableState.update { oldState ->
                    newState.copy(
                        selection = oldState.selection,
                        dialog = oldState.dialog,
                        showCategoryTabs = oldState.showCategoryTabs,
                        showAnimeCount = oldState.showAnimeCount,
                        showContinueWatchingButton = oldState.showContinueWatchingButton,
                        activeCategoryIndex = oldState.coercedActiveCategoryIndex,
                    )
                }
            }
        }

        preferences.categoryTabs().changes()
            .onEach { showTabs ->
                mutableState.update { it.copy(showCategoryTabs = showTabs) }
            }
            .launchIn(screenModelScope)

        preferences.categoryNumberOfItems().changes()
            .onEach { show ->
                mutableState.update { it.copy(showAnimeCount = show) }
            }
            .launchIn(screenModelScope)

        preferences.showContinueWatchingButton().changes()
            .onEach { show ->
                mutableState.update { it.copy(showContinueWatchingButton = show) }
            }
            .launchIn(screenModelScope)

        syncPreferences.syncService()
            .changes()
            .distinctUntilChanged()
            .onEach { syncService ->
                mutableState.update { it.copy(isSyncEnabled = syncService != 0) }
            }
            .launchIn(screenModelScope)

    }

    fun search(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun toggleSelection(anime: LibraryAnime) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                if (list.any { it.id == anime.id }) {
                    list.removeAll { it.id == anime.id }
                } else {
                    list.add(anime)
                }
            }
            state.copy(selection = newSelection)
        }
    }

    fun selectAll(categoryIndex: Int) {
        mutableState.update { state ->
            val items = state.library.values.toList().getOrNull(categoryIndex) ?: return@update state
            val newSelection = state.selection.mutate { list ->
                items.forEach { item ->
                    if (list.none { it.id == item.libraryAnime.id }) {
                        list.add(item.libraryAnime)
                    }
                }
            }
            state.copy(selection = newSelection)
        }
    }

    fun invertSelection(categoryIndex: Int) {
        mutableState.update { state ->
            val items = state.library.values.toList().getOrNull(categoryIndex) ?: return@update state
            val newSelection = state.selection.mutate { list ->
                items.forEach { item ->
                    if (list.any { it.id == item.libraryAnime.id }) {
                        list.removeAll { it.id == item.libraryAnime.id }
                    } else {
                        list.add(item.libraryAnime)
                    }
                }
            }
            state.copy(selection = newSelection)
        }
    }

    fun clearSelection() {
        mutableState.update { it.copy(selection = persistentListOf()) }
    }

    fun markSeenSelection(seen: Boolean) {
        val animes = state.value.selection.toList()
        screenModelScope.launchNonCancellable {
            animes.forEach { anime ->
                setSeenStatus.await(
                    animeId = anime.id,
                    seen = seen,
                )
            }
        }
        clearSelection()
    }

    fun setAnimeCategories(animeIds: List<Long>, categoryIds: List<Long>) {
        screenModelScope.launchNonCancellable {
            animeIds.forEach { animeId ->
                setAnimeCategories.await(animeId, categoryIds)
            }
        }
    }

    fun setAnimeCategories(
        animeList: List<Anime>,
        addCategories: List<Long>,
        removeCategories: List<Long>,
    ) {
        screenModelScope.launchNonCancellable {
            animeList.forEach { anime ->
                val categoryIds = getAnimeCategories.await(anime.id)
                    .map { it.id }
                    .subtract(removeCategories.toSet())
                    .plus(addCategories)
                    .distinct()
                    .toList()
                setAnimeCategories.await(anime.id, categoryIds)
            }
        }
    }

    fun openChangeCategoryDialog() {
        screenModelScope.launchIO {
            val animeList = state.value.selection.map { it.anime }
            val categories = state.value.categories
                .filter { it.id != 0L }
                .map { Category(it.id, it.name, it.order, it.flags, it.hidden) }
            val common = getCommonCategories(animeList)
            val mix = getMixCategories(animeList)
            val preselected = categories.map {
                when (it) {
                    in common -> CheckboxState.State.Checked(it)
                    in mix -> CheckboxState.TriState.Exclude(it)
                    else -> CheckboxState.State.None(it)
                }
            }.toImmutableList()
            mutableState.update { it.copy(dialog = Dialog.ChangeCategory(animeList, preselected)) }
        }
    }

    fun updateActiveCategoryIndex(index: Int) {
        val maxIndex = (state.value.displayCategories.size - 1).coerceAtLeast(0)
        val coerced = index.coerceIn(0, maxIndex)
        if (coerced != state.value.coercedActiveCategoryIndex) {
            mutableState.update { it.copy(activeCategoryIndex = coerced) }
            preferences.lastUsedCategory().set(coerced)
        }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    fun showSettingsDialog() {
        mutableState.update { it.copy(dialog = Dialog.SettingsSheet) }
    }

    private suspend fun getCommonCategories(animes: List<Anime>): Collection<Category> {
        if (animes.isEmpty()) return emptyList()
        return animes
            .map { getAnimeCategories.await(it.id).map { a -> Category(a.id, a.name, a.order, a.flags, a.hidden) }.toSet() }
            .reduce { set1, set2 -> set1.intersect(set2) }
    }

    private suspend fun getMixCategories(animes: List<Anime>): Collection<Category> {
        if (animes.isEmpty()) return emptyList()
        val animeCategories = animes
            .map { getAnimeCategories.await(it.id).map { a -> Category(a.id, a.name, a.order, a.flags, a.hidden) }.toSet() }
        val common = animeCategories.reduce { set1, set2 -> set1.intersect(set2) }
        return animeCategories.flatten().distinct().subtract(common)
    }

    fun removeAnime(animeIds: List<Long>) {
        screenModelScope.launchNonCancellable {
            animeIds.forEach { animeId ->
                updateAnime.await(AnimeUpdate(id = animeId, favorite = false))
            }
        }
        clearSelection()
    }

    fun removeAnimes(animeList: List<Anime>, deleteFromLibrary: Boolean, deleteEpisodes: Boolean) {
        screenModelScope.launchNonCancellable {
            val animeToDelete = animeList.distinctBy { it.id }

            if (deleteFromLibrary) {
                val toDelete = animeToDelete.map {
                    it.removeCovers(coverCache)
                    it.removeBackgrounds(backgroundCache)
                    AnimeUpdate(
                        favorite = false,
                        id = it.id,
                    )
                }
                updateAnime.awaitAll(toDelete)
            }

            if (deleteEpisodes) {
                animeToDelete.forEach { anime ->
                    val source = sourceManager.get(anime.source)
                    if (source != null) {
                        downloadManager.deleteAnime(anime, source)
                    }
                }
            }
        }
    }

    fun runDownloadActionSelection(action: DownloadAction) {
        val selection = state.value.selection
        val animes = selection.map { it.anime }.toList()
        when (action) {
            DownloadAction.NEXT_1_ITEM -> downloadUnseenEpisodes(animes, 1)
            DownloadAction.NEXT_5_ITEMS -> downloadUnseenEpisodes(animes, 5)
            DownloadAction.NEXT_10_ITEMS -> downloadUnseenEpisodes(animes, 10)
            DownloadAction.NEXT_25_ITEMS -> downloadUnseenEpisodes(animes, 25)
            DownloadAction.UNVIEWED_ITEMS -> downloadUnseenEpisodes(animes, null)
        }
        clearSelection()
    }

    private fun downloadUnseenEpisodes(animes: List<Anime>, amount: Int?) {
        screenModelScope.launchNonCancellable {
            animes.forEach { anime ->
                val episodes = getNextEpisodes.await(anime.id)
                    .fastFilterNot { episode ->
                        downloadManager.getQueuedDownloadOrNull(episode.id) != null ||
                            downloadManager.isEpisodeDownloaded(
                                episode.name,
                                episode.scanlator,
                                anime.title,
                                anime.source,
                            )
                    }
                    .let { if (amount != null) it.take(amount) else it }

                downloadManager.downloadEpisodes(anime, episodes)
            }
        }
    }

    fun getRandomAnimelibItemForCurrentCategory(page: Int): AnimeLibraryItem? {
        return state.value.getAnimelibItemsByPage(page).randomOrNull()
    }

    fun getRandomAnimelibItemForCurrentCategory(): AnimeLibraryItem? {
        return getRandomAnimelibItemForCurrentCategory(state.value.coercedActiveCategoryIndex)
    }

    fun toggleRangeSelection(anime: LibraryAnime) {
        toggleSelection(anime)
    }

    fun getDisplayMode(): PreferenceMutableState<LibraryDisplayMode> {
        return preferences.displayMode().asState(screenModelScope)
    }

    fun getColumnsPreferenceForCurrentOrientation(isLandscape: Boolean): PreferenceMutableState<Int> {
        return (if (isLandscape) preferences.landscapeColumns() else preferences.portraitColumns())
            .asState(screenModelScope)
    }

    fun resetSelectedInfoFlags() {
        clearSelection()
    }

    suspend fun getNextUnseenEpisode(anime: Anime): Episode? {
        return getEpisodesByAnimeId.await(anime.id).getNextUnseen()
    }

    fun openDeleteAnimeDialog() {
        val animeList = state.value.selection.map { it.anime }
        mutableState.update { it.copy(dialog = Dialog.DeleteAnime(animeList)) }
    }

    private fun applyFilters(
        items: List<AnimeLibraryItem>,
        searchQuery: String?,
        filterUnseen: TriState,
        filterStarted: TriState,
        filterBookmarked: TriState,
        filterCompleted: TriState,
        filterDownloaded: TriState,
        filterFillermarked: TriState,
        filterLewd: TriState,
    ): List<AnimeLibraryItem> {
        var result = items

        if (!searchQuery.isNullOrBlank()) {
            result = result.filter { it.matches(searchQuery) }
        }

        result = applyTriStateFilter(result, filterUnseen) { it.libraryAnime.unseenCount > 0 }
        result = applyTriStateFilter(result, filterStarted) { it.libraryAnime.hasStarted }
        result = applyTriStateFilter(result, filterBookmarked) { it.libraryAnime.hasBookmarks }
        result = applyTriStateFilter(result, filterCompleted) { it.libraryAnime.anime.status == SAnime.COMPLETED.toLong() }
        result = applyTriStateFilter(result, filterFillermarked) { it.libraryAnime.hasFillermarks }
        result = applyTriStateFilter(result, filterDownloaded) { it.downloadCount > 0 || it.isLocal }
        result = applyTriStateFilter(result, filterLewd) { it.libraryAnime.anime.isLewd() }

        return result
    }

    private fun applyTriStateFilter(
        items: List<AnimeLibraryItem>,
        state: TriState,
        predicate: (AnimeLibraryItem) -> Boolean,
    ): List<AnimeLibraryItem> {
        return when (state) {
            TriState.DISABLED -> items
            TriState.ENABLED_IS -> items.filter(predicate)
            TriState.ENABLED_NOT -> items.filterNot(predicate)
        }
    }

    private fun applyTrackingFilters(
        items: List<AnimeLibraryItem>,
        trackMap: Map<Long, List<AnimeTrack>>,
        trackingFilters: Map<Long, TriState>,
    ): List<AnimeLibraryItem> {
        return trackingFilters.entries.fold(items) { filteredItems, (trackerId, state) ->
            applyTriStateFilter(filteredItems, state) { item ->
                trackMap[item.libraryAnime.id].orEmpty().any { it.trackerId == trackerId }
            }
        }
    }

    private fun getTrackingFiltersFlow(): Flow<Map<Long, TriState>> {
        return trackerManager.loggedInAnimeTrackersFlow().flatMapLatest { loggedInTrackers ->
            if (loggedInTrackers.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val filterFlows = loggedInTrackers.map { tracker ->
                    preferences.filterTracking(tracker.id.toInt()).changes().map { tracker.id to it }
                }
                combine(filterFlows) { it.toMap() }
            }
        }
    }

    private fun applyGrouping(
        items: List<AnimeLibraryItem>,
        categories: List<AnimeCategory>,
        groupType: Int,
        showHiddenCategories: Boolean,
    ): Map<AnimeCategory, List<AnimeLibraryItem>> {
        return when (groupType) {
            LibraryGroup.BY_SOURCE -> {
                items.groupBy { sourceManager.getOrStub(it.libraryAnime.anime.source) }
                    .toSortedMap(compareBy { it.name })
                    .entries.mapIndexed { index, (source, animeList) ->
                        AnimeCategory(
                            id = source.id,
                            name = source.name,
                            order = index.toLong(),
                            flags = 0L,
                            hidden = false,
                        ) to animeList
                    }.toMap()
            }
            LibraryGroup.BY_STATUS -> {
                val statusOrder = listOf(
                    SAnime.ONGOING.toLong(),
                    SAnime.COMPLETED.toLong(),
                    SAnime.LICENSED.toLong(),
                    SAnime.ON_HIATUS.toLong(),
                    SAnime.CANCELLED.toLong(),
                )
                items.groupBy { it.libraryAnime.anime.status }
                    .toSortedMap(compareBy { statusOrder.indexOf(it) })
                    .entries.mapIndexed { index, (status, animeList) ->
                        AnimeCategory(
                            id = status,
                            name = statusToString(status),
                            order = index.toLong(),
                            flags = 0L,
                            hidden = false,
                        ) to animeList
                    }.toMap()
            }
            else -> {
                if (categories.isEmpty()) {
                    val defaultCategory = AnimeCategory(
                        id = AnimeCategory.UNCATEGORIZED_ID,
                        name = "Default",
                        order = 0,
                        flags = 0L,
                        hidden = false,
                    )
                    mapOf(defaultCategory to items)
                } else {
                    val uncategorized = items.filter { item ->
                        item.libraryAnime.categories.isEmpty() ||
                            item.libraryAnime.categories == listOf(AnimeCategory.UNCATEGORIZED_ID)
                    }
                    val categorized = categories.filter { !it.isSystemCategory && (showHiddenCategories || !it.hidden) }.associateWith { category ->
                        items.filter { item -> category.id in item.libraryAnime.categories }
                    }
                    val result = mutableMapOf<AnimeCategory, List<AnimeLibraryItem>>()
                    if (uncategorized.isNotEmpty()) {
                        val systemCategory = categories.find { it.isSystemCategory } ?: AnimeCategory(
                            id = AnimeCategory.UNCATEGORIZED_ID,
                            name = "Default",
                            order = -1,
                            flags = 0L,
                            hidden = false,
                        )
                        result[systemCategory] = uncategorized
                    }
                    result.putAll(categorized)
                    result
                }
            }
        }
    }

    private fun applySort(
        items: List<AnimeLibraryItem>,
        sort: LibrarySort,
        collator: Collator,
        trackMap: Map<Long, List<AnimeTrack>> = emptyMap(),
        loggedInTrackerIds: Set<Long> = emptySet(),
    ): List<AnimeLibraryItem> {
        val trackerScores by lazy {
            val trackerMap = trackerManager.getAll(loggedInTrackerIds).associateBy { it.id }
            trackMap.mapValues { entry ->
                entry.value
                    .mapNotNull { track ->
                        (trackerMap[track.trackerId] as? AnimeTracker)?.get10PointScore(track)
                    }
                    .takeIf { it.isNotEmpty() }
                    ?.average()
            }
        }

        val comparator: Comparator<AnimeLibraryItem> = when (sort.type) {
            LibrarySort.Type.Alphabetical -> Comparator { a, b ->
                collator.compare(a.libraryAnime.anime.title, b.libraryAnime.anime.title)
            }
            LibrarySort.Type.LastRead -> compareByDescending { it.libraryAnime.lastSeen }
            LibrarySort.Type.LastUpdate -> compareByDescending { it.libraryAnime.anime.lastUpdate }
            LibrarySort.Type.UnreadCount -> compareByDescending { it.libraryAnime.unseenCount }
            LibrarySort.Type.TotalChapters -> compareByDescending { it.libraryAnime.totalEpisodes }
            LibrarySort.Type.LatestChapter -> compareByDescending { it.libraryAnime.latestUpload }
            LibrarySort.Type.ChapterFetchDate -> compareByDescending { it.libraryAnime.episodeFetchedAt }
            LibrarySort.Type.DateAdded -> compareByDescending { it.libraryAnime.anime.dateAdded }
            LibrarySort.Type.TrackerMean -> compareBy {
                trackerScores[it.libraryAnime.id] ?: DEFAULT_TRACKER_SCORE_SORT_VALUE
            }
            LibrarySort.Type.Random -> {
                val seed = preferences.randomSortSeed().get()
                val random = java.util.Random(seed.toLong())
                val shuffled = items.shuffled(random)
                return shuffled
            }
            else -> compareBy { it.libraryAnime.anime.title }
        }

        val sorted = items.sortedWith(comparator)
        return if (sort.direction == LibrarySort.Direction.Descending) sorted.reversed() else sorted
    }

    private fun statusToString(status: Long): String {
        return when (status.toInt()) {
            SAnime.ONGOING -> "Ongoing"
            SAnime.COMPLETED -> "Completed"
            SAnime.LICENSED -> "Licensed"
            SAnime.ON_HIATUS -> "On Hiatus"
            SAnime.CANCELLED -> "Cancelled"
            else -> "Unknown"
        }
    }

    sealed interface Dialog {
        data object SettingsSheet : Dialog
        data class ChangeCategory(
            val anime: List<Anime>,
            val initialSelection: ImmutableList<CheckboxState<Category>>,
        ) : Dialog
        data class DeleteAnime(val anime: List<Anime>) : Dialog
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val library: Map<AnimeCategory, List<AnimeLibraryItem>> = emptyMap(),
        val categories: List<AnimeCategory> = emptyList(),
        val searchQuery: String? = null,
        val selection: PersistentList<LibraryAnime> = persistentListOf(),
        val hasActiveFilters: Boolean = false,
        val showCategoryTabs: Boolean = true,
        val showAnimeCount: Boolean = false,
        val showContinueWatchingButton: Boolean = false,
        val isSyncEnabled: Boolean = false,
        val dialog: Dialog? = null,
        private val activeCategoryIndex: Int = 0,
    ) {
        val selectionMode = selection.isNotEmpty()
        val showAnimeContinueButton: Boolean
            get() = showContinueWatchingButton

        val displayCategories: List<AnimeCategory> = library.keys.toList()
        val coercedActiveCategoryIndex: Int
            get() = activeCategoryIndex.coerceIn(
                minimumValue = 0,
                maximumValue = displayCategories.lastIndex.coerceAtLeast(0),
            )

        private val libraryCount: Int
            get() = library.values
                .flatten()
                .distinctBy { it.libraryAnime.id }
                .size

        val isLibraryEmpty: Boolean
            get() = libraryCount == 0

        fun getAnimelibItemsByPage(page: Int): List<AnimeLibraryItem> {
            return library.values.toList().getOrElse(page) { emptyList() }
        }

        fun getAnimeCountForCategory(category: AnimeCategory): Int {
            return library[category]?.size ?: 0
        }

        fun getToolbarTitle(
            defaultTitle: String,
            defaultCategoryTitle: String,
            page: Int,
        ): LibraryToolbarTitle {
            val category = displayCategories.getOrNull(page) ?: return LibraryToolbarTitle(defaultTitle)
            if (searchQuery != null) {
                return LibraryToolbarTitle(searchQuery)
            }
            val categoryName = if (category.isSystemCategory) defaultCategoryTitle else category.name
            val title = if (showCategoryTabs) defaultTitle else categoryName
            val numberOfAnime = when {
                !showAnimeCount -> null
                showCategoryTabs -> libraryCount
                else -> getAnimeCountForCategory(category)
            }
            return LibraryToolbarTitle(text = title, numberOfManga = numberOfAnime)
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MILLIS = 250L
        private const val LOCAL_SOURCE_ID = 0L
        private const val DEFAULT_TRACKER_SCORE_SORT_VALUE = -1.0
    }
}
