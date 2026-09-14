package exh.recs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.produceState
import cafe.adriel.voyager.core.model.StateScreenModel
import eu.kanade.presentation.util.ioCoroutineScope
import exh.recs.sources.ANIME_RECOMMENDS_SOURCE
import exh.recs.sources.AnimeRecommendationPagingSource
import exh.recs.sources.AnimeRecommendationSource
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import eu.kanade.domain.entries.anime.model.toDomainAnime
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.anime.model.Anime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

open class AnimeRecommendsScreenModel(
    private val args: AnimeRecommendsScreen.Args,
    private val getAnime: GetAnime = Injekt.get(),
    protected val networkToLocalAnime: NetworkToLocalAnime = Injekt.get(),
) : StateScreenModel<AnimeRecommendsScreenModel.State>(State()) {

    private val coroutineDispatcher = Dispatchers.IO.limitedParallelism(5)

    private val sortComparator = { map: Map<AnimeRecommendationPagingSource, AnimeRecommendationItemResult> ->
        compareBy<AnimeRecommendationPagingSource>(
            { (map[it] as? AnimeRecommendationItemResult.Success)?.isEmpty ?: true },
            { it.name },
            { it.category.resourceId },
        )
    }

    init {
        ioCoroutineScope.launch {
            val recommendationSources = when (args) {
                is AnimeRecommendsScreen.Args.SingleSourceAnime -> {
                    val anime = getAnime.await(args.animeId) ?: return@launch
                    mutableState.update { it.copy(title = anime.title) }

                    AnimeRecommendationPagingSource.createSources(
                        anime,
                        AnimeRecommendationSource(args.sourceId),
                    )
                }
            }

            updateItems(
                recommendationSources
                    .associateWith { AnimeRecommendationItemResult.Loading }
                    .toPersistentMap(),
            )

            recommendationSources.map { recSource ->
                async {
                    if (state.value.items[recSource] !is AnimeRecommendationItemResult.Loading) {
                        return@async
                    }

                    try {
                        val page = withContext(coroutineDispatcher) {
                            recSource.requestNextPage(1)
                        }

                        val recSourceId = recSource.associatedSourceId
                        val titles = if (recSourceId != null) {
                            // If the recommendation is associated with a source, resolve it
                            page.animes.map { it.toDomainAnime(recSourceId) }
                                .map { networkToLocalAnime.await(it) }
                        } else {
                            // Otherwise, skip this step. The user will be prompted to choose a source via global search
                            page.animes.map { it.toDomainAnime(ANIME_RECOMMENDS_SOURCE) }
                        }
                            .distinctBy { it.url }

                        if (isActive) {
                            updateItem(recSource, AnimeRecommendationItemResult.Success(titles))
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            updateItem(recSource, AnimeRecommendationItemResult.Error(e))
                        }
                    }
                }
            }.awaitAll()
        }
    }

    @Composable
    fun getAnime(initialAnime: Anime): androidx.compose.runtime.State<Anime> {
        return produceState(initialValue = initialAnime) {
            getAnime.subscribe(initialAnime.url, initialAnime.source)
                .filterNotNull()
                .collectLatest { anime ->
                    value = anime
                }
        }
    }

    private fun updateItems(items: PersistentMap<AnimeRecommendationPagingSource, AnimeRecommendationItemResult>) {
        mutableState.update {
            it.copy(
                items = items
                    .toSortedMap(sortComparator(items))
                    .toPersistentMap(),
            )
        }
    }

    private fun updateItem(source: AnimeRecommendationPagingSource, result: AnimeRecommendationItemResult) {
        val newItems = state.value.items.mutate {
            it[source] = result
        }
        updateItems(newItems)
    }

    @Immutable
    data class State(
        val title: String? = null,
        val items: PersistentMap<AnimeRecommendationPagingSource, AnimeRecommendationItemResult> = persistentMapOf(),
    ) {
        val progress: Int = items.count { it.value !is AnimeRecommendationItemResult.Loading }
        val total: Int = items.size
        val filteredItems = items.filter { (_, result) -> result.isVisible(false) }
            .toImmutableMap()
    }
}

sealed interface AnimeRecommendationItemResult {
    data object Loading : AnimeRecommendationItemResult

    data class Error(
        val throwable: Throwable,
    ) : AnimeRecommendationItemResult

    data class Success(
        val result: List<Anime>,
    ) : AnimeRecommendationItemResult {
        val isEmpty: Boolean
            get() = result.isEmpty()
    }

    fun isVisible(onlyShowHasResults: Boolean): Boolean {
        return !onlyShowHasResults || (this is Success && !this.isEmpty)
    }
}
