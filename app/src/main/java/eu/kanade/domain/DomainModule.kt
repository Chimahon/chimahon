package eu.kanade.domain

import android.app.Application
import eu.kanade.domain.entries.anime.interactor.SetAnimeViewerFlags
import eu.kanade.domain.animeextension.interactor.GetAnimeExtensionLanguages
import eu.kanade.domain.animeextension.interactor.GetAnimeExtensionSources
import eu.kanade.domain.animeextension.interactor.GetAnimeExtensionsByType
import eu.kanade.domain.chapter.interactor.GetAvailableScanlators
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.animedownload.interactor.DeleteAnimeDownload
import eu.kanade.domain.download.interactor.DeleteDownload
import eu.kanade.domain.extension.interactor.GetExtensionLanguages
import eu.kanade.domain.extension.interactor.GetExtensionSources
import eu.kanade.domain.extension.interactor.GetExtensionsByType
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.manga.interactor.GetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetExcludedScanlators
import eu.kanade.domain.manga.interactor.SetMangaViewerFlags
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.interactor.GetLanguagesWithSources
import eu.kanade.domain.source.anime.interactor.GetAnimeSourcesWithFavoriteCount
import eu.kanade.domain.source.interactor.GetSourcesWithFavoriteCount
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.domain.source.interactor.ToggleIncognito
import eu.kanade.domain.source.interactor.ToggleLanguage
import eu.kanade.domain.source.interactor.ToggleAnimeLanguage
import eu.kanade.domain.source.interactor.ToggleSource
import eu.kanade.domain.source.interactor.ToggleSourcePin
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.domain.track.interactor.SyncChapterProgressWithTrack
import eu.kanade.domain.track.interactor.TrackChapter
import mihon.data.extension.repository.ExtensionStoreRepositoryImpl
import mihon.data.extension.service.ExtensionStoreService
import mihon.data.panel.PanelDetectionRepositoryImpl
import eu.kanade.tachiyomi.ui.player.utils.TrackSelect
import mihon.domain.panel.interactor.DetectPanels
import mihon.domain.panel.repository.PanelDetectionRepository
import mihon.data.repository.AnimeExtensionRepoRepositoryImpl
import mihon.domain.animeextensionrepo.interactor.CreateAnimeExtensionRepo
import mihon.domain.animeextensionrepo.interactor.DeleteAnimeExtensionRepo
import mihon.domain.animeextensionrepo.interactor.GetAnimeExtensionRepo
import mihon.domain.animeextensionrepo.interactor.GetAnimeExtensionRepoCount
import mihon.domain.animeextensionrepo.interactor.ReplaceAnimeExtensionRepo
import mihon.domain.animeextensionrepo.interactor.UpdateAnimeExtensionRepo
import mihon.domain.animeextensionrepo.repository.AnimeExtensionRepoRepository
import mihon.data.repository.NovelExtensionRepoRepositoryImpl
import mihon.domain.novelextensionrepo.interactor.CreateNovelExtensionRepo
import mihon.domain.novelextensionrepo.interactor.DeleteNovelExtensionRepo
import mihon.domain.novelextensionrepo.interactor.GetNovelExtensionRepo
import mihon.domain.novelextensionrepo.interactor.GetNovelExtensionRepoCount
import mihon.domain.novelextensionrepo.repository.NovelExtensionRepoRepository
import mihon.domain.extensionrepo.service.ExtensionRepoService
import mihon.domain.chapter.interactor.FilterChaptersForDownload
import mihon.domain.extension.interactor.AddExtensionStore
import mihon.domain.extension.interactor.GetExtensionStoreCountAsFlow
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.interactor.RemoveExtensionStore
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.repository.ExtensionStoreRepository
import mihon.domain.migration.usecases.MigrateMangaUseCase
import mihon.domain.source.interactor.UpdateMangaFromRemote
import mihon.domain.upcoming.interactor.GetUpcomingManga
import tachiyomi.data.source.anime.AnimeSourceRepositoryImpl
import tachiyomi.data.source.anime.StubAnimeSourceRepositoryImpl
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.history.AnimeHistoryRepositoryImpl
import tachiyomi.data.history.HistoryRepositoryImpl
import tachiyomi.data.history.SearchHistoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.release.ReleaseServiceImpl
import tachiyomi.data.source.SourceRepositoryImpl
import tachiyomi.data.source.StubSourceRepositoryImpl
import tachiyomi.data.track.TrackRepositoryImpl
import tachiyomi.data.updates.anime.AnimeUpdatesRepositoryImpl
import tachiyomi.data.updates.UpdatesRepositoryImpl
import tachiyomi.domain.source.anime.interactor.GetRemoteAnime
import tachiyomi.domain.source.anime.repository.AnimeSourceRepository
import tachiyomi.domain.source.anime.repository.StubAnimeSourceRepository
import tachiyomi.domain.category.interactor.CreateCategoryWithName
import tachiyomi.domain.category.interactor.DeleteCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.HideCategory
import tachiyomi.domain.category.interactor.RenameCategory
import tachiyomi.domain.category.interactor.ReorderCategory
import tachiyomi.domain.category.interactor.ResetCategoryFlags
import tachiyomi.domain.category.interactor.SetDisplayMode
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.interactor.SetSortModeForCategory
import tachiyomi.domain.category.interactor.UpdateCategory
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.interactor.GetBookmarkedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChapterByUrlAndMangaId
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.chapter.interactor.ShouldUpdateDbChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.history.interactor.ClearSearchHistory
import tachiyomi.domain.history.interactor.DeleteSearchHistory
import tachiyomi.domain.history.interactor.GetAnimeHistory
import tachiyomi.domain.history.interactor.GetAllHistory
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.interactor.GetSearchHistory
import tachiyomi.domain.history.interactor.GetTotalReadDuration
import tachiyomi.domain.history.interactor.RemoveAnimeHistory
import tachiyomi.domain.history.interactor.RemoveHistory
import tachiyomi.domain.history.interactor.UpsertAnimeHistory
import tachiyomi.domain.history.interactor.UpsertHistory
import tachiyomi.domain.history.interactor.UpsertSearchHistory
import tachiyomi.domain.history.repository.AnimeHistoryRepository
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.history.repository.SearchHistoryRepository
import tachiyomi.domain.manga.interactor.FetchInterval
import tachiyomi.domain.manga.interactor.GetDuplicateLibraryManga
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.GetMangaWithChapters
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.interactor.ResetViewerFlags
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.interactor.UpdateMangaNotes
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.service.ReleaseService
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.interactor.GetSourcesWithNonLibraryManga
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.repository.StubSourceRepository
import tachiyomi.data.custombutton.CustomButtonRepositoryImpl
import tachiyomi.domain.custombuttons.interactor.CreateCustomButton
import tachiyomi.domain.custombuttons.interactor.DeleteCustomButton
import tachiyomi.domain.custombuttons.interactor.GetCustomButtons
import tachiyomi.domain.custombuttons.interactor.ReorderCustomButton
import tachiyomi.domain.custombuttons.interactor.ToggleFavoriteCustomButton
import tachiyomi.domain.custombuttons.interactor.UpdateCustomButton
import tachiyomi.domain.custombuttons.repository.CustomButtonRepository
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.repository.TrackRepository
import tachiyomi.domain.updates.interactor.GetUpdates
import tachiyomi.domain.updates.anime.interactor.GetAnimeUpdates
import tachiyomi.domain.updates.anime.repository.AnimeUpdatesRepository
import tachiyomi.domain.updates.repository.UpdatesRepository
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addFactory
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

class DomainModule : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory<CategoryRepository> { CategoryRepositoryImpl(get()) }
        addFactory { GetCategories(get()) }
        addFactory { ResetCategoryFlags(get(), get()) }
        addFactory { SetDisplayMode(get()) }
        addFactory { SetSortModeForCategory(get(), get()) }
        addFactory { CreateCategoryWithName(get(), get()) }
        addFactory { RenameCategory(get()) }
        addFactory { ReorderCategory(get()) }
        addFactory { UpdateCategory(get()) }
        addFactory { DeleteCategory(get(), get(), get()) }
        // KMK -->
        addFactory { HideCategory(get()) }
        // KMK <--

        addSingletonFactory<MangaRepository> { MangaRepositoryImpl(get()) }
        addFactory { GetDuplicateLibraryManga(get()) }
        addFactory { GetFavorites(get()) }
        addFactory { GetLibraryManga(get()) }
        addFactory { GetMangaWithChapters(get(), get()) }
        addFactory { GetMangaByUrlAndSourceId(get()) }
        addFactory { GetManga(get()) }
        addFactory { GetNextChapters(get(), get(), get(), get()) }
        addFactory { GetUpcomingManga(get()) }
        addFactory { ResetViewerFlags(get()) }
        addFactory { SetMangaChapterFlags(get()) }
        addFactory { FetchInterval(get()) }
        addFactory { SetMangaDefaultChapterFlags(get(), get(), get()) }
        addFactory { SetMangaViewerFlags(get()) }
        addFactory { NetworkToLocalManga(get()) }
        addFactory { UpdateManga(get(), get()) }
        addFactory { UpdateMangaNotes(get()) }
        addFactory { SetMangaCategories(get()) }
        addFactory { GetExcludedScanlators(get()) }
        addFactory { SetExcludedScanlators(get()) }
        addFactory {
            MigrateMangaUseCase(
                get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            )
        }

        addSingletonFactory<ReleaseService> { ReleaseServiceImpl(get(), get()) }
        addFactory { GetApplicationRelease(get(), get()) }

        addSingletonFactory<TrackRepository> { TrackRepositoryImpl(get()) }
        addFactory { TrackChapter(get(), get(), get(), get()) }
        addFactory { AddTracks(get(), get(), get(), get()) }
        addFactory { RefreshTracks(get(), get(), get(), get()) }
        addFactory { DeleteTrack(get()) }
        addFactory { GetTracksPerManga(get(), get()) }
        addFactory { GetTracks(get()) }
        addFactory { InsertTrack(get()) }
        addFactory { SyncChapterProgressWithTrack(get(), get(), get()) }

        addSingletonFactory<ChapterRepository> { ChapterRepositoryImpl(get()) }
        addFactory { GetChapter(get()) }
        addFactory { GetChaptersByMangaId(get()) }
        addFactory { GetBookmarkedChaptersByMangaId(get(), get(), get()) }
        addFactory { GetChapterByUrlAndMangaId(get()) }
        addFactory { UpdateChapter(get()) }
        addFactory { SetReadStatus(get(), get(), get(), get(), get()) }
        addFactory { ShouldUpdateDbChapter() }
        addFactory { SyncChaptersWithSource(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        addFactory { GetAvailableScanlators(get()) }
        addFactory { FilterChaptersForDownload(get(), get(), get(), get()) }

        addSingletonFactory<HistoryRepository> { HistoryRepositoryImpl(get()) }
        addFactory { GetAllHistory(get()) }
        addFactory { GetHistory(get()) }
        addFactory { UpsertHistory(get()) }
        addFactory { RemoveHistory(get()) }
        addFactory { GetTotalReadDuration(get()) }

        addSingletonFactory<AnimeHistoryRepository> { AnimeHistoryRepositoryImpl(get()) }
        addFactory { GetAnimeHistory(get()) }
        addFactory { UpsertAnimeHistory(get()) }
        addFactory { RemoveAnimeHistory(get()) }

        addSingletonFactory<SearchHistoryRepository> { SearchHistoryRepositoryImpl(get()) }
        addFactory { GetSearchHistory(get()) }
        addFactory { UpsertSearchHistory(get()) }
        addFactory { DeleteSearchHistory(get()) }
        addFactory { ClearSearchHistory(get()) }

        addFactory { DeleteDownload(get(), get()) }
        addFactory { DeleteAnimeDownload(get(), get()) }

        addSingletonFactory<CustomButtonRepository> { CustomButtonRepositoryImpl(get()) }
        addFactory { CreateCustomButton(get()) }
        addFactory { DeleteCustomButton(get()) }
        addFactory { GetCustomButtons(get()) }
        addFactory { UpdateCustomButton(get()) }
        addFactory { ReorderCustomButton(get()) }
        addFactory { ToggleFavoriteCustomButton(get()) }

        addFactory { SetAnimeViewerFlags(get()) }

        addFactory { TrackSelect(get(), get()) }

        addFactory { GetExtensionsByType(get(), get()) }
        addFactory { GetExtensionSources(get()) }
        addFactory { GetExtensionLanguages(get(), get()) }

        addSingletonFactory<UpdatesRepository> { UpdatesRepositoryImpl(get()) }
        addFactory { GetUpdates(get()) }
        addSingletonFactory<AnimeUpdatesRepository> { AnimeUpdatesRepositoryImpl(get()) }
        addFactory { GetAnimeUpdates(get()) }

        addSingletonFactory<SourceRepository> { SourceRepositoryImpl(get(), get()) }
        addSingletonFactory<StubSourceRepository> { StubSourceRepositoryImpl(get()) }
        addFactory { GetEnabledSources(get(), get()) }
        addFactory { GetLanguagesWithSources(get(), get()) }
        addFactory { GetRemoteManga(get()) }
        addFactory { GetSourcesWithFavoriteCount(get(), get()) }
        addFactory { GetSourcesWithNonLibraryManga(get()) }
        addFactory { SetMigrateSorting(get()) }
        addFactory { ToggleLanguage(get()) }
        addFactory { ToggleAnimeLanguage(get()) }
        addFactory { eu.kanade.domain.source.interactor.ToggleNovelLanguage(get()) }
        addFactory { ToggleSource(get()) }
        addFactory { ToggleSourcePin(get()) }
        addFactory { TrustExtension(get(), get()) }

        addSingletonFactory { ExtensionStoreService(get(), get(), get()) }
        addSingletonFactory { ExtensionRepoService(get(), get()) }
        addSingletonFactory<ExtensionStoreRepository> { ExtensionStoreRepositoryImpl(get(), get()) }
        addFactory { AddExtensionStore(get()) }
        addFactory { GetExtensionStoreCountAsFlow(get()) }
        addFactory { GetExtensionStores(get()) }
        addFactory { RemoveExtensionStore(get()) }
        addFactory { UpdateExtensionStores(get()) }

        addSingletonFactory<AnimeExtensionRepoRepository> { AnimeExtensionRepoRepositoryImpl(get()) }
        addSingletonFactory<StubAnimeSourceRepository> { StubAnimeSourceRepositoryImpl(get()) }
        addSingletonFactory<AnimeSourceRepository> { AnimeSourceRepositoryImpl(get(), get()) }
        addFactory { GetAnimeSourcesWithFavoriteCount(get(), get()) }
        addFactory { GetRemoteAnime(get()) }
        addFactory { GetAnimeExtensionRepo(get()) }
        addFactory { GetAnimeExtensionRepoCount(get()) }
        addFactory { CreateAnimeExtensionRepo(get(), get()) }
        addFactory { DeleteAnimeExtensionRepo(get()) }
        addFactory { ReplaceAnimeExtensionRepo(get()) }
        addFactory { UpdateAnimeExtensionRepo(get(), get()) }
        addFactory { GetAnimeExtensionsByType(get(), get()) }
        addFactory { GetAnimeExtensionLanguages(get(), get()) }
        addFactory { GetAnimeExtensionSources(get()) }

        addSingletonFactory<NovelExtensionRepoRepository> { NovelExtensionRepoRepositoryImpl(get()) }
        addFactory { GetNovelExtensionRepo(get()) }
        addFactory { GetNovelExtensionRepoCount(get()) }
        addFactory { CreateNovelExtensionRepo(get()) }
        addFactory { DeleteNovelExtensionRepo(get()) }

        addFactory { ToggleIncognito(get()) }
        addFactory { GetIncognitoState(get(), get(), get()) }
        addFactory { GetAnimeIncognitoState(get(), get(), get()) }

        addFactory { UpdateMangaFromRemote(get(), get(), get(), get(), get(), get(), get()) }

        addSingletonFactory<PanelDetectionRepository> {
            PanelDetectionRepositoryImpl(
                context = get<Application>(),
            )
        }
        addFactory { DetectPanels(get()) }
    }
}
