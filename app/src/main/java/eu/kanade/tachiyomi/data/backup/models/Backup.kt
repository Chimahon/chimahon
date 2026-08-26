package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
data class LegacyBackup(
    @ProtoNumber(1) val backupManga: List<BackupManga> = emptyList(),
    @ProtoNumber(2) var backupCategories: List<BackupCategory> = emptyList(),
    @ProtoNumber(3) val backupAnime: List<BackupAnime> = emptyList(),
    @ProtoNumber(4) var backupAnimeCategories: List<BackupCategory> = emptyList(),
    // @ProtoNumber(100) var backupBrokenSources, legacy source model with non-compliant proto number,
    @ProtoNumber(101) var backupSources: List<BackupSource> = emptyList(),
    // @ProtoNumber(102) var backupBrokenAnimeSources, legacy source model with non-compliant proto number,
    @ProtoNumber(103) var backupAnimeSources: List<BackupAnimeSource> = emptyList(),
    @ProtoNumber(104) var backupPreferences: List<BackupPreference> = emptyList(),
    @ProtoNumber(105) var backupSourcePreferences: List<BackupSourcePreferences> = emptyList(),
    @ProtoNumber(107) var backupAnimeExtensionRepo: List<BackupExtensionRepos> = emptyList(),
    @ProtoNumber(108) var backupExtensionRepo: List<BackupExtensionRepos> = emptyList(),
) {
    fun toBackup(): Backup {
        return Backup(
            backupManga = backupManga,
            backupCategories = backupCategories,
            backupSources = backupSources,
            backupPreferences = backupPreferences,
            backupSourcePreferences = backupSourcePreferences,
            backupAnime = backupAnime,
            backupAnimeCategories = backupAnimeCategories,
            backupAnimeSources = backupAnimeSources,
            backupAnimeExtensionRepo = backupAnimeExtensionRepo,
        )
    }
}

@Serializable
data class Backup(
    @ProtoNumber(1) val backupManga: List<BackupManga> = emptyList(),
    @ProtoNumber(2) var backupCategories: List<BackupCategory> = emptyList(),
    // @ProtoNumber(100) var backupBrokenSources, legacy source model with non-compliant proto number,
    @ProtoNumber(101) var backupSources: List<BackupSource> = emptyList(),
    @ProtoNumber(104) var backupPreferences: List<BackupPreference> = emptyList(),
    @ProtoNumber(105) var backupSourcePreferences: List<BackupSourcePreferences> = emptyList(),
    @ProtoNumber(106) var backupExtensionStores: List<BackupExtensionStore> = emptyList(),
    // Aniyomi/Anikku anime values
    @ProtoNumber(501) val backupAnime: List<BackupAnime> = emptyList(),
    @ProtoNumber(502) var backupAnimeCategories: List<BackupCategory> = emptyList(),
    @ProtoNumber(503) var backupAnimeSources: List<BackupAnimeSource> = emptyList(),
    @ProtoNumber(505) var backupAnimeExtensionRepo: List<BackupExtensionRepos> = emptyList(),
    // SY specific values
    @ProtoNumber(600) var backupSavedSearches: List<BackupSavedSearch> = emptyList(),
    // KMK -->
    // Global Popular/Latest feeds
    @ProtoNumber(610) var backupFeeds: List<BackupFeed> = emptyList(),
    // KMK <--
    @ProtoNumber(650) var backupSearchHistory: List<BackupSearchHistory> = emptyList(),

    // Chimahon -->
    @ProtoNumber(700) var backupNovels: List<BackupNovel> = emptyList(),
    @ProtoNumber(701) var backupNovelCategories: List<BackupNovelCategory> = emptyList(),
    @ProtoNumber(710) var backupMangaStats: List<com.canopus.chimareader.data.MangaStats> = emptyList(),
    @ProtoNumber(711) var backupAnkiStats: List<com.canopus.chimareader.data.AnkiStats> = emptyList(),
    // Remembered extension identities for the "Extensions from Sync" feature (package id + signing hash only)
    @ProtoNumber(720) var backupMangaExtensions: List<BackupExtension> = emptyList(),
    @ProtoNumber(721) var backupAnimeExtensions: List<BackupExtension> = emptyList(),
    // Chimahon <--
)
