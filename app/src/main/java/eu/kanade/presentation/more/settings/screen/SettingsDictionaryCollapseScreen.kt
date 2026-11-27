package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import eu.kanade.presentation.more.settings.Preference
import tachiyomi.i18n.kmk.KMR

/**
 * Per-profile collapsible dictionary settings (global mode + per-dictionary overrides).
 * Preference widgets are shared from [SettingsDictionaryScreen].
 */
object SettingsDictionaryCollapseScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_dict_collapse_behavior

    @Composable
    override fun getPreferences(): List<Preference> =
        SettingsDictionaryScreen.collapsePreferences()
}
