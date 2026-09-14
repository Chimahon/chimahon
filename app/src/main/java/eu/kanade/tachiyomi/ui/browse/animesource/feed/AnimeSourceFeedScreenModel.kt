package eu.kanade.tachiyomi.ui.browse.animesource.feed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.icerock.moko.resources.StringResource
import eu.kanade.core.preference.asState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.browse.anime.AnimeFeedItemUI
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.ui.browse.animefeed.AnimeMaxFeedItems
import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.domain.entries.anime.model.toSAnime
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
import exh.util.nullIfBlank
import tachiyomi.domain.entries.anime.model.Anime as DomainAnime
import tachiyomi.domain.source.anime.interactor.CountAnimeFeedSavedSearchBySourceId
import tachiyomi.domain.source.anime.interactor.DeleteAnimeFeedSavedSearchById
import tachiyomi.domain.source.anime.interactor.GetAnimeFeedSavedSearchBySourceId
import tachiyomi.domain.source.anime.interactor.GetAnimeSavedSearchBySourceIdFeed
import tachiyomi.domain.source.anime.interactor.InsertAnimeFeedSavedSearch
import tachiyomi.domain.source.anime.interactor.ReorderAnimeFeed
import tachiyomi.domain.source.anime.model.AnimeFeedSavedSearch
import tachiyomi.domain.source.anime.model.AnimeSavedSearch
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import xyz.nulldev.ts.api.http.serializer.AnimeFilterSerializer
import java.util.concurrent.Executors

open class AnimeSourceFeedScreenModel(
    val sourceId: Long,
    uiPreferences: UiPreferences = Injekt.get(),
    sourceManager: AnimeSourceManager = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
    getFeedSavedSearchBySourceId: GetAnimeFeedSavedSearchBySourceId = Injekt.get(),
    private val getSavedSearchBySourceIdFeed: GetAnimeSavedSearchBySourceIdFeed = Injekt.get(),
    private val countFeedSavedSearchBySourceId: CountAnimeFeedSavedSearchBySourceId = Injekt.get(),
    private val insertFeedSavedSearch: InsertAnimeFeedSavedSearch = Injekt.get(),
    private val deleteFeedSavedSearchById: DeleteAnimeFeedSavedSearchById = Injekt.get(),
    private val reorderFeed: ReorderAnimeFeed = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
) : StateScreenModel<AnimeSourceFeedState>(AnimeSourceFeedState()) {

    var source = sourceManager.getOrStub(sourceId)

    private val coroutineDispatcher = Executors.newFixedThreadPool(5).asCoroutineDispatcher()

    val startExpanded by uiPreferences.expandFilters().asState(screenModelScope)

    init {
        setFilters((source as? AnimeCatalogueSource)?.getFilterList() ?: AnimeFilterList())

        getFeedSavedSearchBySourceId.subscribe(source.id)
            .onEach {
                val items = getSourcesToGetFeed(it)
                mutableState.update { state ->
                    state.copy(
                        items = items,
                    )
                }
                getFeed(items)
            }
            .launchIn(screenModelScope)
    }

    fun resetFilters() {
        val catalogueSource = source as? AnimeCatalogueSource ?: return
        setFilters(catalogueSource.getFilterList())
    }

    fun setFilters(filters: AnimeFilterList) {
        mutableState.update { it.copy(filters = filters) }
    }

    private suspend fun hasTooManyFeeds(): Boolean {
        return countFeedSavedSearchBySourceId.await(source.id) > AnimeMaxFeedItems
    }

    fun createFeed(savedSearchId: Long) {
        screenModelScope.launchNonCancellable {
            insertFeedSavedSearch.await(
                AnimeFeedSavedSearch(
                    id = -1,
                    source = source.id,
                    savedSearch = savedSearchId,
                    global = false,
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
            reorderFeed.changeOrder(feed, newIndex, false)
        }
    }

    private suspend fun getSourcesToGetFeed(feedSavedSearch: List<AnimeFeedSavedSearch>): ImmutableList<AnimeSourceFeedUI> {
        val savedSearches = getSavedSearchBySourceIdFeed.await(source.id)
            .associateBy { it.id }

        return (
            listOfNotNull(
                if ((source as? AnimeCatalogueSource)?.supportsLatest == true) {
                    AnimeSourceFeedUI.Latest(null)
                } else {
                    null
                },
                AnimeSourceFeedUI.Browse(null),
            ) + feedSavedSearch
                .map { AnimeSourceFeedUI.AnimeSourceSavedSearch(it, savedSearches[it.savedSearch]!!, null) }
            )
            .toImmutableList()
    }

    private val hideInLibraryFeedItems = sourcePreferences.hideInLibraryFeedItems().get()

    /**
     * Initiates get anime per feed.
     */
    private fun getFeed(feedSavedSearch: List<AnimeSourceFeedUI>) {
        screenModelScope.launch {
            feedSavedSearch.map { sourceFeed ->
                async {
                    val page = try {
                        withContext(coroutineDispatcher) {
                            val catalogueSource = source as? AnimeCatalogueSource
                            val animesPage = when (sourceFeed) {
                                is AnimeSourceFeedUI.Browse -> catalogueSource?.getPopularAnime(1)
                                is AnimeSourceFeedUI.Latest -> catalogueSource?.getLatestUpdates(1)
                                is AnimeSourceFeedUI.AnimeSourceSavedSearch -> catalogueSource?.getSearchAnime(
                                    page = 1,
                                    query = sourceFeed.savedSearch.query?.sanitize().orEmpty(),
                                    filters = catalogueSource?.let { getFilterList(sourceFeed.savedSearch, it) }
                                        ?: AnimeFilterList(),
                                )
                            }
                            animesPage?.animes.orEmpty()
                        }
                    } catch (_: Exception) {
                        emptyList()
                    } catch (e: LinkageError) {
                        emptyList()
                    }

                    val titles = withIOContext {
                        page.map { it.toDomainAnime(source.id) }
                            .distinctBy { it.url }
                            .map { networkToLocalAnime.await(it) }
                            .filter { !hideInLibraryFeedItems || !it.favorite }
                    }

                    mutableState.update { state ->
                        state.copy(
                            items = state.items.map { item ->
                                if (item.id == sourceFeed.id) sourceFeed.withResults(titles) else item
                            }.toImmutableList(),
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

    fun search(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun openFilterSheet() {
        mutableState.update { it.copy(dialog = Dialog.Filter) }
    }

    fun onFilter(onBrowseClick: (query: String?, filters: String?) -> Unit) {
        screenModelScope.launchIO {
            val allDefault = state.value.filters == (source as? AnimeCatalogueSource)?.getFilterList()
            dismissDialog()
            if (allDefault) {
                onBrowseClick(
                    state.value.searchQuery?.nullIfBlank(),
                    null,
                )
            } else {
                onBrowseClick(
                    state.value.searchQuery?.nullIfBlank(),
                    Json.encodeToString(filterSerializer.serialize(state.value.filters)),
                )
            }
        }
    }

    fun openDeleteFeed(feed: AnimeFeedSavedSearch) {
        mutableState.update { it.copy(dialog = Dialog.DeleteFeed(feed)) }
    }

    fun openActionsDialog(
        feed: AnimeSourceFeedUI.AnimeSourceSavedSearch,
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
        data object Filter : Dialog()
        data class DeleteFeed(val feed: AnimeFeedSavedSearch) : Dialog()
        data class AddFeed(val feedId: Long, val name: String) : Dialog()

        data class FeedActions(
            val feedItem: AnimeSourceFeedUI.AnimeSourceSavedSearch,
        ) : Dialog()
    }

    override fun onDispose() {
        super.onDispose()
        coroutineDispatcher.close()
    }
}

@Immutable
data class AnimeSourceFeedState(
    val searchQuery: String? = null,
    val items: ImmutableList<AnimeSourceFeedUI> = persistentListOf(),
    val filters: AnimeFilterList = AnimeFilterList(),
    val dialog: AnimeSourceFeedScreenModel.Dialog? = null,
) {
    val isLoading
        get() = items.isEmpty()
}

sealed interface AnimeSourceFeedUI {
    abstract val id: Long
    abstract val title: Any
    abstract val results: List<DomainAnime>?
    abstract fun withResults(results: List<DomainAnime>?): AnimeSourceFeedUI

    data class Latest(override val results: List<DomainAnime>?) : AnimeSourceFeedUI {
        override val id: Long = -1
        override val title: dev.icerock.moko.resources.StringResource
            get() = tachiyomi.i18n.MR.strings.latest

        override fun withResults(results: List<DomainAnime>?): AnimeSourceFeedUI {
            return copy(results = results)
        }
    }

    data class Browse(override val results: List<DomainAnime>?) : AnimeSourceFeedUI {
        override val id: Long = -2
        override val title: dev.icerock.moko.resources.StringResource
            get() = tachiyomi.i18n.MR.strings.browse

        override fun withResults(results: List<DomainAnime>?): AnimeSourceFeedUI {
            return copy(results = results)
        }
    }

    data class AnimeSourceSavedSearch(
        val feed: AnimeFeedSavedSearch,
        val savedSearch: AnimeSavedSearch,
        override val results: List<DomainAnime>?,
    ) : AnimeSourceFeedUI {
        override val id: Long
            get() = feed.id

        override val title: String
            get() = savedSearch.name

        override fun withResults(results: List<DomainAnime>?): AnimeSourceFeedUI {
            return copy(results = results)
        }
    }
}
