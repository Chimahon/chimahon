package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

// Chimahon -->
/**
 * A lightweight, backward-compatible identity for a manga or anime extension used by the
 * "Extensions from Sync" feature.
 *
 * Only the package id and signing hash are remembered so devices can recognise an extension
 * they previously had without syncing APKs, download URLs, or repositories. The display name is
 * intentionally omitted: matches are rendered using the current device's catalog name.
 */
@Serializable
data class BackupExtension(
    @ProtoNumber(1) val pkgName: String = "",
    @ProtoNumber(2) val signatureHash: String = "",
)
// Chimahon <--
