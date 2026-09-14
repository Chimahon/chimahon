@file:Suppress("PropertyName")

package eu.kanade.tachiyomi.ui.browse.animefeed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.util.fastAny
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.domain.entries.anime.model.toSAnime
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.browse.anime.AnimeFeedItemUI
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import tachiyomi.core.common.util.QuerySanitizer.sanitize
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.anime.model.Anime as DomainAnime
import tachiyomi.domain.source.anime.interactor.CountAnimeFeedSavedSearchGlobal
import tachiyomi.domain.source.anime.interactor.DeleteAnimeFeedSavedSearchById
import tachiyomi.domain.source.anime.interactor.GetAnimeFeedSavedSearchGlobal
import tachiyomi.domain.source.anime.interactor.GetAnimeSavedSearchBySourceId
import tachiyomi.domain.source.anime.interactor.GetAnimeSavedSearchGlobalFeed
import tachiyomi.domain.source.anime.interactor.InsertAnimeFeedSavedSearch
import tachiyomi.domain.source.anime.interactor.ReorderAnimeFeed
import tachiyomi.domain.source.anime.model.AnimeFeedSavedSearch
import tachiyomi.domain.source.anime.model.AnimeSavedSearch
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.ts.api.http.serializer.AnimeFilterSerializer
import java.util.concurrent.Executors

/**
 * Presenter of anime feed tab
 */
open class AnimeFeedScreenModel(
    val sourceManager: AnimeSourceManager = Injekt.get(),
    val sourcePreferences: SourcePreferences = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
    getFeedSavedSearchGlobal: GetAnimeFeedSavedSearchGlobal = Injekt.get(),
    private val getSavedSearchGlobalFeed: GetAnimeSavedSearchGlobalFeed = Injekt.get(),
    private val countFeedSavedSearchGlobal: CountAnimeFeedSavedSearchGlobal = Injekt.get(),
    private val getSavedSearchBySourceId: GetAnimeSavedSearchBySourceId = Injekt.get(),
    private val insertFeedSavedSearch: InsertAnimeFeedSavedSearch = Injekt.get(),
    private val deleteFeedSavedSearchById: DeleteAnimeFeedSavedSearchById = Injekt.get(),
    private val reorderFeed: ReorderAnimeFeed = Injekt.get(),
) : StateScreenModel<AnimeFeedScreenState>(AnimeFeedScreenState()) {

    private val _events = Channel<Event>(Int.MAX_VALUE)
    val events = _events.receiveAsFlow()

    private val coroutineDispatcher = Executors.newFixedThreadPool(1).asCoroutineDispatcher()
    var pushed: Boolean = false

    init {
        getFeedSavedSearchGlobal.subscribe()
            .distinctUntilChanged()
            .onEach {
                sourceManager.isInitialized.first { it }
                val items = getSourcesToGetFeed(it).map { (feed, savedSearch) ->
                    createCatalogueSearchItem(
                        feed = feed,
                        savedSearch = savedSearch,
                        source = sourceManager.get(feed.source),
                        results = null,
                    )
                }
                mutableState.update { state ->
                    state.copy(
                        items = items
                            .toImmutableList(),
                    )
                }
                getFeed(items)
            }
            .catch { _events.send(Event.FailedFetchingSources) }
            .launchIn(screenModelScope)
    }

    fun init() {
        pushed = false
        screenModelScope.launchIO {
            val newItems = state.value.items?.map { it.copy(results = null) } ?: return@launchIO
            mutableState.update { state ->
                state.copy(
                    items = newItems
                        .toImmutableList(),
                )
            }
            getFeed(newItems)
        }
    }

    fun openAddDialog() {
        screenModelScope.launchIO {
            if (hasTooManyFeeds()) {
                _events.send(Event.TooManyFeeds)
                return@launchIO
            }
            mutableState.update { state ->
                state.copy(
                    dialog = Dialog.AddFeed(getEnabledSources()),
                )
            }
        }
    }

    fun openAddSearchDialog(source: AnimeSource) {
        screenModelScope.launchIO {
            mutableState.update { state ->
                state.copy(
                    dialog = Dialog.AddFeedSearch(
                        source,
                        (
                            persistentListOf(null) +
                                getSourceSavedSearches(source.id)
                            ).toImmutableList(),
                    ),
                )
            }
        }
    }

    fun openDeleteDialog(feed: AnimeFeedSavedSearch) {
        screenModelScope.launchIO {
            mutableState.update { state ->
                state.copy(
                    dialog = Dialog.DeleteFeed(feed),
                )
            }
        }
    }

    fun openActionsDialog(
        feed: AnimeFeedItemUI,
    ) {
        screenModelScope.launchIO {
            mutableState.update { state ->
                state.copy(
                    dialog = Dialog.FeedActions(
                        feedItem = feed,
                    ),
                )
            }
        }
    }

    private suspend fun hasTooManyFeeds(): Boolean {
        return countFeedSavedSearchGlobal.await() > AnimeMaxFeedItems
    }

    private fun getEnabledSources(): ImmutableList<AnimeSource> {
        val languages = sourcePreferences.enabledAnimeLanguages().get()
        val pinnedSources = sourcePreferences.pinnedAnimeSources().get()
        val disabledSources = sourcePreferences.disabledAnimeSources().get()
            .mapNotNull { it.toLongOrNull() }

        val list = sourceManager.getCatalogueSources()
            .filter { it.lang in languages }
            .filterNot { it.id in disabledSources }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { "(${it.lang}) ${it.name}" })

        return list.sortedBy { it.id.toString() !in pinnedSources }.toImmutableList()
    }

    private suspend fun getSourceSavedSearches(sourceId: Long): ImmutableList<AnimeSavedSearch> {
        return getSavedSearchBySourceId.await(sourceId).toImmutableList()
    }

    fun createFeed(source: AnimeSource, savedSearch: AnimeSavedSearch?) {
        screenModelScope.launchNonCancellable {
            insertFeedSavedSearch.await(
                AnimeFeedSavedSearch(
                    id = -1,
                    source = source.id,
                    savedSearch = savedSearch?.id,
                    global = true,
                    feedOrder = 0,
                ),
            )
        }
    }

    fun deleteFeed(feed: AnimeFeedSavedSearch) {
        screenModelScope.launchNonCancellable {
            deleteFeedSavedSearchById.await(feed.id)
        }
    }

    fun changeOrder(feed: AnimeFeedSavedSearch, newIndex: Int) {
        screenModelScope.launch {
            reorderFeed.changeOrder(feed, newIndex)
        }
    }

    private suspend fun getSourcesToGetFeed(feedSavedSearch: List<AnimeFeedSavedSearch>): List<Pair<AnimeFeedSavedSearch, AnimeSavedSearch?>> {
        val savedSearches = getSavedSearchGlobalFeed.await()
            .associateBy { it.id }
        return feedSavedSearch
            .map { it to savedSearches[it.savedSearch] }
    }

    /**
     * Creates a catalogue search item
     */
    private fun createCatalogueSearchItem(
        feed: AnimeFeedSavedSearch,
        savedSearch: AnimeSavedSearch?,
        source: AnimeSource?,
        results: List<DomainAnime>?,
    ): AnimeFeedItemUI {
        return AnimeFeedItemUI(
            feed,
            savedSearch,
            source,
            savedSearch?.name ?: (source?.name ?: feed.source.toString()),
            if (savedSearch != null) {
                source?.name ?: feed.source.toString()
            } else {
                LocaleHelper.getLocalizedDisplayName(source?.lang)
            },
            results,
        )
    }

    private val hideInLibraryFeedItems = sourcePreferences.hideInLibraryFeedItems()

    /**
     * Initiates get anime per feed.
     */
    private fun getFeed(feedSavedSearch: List<AnimeFeedItemUI>) {
        screenModelScope.launch {
            feedSavedSearch.map { itemUI ->
                async {
                    val page = try {
                        if (itemUI.source != null) {
                            withContext(coroutineDispatcher) {
                                if (itemUI.savedSearch == null) {
                                    if ((itemUI.source as? AnimeCatalogueSource)?.supportsLatest == true) {
                                        (itemUI.source as AnimeCatalogueSource).getLatestUpdates(1)
                                    } else {
                                        (itemUI.source as AnimeCatalogueSource).getPopularAnime(1)
                                    }
                                } else {
                                    (itemUI.source as AnimeCatalogueSource).getSearchAnime(
                                        1,
                                        itemUI.savedSearch.query?.sanitize().orEmpty(),
                                        getFilterList(itemUI.savedSearch, itemUI.source as AnimeCatalogueSource),
                                    )
                                }
                            }.animes
                        } else {
                            emptyList()
                        }
                    } catch (_: Exception) {
                        emptyList()
                    } catch (e: LinkageError) {
                        emptyList()
                    }

                    val result = withIOContext {
                        itemUI.copy(
                            results = page
                                .map { it.toDomainAnime(itemUI.source!!.id) }
                                .distinctBy { it.url }
                                .map { networkToLocalAnime.await(it) }
                                .filter { !hideInLibraryFeedItems.get() || !it.favorite },
                        )
                    }

                    mutableState.update { state ->
                        state.copy(
                            items = state.items?.map { if (it.feed.id == result.feed.id) result else it }
                                ?.toImmutableList(),
                        )
                    }
                }
            }.awaitAll()
        }
    }

    private val filterSerializer = AnimeFilterSerializer()

    private fun getFilterList(savedSearch: AnimeSavedSearch, source: AnimeCatalogueSource): AnimeFilterList {
        val filters = savedSearch.filtersJson ?: return AnimeFilterList()
        return runCatching {
            val originalFilters = source.getFilterList()
            filterSerializer.deserialize(
                filters = originalFilters,
                json = Json.decodeFromString(filters),
            )
            originalFilters
        }.getOrElse { AnimeFilterList() }
    }

    @Composable
    fun getAnime(initialAnime: DomainAnime): State<DomainAnime> {
        return produceState(initialValue = initialAnime) {
            getAnime.subscribe(initialAnime.url, initialAnime.source)
                .collectLatest { anime ->
                    if (anime == null) return@collectLatest
                    value = anime
                }
            }
    }

    override fun onDispose() {
        super.onDispose()
        coroutineDispatcher.close()
    }

    fun showDialog(dialog: Dialog) {
        if (!state.value.isLoading) {
            mutableState.update {
                it.copy(dialog = dialog)
            }
        }
    }

    fun dismissDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    sealed class Dialog {
        data class AddFeed(val options: ImmutableList<AnimeSource>) : Dialog()
        data class AddFeedSearch(val source: AnimeSource, val options: ImmutableList<AnimeSavedSearch?>) : Dialog()
        data class DeleteFeed(val feed: AnimeFeedSavedSearch) : Dialog()

        data class FeedActions(
            val feedItem: AnimeFeedItemUI,
        ) : Dialog()
    }

    sealed class Event {
        data object FailedFetchingSources : Event()
        data object TooManyFeeds : Event()
    }
}

data class AnimeFeedScreenState(
    val dialog: AnimeFeedScreenModel.Dialog? = null,
    val items: ImmutableList<AnimeFeedItemUI>? = null,
) {
    val isLoading
        get() = items == null

    val isEmpty
        get() = items.isNullOrEmpty()

    val isLoadingItems
        get() = items?.fastAny { it.results == null } != false
}

const val AnimeMaxFeedItems = 20
