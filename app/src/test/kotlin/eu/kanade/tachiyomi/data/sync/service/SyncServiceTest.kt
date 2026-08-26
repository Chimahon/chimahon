package eu.kanade.tachiyomi.data.sync.service

import android.content.Context
import eu.kanade.domain.sync.SyncPreferences
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeSource
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupEpisode
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class SyncServiceTest {

    @Test
    fun `merge preserves anime sync data`() {
        val service = TestSyncService()

        val localAnime = BackupAnime(
            source = 1L,
            url = "/anime/local",
            title = "Local Anime",
            author = "Studio",
            categories = listOf(0L),
            version = 1L,
            episodes = listOf(BackupEpisode(url = "/ep/1", name = "Episode 1", version = 1L)),
        )
        val remoteAnime = BackupAnime(
            source = 2L,
            url = "/anime/remote",
            title = "Remote Anime",
            author = "Studio",
            categories = listOf(1L),
            version = 1L,
            episodes = listOf(BackupEpisode(url = "/ep/2", name = "Episode 2", version = 1L)),
        )

        val merged = service.merge(
            local = SyncData(
                backup = Backup(
                    backupAnime = listOf(localAnime),
                    backupAnimeCategories = listOf(BackupCategory(name = "Watching", order = 0L)),
                    backupAnimeSources = listOf(BackupAnimeSource("Local source", 1L)),
                    backupAnimeExtensionRepo = listOf(extensionRepo("https://local.example")),
                ),
            ),
            remote = SyncData(
                backup = Backup(
                    backupAnime = listOf(remoteAnime),
                    backupAnimeCategories = listOf(BackupCategory(name = "Completed", order = 1L)),
                    backupAnimeSources = listOf(BackupAnimeSource("Remote source", 2L)),
                    backupAnimeExtensionRepo = listOf(extensionRepo("https://remote.example")),
                ),
            ),
        ).backup!!

        merged.backupAnime.map { it.title }.shouldContainExactlyInAnyOrder("Local Anime", "Remote Anime")
        merged.backupAnimeCategories.map { it.name }.shouldContainExactlyInAnyOrder("Watching", "Completed")
        merged.backupAnimeSources.map { it.sourceId }.shouldContainExactlyInAnyOrder(1L, 2L)
        merged.backupAnimeExtensionRepo.map { it.baseUrl }
            .shouldContainExactlyInAnyOrder("https://local.example", "https://remote.example")
        merged.backupAnime.flatMap { it.episodes }.map { it.name }
            .shouldContainExactlyInAnyOrder("Episode 1", "Episode 2")
    }

    @Test
    fun `merge keeps newer anime version and merged episodes`() {
        val service = TestSyncService()

        val merged = service.merge(
            local = SyncData(
                backup = Backup(
                    backupAnime = listOf(
                        BackupAnime(
                            source = 1L,
                            url = "/anime/same",
                            title = "Same Anime",
                            author = "Studio",
                            version = 2L,
                            episodes = listOf(BackupEpisode(url = "/ep/1", name = "Episode 1", version = 1L)),
                        ),
                    ),
                ),
            ),
            remote = SyncData(
                backup = Backup(
                    backupAnime = listOf(
                        BackupAnime(
                            source = 1L,
                            url = "/anime/same",
                            title = "Same Anime",
                            author = "Studio",
                            version = 1L,
                            episodes = listOf(BackupEpisode(url = "/ep/2", name = "Episode 2", version = 1L)),
                        ),
                    ),
                ),
            ),
        ).backup!!

        merged.backupAnime.single().version shouldBe 2L
        merged.backupAnime.single().episodes.map { it.name }
            .shouldContainExactlyInAnyOrder("Episode 1", "Episode 2")
    }

    @Test
    fun `merge unions and de-duplicates extension identities (extension-only payload)`() {
        val service = TestSyncService()

        val merged = service.merge(
            local = SyncData(
                backup = Backup(
                    backupMangaExtensions = listOf(
                        BackupExtension("pkg.manga.a", "hashA"),
                        BackupExtension("pkg.manga.shared", "hashShared"),
                    ),
                    backupAnimeExtensions = listOf(BackupExtension("pkg.anime.a", "hashAnimeA")),
                ),
            ),
            remote = SyncData(
                backup = Backup(
                    backupMangaExtensions = listOf(
                        BackupExtension("pkg.manga.shared", "hashShared"), // duplicate
                        BackupExtension("pkg.manga.b", "hashB"),
                    ),
                    backupAnimeExtensions = listOf(BackupExtension("pkg.anime.b", "hashAnimeB")),
                ),
            ),
        ).backup!!

        merged.backupMangaExtensions.map { it.pkgName }
            .shouldContainExactlyInAnyOrder("pkg.manga.a", "pkg.manga.shared", "pkg.manga.b")
        merged.backupAnimeExtensions.map { it.pkgName }
            .shouldContainExactlyInAnyOrder("pkg.anime.a", "pkg.anime.b")
    }

    private fun extensionRepo(baseUrl: String) = BackupExtensionRepos(
        baseUrl = baseUrl,
        name = baseUrl,
        shortName = null,
        website = baseUrl,
        signingKeyFingerprint = "fingerprint",
    )

    private class TestSyncService : SyncService(
        context = mockk<Context>(relaxed = true),
        json = Json { ignoreUnknownKeys = true },
        syncPreferences = mockk<SyncPreferences>().also {
            every { it.uniqueDeviceID() } returns "device"
        },
    ) {
        override suspend fun doSync(syncData: SyncData): Backup? = syncData.backup

        fun merge(local: SyncData, remote: SyncData): SyncData {
            return mergeSyncData(local, remote)
        }
    }
}
