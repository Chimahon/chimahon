package tachiyomi.domain.library.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.preference.getEnum
import tachiyomi.domain.library.model.GroupLibraryMode
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryGroup
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.SeasonDisplayMode
import tachiyomi.domain.manga.model.Manga

class LibraryPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun displayMode() = preferenceStore.getObjectFromString(
        "pref_display_mode_library",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    fun sortingMode() = preferenceStore.getObjectFromString(
        "library_sorting_mode",
        LibrarySort.default,
        LibrarySort.Serializer::serialize,
        LibrarySort.Serializer::deserialize,
    )

    fun randomSortSeed() = preferenceStore.getInt("library_random_sort_seed", 0)

    fun portraitColumns() = preferenceStore.getInt("pref_library_columns_portrait_key", 0)
    fun landscapeColumns() = preferenceStore.getInt("pref_library_columns_landscape_key", 0)

    fun animePortraitColumns() = preferenceStore.getInt("pref_anime_library_columns_portrait_key", 0)
    fun animeLandscapeColumns() = preferenceStore.getInt("pref_anime_library_columns_landscape_key", 0)

    // KMK -->
    fun novelPortraitColumns() = preferenceStore.getInt("pref_novel_library_columns_portrait_key", 2)
    fun novelLandscapeColumns() = preferenceStore.getInt("pref_novel_library_columns_landscape_key", 0)
    // KMK <--

    fun lastUpdatedTimestamp() = preferenceStore.getLong(Preference.appStateKey("library_update_last_timestamp"), 0L)
    fun autoUpdateInterval() = preferenceStore.getInt("pref_library_update_interval_key", 0)

    // KMK -->
    fun showUpdatingProgressBanner() = preferenceStore.getBoolean(
        Preference.appStateKey("pref_show_updating_progress_banner_key"),
        true,
    )
    // KMK <--

    fun coverRatios() = preferenceStore.getStringSet(
        Preference.appStateKey("pref_library_cover_ratios_key"),
        emptySet(),
    )

    fun coverColors() = preferenceStore.getStringSet(
        Preference.appStateKey("pref_library_cover_colors_key"),
        emptySet(),
    )
    // KMK <--

    fun autoUpdateDeviceRestrictions() = preferenceStore.getStringSet(
        "library_update_restriction",
        setOf(
            DEVICE_ONLY_ON_WIFI,
        ),
    )
    fun autoUpdateMangaRestrictions() = preferenceStore.getStringSet(
        "library_update_manga_restriction",
        setOf(
            MANGA_HAS_UNREAD,
            MANGA_NON_COMPLETED,
            MANGA_NON_READ,
            MANGA_OUTSIDE_RELEASE_PERIOD,
        ),
    )

    fun autoUpdateAnimeRestrictions() = preferenceStore.getStringSet(
        "library_update_anime_restriction",
        setOf(
            ANIME_HAS_UNVIEWED,
            ANIME_NON_COMPLETED,
            ANIME_NON_VIEWED,
            ANIME_OUTSIDE_RELEASE_PERIOD,
        ),
    )

    fun animeUpdateCategories() = preferenceStore.getStringSet(LIBRARY_UPDATE_ANIME_CATEGORIES_PREF_KEY, emptySet())

    fun animeUpdateCategoriesExclude() =
        preferenceStore.getStringSet(LIBRARY_UPDATE_ANIME_CATEGORIES_EXCLUDE_PREF_KEY, emptySet())

    fun autoUpdateNovelRestrictions() = preferenceStore.getStringSet(
        "library_update_novel_restriction",
        setOf(
            NOVEL_HAS_UNREAD,
            NOVEL_NON_COMPLETED,
            NOVEL_NON_READ,
            NOVEL_OUTSIDE_RELEASE_PERIOD,
        ),
    )

    fun updateNovelCategories() = preferenceStore.getStringSet(LIBRARY_UPDATE_NOVEL_CATEGORIES_PREF_KEY, emptySet())

    fun updateNovelCategoriesExclude() =
        preferenceStore.getStringSet(LIBRARY_UPDATE_NOVEL_CATEGORIES_EXCLUDE_PREF_KEY, emptySet())

    fun novelExtensionRepos() = preferenceStore.getStringSet("novel_extension_repos", emptySet())

    fun updateSeasonOnLibraryUpdate() = preferenceStore.getBoolean("update_season_on_animelib_update", false)

    fun autoUpdateMetadata() = preferenceStore.getBoolean("auto_update_metadata", false)

    // KMK -->
    fun fetchMetadataOnAdd() = preferenceStore.getBoolean("fetch_metadata_on_add", false)
    fun fetchChaptersOnAdd() = preferenceStore.getBoolean("fetch_chapters_on_add", false)
    // KMK <--

    fun showContinueReadingButton() = preferenceStore.getBoolean(
        "display_continue_reading_button",
        false,
    )

    fun markDuplicateReadChapterAsRead() = preferenceStore.getStringSet("mark_duplicate_read_chapter_read", emptySet())

    // region Filter

    fun filterDownloaded() = preferenceStore.getEnum(
        "pref_filter_library_downloaded_v2",
        TriState.DISABLED,
    )

    fun filterUnread() = preferenceStore.getEnum("pref_filter_library_unread_v2", TriState.DISABLED)

    fun filterStarted() = preferenceStore.getEnum(
        "pref_filter_library_started_v2",
        TriState.DISABLED,
    )

    fun filterBookmarked() = preferenceStore.getEnum(
        "pref_filter_library_bookmarked_v2",
        TriState.DISABLED,
    )

    fun filterCompleted() = preferenceStore.getEnum(
        "pref_filter_library_completed_v2",
        TriState.DISABLED,
    )

    fun filterIntervalCustom() = preferenceStore.getEnum(
        "pref_filter_library_interval_custom",
        TriState.DISABLED,
    )

    // SY -->
    fun filterLewd() = preferenceStore.getEnum(
        "pref_filter_library_lewd_v2",
        TriState.DISABLED,
    )
    // SY <--

    // KMK -->
    fun filterCategories() = preferenceStore.getBoolean(
        "pref_filter_library_categories",
        false,
    )

    fun filterCategoriesInclude() = preferenceStore.getStringSet(FILTER_LIBRARY_CATEGORIES_INCLUDE_PREF_KEY, emptySet())

    fun filterCategoriesExclude() = preferenceStore.getStringSet(FILTER_LIBRARY_CATEGORIES_EXCLUDE_PREF_KEY, emptySet())
    // KMK <--

    fun filterTracking(id: Int) = preferenceStore.getEnum(
        "pref_filter_library_tracked_${id}_v2",
        TriState.DISABLED,
    )

    // endregion

    // region Badges

    fun downloadBadge() = preferenceStore.getBoolean("display_download_badge", false)

    fun unreadBadge() = preferenceStore.getBoolean("display_unread_badge", true)

    fun localBadge() = preferenceStore.getBoolean("display_local_badge", true)

    fun languageBadge() = preferenceStore.getBoolean("display_language_badge", true)

    // KMK -->
    fun sourceBadge() = preferenceStore.getBoolean("display_source_badge", true)

    fun useLangIcon() = preferenceStore.getBoolean("display_language_text", true)
    // KMK <--

    fun newShowUpdatesCount() = preferenceStore.getBoolean("library_show_updates_count", true)
    fun newUpdatesCount() = preferenceStore.getInt(Preference.appStateKey("library_unseen_updates_count"), 0)
    fun newAnimeUpdatesCount() = preferenceStore.getInt(Preference.appStateKey("library_unseen_anime_updates_count"), 0)

    // endregion

    // region Category

    fun defaultCategory() = preferenceStore.getInt(DEFAULT_CATEGORY_PREF_KEY, -1)

    fun defaultAnimeCategory() = preferenceStore.getInt("default_anime_category", 0)

    fun lastUsedCategory() = preferenceStore.getInt(Preference.appStateKey("last_used_category"), 0)

    fun lastUsedLibraryMode() = preferenceStore.getInt("library_last_used_mode", 0)

    fun categoryTabs() = preferenceStore.getBoolean("display_category_tabs", true)

    fun categoryNumberOfItems() = preferenceStore.getBoolean("display_number_of_items", false)

    fun categorizedDisplaySettings() = preferenceStore.getBoolean("categorized_display", false)

    // KMK -->
    fun showHiddenCategories() = preferenceStore.getBoolean("hide_hidden_categories", false)
    // KMK <--

    fun updateCategories() = preferenceStore.getStringSet(LIBRARY_UPDATE_CATEGORIES_PREF_KEY, emptySet())

    fun updateCategoriesExclude() = preferenceStore.getStringSet(LIBRARY_UPDATE_CATEGORIES_EXCLUDE_PREF_KEY, emptySet())

    // endregion

    // region Chapter

    fun filterChapterByRead() = preferenceStore.getLong(
        "default_chapter_filter_by_read",
        Manga.SHOW_ALL,
    )

    fun filterChapterByDownloaded() = preferenceStore.getLong(
        "default_chapter_filter_by_downloaded",
        Manga.SHOW_ALL,
    )

    fun filterChapterByBookmarked() = preferenceStore.getLong(
        "default_chapter_filter_by_bookmarked",
        Manga.SHOW_ALL,
    )

    // and upload date
    fun sortChapterBySourceOrNumber() = preferenceStore.getLong(
        "default_chapter_sort_by_source_or_number",
        Manga.CHAPTER_SORTING_SOURCE,
    )

    fun displayChapterByNameOrNumber() = preferenceStore.getLong(
        "default_chapter_display_by_name_or_number",
        Manga.CHAPTER_DISPLAY_NAME,
    )

    fun sortChapterByAscendingOrDescending() = preferenceStore.getLong(
        "default_chapter_sort_by_ascending_or_descending",
        Manga.CHAPTER_SORT_DESC,
    )

    fun setChapterSettingsDefault(manga: Manga) {
        filterChapterByRead().set(manga.unreadFilterRaw)
        filterChapterByDownloaded().set(manga.downloadedFilterRaw)
        filterChapterByBookmarked().set(manga.bookmarkedFilterRaw)
        sortChapterBySourceOrNumber().set(manga.sorting)
        displayChapterByNameOrNumber().set(manga.displayMode)
        sortChapterByAscendingOrDescending().set(
            if (manga.sortDescending()) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC,
        )
    }

    fun autoClearChapterCache() = preferenceStore.getBoolean("auto_clear_chapter_cache", false)

    fun hideMissingChapters() = preferenceStore.getBoolean("pref_hide_missing_chapter_indicators", false)

    // AM (FILLERMARK) -->
    fun filterEpisodeBySeen() = preferenceStore.getLong(
        "default_episode_filter_by_seen",
        Anime.SHOW_ALL,
    )

    fun filterEpisodeByDownloaded() = preferenceStore.getLong(
        "default_episode_filter_by_downloaded",
        Anime.SHOW_ALL,
    )

    fun filterEpisodeByBookmarked() = preferenceStore.getLong(
        "default_episode_filter_by_bookmarked",
        Anime.SHOW_ALL,
    )

    fun filterEpisodeByFillermarked() =
        preferenceStore.getLong("default_episode_filter_by_fillermarked", Anime.SHOW_ALL)
    // <-- AM (FILLERMARK)

    // and upload date
    fun sortEpisodeBySourceOrNumber() = preferenceStore.getLong(
        "default_episode_sort_by_source_or_number",
        Anime.EPISODE_SORTING_SOURCE,
    )

    fun displayEpisodeByNameOrNumber() = preferenceStore.getLong(
        "default_chapter_display_by_name_or_number",
        Anime.EPISODE_DISPLAY_NAME,
    )

    fun sortEpisodeByAscendingOrDescending() = preferenceStore.getLong(
        "default_chapter_sort_by_ascending_or_descending",
        Anime.EPISODE_SORT_DESC,
    )

    fun setEpisodeSettingsDefault(anime: Anime) {
        filterEpisodeBySeen().set(anime.unseenFilterRaw)
        filterEpisodeByDownloaded().set(anime.downloadedFilterRaw)
        filterEpisodeByBookmarked().set(anime.bookmarkedFilterRaw)
        // AM (FILLERMARK) -->
        filterEpisodeByFillermarked().set(anime.fillermarkedFilterRaw)
        // <-- AM (FILLERMARK)
        sortEpisodeBySourceOrNumber().set(anime.sorting)
        displayEpisodeByNameOrNumber().set(anime.displayMode)
        sortEpisodeByAscendingOrDescending().set(
            if (anime.sortDescending()) Anime.EPISODE_SORT_DESC else Anime.EPISODE_SORT_ASC,
        )
    }

    // KMK -->
    fun showEmptyCategoriesSearch() = preferenceStore.getBoolean("show_empty_categories_search", false)
    // KMK <--

    // Seasons

    fun filterSeasonByDownload() =
        preferenceStore.getLong("default_season_filter_by_downloaded", Anime.SHOW_ALL)

    fun filterSeasonByUnseen() =
        preferenceStore.getLong("default_season_filter_by_unseen", Anime.SHOW_ALL)

    fun filterSeasonByStarted() =
        preferenceStore.getLong("default_season_filter_by_started", Anime.SHOW_ALL)

    fun filterSeasonByCompleted() =
        preferenceStore.getLong("default_season_filter_by_completed", Anime.SHOW_ALL)

    fun filterSeasonByBookmarked() =
        preferenceStore.getLong("default_season_filter_by_bookmarked", Anime.SHOW_ALL)

    fun filterSeasonByFillermarked() =
        preferenceStore.getLong("default_season_filter_by_fillermarked", Anime.SHOW_ALL)

    fun sortSeasonBySourceOrNumber() = preferenceStore.getLong(
        "default_season_sort_by_source_or_number",
        Anime.SEASON_SORT_SOURCE,
    )

    fun sortSeasonByAscendingOrDescending() = preferenceStore.getLong(
        "default_season_sort_by_ascending_or_descending",
        Anime.SEASON_SORT_DESC,
    )

    fun seasonDisplayGridMode() = preferenceStore.getLong(
        "default_season_grid_display_mode",
        SeasonDisplayMode.toLong(SeasonDisplayMode.CompactGrid),
    )

    fun seasonDisplayGridSize() = preferenceStore.getInt(
        "default_season_grid_display_size",
        0,
    )

    fun seasonDownloadOverlay() = preferenceStore.getBoolean(
        "default_season_download_overlay",
        false,
    )

    fun seasonUnseenOverlay() = preferenceStore.getBoolean(
        "default_season_unseen_overlay",
        true,
    )

    fun seasonLocalOverlay() = preferenceStore.getBoolean(
        "default_season_local_overlay",
        true,
    )

    fun seasonLangOverlay() = preferenceStore.getBoolean(
        "default_season_lang_overlay",
        false,
    )

    fun seasonContinueOverlay() = preferenceStore.getBoolean(
        "default_season_continue_overlay",
        true,
    )

    fun seasonDisplayMode() = preferenceStore.getLong(
        "default_season_display_mode",
        Anime.SEASON_DISPLAY_MODE_SOURCE,
    )

    fun setSeasonSettingsDefault(anime: Anime) {
        filterSeasonByDownload().set(anime.seasonDownloadedFilterRaw)
        filterSeasonByUnseen().set(anime.seasonUnseenFilterRaw)
        filterSeasonByStarted().set(anime.seasonStartedFilterRaw)
        filterSeasonByCompleted().set(anime.seasonCompletedFilterRaw)
        filterSeasonByBookmarked().set(anime.seasonBookmarkedFilterRaw)
        filterSeasonByFillermarked().set(anime.seasonFillermarkedFilterRaw)
        sortSeasonBySourceOrNumber().set(anime.seasonSorting)
        sortSeasonByAscendingOrDescending().set(
            if (anime.seasonSortDescending()) Anime.SEASON_SORT_DESC else Anime.SEASON_SORT_ASC,
        )
        seasonDisplayGridMode().set(SeasonDisplayMode.toLong(anime.seasonDisplayGridMode))
        seasonDisplayGridSize().set(anime.seasonDisplayGridSize)
        seasonDownloadOverlay().set(anime.seasonDownloadedOverlay)
        seasonUnseenOverlay().set(anime.seasonUnseenOverlay)
        seasonLocalOverlay().set(anime.seasonLocalOverlay)
        seasonLangOverlay().set(anime.seasonLangOverlay)
        seasonContinueOverlay().set(anime.seasonContinueOverlay)
        seasonDisplayMode().set(anime.seasonDisplayMode)
    }

    // endregion

    // region Swipe Actions

    fun swipeToStartAction() = preferenceStore.getEnum(
        "pref_chapter_swipe_end_action",
        ChapterSwipeAction.ToggleBookmark,
    )

    fun swipeToEndAction() = preferenceStore.getEnum(
        "pref_chapter_swipe_start_action",
        ChapterSwipeAction.ToggleRead,
    )

    fun updateMangaTitles() = preferenceStore.getBoolean("pref_update_library_manga_titles", false)

    fun swipeEpisodeStartAction() = preferenceStore.getEnum(
        "pref_episode_swipe_end_action",
        EpisodeSwipeAction.ToggleSeen,
    )

    fun swipeEpisodeEndAction() = preferenceStore.getEnum(
        "pref_episode_swipe_start_action",
        EpisodeSwipeAction.ToggleSeen,
    )

    fun disallowNonAsciiFilenames() = preferenceStore.getBoolean("disallow_non_ascii_filenames", false)

    // endregion

    enum class ChapterSwipeAction {
        ToggleRead,
        ToggleBookmark,
        Download,
        Disabled,
    }

    enum class EpisodeSwipeAction {
        ToggleSeen,
        ToggleBookmark,
        ToggleFillermark,
        Download,
        Disabled,
    }

    // SY -->

    fun sortTagsForLibrary() = preferenceStore.getStringSet("sort_tags_for_library", mutableSetOf())

    fun groupLibraryUpdateType() = preferenceStore.getEnum("group_library_update_type", GroupLibraryMode.GLOBAL)

    fun groupLibraryBy() = preferenceStore.getInt("group_library_by", LibraryGroup.BY_DEFAULT)

    // SY <--

    companion object {
        const val DEVICE_ONLY_ON_WIFI = "wifi"
        const val DEVICE_NETWORK_NOT_METERED = "network_not_metered"
        const val DEVICE_CHARGING = "ac"

        const val MANGA_NON_COMPLETED = "manga_ongoing"
        const val MANGA_HAS_UNREAD = "manga_fully_read"
        const val MANGA_NON_READ = "manga_started"
        const val MANGA_OUTSIDE_RELEASE_PERIOD = "manga_outside_release_period"
        const val ANIME_NON_COMPLETED = "anime_ongoing"
        const val ANIME_HAS_UNVIEWED = "anime_fully_viewed"
        const val ANIME_NON_VIEWED = "anime_started"
        const val ANIME_OUTSIDE_RELEASE_PERIOD = "anime_outside_release_period"
        const val NOVEL_NON_COMPLETED = "novel_ongoing"
        const val NOVEL_HAS_UNREAD = "novel_fully_read"
        const val NOVEL_NON_READ = "novel_started"
        const val NOVEL_OUTSIDE_RELEASE_PERIOD = "novel_outside_release_period"

        const val MARK_DUPLICATE_CHAPTER_READ_NEW = "new"
        const val MARK_DUPLICATE_CHAPTER_READ_EXISTING = "existing"

        const val DEFAULT_CATEGORY_PREF_KEY = "default_category"
        private const val LIBRARY_UPDATE_CATEGORIES_PREF_KEY = "library_update_categories"
        private const val LIBRARY_UPDATE_CATEGORIES_EXCLUDE_PREF_KEY = "library_update_categories_exclude"
        private const val LIBRARY_UPDATE_ANIME_CATEGORIES_PREF_KEY = "animelib_update_categories"
        private const val LIBRARY_UPDATE_ANIME_CATEGORIES_EXCLUDE_PREF_KEY = "animelib_update_categories_exclude"
        private const val LIBRARY_UPDATE_NOVEL_CATEGORIES_PREF_KEY = "novel_update_categories"
        private const val LIBRARY_UPDATE_NOVEL_CATEGORIES_EXCLUDE_PREF_KEY = "novel_update_categories_exclude"

        // KMK -->
        private const val FILTER_LIBRARY_CATEGORIES_INCLUDE_PREF_KEY = "pref_filter_library_categories_include"
        private const val FILTER_LIBRARY_CATEGORIES_EXCLUDE_PREF_KEY = "pref_filter_library_categories_exclude"
        // KMK <--

        val categoryPreferenceKeys = setOf(
            DEFAULT_CATEGORY_PREF_KEY,
            LIBRARY_UPDATE_CATEGORIES_PREF_KEY,
            LIBRARY_UPDATE_CATEGORIES_EXCLUDE_PREF_KEY,
            // KMK -->
            FILTER_LIBRARY_CATEGORIES_INCLUDE_PREF_KEY,
            FILTER_LIBRARY_CATEGORIES_EXCLUDE_PREF_KEY,
            // KMK <--
        )
    }
}
