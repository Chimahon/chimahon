package eu.kanade.tachiyomi.ui.player.controls.components.panels

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.player.PlayerViewModel.SubtitleCue
import eu.kanade.tachiyomi.ui.player.controls.SubtitleLookupRequest
import eu.kanade.tachiyomi.ui.player.controls.panelCardsColors
import eu.kanade.tachiyomi.ui.player.utils.SubtitleListEmptyReason
import eu.kanade.tachiyomi.ui.player.utils.SubtitleListTiming
import eu.kanade.tachiyomi.ui.player.utils.SubtitleListTrack
import eu.kanade.tachiyomi.ui.player.utils.filterSubtitleCues
import eu.kanade.tachiyomi.ui.player.utils.formatCueTimestamp
import eu.kanade.tachiyomi.ui.reader.viewer.extractOcrLookupString
import eu.kanade.tachiyomi.ui.reader.viewer.isLookupStartChar
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.abs

@Composable
fun SubtitleListPanel(
    cues: ImmutableList<SubtitleCue>,
    activeCueIndices: ImmutableSet<Int>,
    activeCueIndex: Int?,
    nearestCueIndex: Int?,
    selectedTrack: SubtitleListTrack,
    hasSecondaryTrack: Boolean,
    emptyReason: SubtitleListEmptyReason,
    timing: SubtitleListTiming,
    onSelectTrack: (SubtitleListTrack) -> Unit,
    onSelectCue: (Int) -> Unit,
    onLookupWord: (SubtitleLookupRequest) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismissRequest)

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    val panelModifier = if (isPortrait) {
        val height = (configuration.screenHeightDp.dp * 0.44f)
            .coerceAtLeast(240.dp)
            .coerceAtMost((configuration.screenHeightDp.dp - 48.dp).coerceAtLeast(200.dp))
        Modifier
            .fillMaxWidth()
            .height(height)
    } else {
        val width = (configuration.screenWidthDp.dp * 0.36f)
            .coerceAtLeast(280.dp)
            .coerceAtMost(minOf(430.dp, (configuration.screenWidthDp.dp - 24.dp).coerceAtLeast(220.dp)))
        Modifier
            .width(width)
            .fillMaxHeight()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(MaterialTheme.padding.small),
    ) {
        SubtitleSideListCard(
            cues = cues,
            activeCueIndices = activeCueIndices,
            activeCueIndex = activeCueIndex,
            nearestCueIndex = nearestCueIndex,
            selectedTrack = selectedTrack,
            hasSecondaryTrack = hasSecondaryTrack,
            emptyReason = emptyReason,
            timing = timing,
            onSelectTrack = onSelectTrack,
            onSelectCue = onSelectCue,
            onLookupWord = onLookupWord,
            onClose = onDismissRequest,
            modifier = panelModifier.align(if (isPortrait) Alignment.BottomCenter else Alignment.CenterEnd),
        )
    }
}

@Composable
private fun SubtitleSideListCard(
    cues: ImmutableList<SubtitleCue>,
    activeCueIndices: ImmutableSet<Int>,
    activeCueIndex: Int?,
    nearestCueIndex: Int?,
    selectedTrack: SubtitleListTrack,
    hasSecondaryTrack: Boolean,
    emptyReason: SubtitleListEmptyReason,
    timing: SubtitleListTiming,
    onSelectTrack: (SubtitleListTrack) -> Unit,
    onSelectCue: (Int) -> Unit,
    onLookupWord: (SubtitleLookupRequest) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var ignoreWhitespace by rememberSaveable { mutableStateOf(true) }

    val displayedCues = remember(cues, searchQuery, ignoreWhitespace) {
        filterSubtitleCues(cues, searchQuery, ignoreWhitespace)
    }

    Card(
        colors = panelCardsColors(),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SubtitleListHeader(
                hasSecondaryTrack = hasSecondaryTrack,
                selectedTrack = selectedTrack,
                searchExpanded = searchExpanded,
                onSelectTrack = onSelectTrack,
                onToggleSearch = {
                    if (searchExpanded) {
                        searchQuery = ""
                    }
                    searchExpanded = !searchExpanded
                },
                onClose = onClose,
            )

            AnimatedVisibility(visible = searchExpanded) {
                SubtitleListSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    ignoreWhitespace = ignoreWhitespace,
                    onToggleIgnoreWhitespace = { ignoreWhitespace = !ignoreWhitespace },
                    matchCount = displayedCues.size,
                    showMatchCount = searchQuery.isNotBlank(),
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

            SubtitleCueLazyList(
                cues = displayedCues,
                hasSourceCues = cues.isNotEmpty(),
                isFiltering = searchQuery.isNotBlank(),
                activeCueIndices = activeCueIndices,
                activeCueIndex = activeCueIndex,
                nearestCueIndex = nearestCueIndex,
                selectedTrack = selectedTrack,
                emptyReason = emptyReason,
                timing = timing,
                onSelectCue = onSelectCue,
                onLookupWord = onLookupWord,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SubtitleListHeader(
    hasSecondaryTrack: Boolean,
    selectedTrack: SubtitleListTrack,
    searchExpanded: Boolean,
    onSelectTrack: (SubtitleListTrack) -> Unit,
    onToggleSearch: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = MaterialTheme.padding.medium, end = MaterialTheme.padding.extraSmall, top = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(MR.strings.player_subtitle_list_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleSearch) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(MR.strings.action_search),
                        tint = if (searchExpanded) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(MR.strings.action_close),
                    )
                }
            }
        }

        if (hasSecondaryTrack) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = MaterialTheme.padding.small, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                FilterChip(
                    selected = selectedTrack == SubtitleListTrack.Primary,
                    onClick = { onSelectTrack(SubtitleListTrack.Primary) },
                    label = { Text(stringResource(MR.strings.player_subtitle_list_primary)) },
                )
                FilterChip(
                    selected = selectedTrack == SubtitleListTrack.Secondary,
                    onClick = { onSelectTrack(SubtitleListTrack.Secondary) },
                    label = { Text(stringResource(MR.strings.player_subtitle_list_secondary)) },
                )
            }
        }
    }
}

@Composable
private fun SubtitleListSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    ignoreWhitespace: Boolean,
    onToggleIgnoreWhitespace: () -> Unit,
    matchCount: Int,
    showMatchCount: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.extraSmall),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(MR.strings.player_subtitle_list_search_hint)) },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(MR.strings.action_close),
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FilterChip(
                selected = ignoreWhitespace,
                onClick = onToggleIgnoreWhitespace,
                label = { Text(stringResource(MR.strings.player_subtitle_list_ignore_whitespace)) },
            )
            if (showMatchCount) {
                Text(
                    text = stringResource(MR.strings.player_subtitle_list_matches_count, matchCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SubtitleCueLazyList(
    cues: List<SubtitleCue>,
    hasSourceCues: Boolean,
    isFiltering: Boolean,
    activeCueIndices: ImmutableSet<Int>,
    activeCueIndex: Int?,
    nearestCueIndex: Int?,
    selectedTrack: SubtitleListTrack,
    emptyReason: SubtitleListEmptyReason,
    timing: SubtitleListTiming,
    onSelectCue: (Int) -> Unit,
    onLookupWord: (SubtitleLookupRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var autoFollow by remember(selectedTrack) { mutableStateOf(true) }

    val followTargetIndex = activeCueIndex ?: nearestCueIndex
    val followPosition = remember(cues, followTargetIndex) {
        findTargetListPosition(cues, followTargetIndex)
    }

    val userScrollMonitor = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && abs(available.y) > 0.5f) {
                    autoFollow = false
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(isFiltering) {
        if (!isFiltering) {
            autoFollow = true
        }
    }

    LaunchedEffect(followPosition, autoFollow, isFiltering, cues.size) {
        if (!autoFollow || isFiltering || cues.isEmpty()) return@LaunchedEffect
        val target = followPosition ?: return@LaunchedEffect
        listState.animateScrollToCenteredItem(target)
    }

    if (cues.isEmpty()) {
        EmptySubtitleListMessage(
            hasSourceCues = hasSourceCues,
            isFiltering = isFiltering,
            emptyReason = emptyReason,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val copyCue: (SubtitleCue) -> Unit = { cue ->
        context.copyToClipboard(cue.text, cue.text)
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(userScrollMonitor),
            contentPadding = PaddingValues(
                horizontal = MaterialTheme.padding.small,
                vertical = MaterialTheme.padding.small,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(cues, key = { it.index }) { cue ->
                SubtitleCueSideRow(
                    cue = cue,
                    selected = cue.index in activeCueIndices,
                    timing = timing,
                    onSelect = {
                        autoFollow = true
                        onSelectCue(cue.index)
                    },
                    onCopy = { copyCue(cue) },
                    onLookupWord = onLookupWord,
                )
            }
        }

        if (!autoFollow && !isFiltering && followPosition != null) {
            FilledTonalButton(
                onClick = {
                    autoFollow = true
                    scope.launch {
                        listState.animateScrollToCenteredItem(followPosition)
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = MaterialTheme.padding.small),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(MR.strings.player_subtitle_list_jump_to_current),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

private suspend fun LazyListState.animateScrollToCenteredItem(index: Int) {
    if (index < 0) return
    val visible = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (visible == null) {
        val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset)
            .coerceAtLeast(0)
        val avgItemSize = layoutInfo.visibleItemsInfo
            .takeIf { it.isNotEmpty() }
            ?.let { items -> items.sumOf { it.size } / items.size }
            ?: 0
        val offsetFromTop = ((viewportHeight - avgItemSize) / 2).coerceAtLeast(0)
        scrollToItem(index, -offsetFromTop)
        withFrameNanos { }
    }

    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
    val itemCenter = item.offset + item.size / 2
    val scrollDelta = itemCenter - viewportCenter

    if (abs(scrollDelta) > 2) {
        animateScrollBy(scrollDelta.toFloat())
    }
}

@Composable
private fun SubtitleCueSideRow(
    cue: SubtitleCue,
    selected: Boolean,
    timing: SubtitleListTiming,
    onSelect: () -> Unit,
    onCopy: () -> Unit,
    onLookupWord: (SubtitleLookupRequest) -> Unit,
    modifier: Modifier = Modifier,
) {
    var textLayoutResult by remember(cue.index, cue.text) { mutableStateOf<TextLayoutResult?>(null) }
    var textOrigin by remember { mutableStateOf(Offset.Zero) }
    val timestamp = remember(cue.positionSeconds, timing) {
        formatCueTimestamp(cue, timing)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(activeLineColor(selected))
            .semantics { this.selected = selected }
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = timestamp,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(top = 3.dp),
        )
        Text(
            text = cue.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            onTextLayout = { textLayoutResult = it },
            modifier = Modifier
                .weight(1f)
                .onGloballyPositioned { textOrigin = it.positionInRoot() }
                .pointerInput(cue.index, cue.text, timing) {
                    detectTapGestures(
                        onTap = { onSelect() },
                        onLongPress = { localOffset ->
                            val request = buildCueWordLookupRequest(
                                cue = cue,
                                timing = timing,
                                textLayoutResult = textLayoutResult,
                                textOrigin = textOrigin,
                                localOffset = localOffset,
                            )
                            if (request != null) {
                                onLookupWord(request)
                            } else {
                                onCopy()
                            }
                        },
                    )
                },
        )
        if (selected) {
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(MR.strings.action_copy_to_clipboard),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private fun buildCueWordLookupRequest(
    cue: SubtitleCue,
    timing: SubtitleListTiming,
    textLayoutResult: TextLayoutResult?,
    textOrigin: Offset,
    localOffset: Offset,
): SubtitleLookupRequest? {
    val layout = textLayoutResult ?: return null
    if (cue.text.isEmpty()) return null
    val rawOffset = layout.getOffsetForPosition(localOffset).coerceIn(0, cue.text.lastIndex)
    val startOffset = when {
        isLookupStartChar(cue.text[rawOffset]) -> rawOffset
        rawOffset > 0 && isLookupStartChar(cue.text[rawOffset - 1]) -> rawOffset - 1
        else -> return null
    }
    val lookup = extractOcrLookupString(cue.text, startOffset).take(80).trim()
    if (lookup.isBlank()) return null

    val box = layout.getBoundingBox(startOffset)
    val anchorLeft = textOrigin.x + box.left
    val anchorTop = textOrigin.y + box.top
    val anchorWidth = box.width.coerceAtLeast(1f)
    val anchorHeight = box.height.coerceAtLeast(1f)

    val speed = timing.speed.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    val delay = timing.delaySeconds.takeIf { it.isFinite() } ?: 0.0
    val adjustedStart = (cue.positionSeconds * speed + delay).coerceAtLeast(0.0)
    val adjustedEnd = cue.endPositionSeconds
        ?.takeIf { it.isFinite() && it >= cue.positionSeconds }
        ?.let { (it * speed + delay).coerceAtLeast(adjustedStart) }

    return SubtitleLookupRequest(
        lookupString = lookup,
        fullText = cue.text,
        charOffset = startOffset,
        tapCharOffset = startOffset,
        lineText = cue.text,
        lineIndex = 0,
        lineStartOffset = 0,
        anchorX = anchorLeft,
        anchorY = anchorTop,
        anchorWidth = anchorWidth,
        anchorHeight = anchorHeight,
        lineLeft = anchorLeft,
        lineTop = anchorTop,
        lineWidth = anchorWidth,
        lineHeight = anchorHeight,
        cueStartSeconds = adjustedStart,
        cueEndSeconds = adjustedEnd,
        nativeCueKey = "sublist:${cue.index}:${cue.positionSeconds}",
    )
}

@Composable
private fun EmptySubtitleListMessage(
    hasSourceCues: Boolean,
    isFiltering: Boolean,
    emptyReason: SubtitleListEmptyReason,
    modifier: Modifier = Modifier,
) {
    val message = when {
        isFiltering && hasSourceCues -> stringResource(MR.strings.player_subtitle_list_no_matches)
        emptyReason == SubtitleListEmptyReason.NoTrackSelected ->
            stringResource(MR.strings.player_subtitle_list_empty_no_track)
        emptyReason == SubtitleListEmptyReason.ImageBasedTrack ->
            stringResource(MR.strings.player_subtitle_list_empty_image_track)
        else -> stringResource(MR.strings.player_subtitle_list_empty_waiting)
    }

    Box(
        modifier = modifier.padding(MaterialTheme.padding.medium),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun activeLineColor(selected: Boolean): Color {
    return if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
    } else {
        Color.Transparent
    }
}

private fun findTargetListPosition(cues: List<SubtitleCue>, targetCueIndex: Int?): Int? {
    if (cues.isEmpty() || targetCueIndex == null) return null
    val index = cues.indexOfFirst { it.index == targetCueIndex }
    return index.takeIf { it >= 0 }
}
