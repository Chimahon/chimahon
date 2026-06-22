package eu.kanade.tachiyomi.ui.browse.novelextension

import android.app.Application
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.components.SEARCH_DEBOUNCE_MILLIS
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionUiModel
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

class NovelExtensionsScreenModel(
    preferences: SourcePreferences = Injekt.get(),
    basePreferences: BasePreferences = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
) : StateScreenModel<ExtensionsScreenModel.State>(ExtensionsScreenModel.State()) {

    private val currentDownloads = MutableStateFlow<Map<String, InstallStep>>(hashMapOf())
    private val showNsfwSources = preferences.showNsfwSource().get()

    init {
        val context = Injekt.get<Application>()
        val extensionMapper: (Map<String, InstallStep>) -> ((Extension) -> ExtensionUiModel.Item) = { map ->
            {
                ExtensionUiModel.Item(
                    it,
                    map[it.pkgName + ":${it.signatureHash}"] ?: InstallStep.Idle,
                )
            }
        }

        screenModelScope.launchIO {
            combine(
                state.map { it.searchQuery }
                    .distinctUntilChanged()
                    .debounce(SEARCH_DEBOUNCE_MILLIS)
                    .map { searchQueryPredicate(it ?: "") },
                currentDownloads,
                combine(
                    preferences.enabledLanguages().changes(),
                    extensionManager.installedNovelExtensionsFlow,
                    extensionManager.untrustedNovelExtensionsFlow,
                    extensionManager.availableNovelExtensionsFlow,
                ) { enabledLanguages, _installed, _untrusted, _available ->
                    val (updates, installed) = _installed
                        .filter { showNsfwSources || !it.isNsfw }
                        .sortedWith(
                            compareBy<Extension.Installed> { !it.isObsolete }
                                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                        )
                        .partition { it.hasUpdate }

                    val untrusted = _untrusted
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

                    val available = _available
                        .filter { extension ->
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

                    Triple(updates, installed, untrusted) to available
                },
            ) { predicate, downloads, (grouped, available) ->
                val (updates, installed, untrusted) = grouped
                buildMap {
                    val updateItems = updates.filter(predicate).map(extensionMapper(downloads))
                    if (updateItems.isNotEmpty()) {
                        put(ExtensionUiModel.Header.Resource(MR.strings.ext_updates_pending), updateItems)
                    }

                    val installedItems = installed.filter(predicate).map(extensionMapper(downloads))
                    val untrustedItems = untrusted.filter(predicate).map(extensionMapper(downloads))
                    if (installedItems.isNotEmpty() || untrustedItems.isNotEmpty()) {
                        put(ExtensionUiModel.Header.Resource(MR.strings.ext_installed), installedItems + untrustedItems)
                    }

                    val availableByLang = available
                        .filter(predicate)
                        .groupBy { it.lang }
                        .toSortedMap(LocaleHelper.comparator)
                        .map { (lang, exts) ->
                            ExtensionUiModel.Header.Text(LocaleHelper.getSourceDisplayName(lang, context)) to
                                exts.map(extensionMapper(downloads))
                        }
                    if (availableByLang.isNotEmpty()) {
                        putAll(availableByLang)
                    }
                }
            }
                .collectLatest { items ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            items = items,
                        )
                    }
                }
        }

        screenModelScope.launchIO { findAvailableExtensions() }

        preferences.extensionUpdatesCount().changes()
            .onEach { mutableState.update { state -> state.copy(updates = it) } }
            .launchIn(screenModelScope)

        basePreferences.extensionInstaller().changes()
            .onEach { mutableState.update { state -> state.copy(installer = it) } }
            .launchIn(screenModelScope)
    }

    private fun searchQueryPredicate(query: String): (Extension) -> Boolean {
        val subqueries = query.split(",")
            .map { it.trim() }
            .filterNot { it.isBlank() }

        if (subqueries.isEmpty()) return { true }

        return { extension ->
            subqueries.any { subquery ->
                if (extension.name.contains(subquery, ignoreCase = true)) return@any true

                when (extension) {
                    is Extension.Installed -> extension.sources.any { source ->
                        source.name.contains(subquery, ignoreCase = true) ||
                            source.id == subquery.toLongOrNull()
                    }

                    is Extension.Available -> extension.sources.any {
                        it.name.contains(subquery, ignoreCase = true) ||
                            it.id == subquery.toLongOrNull()
                    }

                    else -> false
                }
            }
        }
    }

    fun search(query: String?) {
        mutableState.update {
            it.copy(searchQuery = query)
        }
    }

    fun updateAllExtensions() {
        screenModelScope.launchIO {
            state.value.items.values.flatten()
                .map { it.extension }
                .filterIsInstance<Extension.Installed>()
                .filter { it.hasUpdate }
                .forEach(::updateExtension)
        }
    }

    fun installExtension(extension: Extension.Available) {
        screenModelScope.launchIO {
            extensionManager.installExtension(extension).collectToInstallUpdate(extension)
        }
    }

    fun updateExtension(extension: Extension.Installed) {
        screenModelScope.launchIO {
            extensionManager.updateExtension(extension).collectToInstallUpdate(extension)
        }
    }

    fun cancelInstallUpdateExtension(extension: Extension) {
        extensionManager.cancelInstallUpdateExtension(extension)
        removeDownloadState(extension)
    }

    private fun addDownloadState(extension: Extension, installStep: InstallStep) {
        currentDownloads.update {
            it + Pair(extension.pkgName + ":${extension.signatureHash}", installStep)
        }
    }

    private fun removeDownloadState(extension: Extension) {
        currentDownloads.update {
            it - (extension.pkgName + ":${extension.signatureHash}")
        }
    }

    private suspend fun Flow<InstallStep>.collectToInstallUpdate(extension: Extension) =
        this
            .onEach { installStep -> addDownloadState(extension, installStep) }
            .onCompletion { removeDownloadState(extension) }
            .collect()

    fun uninstallExtension(extension: Extension) {
        extensionManager.uninstallExtension(extension)
    }

    fun findAvailableExtensions() {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true) }
            extensionManager.findAvailableExtensions()
            delay(1.seconds)
            mutableState.update { it.copy(isRefreshing = false) }
        }
    }

    fun trustExtension(extension: Extension.Untrusted) {
        screenModelScope.launch {
            extensionManager.trust(extension)
        }
    }
}
