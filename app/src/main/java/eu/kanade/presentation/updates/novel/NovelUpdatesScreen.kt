package eu.kanade.presentation.updates.novel

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.entries.components.ItemCover
import tachiyomi.domain.updates.novel.model.NovelUpdatesWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ListGroupHeader
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun NovelUpdatesScreen(
    isLoading: Boolean,
    items: List<NovelUpdatesWithRelations>,
    onClickItem: (NovelUpdatesWithRelations) -> Unit,
) {
    if (isLoading) {
        LoadingScreen()
        return
    }
    if (items.isEmpty()) {
        EmptyScreen(stringRes = MR.strings.information_no_recent_manga)
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items.groupBy { it.dateFetch / 86_400_000L }.forEach { (_, updates) ->
            item(key = "novelUpdatesHeader-${updates.first().chapterId}") {
                ListGroupHeader(text = relativeDateText(updates.first().dateFetch))
            }
            items(
                items = updates,
                key = { "novelUpdates-${it.novelId}-${it.chapterId}" },
            ) { update ->
                NovelUpdatesUiItem(
                    update = update,
                    onClick = { onClickItem(update) },
                )
            }
        }
    }
}

@Composable
private fun NovelUpdatesUiItem(
    update: NovelUpdatesWithRelations,
    onClick: () -> Unit,
) {
    val textAlpha = if (update.read) DISABLED_ALPHA else 1f

    Row(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = null)
            .height(56.dp)
            .padding(horizontal = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemCover.Book(
            modifier = Modifier
                .padding(vertical = 6.dp)
                .fillMaxHeight(),
            data = update.coverData,
        )
        Column(
            modifier = Modifier
                .padding(horizontal = MaterialTheme.padding.medium)
                .weight(1f),
        ) {
            Text(
                text = update.novelTitle,
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = textAlpha),
                overflow = TextOverflow.Ellipsis,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!update.read) {
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = stringResource(MR.strings.unread),
                        modifier = Modifier
                            .height(8.dp)
                            .padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                if (update.bookmark) {
                    Icon(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = stringResource(MR.strings.action_filter_bookmarked),
                        modifier = Modifier
                            .height(14.dp)
                            .padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = update.chapterName,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = textAlpha),
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
