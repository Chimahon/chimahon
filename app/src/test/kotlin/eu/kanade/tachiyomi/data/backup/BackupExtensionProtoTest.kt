package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Test

class BackupExtensionProtoTest {

    @Test
    fun `round-trips manga and anime extension identities`() {
        val original = Backup(
            backupMangaExtensions = listOf(
                BackupExtension("com.example.manga.en.foo", "hashFoo"),
                BackupExtension("com.example.manga.ja.bar", "hashBar"),
            ),
            backupAnimeExtensions = listOf(
                BackupExtension("com.example.anime.en.baz", "hashBaz"),
            ),
        )

        val decoded = ProtoBuf.decodeFromByteArray(
            Backup.serializer(),
            ProtoBuf.encodeToByteArray(Backup.serializer(), original),
        )

        decoded.backupMangaExtensions shouldContainExactly original.backupMangaExtensions
        decoded.backupAnimeExtensions shouldContainExactly original.backupAnimeExtensions
    }

    @Test
    fun `existing sync files without extension fields decode with empty histories`() {
        val legacyBytes = ProtoBuf.encodeToByteArray(
            LegacyBackupWithoutExtensions.serializer(),
            LegacyBackupWithoutExtensions(
                backupCategories = listOf(BackupCategory(name = "Reading", order = 0L)),
            ),
        )

        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), legacyBytes)

        decoded.backupCategories.map { it.name } shouldContainExactly listOf("Reading")
        decoded.backupMangaExtensions.shouldBeEmpty()
        decoded.backupAnimeExtensions.shouldBeEmpty()
    }

    @Test
    fun `extension identities are ignored by readers that do not know the fields`() {
        val bytes = ProtoBuf.encodeToByteArray(
            Backup.serializer(),
            Backup(
                backupCategories = listOf(BackupCategory(name = "Reading", order = 0L)),
                backupMangaExtensions = listOf(BackupExtension("pkg", "hash")),
                backupAnimeExtensions = listOf(BackupExtension("anime.pkg", "hash")),
            ),
        )

        val decoded = ProtoBuf.decodeFromByteArray(LegacyBackupWithoutExtensions.serializer(), bytes)

        decoded.backupCategories.map { it.name } shouldContainExactly listOf("Reading")
    }

    /** Mirrors a pre-feature backup that has no extension identity fields (720/721). */
    @Serializable
    private data class LegacyBackupWithoutExtensions(
        @ProtoNumber(2) val backupCategories: List<BackupCategory> = emptyList(),
    )
}
