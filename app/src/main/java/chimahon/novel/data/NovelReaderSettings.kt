package chimahon.novel.data

import android.content.Context
import android.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.novelReaderDataStore: DataStore<Preferences> by preferencesDataStore(name = "novel_reader_settings")

enum class Theme {
    SYSTEM,
    LIGHT,
    DARK,
    SEPIA,
    CUSTOM,
    PURE_BLACK,
}

data class CustomReaderTheme(
    val name: String = "",
    val backgroundColor: Int,
    val textColor: Int,
)

enum class StatisticsAutostartMode {
    OFF,
    ON,
    PAGETURN,
}

class NovelReaderSettings(private val context: Context, private val namespace: String? = null) {

    companion object {
        private const val MAX_CUSTOM_THEMES = 24

        fun default(): NovelReaderSettings {
            throw IllegalStateException("NovelReaderSettings requires a Context. Pass context from Activity.")
        }
    }

    private fun prefName(name: String): String {
        return if (namespace.isNullOrEmpty()) name else "${namespace}_$name"
    }

    private fun stringKey(name: String): androidx.datastore.preferences.core.Preferences.Key<String> {
        return stringPreferencesKey(prefName(name))
    }

    private fun booleanKey(name: String): androidx.datastore.preferences.core.Preferences.Key<Boolean> {
        return booleanPreferencesKey(prefName(name))
    }

    private fun intKey(name: String): androidx.datastore.preferences.core.Preferences.Key<Int> {
        return intPreferencesKey(prefName(name))
    }

    private fun doubleKey(name: String): androidx.datastore.preferences.core.Preferences.Key<Double> {
        return doublePreferencesKey(prefName(name))
    }

    private val dataStore = context.novelReaderDataStore

    private fun encodeCustomThemes(themes: List<CustomReaderTheme>): String {
        return themes.joinToString("|") { "${it.name}~${it.backgroundColor},${it.textColor}" }
    }

    private fun decodeCustomThemes(value: String?): List<CustomReaderTheme> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split("|")
            .mapNotNull { encoded ->
                val parts = encoded.split("~", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val colorParts = parts[1].split(",")
                val backgroundColor = colorParts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val textColor = colorParts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                CustomReaderTheme(name = parts[0], backgroundColor = backgroundColor, textColor = textColor)
            }
            .distinct()
    }

    private fun androidx.datastore.preferences.core.Preferences.getSafeDouble(key: androidx.datastore.preferences.core.Preferences.Key<Double>, default: Double): Double {
        return try {
            this[key] ?: default
        } catch (e: ClassCastException) {
            val intKey = androidx.datastore.preferences.core.intPreferencesKey(key.name)
            this[intKey]?.toDouble() ?: default
        }
    }

    private fun androidx.datastore.preferences.core.Preferences.getSafeInt(key: androidx.datastore.preferences.core.Preferences.Key<Int>, default: Int): Int {
        return try {
            this[key] ?: default
        } catch (e: ClassCastException) {
            val doubleKey = androidx.datastore.preferences.core.doublePreferencesKey(key.name)
            this[doubleKey]?.toInt() ?: default
        }
    }

    val theme: Flow<Theme> = dataStore.data.map { prefs ->
        Theme.valueOf(prefs[keys.THEME] ?: Theme.SYSTEM.name)
    }

    val systemLightSepia: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.SYSTEM_LIGHT_SEPIA] ?: false
    }

    val uiTheme: Flow<Theme> = dataStore.data.map { prefs ->
        Theme.valueOf(prefs[keys.UI_THEME] ?: Theme.SYSTEM.name)
    }

    val customBackgroundColor: Flow<Int> = dataStore.data.map { prefs ->
        prefs[keys.CUSTOM_BACKGROUND_COLOR] ?: 0xFFF2E2C9.toInt()
    }

    val customTextColor: Flow<Int> = dataStore.data.map { prefs ->
        prefs[keys.CUSTOM_TEXT_COLOR] ?: Color.BLACK
    }

    val customThemes: Flow<List<CustomReaderTheme>> = dataStore.data.map { prefs ->
        decodeCustomThemes(prefs[keys.customThemes])
    }

    val customInfoColor: Flow<Int> = dataStore.data.map { prefs ->
        prefs[keys.CUSTOM_INFO_COLOR] ?: Color.DKGRAY
    }

    val verticalWriting: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.VERTICAL_WRITING] ?: true
    }

    /**
     * Per-novel writing direction. Key is namespace-free on purpose so
     * every settings profile resolves the same novel identically. Null =
     * never set (first open infers from language, then stores).
     */
    fun verticalWritingFor(novelKey: String): Flow<Boolean?> =
        dataStore.data.map { prefs ->
            prefs[booleanPreferencesKey("vertical_writing_novel_$novelKey")]
        }

    suspend fun setVerticalWritingFor(novelKey: String, value: Boolean) {
        dataStore.edit { prefs ->
            prefs[booleanPreferencesKey("vertical_writing_novel_$novelKey")] = value
        }
    }

    val selectedFont: Flow<String> = dataStore.data.map { prefs ->
        prefs[keys.SELECTED_FONT] ?: "System"
    }

    val fontSize: Flow<Double> = dataStore.data.map { prefs ->
        prefs.getSafeDouble(keys.FONT_SIZE, 18.0)
    }

    val readerHideFurigana: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.READER_HIDE_FURIGANA] ?: false
    }

    val continuousMode: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.CONTINUOUS_MODE] ?: false
    }

    val chapterSwipeDistance: Flow<Int> = dataStore.data.map { prefs ->
        prefs.getSafeInt(keys.CHAPTER_SWIPE_DISTANCE, 96)
    }

    val chapterTapZones: Flow<Int> = dataStore.data.map { prefs ->
        prefs.getSafeInt(keys.CHAPTER_TAP_ZONES, 20)
    }

    val chapterCacheSizeMb: Flow<Int> = dataStore.data.map { prefs ->
        prefs.getSafeInt(keys.CHAPTER_CACHE_SIZE_MB, 100)
    }

    val horizontalPadding: Flow<Double> = dataStore.data.map { prefs ->
        prefs.getSafeDouble(keys.HORIZONTAL_PADDING, 10.0)
    }

    val verticalPadding: Flow<Double> = dataStore.data.map { prefs ->
        prefs.getSafeDouble(keys.VERTICAL_PADDING, 10.0)
    }

    val avoidPageBreak: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.AVOID_PAGE_BREAK] ?: true
    }

    val justifyText: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.JUSTIFY_TEXT] ?: false
    }

    val layoutAdvanced: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.LAYOUT_ADVANCED] ?: false
    }

    val lineHeight: Flow<Double> = dataStore.data.map { prefs ->
        prefs.getSafeDouble(keys.LINE_HEIGHT, 1.6)
    }

    val characterSpacing: Flow<Double> = dataStore.data.map { prefs ->
        prefs.getSafeDouble(keys.CHARACTER_SPACING, 0.0)
    }

    val paragraphSpacing: Flow<Double> = dataStore.data.map { prefs ->
        prefs.getSafeDouble(keys.PARAGRAPH_SPACING, 0.0)
    }

    val readerShowTitle: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.READER_SHOW_TITLE] ?: true
    }

    val readerShowCharacters: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.READER_SHOW_CHARACTERS] ?: true
    }

    val readerShowPercentage: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.READER_SHOW_PERCENTAGE] ?: true
    }

    val readerShowProgressTop: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.READER_SHOW_PROGRESS_TOP] ?: true
    }

    val enableStatistics: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.ENABLE_STATISTICS] ?: true
    }

    val statisticsAutostartMode: Flow<StatisticsAutostartMode> = dataStore.data.map { prefs ->
        StatisticsAutostartMode.valueOf(prefs[keys.STATISTICS_AUTOSTART_MODE] ?: StatisticsAutostartMode.OFF.name)
    }

    val readerShowReadingSpeed: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.READER_SHOW_READING_SPEED] ?: true
    }

    val readerShowReadingTime: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.READER_SHOW_READING_TIME] ?: true
    }

    val popupWidth: Flow<Int> = dataStore.data.map { prefs ->
        prefs.getSafeInt(keys.POPUP_WIDTH, 300)
    }

    val popupHeight: Flow<Int> = dataStore.data.map { prefs ->
        prefs.getSafeInt(keys.POPUP_HEIGHT, 200)
    }

    val popupFullWidth: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.POPUP_FULL_WIDTH] ?: false
    }

    val popupSwipeToDismiss: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.POPUP_SWIPE_TO_DISMISS] ?: true
    }

    val popupSwipeThreshold: Flow<Int> = dataStore.data.map { prefs ->
        prefs.getSafeInt(keys.POPUP_SWIPE_THRESHOLD, 50)
    }

    val maxResults: Flow<Int> = dataStore.data.map { prefs ->
        prefs.getSafeInt(keys.MAX_RESULTS, 10)
    }

    val scanLength: Flow<Int> = dataStore.data.map { prefs ->
        prefs.getSafeInt(keys.SCAN_LENGTH, 50)
    }

    val keepScreenOn: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.KEEP_SCREEN_ON] ?: false
    }

    val einkRefreshOnPageTurn: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.EINK_REFRESH_ON_PAGE_TURN] ?: false
    }

    val einkRefreshOnScroll: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[keys.EINK_REFRESH_ON_SCROLL] ?: false
    }

    val einkRefreshDurationMillis: Flow<Int> = dataStore.data.map { prefs ->
        prefs.getSafeInt(keys.EINK_REFRESH_DURATION_MILLIS, 100)
    }

    val einkRefreshDelayMillis: Flow<Int> = dataStore.data.map { prefs ->
        prefs.getSafeInt(keys.EINK_REFRESH_DELAY_MILLIS, 0)
    }

    val einkRefreshPageInterval: Flow<Int> = dataStore.data.map { prefs ->
        prefs.getSafeInt(keys.EINK_REFRESH_PAGE_INTERVAL, 1)
    }

    val einkRefreshColor: Flow<String> = dataStore.data.map { prefs ->
        prefs[keys.EINK_REFRESH_COLOR] ?: "BLACK"
    }

    suspend fun setTheme(value: Theme) {
        dataStore.edit { prefs ->
            prefs[keys.THEME] = value.name
        }
    }

    suspend fun setSystemLightSepia(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.SYSTEM_LIGHT_SEPIA] = value
        }
    }

    suspend fun setUiTheme(value: Theme) {
        dataStore.edit { prefs ->
            prefs[keys.UI_THEME] = value.name
        }
    }

    suspend fun setCustomBackgroundColor(value: Int) {
        dataStore.edit { prefs ->
            prefs[keys.CUSTOM_BACKGROUND_COLOR] = value
        }
    }

    suspend fun setCustomTextColor(value: Int) {
        dataStore.edit { prefs ->
            prefs[keys.CUSTOM_TEXT_COLOR] = value
        }
    }

    suspend fun setCustomTheme(value: CustomReaderTheme) {
        dataStore.edit { prefs ->
            prefs[keys.CUSTOM_BACKGROUND_COLOR] = value.backgroundColor
            prefs[keys.CUSTOM_TEXT_COLOR] = value.textColor
            prefs[keys.THEME] = Theme.CUSTOM.name
        }
    }

    suspend fun addCustomTheme(value: CustomReaderTheme) {
        dataStore.edit { prefs ->
            val existing = decodeCustomThemes(prefs[keys.customThemes])
            val next = (existing + value).distinct().takeLast(MAX_CUSTOM_THEMES)
            prefs[keys.customThemes] = encodeCustomThemes(next)
            prefs[keys.CUSTOM_BACKGROUND_COLOR] = value.backgroundColor
            prefs[keys.CUSTOM_TEXT_COLOR] = value.textColor
            prefs[keys.THEME] = Theme.CUSTOM.name
        }
    }

    suspend fun deleteCustomTheme(value: CustomReaderTheme) {
        dataStore.edit { prefs ->
            val existing = decodeCustomThemes(prefs[keys.customThemes])
            val next = existing.filter { it != value }
            prefs[keys.customThemes] = encodeCustomThemes(next)
        }
    }

    suspend fun renameCustomTheme(old: CustomReaderTheme, newName: String) {
        dataStore.edit { prefs ->
            val existing = decodeCustomThemes(prefs[keys.customThemes])
            val next = existing.map {
                if (it == old) it.copy(name = newName) else it
            }
            prefs[keys.customThemes] = encodeCustomThemes(next)
        }
    }

    suspend fun setCustomInfoColor(value: Int) {
        dataStore.edit { prefs ->
            prefs[keys.CUSTOM_INFO_COLOR] = value
        }
    }

    suspend fun setVerticalWriting(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.VERTICAL_WRITING] = value
        }
    }

    suspend fun setSelectedFont(value: String) {
        dataStore.edit { prefs ->
            prefs[keys.SELECTED_FONT] = value
        }
    }

    suspend fun setFontSize(value: Double) {
        dataStore.edit { prefs ->
            prefs[keys.FONT_SIZE] = value
        }
    }

    suspend fun setReaderHideFurigana(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.READER_HIDE_FURIGANA] = value
        }
    }

    suspend fun setContinuousMode(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.CONTINUOUS_MODE] = value
        }
    }

    suspend fun setChapterSwipeDistance(value: Int) {
        dataStore.edit { prefs ->
            prefs[keys.CHAPTER_SWIPE_DISTANCE] = value
        }
    }

    suspend fun setChapterTapZones(value: Int) {
        dataStore.edit { prefs ->
            prefs[keys.CHAPTER_TAP_ZONES] = value
        }
    }

    suspend fun setChapterCacheSizeMb(value: Int) {
        dataStore.edit { prefs ->
            prefs[keys.CHAPTER_CACHE_SIZE_MB] = value.coerceIn(10, 1000)
        }
    }

    suspend fun setHorizontalPadding(value: Double) {
        dataStore.edit { prefs ->
            prefs[keys.HORIZONTAL_PADDING] = value
        }
    }

    suspend fun setVerticalPadding(value: Double) {
        dataStore.edit { prefs ->
            prefs[keys.VERTICAL_PADDING] = value
        }
    }

    suspend fun setAvoidPageBreak(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.AVOID_PAGE_BREAK] = value
        }
    }

    suspend fun setJustifyText(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.JUSTIFY_TEXT] = value
        }
    }

    suspend fun setLayoutAdvanced(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.LAYOUT_ADVANCED] = value
        }
    }

    suspend fun setLineHeight(value: Double) {
        dataStore.edit { prefs ->
            prefs[keys.LINE_HEIGHT] = value
        }
    }

    suspend fun setCharacterSpacing(value: Double) {
        dataStore.edit { prefs ->
            prefs[keys.CHARACTER_SPACING] = value
        }
    }

    suspend fun setParagraphSpacing(value: Double) {
        dataStore.edit { prefs ->
            prefs[keys.PARAGRAPH_SPACING] = value
        }
    }

    suspend fun setReaderShowTitle(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.READER_SHOW_TITLE] = value
        }
    }

    suspend fun setReaderShowCharacters(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.READER_SHOW_CHARACTERS] = value
        }
    }

    suspend fun setReaderShowPercentage(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.READER_SHOW_PERCENTAGE] = value
        }
    }

    suspend fun setReaderShowProgressTop(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.READER_SHOW_PROGRESS_TOP] = value
        }
    }

    suspend fun setEnableStatistics(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.ENABLE_STATISTICS] = value
        }
    }

    suspend fun setStatisticsAutostartMode(value: StatisticsAutostartMode) {
        dataStore.edit { prefs ->
            prefs[keys.STATISTICS_AUTOSTART_MODE] = value.name
        }
    }

    suspend fun setReaderShowReadingSpeed(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.READER_SHOW_READING_SPEED] = value
        }
    }

    suspend fun setReaderShowReadingTime(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.READER_SHOW_READING_TIME] = value
        }
    }

    suspend fun setPopupWidth(value: Int) {
        dataStore.edit { prefs ->
            prefs[keys.POPUP_WIDTH] = value
        }
    }

    suspend fun setPopupHeight(value: Int) {
        dataStore.edit { prefs ->
            prefs[keys.POPUP_HEIGHT] = value
        }
    }

    suspend fun setPopupFullWidth(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.POPUP_FULL_WIDTH] = value
        }
    }

    suspend fun setPopupSwipeToDismiss(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.POPUP_SWIPE_TO_DISMISS] = value
        }
    }

    suspend fun setPopupSwipeThreshold(value: Int) {
        dataStore.edit { prefs ->
            prefs[keys.POPUP_SWIPE_THRESHOLD] = value
        }
    }

    suspend fun setMaxResults(value: Int) {
        dataStore.edit { prefs ->
            prefs[keys.MAX_RESULTS] = value
        }
    }

    suspend fun setScanLength(value: Int) {
        dataStore.edit { prefs ->
            prefs[keys.SCAN_LENGTH] = value
        }
    }

    suspend fun setKeepScreenOn(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.KEEP_SCREEN_ON] = value
        }
    }

    suspend fun setEinkRefreshOnPageTurn(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.EINK_REFRESH_ON_PAGE_TURN] = value
        }
    }

    suspend fun setEinkRefreshOnScroll(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[keys.EINK_REFRESH_ON_SCROLL] = value
        }
    }

    suspend fun setEinkRefreshDurationMillis(value: Int) {
        dataStore.edit { prefs ->
            prefs[keys.EINK_REFRESH_DURATION_MILLIS] = value
        }
    }

    suspend fun setEinkRefreshDelayMillis(value: Int) {
        dataStore.edit { prefs ->
            prefs[keys.EINK_REFRESH_DELAY_MILLIS] = value
        }
    }

    suspend fun setEinkRefreshPageInterval(value: Int) {
        dataStore.edit { prefs ->
            prefs[keys.EINK_REFRESH_PAGE_INTERVAL] = value
        }
    }

    suspend fun setEinkRefreshColor(value: String) {
        dataStore.edit { prefs ->
            prefs[keys.EINK_REFRESH_COLOR] = value
        }
    }

    @Suppress("PropertyName", "ktlint:standard:property-naming")
    private inner class PreferencesKeys {
        val THEME = stringKey("theme")
        val SYSTEM_LIGHT_SEPIA = booleanKey("system_light_sepia")
        val UI_THEME = stringKey("ui_theme")
        val CUSTOM_BACKGROUND_COLOR = intKey("custom_background_color")
        val CUSTOM_TEXT_COLOR = intKey("custom_text_color")
        val customThemes = stringKey("custom_themes")
        val CUSTOM_INFO_COLOR = intKey("custom_info_color")
        val VERTICAL_WRITING = booleanKey("vertical_writing")
        val SELECTED_FONT = stringKey("selected_font")
        val FONT_SIZE = doubleKey("font_size")
        val READER_HIDE_FURIGANA = booleanKey("reader_hide_furigana")
        val CONTINUOUS_MODE = booleanKey("continuous_mode")
        val CHAPTER_SWIPE_DISTANCE = intKey("chapter_swipe_distance")
        val CHAPTER_TAP_ZONES = intKey("chapter_tap_zones")
        val HORIZONTAL_PADDING = doubleKey("horizontal_padding")
        val VERTICAL_PADDING = doubleKey("vertical_padding")
        val AVOID_PAGE_BREAK = booleanKey("avoid_page_break")
        val JUSTIFY_TEXT = booleanKey("justify_text")
        val LAYOUT_ADVANCED = booleanKey("layout_advanced")
        val LINE_HEIGHT = doubleKey("line_height")
        val CHARACTER_SPACING = doubleKey("character_spacing")
        val PARAGRAPH_SPACING = doubleKey("paragraph_spacing")
        val READER_SHOW_TITLE = booleanKey("reader_show_title")
        val READER_SHOW_CHARACTERS = booleanKey("reader_show_characters")
        val READER_SHOW_PERCENTAGE = booleanKey("reader_show_percentage")
        val READER_SHOW_PROGRESS_TOP = booleanKey("reader_show_progress_top")
        val ENABLE_STATISTICS = booleanKey("enable_statistics")
        val STATISTICS_AUTOSTART_MODE = stringKey("statistics_autostart_mode")
        val READER_SHOW_READING_SPEED = booleanKey("reader_show_reading_speed")
        val READER_SHOW_READING_TIME = booleanKey("reader_show_reading_time")
        val POPUP_WIDTH = intKey("popup_width")
        val POPUP_HEIGHT = intKey("popup_height")
        val POPUP_FULL_WIDTH = booleanKey("popup_full_width")
        val POPUP_SWIPE_TO_DISMISS = booleanKey("popup_swipe_to_dismiss")
        val POPUP_SWIPE_THRESHOLD = intKey("popup_swipe_threshold")
        val MAX_RESULTS = intKey("max_results")
        val SCAN_LENGTH = intKey("scan_length")
        val KEEP_SCREEN_ON = booleanKey("keep_screen_on")
        val EINK_REFRESH_ON_PAGE_TURN = booleanKey("eink_refresh_on_page_turn")
        val EINK_REFRESH_ON_SCROLL = booleanKey("eink_refresh_on_scroll")
        val EINK_REFRESH_DURATION_MILLIS = intKey("eink_refresh_duration_millis")
        val EINK_REFRESH_DELAY_MILLIS = intKey("eink_refresh_delay_millis")
        val EINK_REFRESH_PAGE_INTERVAL = intKey("eink_refresh_page_interval")
        val EINK_REFRESH_COLOR = stringKey("eink_refresh_color")
        val CHAPTER_CACHE_SIZE_MB = intKey("chapter_cache_size_mb")
    }

    private val keys = PreferencesKeys()
}

val Context.novelReaderSettings: NovelReaderSettings
    get() = NovelReaderSettings(this)
