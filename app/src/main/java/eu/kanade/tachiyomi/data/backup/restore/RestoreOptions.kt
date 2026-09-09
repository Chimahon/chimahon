package eu.kanade.tachiyomi.data.backup.restore

import dev.icerock.moko.resources.StringResource
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR

data class RestoreOptions(
    val libraryEntries: Boolean = true,
    val categories: Boolean = true,
    val appSettings: Boolean = true,
    val extensionStores: Boolean = true,
    val sourceSettings: Boolean = true,
    // SY -->
    val savedSearchesFeeds: Boolean = true,
    // SY <--
    // Chimahon -->
    val novels: Boolean = true,
    val sourceNovelLibrary: Boolean = true,
    val history: Boolean = true,
    // Chimahon <--
    val animeEntries: Boolean = true,
) {

    fun asBooleanArray() = booleanArrayOf(
        libraryEntries,
        categories,
        appSettings,
        extensionStores,
        sourceSettings,
        // SY -->
        savedSearchesFeeds,
        // SY <--
        // Chimahon -->
        novels,
        sourceNovelLibrary,
        // Chimahon <--
        animeEntries,
    )

    fun canRestore() =
        libraryEntries ||
            categories ||
            appSettings ||
            extensionStores ||
            sourceSettings /* SY --> */ ||
            savedSearchesFeeds /* SY <-- */ ||
            novels ||
            sourceNovelLibrary ||
            animeEntries

    companion object {
        val options = persistentListOf(
            Entry(
                label = MR.strings.label_library,
                getter = RestoreOptions::libraryEntries,
                setter = { options, enabled -> options.copy(libraryEntries = enabled) },
            ),
            Entry(
                label = MR.strings.label_anime,
                getter = RestoreOptions::animeEntries,
                setter = { options, enabled -> options.copy(animeEntries = enabled) },
            ),
            Entry(
                label = MR.strings.categories,
                getter = RestoreOptions::categories,
                setter = { options, enabled -> options.copy(categories = enabled) },
            ),
            Entry(
                label = MR.strings.app_settings,
                getter = RestoreOptions::appSettings,
                setter = { options, enabled -> options.copy(appSettings = enabled) },
            ),
            Entry(
                label = MR.strings.extensionStores,
                getter = RestoreOptions::extensionStores,
                setter = { options, enabled -> options.copy(extensionStores = enabled) },
            ),
            Entry(
                label = MR.strings.source_settings,
                getter = RestoreOptions::sourceSettings,
                setter = { options, enabled -> options.copy(sourceSettings = enabled) },
            ),
            // SY -->
            Entry(
                // KMK-->
                label = KMR.strings.saved_searches_feeds,
                // KMK <--
                getter = RestoreOptions::savedSearchesFeeds,
                setter = { options, enabled -> options.copy(savedSearchesFeeds = enabled) },
            ),
            // SY <--
            // Chimahon -->
            Entry(
                label = MR.strings.backup_option_novels,
                getter = RestoreOptions::novels,
                setter = { options, enabled -> options.copy(novels = enabled) },
            ),
            Entry(
                label = MR.strings.backup_option_source_novel_library,
                getter = RestoreOptions::sourceNovelLibrary,
                setter = { options, enabled -> options.copy(sourceNovelLibrary = enabled) },
            ),
            // Chimahon <--
        )

        fun fromBooleanArray(array: BooleanArray) = RestoreOptions(
            libraryEntries = array[0],
            categories = array[1],
            appSettings = array[2],
            extensionStores = array[3],
            sourceSettings = array[4],
            // SY -->
            savedSearchesFeeds = array[5],
            // SY <--
            // Chimahon -->
            novels = array.getOrElse(6) { true },
            sourceNovelLibrary = array.getOrElse(7) { true },
            // Chimahon <--
            animeEntries = array.getOrElse(8) { true },
        )
    }

    data class Entry(
        val label: StringResource,
        val getter: (RestoreOptions) -> Boolean,
        val setter: (RestoreOptions, Boolean) -> RestoreOptions,
    )
}
