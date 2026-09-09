package chimahon.novel.ui.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.abs

/**
 * Between-chapters info page: finished / next (or previous / current) cards,
 * download checkmarks, end cards. The initial open shows a loading card
 * instead (spinner + chapter, never the Finished/Next pair twice). No
 * buttons: it turns like a page — swiping further along the travel direction
 * continues, swiping back returns — with the same forward/back directions as
 * the reader itself per text mode.
 * The overlay locks all input while up (drags consumed, taps swallowed) so
 * scrolling or tapping mid-load does nothing.
 * Surface pairs the text with the reader background (a bare Box would inherit
 * the app theme's content color and go unreadable on dark reader themes).
 */
@Composable
fun NovelChapterTransitionOverlay(
    transition: NovelChapterTransition,
    fromTitle: String?,
    toTitle: String?,
    fromDownloaded: Boolean,
    toDownloaded: Boolean,
    continuousMode: Boolean,
    verticalWriting: Boolean,
    onContinue: () -> Unit,
    onRetry: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    val density = LocalDensity.current
    val thresholdPx = with(density) { 64.dp.toPx() }
    var dragTotal by remember(transition) { mutableStateOf(Offset.Zero) }
    // Full input lock while up: drags are consumed below, and taps are
    // swallowed here (a disabled clickable would let them fall through to
    // the WebView's tap zones and turn the chapter mid-load).
    val tapSwallower = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                enabled = true,
                indication = null,
                interactionSource = tapSwallower,
                onClick = {},
            )
            .pointerInput(transition, continuousMode, verticalWriting) {
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        dragTotal += drag
                    },
                    onDragEnd = {
                        // The continue gesture follows the direction of movement:
                        // swiping further along the travel direction continues,
                        // swiping back returns. (NEXT: forward continues, back
                        // dismisses; PREV: back continues, forward dismisses.)
                        // The reader's own gesture map is untouched — this only
                        // mirrors its forward/back directions per text mode.
                        val continueSwipe = if (transition.direction == NovelTurnDirection.PREV) {
                            Swipe.Back
                        } else {
                            Swipe.Forward
                        }
                        when (resolveSwipe(dragTotal, thresholdPx, continuousMode, verticalWriting)) {
                            continueSwipe -> when {
                                // Loading: hold the spinner, don't dismiss — bailing
                                // used to cancel the fill and strand the turn.
                                transition.isLoading -> Unit
                                transition.error != null -> onRetry?.invoke()
                                transition.toIndex != null -> onContinue()
                                else -> onDismiss()
                            }
                            Swipe.Forward, Swipe.Back -> onDismiss()
                            null -> Unit
                        }
                        dragTotal = Offset.Zero
                    },
                    onDragCancel = { dragTotal = Offset.Zero },
                )
            },
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
                    if (transition.fromIndex == transition.toIndex) {
                        // Initial open, not a turn: a single loading card.
                        // The old Finished/Next pair showed the same chapter
                        // twice here.
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            ChapterText(
                                header = stringResource(MR.strings.loading),
                                name = fromTitle,
                                downloaded = fromDownloaded,
                            )
                        }
                    } else when (transition.direction) {
                        NovelTurnDirection.PREV -> {
                            if (transition.toIndex != null) {
                                ChapterText(
                                    header = "Previous",
                                    name = toTitle,
                                    downloaded = toDownloaded,
                                )
                                Spacer(Modifier.height(24.dp))
                            } else {
                                EndCard(text = "There's no previous chapter")
                            }
                            ChapterText(
                                header = "Current",
                                name = fromTitle,
                                downloaded = fromDownloaded,
                            )
                        }
                        NovelTurnDirection.NEXT -> {
                            ChapterText(
                                header = "Finished",
                                name = fromTitle,
                                downloaded = fromDownloaded,
                            )
                            Spacer(Modifier.height(24.dp))
                            if (transition.toIndex != null) {
                                ChapterText(
                                    header = "Next",
                                    name = toTitle,
                                    downloaded = toDownloaded,
                                )
                            } else {
                                EndCard(text = "There's no next chapter")
                            }
                        }
                    }
                }

                if (transition.error != null) {
                    Spacer(Modifier.height(24.dp))
                    ErrorCard(
                        text = transition.error,
                        onRetry = onRetry,
                    )
                }
            }
        }
    }
}

private enum class Swipe { Forward, Back }

/** Same direction map as the reader's own fling handling. */
private fun resolveSwipe(
    total: Offset,
    thresholdPx: Float,
    continuousMode: Boolean,
    verticalWriting: Boolean,
): Swipe? {
    val dx = total.x
    val dy = total.y
    return if (continuousMode && !verticalWriting) {
        if (abs(dy) < thresholdPx || abs(dy) < abs(dx)) return null
        if (dy < 0f) Swipe.Forward else Swipe.Back
    } else {
        if (abs(dx) < thresholdPx || abs(dx) < abs(dy)) return null
        val forward = if (verticalWriting) dx > 0f else dx < 0f
        if (forward) Swipe.Forward else Swipe.Back
    }
}

@Composable
private fun ChapterText(
    header: String,
    name: String?,
    downloaded: Boolean,
) {
    Column {
        Text(
            text = header,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (downloaded) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Downloaded",
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(
                text = name?.takeIf { it.isNotBlank() } ?: "Unknown chapter",
                fontSize = 20.sp,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun EndCard(
    text: String,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier,
        colors = androidx.compose.material3.CardDefaults.outlinedCardColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                tint = MaterialTheme.colorScheme.primary,
                contentDescription = null,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ErrorCard(
    text: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(
        modifier = modifier.clickable(enabled = onRetry != null, onClick = { onRetry?.invoke() }),
        colors = androidx.compose.material3.CardDefaults.outlinedCardColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                tint = MaterialTheme.colorScheme.error,
                contentDescription = null,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

enum class NovelTurnDirection { NEXT, PREV }

/**
 * Between-chapters transition state (manga ChapterTransition equivalent).
 * [toIndex] null means the novel edge (no next/previous chapter card).
 */
data class NovelChapterTransition(
    val fromIndex: Int,
    val toIndex: Int?,
    val direction: NovelTurnDirection,
    val isLoading: Boolean = false,
    val error: String? = null,
)
