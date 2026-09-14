package eu.kanade.presentation.entries.anime.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.EmptyResultItem
import eu.kanade.presentation.library.components.AnimeComfortableGridItem
import eu.kanade.presentation.library.components.CommonAnimeItemDefaults
import eu.kanade.tachiyomi.ui.entries.anime.RelatedAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.asAnimeCover
import tachiyomi.presentation.core.components.material.padding

@Composable
fun RelatedAnimeRow(
    relatedAnime: List<RelatedAnime>?,
    onAnimeClick: (Anime) -> Unit,
    onAnimeLongClick: (Anime) -> Unit,
) {
    when {
        relatedAnime == null -> {
            LazyRow(
                contentPadding = PaddingValues(MaterialTheme.padding.small),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                item {
                    RelatedAnimeLoadingItem()
                }
            }
        }

        else -> {
            val animes = relatedAnime.filterIsInstance<RelatedAnime.Success>().flatMap { it.animeList }
            val loading = relatedAnime.filterIsInstance<RelatedAnime.Loading>().isNotEmpty()

            if (animes.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(MaterialTheme.padding.small),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                ) {
                    items(animes, key = { "related-anime-${it.id}" }) { anime ->
                        Box(modifier = Modifier.width(104.dp)) {
                            AnimeComfortableGridItem(
                                title = anime.title,
                                titleMaxLines = 3,
                                coverData = anime.asAnimeCover(),
                                coverAlpha = if (anime.favorite) CommonAnimeItemDefaults.BrowseFavoriteCoverAlpha else 1f,
                                onClick = { onAnimeClick(anime) },
                                onLongClick = { onAnimeLongClick(anime) },
                            )
                        }
                    }
                    if (loading) {
                        item {
                            RelatedAnimeLoadingItem()
                        }
                    }
                }
            } else if (!loading) {
                EmptyResultItem()
            } else {
                LazyRow(
                    contentPadding = PaddingValues(MaterialTheme.padding.small),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                ) {
                    item {
                        RelatedAnimeLoadingItem()
                    }
                }
            }
        }
    }
}

@Composable
private fun RelatedAnimeLoadingItem() {
    Box(
        modifier = Modifier
            .width(96.dp)
            .aspectRatio(AnimeCover.Book.ratio)
            .padding(vertical = MaterialTheme.padding.medium),
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(16.dp)
                .align(Alignment.Center),
            strokeWidth = 2.dp,
        )
    }
}
