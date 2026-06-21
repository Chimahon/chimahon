package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupFeed
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSourceNovel
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionStoreRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.AnimeCategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.AnimeExtensionRepoRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.AnimeRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.FeedRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.SavedSearchRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.SourceNovelRestorer
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.domain.history.interactor.UpsertSearchHistory
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupRestorer(
    private val context: Context,
    private val notifier: BackupNotifier,
    private val isSync: Boolean,

    private val categoriesRestorer: CategoriesRestorer = CategoriesRestorer(),
    private val animeCategoriesRestorer: AnimeCategoriesRestorer = AnimeCategoriesRestorer(),
    private val preferenceRestorer: PreferenceRestorer = PreferenceRestorer(context),
    private val extensionStoreRestorer: ExtensionStoreRestorer = ExtensionStoreRestorer(),
    private val animeExtensionRepoRestorer: AnimeExtensionRepoRestorer = AnimeExtensionRepoRestorer(),
    private val mangaRestorer: MangaRestorer = MangaRestorer(isSync),
    private val animeRestorer: AnimeRestorer = AnimeRestorer(),
    // SY -->
    private val savedSearchRestorer: SavedSearchRestorer = SavedSearchRestorer(),
    // SY <--
    // KMK -->
    private val feedRestorer: FeedRestorer = FeedRestorer(),
    // KMK <--
    // Chimahon -->
    private val novelRestorer: eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer = eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer(context),
    private val upsertSearchHistory: UpsertSearchHistory = Injekt.get(),
    private val sourceNovelRestorer: SourceNovelRestorer = SourceNovelRestorer(isSync),
    // Chimahon <--
) {

    private var restoreAmount = 0
    private var restoreProgress = 0
    private val errors = mutableListOf<Pair<Date, String>>()

    /**
     * Mapping of source ID to source name from backup data
     */
    private var sourceMapping: Map<Long, String> = emptyMap()
    private var animeSourceMapping: Map<Long, String> = emptyMap()

    suspend fun restore(uri: Uri, options: RestoreOptions) {
        val startTime = System.currentTimeMillis()

        restoreFromFile(uri, options)

        val time = System.currentTimeMillis() - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(
            time,
            errors.size,
            logFile.parent,
            logFile.name,
            isSync,
        )
    }

    private suspend fun restoreFromFile(uri: Uri, options: RestoreOptions) {
        val backup = BackupDecoder(context).decode(uri)

        // Store source mapping for error messages
        val backupMaps = backup.backupSources
        sourceMapping = backupMaps.associate { it.sourceId to it.name }
        animeSourceMapping = backup.backupAnimeSources.associate { it.sourceId to it.name }

        if (options.libraryEntries) {
            restoreAmount += backup.backupManga.size
        }
        if (options.animeEntries) {
            restoreAmount += backup.backupAnime.count { it.parentId == null }
        }
        if (options.categories) {
            restoreAmount += 1
            if (backup.backupAnimeCategories.isNotEmpty()) {
                restoreAmount += 1
            }
        }
        // SY -->
        if (options.savedSearchesFeeds) {
            restoreAmount += 1
        }
        // SY <--
        if (options.appSettings) {
            restoreAmount += 1
        }
        if (options.extensionStores) {
            restoreAmount += backup.backupExtensionStores.size
            restoreAmount += backup.backupAnimeExtensionRepo.size
        }
        if (options.sourceSettings) {
            restoreAmount += 1
        }
        // Chimahon -->
        if (options.novels) {
            restoreAmount += backup.backupNovels.size
            if (backup.backupNovelCategories.isNotEmpty()) {
                restoreAmount += 1
            }
        }
        if (options.sourceNovelLibrary) {
            restoreAmount += backup.backupSourceNovels.size
        }
        if (options.appSettings) {
            if (backup.backupMangaStats.isNotEmpty()) restoreAmount += 1
            if (backup.backupAnkiStats.isNotEmpty()) restoreAmount += 1
        }
        if (options.history && backup.backupSearchHistory.isNotEmpty()) {
            restoreAmount += 1
        }
        // Chimahon <--

        coroutineScope {
            if (options.categories) {
                restoreCategories(backup.backupCategories)
                restoreAnimeCategories(backup.backupAnimeCategories)
            }
            // SY -->
            if (options.savedSearchesFeeds) {
                restoreSavedSearches(
                    backup.backupSavedSearches,
                    // KMK -->
                    backup.backupFeeds,
                    // KMK <--
                )
            }
            // SY <--
            if (options.appSettings) {
                restoreAppPreferences(backup.backupPreferences, backup.backupCategories.takeIf { options.categories })
                restoreGlobalStats(backup.backupMangaStats, backup.backupAnkiStats)
            }
            if (options.sourceSettings) {
                restoreSourcePreferences(backup.backupSourcePreferences)
            }
            if (options.libraryEntries) {
                restoreManga(backup.backupManga, if (options.categories) backup.backupCategories else emptyList())
            }
            if (options.extensionStores) {
                restoreExtensionStores(backup.backupExtensionStores)
                restoreAnimeExtensionRepos(backup.backupAnimeExtensionRepo)
            }
            if (options.animeEntries) {
                restoreAnime(backup.backupAnime, if (options.categories) backup.backupAnimeCategories else emptyList())
            }
            // Chimahon -->
            if (options.novels) {
                restoreNovels(backup.backupNovels, backup.backupNovelCategories)
            }
            if (options.sourceNovelLibrary) {
                restoreSourceNovels(backup.backupSourceNovels)
            }
            if (options.history && backup.backupSearchHistory.isNotEmpty()) {
                restoreSearchHistory(backup.backupSearchHistory)
            }
            // Chimahon <--

            // TODO: optionally trigger online library + tracker update
        }
    }

    context(scope: CoroutineScope)
    private /* KMK --> */suspend /* KMK <-- */ fun restoreCategories(backupCategories: List<BackupCategory>) {
        scope.ensureActive()
        categoriesRestorer(backupCategories)

        restoreProgress += 1
        with(notifier) {
            showRestoreProgress(
                context.stringResource(MR.strings.categories),
                restoreProgress,
                restoreAmount,
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }

    context(scope: CoroutineScope)
    private suspend fun restoreAnimeCategories(backupCategories: List<BackupCategory>) {
        if (backupCategories.isEmpty()) return

        scope.ensureActive()
        animeCategoriesRestorer(backupCategories)

        restoreProgress += 1
        with(notifier) {
            showRestoreProgress(
                context.stringResource(MR.strings.categories),
                restoreProgress,
                restoreAmount,
                isSync,
            )
                .show(Notifications.ID_RESTORE_PROGRESS)
        }
    }

    // SY -->
    private fun CoroutineScope.restoreSavedSearches(
        backupSavedSearches: List<BackupSavedSearch>,
        // KMK -->
        backupFeeds: List<BackupFeed>,
        // KMK <--
    ) = launch {
        ensureActive()
        savedSearchRestorer.restoreSavedSearches(backupSavedSearches)
        // KMK -->
        feedRestorer.restoreFeeds(backupFeeds)
        // KMK <--

        restoreProgress += 1
        with(notifier) {
            showRestoreProgress(
                context.stringResource(KMR.strings.saved_searches_feeds),
                restoreProgress,
                restoreAmount,
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }
    // SY <--

    private fun CoroutineScope.restoreManga(
        backupMangas: List<BackupManga>,
        backupCategories: List<BackupCategory>,
    ) = launch {
        mangaRestorer.sortByNew(backupMangas)
            .forEach {
                ensureActive()

                try {
                    mangaRestorer.restore(it, backupCategories)
                } catch (e: Exception) {
                    val sourceName = sourceMapping[it.source] ?: it.source.toString()
                    errors.add(Date() to "${it.title} [$sourceName]: ${e.message}")
                }

                restoreProgress += 1
                with(notifier) {
                    showRestoreProgress(it.title, restoreProgress, restoreAmount, isSync)
                        // KMK -->
                        .show(Notifications.ID_RESTORE_PROGRESS)
                    // KMK <--
                }
            }
    }

    private fun CoroutineScope.restoreAnime(
        backupAnimes: List<BackupAnime>,
        backupCategories: List<BackupCategory>,
    ) = launch {
        val seasonsByParentBackupId = backupAnimes
            .filter { it.parentId != null }
            .groupBy { it.parentId }

        animeRestorer.sortByNew(backupAnimes.filter { it.parentId == null })
            .forEach {
                ensureActive()

                try {
                    animeRestorer.restore(it, backupCategories, seasonsByParentBackupId[it.id].orEmpty())
                } catch (e: Exception) {
                    val sourceName = animeSourceMapping[it.source] ?: it.source.toString()
                    errors.add(Date() to "${it.title} [$sourceName]: ${e.message}")
                }

                restoreProgress += 1
                with(notifier) {
                    showRestoreProgress(it.title, restoreProgress, restoreAmount, isSync)
                        .show(Notifications.ID_RESTORE_PROGRESS)
                }
            }
    }

    private fun CoroutineScope.restoreAppPreferences(
        preferences: List<BackupPreference>,
        categories: List<BackupCategory>?,
    ) = launch {
        ensureActive()
        preferenceRestorer.restoreApp(
            preferences,
            categories,
        )

        restoreProgress += 1
        with(notifier) {
            showRestoreProgress(
                context.stringResource(MR.strings.app_settings),
                restoreProgress,
                restoreAmount,
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }

    private fun CoroutineScope.restoreSourcePreferences(preferences: List<BackupSourcePreferences>) = launch {
        ensureActive()
        preferenceRestorer.restoreSource(preferences)

        restoreProgress += 1
        with(notifier) {
            showRestoreProgress(
                context.stringResource(MR.strings.source_settings),
                restoreProgress,
                restoreAmount,
                isSync,
            )
                // KMK -->
                .show(Notifications.ID_RESTORE_PROGRESS)
            // KMK <--
        }
    }

    private fun CoroutineScope.restoreExtensionStores(
        backupExtensionStores: List<BackupExtensionStore>,
    ) = launch {
        backupExtensionStores
            .forEach {
                ensureActive()

                try {
                    extensionStoreRestorer(it)
                } catch (e: Exception) {
                    errors.add(Date() to "Error adding extension store: ${it.name} : ${e.message}")
                }

                restoreProgress += 1
                // KMK -->
                with(notifier) {
                    // KMK <--
                    showRestoreProgress(
                        context.stringResource(MR.strings.extensionStores),
                        restoreProgress,
                        restoreAmount,
                        isSync,
                    )
                        // KMK -->
                        .show(Notifications.ID_RESTORE_PROGRESS)
                    // KMK <--
                }
            }
    }

    private fun CoroutineScope.restoreAnimeExtensionRepos(
        backupExtensionRepo: List<BackupExtensionRepos>,
    ) = launch {
        backupExtensionRepo
            .forEach {
                ensureActive()

                try {
                    animeExtensionRepoRestorer(it)
                } catch (e: Exception) {
                    errors.add(Date() to "Error Adding Anime Repo: ${it.name} : ${e.message}")
                }

                restoreProgress += 1
                with(notifier) {
                    showRestoreProgress(
                        context.stringResource(MR.strings.extensionRepo_settings),
                        restoreProgress,
                        restoreAmount,
                        isSync,
                    )
                        .show(Notifications.ID_RESTORE_PROGRESS)
                }
            }
    }

    // Chimahon -->
    private fun CoroutineScope.restoreNovels(
        backupNovels: List<eu.kanade.tachiyomi.data.backup.models.BackupNovel>,
        backupNovelCategories: List<eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory>
    ) = launch {
        ensureActive()

        val categoryIdMap = try {
            novelRestorer.restoreCategories(backupNovelCategories)
        } catch (e: Exception) {
            errors.add(Date() to "Error Restoring Novel Categories: ${e.message}")
            emptyMap()
        }

        if (backupNovelCategories.isNotEmpty()) {
            restoreProgress += 1
        }

        backupNovels.forEach { backupNovel ->
            ensureActive()

            try {
                novelRestorer.restoreNovel(backupNovel, categoryIdMap)
            } catch (e: Exception) {
                errors.add(Date() to "${backupNovel.title}: ${e.message}")
            }

            restoreProgress += 1
            with(notifier) {
                showRestoreProgress(
                    backupNovel.title,
                    restoreProgress,
                    restoreAmount,
                    isSync,
                ).show(Notifications.ID_RESTORE_PROGRESS)
            }
        }
    }

    private fun CoroutineScope.restoreSourceNovels(
        backupNovels: List<BackupSourceNovel>,
    ) = launch {
        sourceNovelRestorer.sortByNew(backupNovels)
            .forEach {
                ensureActive()

                try {
                    sourceNovelRestorer.restore(it)
                } catch (e: Exception) {
                    errors.add(Date() to "${it.title} [${it.source}]: ${e.message}")
                }

                restoreProgress += 1
                with(notifier) {
                    showRestoreProgress(
                        it.title,
                        restoreProgress,
                        restoreAmount,
                        isSync,
                    ).show(Notifications.ID_RESTORE_PROGRESS)
                }
            }
    }

    private fun CoroutineScope.restoreGlobalStats(
        mangaStats: List<com.canopus.chimareader.data.MangaStats>,
        ankiStats: List<com.canopus.chimareader.data.AnkiStats>
    ) = launch {
        with(notifier) {
            if (mangaStats.isNotEmpty()) {
                ensureActive()
                com.canopus.chimareader.data.MangaStatsStorage.merge(context, mangaStats)
                restoreProgress += 1
                showRestoreProgress(context.stringResource(MR.strings.manga_singular), restoreProgress, restoreAmount, isSync)
                    .show(Notifications.ID_RESTORE_PROGRESS)
            }
            if (ankiStats.isNotEmpty()) {
                ensureActive()
                com.canopus.chimareader.data.AnkiStatsStorage.merge(context, ankiStats)
                restoreProgress += 1
                showRestoreProgress("Anki", restoreProgress, restoreAmount, isSync)
                    .show(Notifications.ID_RESTORE_PROGRESS)
            }
        }
    }

    private fun CoroutineScope.restoreSearchHistory(
        history: List<eu.kanade.tachiyomi.data.backup.models.BackupSearchHistory>,
    ) = launch {
        ensureActive()
        history.forEach { item ->
            try {
                upsertSearchHistory.await(item.scope, item.query, item.lastSearchedAt)
            } catch (e: Exception) {
                errors.add(Date() to "Search History [${item.scope}]: ${e.message}")
            }
        }

        restoreProgress += 1
        with(notifier) {
            showRestoreProgress(
                context.stringResource(MR.strings.history),
                restoreProgress,
                restoreAmount,
                isSync,
            ).show(Notifications.ID_RESTORE_PROGRESS)
        }
    }
    // Chimahon <--

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("komikku_restore_error.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (_: Exception) {
            // Empty
        }
        return File("")
    }
}
