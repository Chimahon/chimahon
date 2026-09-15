package eu.kanade.tachiyomi.ui.player.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.player.controls.components.panels.SubtitlesBorderStyle
import tachiyomi.i18n.MR
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class SubtitlePreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun preferredSubLanguages() = preferenceStore.getString("pref_subtitle_lang", "")
    fun subtitleWhitelist() = preferenceStore.getString("pref_subtitle_whitelist", "")
    fun subtitleBlacklist() = preferenceStore.getString("pref_subtitle_blacklist", "")
    fun subtitleBlackBars() = preferenceStore.getBoolean("pref_subtitle_black_bars", true)
    fun jimakuApiKey() = preferenceStore.getString(Preference.privateKey(JIMAKU_API_KEY), "")
    fun jimakuTitle() = preferenceStore.getString("pref_jimaku_title", "")
    fun subtitleRegexRemoveSpeakerNames() = preferenceStore.getBoolean(
        "pref_subtitle_regex_remove_speaker_names",
        false,
    )
    fun subtitleRegexMergeMultiline() = preferenceStore.getBoolean("pref_subtitle_regex_merge_multiline", false)
    fun subtitleRegexRemoveBracketedText() = preferenceStore.getBoolean(
        "pref_subtitle_regex_remove_bracketed_text",
        false,
    )
    fun subtitleRegexRemoveUppercaseLines() = preferenceStore.getBoolean(
        "pref_subtitle_regex_remove_uppercase_lines",
        false,
    )
    fun subtitleRegexRemoveMusicSymbols() = preferenceStore.getBoolean(
        "pref_subtitle_regex_remove_music_symbols",
        false,
    )
    fun subtitleRegexRemoveCurlyBracedText() = preferenceStore.getBoolean(
        "pref_subtitle_regex_remove_curly_braced_text",
        false,
    )
    fun subtitleRegexCustomEnabled() = preferenceStore.getBoolean("pref_subtitle_regex_custom_enabled", false)
    fun subtitleRegexCustomPattern() = preferenceStore.getString("pref_subtitle_regex_custom_pattern", "")
    fun jimakuTitleForAnime(animeId: Long?) = animeId
        ?.takeIf { it > 0 }
        ?.let { preferenceStore.getString("pref_jimaku_title_anime_$it", "") }
        ?: jimakuTitle()

    // Non-preferences

    fun screenshotSubtitles() = preferenceStore.getBoolean("pref_screenshot_subtitles", false)

    fun subtitleFont() = preferenceStore.getString("pref_subtitle_font", "Sans Serif")
    fun subtitleSystemFonts(): Preference<Boolean> =
        preferenceStore.getBoolean("pref_subtitle_system_fonts", false)
    fun subtitleFontSize() = preferenceStore.getInt("pref_subtitles_font_size", 55)
    fun subtitleFontScale() = preferenceStore.getFloat("pref_sub_scale", 1f)
    fun subtitleBorderSize() = preferenceStore.getInt("pref_sub_border_size", 3)
    fun boldSubtitles() = preferenceStore.getBoolean("pref_bold_subtitles", false)
    fun italicSubtitles() = preferenceStore.getBoolean("pref_italic_subtitles", false)

    fun textColorSubtitles() = preferenceStore.getInt("pref_text_color_subtitles", Color.White.toArgb())

    fun borderColorSubtitles() = preferenceStore.getInt("pref_border_color_subtitles", Color.Black.toArgb())
    fun borderStyleSubtitles() = preferenceStore.getEnum(
        "pref_border_style_subtitles",
        SubtitlesBorderStyle.OutlineAndShadow,
    )
    fun shadowOffsetSubtitles() = preferenceStore.getInt("sub_shadow_offset", 0)
    fun backgroundColorSubtitles() = preferenceStore.getInt(
        "pref_background_color_subtitles",
        Color.Transparent.toArgb(),
    )

    fun subtitleJustification() = preferenceStore.getEnum("pref_sub_justify", SubtitleJustification.Auto)
    fun subtitlePos() = preferenceStore.getInt("pref_sub_pos", 100)

    fun overrideSubsASS() = preferenceStore.getEnum("pref_override_subtitles_ass_enum", SubtitleAssOverride.Force)

    fun subtitlesDelay() = preferenceStore.getInt("pref_subtitles_delay", 0)
    fun subtitlesDelayForAnime(animeId: Long?) = animeId
        ?.takeIf { it > 0 }
        ?.let { preferenceStore.getInt("pref_subtitles_delay_anime_$it", subtitlesDelay().get()) }
        ?: subtitlesDelay()
    fun subtitlesDelayForEpisode(episodeId: Long?, animeId: Long?) = episodeId
        ?.takeIf { it > 0 }
        ?.let {
            preferenceStore.getInt(
                "pref_subtitles_delay_episode_$it",
                subtitlesDelayForAnime(animeId).get(),
            )
        }
        ?: subtitlesDelayForAnime(animeId)

    fun subtitlesSpeed() = preferenceStore.getFloat("pref_subtitles_speed", 1f)
    fun subtitlesSpeedForEpisode(episodeId: Long?) = episodeId
        ?.takeIf { it > 0 }
        ?.let { preferenceStore.getFloat("pref_subtitles_speed_episode_$it", subtitlesSpeed().get()) }
        ?: subtitlesSpeed()
    fun subtitlesSecondaryDelay() = preferenceStore.getInt("pref_subtitles_secondary_delay", 0)
    fun subtitlesSecondaryDelayForAnime(animeId: Long?) = animeId
        ?.takeIf { it > 0 }
        ?.let {
            preferenceStore.getInt(
                "pref_subtitles_secondary_delay_anime_$it",
                subtitlesSecondaryDelay().get(),
            )
        }
        ?: subtitlesSecondaryDelay()
    fun subtitlesSecondaryDelayForEpisode(episodeId: Long?, animeId: Long?) = episodeId
        ?.takeIf { it > 0 }
        ?.let {
            preferenceStore.getInt(
                "pref_subtitles_secondary_delay_episode_$it",
                subtitlesSecondaryDelayForAnime(animeId).get(),
            )
        }
        ?: subtitlesSecondaryDelayForAnime(animeId)

    private companion object {
        const val JIMAKU_API_KEY = "pref_jimaku_api_key"
    }
}

enum class SubtitleJustification(
    val value: String,
    val icon: ImageVector,
) {
    Left("left", Icons.AutoMirrored.Default.FormatAlignLeft),
    Center("center", Icons.Default.FormatAlignCenter),
    Right("right", Icons.AutoMirrored.Default.FormatAlignRight),
    Auto("auto", Icons.Default.FormatAlignJustify),
}

enum class SubtitleAssOverride(
    val value: String,
    val titleRes: StringResource,
) {
    No("no", MR.strings.player_sheets_subtitles_ass_no),
    Yes("yes", MR.strings.player_sheets_subtitles_ass_yes),
    Scale("scale", MR.strings.player_sheets_subtitles_ass_scale),
    Force("force", MR.strings.player_sheets_subtitles_ass_force),
    Strip("strip", MR.strings.player_sheets_subtitles_ass_strip),
    ;

    companion object {
        fun byValue(value: String): SubtitleAssOverride =
            entries.firstOrNull { it.value == value } ?: No
    }
}
