package eu.kanade.tachiyomi.di

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import chimahon.DictionaryRepository
import chimahon.audio.WordAudioPreferences
import chimahon.audio.WordAudioService
import chimahon.novel.manager.NovelSourceManager
import chimahon.ocr.LensClient
import chimahon.ocr.OcrCacheManager
import chimahon.novel.data.NovelCategoryStorage
import chimahon.novel.ui.reader.NovelReaderActivity
import eu.kanade.domain.track.store.DelayedAnimeTrackingStore
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.animesource.AndroidAnimeSourceManager
import eu.kanade.tachiyomi.data.BackupRestoreStatus
import eu.kanade.tachiyomi.data.LibraryUpdateStatus
import eu.kanade.tachiyomi.data.SyncStatus
import eu.kanade.tachiyomi.data.animedownload.AnimeDownloadCache
import eu.kanade.tachiyomi.data.animedownload.AnimeDownloadManager
import eu.kanade.tachiyomi.data.animedownload.AnimeDownloadProvider
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.cache.AnimeBackgroundCache
import eu.kanade.tachiyomi.data.cache.AnimeCoverCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.cache.PagePreviewCache
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.MokuroSidecarCopier
import eu.kanade.tachiyomi.data.ocr.LocalOcrBridge
import eu.kanade.tachiyomi.data.ocr.PaddleOcrBridge
import eu.kanade.tachiyomi.data.ocr.ModelDownloader
import eu.kanade.tachiyomi.data.panel.PanelModelDownloader
import eu.kanade.tachiyomi.data.ocr.OcrManager
import eu.kanade.tachiyomi.data.ocr.OcrStore
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.sync.service.GoogleDriveService
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.JavaScriptEngine
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.AndroidSourceManager
import eu.kanade.tachiyomi.ui.library.novels.ChimaReaderActivity
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.player.ExternalIntents
import eu.kanade.tachiyomi.util.LocalHttpServerHolder
import eu.kanade.tachiyomi.util.system.isDebugBuildType
import exh.eh.EHentaiUpdateHelper
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import nl.adaptivity.xmlutil.XmlDeclMode.Charset
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.storage.AndroidStorageFolderProvider
import tachiyomi.core.common.storage.UniFileTempFileManager
import tachiyomi.data.Chapters
import tachiyomi.data.Database
import tachiyomi.data.AndroidDatabaseHandler
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.novel.NovelChapterRepositoryImpl
import tachiyomi.data.novel.NovelHistoryRepositoryImpl
import tachiyomi.data.novel.NovelRepositoryImpl
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.FetchTypeColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.MangaUpdateStrategyColumnAdapter
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.Reading_sessions
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.handlers.anime.AndroidAnimeDatabaseHandler
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.data.track.anime.AnimeTrackRepositoryImpl
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelHistoryRepository
import tachiyomi.domain.novel.repository.NovelPluginStoreRepository
import tachiyomi.domain.novel.repository.NovelRepository
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.track.anime.repository.AnimeTrackRepository
import tachiyomi.mi.data.AnimeDatabase
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.image.anime.LocalAnimeBackgroundManager
import tachiyomi.source.local.image.anime.LocalAnimeCoverManager
import tachiyomi.source.local.image.anime.LocalEpisodeThumbnailManager
import tachiyomi.source.local.io.LocalSourceFileSystem
import tachiyomi.source.local.io.anime.LocalAnimeSourceFileSystem
import tachiyomi.source.local.entries.anime.LocalAnimeFetchTypeManager
import dataanime.Animehistory
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

class AppModule(val app: Application) : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingleton(app)
        addSingleton<Context>(app)
        NovelReaderActivity.activityClass = ChimaReaderActivity::class.java

        val sqlDriverManga = AndroidSqliteDriver(
            schema = Database.Schema,
            context = app,
            name = "tachiyomi.db",
            factory = if (isDebugBuildType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Support database inspector in Android Studio
                FrameworkSQLiteOpenHelperFactory()
            } else {
                RequerySQLiteOpenHelperFactory()
            },
            callback = object : AndroidSqliteDriver.Callback(Database.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    setPragma(db, "foreign_keys = ON")
                    setPragma(db, "journal_mode = WAL")
                    setPragma(db, "synchronous = NORMAL")
                }
                private fun setPragma(db: SupportSQLiteDatabase, pragma: String) {
                    val cursor = db.query("PRAGMA $pragma")
                    cursor.moveToFirst()
                    cursor.close()
                }
            },
        )

        addSingletonFactory {
            Database(
                driver = sqlDriverManga,
                historyAdapter = History.Adapter(
                    last_readAdapter = DateColumnAdapter,
                ),
                mangasAdapter = Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = MangaUpdateStrategyColumnAdapter,
                    memoAdapter = MemoColumnAdapter,
                ),
                chaptersAdapter = Chapters.Adapter(
                    memoAdapter = MemoColumnAdapter,
                ),
                reading_sessionsAdapter = Reading_sessions.Adapter(
                    read_atAdapter = DateColumnAdapter,
                ),
            )
        }

        addSingletonFactory<DatabaseHandler> {
            AndroidDatabaseHandler(
                get(),
                sqlDriverManga,
            )
        }

        val sqlDriverAnime = AndroidSqliteDriver(
            schema = AnimeDatabase.Schema,
            context = app,
            name = "tachiyomi.animedb",
            factory = if (isDebugBuildType && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                FrameworkSQLiteOpenHelperFactory()
            } else {
                RequerySQLiteOpenHelperFactory()
            },
            callback = object : AndroidSqliteDriver.Callback(AnimeDatabase.Schema) {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    setPragma(db, "foreign_keys = ON")
                    setPragma(db, "journal_mode = WAL")
                    setPragma(db, "synchronous = NORMAL")
                }
                private fun setPragma(db: SupportSQLiteDatabase, pragma: String) {
                    val cursor = db.query("PRAGMA $pragma")
                    cursor.moveToFirst()
                    cursor.close()
                }
            },
        )

        addSingletonFactory {
            AnimeDatabase(
                driver = sqlDriverAnime,
                animehistoryAdapter = Animehistory.Adapter(
                    last_seenAdapter = DateColumnAdapter,
                ),
                animesAdapter = dataanime.Animes.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = MangaUpdateStrategyColumnAdapter,
                    fetch_typeAdapter = FetchTypeColumnAdapter,
                ),
            )
        }

        addSingletonFactory<AnimeDatabaseHandler> {
            AndroidAnimeDatabaseHandler(
                get(),
                sqlDriverAnime,
            )
        }

        addSingletonFactory<AnimeTrackRepository> {
            AnimeTrackRepositoryImpl(
                get(),
            )
        }

        addSingletonFactory {
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            }
        }
        addSingletonFactory {
            XML {
                defaultPolicy {
                    ignoreUnknownChildren()
                }
                autoPolymorphic = true
                xmlDeclMode = Charset
                indent = 2
                xmlVersion = XmlVersion.XML10
            }
        }
        addSingletonFactory<ProtoBuf> {
            ProtoBuf
        }

        addSingletonFactory { ChapterCache(app, get(), get()) }

        addSingletonFactory { CoverCache(app) }
        addSingletonFactory { AnimeCoverCache(app) }
        addSingletonFactory { AnimeBackgroundCache(app) }
        addSingletonFactory { PagePreviewCache(app) }

        addSingletonFactory { NetworkHelper(app, get(), get()) }
        addSingletonFactory { JavaScriptEngine(app) }

        addSingletonFactory<SourceManager> { AndroidSourceManager(app, get(), get()) }
        addSingletonFactory<AnimeSourceManager> { AndroidAnimeSourceManager(app, get(), get()) }

        addSingletonFactory { ExtensionManager(app) }
        addSingletonFactory { AnimeExtensionManager(app) }

        addSingletonFactory { DownloadProvider(app) }
        addSingletonFactory { DownloadManager(app) }
        addSingletonFactory { DownloadCache(app) }
        addSingletonFactory { MokuroSidecarCopier(get<NetworkHelper>().client) }
        addSingletonFactory { AnimeDownloadProvider(app) }
        addSingletonFactory { AnimeDownloadManager(app) }
        addSingletonFactory { AnimeDownloadCache(app) }

        addSingletonFactory { TrackerManager() }
        addSingletonFactory { DelayedTrackingStore(app) }
        addSingletonFactory { DelayedAnimeTrackingStore(app) }

        addSingletonFactory { ImageSaver(app) }

        addSingletonFactory { AndroidStorageFolderProvider(app) }

        addSingletonFactory { LocalSourceFileSystem(get()) }
        addSingletonFactory { LocalCoverManager(app, get()) }
        addSingletonFactory { LocalAnimeSourceFileSystem(get()) }
        addSingletonFactory { LocalAnimeCoverManager(app, get()) }
        addSingletonFactory { LocalAnimeBackgroundManager(app, get()) }
        addSingletonFactory { LocalEpisodeThumbnailManager(app, get()) }
        addSingletonFactory { LocalAnimeFetchTypeManager(get()) }
        addSingletonFactory { UniFileTempFileManager(app) }

        addSingletonFactory { StorageManager(app, get()) }
        addSingletonFactory { LocalHttpServerHolder(get()) }

        addSingletonFactory { ExternalIntents() }
        addSingletonFactory { EHentaiUpdateHelper(app) }

        addSingletonFactory { NovelCategoryStorage(app) }
        addSingletonFactory {
            val dictionaryPreferences = get<DictionaryPreferences>()
            DictionaryRepository(
                externalFilesDir = app.getExternalFilesDir(null),
                koreanParserMode = { dictionaryPreferences.koreanParserMode().get() },
            )
        }
        addSingletonFactory { chimahon.novel.plugin.NovelPluginManager(get()) }
        addSingletonFactory<tachiyomi.domain.source.novel.repository.StubNovelSourceRepository> {
            tachiyomi.data.source.novel.StubNovelSourceRepositoryImpl(get())
        }
        addSingletonFactory { NovelSourceManager(get(), get()) }
        addSingletonFactory { chimahon.novel.reader.NovelChapterLoader() }
        addSingletonFactory<NovelRepository> { NovelRepositoryImpl(get()) }
        addSingletonFactory<NovelChapterRepository> { NovelChapterRepositoryImpl(get()) }
        addSingletonFactory<NovelHistoryRepository> { NovelHistoryRepositoryImpl(get()) }
        addSingletonFactory<tachiyomi.domain.updates.novel.repository.NovelUpdatesRepository> {
            tachiyomi.data.updates.novel.NovelUpdatesRepositoryImpl(get())
        }
        addSingletonFactory<tachiyomi.domain.novel.repository.NovelCategoryRepository> {
            tachiyomi.data.novel.NovelCategoryRepositoryImpl(get())
        }
        addSingletonFactory<tachiyomi.domain.novel.repository.NovelReadingStatsRepository> {
            tachiyomi.data.novel.NovelReadingStatsRepositoryImpl(get())
        }
        addSingletonFactory { chimahon.novel.download.NovelDownloadProvider(app) }
        addSingletonFactory { chimahon.novel.download.NovelDownloadNotifier(app) }
        addSingletonFactory { chimahon.novel.download.NovelDownloadManager(app) }
        addSingletonFactory { chimahon.novel.interactor.MigrateNovelJsonData() }
        addSingletonFactory { chimahon.novel.interactor.RegisterLocalNovelHome() }
        addSingletonFactory<NovelPluginStoreRepository> {
            tachiyomi.data.novel.NovelPluginStoreRepositoryImpl(get())
        }
        addSingletonFactory {
            chimahon.novel.cache.NovelChapterCache(get<android.app.Application>().applicationContext)
        }
        addSingletonFactory { chimahon.novel.interactor.SyncNovelChapters() }
        addSingletonFactory { chimahon.novel.interactor.SetNovelReadStatus() }
        addSingletonFactory { chimahon.novel.interactor.SetNovelChapterFlags() }
        addSingletonFactory { chimahon.novel.interactor.GetNextNovelChapters() }
        addSingletonFactory { chimahon.novel.interactor.UpdateNovel() }
        addSingletonFactory { chimahon.novel.interactor.SetNovelCategories() }
        addSingletonFactory { chimahon.novel.interactor.ToggleNovelSourcePin() }
        addSingletonFactory { chimahon.novel.interactor.FetchNovelInterval() }
        addSingletonFactory { eu.kanade.tachiyomi.data.backup.create.creators.NovelExtensionRepoBackupCreator() }
        addSingletonFactory { eu.kanade.tachiyomi.data.backup.restore.restorers.NovelExtensionRepoRestorer() }
        addSingletonFactory<WordAudioPreferences> { get<DictionaryPreferences>() }
        addSingletonFactory { WordAudioService(app) }

        addSingletonFactory { LensClient() }
        addSingletonFactory { LocalOcrBridge(app) }
        addSingletonFactory { PaddleOcrBridge(app) }
        addSingletonFactory { ModelDownloader(app, get<NetworkHelper>().client) }
        addSingletonFactory { OcrStore(app) }
        addSingletonFactory { OcrCacheManager(app, get()) }
        addSingletonFactory { OcrManager(app, get(), get()) }
        addSingletonFactory { PanelModelDownloader(app, get<NetworkHelper>().client) }

        addSingletonFactory<BackupRestoreStatus> { BackupRestoreStatus() }
        addSingletonFactory<SyncStatus> { SyncStatus() }
        addSingletonFactory<LibraryUpdateStatus> { LibraryUpdateStatus() }

        // AM (CONNECTIONS) -->
        addSingletonFactory { ConnectionsManager() }
        // <-- AM (CONNECTIONS)

        // Asynchronously init expensive components for a faster cold start
        ContextCompat.getMainExecutor(app).execute {
            get<NetworkHelper>()

            get<SourceManager>()
            get<AnimeSourceManager>()

            get<Database>()

            get<DownloadManager>()
            get<AnimeDownloadManager>()
        }

        addSingletonFactory { GoogleDriveService(app) }
    }
}
