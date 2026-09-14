package exh.recs.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.browse.anime.components.GlobalAnimeSearchCardRow
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.GlobalSearchResultItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.formattedMessage
import exh.recs.AnimeRecommendationItemResult
import exh.recs.AnimeRecommendsScreenModel
import exh.recs.sources.AnimeRecommendationPagingSource
import kotlinx.collections.immutable.ImmutableMap
import nl.adaptivity.xmlutil.core.impl.multiplatform.name
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun AnimeRecommendsScreen(
    title: String,
    state: AnimeRecommendsScreenModel.State,
    navigateUp: () -> Unit,
    getAnime: @Composable (Anime) -> State<Anime>,
    onClickSource: (AnimeRecommendationPagingSource) -> Unit,
    onClickItem: (Anime) -> Unit,
    onLongClickItem: (Anime) -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = title,
                scrollBehavior = scrollBehavior,
                navigateUp = navigateUp,
            )
        },
    ) { paddingValues ->
        AnimeRecommendsContent(
            items = state.filteredItems,
            contentPadding = paddingValues,
            getAnime = getAnime,
            onClickSource = onClickSource,
            onClickItem = onClickItem,
            onLongClickItem = onLongClickItem,
        )
    }
}

@Composable
internal fun AnimeRecommendsContent(
    items: ImmutableMap<AnimeRecommendationPagingSource, AnimeRecommendationItemResult>,
    contentPadding: PaddingValues,
    getAnime: @Composable (Anime) -> State<Anime>,
    onClickSource: (AnimeRecommendationPagingSource) -> Unit,
    onClickItem: (Anime) -> Unit,
    onLongClickItem: (Anime) -> Unit,
) {
    LazyColumn(
        contentPadding = contentPadding,
    ) {
        items.forEach { (source, recResult) ->
            item(key = "${source::class.name}-${source.name}-${source.category.resourceId}") {
                GlobalSearchResultItem(
                    title = source.name,
                    subtitle = stringResource(source.category),
                    onClick = { onClickSource(source) },
                ) {
                    when (recResult) {
                        AnimeRecommendationItemResult.Loading -> {
                            GlobalSearchLoadingResultItem()
                        }
                        is AnimeRecommendationItemResult.Success -> {
                            GlobalAnimeSearchCardRow(
                                titles = recResult.result,
                                getAnime = getAnime,
                                onClick = onClickItem,
                                onLongClick = onLongClickItem,
                            )
                        }
                        is AnimeRecommendationItemResult.Error -> {
                            GlobalSearchErrorResultItem(
                                message = with(LocalContext.current) {
                                    recResult.throwable.formattedMessage
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
