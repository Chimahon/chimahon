package eu.kanade.presentation.more.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.entries.anime.components.AnimeCover
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.manga.components.MangaCoverHide
import eu.kanade.tachiyomi.ui.stats.StatsTitleItem
import eu.kanade.tachiyomi.ui.stats.StatsTitlesState
import eu.kanade.tachiyomi.util.lang.toCountString
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.asAnimeCover
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import java.io.File
import java.time.format.DateTimeFormatter

@Composable
fun StatsTitlesContent(
    state: StatsTitlesState.Success,
    paddingValues: PaddingValues,
    onTitleClick: (StatsTitleItem) -> Unit,
) {
    if (state.titles.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No titles found matching this profile.",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        contentPadding = paddingValues,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.titles, key = { "${it.isNovel}_${it.isAnime}_${it.id}" }) { item ->
            StatsTitleListItem(
                item = item,
                onClick = { onTitleClick(item) },
            )
        }
    }
}

@Composable
private fun StatsTitleListItem(
    item: StatsTitleItem,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Cover Art
        Box(
            modifier = Modifier
                .width(56.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
        ) {
            when {
                item.isNovel -> {
                    val file = item.coverData as? File
                    if (file != null && file.exists()) {
                        AsyncImage(
                            model = file,
                            contentDescription = item.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        MangaCoverHide.Book(
                            modifier = Modifier.fillMaxSize(),
                            size = MangaCover.Size.Medium,
                        )
                    }
                }
                item.isAnime -> {
                    val anime = item.coverData as? Anime
                    if (anime != null) {
                        AnimeCover.Book(
                            modifier = Modifier.fillMaxSize(),
                            data = anime.asAnimeCover(),
                            size = AnimeCover.Size.Medium,
                        )
                    } else {
                        MangaCoverHide.Book(
                            modifier = Modifier.fillMaxSize(),
                            size = MangaCover.Size.Medium,
                        )
                    }
                }
                else -> {
                    val manga = item.coverData as? Manga
                    if (manga != null) {
                        MangaCover.Book(
                            modifier = Modifier.fillMaxSize(),
                            data = manga.asMangaCover(),
                            size = MangaCover.Size.Medium,
                        )
                    } else {
                        MangaCoverHide.Book(
                            modifier = Modifier.fillMaxSize(),
                            size = MangaCover.Size.Medium,
                        )
                    }
                }
            }
        }

        // Details
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp, end = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            item.author?.let { author ->
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            
            val infoText = buildString {
                if (item.readDurationMs > 0) {
                    append(formatDuration(item.readDurationMs))
                    append(if (item.isAnime) " watched" else " read")
                    if (item.charactersRead > 0) {
                        append(" • ")
                        append(item.charactersRead.toCountString())
                        append(" characters")
                    }
                } else if (item.lastReadDate != null) {
                    append("Last read: ")
                    append(item.lastReadDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy")))
                } else {
                    append("Unread")
                }
            }
            Text(
                text = infoText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val hours = durationMs / 3600000
    val minutes = (durationMs % 3600000) / 60000
    return buildString {
        if (hours > 0) append("${hours}h ")
        append("${minutes}m")
    }
}
