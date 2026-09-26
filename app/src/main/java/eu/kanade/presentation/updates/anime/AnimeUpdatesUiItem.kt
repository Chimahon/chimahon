package eu.kanade.presentation.updates.anime

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.entries.anime.components.AnimeCover
import eu.kanade.presentation.entries.anime.components.EpisodeDownloadAction
import eu.kanade.presentation.entries.anime.components.EpisodeDownloadIndicator
import eu.kanade.presentation.entries.anime.components.RatioSwitchToPanorama
import eu.kanade.presentation.entries.anime.components.getSwipeAction
import eu.kanade.presentation.entries.anime.components.swipeActionThreshold
import eu.kanade.presentation.entries.components.DotSeparatorText
import eu.kanade.presentation.updates.CollapseButton
import eu.kanade.presentation.util.animateItemFastScroll
import eu.kanade.presentation.util.relativeTimeSpanString
import eu.kanade.tachiyomi.data.animedownload.model.AnimeDownload
import eu.kanade.tachiyomi.ui.updates.anime.AnimeUpdatesItem
import eu.kanade.tachiyomi.ui.updates.anime.groupByDateAndAnime
import me.saket.swipe.SwipeableActionsBox
import mihon.feature.upcoming.DateHeading
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.updates.anime.model.AnimeUpdatesWithRelations
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground
import java.util.concurrent.TimeUnit

internal fun LazyListScope.animeUpdatesLastUpdatedItem(
    lastUpdated: Long,
) {
    item(key = "animeUpdates-lastUpdated") {
        Box(
            modifier = Modifier
                .animateItem(fadeInSpec = null, fadeOutSpec = null)
                .padding(
                    horizontal = MaterialTheme.padding.medium,
                    vertical = MaterialTheme.padding.small,
                ),
        ) {
            Text(
                text = stringResource(MR.strings.updates_last_update_info, relativeTimeSpanString(lastUpdated)),
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

internal fun LazyListScope.animeUpdatesUiItems(
    uiModels: List<AnimeUpdatesUiModel>,
    expandedState: Set<String>,
    collapseToggle: (key: String) -> Unit,
    usePanoramaCover: Boolean,
    selectionMode: Boolean,
    onUpdateSelected: (AnimeUpdatesItem, Boolean, Boolean, Boolean) -> Unit,
    onClickCover: (AnimeUpdatesItem) -> Unit,
    onClickUpdate: (AnimeUpdatesItem, altPlayer: Boolean) -> Unit,
    onDownloadEpisode: (List<AnimeUpdatesItem>, EpisodeDownloadAction) -> Unit,
    updateSwipeStartAction: LibraryPreferences.EpisodeSwipeAction,
    updateSwipeEndAction: LibraryPreferences.EpisodeSwipeAction,
    onUpdateSwipe: (AnimeUpdatesItem, LibraryPreferences.EpisodeSwipeAction) -> Unit,
) {
    items(
        items = uiModels,
        contentType = {
            when (it) {
                is AnimeUpdatesUiModel.Header -> "header"
                is AnimeUpdatesUiModel.Item -> "item"
            }
        },
        key = {
            when (it) {
                is AnimeUpdatesUiModel.Header -> "animeUpdatesHeader-${it.hashCode()}"
                is AnimeUpdatesUiModel.Item -> "animeUpdates-${it.item.update.animeId}-${it.item.update.episodeId}"
            }
        },
    ) { item ->
        when (item) {
            is AnimeUpdatesUiModel.Header -> {
                DateHeading(
                    date = item.date,
                    mangaCount = item.animeCount,
                    modifier = Modifier.animateItemFastScroll()
                        .padding(top = MaterialTheme.padding.extraSmall),
                )
            }
            is AnimeUpdatesUiModel.Item -> {
                val updatesItem = item.item
                val isLeader = item is AnimeUpdatesUiModel.Leader
                val isExpanded = expandedState.contains(updatesItem.update.groupByDateAndAnime())

                AnimatedVisibility(
                    visible = isLeader || isExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    AnimeUpdatesUiItem(
                        modifier = Modifier.animateItemFastScroll(),
                        update = updatesItem.update,
                        selected = updatesItem.selected,
                        watchProgress = updatesItem.update.lastSecondSeen
                            .takeIf { !updatesItem.update.seen && it > 0L }
                            ?.let {
                                stringResource(
                                    MR.strings.episode_progress,
                                    formatProgress(it),
                                    formatProgress(updatesItem.update.totalSeconds),
                                )
                            },
                        onLongClick = {
                            onUpdateSelected(updatesItem, !updatesItem.selected, true, true)
                        },
                        onClick = {
                            when {
                                selectionMode -> onUpdateSelected(
                                    updatesItem,
                                    !updatesItem.selected,
                                    true,
                                    false,
                                )
                                else -> onClickUpdate(updatesItem, false)
                            }
                        },
                        onClickCover = { onClickCover(updatesItem) }.takeIf { !selectionMode },
                        onDownloadEpisode = { action: EpisodeDownloadAction ->
                            onDownloadEpisode(listOf(updatesItem), action)
                        }.takeIf { !selectionMode },
                        downloadStateProvider = updatesItem.downloadStateProvider,
                        downloadProgressProvider = updatesItem.downloadProgressProvider,
                        updateSwipeStartAction = updateSwipeStartAction,
                        updateSwipeEndAction = updateSwipeEndAction,
                        onUpdateSwipe = { onUpdateSwipe(updatesItem, it) },
                        isLeader = isLeader,
                        isExpandable = item.isExpandable,
                        expanded = isExpanded,
                        collapseToggle = collapseToggle,
                        usePanoramaCover = usePanoramaCover,
                    )
                }
            }
        }
    }
}

@Composable
private fun AnimeUpdatesUiItem(
    update: AnimeUpdatesWithRelations,
    selected: Boolean,
    watchProgress: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickCover: (() -> Unit)?,
    onDownloadEpisode: ((EpisodeDownloadAction) -> Unit)?,
    // Download Indicator
    downloadStateProvider: () -> AnimeDownload.State,
    downloadProgressProvider: () -> Int,
    // Swipe
    updateSwipeStartAction: LibraryPreferences.EpisodeSwipeAction,
    updateSwipeEndAction: LibraryPreferences.EpisodeSwipeAction,
    onUpdateSwipe: (LibraryPreferences.EpisodeSwipeAction) -> Unit,
    // Grouping
    isLeader: Boolean,
    isExpandable: Boolean,
    expanded: Boolean,
    collapseToggle: (key: String) -> Unit,
    usePanoramaCover: Boolean,
    modifier: Modifier = Modifier,
    coverRatio: MutableFloatState = remember { mutableFloatStateOf(1f) },
) {
    val haptic = LocalHapticFeedback.current
    val textAlpha = if (update.seen) DISABLED_ALPHA else 1f

    val swipeBackground = MaterialTheme.colorScheme.primaryContainer
    val swipeStart = remember(
        updateSwipeStartAction,
        update.seen,
        update.bookmark,
        update.fillermark,
        downloadStateProvider(),
    ) {
        getSwipeAction(
            action = updateSwipeStartAction,
            seen = update.seen,
            bookmark = update.bookmark,
            fillermark = update.fillermark,
            downloadState = downloadStateProvider(),
            background = swipeBackground,
            onSwipe = { onUpdateSwipe(updateSwipeStartAction) },
        )
    }
    val swipeEnd = remember(
        updateSwipeEndAction,
        update.seen,
        update.bookmark,
        update.fillermark,
        downloadStateProvider(),
    ) {
        getSwipeAction(
            action = updateSwipeEndAction,
            seen = update.seen,
            bookmark = update.bookmark,
            fillermark = update.fillermark,
            downloadState = downloadStateProvider(),
            background = swipeBackground,
            onSwipe = { onUpdateSwipe(updateSwipeEndAction) },
        )
    }

    SwipeableActionsBox(
        modifier = modifier.clipToBounds(),
        startActions = listOfNotNull(swipeStart),
        endActions = listOfNotNull(swipeEnd),
        swipeThreshold = swipeActionThreshold,
        backgroundUntilSwipeThreshold = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(
            modifier = Modifier
                .selectedBackground(selected)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    },
                )
                .padding(top = if (isLeader) MaterialTheme.padding.small else 0.dp)
                .padding(
                    vertical = if (isLeader) MaterialTheme.padding.extraSmall else 0.dp,
                    horizontal = MaterialTheme.padding.medium,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val animeCover = update.coverData
            val coverIsWide = coverRatio.floatValue <= RatioSwitchToPanorama
            val bgColor = animeCover.dominantCoverColors?.first?.let { Color(it) }
            val onBgColor = animeCover.dominantCoverColors?.second
            if (isLeader) {
                if (usePanoramaCover && coverIsWide) {
                    AnimeCover.Panorama(
                        modifier = Modifier.width(AnimeUpdateItemPanoramaWidth),
                        data = animeCover,
                        onClick = onClickCover,
                        bgColor = bgColor,
                        tint = onBgColor,
                        size = AnimeCover.Size.Medium,
                        onCoverLoaded = { _, result ->
                            val image = result.result.image
                            coverRatio.floatValue = image.height.toFloat() / image.width
                        },
                    )
                } else {
                    AnimeCover.Square(
                        modifier = Modifier
                            .padding(vertical = 6.dp)
                            .width(AnimeUpdateItemWidth),
                        data = animeCover,
                        onClick = onClickCover,
                        bgColor = bgColor,
                        tint = onBgColor,
                        size = AnimeCover.Size.Medium,
                        onCoverLoaded = { _, result ->
                            val image = result.result.image
                            coverRatio.floatValue = image.height.toFloat() / image.width
                        },
                    )
                }
            } else {
                Box(
                    modifier = Modifier.width(
                        if (usePanoramaCover && coverIsWide) {
                            AnimeUpdateItemPanoramaWidth
                        } else {
                            AnimeUpdateItemWidth
                        },
                    ),
                )
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = MaterialTheme.padding.medium)
                    .weight(1f),
            ) {
                if (isLeader) {
                    Text(
                        text = update.animeTitle,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalContentColor.current.copy(alpha = textAlpha),
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    var textHeight by remember { mutableIntStateOf(0) }
                    if (!update.seen) {
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
                                .sizeIn(
                                    maxHeight = with(LocalDensity.current) { textHeight.toDp() - 2.dp },
                                ),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    if (update.fillermark) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Label,
                            contentDescription = stringResource(AMR.strings.action_filter_fillermarked),
                            modifier = Modifier
                                .sizeIn(
                                    maxHeight = with(LocalDensity.current) { textHeight.toDp() - 2.dp },
                                ),
                            tint = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    Text(
                        text = update.episodeName,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = textAlpha),
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { textHeight = it.size.height },
                        modifier = Modifier
                            .weight(weight = 1f, fill = false),
                    )
                    if (watchProgress != null) {
                        DotSeparatorText()
                        Text(
                            text = watchProgress,
                            maxLines = 1,
                            color = LocalContentColor.current.copy(alpha = DISABLED_ALPHA),
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (isLeader && isExpandable) {
                CollapseButton(
                    expanded = expanded,
                    collapseToggle = { collapseToggle(update.groupByDateAndAnime()) },
                )
            }

            EpisodeDownloadIndicator(
                enabled = onDownloadEpisode != null,
                modifier = Modifier.padding(start = 4.dp),
                downloadStateProvider = downloadStateProvider,
                downloadProgressProvider = downloadProgressProvider,
                fileSize = null,
                onClick = { onDownloadEpisode?.invoke(it) },
            )
        }
    }
}

private val AnimeUpdateItemPanoramaWidth = 108.dp
private val AnimeUpdateItemWidth = 48.dp

private fun formatProgress(milliseconds: Long): String {
    return if (milliseconds > 3600000L) {
        String.format(
            "%d:%02d:%02d",
            TimeUnit.MILLISECONDS.toHours(milliseconds),
            TimeUnit.MILLISECONDS.toMinutes(milliseconds) -
                TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(milliseconds)),
            TimeUnit.MILLISECONDS.toSeconds(milliseconds) -
                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(milliseconds)),
        )
    } else {
        String.format(
            "%d:%02d",
            TimeUnit.MILLISECONDS.toMinutes(milliseconds),
            TimeUnit.MILLISECONDS.toSeconds(milliseconds) -
                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(milliseconds)),
        )
    }
}
