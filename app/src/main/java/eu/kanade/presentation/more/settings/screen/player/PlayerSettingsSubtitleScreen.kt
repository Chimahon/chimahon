package eu.kanade.presentation.more.settings.screen.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import eu.kanade.tachiyomi.ui.player.utils.SubtitleFontResolver
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import java.util.MissingResourceException

object PlayerSettingsSubtitleScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_player_subtitle

    @Composable
    override fun getPreferences(): List<Preference> {
        val subtitlePreferences = remember { Injekt.get<SubtitlePreferences>() }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var importing by remember { mutableStateOf(false) }
        val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    importing = true
                    try {
                        context.toast(
                            if (SubtitleFontResolver.importFont(context, uri)) {
                                MR.strings.subtitle_font_imported
                            } else {
                                MR.strings.subtitle_font_import_failed
                            },
                        )
                    } finally {
                        importing = false
                    }
                }
            }
        }

        val langPref = subtitlePreferences.preferredSubLanguages()
        val whitelist = subtitlePreferences.subtitleWhitelist()
        val blacklist = subtitlePreferences.subtitleBlacklist()
        val blackBars = subtitlePreferences.subtitleBlackBars()
        val jimakuApiKey = subtitlePreferences.jimakuApiKey()
        val systemFonts = subtitlePreferences.subtitleSystemFonts()

        return listOf(
            Preference.PreferenceItem.EditTextInfoPreference(
                preference = langPref,
                dialogSubtitle = stringResource(MR.strings.pref_player_subtitle_lang_info),
                title = stringResource(MR.strings.pref_player_subtitle_lang),
                validate = { pref ->
                    val langs = pref.split(",").filter(String::isNotEmpty).map(String::trim)
                    langs.forEach {
                        try {
                            val locale = Locale(it)
                            if (locale.isO3Language == locale.language &&
                                locale.language == locale.getDisplayName(Locale.ENGLISH)
                            ) {
                                throw MissingResourceException("", "", "")
                            }
                        } catch (_: MissingResourceException) {
                            return@EditTextInfoPreference false
                        }
                    }

                    true
                },
                errorMessage = { pref ->
                    val langs = pref.split(",").filter(String::isNotEmpty).map(String::trim)
                    langs.forEach {
                        try {
                            val locale = Locale(it)
                            if (locale.isO3Language == locale.language &&
                                locale.language == locale.getDisplayName(Locale.ENGLISH)
                            ) {
                                throw MissingResourceException("", "", "")
                            }
                        } catch (_: MissingResourceException) {
                            return@EditTextInfoPreference stringResource(
                                MR.strings.pref_player_subtitle_invalid_lang,
                                it,
                            )
                        }
                    }
                    ""
                },
            ),
            Preference.PreferenceItem.EditTextInfoPreference(
                preference = whitelist,
                dialogSubtitle = stringResource(MR.strings.pref_player_subtitle_whitelist_info),
                title = stringResource(MR.strings.pref_player_subtitle_whitelist),
            ),
            Preference.PreferenceItem.EditTextInfoPreference(
                preference = blacklist,
                title = stringResource(MR.strings.pref_player_subtitle_blacklist),
                dialogSubtitle = stringResource(MR.strings.pref_player_subtitle_blacklist_info),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = blackBars,
                title = stringResource(MR.strings.player_pref_subtitle_black_bars),
                subtitle = stringResource(MR.strings.player_pref_subtitle_black_bars_summary),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = systemFonts,
                title = stringResource(MR.strings.player_pref_subtitle_system_fonts),
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.import_subtitle_font),
                subtitle = stringResource(MR.strings.import_subtitle_font_summary),
                enabled = !importing,
                onClick = {
                    fontPicker.launch(
                        arrayOf(
                            "font/*",
                            "application/x-font-ttf",
                            "application/x-font-opentype",
                            "application/octet-stream",
                        ),
                    )
                },
            ),
            Preference.PreferenceItem.EditTextPreference(
                preference = jimakuApiKey,
                title = "Jimaku API key",
                subtitle = "Required to search and download Jimaku subtitles",
            ),
        )
    }
}
