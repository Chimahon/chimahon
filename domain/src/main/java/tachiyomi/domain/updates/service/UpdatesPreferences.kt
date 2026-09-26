package tachiyomi.domain.updates.service

import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getEnum

class UpdatesPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun filterDownloaded() = preferenceStore.getEnum(
        "pref_filter_updates_downloaded",
        TriState.DISABLED,
    )

    fun filterUnread() = preferenceStore.getEnum(
        "pref_filter_updates_unread",
        TriState.DISABLED,
    )

    fun filterStarted() = preferenceStore.getEnum(
        "pref_filter_updates_started",
        TriState.DISABLED,
    )

    fun filterBookmarked() = preferenceStore.getEnum(
        "pref_filter_updates_bookmarked",
        TriState.DISABLED,
    )

    fun filterExcludedScanlators() = preferenceStore.getBoolean(
        "pref_filter_updates_hide_excluded_scanlators",
        false,
    )

    // KMK -->
    fun usePanoramaCover() = preferenceStore.getBoolean(
        USE_PANORAMA_COVER_PREF,
        false,
    )
    // KMK <--

    // AY -->
    fun filterSeen() = preferenceStore.getEnum(
        "pref_filter_anime_updates_seen",
        TriState.DISABLED,
    )

    fun filterStartedAnime() = preferenceStore.getEnum(
        "pref_filter_anime_updates_started",
        TriState.DISABLED,
    )

    fun filterBookmarkedAnime() = preferenceStore.getEnum(
        "pref_filter_anime_updates_bookmarked",
        TriState.DISABLED,
    )

    fun filterFillermarked() = preferenceStore.getEnum(
        "pref_filter_anime_updates_fillermarked",
        TriState.DISABLED,
    )
    // <-- AY
}

// KMK -->
const val USE_PANORAMA_COVER_PREF = "pref_updates_history_screen_use_panorama_cover"
// KMK <--
