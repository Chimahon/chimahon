package eu.kanade.tachiyomi.ui.dictionary

import chimahon.anki.AnkiProfile
import chimahon.anki.AnkiProfileStore
import chimahon.audio.WordAudioPreferences
import chimahon.dictionary.ko.KoreanParserMode
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class DictionaryPreferences(
    private val preferenceStore: PreferenceStore,
) : WordAudioPreferences {

    fun popupWidth() = preferenceStore.getInt("pref_dictionary_popup_width", 300)

    fun popupHeight() = preferenceStore.getInt("pref_dictionary_popup_height", 360)

    fun popupMode() = preferenceStore.getString("pref_dictionary_popup_mode", "floating")
    fun fontSize() = preferenceStore.getInt("pref_dictionary_font_size", 16)
    fun fontFamily() = preferenceStore.getString("pref_dictionary_font_family", "")

    fun ocrBoxScale() = preferenceStore.getFloat("pref_ocr_box_scale", 1.0f)

    fun ocrBoxScaleX() = preferenceStore.getFloat("pref_ocr_box_scale_x", ocrBoxScale().get())

    fun ocrBoxScaleY() = preferenceStore.getFloat("pref_ocr_box_scale_y", ocrBoxScale().get())

    fun ocrBoxOpacity() = preferenceStore.getFloat("pref_ocr_box_opacity", 0.0f)

    /** Opacity of text characters in the currently active/tapped OCR block (0..1). */
    fun activeOcrTextOpacity() = preferenceStore.getFloat("pref_active_ocr_text_opacity", 1.0f)

    /** Opacity of background fill box in the currently active/tapped OCR block (0..1). */
    fun activeOcrBgOpacity() = preferenceStore.getFloat("pref_active_ocr_bg_opacity", 0.7f)

    /** Floating screen-lookup OCR capture button size in dp. */
    fun ocrButtonSize() = preferenceStore.getInt("pref_ocr_button_size", 56)

    /** Floating screen-lookup OCR capture button opacity (0..1). */
    fun ocrButtonAlpha() = preferenceStore.getFloat("pref_ocr_button_alpha", 0.92f)

    /** Floating screen-lookup OCR capture button background color as ARGB int; 0 = theme primary. */
    fun ocrButtonColor() = preferenceStore.getInt("pref_ocr_button_color", 0)

    /** "cloud" (default) or "local" */
    fun ocrEngine() = preferenceStore.getString("pref_ocr_engine", "cloud")

    fun parallelOcrLimit() = preferenceStore.getInt("pref_parallel_ocr_limit", 1)

    fun videoOcrSentenceAudioPaddingSeconds() = preferenceStore.getInt("pref_video_ocr_sentence_audio_padding_seconds", 3)

    fun showFrequencyHarmonic() = preferenceStore.getBoolean("pref_dict_show_frequency_harmonic", false)
    fun showFrequencyAverage() = preferenceStore.getBoolean("pref_dict_show_frequency_average", false)

    /**
     * Multi-select keys for frequency display modes (settings UI).
     * Kept in sync with [showFrequencyHarmonic] / [showFrequencyAverage].
     */
    fun frequencyDisplayModes() = preferenceStore.getStringSet("pref_dict_frequency_display_modes", emptySet())

    fun groupTerms() = preferenceStore.getBoolean("pref_dict_group_terms", true)
    fun compactGlossary() = preferenceStore.getBoolean("pref_dict_compact_glossary", false)
    fun showPitchDiagram() = preferenceStore.getBoolean("pref_dict_show_pitch_diagram", true)
    fun showPitchNumber() = preferenceStore.getBoolean("pref_dict_show_pitch_number", true)
    fun showPitchText() = preferenceStore.getBoolean("pref_dict_show_pitch_text", true)

    /**
     * Multi-select keys for pitch accent display (settings UI).
     * Kept in sync with [showPitchDiagram] / [showPitchNumber] / [showPitchText].
     * Default matches historical boolean defaults (all on).
     */
    fun pitchDisplayModes() = preferenceStore.getStringSet(
        "pref_dict_pitch_display_modes",
        setOf(PITCH_DIAGRAM, PITCH_NUMBER, PITCH_TEXT),
    )
    fun groupPitches() = preferenceStore.getBoolean("pref_dict_group_pitches", false)
    fun showNavigationButtons() = preferenceStore.getBoolean("pref_dict_show_navigation_buttons", true)

    fun autoKanaConversion() = preferenceStore.getBoolean("pref_dict_auto_kana_conversion", true)

    fun koreanParserMode() = preferenceStore.getString("pref_dict_korean_parser_mode", KoreanParserMode.Legacy)

    fun recursiveLookupMode() = preferenceStore.getString("pref_dict_recursive_lookup_mode", "tabs")

    /** Whether horizontal swipe-to-dismiss is enabled on dictionary popups. */
    fun popupSwipeToDismiss() = preferenceStore.getBoolean("pref_dictionary_popup_swipe_to_dismiss", true)

    /** Horizontal swipe distance (in dp) required to dismiss a dictionary popup. */
    fun popupSwipeThreshold() = preferenceStore.getInt("pref_dictionary_popup_swipe_threshold", 56)

    fun themeMode(): tachiyomi.core.common.preference.Preference<String> {
        // One-time migration from old boolean pref
        val oldPref = preferenceStore.getBoolean("pref_dictionary_theme_dark_amoled", false)
        if (oldPref.isSet()) {
            val oldValue = oldPref.get()
            oldPref.delete()
            val newPref = preferenceStore.getString("pref_dictionary_theme_mode", "system")
            newPref.set(if (oldValue) "pure_black" else "system")
            return newPref
        }
        return preferenceStore.getString("pref_dictionary_theme_mode", "system")
    }
    fun customColor() = preferenceStore.getInt("pref_dictionary_custom_color", 0)

    fun eInkMode() = preferenceStore.getBoolean(Preference.appStateKey("pref_dictionary_eink_mode"), false)

    fun paginatedScrolling() = preferenceStore.getBoolean("pref_dictionary_paginated_scrolling", false)

    /** Paginated scroll step size as percentage of viewport height (50-100, default 90). */
    fun paginatedScrollStepSize() = preferenceStore.getInt("pref_dictionary_paginated_scroll_step_size", 90)

    /** Scroll behavior for floating navigation buttons: "smooth" (default) or "instant". */
    fun scrollBehavior() = preferenceStore.getString("pref_dictionary_scroll_behavior", SCROLL_SMOOTH)

    /** Whether volume keys can be used to navigate between dictionary entries. */
    fun volumeKeyNavigation() = preferenceStore.getBoolean("pref_dictionary_volume_key_navigation", true)

    fun customCss() = preferenceStore.getString("pref_dictionary_custom_css", "")

    /** "webview" (default) or "compose" — native Compose renderer for the reader popup. */
    fun renderer() = preferenceStore.getString("pref_dictionary_renderer", RENDERER_WEBVIEW)

    // -------------------------------------------------------------------------
    // Profile storage (raw pref keys — consumed by AnkiProfileStore and settings UI)
    // -------------------------------------------------------------------------

    fun rawProfiles() = preferenceStore.getString("pref_anki_profiles", "[]")

    fun rawActiveProfileId() = preferenceStore.getString("pref_active_profile_id", "")

    // -------------------------------------------------------------------------
    // AnkiProfileStore (single instance, lazy)
    // -------------------------------------------------------------------------

    val profileStore: AnkiProfileStore by lazy {
        AnkiProfileStore(
            readProfiles = { rawProfiles().get() },
            writeProfiles = { rawProfiles().set(it) },
            readActiveId = { rawActiveProfileId().get() },
            writeActiveId = { rawActiveProfileId().set(it) },
        ).apply {
            if (getProfiles().isEmpty()) {
                val legacyDicts = dictionaryOrder().get()
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                migrateIfEmpty(
                    defaultName = "Default",
                    legacyValues = AnkiProfileStore.LegacyAnkiValues(
                        deck = legacyAnkiDeck().get(),
                        model = legacyAnkiModel().get(),
                        fieldMap = legacyAnkiFieldMap().get(),
                        tags = legacyAnkiDefaultTags().get(),
                        dupCheck = legacyAnkiDuplicateCheck().get(),
                        dupScope = legacyAnkiDuplicateScope().get(),
                        dupAction = legacyAnkiDuplicateAction().get(),
                        cropMode = legacyAnkiCropMode().get(),
                    ),
                    allDictNames = legacyDicts,
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // Per-manga / per-source profile overrides
    // -------------------------------------------------------------------------

    /**
     * Returns a raw [tachiyomi.core.common.preference.Preference] for the given
     * dynamic override key.  Callers use [chimahon.dictionary.DictionaryProfileResolver.mangaOverrideKey]
     * or [chimahon.dictionary.DictionaryProfileResolver.sourceOverrideKey] to build the key.
     */
    fun rawProfileOverride(key: String) = preferenceStore.getString(key, "")

    /**
     * The single [chimahon.dictionary.DictionaryProfileResolver] instance.
     * Reads override keys directly from [preferenceStore].
     */
    val profileResolver: chimahon.dictionary.DictionaryProfileResolver by lazy {
        chimahon.dictionary.DictionaryProfileResolver(
            profileStore = profileStore,
            readMangaOverride = { mangaId ->
                preferenceStore.getString(
                    chimahon.dictionary.DictionaryProfileResolver.mangaOverrideKey(mangaId), "",
                ).get()
            },
            readAnimeOverride = { animeId ->
                preferenceStore.getString(
                    chimahon.dictionary.DictionaryProfileResolver.animeOverrideKey(animeId), "",
                ).get()
            },
            readSourceOverride = { sourceId ->
                preferenceStore.getString(
                    chimahon.dictionary.DictionaryProfileResolver.sourceOverrideKey(sourceId), "",
                ).get()
            },
            readNovelOverride = { novelId ->
                preferenceStore.getString(
                    chimahon.dictionary.DictionaryProfileResolver.novelOverrideKey(novelId), "",
                ).get()
            },
        )
    }

    /**
     * Delete a profile by ID and clean up any manga/source override keys that
     * pointed to it, so orphaned overrides can never resolve to a ghost profile.
     * Returns false (and does nothing) if it is the last profile.
     */
    fun deleteProfileWithOverrides(profileId: String): Boolean {
        val deleted = profileStore.deleteProfile(profileId)
        if (!deleted) return false

        // Sweep all pref keys — any override that matched the deleted ID becomes ""
        val mangaPrefix = "pref_dict_profile_manga_"
        val animePrefix = "pref_dict_profile_anime_"
        val sourcePrefix = "pref_dict_profile_source_"
        val novelPrefix = "pref_dict_profile_novel_"
        preferenceStore.getAll().keys
            .filter {
                it.startsWith(mangaPrefix) || it.startsWith(animePrefix) ||
                    it.startsWith(sourcePrefix) || it.startsWith(novelPrefix)
            }
            .forEach { key ->
                val pref = preferenceStore.getString(key, "")
                if (pref.get() == profileId) pref.delete()
            }
        return true
    }

    // -------------------------------------------------------------------------
    // Legacy flat-key READERS — only used for one-time migration.
    // -------------------------------------------------------------------------

    fun legacyAnkiDeck() = preferenceStore.getString("pref_anki_deck", "")
    fun legacyAnkiModel() = preferenceStore.getString("pref_anki_model", "")
    fun legacyAnkiFieldMap() = preferenceStore.getString("pref_anki_field_map", "{}")
    fun legacyAnkiDuplicateCheck() = preferenceStore.getBoolean("pref_anki_duplicate_check", true)
    fun legacyAnkiDuplicateScope() = preferenceStore.getString("pref_anki_duplicate_scope", "deck")
    fun legacyAnkiDuplicateAction() = preferenceStore.getString("pref_anki_duplicate_action", "prevent")
    fun legacyAnkiDefaultTags() = preferenceStore.getString("pref_anki_default_tags", "chimahon")
    fun legacyAnkiCropMode() = preferenceStore.getString("pref_dict_anki_crop_mode", "full")

    // -------------------------------------------------------------------------
    // Dictionary display names (dirName → displayName, stored as JSON map)
    // -------------------------------------------------------------------------

    fun displayNames() = preferenceStore.getString("pref_display_names", "{}")

    /**
     * Get display name for a directory, or null if none is set.
     */
    fun getDisplayName(dirName: String): String? {
        val json = displayNames().get()
        if (json.isBlank() || json == "{}") return null
        return try {
            org.json.JSONObject(json).optString(dirName, null)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Set display name for a directory. Pass null to remove it.
     */
    fun setDisplayName(dirName: String, displayName: String?) {
        val json = displayNames().get()
        val obj = try {
            if (json.isBlank() || json == "{}") org.json.JSONObject()
            else org.json.JSONObject(json)
        } catch (_: Exception) {
            org.json.JSONObject()
        }
        if (displayName != null) {
            obj.put(dirName, displayName)
        } else {
            obj.remove(dirName)
        }
        displayNames().set(obj.toString())
    }

    /**
     * One-time cleanup: clear stale migration artifacts from a previous version.
     * Resets display names and removes the migration flag so it runs only once.
     */
    fun clearMigrationArtifacts() {
        val migrated = preferenceStore.getBoolean("pref_display_names_migrated", false).get()
        if (migrated) {
            displayNames().set("{}")
            preferenceStore.getBoolean("pref_display_names_migrated", false).set(false)
        }
    }

    /** Legacy global dictionary order — kept only to supply the initial migration list. */
    fun dictionaryOrder() = preferenceStore.getString("pref_dictionary_order", "")

    // -------------------------------------------------------------------------
    // Dictionary auto-update
    // -------------------------------------------------------------------------

    fun autoUpdateEnabled() = preferenceStore.getBoolean("pref_dict_auto_update", false)
    fun autoUpdateInterval() = preferenceStore.getInt("pref_dict_auto_update_interval", 24)
    fun lastDictUpdateCheck() = preferenceStore.getLong("pref_last_dict_update_check", 0L)
    fun dictUpdateCheckState() = preferenceStore.getString("pref_dict_update_check_state", "idle")

    companion object {
        const val FREQ_HARMONIC = "harmonic"
        const val FREQ_AVERAGE = "average"
        const val PITCH_DIAGRAM = "diagram"
        const val PITCH_NUMBER = "number"
        const val PITCH_TEXT = "text"

        const val SCROLL_SMOOTH = "smooth"
        const val SCROLL_INSTANT = "instant"

        const val RENDERER_WEBVIEW = "webview"
        const val RENDERER_COMPOSE = "compose"
        const val RENDERER_PLAIN_TEXT = "plain_text"
    }

    // -------------------------------------------------------------------------
    // Word Audio Preferences (Implementing WordAudioPreferences interface)
    // -------------------------------------------------------------------------

    override fun wordAudioEnabled() = preferenceStore.getBoolean("pref_word_audio_enabled", true)
    override fun wordAudioAutoplay() = preferenceStore.getBoolean("pref_word_audio_autoplay", false)
    
    /** JSON list of WordAudioSource */
    override fun wordAudioSources() = preferenceStore.getString("pref_word_audio_sources", "[]")
    
    /** Local android.db file path */
    override fun wordAudioLocalPath() = preferenceStore.getString("pref_word_audio_local_path", "")

    /** SAF Uri string for the audio database */
    override fun wordAudioLocalUri() = preferenceStore.getString("pref_word_audio_local_uri", "")

    override fun wordAudioLocalEnabled() = preferenceStore.getBoolean("pref_word_audio_local_enabled", false)
}
