package eu.kanade.tachiyomi.ui.player.utils

import androidx.compose.runtime.Immutable
import eu.kanade.tachiyomi.ui.player.PlayerViewModel.SubtitleCue
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

enum class SubtitleListTrack {
    Primary,
    Secondary,
}

enum class SubtitleListEmptyReason {
    WaitingForCues,
    NoTrackSelected,
    ImageBasedTrack,
}

@Immutable
data class SubtitleListTiming(
    val speed: Double = 1.0,
    val delaySeconds: Double = 0.0,
)

internal data class ActiveSubtitleCueMatch(
    val activeIndices: Set<Int> = emptySet(),
    val anchorCueIndex: Int? = null,
    val nearestCueIndex: Int? = null,
)

private val IMAGE_SUBTITLE_CODECS = setOf(
    "hdmv_pgs_subtitle",
    "pgs",
    "dvd_subtitle",
    "dvdsub",
    "dvb_subtitle",
    "dvbsub",
    "xsub",
)

private const val MAX_LIVE_CUE_LENGTH = 2_000
private const val MAX_LIVE_CUE_NEWLINES = 15
private const val CUE_START_EPSILON = 0.001
private const val ZERO_DURATION_TOLERANCE = 0.05

internal fun isImageSubtitleCodec(codec: String?): Boolean {
    val normalized = codec?.trim()?.lowercase(Locale.ROOT) ?: return false
    return normalized in IMAGE_SUBTITLE_CODECS
}

internal fun decodeSubtitleCues(rawJson: String?, json: Json): List<SubtitleCue> {
    if (rawJson.isNullOrBlank()) return emptyList()
    return runCatching { json.decodeFromString<List<SubtitleCue>>(rawJson) }.getOrNull().orEmpty()
}

internal fun prepareSubtitleCues(
    rawCues: List<SubtitleCue>,
    options: SubtitleRegexFilterOptions,
): List<SubtitleCue> {
    if (rawCues.isEmpty()) return emptyList()
    return rawCues.mapIndexedNotNull { originalIndex, cue ->
        if (!cue.positionSeconds.isFinite()) return@mapIndexedNotNull null
        val end = cue.endPositionSeconds
        if (end != null && (!end.isFinite() || end < cue.positionSeconds)) {
            return@mapIndexedNotNull null
        }
        val text = cue.text.applySubtitleRegexFilters(options)
        if (text.isBlank()) {
            null
        } else {
            cue.copy(index = originalIndex, text = text)
        }
    }.sortedWith(
        compareBy<SubtitleCue>(
            { it.positionSeconds },
            { it.endPositionSeconds ?: it.positionSeconds },
            { it.index },
        ),
    )
}

internal fun maxSubtitleCueDuration(cues: List<SubtitleCue>): Double {
    var maxDuration = 0.0
    for (cue in cues) {
        val end = cue.endPositionSeconds ?: continue
        val duration = end - cue.positionSeconds
        if (duration > maxDuration) {
            maxDuration = duration
        }
    }
    return maxDuration
}

internal fun findActiveSubtitleCueMatch(
    cues: List<SubtitleCue>,
    maxCueDuration: Double,
    subStart: Double?,
    playbackPosSeconds: Double,
    timing: SubtitleListTiming,
): ActiveSubtitleCueMatch {
    if (cues.isEmpty()) return ActiveSubtitleCueMatch()

    val speed = timing.speed.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    val delay = timing.delaySeconds.takeIf { it.isFinite() } ?: 0.0
    val rawPos = if (playbackPosSeconds.isFinite()) {
        (playbackPosSeconds - delay) / speed
    } else {
        0.0
    }

    val activeIndices = linkedSetOf<Int>()
    val validSubStart = subStart?.takeIf { it.isFinite() }
    if (validSubStart != null) {
        val checkRawPos = rawPos.isFinite() && rawPos >= validSubStart - CUE_START_EPSILON
        val upperTime = if (checkRawPos) maxOf(validSubStart, rawPos) else validSubStart
        val lowerStartFloor = minOf(validSubStart, if (checkRawPos) rawPos else validSubStart) -
            maxCueDuration.coerceAtLeast(0.0) - ZERO_DURATION_TOLERANCE - CUE_START_EPSILON

        val matches = mutableListOf<Int>()
        val upperIdx = upperBoundCueIndex(cues, upperTime + ZERO_DURATION_TOLERANCE)
        for (i in upperIdx - 1 downTo 0) {
            val cue = cues[i]
            if (cue.positionSeconds < lowerStartFloor) break
            if (cueMatchesTime(cue, validSubStart) || (checkRawPos && cueMatchesTime(cue, rawPos))) {
                matches.add(cue.index)
            }
        }
        for (i in matches.lastIndex downTo 0) {
            activeIndices.add(matches[i])
        }
    }

    val anchorCueIndex = activeIndices.lastOrNull()
    val nearestCueIndex = anchorCueIndex ?: findNearestCueIndex(cues, rawPos)
    return ActiveSubtitleCueMatch(
        activeIndices = activeIndices,
        anchorCueIndex = anchorCueIndex,
        nearestCueIndex = nearestCueIndex,
    )
}

private fun cueMatchesTime(cue: SubtitleCue, timeSeconds: Double): Boolean {
    val end = cue.endPositionSeconds
    return if (end == null) {
        abs(cue.positionSeconds - timeSeconds) <= CUE_START_EPSILON
    } else if (end == cue.positionSeconds) {
        abs(cue.positionSeconds - timeSeconds) <= ZERO_DURATION_TOLERANCE
    } else {
        cue.positionSeconds - CUE_START_EPSILON <= timeSeconds && timeSeconds < end
    }
}

private fun findNearestCueIndex(cues: List<SubtitleCue>, rawPosSeconds: Double): Int {
    val upperIdx = upperBoundCueIndex(cues, rawPosSeconds + CUE_START_EPSILON)
    return if (upperIdx > 0) {
        cues[upperIdx - 1].index
    } else {
        cues.first().index
    }
}

private fun upperBoundCueIndex(cues: List<SubtitleCue>, targetStart: Double): Int {
    var low = 0
    var high = cues.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (cues[mid].positionSeconds <= targetStart) {
            low = mid + 1
        } else {
            high = mid
        }
    }
    return low
}

internal fun computeCueSeekTargetSeconds(
    cue: SubtitleCue,
    timing: SubtitleListTiming,
    durationSeconds: Double?,
): Double {
    val speed = timing.speed.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    val delay = timing.delaySeconds.takeIf { it.isFinite() } ?: 0.0
    val start = (cue.positionSeconds * speed + delay).coerceAtLeast(0.0)
    val nudged = cue.endPositionSeconds
        ?.takeIf { it.isFinite() && it >= cue.positionSeconds }
        ?.let { end ->
            val adjustedEnd = (end * speed + delay).coerceAtLeast(start)
            val span = adjustedEnd - start
            start + if (span > 0.02) 0.01 else span * 0.25
        }
        ?: (start + 0.005)
    val validDuration = durationSeconds?.takeIf { it.isFinite() && it > 0.0 }
    return if (validDuration != null) {
        nudged.coerceIn(0.0, (validDuration - 0.1).coerceAtLeast(0.0))
    } else {
        nudged.coerceAtLeast(0.0)
    }
}

internal fun filterSubtitleCues(
    cues: List<SubtitleCue>,
    query: String,
    ignoreWhitespace: Boolean = true,
): List<SubtitleCue> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return cues
    val normalizedQuery = if (ignoreWhitespace) trimmed.removeAllWhitespace() else trimmed
    if (normalizedQuery.isEmpty()) return cues
    return cues.filter { cue ->
        val candidate = if (ignoreWhitespace) cue.text.removeAllWhitespace() else cue.text
        candidate.contains(normalizedQuery, ignoreCase = true)
    }
}

private fun String.removeAllWhitespace(): String {
    return buildString(length) {
        for (ch in this@removeAllWhitespace) {
            if (!ch.isWhitespace()) append(ch)
        }
    }
}

internal fun formatCueTimestamp(cue: SubtitleCue, timing: SubtitleListTiming): String {
    val speed = timing.speed.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    val delay = timing.delaySeconds.takeIf { it.isFinite() } ?: 0.0
    val totalSeconds = floor((cue.positionSeconds * speed + delay).coerceAtLeast(0.0)).toLong()
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

internal fun appendLiveSubtitleCue(
    existing: List<SubtitleCue>,
    rawText: String,
    startSeconds: Double?,
    endSeconds: Double?,
    fallbackPositionSeconds: Double,
): List<SubtitleCue> {
    if (rawText.isBlank()) return existing
    if (rawText.length > MAX_LIVE_CUE_LENGTH || rawText.count { it == '\n' } > MAX_LIVE_CUE_NEWLINES) {
        return existing
    }
    val start = startSeconds?.takeIf { it.isFinite() && it >= 0.0 }
        ?: fallbackPositionSeconds.takeIf { it.isFinite() && it >= 0.0 }
        ?: return existing
    val end = endSeconds?.takeIf { it.isFinite() && it >= start }
    val duplicate = existing.any { cue ->
        abs(cue.positionSeconds - start) <= CUE_START_EPSILON && cue.text == rawText
    }
    if (duplicate) return existing

    val nextIndex = (existing.maxOfOrNull { it.index } ?: -1) + 1
    return (existing + SubtitleCue(
        index = nextIndex,
        text = rawText,
        positionSeconds = start,
        endPositionSeconds = end,
    )).sortedWith(
        compareBy<SubtitleCue>(
            { it.positionSeconds },
            { it.endPositionSeconds ?: it.positionSeconds },
            { it.index },
        ),
    )
}
