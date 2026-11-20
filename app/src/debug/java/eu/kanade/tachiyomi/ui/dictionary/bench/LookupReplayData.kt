package eu.kanade.tachiyomi.ui.dictionary.bench

import chimahon.DictionaryStyle
import chimahon.Frequency
import chimahon.FrequencyEntry
import chimahon.GlossaryEntry
import chimahon.LookupResult
import chimahon.PitchEntry
import chimahon.TermResult
import chimahon.TransformGroup
import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned, lossless Hoshi output snapshots for the replay harness.
 *
 * The JSON shapes here are the wire contract of `tools/perf/lookup-replay/replay.py`: the bundle
 * carries `protocol`/`scope`/`cases`, and each case carries the production popup inputs
 * (`results`, `styles`, `media`, `profile`, `environment`, `options`, geometry and typography).
 * No dictionary paths or Anki account data are stored.
 */
internal object LookupReplayData {
    const val PROTOCOL = 1
    const val SCOPE = "production-popup/results-to-frame-commit"

    fun objects(array: JSONArray): List<JSONObject> = List(array.length()) { array.getJSONObject(it) }
    fun strings(array: JSONArray): List<String> = List(array.length()) { array.getString(it) }
    fun stringMap(json: JSONObject): Map<String, String> = json.keys().asSequence().associateWith { json.getString(it) }

    /** Rebuilds the same [LookupResult] model the native lookup returns to the reader. */
    fun decode(array: JSONArray): List<LookupResult> = objects(array).map { result ->
        val term = result.getJSONObject("term")
        LookupResult(
            result.getString("matched"),
            result.getString("deinflected"),
            objects(result.getJSONArray("process")).map {
                TransformGroup(it.getString("name"), it.getString("description"))
            }.toTypedArray(),
            TermResult(
                term.getString("expression"),
                term.getString("reading"),
                term.getString("rules"),
                objects(term.getJSONArray("glossaries")).map {
                    GlossaryEntry(it.getString("dictName"), it.getString("glossary"),
                        it.getString("definitionTags"), it.getString("termTags"))
                }.toTypedArray(),
                objects(term.getJSONArray("frequencies")).map { entry ->
                    FrequencyEntry(entry.getString("dictName"), objects(entry.getJSONArray("frequencies")).map {
                        Frequency(it.getInt("value"), it.getString("displayValue"))
                    }.toTypedArray())
                }.toTypedArray(),
                objects(term.getJSONArray("pitches")).map {
                    val positions = it.getJSONArray("pitchPositions")
                    PitchEntry(it.getString("dictName"), IntArray(positions.length()) { index -> positions.getInt(index) })
                }.toTypedArray(),
            ),
            result.getInt("preprocessorSteps"),
        )
    }

    fun styles(json: JSONArray): List<DictionaryStyle> = objects(json).map {
        DictionaryStyle(it.getString("dictName"), it.getString("styles"))
    }

    /** Rejects bundles the replay runner cannot render faithfully, before anything is measured. */
    fun validate(bundle: JSONObject) {
        require(bundle.getInt("protocol") == PROTOCOL) { "Unsupported replay protocol" }
        require(bundle.getString("scope") == SCOPE) { "Not a popup replay bundle" }
        val cases = objects(bundle.getJSONArray("cases"))
        require(cases.isNotEmpty()) { "Replay bundle has no cases" }
        require(cases.map { it.getString("id") }.distinct().size == cases.size) { "Duplicate replay case ids" }
        for (case in cases) {
            require(case.getInt("widthPx") in 1..8192 && case.getInt("heightPx") in 1..8192) {
                "Replay viewport out of range in ${case.getString("id")}"
            }
            require(case.getDouble("density") in 0.5..8.0) { "Replay density out of range" }
            require(case.getDouble("fontScale") in 0.5..3.0) { "Replay font scale out of range" }
            require(case.getInt("fontSize") in 1..100) { "Replay font size out of range" }
            require(case.getJSONArray("results").length() > 0) { "Empty lookup cannot signal a glossary draw" }
            require(stringMap(case.getJSONObject("media")).values.all { it.startsWith("data:") }) {
                "Replay media must be portable data URIs, not device file paths"
            }
            require(case.getJSONObject("options").optString("fontFamily").isEmpty()) {
                "Custom dictionary fonts need an explicit portable font export before replay"
            }
        }
    }
}
