package eu.kanade.tachiyomi.sourcenovel

import androidx.preference.PreferenceScreen

interface NovelConfigurableSource {
    fun setupPreferenceScreen(screen: PreferenceScreen)
}
