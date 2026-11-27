package chimahon.anki

import org.json.JSONArray
import org.json.JSONObject

/**
 * Classification of a term dictionary for Anki card mining.
 * Matches Hoshi Reader Android's dictionary category selector.
 */
enum class DictionaryCategory(
    val storageValue: String,
    val label: String,
) {
    NONE("none", "None"),
    MONOLINGUAL("monolingual", "Monolingual"),
    BILINGUAL("bilingual", "Bilingual"),
    EXCLUDE("exclude", "Exclude"),
    ;

    companion object {
        fun fromStorageValue(value: String?): DictionaryCategory =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: NONE
    }
}

/**
 * A named mining configuration that bundles together:
 * - AnkiDroid deck/model/field-map/tag settings
 * - The ordered list of dictionaries to use
 * - Which of those dictionaries are enabled for lookup
 *
 * The list [dictionaryOrder] defines priority (index 0 = highest).
 * [enabledDictionaries] is a subset of [dictionaryOrder]; when empty every
 * dictionary in [dictionaryOrder] is treated as enabled (backwards-compatible
 * default for the "Default" profile created during migration).
 */
data class AnkiProfile(
    val id: String,
    val name: String,
    // Anki settings
    val ankiEnabled: Boolean = false,
    val ankiDeck: String = "",
    val ankiModel: String = LapisPreset.MODEL_NAME,
    val ankiFieldMap: String = LapisPreset.defaultFieldMapJson,
    val ankiTags: String = "chimahon",
    val ankiDupCheck: Boolean = true,
    val ankiDupScope: String = "deck",
    val ankiDupAction: String = "prevent",
    val ankiCropMode: String = "full",
    val ankiCropPreset: String = "full",
    val ankiSyncOnCreate: Boolean = false,
    // Dictionary configuration
    val dictionaryOrder: List<String> = emptyList(),
    val enabledDictionaries: Set<String> = emptySet(), // empty = all enabled
    val dictionaryCollapseMode: String = DICTIONARY_COLLAPSE_EXPAND_ALL,
    val dictionaryDisplayModes: Map<String, String> = emptyMap(),
    val dictionaryCategories: Map<String, String> = emptyMap(),

    /**
     * BCP-47-style language code for this profile, e.g. "ja", "ko", "ar", "en".
     * Used by [chimahon.dictionary.DictionaryProfileResolver] to auto-select a
     * matching profile when a source declares a specific language.
     * Empty string means "any / not language-specific".
     */
    val languageCode: String = "",

    /**
     * OCR scan resolution (yomitan "scanning.scanResolution"). Controls whether
     * the tapped position expands to the whole word ("word", default for
     * space-delimited languages) or stays character-based ("character", default
     * for CJK). Empty means auto-derive from [languageCode].
     */
    val scanResolution: String = "",

    /**
     * Lookup search resolution (yomitan "translation.searchResolution"). When
     * no dictionary entry matches the full source, "word" retries cutting at
     * word boundaries while "letter" retries one character at a time.
     * Empty means auto (derive from [languageCode]).
     */
    val searchResolution: String = "",
) {

    fun dictionaryCategory(dirName: String): DictionaryCategory =
        DictionaryCategory.fromStorageValue(dictionaryCategories[dirName])

    fun dictionaryCategoryMap(): Map<String, DictionaryCategory> =
        dictionaryCategories.mapValues { (_, value) -> DictionaryCategory.fromStorageValue(value) }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("ankiEnabled", ankiEnabled)
        put("ankiDeck", ankiDeck)
        put("ankiModel", ankiModel)
        put("ankiFieldMap", ankiFieldMap)
        put("ankiTags", ankiTags)
        put("ankiDupCheck", ankiDupCheck)
        put("ankiDupScope", ankiDupScope)
        put("ankiDupAction", ankiDupAction)
        put("ankiCropMode", ankiCropMode)
        put("ankiCropPreset", ankiCropPreset)
        put("ankiSyncOnCreate", ankiSyncOnCreate)
        put("dictionaryOrder", JSONArray(dictionaryOrder))
        put("enabledDictionaries", JSONArray(enabledDictionaries.toList()))
        put("dictionaryCollapseMode", dictionaryCollapseMode)
        put("dictionaryDisplayModes", JSONObject(dictionaryDisplayModes))
        put("dictionaryCategories", JSONObject(dictionaryCategories))
        put("languageCode", languageCode)
        put("scanResolution", scanResolution)
        put("searchResolution", searchResolution)
    }

    companion object {
        val EMPTY = AnkiProfile(id = "", name = "")
        const val DICTIONARY_COLLAPSE_EXPAND_ALL = "expand_all"
        const val DICTIONARY_COLLAPSE_EXPAND_FIRST_AVAILABLE = "expand_first_available"
        const val DICTIONARY_COLLAPSE_COLLAPSE_ALL = "collapse_all"
        const val DICTIONARY_COLLAPSE_CUSTOM = "custom"

        const val DICTIONARY_DISPLAY_ALWAYS_EXPANDED = "always_expanded"
        const val DICTIONARY_DISPLAY_FALLBACK = "fallback"
        const val DICTIONARY_DISPLAY_ALWAYS_COLLAPSED = "always_collapsed"

        const val SCAN_RESOLUTION_AUTO = ""
        const val SCAN_RESOLUTION_CHARACTER = "character"
        const val SCAN_RESOLUTION_WORD = "word"

        const val SEARCH_RESOLUTION_AUTO = ""
        const val SEARCH_RESOLUTION_LETTER = "letter"
        const val SEARCH_RESOLUTION_WORD = "word"

        fun fromJson(json: JSONObject): AnkiProfile = AnkiProfile(
            id = json.getString("id"),
            name = json.getString("name"),
            ankiEnabled = json.optBoolean("ankiEnabled", false),
            ankiDeck = json.optString("ankiDeck", ""),
            ankiModel = json.optString("ankiModel", "").ifBlank { LapisPreset.MODEL_NAME },
            ankiFieldMap = json.optString("ankiFieldMap", "{}").let { fieldMap ->
                val model = json.optString("ankiModel", "")
                when {
                    model.isBlank() && LapisPreset.isBlankFieldMap(fieldMap) -> LapisPreset.defaultFieldMapJson
                    LapisPreset.isBundledModelName(model) && LapisPreset.isBlankFieldMap(fieldMap) -> LapisPreset.defaultFieldMapJson
                    else -> fieldMap
                }
            },
            ankiTags = json.optString("ankiTags", "chimahon"),
            ankiDupCheck = json.optBoolean("ankiDupCheck", true),
            ankiDupScope = json.optString("ankiDupScope", "deck"),
            ankiDupAction = json.optString("ankiDupAction", "prevent"),
            ankiCropMode = json.optString("ankiCropMode", "full"),
            ankiCropPreset = json.optString("ankiCropPreset", "full"),
            ankiSyncOnCreate = json.optBoolean("ankiSyncOnCreate", false),
            dictionaryOrder = json.optJSONArray("dictionaryOrder")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList(),
            enabledDictionaries = json.optJSONArray("enabledDictionaries")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) }.toSet() }
                ?: emptySet(),
            dictionaryCollapseMode = json.optString("dictionaryCollapseMode", DICTIONARY_COLLAPSE_EXPAND_ALL),
            dictionaryDisplayModes = json.optJSONObject("dictionaryDisplayModes")
                ?.let { obj ->
                    buildMap {
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            put(key, obj.optString(key, DICTIONARY_DISPLAY_FALLBACK))
                        }
                    }
                }
                ?: emptyMap(),
            dictionaryCategories = json.optJSONObject("dictionaryCategories")
                ?.let { obj ->
                    buildMap {
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val category = DictionaryCategory.fromStorageValue(
                                obj.optString(key, DictionaryCategory.NONE.storageValue),
                            )
                            if (category != DictionaryCategory.NONE) {
                                put(key, category.storageValue)
                            }
                        }
                    }
                }
                ?: emptyMap(),
            languageCode = json.optString("languageCode", ""),
            scanResolution = json.optString("scanResolution", ""),
            searchResolution = json.optString("searchResolution", ""),
        )

        /**
         * Migrate legacy flat-key values (passed in from the UI/prefs layer,
         * so this class itself stays free of Android/Prefs dependencies)
         * into a brand-new Default profile.
         */
        fun createDefault(
            defaultName: String = "Default",
            ankiEnabled: Boolean = false,
            ankiDeck: String = "",
            ankiModel: String = LapisPreset.MODEL_NAME,
            ankiFieldMap: String = LapisPreset.defaultFieldMapJson,
            ankiTags: String = "chimahon",
            ankiDupCheck: Boolean = true,
            ankiDupScope: String = "deck",
            ankiDupAction: String = "prevent",
            ankiCropMode: String = "full",
            ankiCropPreset: String = "full",
            ankiSyncOnCreate: Boolean = false,
            dictionaryOrder: List<String> = emptyList(),
        ): AnkiProfile = AnkiProfile(
            id = java.util.UUID.randomUUID().toString(),
            name = defaultName,
            ankiEnabled = ankiEnabled,
            ankiDeck = ankiDeck,
            ankiModel = ankiModel.ifBlank { LapisPreset.MODEL_NAME },
            ankiFieldMap = when {
                ankiModel.isBlank() && LapisPreset.isBlankFieldMap(ankiFieldMap) -> LapisPreset.defaultFieldMapJson
                LapisPreset.isBundledModelName(ankiModel) && LapisPreset.isBlankFieldMap(ankiFieldMap) -> LapisPreset.defaultFieldMapJson
                else -> ankiFieldMap
            },
            ankiTags = ankiTags,
            ankiDupCheck = ankiDupCheck,
            ankiDupScope = ankiDupScope,
            ankiDupAction = ankiDupAction,
            ankiCropMode = ankiCropMode,
            ankiCropPreset = ankiCropPreset,
            ankiSyncOnCreate = ankiSyncOnCreate,
            dictionaryOrder = dictionaryOrder,
            enabledDictionaries = emptySet(),
        )
    }
}
