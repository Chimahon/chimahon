package tachiyomi.domain.library.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.library.model.LibraryDisplayMode

class NovelLibraryPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun displayMode() = preferenceStore.getObjectFromString(
        "pref_novel_display_mode_library",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )

    fun portraitColumns() = preferenceStore.getInt("pref_novel_library_columns_portrait_key", 2)

    fun landscapeColumns() = preferenceStore.getInt("pref_novel_library_columns_landscape_key", 0)

    fun categoryTabs() = preferenceStore.getBoolean("display_novel_category_tabs", true)

    fun categoryNumberOfItems() = preferenceStore.getBoolean("display_novel_number_of_items", false)

    fun showHiddenCategories() = preferenceStore.getBoolean("hide_novel_hidden_categories", false)

    fun defaultCategory() = preferenceStore.getString(NOVEL_DEFAULT_CATEGORY_PREF_KEY, "")

    fun jsonMigrated() = preferenceStore.getBoolean("novel_json_migrated", false)

    fun sidecarsFolded() = preferenceStore.getBoolean("novel_sidecars_folded", false)

    companion object {
        const val NOVEL_DEFAULT_CATEGORY_PREF_KEY = "novel_default_category"
    }
}
