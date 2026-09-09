package eu.kanade.tachiyomi.data.backup.create

import dev.icerock.moko.resources.StringResource
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR

data class BackupOptions(
    val libraryEntries: Boolean = true,
    val categories: Boolean = true,
    val chapters: Boolean = true,
    val tracking: Boolean = true,
    val history: Boolean = true,
    val readEntries: Boolean = true,
    val appSettings: Boolean = true,
    val extensionStores: Boolean = true,
    val sourceSettings: Boolean = true,
    val privateSettings: Boolean = false,
    // SY -->
    val customInfo: Boolean = true,
    val savedSearchesFeeds: Boolean = true,
    // SY <--
    // Chimahon -->
    val novels: Boolean = true,
    val sourceNovelLibrary: Boolean = true,
    // Chimahon <--
    val animeEntries: Boolean = true,
) {

    fun asBooleanArray() = booleanArrayOf(
        libraryEntries,
        categories,
        chapters,
        tracking,
        history,
        readEntries,
        appSettings,
        extensionStores,
        sourceSettings,
        privateSettings,
        // SY -->
        customInfo,
        savedSearchesFeeds,
        // SY <--
        // Chimahon -->
        novels,
        sourceNovelLibrary,
        // Chimahon <--
        animeEntries,
    )

    fun canCreate() =
        libraryEntries || animeEntries || categories || appSettings || extensionStores || sourceSettings || savedSearchesFeeds || novels || sourceNovelLibrary

    companion object {
        val libraryOptions = persistentListOf(
            Entry(
                label = MR.strings.manga,
                getter = BackupOptions::libraryEntries,
                setter = { options, enabled -> options.copy(libraryEntries = enabled) },
            ),
            Entry(
                label = MR.strings.label_anime,
                getter = BackupOptions::animeEntries,
                setter = { options, enabled -> options.copy(animeEntries = enabled) },
            ),
            Entry(
                label = MR.strings.chapters,
                getter = BackupOptions::chapters,
                setter = { options, enabled -> options.copy(chapters = enabled) },
                enabled = { it.libraryEntries || it.animeEntries },
            ),
            Entry(
                label = MR.strings.track,
                getter = BackupOptions::tracking,
                setter = { options, enabled -> options.copy(tracking = enabled) },
                enabled = { it.libraryEntries || it.animeEntries },
            ),
            Entry(
                label = MR.strings.history,
                getter = BackupOptions::history,
                setter = { options, enabled -> options.copy(history = enabled) },
                enabled = { it.libraryEntries || it.animeEntries },
            ),
            Entry(
                label = MR.strings.categories,
                getter = BackupOptions::categories,
                setter = { options, enabled -> options.copy(categories = enabled) },
            ),
            Entry(
                label = MR.strings.non_library_settings,
                getter = BackupOptions::readEntries,
                setter = { options, enabled -> options.copy(readEntries = enabled) },
                enabled = { it.libraryEntries || it.animeEntries },
            ),
            // SY -->
            Entry(
                label = SYMR.strings.custom_entry_info,
                getter = BackupOptions::customInfo,
                setter = { options, enabled -> options.copy(customInfo = enabled) },
                enabled = { it.libraryEntries },
            ),
            Entry(
                // KMK-->
                label = KMR.strings.saved_searches_feeds,
                // KMK <--
                getter = BackupOptions::savedSearchesFeeds,
                setter = { options, enabled -> options.copy(savedSearchesFeeds = enabled) },
            ),
            // SY <--
            // Chimahon -->
            Entry(
                label = MR.strings.backup_option_novels,
                getter = BackupOptions::novels,
                setter = { options, enabled -> options.copy(novels = enabled) },
            ),
            Entry(
                label = MR.strings.backup_option_source_novel_library,
                getter = BackupOptions::sourceNovelLibrary,
                setter = { options, enabled -> options.copy(sourceNovelLibrary = enabled) },
            ),
            // Chimahon <--
        )

        val settingsOptions = persistentListOf(
            Entry(
                label = MR.strings.app_settings,
                getter = BackupOptions::appSettings,
                setter = { options, enabled -> options.copy(appSettings = enabled) },
            ),
            Entry(
                label = MR.strings.extensionStores,
                getter = BackupOptions::extensionStores,
                setter = { options, enabled -> options.copy(extensionStores = enabled) },
            ),
            Entry(
                label = MR.strings.source_settings,
                getter = BackupOptions::sourceSettings,
                setter = { options, enabled -> options.copy(sourceSettings = enabled) },
            ),
            Entry(
                label = MR.strings.private_settings,
                getter = BackupOptions::privateSettings,
                setter = { options, enabled -> options.copy(privateSettings = enabled) },
                enabled = { it.appSettings || it.sourceSettings },
            ),
        )

        fun fromBooleanArray(array: BooleanArray) = BackupOptions(
            libraryEntries = array[0],
            categories = array[1],
            chapters = array[2],
            tracking = array[3],
            history = array[4],
            readEntries = array[5],
            appSettings = array[6],
            extensionStores = array[7],
            sourceSettings = array[8],
            privateSettings = array[9],
            // SY -->
            customInfo = array[10],
            savedSearchesFeeds = array[11],
            // SY <--
            // Chimahon -->
            novels = array.getOrElse(12) { true },
            sourceNovelLibrary = array.getOrElse(13) { true },
            // Chimahon <--
            animeEntries = array.getOrElse(14) { true },
        )
    }

    data class Entry(
        val label: StringResource,
        val getter: (BackupOptions) -> Boolean,
        val setter: (BackupOptions, Boolean) -> BackupOptions,
        val enabled: (BackupOptions) -> Boolean = { true },
    )
}
