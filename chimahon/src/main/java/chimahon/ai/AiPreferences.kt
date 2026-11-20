package chimahon.ai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.util.Locale

internal object AiTtsLocaleHelper {
    val JA_REGEX = Regex("[\\u3040-\\u30FF\\u4E00-\\u9FFF]")
    val KO_REGEX = Regex("[\\uAC00-\\uD7AF]")

    fun resolveLocale(text: String, languageMode: String): Locale = when {
        KO_REGEX.containsMatchIn(text) -> Locale.KOREAN
        JA_REGEX.containsMatchIn(text) -> Locale.JAPANESE
        languageMode == AiPreferences.LANG_GLOBAL -> Locale.getDefault()
        else -> Locale.US
    }
}

class AiPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ai_prefs", Context.MODE_PRIVATE)

    var groqApiKey: String
        get() = prefs.getString("groq_api_key", "")?.trim().orEmpty()
        set(value) = prefs.edit().putString("groq_api_key", value.trim()).apply()

    var groqBackupApiKey: String
        get() = prefs.getString("groq_backup_api_key", "")?.trim().orEmpty()
        set(value) = prefs.edit().putString("groq_backup_api_key", value.trim()).apply()

    var useCustomAiPopup: Boolean
        get() = prefs.getBoolean("use_custom_ai_popup", false)
        set(value) = prefs.edit().putBoolean("use_custom_ai_popup", value).apply()

    var groqModel: String
        get() = prefs.getString("groq_model", DEFAULT_MODEL)?.trim().orEmpty().ifEmpty { DEFAULT_MODEL }
        set(value) = prefs.edit().putString("groq_model", value.trim().ifEmpty { DEFAULT_MODEL }).apply()

    var cachedGroqModels: List<String>
        get() {
            val raw = prefs.getString("cached_groq_models", "").orEmpty()
            if (raw.isBlank()) return DEFAULT_MODELS
            return runCatching {
                val arr = JSONArray(raw)
                val list = buildList {
                    for (i in 0 until arr.length()) {
                        val id = arr.optString(i).trim()
                        if (id.isNotEmpty()) add(id)
                    }
                }
                list.ifEmpty { DEFAULT_MODELS }
            }.getOrDefault(DEFAULT_MODELS)
        }
        set(value) {
            val distinct = value.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val arr = JSONArray().apply { distinct.forEach { put(it) } }
            prefs.edit().putString("cached_groq_models", arr.toString()).apply()
        }

    var popupDesign: String
        get() = prefs.getString("popup_design", DESIGN_CLASSIC_CARDS)?.trim().orEmpty().ifEmpty { DESIGN_CLASSIC_CARDS }
        set(value) = prefs.edit().putString("popup_design", value.trim().ifEmpty { DESIGN_CLASSIC_CARDS }).apply()

    var languageMode: String
        get() = prefs.getString("language_mode", LANG_EN_JA)?.trim().orEmpty().ifEmpty { LANG_EN_JA }
        set(value) = prefs.edit().putString("language_mode", value.trim().ifEmpty { LANG_EN_JA }).apply()

    companion object {
        const val DEFAULT_MODEL = "openai/gpt-oss-120b"
        val DEFAULT_MODELS = listOf(
            "openai/gpt-oss-120b",
            "openai/gpt-oss-20b",
            "qwen/qwen3.8-27b",
        )

        const val DESIGN_CLASSIC_CARDS = "classic_cards"
        const val DESIGN_GLASS_INDIGO = "glass_indigo"
        const val DESIGN_NEO_BENTO = "neo_bento"
        const val DESIGN_COSMIC_VIOLET = "cosmic_violet"

        val POPUP_DESIGN_OPTIONS = listOf(
            DESIGN_CLASSIC_CARDS to "Classic Cards (Default)",
            DESIGN_GLASS_INDIGO to "Obsidian-Indigo Glass",
            DESIGN_NEO_BENTO to "Paper & Vermilion",
            DESIGN_COSMIC_VIOLET to "Cosmic Violet",
        )

        const val LANG_EN_JA = "en_ja"
        const val LANG_GLOBAL = "global"

        val LANGUAGE_MODE_OPTIONS = listOf(
            LANG_EN_JA to "English & Japanese (Main)",
            LANG_GLOBAL to "Global Language (Any Other Languages)",
        )
    }
}