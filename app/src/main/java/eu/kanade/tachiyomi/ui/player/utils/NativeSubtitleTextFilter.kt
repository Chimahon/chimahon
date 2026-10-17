package eu.kanade.tachiyomi.ui.player.utils

import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import `is`.xyz.mpv.MPVLib

/** Reuses the existing regex semantics before native layout, on MPV's render threads. */
internal class NativeSubtitleTextFilter(private val preferences: SubtitlePreferences) : MPVLib.SubtitleTextFilter {
    private var options: SubtitleRegexFilterOptions? = null
    private val cache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 128
    }

    @Synchronized
    override fun filter(text: String): String? {
        val current = preferences.subtitleRegexFilterOptions()
        if (options != current) {
            options = current
            cache.clear()
        }
        if (!current.enabled) return null
        return cache.getOrPut(text) { text.applySubtitleRegexFilters(current) }
    }
}
