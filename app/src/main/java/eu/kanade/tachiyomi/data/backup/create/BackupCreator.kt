package eu.kanade.tachiyomi.data.backup.create

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.create.creators.AnimeBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.AnimeCategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.AnimeExtensionRepoBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.AnimeSourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionStoresBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.FeedBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.PreferenceBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SavedSearchBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourceNovelBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.SourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeSource
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupFeed
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSavedSearch
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import okio.buffer
import okio.gzip
import okio.sink
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.backup.service.BackupPreferences
import tachiyomi.domain.entries.anime.interactor.GetAnimeSeasonsByParentId
import tachiyomi.domain.entries.anime.interactor.GetFavoriteAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetMergedManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import eu.kanade.tachiyomi.data.backup.models.BackupSearchHistory
import tachiyomi.domain.history.interactor.GetSearchHistory
import tachiyomi.domain.history.model.SearchHistory

class BackupCreator(
    private val context: Context,
    private val isAutoBackup: Boolean,

    private val parser: ProtoBuf = Injekt.get(),
    private val getFavorites: GetFavorites = Injekt.get(),
    private val getFavoriteAnime: GetFavoriteAnime = Injekt.get(),
    private val backupPreferences: BackupPreferences = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val animeRepository: AnimeRepository = Injekt.get(),
    private val getAnimeSeasonsByParentId: GetAnimeSeasonsByParentId = Injekt.get(),

    private val categoriesBackupCreator: CategoriesBackupCreator = CategoriesBackupCreator(),
    private val animeCategoriesBackupCreator: AnimeCategoriesBackupCreator = AnimeCategoriesBackupCreator(),
    private val mangaBackupCreator: MangaBackupCreator = MangaBackupCreator(),
    private val animeBackupCreator: AnimeBackupCreator = AnimeBackupCreator(),
    private val preferenceBackupCreator: PreferenceBackupCreator = PreferenceBackupCreator(),
    private val extensionStoresBackupCreator: ExtensionStoresBackupCreator = ExtensionStoresBackupCreator(),
    private val animeExtensionRepoBackupCreator: AnimeExtensionRepoBackupCreator = AnimeExtensionRepoBackupCreator(),
    private val sourcesBackupCreator: SourcesBackupCreator = SourcesBackupCreator(),
    private val animeSourcesBackupCreator: AnimeSourcesBackupCreator = AnimeSourcesBackupCreator(),
    // KMK -->
    private val feedBackupCreator: FeedBackupCreator = FeedBackupCreator(),
    // KMK <--
    // Chimahon -->
    private val novelBackupCreator: eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator = eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator(context),
    private val novelExtensionRepoBackupCreator: eu.kanade.tachiyomi.data.backup.create.creators.NovelExtensionRepoBackupCreator = eu.kanade.tachiyomi.data.backup.create.creators.NovelExtensionRepoBackupCreator(),
    private val getSearchHistory: GetSearchHistory = Injekt.get(),
    private val sourceNovelBackupCreator: SourceNovelBackupCreator = SourceNovelBackupCreator(),
    // Chimahon <--
    // SY -->
    private val savedSearchBackupCreator: SavedSearchBackupCreator = SavedSearchBackupCreator(),
    private val getMergedManga: GetMergedManga = Injekt.get(),
    // SY <--
) {

    suspend fun backup(uri: Uri, options: BackupOptions): String {
        var file: UniFile? = null
        try {
            file = if (isAutoBackup) {
                // Get dir of file and create
                val dir = UniFile.fromUri(context, uri)

                // Delete older backups
                dir?.listFiles { _, filename -> FILENAME_REGEX.matches(filename) }
                    .orEmpty()
                    .sortedByDescending { it.name }
                    .drop(MAX_AUTO_BACKUPS - 1)
                    .forEach { it.delete() }

                // Create new file to place backup
                dir?.createFile(getFilename())
            } else {
                UniFile.fromUri(context, uri)
            }

            if (file == null || !file.isFile) {
                throw IllegalStateException(context.stringResource(MR.strings.create_backup_file_error))
            }

            val nonFavoriteManga = if (options.readEntries) mangaRepository.getReadMangaNotInLibrary() else emptyList()
            // SY -->
            val mergedManga = getMergedManga.await()
            // SY <--
            val backupManga =
                backupMangas(getFavorites.await() + nonFavoriteManga /* SY --> */ + mergedManga /* SY <-- */, options)
            val backupAnime = backupAnimes(options)
            // Chimahon -->
            val backupSourceNovels = backupSourceNovels(options)
            // Chimahon <--

            val backup = Backup(
                backupManga = backupManga,
                backupCategories = backupCategories(options),
                backupSources = backupSources(backupManga, backupSourceNovels),
                backupAnime = backupAnime,
                backupAnimeCategories = backupAnimeCategories(options),
                backupAnimeSources = backupAnimeSources(backupAnime),
                backupPreferences = backupAppPreferences(options),
                backupExtensionStores = backupExtensionStores(options),
                backupAnimeExtensionRepo = backupAnimeExtensionRepos(options),
                backupSourcePreferences = backupSourcePreferences(options),

                // SY -->
                backupSavedSearches = backupSavedSearches(options),
                // SY <--

                // KMK -->
                backupFeeds = backupFeeds(options),
                // KMK <--

                // Chimahon -->
                backupNovels = backupNovels(options),
                backupNovelCategories = backupNovelCategories(options),
                backupSourceNovels = backupSourceNovels,
                backupNovelExtensionRepo = backupNovelExtensionRepos(options),
                backupMangaStats = backupMangaStats(options),
                backupAnkiStats = backupAnkiStats(options),
                backupSearchHistory = backupSearchHistory(options),
                // Chimahon <--
            )

            val byteArray = parser.encodeToByteArray(Backup.serializer(), backup)
            if (byteArray.isEmpty()) {
                throw IllegalStateException(context.stringResource(MR.strings.empty_backup_error))
            }

            file.openOutputStream()
                .also {
                    // Force overwrite old file
                    (it as? FileOutputStream)?.channel?.truncate(0)
                }
                .sink().gzip().buffer().use {
                    it.write(byteArray)
                }
            val fileUri = file.uri

            // Make sure it's a valid backup file
            BackupFileValidator(context).validate(fileUri)

            if (isAutoBackup) {
                backupPreferences.lastAutoBackupTimestamp().set(Instant.now().toEpochMilli())
            }

            return fileUri.toString()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            file?.delete()
            throw e
        }
    }

    suspend fun backupCategories(options: BackupOptions): List<BackupCategory> {
        if (!options.categories) return emptyList()

        return categoriesBackupCreator()
    }

    suspend fun backupMangas(mangas: List<Manga>, options: BackupOptions): List<BackupManga> {
        if (!options.libraryEntries) return emptyList()

        return mangaBackupCreator(mangas, options)
    }

    fun backupSources(
        mangas: List<BackupManga>,
        novels: List<eu.kanade.tachiyomi.data.backup.models.BackupSourceNovel> = emptyList(),
    ): List<BackupSource> {
        return sourcesBackupCreator(mangas, novels)
    }

    suspend fun backupAnimeCategories(options: BackupOptions): List<BackupCategory> {
        if (!options.categories) return emptyList()

        return animeCategoriesBackupCreator()
    }

    suspend fun backupAnimes(options: BackupOptions): List<BackupAnime> {
        if (!options.animeEntries) return emptyList()

        val favoriteAnime = getFavoriteAnime.await()
        val seenAnime = if (options.readEntries) animeRepository.getSeenAnimeNotInLibrary() else emptyList()
        val seasons = favoriteAnime
            .flatMap { getAnimeSeasonsByParentId.await(it.id).map { season -> season.anime } }
        return animeBackupCreator((favoriteAnime + seenAnime + seasons).distinctBy { it.id }, options)
    }

    fun backupAnimeSources(animes: List<BackupAnime>): List<BackupAnimeSource> {
        return animeSourcesBackupCreator(animes)
    }

    /* KMK --> */ suspend /* KMK <-- */ fun backupAppPreferences(options: BackupOptions): List<BackupPreference> {
        if (!options.appSettings) return emptyList()

        return preferenceBackupCreator.createApp(includePrivatePreferences = options.privateSettings)
    }

    internal suspend fun backupExtensionStores(options: BackupOptions): List<BackupExtensionStore> {
        if (!options.extensionStores) return emptyList()

        return extensionStoresBackupCreator()
    }

    suspend fun backupAnimeExtensionRepos(options: BackupOptions): List<BackupExtensionRepos> {
        if (!options.extensionStores) return emptyList<BackupExtensionRepos>()

        return animeExtensionRepoBackupCreator()
    }

    suspend fun backupNovelExtensionRepos(options: BackupOptions): List<BackupExtensionRepos> {
        if (!options.extensionStores) return emptyList<BackupExtensionRepos>()

        return novelExtensionRepoBackupCreator()
    }

    fun backupSourcePreferences(options: BackupOptions): List<BackupSourcePreferences> {
        if (!options.sourceSettings) return emptyList()

        return preferenceBackupCreator.createSource(includePrivatePreferences = options.privateSettings)
    }

    // SY -->
    suspend fun backupSavedSearches(options: BackupOptions): List<BackupSavedSearch> {
        if (!options.savedSearchesFeeds) return emptyList()

        return savedSearchBackupCreator()
    }
    // SY <--

    // KMK -->
    /**
     * Backup global Popular/Latest feeds
     */
    suspend fun backupFeeds(options: BackupOptions): List<BackupFeed> {
        if (!options.savedSearchesFeeds) return emptyList()

        return feedBackupCreator()
    }
    // KMK <--

    // Chimahon -->
    suspend fun backupNovels(options: BackupOptions): List<eu.kanade.tachiyomi.data.backup.models.BackupNovel> {
        if (!options.novels) return emptyList()

        return novelBackupCreator.backupNovels()
    }

    suspend fun backupNovelCategories(options: BackupOptions): List<eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory> {
        if (!options.novels) return emptyList()

        return novelBackupCreator.backupCategories()
    }

    suspend fun backupSourceNovels(options: BackupOptions): List<eu.kanade.tachiyomi.data.backup.models.BackupSourceNovel> {
        return sourceNovelBackupCreator(options)
    }

    fun backupMangaStats(options: BackupOptions): List<com.canopus.chimareader.data.MangaStats> {
        if (!options.appSettings) return emptyList()
        return com.canopus.chimareader.data.MangaStatsStorage.loadAll(context)
    }

    fun backupAnkiStats(options: BackupOptions): List<com.canopus.chimareader.data.AnkiStats> {
        if (!options.appSettings) return emptyList()
        return com.canopus.chimareader.data.AnkiStatsStorage.loadAll(context)
    }

    suspend fun backupSearchHistory(options: BackupOptions): List<BackupSearchHistory> {
        if (!options.history) return emptyList()

        return getSearchHistory.awaitAll().map {
            BackupSearchHistory(
                scope = it.scope,
                query = it.query,
                lastSearchedAt = it.lastSearchedAt,
            )
        }
    }
    // Chimahon <--

    companion object {
        private const val MAX_AUTO_BACKUPS: Int = 4
        private val FILENAME_REGEX = """${BuildConfig.APPLICATION_ID}_\d{4}-\d{2}-\d{2}_\d{2}-\d{2}.tachibk""".toRegex()

        fun getFilename(): String {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ENGLISH).format(Date())
            return "${BuildConfig.APPLICATION_ID}_$date.tachibk"
        }
    }
}
