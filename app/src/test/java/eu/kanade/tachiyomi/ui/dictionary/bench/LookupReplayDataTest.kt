package eu.kanade.tachiyomi.ui.dictionary.bench

import chimahon.Frequency
import chimahon.FrequencyEntry
import chimahon.GlossaryEntry
import chimahon.LookupResult
import chimahon.PitchEntry
import chimahon.TermResult
import chimahon.TransformGroup
import chimahon.anki.AnkiProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LookupReplayDataTest {
    private fun result(): LookupResult = LookupResult("\u884c\u52d5", "\u884c\u52d5", arrayOf(TransformGroup("v", "past")),
        TermResult("\u884c\u52d5", "\u3053\u3046\u3069\u3046", "v1", arrayOf(
            GlossaryEntry("dictionary", "{\"tag\":\"ruby\",\"content\":\"a\\nb\"}", "noun", "popular"),
        ), arrayOf(FrequencyEntry("frequency", arrayOf(Frequency(42, "42*")))),
            arrayOf(PitchEntry("pitch", intArrayOf(0, 2)))), 3)

    @Test fun preservesEveryLookupFieldAndGlossaryBytes() {
        val original = result()
        val encoded = LookupReplayData.encode(listOf(original))
        val decoded = LookupReplayData.decode(JSONArray(encoded.toString())).single()
        assertEquals(encoded.toString(), LookupReplayData.encode(listOf(decoded)).toString())
        assertEquals(original.term.glossaries.single().glossary, decoded.term.glossaries.single().glossary)
        assertEquals(42, decoded.term.frequencies.single().frequencies.single().value)
        assertEquals(listOf(0, 2), decoded.term.pitches.single().pitchPositions.toList())
        assertEquals(3, decoded.preprocessorSteps)
    }

    @Test fun exportsRenderProfileWithoutAnkiAccountFields() {
        val profile = AnkiProfile("private-id", "private-name", ankiEnabled = true,
            ankiDeck = "private-deck", dictionaryOrder = listOf("second", "first"),
            dictionaryDisplayModes = mapOf("first" to "always_collapsed"))
        val json = LookupReplayData.renderProfile(profile)
        assertFalse(json.has("ankiDeck"))
        assertEquals("replay", json.getString("id"))
        val restored = AnkiProfile.fromJson(json)
        assertEquals(profile.dictionaryOrder, restored.dictionaryOrder)
        assertEquals(profile.dictionaryDisplayModes, restored.dictionaryDisplayModes)
        assertEquals(true, restored.ankiEnabled)
    }

    private fun bundle(media: String = "data:image/png;base64,AA=="): JSONObject = JSONObject()
        .put("protocol", 1).put("scope", LookupReplayData.SCOPE).put("cases", JSONArray().put(JSONObject()
            .put("id", "one").put("widthPx", 825).put("heightPx", 990).put("density", 2.75)
            .put("fontScale", 1).put("fontSize", 16).put("results", LookupReplayData.encode(listOf(result())))
            .put("media", JSONObject().put("image", media)).put("options", JSONObject().put("fontFamily", ""))))

    @Test fun rejectsNonportableMediaAndUnknownProtocols() {
        LookupReplayData.validate(bundle())
        assertThrows(IllegalArgumentException::class.java) { LookupReplayData.validate(bundle("file:///sdcard/image.png")) }
        assertThrows(IllegalArgumentException::class.java) { LookupReplayData.validate(bundle().put("protocol", 2)) }
        val invalid = bundle()
        invalid.getJSONArray("cases").getJSONObject(0).put("widthPx", 0)
        assertThrows(IllegalArgumentException::class.java) { LookupReplayData.validate(invalid) }
    }

    @Test fun containerDrawCannotCompleteReplayAndPhaseLedgerAddsUp() {
        var draws = 0
        val timings = LookupReplayTimings(clock = { 100_000_000L }, documentDrawn = { draws++ })
        timings.requestId = 1
        timings.event(1, "content.container.draw", 150_000_000L, 150_000_000L, 0, "")
        assertEquals(0, draws)
        assertThrows(IllegalArgumentException::class.java) { timings.result(null) }
        timings.event(1, "plan.prepare", 200_000_000L, 490_000_000L, 0, "")
        timings.event(1, "plan.ready", 500_000_000L, 500_000_000L, 0, "")
        timings.event(2, "document.first_draw", 550_000_000L, 550_000_000L, 0, "other request")
        assertEquals(0, draws)
        timings.event(1, "document.first_draw", 600_000_000L, 600_000_000L, 0, "")
        val sample = timings.result(700_000_000L)
        assertEquals(1, draws)
        assertEquals(600.0, sample.getDouble("frameCommitTotalMs"))
        assertEquals(600.0, listOf("mountQueueMs", "prepareMs", "readyToDrawMs", "drawToCommitMs")
            .sumOf { sample.getDouble(it) })
        assertFalse(sample.getBoolean("commitIsDisplayPresentation"))
    }
}
