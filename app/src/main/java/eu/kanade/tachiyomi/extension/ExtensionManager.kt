package eu.kanade.tachiyomi.extension

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import chimahon.novel.model.NovelServer
import chimahon.novel.model.NovelServerStorage
import chimahon.novel.model.NovelServerType
import chimahon.source.kavita.KavitaSource
import chimahon.source.komga.KomgaSource
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.extension.api.ExtensionApi
import eu.kanade.tachiyomi.extension.api.ExtensionUpdateNotifier
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionInstallReceiver
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.util.system.toast
import exh.log.xLogD
import exh.source.BlacklistedSources
import exh.source.EHENTAI_EXT_SOURCES
import exh.source.EXHENTAI_EXT_SOURCES
import exh.source.ExhPreferences
import exh.source.MERGED_SOURCE_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

/**
 * The manager of extensions installed as another apk which extend the available sources. It handles
 * the retrieval of remotely available extensions as well as installing, updating and removing them.
 * To avoid malicious distribution, every extension must be signed and it will only be loaded if its
 * signature is trusted, otherwise the user will be prompted with a warning to trust it before being
 * loaded.
 */
class ExtensionManager(
    private val context: Context,
    private val preferences: SourcePreferences = Injekt.get(),
    private val trustExtension: TrustExtension = Injekt.get(),
    private val serverStorage: NovelServerStorage = Injekt.get(),
) {

    val scope = CoroutineScope(SupervisorJob())

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /**
     * API where all the available extensions can be found.
     */
    private val api = ExtensionApi()

    /**
     * The installer which installs, updates and uninstalls the extensions.
     */
    private val installer by lazy { ExtensionInstaller(context) }

    private val iconMap = mutableMapOf<String, Drawable>()

    private val installedExtensionMapFlow = MutableStateFlow(emptyMap<String, Extension.Installed>())
    val installedExtensionsFlow = installedExtensionMapFlow.mapExtensions(scope)
    val installedMangaExtensionsFlow = installedExtensionsFlow
        .map { extensions -> extensions.filter { it.contentType == Extension.ContentType.MANGA } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Lazily, emptyList())
    val installedNovelExtensionsFlow = installedExtensionsFlow
        .map { extensions -> extensions.filter { it.contentType == Extension.ContentType.NOVEL } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    private val availableExtensionMapFlow = MutableStateFlow(emptyMap<String, Extension.Available>())

    // SY -->
    val availableExtensionsFlow = availableExtensionMapFlow.map { it.filterNotBlacklisted().values.toList() }
        .stateIn(scope, SharingStarted.Lazily, availableExtensionMapFlow.value.values.toList())
    val availableNovelExtensionsFlow = availableExtensionsFlow
        .map { extensions -> extensions.filter { it.contentType == Extension.ContentType.NOVEL } }
        .stateIn(scope, SharingStarted.Lazily, emptyList())
    // SY <--

    private val untrustedExtensionMapFlow = MutableStateFlow(emptyMap<String, Extension.Untrusted>())
    val untrustedExtensionsFlow = untrustedExtensionMapFlow.mapExtensions(scope)
    val untrustedNovelExtensionsFlow = untrustedExtensionsFlow
        .map { extensions -> extensions.filter { it.contentType == Extension.ContentType.NOVEL } }
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    init {
        initExtensions()
        initBuiltInServerExtensions()
        ExtensionInstallReceiver(InstallationListener()).register(context)
    }

    private var subLanguagesEnabledOnFirstRun = preferences.enabledLanguages().isSet()

    fun getExtensionPackage(sourceId: Long): String? {
        return installedExtensionsFlow.value.find { extension ->
            extension.sources.any { it.id == sourceId }
        }
            ?.pkgName
    }

    fun getExtensionPackageAsFlow(sourceId: Long): Flow<String?> {
        return installedExtensionsFlow.map { extensions ->
            extensions.find { extension ->
                extension.sources.any { it.id == sourceId }
            }
                ?.pkgName
        }
    }

    fun getAppIconForSource(sourceId: Long): Drawable? {
        val extension = installedExtensionsFlow.value.find { extension ->
            extension.sources.any { it.id == sourceId } ||
                extension.novelSources.any { it.id == sourceId }
        }
        extension?.icon?.let { return it }

        val pkgName = extension?.pkgName
        if (pkgName != null) {
            val packageInfo = ExtensionLoader.getExtensionPackageInfoFromPkgName(context, pkgName)
                ?: return null
            return iconMap[pkgName] ?: iconMap.getOrPut(pkgName) {
                packageInfo.applicationInfo!!
                    .loadIcon(context.packageManager)
            }
        }

        // SY -->
        return when (sourceId) {
            // KMK -->
            in EHENTAI_EXT_SOURCES -> ContextCompat.getDrawable(context, R.mipmap.ic_ehentai_source)
            in EXHENTAI_EXT_SOURCES -> ContextCompat.getDrawable(context, R.mipmap.ic_exhentai_source)
            // KMK <--
            MERGED_SOURCE_ID -> ContextCompat.getDrawable(context, R.mipmap.ic_merged_source)
            else -> null
        }
        // SY <--
    }

    private var availableExtensionsSourcesData: Map<Long, StubSource> = emptyMap()

    private fun setupAvailableExtensionsSourcesDataMap(extensions: List<Extension.Available>) {
        if (extensions.isEmpty()) return
        availableExtensionsSourcesData = extensions
            .flatMap { ext -> ext.sources.map { it.toStubSource() } }
            .associateBy { it.id }
    }

    fun getSourceData(id: Long) = availableExtensionsSourcesData[id]

    /**
     * Loads and registers the installed extensions.
     */
    private fun initExtensions() {
        val extensions = ExtensionLoader.loadExtensions(context)

        installedExtensionMapFlow.value = extensions
            .filterIsInstance<LoadResult.Success>()
            .associate { it.extension.pkgName to it.extension }

        untrustedExtensionMapFlow.value = extensions
            .filterIsInstance<LoadResult.Untrusted>()
            .associate { it.extension.pkgName to it.extension }
            // SY -->
            .filterNotBlacklisted()
        // SY <--

        _isInitialized.value = true
    }

    private fun initBuiltInServerExtensions() {
        scope.launch {
            serverStorage.getAllServers()
                .map { servers ->
                    servers
                        .filter { it.enabled }
                        .mapNotNull { it.toBuiltInExtension() }
                        .associateBy { it.pkgName }
                }
                .collectLatest { builtInExtensions ->
                    installedExtensionMapFlow.update { installedExtensions ->
                        installedExtensions
                            .filterKeys { !it.isBuiltInExtensionPackage() } + builtInExtensions
                    }
                    updatePendingUpdatesCount()
                }
        }
    }

    private fun NovelServer.toBuiltInExtension(): Extension.Installed? {
        val (source, typeName, iconRes) = when (type) {
            NovelServerType.KOMGA -> Triple(KomgaSource(this), "Komga", R.drawable.brand_komga)
            NovelServerType.KAVITA -> Triple(KavitaSource(this), "Kavita", R.drawable.brand_kavita)
            NovelServerType.OPDS -> return null
        }

        return Extension.Installed(
            name = "$typeName: $name",
            pkgName = builtInPackageName(type, id),
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            libVersion = ExtensionLoader.LIB_VERSION_MAX,
            lang = source.lang,
            isNsfw = false,
            signatureHash = "$BUILT_IN_SIGNATURE_HASH:${type.name.lowercase(Locale.ROOT)}",
            storeName = BUILT_IN_REPO_NAME,
            pkgFactory = null,
            sources = listOf(source),
            icon = ContextCompat.getDrawable(context, iconRes),
            hasUpdate = false,
            isObsolete = false,
            isShared = false,
            isBuiltIn = true,
            isRedundant = false,
        )
    }

    // EXH -->
    private fun <T : Extension> Map<String, T>.filterNotBlacklisted(): Map<String, T> {
        val blacklistEnabled = preferences.enableSourceBlacklist().get()
        return filterNot { (_, extension) ->
            extension.isBlacklisted(blacklistEnabled)
                .also {
                    if (it) this@ExtensionManager.xLogD("Removing blacklisted extension: (name: %s, pkgName: %s)!", extension.name, extension.pkgName)
                }
        }
    }

    private fun Extension.isBlacklisted(
        blacklistEnabled: Boolean = preferences.enableSourceBlacklist().get(),
        // KMK -->
        isHentaiEnabled: Boolean = Injekt.get<ExhPreferences>().isHentaiEnabled().get(),
        // KMK <--
    ): Boolean {
        return pkgName in BlacklistedSources.BLACKLISTED_EXTENSIONS &&
            blacklistEnabled &&
            // KMK -->
            isHentaiEnabled
        // KMK <--
    }
    // EXH <--

    /**
     * Finds the available extensions in the [api] and updates [availableExtensionMapFlow].
     */
    suspend fun findAvailableExtensions() {
        val extensions: List<Extension.Available> = try {
            api.findExtensions()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            withUIContext { context.toast(MR.strings.extension_api_error) }
            return
        }

        enableAdditionalSubLanguages(extensions)

        availableExtensionMapFlow.value = extensions.associateBy {
            it.pkgName +
                // KMK -->
                "_${it.signatureHash}"
            // KMK <--
        }
        updatedInstalledExtensionsStatuses(extensions)
        setupAvailableExtensionsSourcesDataMap(extensions)
    }

    /**
     * Enables the additional sub-languages in the app first run. This addresses
     * the issue where users still need to enable some specific languages even when
     * the device language is inside that major group. As an example, if a user
     * has a zh device language, the app will also enable zh-Hans and zh-Hant.
     *
     * If the user have already changed the enabledLanguages preference value once,
     * the new languages will not be added to respect the user enabled choices.
     */
    private fun enableAdditionalSubLanguages(extensions: List<Extension.Available>) {
        if (subLanguagesEnabledOnFirstRun || extensions.isEmpty()) {
            return
        }

        // Use the source lang as some aren't present on the extension level.
        val availableLanguages = extensions
            .flatMap(Extension.Available::sources)
            .distinctBy(Extension.Available.Source::lang)
            .map(Extension.Available.Source::lang)

        val deviceLanguage = Locale.getDefault().language
        val defaultLanguages = preferences.enabledLanguages().defaultValue()
        val languagesToEnable = availableLanguages.filter {
            it != deviceLanguage && it.startsWith(deviceLanguage)
        }

        preferences.enabledLanguages().set(defaultLanguages + languagesToEnable)
        subLanguagesEnabledOnFirstRun = true
    }

    /**
     * Sets the update field of the installed extensions with the given [availableExtensions].
     *
     * @param availableExtensions The list of extensions given by the [api].
     */
    private fun updatedInstalledExtensionsStatuses(availableExtensions: List<Extension.Available>) {
        val installedExtensionsMap = installedExtensionMapFlow.value.toMutableMap()
        var changed = false
        for ((pkgName, extension) in installedExtensionsMap) {
            if (extension.isBuiltIn) {
                continue
            }

            val availableExt = availableExtensions.find {
                // KMK -->
                it.signatureHash == extension.signatureHash &&
                    // KMK <--
                    it.pkgName == pkgName
            }

            if (availableExt == null &&
                (!extension.isObsolete || /* KMK --> */ extension.hasUpdate /* KMK <-- */)
            ) {
                // Ext not found: Set isObsolete & clear hasUpdate
                installedExtensionsMap[pkgName] = extension.copy(
                    isObsolete = true,
                    // KMK -->
                    hasUpdate = false,
                    // KMK <--
                )
                changed = true
                // SY -->
            } else if (extension.isBlacklisted() && !extension.isRedundant) {
                installedExtensionsMap[pkgName] = extension.copy(isRedundant = true)
                changed = true
                // SY <--
            } else if (availableExt != null) {
                // Ext found: Update installed extensions with new information from repo
                // Also clear isObsolete and set new repo Name if needed
                val hasUpdate = extension.updateExists(availableExt)
                // KMK -->
                installedExtensionsMap[pkgName] = extension.copy(
                    hasUpdate = hasUpdate,
                    store = availableExt.store,
                    isObsolete = false,
                    storeName = extension.storeName ?: availableExt.storeName,
                )
                // KMK <--
                changed = true
            }
        }
        if (changed) {
            installedExtensionMapFlow.value = installedExtensionsMap
        }
        updatePendingUpdatesCount()
    }

    /**
     * Returns a flow of the installation process for the given extension. It will complete
     * once the extension is installed or throws an error. The process will be canceled if
     * unsubscribed before its completion.
     *
     * @param extension The extension to be installed.
     */
    fun installExtension(extension: Extension.Available): Flow<InstallStep> {
        return installer.downloadAndInstall(extension.apkUrl, extension)
    }

    /**
     * Returns a flow of the installation process for the given extension. It will complete
     * once the extension is updated or throws an error. The process will be canceled if
     * unsubscribed before its completion.
     *
     * @param extension The extension to be updated.
     */
    fun updateExtension(extension: Extension.Installed): Flow<InstallStep> {
        if (extension.isBuiltIn) {
            return emptyFlow()
        }

        val availableExt = availableExtensionMapFlow.value[
            extension.pkgName +
                // KMK -->
                "_${extension.signatureHash}",
            // KMK <--
        ] ?: return emptyFlow()
        return installExtension(availableExt)
    }

    fun cancelInstallUpdateExtension(extension: Extension) {
        if (extension is Extension.Installed && extension.isBuiltIn) {
            return
        }

        installer.cancelInstall(
            extension.pkgName +
                // KMK -->
                "_${extension.signatureHash}",
            // KMK <--
        )
    }

    /**
     * Sets to "installing" status of an extension installation.
     *
     * @param downloadId The id of the download.
     */
    fun setInstalling(downloadId: Long) {
        installer.updateInstallStep(downloadId, InstallStep.Installing)
    }

    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        installer.updateInstallStep(downloadId, step)
    }

    /**
     * Uninstalls the extension that matches the given package name.
     *
     * @param extension The extension to uninstall.
     */
    fun uninstallExtension(extension: Extension) {
        if (extension is Extension.Installed && extension.isBuiltIn) {
            return
        }

        installer.uninstallApk(extension.pkgName)
    }

    /**
     * Adds the given extension to the list of trusted extensions. It also loads in background the
     * now trusted extensions.
     *
     * @param extension the extension to trust
     */
    suspend fun trust(extension: Extension.Untrusted) {
        untrustedExtensionMapFlow.value[extension.pkgName] ?: return

        trustExtension.trust(extension.pkgName, extension.versionCode, extension.signatureHash)

        untrustedExtensionMapFlow.value -= extension.pkgName

        ExtensionLoader.loadExtensionFromPkgName(context, extension.pkgName)
            .let { it as? LoadResult.Success }
            ?.let { registerNewExtension(it.extension) }
    }

    /**
     * Registers the given extension in this and the source managers.
     *
     * @param extension The extension to be registered.
     */
    private fun registerNewExtension(extension: Extension.Installed) {
        // SY -->
        if (extension.isBlacklisted()) {
            xLogD("Removing blacklisted extension: (name: String, pkgName: %s)!", extension.name, extension.pkgName)
            return
        }
        // SY <--

        installedExtensionMapFlow.value += extension
    }

    /**
     * Registers the given updated extension in this and the source managers previously removing
     * the outdated ones.
     *
     * @param extension The extension to be registered.
     */
    private fun registerUpdatedExtension(extension: Extension.Installed) {
        // SY -->
        if (extension.isBlacklisted()) {
            xLogD("Removing blacklisted extension: (name: %s, pkgName: %s)!", extension.name, extension.pkgName)
            return
        }
        // SY <--

        installedExtensionMapFlow.value += extension
    }

    /**
     * Unregisters the extension in this and the source managers given its package name. Note this
     * method is called for every uninstalled application in the system.
     *
     * @param pkgName The package name of the uninstalled application.
     */
    private fun unregisterExtension(pkgName: String) {
        installedExtensionMapFlow.value -= pkgName
        untrustedExtensionMapFlow.value -= pkgName
    }

    /**
     * Listener which receives events of the extensions being installed, updated or removed.
     */
    private inner class InstallationListener : ExtensionInstallReceiver.Listener {

        override fun onExtensionInstalled(extension: Extension.Installed) {
            registerNewExtension(extension.withUpdateCheck())
            updatePendingUpdatesCount()
        }

        override fun onExtensionUpdated(extension: Extension.Installed) {
            registerUpdatedExtension(extension.withUpdateCheck())
            updatePendingUpdatesCount()
        }

        override fun onExtensionUntrusted(extension: Extension.Untrusted) {
            installedExtensionMapFlow.value -= extension.pkgName
            untrustedExtensionMapFlow.value += extension
            updatePendingUpdatesCount()
        }

        override fun onPackageUninstalled(pkgName: String) {
            ExtensionLoader.uninstallPrivateExtension(context, pkgName)
            unregisterExtension(pkgName)
            updatePendingUpdatesCount()
        }
    }

    /**
     * Extension method to set the update field of an installed extension.
     */
    private fun Extension.Installed.withUpdateCheck(): Extension.Installed {
        return if (updateExists()) {
            copy(hasUpdate = true)
        } else {
            this
        }
    }

    private fun Extension.Installed.updateExists(availableExtension: Extension.Available? = null): Boolean {
        val availableExt = availableExtension
            ?: availableExtensionMapFlow.value["$pkgName:$signatureHash"]
            ?: return false

        return (availableExt.versionCode > versionCode || availableExt.libVersion > libVersion)
    }

    private fun updatePendingUpdatesCount() {
        val pendingUpdateCount = installedExtensionMapFlow.value.values.count { it.hasUpdate }
        preferences.extensionUpdatesCount().set(pendingUpdateCount)
        if (pendingUpdateCount == 0) {
            ExtensionUpdateNotifier(context).dismiss()
        }
    }

    private operator fun <T : Extension> Map<String, T>.plus(extension: T) = plus(extension.pkgName to extension)

    private fun <T : Extension> StateFlow<Map<String, T>>.mapExtensions(scope: CoroutineScope): StateFlow<List<T>> {
        return map { it.values.toList() }.stateIn(scope, SharingStarted.Lazily, value.values.toList())
    }

    private fun builtInPackageName(type: NovelServerType, serverId: String): String {
        return "$BUILT_IN_PACKAGE_PREFIX.${type.name.lowercase(Locale.ROOT)}.${serverId.toPackagePart()}"
    }

    private fun String.isBuiltInExtensionPackage(): Boolean {
        return startsWith(BUILT_IN_PACKAGE_PREFIX)
    }

    private fun String.toPackagePart(): String {
        val packagePart = lowercase(Locale.ROOT)
            .map { char -> if (char.isLetterOrDigit()) char else '_' }
            .joinToString("")
            .ifBlank { "server" }

        return if (packagePart.first().isLetter() || packagePart.first() == '_') {
            packagePart
        } else {
            "server_$packagePart"
        }
    }

    private companion object {
        const val BUILT_IN_PACKAGE_PREFIX = "app.chimahon.builtin"
        const val BUILT_IN_REPO_NAME = "Built-in"
        const val BUILT_IN_SIGNATURE_HASH = "chimahon-builtin"
    }
}
