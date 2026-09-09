package chimahon.novel.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Holds the e-ink refresh (page-turn flash) state for the novel reader.
 * Mirrors the manga reader's DisplayRefreshHost: every [interval]-th turn
 * triggers a full-screen repaint used to force e-ink displays to refresh.
 */
@Stable
class EinkRefreshHost {
    internal var refreshing by mutableStateOf(false)
    private var interval = 1
    private var timesCalled = 0

    fun trigger() {
        if (timesCalled % interval == 0) {
            refreshing = true
        }
        timesCalled += 1
    }

    /**
     * Triggers a refresh immediately, without the every-[interval]-call gating used by [trigger].
     * Used for movement/scroll so each gesture repaints the display.
     */
    fun triggerScroll() {
        refreshing = true
    }

    fun setInterval(value: Int) {
        interval = value.coerceAtLeast(1)
        timesCalled = 0
    }
}

@Composable
fun EinkRefreshOverlay(
    hostState: EinkRefreshHost,
    durationMillis: Int,
    delayMillis: Int,
    color: EinkRefreshColor,
    interval: Int,
    modifier: Modifier = Modifier,
) {
    val refreshing = hostState.refreshing
    var currentColor by remember { mutableStateOf<Color?>(null) }

    LaunchedEffect(interval) {
        hostState.setInterval(interval)
    }

    LaunchedEffect(refreshing) {
        if (!refreshing) {
            currentColor = null
            return@LaunchedEffect
        }

        delay(delayMillis.milliseconds)

        val half = durationMillis.milliseconds / 2
        currentColor = when (color) {
            EinkRefreshColor.WHITE -> Color.White
            else -> Color.Black
        }
        delay(half)
        if (color == EinkRefreshColor.WHITE_BLACK) {
            currentColor = Color.Black
        }
        delay(half)
        hostState.refreshing = false
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        currentColor?.let { drawRect(it) }
    }
}
