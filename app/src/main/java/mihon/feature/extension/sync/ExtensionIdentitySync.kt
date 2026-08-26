package mihon.feature.extension.sync

import eu.kanade.tachiyomi.data.backup.models.BackupExtension

// Chimahon -->
/**
 * Lightweight identity for a manga or anime extension: its package id and signing hash.
 * This is everything the "Extensions from Sync" feature remembers about an extension.
 */
data class SyncExtensionIdentity(
    val pkgName: String,
    val signatureHash: String,
) {
    /** Encode for storage in an app-state [Set] preference. */
    fun encode(): String = "$pkgName$SEPARATOR$signatureHash"

    fun toBackup(): BackupExtension = BackupExtension(pkgName = pkgName, signatureHash = signatureHash)

    companion object {
        private const val SEPARATOR = "|"

        /** Decode a preference entry produced by [encode]. Returns null for malformed values. */
        fun decode(value: String): SyncExtensionIdentity? {
            val separatorIndex = value.indexOf(SEPARATOR)
            if (separatorIndex <= 0 || separatorIndex >= value.length - 1) return null
            val pkgName = value.substring(0, separatorIndex)
            val signatureHash = value.substring(separatorIndex + 1)
            if (SEPARATOR in signatureHash) return null
            return SyncExtensionIdentity(pkgName, signatureHash)
        }

        fun from(backup: BackupExtension): SyncExtensionIdentity =
            SyncExtensionIdentity(pkgName = backup.pkgName, signatureHash = backup.signatureHash)
    }
}

/**
 * A candidate extension available in the current device's catalog, used to match remembered
 * identities against what is actually installable here.
 */
data class SyncExtensionCandidate(
    val pkgName: String,
    val signatureHash: String,
    val isNsfw: Boolean,
    val languages: Set<String>,
)

/**
 * Union and de-duplicate two lists of remembered extension identities. Used while merging the
 * local and remote sync payloads so history accumulates across every device.
 */
fun mergeExtensionIdentities(
    local: List<BackupExtension>?,
    remote: List<BackupExtension>?,
): List<BackupExtension> =
    (local.orEmpty() + remote.orEmpty())
        .distinctBy { it.pkgName to it.signatureHash }

/**
 * Combine the remembered identities (encoded preference set) with the currently installed
 * extensions to build the payload sent during sync. This is the "before syncing" step that keeps
 * history cumulative even for extensions that were only ever installed locally.
 */
fun combineRememberedWithInstalled(
    remembered: Set<String>,
    installed: List<SyncExtensionIdentity>,
): List<BackupExtension> {
    val decoded = remembered.mapNotNull(SyncExtensionIdentity::decode)
    return (decoded + installed)
        .distinct()
        .map(SyncExtensionIdentity::toBackup)
}

/**
 * Fold a backup's identities into the existing remembered set to produce the new cumulative
 * history. History is never pruned, so uninstalling an extension does not forget it.
 */
fun cumulativeExtensionHistory(
    existing: Set<String>,
    backup: List<BackupExtension>,
): Set<String> =
    existing + backup.map { SyncExtensionIdentity.from(it).encode() }

/**
 * Match remembered identities against the device's available catalog, returning the set of package
 * ids that should appear in the "Extensions from Sync" section.
 *
 * A package is shown only when:
 * - its (pkgName, signatureHash) pair is remembered — this hides unmatched identities and
 *   signature mismatches,
 * - it is present in the local catalog (i.e. is one of [candidates]),
 * - it is not already installed,
 * - it passes the NSFW content filter, and
 * - it has at least one source in an enabled language (or declares no languages at all).
 */
fun matchRememberedExtensions(
    remembered: Set<SyncExtensionIdentity>,
    candidates: List<SyncExtensionCandidate>,
    installedPkgNames: Set<String>,
    showNsfw: Boolean,
    enabledLanguages: Set<String>,
): Set<String> {
    if (remembered.isEmpty()) return emptySet()
    return candidates.asSequence()
        .filter { it.pkgName !in installedPkgNames }
        .filter { SyncExtensionIdentity(it.pkgName, it.signatureHash) in remembered }
        .filter { showNsfw || !it.isNsfw }
        .filter { candidate -> candidate.languages.isEmpty() || candidate.languages.any { it in enabledLanguages } }
        .map { it.pkgName }
        .toSet()
}
// Chimahon <--
