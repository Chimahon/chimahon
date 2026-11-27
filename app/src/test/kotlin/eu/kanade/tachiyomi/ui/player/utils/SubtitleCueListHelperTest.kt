package eu.kanade.tachiyomi.ui.player.utils

import eu.kanade.tachiyomi.ui.player.PlayerViewModel.SubtitleCue
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubtitleCueListHelperTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val defaultOptions = SubtitleRegexFilterOptions(
        removeSpeakerNames = false,
        mergeMultiline = false,
        removeBracketedText = false,
        removeUppercaseLines = false,
        removeMusicSymbols = false,
        removeCurlyBracedText = false,
        customRegexEnabled = false,
        customRegexPattern = "",
    )

    @Test
    fun `prepareSubtitleCues sorts out-of-order events and drops invalid entries`() {
        val raw = listOf(
            SubtitleCue(text = "Later", positionSeconds = 20.0, endPositionSeconds = 22.0),
            SubtitleCue(text = "   ", positionSeconds = 1.0, endPositionSeconds = 2.0),
            SubtitleCue(text = "Negative span", positionSeconds = 5.0, endPositionSeconds = 4.0),
            SubtitleCue(text = "NaN time", positionSeconds = Double.NaN, endPositionSeconds = 6.0),
            SubtitleCue(text = "Earlier", positionSeconds = 10.0, endPositionSeconds = 12.0),
        )

        val prepared = prepareSubtitleCues(raw, defaultOptions)
        assertEquals(listOf("Earlier", "Later"), prepared.map { it.text })
        assertEquals(listOf(4, 0), prepared.map { it.index })
    }

    @Test
    fun `findActiveSubtitleCueMatch matches zero-duration and overlapping cues`() {
        val cues = prepareSubtitleCues(
            listOf(
                SubtitleCue(text = "Sign", positionSeconds = 10.0, endPositionSeconds = 20.0),
                SubtitleCue(text = "Dialogue", positionSeconds = 12.0, endPositionSeconds = 15.0),
                SubtitleCue(text = "Flash", positionSeconds = 25.0, endPositionSeconds = 25.0),
            ),
            defaultOptions,
        )
        val maxDuration = maxSubtitleCueDuration(cues)

        val overlap = findActiveSubtitleCueMatch(
            cues = cues,
            maxCueDuration = maxDuration,
            subStart = 12.0,
            playbackPosSeconds = 13.0,
            timing = SubtitleListTiming(),
        )
        assertEquals(setOf(0, 1), overlap.activeIndices)
        assertEquals(1, overlap.anchorCueIndex)

        val zeroDuration = findActiveSubtitleCueMatch(
            cues = cues,
            maxCueDuration = maxDuration,
            subStart = 25.0,
            playbackPosSeconds = 25.0,
            timing = SubtitleListTiming(),
        )
        assertEquals(setOf(2), zeroDuration.activeIndices)
        assertEquals(2, zeroDuration.anchorCueIndex)
    }

    @Test
    fun `findActiveSubtitleCueMatch returns nearest preceding cue during gaps instead of lastIndex`() {
        val cues = prepareSubtitleCues(
            listOf(
                SubtitleCue(text = "First", positionSeconds = 5.0, endPositionSeconds = 7.0),
                SubtitleCue(text = "Second", positionSeconds = 15.0, endPositionSeconds = 18.0),
                SubtitleCue(text = "End", positionSeconds = 1200.0, endPositionSeconds = 1203.0),
            ),
            defaultOptions,
        )
        val maxDuration = maxSubtitleCueDuration(cues)

        val beforeFirst = findActiveSubtitleCueMatch(
            cues = cues,
            maxCueDuration = maxDuration,
            subStart = null,
            playbackPosSeconds = 1.0,
            timing = SubtitleListTiming(),
        )
        assertTrue(beforeFirst.activeIndices.isEmpty())
        assertNull(beforeFirst.anchorCueIndex)
        assertEquals(0, beforeFirst.nearestCueIndex)

        val betweenFirstAndSecond = findActiveSubtitleCueMatch(
            cues = cues,
            maxCueDuration = maxDuration,
            subStart = null,
            playbackPosSeconds = 10.0,
            timing = SubtitleListTiming(),
        )
        assertTrue(betweenFirstAndSecond.activeIndices.isEmpty())
        assertNull(betweenFirstAndSecond.anchorCueIndex)
        assertEquals(0, betweenFirstAndSecond.nearestCueIndex)

        val delayedGap = findActiveSubtitleCueMatch(
            cues = cues,
            maxCueDuration = maxDuration,
            subStart = null,
            playbackPosSeconds = 19.0,
            timing = SubtitleListTiming(speed = 1.0, delaySeconds = 5.0),
        )
        // Adjusted raw playback time is 14.0s, so First (5s) is nearest, not Second (15s) or End.
        assertEquals(0, delayedGap.nearestCueIndex)
    }

    @Test
    fun `computeCueSeekTargetSeconds nudges inside cue and clamps to duration`() {
        val cue = SubtitleCue(index = 0, text = "Line", positionSeconds = 10.0, endPositionSeconds = 12.0)
        val target = computeCueSeekTargetSeconds(
            cue = cue,
            timing = SubtitleListTiming(speed = 1.0, delaySeconds = 0.5),
            durationSeconds = 20.0,
        )
        assertEquals(10.51, target, 0.0001)

        val clamped = computeCueSeekTargetSeconds(
            cue = SubtitleCue(index = 1, text = "Late", positionSeconds = 99.0, endPositionSeconds = 101.0),
            timing = SubtitleListTiming(speed = 1.0, delaySeconds = 5.0),
            durationSeconds = 100.0,
        )
        assertEquals(99.9, clamped, 0.0001)
    }

    @Test
    fun `filterSubtitleCues supports whitespace-insensitive matching`() {
        val cues = listOf(
            SubtitleCue(index = 0, text = "今日 は いい 天気", positionSeconds = 1.0),
            SubtitleCue(index = 1, text = "また明日", positionSeconds = 3.0),
        )

        val filtered = filterSubtitleCues(cues, "いい天気", ignoreWhitespace = true)
        assertEquals(listOf(0), filtered.map { it.index })

        val strict = filterSubtitleCues(cues, "いい天気", ignoreWhitespace = false)
        assertTrue(strict.isEmpty())
    }

    @Test
    fun `formatCueTimestamp adjusts for speed and delay`() {
        val cue = SubtitleCue(index = 0, text = "Hi", positionSeconds = 65.0)
        assertEquals("01:05", formatCueTimestamp(cue, SubtitleListTiming()))
        assertEquals("02:15", formatCueTimestamp(cue, SubtitleListTiming(speed = 2.0, delaySeconds = 5.0)))
        assertEquals("1:01:05", formatCueTimestamp(SubtitleCue(0, "Long", 3665.0), SubtitleListTiming()))
    }

    @Test
    fun `appendLiveSubtitleCue deduplicates and ignores blank or huge drawing text`() {
        val initial = appendLiveSubtitleCue(emptyList(), "Hello", 1.0, 2.0, 1.0)
        val deduped = appendLiveSubtitleCue(initial, "Hello", 1.0005, 2.0, 1.0)
        val drawing = appendLiveSubtitleCue(deduped, "m".repeat(2001), 3.0, 4.0, 3.0)
        val second = appendLiveSubtitleCue(drawing, "World", 5.0, 6.0, 5.0)

        assertEquals(listOf("Hello", "World"), second.map { it.text })
    }

    @Test
    fun `decodeSubtitleCues and isImageSubtitleCodec handle expected inputs`() {
        val decoded = decodeSubtitleCues("""[{"text":"Test","start":1.5,"end":2.5}]""", json)
        assertEquals(1, decoded.size)
        assertEquals("Test", decoded[0].text)
        assertTrue(isImageSubtitleCodec("hdmv_pgs_subtitle"))
        assertTrue(isImageSubtitleCodec("dvd_subtitle"))
        assertFalse(isImageSubtitleCodec("ass"))
    }
}
