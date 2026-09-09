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
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionUiModel
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
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
    private val preferences: SourcePreferences = Injekt.get(),
    basePreferences: BasePreferences = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val pluginManager: chimahon.novel.plugin.NovelPluginManager = Injekt.get(),
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
                    preferences.enabledNovelLanguages().changes(),
                    extensionManager.installedNovelExtensionsFlow,
                    extensionManager.untrustedNovelExtensionsFlow,
                    extensionManager.availableNovelExtensionsFlow,
                    pluginManager.catalog,
                ) { enabledLanguages, _installedApk, _untrustedApk, _availableApk, catalog ->
                    // Map JS plugins to Extension.Available / Installed so they appear alongside APK extensions
                    val jsAvailable = catalog.available.map { desc ->
                        Extension.Available(
                            name = desc.name,
                            pkgName = "js.${desc.id}",
                            versionName = desc.version,
                            versionCode = 1,
                            libVersion = 1.0,
                            lang = desc.normalizedLanguage(),
                            isNsfw = false,
                            sources = emptyList(),
                            apkUrl = desc.codeUrl,
                            iconUrl = desc.iconUrl,
                            signatureHash = "js",
                            storeName = "LNReader",
                            contentType = Extension.ContentType.NOVEL,
                        )
                    }
                    val jsInstalled = catalog.installed.map { inst ->
                        Extension.Installed(
                            name = inst.descriptor.name,
                            pkgName = "js.${inst.descriptor.id}",
                            versionName = inst.descriptor.version,
                            versionCode = 1,
                            libVersion = 1.0,
                            lang = inst.descriptor.normalizedLanguage(),
                            isNsfw = false,
                            sources = emptyList(),
                            pkgFactory = null,
                            icon = null,
                            hasUpdate = pluginManager.hasUpdate(inst.descriptor.id),
                            isObsolete = false,
                            isShared = false,
                            store = null,
                            isRedundant = false,
                            novelSources = emptyList(),
                            contentType = Extension.ContentType.NOVEL,
                            signatureHash = "js",
                            storeName = "LNReader",
                            iconUrl = inst.descriptor.iconUrl,
                        )
                    }
                    // Merge APK + JS, deduplicate by pkgName
                    val available = (_availableApk + jsAvailable)
                        .filter { extension ->
                            (_installedApk + jsInstalled).none { it.pkgName == extension.pkgName } &&
                                _untrustedApk.none { it.pkgName == extension.pkgName } &&
                                (showNsfwSources || !extension.isNsfw) &&
                                (extension.lang == "all" || extension.lang in enabledLanguages || "all" in enabledLanguages || enabledLanguages.isEmpty())
                        }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

                    val installedCombined = (_installedApk + jsInstalled)
                        .filter { showNsfwSources || !it.isNsfw }
                        .sortedWith(
                            compareBy<Extension.Installed> { !it.isObsolete }
                                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
                        )

                    val (updates, installed) = installedCombined.partition { it.hasUpdate }
                    val untrusted = _untrustedApk
                        .filter { showNsfwSources || !it.isNsfw }
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
                    // Show empty hint that points to novel stores if nothing available
                    if (available.isEmpty() && installed.isEmpty() && untrusted.isEmpty() && updates.isEmpty()) {
                        // leave items empty -> ExtensionScreen will show empty; NovelExtensionsTab intercepts to show novel empty
                    }
                }
            }
                .collect { items ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            items = items,
                        )
                    }
                }
        }

        screenModelScope.launchIO { findAvailableExtensions() }

        preferences.novelExtensionUpdatesCount().changes()
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
                    } || extension.novelSources.any { it.name.contains(subquery, ignoreCase = true) }

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
        if (extension.pkgName.startsWith("js.")) {
            val id = extension.pkgName.removePrefix("js.")
            screenModelScope.launchIO {
                addDownloadState(extension, InstallStep.Downloading)
                try {
                    addDownloadState(extension, InstallStep.Installing)
                    pluginManager.install(id)
                    addDownloadState(extension, InstallStep.Installed)
                } catch (_: Exception) {
                    addDownloadState(extension, InstallStep.Error)
                } finally {
                    kotlinx.coroutines.delay(400)
                    removeDownloadState(extension)
                }
            }
            return
        }
        screenModelScope.launchIO {
            extensionManager.installExtension(extension).collectToInstallUpdate(extension)
        }
    }

    fun updateExtension(extension: Extension.Installed) {
        if (extension.pkgName.startsWith("js.")) {
            val id = extension.pkgName.removePrefix("js.")
            screenModelScope.launchIO {
                addDownloadState(extension, InstallStep.Downloading)
                try {
                    addDownloadState(extension, InstallStep.Installing)
                    pluginManager.update(id)
                    addDownloadState(extension, InstallStep.Installed)
                } catch (_: Exception) {
                    addDownloadState(extension, InstallStep.Error)
                } finally {
                    kotlinx.coroutines.delay(400)
                    removeDownloadState(extension)
                }
            }
            return
        }
        screenModelScope.launchIO {
            extensionManager.updateExtension(extension).collectToInstallUpdate(extension)
        }
    }

    fun cancelInstallUpdateExtension(extension: Extension) {
        if (extension.pkgName.startsWith("js.")) return
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
        if (extension.pkgName.startsWith("js.")) {
            val id = extension.pkgName.removePrefix("js.")
            screenModelScope.launchIO {
                try { pluginManager.uninstall(id) } catch (_: Exception) {}
            }
            return
        }
        extensionManager.uninstallExtension(extension)
    }

    fun findAvailableExtensions() {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true) }
            try { extensionManager.findAvailableExtensions() } catch (_: Exception) {}
            try { pluginManager.refresh() } catch (_: Exception) {}
            try {
                val updates = pluginManager.catalog.value.installed.count { pluginManager.hasUpdate(it.descriptor.id) }
                preferences.novelExtensionUpdatesCount().set(updates)
            } catch (_: Exception) {}
            delay(1.seconds)
            mutableState.update { it.copy(isRefreshing = false) }
        }
    }

    fun trustExtension(extension: Extension.Untrusted) {
        screenModelScope.launch {
            extensionManager.trust(extension)
        }
    }

    /** Site URL for a JS plugin row (`js.<id>`), backing the WebView action. */
    fun getJsPluginSite(pkgName: String): String? {
        val id = pkgName.removePrefix("js.")
        val catalog = pluginManager.catalog.value
        return (catalog.installed.find { it.descriptor.id == id }?.descriptor
            ?: catalog.available.find { it.id == id })
            ?.site?.takeIf { it.isNotBlank() }
    }
}
