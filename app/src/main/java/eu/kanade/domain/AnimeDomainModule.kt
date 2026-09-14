package eu.kanade.domain

import eu.kanade.domain.track.anime.interactor.AddAnimeTracks
import eu.kanade.domain.track.anime.interactor.RefreshAnimeTracks
import eu.kanade.domain.entries.anime.interactor.UpdateAnime as AppUpdateAnime
import eu.kanade.domain.entries.anime.interactor.SyncSeasonsWithSource
import eu.kanade.domain.episode.interactor.SetSeenStatus as AppSetSeenStatus
import eu.kanade.domain.track.interactor.SyncEpisodeProgressWithTrack
import eu.kanade.domain.track.interactor.TrackEpisode
import eu.kanade.domain.episode.interactor.GetAvailableAnimeScanlators
import eu.kanade.domain.episode.interactor.GetExcludedAnimeScanlators
import eu.kanade.domain.episode.interactor.SetExcludedAnimeScanlators
import mihon.domain.animemigration.usecases.MigrateAnimeUseCase
import tachiyomi.data.entries.anime.CustomAnimeRepositoryImpl
import tachiyomi.data.entries.anime.AnimeRepositoryImpl
import tachiyomi.data.entries.anime.AnimeMergeRepositoryImpl
import tachiyomi.data.category.AnimeCategoryRepositoryImpl
import tachiyomi.data.episode.EpisodeRepositoryImpl
import tachiyomi.domain.entries.anime.interactor.DeleteAnimeById
import tachiyomi.domain.entries.anime.interactor.DeleteByMergeId
import tachiyomi.domain.entries.anime.interactor.DeleteMergeById
import tachiyomi.domain.entries.anime.interactor.GetAllAnime
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.interactor.GetAnimeBySource
import tachiyomi.domain.entries.anime.interactor.GetAnimeByUrlAndSourceId
import tachiyomi.domain.entries.anime.interactor.FetchInterval
import tachiyomi.domain.entries.anime.interactor.GetAnimeSeasonsByParentId
import tachiyomi.domain.entries.anime.interactor.GetAnimeWithEpisodes
import tachiyomi.domain.entries.anime.interactor.GetCustomAnimeInfo
import tachiyomi.domain.entries.anime.interactor.GetDuplicateLibraryAnime
import tachiyomi.domain.entries.anime.interactor.GetFavoriteAnime
import tachiyomi.domain.entries.anime.interactor.GetFavorites
import tachiyomi.domain.entries.anime.interactor.GetLibraryAnime
import tachiyomi.domain.entries.anime.interactor.GetMergedAnime
import tachiyomi.domain.entries.anime.interactor.GetMergedAnimeById
import tachiyomi.domain.entries.anime.interactor.GetMergedAnimeForDownloading
import tachiyomi.domain.entries.anime.interactor.GetMergedReferencesById
import tachiyomi.domain.entries.anime.interactor.GetSeenAnimeNotInLibraryView
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.anime.interactor.ResetViewerFlags
import tachiyomi.domain.entries.anime.interactor.SetCustomAnimeInfo
import tachiyomi.domain.entries.anime.interactor.SetAnimeEpisodeFlags
import tachiyomi.domain.entries.anime.interactor.SetAnimeSeasonFlags
import tachiyomi.domain.entries.anime.interactor.UpdateAnime as DomainUpdateAnime
import tachiyomi.domain.entries.anime.interactor.UpdateMergedSettings
import tachiyomi.domain.entries.anime.repository.AnimeMergeRepository
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.entries.anime.repository.CustomAnimeRepository
import tachiyomi.domain.source.anime.interactor.GetAnimeSourcesWithNonLibraryAnime
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.category.interactor.CreateAnimeCategory
import tachiyomi.domain.category.interactor.DeleteAnimeCategory
import tachiyomi.domain.category.interactor.GetAnimeCategories
import tachiyomi.domain.category.interactor.HideAnimeCategory
import tachiyomi.domain.category.interactor.RenameAnimeCategory
import tachiyomi.domain.category.interactor.ReorderAnimeCategory
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.interactor.SetSortModeForAnimeCategory
import tachiyomi.domain.category.repository.AnimeCategoryRepository
import eu.kanade.domain.episode.interactor.SyncEpisodesWithSource
import tachiyomi.domain.episode.interactor.FilterEpisodesForDownload
import tachiyomi.domain.episode.interactor.GetEpisode
import tachiyomi.domain.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.episode.interactor.GetMergedEpisodesByAnimeId
import tachiyomi.domain.episode.interactor.SetAnimeDefaultEpisodeFlags
import tachiyomi.domain.episode.interactor.SetSeenStatus as DomainSetSeenStatus
import tachiyomi.domain.season.interactor.SetAnimeDefaultSeasonFlags
import tachiyomi.domain.season.interactor.ShouldUpdateDbSeason
import tachiyomi.domain.episode.interactor.ShouldUpdateDbEpisode
import tachiyomi.domain.episode.interactor.UpdateEpisode
import tachiyomi.domain.episode.repository.EpisodeRepository
import tachiyomi.domain.history.interactor.GetNextEpisodes
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.AnimeLibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack
import tachiyomi.domain.track.anime.interactor.DeleteAnimeTrack
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.anime.interactor.GetTracksPerAnime
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addFactory
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

class AnimeDomainModule : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        addSingletonFactory<AnimeRepository> { AnimeRepositoryImpl(get()) }
        addSingletonFactory<CustomAnimeRepository> { CustomAnimeRepositoryImpl(get()) }
        addFactory { GetAnime(get()) }
        addFactory { GetAllAnime(get()) }
        addFactory { GetAnimeBySource(get()) }
        addFactory { GetAnimeByUrlAndSourceId(get()) }
        addFactory { FetchInterval(get()) }
        addFactory { GetAnimeSeasonsByParentId(get()) }
        addFactory { GetAnimeWithEpisodes(get(), get()) }
        addFactory { GetCustomAnimeInfo(get()) }
        addFactory { DomainUpdateAnime(get()) }
        addFactory { AppUpdateAnime(get(), get()) }
        addFactory { GetFavoriteAnime(get()) }
        addFactory { GetFavorites(get()) }
        addFactory { GetLibraryAnime(get()) }
        addFactory { NetworkToLocalAnime(get(), get()) }
        addFactory { SetCustomAnimeInfo(get()) }
        addFactory { SetAnimeEpisodeFlags(get()) }
        addFactory { SetAnimeSeasonFlags(get()) }
        addFactory { GetDuplicateLibraryAnime(get()) }
        addFactory { DeleteAnimeById(get()) }
        addFactory { ResetViewerFlags(get()) }
        addFactory { GetSeenAnimeNotInLibraryView(get()) }
        addFactory { GetAnimeSourcesWithNonLibraryAnime(get()) }

        addSingletonFactory<AnimeMergeRepository> { AnimeMergeRepositoryImpl(get()) }
        addFactory { GetMergedAnime(get()) }
        addFactory { GetMergedAnimeById(get()) }
        addFactory { GetMergedReferencesById(get()) }
        addFactory { UpdateMergedSettings(get()) }
        addFactory { DeleteByMergeId(get()) }
        addFactory { DeleteMergeById(get()) }
        addFactory { GetMergedAnimeForDownloading(get()) }

        addSingletonFactory<EpisodeRepository> { EpisodeRepositoryImpl(get()) }
        addFactory { GetEpisode(get()) }
        addFactory { GetEpisodesByAnimeId(get()) }
        addFactory { GetMergedEpisodesByAnimeId(get(), get()) }
        addFactory { GetAvailableAnimeScanlators(get()) }
        addFactory { GetExcludedAnimeScanlators(get()) }
        addFactory { SetExcludedAnimeScanlators(get()) }
        addFactory { UpdateEpisode(get()) }
        addFactory { SetAnimeDefaultEpisodeFlags(get(), get(), get()) }
        addFactory { SetAnimeDefaultSeasonFlags(get(), get(), get()) }
        addFactory { DomainSetSeenStatus(get()) }
        addFactory { AppSetSeenStatus(get(), get()) }
        addFactory { ShouldUpdateDbEpisode() }
        addFactory { ShouldUpdateDbSeason() }
        addFactory { SyncSeasonsWithSource(get(), get(), get(), get(), get()) }
        addFactory { SyncEpisodesWithSource(get(), get(), get(), get(), get()) }
        addFactory { FilterEpisodesForDownload(get(), get(), get()) }
        addFactory { GetNextEpisodes(get(), get(), get()) }

        addSingletonFactory<AnimeCategoryRepository> { AnimeCategoryRepositoryImpl(get()) }
        addFactory { GetAnimeCategories(get()) }
        addFactory { CreateAnimeCategory(get(), get()) }
        addFactory { DeleteAnimeCategory(get(), get(), get()) }
        addFactory { RenameAnimeCategory(get()) }
        addFactory { ReorderAnimeCategory(get()) }
        addFactory { HideAnimeCategory(get()) }
        addFactory { SetAnimeCategories(get()) }
        addFactory { SetSortModeForAnimeCategory(get(), get()) }

        addSingletonFactory { AnimeLibraryPreferences(get()) }

        addFactory { InsertAnimeTrack(get()) }
        addFactory { DeleteAnimeTrack(get()) }
        addFactory { GetAnimeTracks(get()) }
        addFactory { GetTracksPerAnime(get()) }
        addFactory { SyncEpisodeProgressWithTrack(get(), get(), get()) }
        addFactory { AddAnimeTracks(get(), get(), get(), get()) }
        addFactory { RefreshAnimeTracks(get(), get(), get(), get()) }
        addFactory { TrackEpisode(get(), get(), get(), get()) }
        addFactory {
            MigrateAnimeUseCase(
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
            )
        }
    }
}
