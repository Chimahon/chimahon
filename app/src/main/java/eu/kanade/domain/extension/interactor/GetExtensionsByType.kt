package eu.kanade.domain.extension.interactor

import eu.kanade.domain.extension.model.Extensions
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import mihon.feature.extension.sync.SyncExtensionCandidate
import mihon.feature.extension.sync.SyncExtensionIdentity
import mihon.feature.extension.sync.matchRememberedExtensions

class GetExtensionsByType(
    private val preferences: SourcePreferences,
    private val extensionManager: ExtensionManager,
) {

    fun subscribe(): Flow<Extensions> {
        val showNsfwSources = preferences.showNsfwSource().get()

        return combine(
            preferences.enabledLanguages().changes(),
            extensionManager.installedExtensionsFlow,
            extensionManager.untrustedExtensionsFlow,
            extensionManager.availableExtensionsFlow,
            // Chimahon -->
            preferences.rememberedMangaExtensions().changes(),
            // Chimahon <--
        ) { enabledLanguages, _installed, _untrusted, _available, _remembered ->
            val (updates, installed) = _installed
                .filter { (showNsfwSources || !it.isNsfw) }
                .sortedWith(
                    compareBy<Extension.Installed> {
                        !it.isObsolete /* SY --> */ && !it.isRedundant /* SY <-- */
                    }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                )
                .partition { it.hasUpdate }

            val untrusted = _untrusted
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

            // Chimahon -->
            // Packages remembered from sync and installable from this device's catalog.
            val fromSyncPkgNames = matchRememberedExtensions(
                remembered = _remembered.mapNotNullTo(mutableSetOf(), SyncExtensionIdentity::decode),
                candidates = _available.map { extension ->
                    SyncExtensionCandidate(
                        pkgName = extension.pkgName,
                        signatureHash = extension.signatureHash,
                        isNsfw = extension.isNsfw,
                        languages = extension.sources.mapTo(mutableSetOf()) { it.lang },
                    )
                },
                installedPkgNames = _installed.mapTo(mutableSetOf()) { it.pkgName },
                showNsfw = showNsfwSources,
                enabledLanguages = enabledLanguages,
            )
            val fromSync = _available
                .filter { it.pkgName in fromSyncPkgNames }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            // Chimahon <--

            val available = _available
                .filter { extension ->
                    // Chimahon --> matched entries are shown in the "Extensions from Sync" section instead
                    extension.pkgName !in fromSyncPkgNames &&
                        // Chimahon <--
                        _installed.none {
                            // KMK -->
                            it.signatureHash == extension.signatureHash &&
                                // KMK <--
                                it.pkgName == extension.pkgName
                        } &&
                        _untrusted.none {
                            // KMK -->
                            it.signatureHash == extension.signatureHash &&
                                // KMK <--
                                it.pkgName == extension.pkgName
                        } &&
                        (showNsfwSources || !extension.isNsfw)
                }
                .flatMap { ext ->
                    ext.sources.filter { it.lang in enabledLanguages }
                        .map {
                            ext.copy(
                                name = it.name,
                                lang = it.lang,
                                pkgName = "${ext.pkgName}-${it.id}",
                                sources = listOf(it),
                            )
                        }
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

            Extensions(updates, installed, available, untrusted, /* Chimahon */ fromSync)
        }
    }
}
