package eu.kanade.tachiyomi.animeextension

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animeextension.api.AnimeExtensionApi
import eu.kanade.tachiyomi.animeextension.api.AnimeExtensionUpdateNotifier
import eu.kanade.tachiyomi.animeextension.model.AnimeExtension
import eu.kanade.tachiyomi.animeextension.model.AnimeLoadResult
import eu.kanade.tachiyomi.animeextension.util.AnimeExtensionInstallReceiver
import eu.kanade.tachiyomi.animeextension.util.AnimeExtensionInstaller
import eu.kanade.tachiyomi.animeextension.util.AnimeExtensionLoader
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.ui.youtube.YouTubeExtension
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.anime.model.StubAnimeSource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeExtensionManager(
    private val context: Context,
    private val preferences: SourcePreferences = Injekt.get(),
    private val trustExtension: TrustExtension = Injekt.get(),
) {

    val scope = CoroutineScope(SupervisorJob())

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val api = AnimeExtensionApi()

    private val installer by lazy { AnimeExtensionInstaller(context) }

    private val iconMap = mutableMapOf<String, Drawable>()
    private val builtInYouTubeExtension by lazy {
        YouTubeExtension.copy(icon = ContextCompat.getDrawable(context, R.drawable.ic_youtube))
    }

    private val installedExtensionMapFlow = MutableStateFlow(emptyMap<String, AnimeExtension.Installed>())
    val installedExtensionsFlow = installedExtensionMapFlow
        .map { (it + builtInYouTubeExtension).values.toList() }
        .stateIn(scope, SharingStarted.Lazily, (installedExtensionMapFlow.value + builtInYouTubeExtension).values.toList())

    private val availableExtensionMapFlow = MutableStateFlow(emptyMap<String, AnimeExtension.Available>())
    val availableExtensionsFlow = availableExtensionMapFlow.map { it.values.toList() }
        .stateIn(scope, SharingStarted.Lazily, availableExtensionMapFlow.value.values.toList())

    private val untrustedExtensionMapFlow = MutableStateFlow(emptyMap<String, AnimeExtension.Untrusted>())
    val untrustedExtensionsFlow = untrustedExtensionMapFlow.mapExtensions(scope)

    init {
        initExtensions()
        AnimeExtensionInstallReceiver(InstallationListener()).register(context)
    }

    fun getExtensionPackageAsFlow(sourceId: Long): Flow<String?> {
        return installedExtensionsFlow.map { extensions ->
            extensions.find { extension ->
                extension.sources.any { it.id == sourceId }
            }
                ?.pkgName
        }
    }

    fun getExtensionPackage(sourceId: Long): String? {
        return (installedExtensionMapFlow.value + builtInYouTubeExtension).values.find { extension ->
            extension.sources.any { it.id == sourceId }
        }
            ?.pkgName
    }

    fun getAppIconForSource(sourceId: Long): Drawable? {
        val pkgName = getExtensionPackage(sourceId) ?: return null
        if (pkgName == YouTubeExtension.pkgName) return ContextCompat.getDrawable(context, R.drawable.ic_youtube)
        return iconMap[pkgName] ?: iconMap.getOrPut(pkgName) {
            AnimeExtensionLoader.getExtensionPackageInfoFromPkgName(context, pkgName)!!.applicationInfo!!
                .loadIcon(context.packageManager)
        }
    }

    private var availableExtensionsSourcesData: Map<Long, StubAnimeSource> = emptyMap()

    private fun setupAvailableExtensionsSourcesDataMap(extensions: List<AnimeExtension.Available>) {
        if (extensions.isEmpty()) return
        availableExtensionsSourcesData = extensions
            .flatMap { ext -> ext.sources.map { it.toStubAnimeSource() } }
            .associateBy { it.id }
    }

    fun getSourceData(id: Long) = availableExtensionsSourcesData[id]

    private fun initExtensions() {
        val extensions = AnimeExtensionLoader.loadExtensions(context)

        installedExtensionMapFlow.value = extensions
            .filterIsInstance<AnimeLoadResult.Success>()
            .associate { it.extension.pkgName to it.extension }

        untrustedExtensionMapFlow.value = extensions
            .filterIsInstance<AnimeLoadResult.Untrusted>()
            .associate { it.extension.pkgName to it.extension }

        _isInitialized.value = true
    }

    suspend fun findAvailableExtensions() {
        val extensions: List<AnimeExtension.Available> = try {
            api.findExtensions()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            withUIContext { context.toast(MR.strings.extension_api_error) }
            return
        }

        availableExtensionMapFlow.value = extensions.associateBy {
            it.pkgName + ":${it.signatureHash}"
        }
        updatedInstalledExtensionsStatuses(extensions)
        setupAvailableExtensionsSourcesDataMap(extensions)
    }

    private fun updatedInstalledExtensionsStatuses(availableExtensions: List<AnimeExtension.Available>) {
        val installedExtensionsMap = installedExtensionMapFlow.value.toMutableMap()
        var changed = false
        for ((pkgName, extension) in installedExtensionsMap) {
            val availableExt = availableExtensions.find {
                it.signatureHash == extension.signatureHash && it.pkgName == pkgName
            }

            if (availableExt == null && (!extension.isObsolete || extension.hasUpdate)) {
                installedExtensionsMap[pkgName] = extension.copy(
                    isObsolete = true,
                    hasUpdate = false,
                )
                changed = true
            } else if (availableExt != null) {
                val hasUpdate = extension.updateExists(availableExt)
                if (hasUpdate != extension.hasUpdate ||
                    availableExt.repoUrl != extension.repoUrl ||
                    extension.isObsolete
                ) {
                    installedExtensionsMap[pkgName] = extension.copy(
                        hasUpdate = hasUpdate,
                        repoUrl = availableExt.repoUrl,
                        isObsolete = false,
                    )
                    changed = true
                }
            }
        }
        if (changed) {
            installedExtensionMapFlow.value = installedExtensionsMap
        }
        updatePendingUpdatesCount()
    }

    fun installExtension(extension: AnimeExtension.Available): Flow<InstallStep> {
        return installer.downloadAndInstall(api.getApkUrl(extension), extension)
    }

    fun updateExtension(extension: AnimeExtension.Installed): Flow<InstallStep> {
        val availableExt = availableExtensionMapFlow.value[
            extension.pkgName + ":${extension.signatureHash}",
        ] ?: return emptyFlow()
        return installExtension(availableExt)
    }

    fun cancelInstallUpdateExtension(extension: AnimeExtension) {
        installer.cancelInstall(extension.pkgName + ":${extension.signatureHash}")
    }

    fun setInstalling(downloadId: Long) {
        installer.updateInstallStep(downloadId, InstallStep.Installing)
    }

    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        installer.updateInstallStep(downloadId, step)
    }

    fun uninstallExtension(extension: AnimeExtension) {
        if (extension.pkgName == YouTubeExtension.pkgName) return
        installer.uninstallApk(extension.pkgName)
    }

    suspend fun trust(extension: AnimeExtension.Untrusted) {
        untrustedExtensionMapFlow.value[extension.pkgName] ?: return

        trustExtension.trust(extension.pkgName, extension.versionCode, extension.signatureHash)

        untrustedExtensionMapFlow.value -= extension.pkgName

        AnimeExtensionLoader.loadExtensionFromPkgName(context, extension.pkgName)
            .let { it as? AnimeLoadResult.Success }
            ?.let { registerNewExtension(it.extension) }
    }

    private fun registerNewExtension(extension: AnimeExtension.Installed) {
        installedExtensionMapFlow.value += extension
    }

    private fun registerUpdatedExtension(extension: AnimeExtension.Installed) {
        installedExtensionMapFlow.value += extension
    }

    private fun unregisterExtension(pkgName: String) {
        installedExtensionMapFlow.value -= pkgName
        untrustedExtensionMapFlow.value -= pkgName
    }

    private inner class InstallationListener : AnimeExtensionInstallReceiver.Listener {

        override fun onExtensionInstalled(extension: AnimeExtension.Installed) {
            registerNewExtension(extension.withUpdateCheck())
            updatePendingUpdatesCount()
        }

        override fun onExtensionUpdated(extension: AnimeExtension.Installed) {
            registerUpdatedExtension(extension.withUpdateCheck())
            updatePendingUpdatesCount()
        }

        override fun onExtensionUntrusted(extension: AnimeExtension.Untrusted) {
            installedExtensionMapFlow.value -= extension.pkgName
            untrustedExtensionMapFlow.value += extension
            updatePendingUpdatesCount()
        }

        override fun onPackageUninstalled(pkgName: String) {
            AnimeExtensionLoader.uninstallPrivateExtension(context, pkgName)
            unregisterExtension(pkgName)
            updatePendingUpdatesCount()
        }
    }

    private fun AnimeExtension.Installed.withUpdateCheck(): AnimeExtension.Installed {
        return if (updateExists()) {
            copy(hasUpdate = true)
        } else {
            this
        }
    }

    private fun AnimeExtension.Installed.updateExists(availableExtension: AnimeExtension.Available? = null): Boolean {
        val availableExt = availableExtension
            ?: availableExtensionMapFlow.value[pkgName + ":${signatureHash}"]
            ?: return false

        return (availableExt.versionCode > versionCode || availableExt.libVersion > libVersion)
    }

    private fun updatePendingUpdatesCount() {
        val pendingUpdateCount = installedExtensionMapFlow.value.values.count { it.hasUpdate }
        preferences.animeExtensionUpdatesCount().set(pendingUpdateCount)
        if (pendingUpdateCount == 0) {
            AnimeExtensionUpdateNotifier(context).dismiss()
        }
    }

    private operator fun <T : AnimeExtension> Map<String, T>.plus(extension: T) = plus(extension.pkgName to extension)

    private fun <T : AnimeExtension> StateFlow<Map<String, T>>.mapExtensions(scope: CoroutineScope): StateFlow<List<T>> {
        return map { it.values.toList() }.stateIn(scope, SharingStarted.Lazily, value.values.toList())
    }
}
