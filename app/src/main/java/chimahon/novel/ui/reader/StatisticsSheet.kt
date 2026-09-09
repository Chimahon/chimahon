package chimahon.novel.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsSheet(
    viewModel: ReaderViewModel,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scrollState = rememberScrollState()
    val trackerState = viewModel.statisticsTracker.state
    val session = trackerState.session
    val today = trackerState.today
    val allTime = trackerState.allTime

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Statistics",
                    style = MaterialTheme.typography.headlineSmall,
                )
                IconButton(onClick = { viewModel.togglePause() }) {
                    Icon(
                        imageVector = if (!trackerState.isTracking) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = if (!trackerState.isTracking) "Resume Timer" else "Pause Timer",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Section(title = "Session") {
                StatRow("Characters Read", session.charactersRead.toString())
                StatRow("Reading Speed", "${session.lastReadingSpeed} / h")
                StatRow("Reading Time", formatDuration(session.readingTime.toLong()))

                // Use frozen position for projections when tracking is paused
                // so page flips during pause don't shift the ETA
                val projectionChar = if (trackerState.isTracking) {
                    viewModel.currentCharacter
                } else {
                    viewModel.statisticsTracker.frozenPosition
                }

                val bookTimeRemaining = secondsRemaining(
                    viewModel.totalCharacters - projectionChar,
                    session.lastReadingSpeed,
                )
                StatRow("Time to finish Book", formatDurationSeconds(bookTimeRemaining))

                val chapterTimeRemaining = secondsRemaining(
                    viewModel.currentChapterEndCharacter - projectionChar,
                    session.lastReadingSpeed,
                )
                StatRow("Time to finish Chapter", formatDurationSeconds(chapterTimeRemaining))
            }

            Section(title = "Today") {
                StatRow("Characters Read", today.charactersRead.toString())
                StatRow("Reading Speed", "${today.lastReadingSpeed} / h")
                StatRow("Reading Time", formatDuration(today.readingTime.toLong()))
            }

            Section(title = "All Time") {
                StatRow("Characters Read", allTime.charactersRead.toString())
                StatRow("Reading Speed", "${allTime.lastReadingSpeed} / h")
                StatRow("Reading Time", formatDuration(allTime.readingTime.toLong()))
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / (60 * 60)

    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun formatDurationSeconds(seconds: Double): String {
    val totalSeconds = maxOf(seconds.toLong(), 0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val remainingSeconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m ${remainingSeconds}s"
        minutes > 0 -> "${minutes}m ${remainingSeconds}s"
        else -> "${remainingSeconds}s"
    }
}

private fun secondsRemaining(remainingCharacters: Int, speed: Int): Double {
    if (speed <= 0) return 0.0
    return maxOf(remainingCharacters, 0).toDouble() / (speed.toDouble() / 3600.0)
}
