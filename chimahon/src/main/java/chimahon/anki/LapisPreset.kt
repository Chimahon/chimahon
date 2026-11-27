package chimahon.anki

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

object LapisPreset {
    const val MODEL_NAME = "Lapis"
    const val FALLBACK_MODEL_NAME = "Lapis (Chimahon)"
    const val DEFAULT_DECK_NAME = "Chimahon"

    val fields: List<String> = listOf(
        "Expression",
        "ExpressionFurigana",
        "ExpressionReading",
        "ExpressionAudio",
        "SelectionText",
        "MainDefinition",
        "DefinitionPicture",
        "Sentence",
        "SentenceFurigana",
        "SentenceAudio",
        "Picture",
        "Glossary",
        "Hint",
        "IsWordAndSentenceCard",
        "IsClickCard",
        "IsSentenceCard",
        "IsAudioCard",
        "PitchPosition",
        "PitchCategories",
        "Frequency",
        "FreqSort",
        "MiscInfo",
    )

    val defaultFieldMap: Map<String, String> = linkedMapOf(
        "Expression" to "{${Marker.EXPRESSION}}",
        "ExpressionFurigana" to "{${Marker.FURIGANA_PLAIN}}",
        "ExpressionReading" to "{${Marker.READING}}",
        "ExpressionAudio" to "{${Marker.AUDIO}}",
        "SelectionText" to "{${Marker.POPUP_SELECTION_TEXT}}",
        "MainDefinition" to "{${Marker.SELECTED_GLOSSARY}}",
        "Sentence" to "{${Marker.SENTENCE}}",
        "SentenceFurigana" to "{${Marker.SENTENCE_FURIGANA}}",
        "SentenceAudio" to "{${Marker.SENTENCE_AUDIO}}",
        "Picture" to "{${Marker.SCREENSHOT}}",
        "Glossary" to "{${Marker.GLOSSARY}}",
        "IsWordAndSentenceCard" to "x",
        "PitchPosition" to "{${Marker.PITCH_ACCENT_POSITIONS}}",
        "PitchCategories" to "{${Marker.PITCH_ACCENT_CATEGORIES}}",
        "Frequency" to "{${Marker.FREQUENCIES}}",
        "FreqSort" to "{${Marker.FREQUENCY_HARMONIC_RANK}}",
        "MiscInfo" to "{${Marker.MEDIA_NAME}}",
    )

    val defaultFieldMapJson: String
        get() = Json.encodeToString(defaultFieldMap)

    fun isBlankFieldMap(fieldMapJson: String): Boolean =
        fieldMapJson.isBlank() || fieldMapJson.trim() == "{}"

    fun isBundledModelName(name: String): Boolean =
        name.equals(MODEL_NAME, ignoreCase = true) ||
            name.equals(FALLBACK_MODEL_NAME, ignoreCase = true) ||
            name.startsWith("Lapis (Chimahon ", ignoreCase = true)

    fun isLapisLikeModel(name: String, modelFields: List<String>): Boolean =
        name.contains("lapis", ignoreCase = true) || hasCoreFields(modelFields)

    fun hasCoreFields(modelFields: List<String>): Boolean {
        val names = modelFields.map { it.lowercase() }.toSet()
        return listOf("expression", "maindefinition", "sentence").all { it in names }
    }

    fun hasAllFields(modelFields: List<String>): Boolean {
        val names = modelFields.map { it.lowercase() }.toSet()
        return fields.all { it.lowercase() in names }
    }

    fun defaultFieldMapFor(modelFields: List<String>): Map<String, String> {
        if (modelFields.isEmpty()) return defaultFieldMap
        val available = modelFields.toSet()
        return defaultFieldMap.filterKeys { it in available }
    }

    fun defaultFieldMapJsonFor(modelFields: List<String>): String =
        JSONObject(defaultFieldMapFor(modelFields)).toString()

    fun applyDefaults(
        modelName: String,
        modelFields: List<String>,
        currentFieldMapJson: String,
    ): String {
        if (!isLapisLikeModel(modelName, modelFields)) return currentFieldMapJson
        if (isBlankFieldMap(currentFieldMapJson)) return defaultFieldMapJsonFor(modelFields)

        val current = AnkiCardCreator.parseFieldMap(currentFieldMapJson)
        val merged = defaultFieldMapFor(modelFields) + current
        return JSONObject(merged).toString()
    }
}
