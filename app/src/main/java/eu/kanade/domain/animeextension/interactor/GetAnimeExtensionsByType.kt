package eu.kanade.domain.animeextension.interactor

import eu.kanade.domain.animeextension.model.AnimeExtensions
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.animeextension.model.AnimeExtension
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import mihon.feature.extension.sync.SyncExtensionCandidate
import mihon.feature.extension.sync.SyncExtensionIdentity
import mihon.feature.extension.sync.matchRememberedExtensions

class GetAnimeExtensionsByType(
    private val preferences: SourcePreferences,
    private val animeExtensionManager: AnimeExtensionManager,
) {

    fun subscribe(): Flow<AnimeExtensions> {
        val showNsfwSources = preferences.showNsfwSource().get()

        return combine(
            preferences.enabledAnimeLanguages().changes(),
            animeExtensionManager.installedExtensionsFlow,
            animeExtensionManager.untrustedExtensionsFlow,
            animeExtensionManager.availableExtensionsFlow,
            // Chimahon -->
            preferences.rememberedAnimeExtensions().changes(),
            // Chimahon <--
        ) { enabledLanguages, _installed, _untrusted, _available, _remembered ->
            val (updates, installed) = _installed
                .filter { (showNsfwSources || !it.isNsfw) }
                .sortedWith(
                    compareBy<AnimeExtension.Installed> { !it.isObsolete }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
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
                        languages = extension.sources.map { it.lang }.toSet()
                            .ifEmpty { setOf(extension.lang) },
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
                        _installed.none { it.pkgName == extension.pkgName } &&
                        _untrusted.none { it.pkgName == extension.pkgName } &&
                        (showNsfwSources || !extension.isNsfw)
                }
                .flatMap { ext ->
                    if (ext.sources.isEmpty()) {
                        return@flatMap if (ext.lang in enabledLanguages) listOf(ext) else emptyList()
                    }
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

            AnimeExtensions(updates, installed, available, untrusted, /* Chimahon */ fromSync)
        }
    }
}
