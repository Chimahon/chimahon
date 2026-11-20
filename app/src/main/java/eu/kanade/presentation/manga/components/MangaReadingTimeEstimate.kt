package eu.kanade.presentation.manga.components

import android.content.Context
import eu.kanade.presentation.util.toDurationString
import eu.kanade.tachiyomi.ui.manga.ChapterList
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Returns estimated reading time calculated from the provided chapters.
 * Deduplicates multi-scanlation chapters to ensure accurate estimates.
 */
internal fun List<ChapterList.Item>.toEstimatedReadingTime(context: Context): String? {
    val uniqueCount = distinctBy { item ->
        if (item.chapter.isRecognizedNumber) {
            "num:${item.chapter.chapterNumber}"
        } else {
            val normalized = item.chapter.name.trim().lowercase()
            if (normalized.isNotEmpty()) "name:$normalized" else "id:${item.chapter.id}"
        }
    }.size

    return uniqueCount.toEstimatedReadingTime(context)
}

/**
 * Returns estimated reading time rounded to the nearest minute.
 * Avoids displaying raw seconds for rough reading estimates.
 */
internal fun Int.toEstimatedReadingTime(context: Context): String? {
    if (this <= 0) return null

    // Round to nearest whole minute (at least 1 minute for non-zero chapter counts)
    val totalMinutes = ((toLong() * AVERAGE_CHAPTER_READING_TIME_MILLIS + 30_000L) / 60_000L).coerceAtLeast(1L)

    val durationString = totalMinutes
        .toDuration(DurationUnit.MINUTES)
        .toDurationString(context, fallback = "")

    return if (durationString.isNotBlank()) "~$durationString" else null
}

/**
 * Baseline assumption: ~18 pages per chapter at ~10 seconds per page (~182.8 seconds / ~3 minutes per chapter).
 */
private const val AVERAGE_CHAPTER_READING_TIME_MILLIS = 182_800L
