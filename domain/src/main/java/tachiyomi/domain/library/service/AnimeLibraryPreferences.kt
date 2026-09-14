package tachiyomi.domain.library.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getEnum
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibrarySort

class AnimeLibraryPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun displayMode() = preferenceStore.getObjectFromString(
        "pref_display_mode_animelib",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    fun sortingMode() = preferenceStore.getObjectFromString(
        "animelib_sorting_mode",
        LibrarySort.default,
        LibrarySort.Serializer::serialize,
        LibrarySort.Serializer::deserialize,
    )

    fun randomSortSeed() = preferenceStore.getInt("library_random_anime_sort_seed", 0)

    fun portraitColumns() = preferenceStore.getInt("pref_animelib_columns_portrait_key", 0)

    fun landscapeColumns() = preferenceStore.getInt("pref_animelib_columns_landscape_key", 0)

    fun showContinueWatchingButton() = preferenceStore.getBoolean(
        "display_continue_watching_button",
        false,
    )

    // region Filter

    fun filterDownloaded() = preferenceStore.getEnum(
        "pref_filter_animelib_downloaded_v2",
        TriState.DISABLED,
    )

    fun filterUnseen() = preferenceStore.getEnum(
        "pref_filter_animelib_unseen_v2",
        TriState.DISABLED,
    )

    fun filterStarted() = preferenceStore.getEnum(
        "pref_filter_animelib_started_v2",
        TriState.DISABLED,
    )

    fun filterBookmarked() = preferenceStore.getEnum(
        "pref_filter_animelib_bookmarked_v2",
        TriState.DISABLED,
    )

    fun filterFillermarked() = preferenceStore.getEnum(
        "pref_filter_animelib_fillermarked_v2",
        TriState.DISABLED,
    )

    fun filterCompleted() = preferenceStore.getEnum(
        "pref_filter_animelib_completed_v2",
        TriState.DISABLED,
    )

    fun filterLewd() = preferenceStore.getEnum(
        "pref_filter_animelib_lewd_v2",
        TriState.DISABLED,
    )

    fun filterTracking(id: Int) = preferenceStore.getEnum(
        "pref_filter_animelib_tracked_${id}_v2",
        TriState.DISABLED,
    )

    // endregion

    // region Badges

    fun downloadBadge() = preferenceStore.getBoolean("display_animelib_download_badge", false)

    fun unseenBadge() = preferenceStore.getBoolean("display_animelib_unseen_badge", true)

    fun localBadge() = preferenceStore.getBoolean("display_animelib_local_badge", true)

    fun languageBadge() = preferenceStore.getBoolean("display_animelib_language_badge", false)

    fun sourceBadge() = preferenceStore.getBoolean("display_animelib_source_badge", true)

    // endregion

    // region Category

    fun defaultCategory() = preferenceStore.getInt("default_anime_category", -1)

    fun lastUsedCategory() = preferenceStore.getInt(
        Preference.appStateKey("last_used_anime_category"),
        0,
    )

    fun categoryTabs() = preferenceStore.getBoolean("display_anime_category_tabs", true)

    fun categoryNumberOfItems() = preferenceStore.getBoolean(
        "display_anime_number_of_items",
        false,
    )

    fun showHiddenCategories() = preferenceStore.getBoolean("hide_anime_hidden_categories", false)

    fun categorizedDisplaySettings() = preferenceStore.getBoolean(
        "anime_categorized_display",
        false,
    )

    // endregion

    // region Grouping

    fun groupLibraryBy() = preferenceStore.getInt(
        "group_anime_library_by",
        LibraryGroup.BY_DEFAULT,
    )

    // endregion
}
