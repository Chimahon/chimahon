package eu.kanade.tachiyomi.ui.dictionary.bench

import chimahon.DictionaryStyle
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

/** Versioned, lossless Hoshi output snapshots. No dictionary paths or Anki account data. */
internal object LookupReplayData {
    const val PROTOCOL = 1
    const val SCOPE = "production-popup/results-to-frame-commit"

    fun objects(array: JSONArray): List<JSONObject> = List(array.length()) { array.getJSONObject(it) }
    fun strings(array: JSONArray): List<String> = List(array.length()) { array.getString(it) }
    fun stringMap(json: JSONObject): Map<String, String> = json.keys().asSequence().associateWith { json.getString(it) }

    fun encode(results: List<LookupResult>): JSONArray = JSONArray(results.map { result ->
        JSONObject().put("matched", result.matched).put("deinflected", result.deinflected)
            .put("preprocessorSteps", result.preprocessorSteps)
            .put("process", JSONArray(result.process.map {
                JSONObject().put("name", it.name).put("description", it.description)
            })).put("term", JSONObject().put("expression", result.term.expression)
                .put("reading", result.term.reading).put("rules", result.term.rules)
                .put("glossaries", JSONArray(result.term.glossaries.map {
                    JSONObject().put("dictName", it.dictName).put("glossary", it.glossary)
                        .put("definitionTags", it.definitionTags).put("termTags", it.termTags)
                })).put("frequencies", JSONArray(result.term.frequencies.map { entry ->
                    JSONObject().put("dictName", entry.dictName).put("frequencies", JSONArray(entry.frequencies.map {
                        JSONObject().put("value", it.value).put("displayValue", it.displayValue)
                    }))
                })).put("pitches", JSONArray(result.term.pitches.map {
                    JSONObject().put("dictName", it.dictName).put("pitchPositions", JSONArray(it.pitchPositions.toList()))
                })))
    })

    fun decode(array: JSONArray): List<LookupResult> = objects(array).map { result ->
        val term = result.getJSONObject("term")
        LookupResult(result.getString("matched"), result.getString("deinflected"),
            objects(result.getJSONArray("process")).map {
                TransformGroup(it.getString("name"), it.getString("description"))
            }.toTypedArray(), TermResult(term.getString("expression"), term.getString("reading"), term.getString("rules"),
                objects(term.getJSONArray("glossaries")).map {
                    GlossaryEntry(it.getString("dictName"), it.getString("glossary"),
                        it.getString("definitionTags"), it.getString("termTags"))
                }.toTypedArray(), objects(term.getJSONArray("frequencies")).map { entry ->
                    FrequencyEntry(entry.getString("dictName"), objects(entry.getJSONArray("frequencies")).map {
                        Frequency(it.getInt("value"), it.getString("displayValue"))
                    }.toTypedArray())
                }.toTypedArray(), objects(term.getJSONArray("pitches")).map {
                    val positions = it.getJSONArray("pitchPositions")
                    PitchEntry(it.getString("dictName"), IntArray(positions.length()) { index -> positions.getInt(index) })
                }.toTypedArray()), result.getInt("preprocessorSteps"))
    }

    fun styles(json: JSONArray): List<DictionaryStyle> = objects(json).map {
        DictionaryStyle(it.getString("dictName"), it.getString("styles"))
    }

    fun renderProfile(profile: AnkiProfile): JSONObject = JSONObject()
        .put("id", "replay").put("name", "replay").put("ankiEnabled", profile.ankiEnabled)
        .put("dictionaryOrder", JSONArray(profile.dictionaryOrder))
        .put("enabledDictionaries", JSONArray(profile.enabledDictionaries.toList()))
        .put("dictionaryCollapseMode", profile.dictionaryCollapseMode)
        .put("dictionaryDisplayModes", JSONObject(profile.dictionaryDisplayModes))
        .put("languageCode", profile.languageCode).put("scanResolution", profile.scanResolution)
        .put("searchResolution", profile.searchResolution)

    fun validate(bundle: JSONObject) {
        require(bundle.getInt("protocol") == PROTOCOL) { "Unsupported replay protocol" }
        require(bundle.getString("scope") == SCOPE) { "Not a popup replay bundle" }
        val cases = objects(bundle.getJSONArray("cases"))
        require(cases.isNotEmpty() && cases.map { it.getString("id") }.distinct().size == cases.size)
        for (case in cases) {
            require(case.getInt("widthPx") in 1..8192 && case.getInt("heightPx") in 1..8192)
            require(case.getDouble("density") in 0.5..8.0 && case.getDouble("fontScale") in 0.5..3.0)
            require(case.getInt("fontSize") in 1..100)
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
