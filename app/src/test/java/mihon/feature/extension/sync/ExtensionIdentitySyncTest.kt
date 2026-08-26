package mihon.feature.extension.sync

import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExtensionIdentitySyncTest {

    @Test
    fun `identity encodes and decodes round trip`() {
        val identity = SyncExtensionIdentity(pkgName = "com.example.ext.en.foo", signatureHash = "abc123")

        SyncExtensionIdentity.decode(identity.encode()) shouldBe identity
    }

    @Test
    fun `decode rejects malformed values`() {
        SyncExtensionIdentity.decode("no-separator").shouldBeNull()
        SyncExtensionIdentity.decode("|onlyHash").shouldBeNull()
        SyncExtensionIdentity.decode("onlyPkg|").shouldBeNull()
        SyncExtensionIdentity.decode("pkg|hash|extra").shouldBeNull()
    }

    @Test
    fun `mergeExtensionIdentities unions and de-duplicates`() {
        val local = listOf(
            BackupExtension("pkg.a", "hashA"),
            BackupExtension("pkg.b", "hashB"),
        )
        val remote = listOf(
            BackupExtension("pkg.b", "hashB"), // duplicate
            BackupExtension("pkg.c", "hashC"),
        )

        val merged = mergeExtensionIdentities(local, remote)

        merged.map { it.pkgName to it.signatureHash }.shouldContainExactlyInAnyOrder(
            "pkg.a" to "hashA",
            "pkg.b" to "hashB",
            "pkg.c" to "hashC",
        )
    }

    @Test
    fun `mergeExtensionIdentities keeps distinct signature hashes for same package`() {
        val merged = mergeExtensionIdentities(
            listOf(BackupExtension("pkg.a", "hashA")),
            listOf(BackupExtension("pkg.a", "hashB")),
        )

        merged.map { it.pkgName to it.signatureHash }.shouldContainExactlyInAnyOrder(
            "pkg.a" to "hashA",
            "pkg.a" to "hashB",
        )
    }

    @Test
    fun `mergeExtensionIdentities tolerates null inputs (extension-only payloads)`() {
        mergeExtensionIdentities(null, null) shouldBe emptyList()
        mergeExtensionIdentities(listOf(BackupExtension("pkg.a", "hashA")), null)
            .map { it.pkgName }.shouldContainExactly("pkg.a")
    }

    @Test
    fun `combineRememberedWithInstalled unions remembered prefs with installed and drops malformed`() {
        val remembered = setOf(
            SyncExtensionIdentity("pkg.a", "hashA").encode(),
            "malformed-entry",
        )
        val installed = listOf(
            SyncExtensionIdentity("pkg.a", "hashA"), // duplicate of remembered
            SyncExtensionIdentity("pkg.b", "hashB"),
        )

        val combined = combineRememberedWithInstalled(remembered, installed)

        combined.map { it.pkgName to it.signatureHash }.shouldContainExactlyInAnyOrder(
            "pkg.a" to "hashA",
            "pkg.b" to "hashB",
        )
    }

    @Test
    fun `cumulativeExtensionHistory accumulates and never prunes`() {
        val existing = setOf(SyncExtensionIdentity("pkg.a", "hashA").encode())
        val fromBackup = listOf(
            BackupExtension("pkg.a", "hashA"), // already known
            BackupExtension("pkg.b", "hashB"), // new
        )

        val history = cumulativeExtensionHistory(existing, fromBackup)

        history shouldContainExactlyInAnyOrder setOf(
            SyncExtensionIdentity("pkg.a", "hashA").encode(),
            SyncExtensionIdentity("pkg.b", "hashB").encode(),
        )

        // Removing an extension from the payload does not remove it from history.
        cumulativeExtensionHistory(history, emptyList()) shouldContainExactlyInAnyOrder history
    }

    @Test
    fun `matchRememberedExtensions returns exact catalog matches`() {
        val remembered = setOf(SyncExtensionIdentity("pkg.a", "hashA"))
        val candidates = listOf(candidate("pkg.a", "hashA"))

        matchRememberedExtensions(
            remembered = remembered,
            candidates = candidates,
            installedPkgNames = emptySet(),
            showNsfw = true,
            enabledLanguages = setOf("en"),
        ) shouldBe setOf("pkg.a")
    }

    @Test
    fun `matchRememberedExtensions hides unmatched identities and signature mismatches`() {
        val remembered = setOf(SyncExtensionIdentity("pkg.a", "hashA"))

        // Not in the catalog at all -> hidden.
        matchRememberedExtensions(
            remembered = remembered,
            candidates = listOf(candidate("pkg.other", "hashOther")),
            installedPkgNames = emptySet(),
            showNsfw = true,
            enabledLanguages = setOf("en"),
        ) shouldBe emptySet()

        // Same package but a different signing hash -> signature mismatch -> hidden.
        matchRememberedExtensions(
            remembered = remembered,
            candidates = listOf(candidate("pkg.a", "hashDIFFERENT")),
            installedPkgNames = emptySet(),
            showNsfw = true,
            enabledLanguages = setOf("en"),
        ) shouldBe emptySet()
    }

    @Test
    fun `matchRememberedExtensions hides already installed packages`() {
        matchRememberedExtensions(
            remembered = setOf(SyncExtensionIdentity("pkg.a", "hashA")),
            candidates = listOf(candidate("pkg.a", "hashA")),
            installedPkgNames = setOf("pkg.a"),
            showNsfw = true,
            enabledLanguages = setOf("en"),
        ) shouldBe emptySet()
    }

    @Test
    fun `matchRememberedExtensions respects the NSFW content filter`() {
        val remembered = setOf(SyncExtensionIdentity("pkg.a", "hashA"))
        val candidates = listOf(candidate("pkg.a", "hashA", isNsfw = true))

        matchRememberedExtensions(remembered, candidates, emptySet(), showNsfw = false, enabledLanguages = setOf("en"))
            .shouldBe(emptySet())
        matchRememberedExtensions(remembered, candidates, emptySet(), showNsfw = true, enabledLanguages = setOf("en"))
            .shouldBe(setOf("pkg.a"))
    }

    @Test
    fun `matchRememberedExtensions respects the enabled-languages content filter`() {
        val remembered = setOf(SyncExtensionIdentity("pkg.a", "hashA"))
        val candidates = listOf(candidate("pkg.a", "hashA", languages = setOf("ja")))

        // No enabled language matches -> hidden.
        matchRememberedExtensions(remembered, candidates, emptySet(), showNsfw = true, enabledLanguages = setOf("en"))
            .shouldBe(emptySet())
        // An enabled language matches -> shown.
        matchRememberedExtensions(remembered, candidates, emptySet(), showNsfw = true, enabledLanguages = setOf("ja"))
            .shouldBe(setOf("pkg.a"))
    }

    @Test
    fun `matchRememberedExtensions returns nothing when nothing is remembered`() {
        matchRememberedExtensions(
            remembered = emptySet(),
            candidates = listOf(candidate("pkg.a", "hashA")),
            installedPkgNames = emptySet(),
            showNsfw = true,
            enabledLanguages = setOf("en"),
        ) shouldBe emptySet()
    }

    private fun candidate(
        pkgName: String,
        signatureHash: String,
        isNsfw: Boolean = false,
        languages: Set<String> = setOf("en"),
    ) = SyncExtensionCandidate(
        pkgName = pkgName,
        signatureHash = signatureHash,
        isNsfw = isNsfw,
        languages = languages,
    )
}
