package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import eu.kanade.presentation.more.settings.Preference
import tachiyomi.i18n.kmk.KMR

/**
 * Per-profile dictionary categorization (None / Monolingual / Bilingual / Exclude).
 * Preference widgets are shared from [SettingsDictionaryScreen].
 */
object SettingsDictionaryCategoriesScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.pref_dict_categorize

    @Composable
    override fun getPreferences(): List<Preference> =
        SettingsDictionaryScreen.categoryPreferences()
}
