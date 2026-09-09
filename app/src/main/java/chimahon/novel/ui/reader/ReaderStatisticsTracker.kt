package chimahon.novel.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import chimahon.novel.data.Statistics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

data class ReaderStatisticsState(
    val isTracking: Boolean,
    val session: Statistics,
    val today: Statistics,
    val allTime: Statistics,
)

class ReaderStatisticsTracker(
    private val title: String,
    initialStatistics: List<Statistics>,
    private val enabled: Boolean,
) {
    private var statistics = initialStatistics.toMutableList()
    private var lastTimestampMillis: Long = System.currentTimeMillis()
    private var lastCharacterCount: Int = 0
    val frozenPosition: Int get() = lastCharacterCount
    private var hasUpdated = false

    var state: ReaderStatisticsState by mutableStateOf(
        ReaderStatisticsState(
            isTracking = false,
            session = defaultStatistic(),
            today = statisticForDate(currentDateKey()),
            allTime = allTimeStatistic(statistics),
        )
    )
        private set

    fun start(currentCharacter: Int) {
        if (!enabled) return
        state = state.copy(isTracking = true)
        resetBaseline(currentCharacter)
    }

    fun startForPageTurnIfNeeded(currentCharacter: Int) {
        if (!state.isTracking) {
            start(currentCharacter)
        }
    }

    fun stop(currentCharacter: Int) {
        pause(currentCharacter)
    }

    fun pause(currentCharacter: Int): Boolean {
        if (!state.isTracking) return false
        update(currentCharacter)
        state = state.copy(isTracking = false)
        return true
    }

    fun togglePause(currentCharacter: Int) {
        if (state.isTracking) {
            pause(currentCharacter)
        } else {
            start(currentCharacter)
        }
    }

    fun update(currentCharacter: Int) {
        if (!enabled || !state.isTracking) return
        rollTodayIfNeeded()
        val now = System.currentTimeMillis()
        val timeDiff = (now - lastTimestampMillis).toDouble() / 1000.0
        if (timeDiff <= 0.0) return

        val charDiff = currentCharacter - lastCharacterCount
        val finalCharDiff = if (charDiff < 0 && abs(charDiff) > state.session.charactersRead) {
            -state.session.charactersRead
        } else {
            charDiff
        }
        val modified = System.currentTimeMillis()
        state = state.copy(
            session = state.session.updated(timeDiff, finalCharDiff, modified),
            today = state.today.updated(timeDiff, finalCharDiff, modified),
            allTime = state.allTime.updated(timeDiff, finalCharDiff, modified),
        )
        hasUpdated = true
        lastTimestampMillis = now
        lastCharacterCount = currentCharacter
    }

    fun resetBaseline(currentCharacter: Int) {
        lastCharacterCount = currentCharacter
        lastTimestampMillis = System.currentTimeMillis()
    }

    fun statisticsForPersistenceOrNull(): List<Statistics>? =
        if (enabled && (hasUpdated || statistics.isNotEmpty())) statisticsForPersistence() else null

    fun statisticsForPersistence(): List<Statistics> {
        val today = state.today
        val grouped = statistics.groupBy { it.dateKey }.mapValues { (_, entries) ->
            entries.maxBy { it.lastStatisticModified }
        }.toMutableMap()
        grouped[today.dateKey] = today
        val next = grouped.values.toList()
        statistics = next.toMutableList()
        return next
    }

    private fun rollTodayIfNeeded() {
        val key = currentDateKey()
        if (state.today.dateKey == key) return
        statisticsForPersistence()
        state = state.copy(today = statisticForDate(key))
    }

    private fun statisticForDate(dateKey: String): Statistics =
        statistics.firstOrNull { it.dateKey == dateKey } ?: defaultStatistic(dateKey)

    private fun defaultStatistic(dateKey: String = currentDateKey()): Statistics =
        Statistics(title = title, dateKey = dateKey)

    private fun allTimeStatistic(statistics: List<Statistics>): Statistics {
        val base = defaultStatistic()
        return statistics.fold(base) { total, statistic ->
            val readingTime = total.readingTime + statistic.readingTime
            val charactersRead = total.charactersRead + statistic.charactersRead
            total.copy(
                readingTime = readingTime,
                charactersRead = charactersRead,
                lastReadingSpeed = if (readingTime > 0.0) {
                    (charactersRead.toDouble() / readingTime * 3600.0).toInt()
                } else {
                    0
                },
            )
        }
    }

    private fun currentDateKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}

private fun Statistics.updated(
    timeDiff: Double,
    characterDiff: Int,
    lastStatisticModified: Long,
): Statistics {
    val nextReadingTime = readingTime + timeDiff
    val nextCharactersRead = (charactersRead + characterDiff).coerceAtLeast(0)
    val nextReadingSpeed = if (nextReadingTime > 0.0) {
        (nextCharactersRead.toDouble() / nextReadingTime * 3600.0).toInt()
    } else {
        0
    }
    return copy(
        readingTime = nextReadingTime,
        charactersRead = nextCharactersRead,
        lastReadingSpeed = nextReadingSpeed,
        maxReadingSpeed = maxOf(maxReadingSpeed, nextReadingSpeed),
        minReadingSpeed = if (minReadingSpeed != 0) minOf(minReadingSpeed, nextReadingSpeed) else nextReadingSpeed,
        altMinReadingSpeed = if (characterDiff != 0) {
            if (altMinReadingSpeed != 0) minOf(altMinReadingSpeed, nextReadingSpeed) else nextReadingSpeed
        } else {
            altMinReadingSpeed
        },
        lastStatisticModified = lastStatisticModified,
    )
}
