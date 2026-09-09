package eu.kanade.presentation.history

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.core.util.insertSeparators
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.ui.history.novel.NovelHistoryScreenModel
import tachiyomi.domain.manga.model.MangaCover as DomainMangaCover
import eu.kanade.tachiyomi.util.lang.toLocalDate
import eu.kanade.tachiyomi.util.lang.toTimestampString
import tachiyomi.domain.novel.model.NovelHistoryEntry
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.time.LocalDate
import java.util.Date

sealed interface NovelHistoryUiModel {
    data class Item(val entry: NovelHistoryEntry) : NovelHistoryUiModel
    data class Header(val date: LocalDate) : NovelHistoryUiModel
}

fun List<NovelHistoryEntry>.toUiModels(): List<NovelHistoryUiModel> {
    return map { NovelHistoryUiModel.Item(it) }
        .insertSeparators { before, after ->
            val beforeDate = (before as? NovelHistoryUiModel.Item)?.entry?.lastRead?.toLocalDate()
            val afterDate = (after as? NovelHistoryUiModel.Item)?.entry?.lastRead?.toLocalDate()
            when {
                beforeDate != afterDate && afterDate != null -> NovelHistoryUiModel.Header(afterDate)
                else -> null
            }
        }
}

private val NovelHistoryItemHeight = 96.dp

/**
 * Reading history list for novels: one entry per novel (latest read first),
 * manga HistoryItem parity — tap resumes, cover opens detail, trash deletes.
 */
@Composable
fun NovelHistoryScreen(
    state: NovelHistoryScreenModel.State,
    contentPadding: PaddingValues,
    onClickResume: (NovelHistoryEntry) -> Unit,
    onClickCover: (NovelHistoryEntry) -> Unit,
    onClickDelete: (NovelHistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiModels = remember(state.list) { state.list.toUiModels() }
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        items(uiModels, key = {
            when (it) {
                is NovelHistoryUiModel.Item -> "history-${it.entry.novelId}"
                is NovelHistoryUiModel.Header -> "header-${it.date}"
            }
        }) { uiModel ->
            when (uiModel) {
                is NovelHistoryUiModel.Header -> {
                    Text(
                        text = uiModel.date.toString(),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
                    )
                }
                is NovelHistoryUiModel.Item -> {
                    NovelHistoryItem(
                        entry = uiModel.entry,
                        onClick = { onClickResume(uiModel.entry) },
                        onClickCover = { onClickCover(uiModel.entry) },
                        onClickDelete = { onClickDelete(uiModel.entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun NovelHistoryItem(
    entry: NovelHistoryEntry,
    onClick: () -> Unit,
    onClickCover: () -> Unit,
    onClickDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val latest = entry.latest
    // Manga HistoryItem parity: fully-read entries dim like read chapters.
    val textAlpha = if (entry.hasUnread) 1f else DISABLED_ALPHA
    Row(
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClickDelete()
                },
            )
            .height(NovelHistoryItemHeight)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            modifier = Modifier.fillMaxHeight(),
            data = DomainMangaCover(
                mangaId = entry.novelId,
                sourceId = entry.source,
                isMangaFavorite = entry.favorite,
                ogUrl = entry.thumbnailUrl,
                lastModified = 0L,
            ),
            onClick = onClickCover,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.medium, end = MaterialTheme.padding.small),
        ) {
            Text(
                text = entry.title,
                color = LocalContentColor.current.copy(alpha = textAlpha),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            val readAt = remember(latest.lastRead) { Date(latest.lastRead).toTimestampString() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                if (entry.hasUnread) {
                    Icon(
                        imageVector = Icons.Filled.Circle,
                        contentDescription = null,
                        modifier = Modifier
                            .height(8.dp)
                            .padding(end = 4.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = stringResource(
                        MR.strings.recent_manga_time,
                        formatChapterNumber(latest.chapterNumber.toDouble()),
                        readAt,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = textAlpha),
                )
                // Progress tail shows only for a halfway chapter — never "0%"
                // on untouched ones. Percent (reflowable text has no pages),
                // plus position when known.
                if (!latest.chapterRead && latest.chapterProgress > 0.0) {
                    val percent =
                        (latest.chapterProgress.coerceIn(0.0, 1.0) * 100).toInt()
                    val position = if (entry.latestIndex >= 0 && entry.totalCount > 0) {
                        " · Ch. ${entry.latestIndex + 1} of ${entry.totalCount}"
                    } else {
                        ""
                    }
                    DotSeparatorText()
                    Text(
                        text = "$percent%$position",
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = textAlpha),
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        IconButton(onClick = onClickDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(MR.strings.action_delete),
            )
        }
    }
}
