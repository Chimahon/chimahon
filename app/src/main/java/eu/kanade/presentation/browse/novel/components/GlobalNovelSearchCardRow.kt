package eu.kanade.presentation.browse.novel.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun GlobalNovelSearchCardRow(
    titles: List<SNNovel>,
    sourceId: Long,
    onClick: (SNNovel) -> Unit,
    onLongClick: (SNNovel) -> Unit,
) {
    if (titles.isEmpty()) {
        Text(
            text = stringResource(MR.strings.no_results_found),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(
                horizontal = MaterialTheme.padding.medium,
                vertical = MaterialTheme.padding.small,
            ),
        )
        return
    }

    LazyRow(
        contentPadding = PaddingValues(MaterialTheme.padding.small),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        items(titles, key = { it.url }) { novel ->
            Box(modifier = Modifier.width(96.dp)) {
                MangaComfortableGridItem(
                    title = novel.title,
                    coverData = MangaCover(
                        mangaId = stableStringHash(novel.url),
                        sourceId = sourceId,
                        isMangaFavorite = false,
                        ogUrl = novel.thumbnail_url,
                        lastModified = 0L,
                    ),
                    usePanoramaCover = false,
                    onClick = { onClick(novel) },
                    onLongClick = { onLongClick(novel) },
                )
            }
        }
    }
}

private fun stableStringHash(input: String): Long {
    val hash = java.security.MessageDigest.getInstance("MD5").digest(input.toByteArray())
    return (0..7).fold(0L) { acc, i ->
        acc or ((hash[i].toLong() and 0xff) shl (8 * (7 - i)))
    } and Long.MAX_VALUE
}
