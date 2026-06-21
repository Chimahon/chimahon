package eu.kanade.tachiyomi.extension.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.domain.extension.interactor.TrustExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ireader.IReaderExtensionConstants
import eu.kanade.tachiyomi.extension.ireader.IReaderRuntime
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.extension.IReaderNovelSource
import eu.kanade.tachiyomi.util.lang.Hash
import eu.kanade.tachiyomi.util.storage.copyAndSetReadOnlyTo
import eu.kanade.tachiyomi.util.system.ChildFirstPathClassLoader
import ireader.core.source.CatalogSource
import ireader.core.source.Dependencies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.domain.extension.interactor.GetExtensionStores
import mihon.domain.extension.model.ExtensionStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.io.File

/**
 * Class that handles the loading of the extensions. Supports two kinds of extensions:
 *
 * 1. Shared extension: This extension is installed to the system with package
 * installer, so other variants of Tachiyomi and its forks can also use this extension.
 *
 * 2. Private extension: This extension is put inside private data directory of the
 * running app, so this extension can only be used by the running app and not shared
 * with other apps.
 *
 * When both kinds of extensions are installed with a same package name, shared
 * extension will be used unless the version codes are different. In that case the
 * one with higher version code will be used.
 */
internal object ExtensionLoader {

    private val preferences: SourcePreferences by injectLazy()
    private val preferenceStore: PreferenceStore by injectLazy()
    private val trustExtension: TrustExtension by injectLazy()

    private val iReaderRuntimeMutex = Mutex()
    @Volatile
    private var iReaderRuntime: IReaderRuntime? = null

    // KMK -->
    private val getExtensionStores: GetExtensionStores by injectLazy()
    // KMK <--

    private val loadNsfwSource by lazy {
        preferences.showNsfwSource().get()
    }

    private const val EXTENSION_FEATURE = "tachiyomi.extension"
    private const val METADATA_SOURCE_CLASS = "tachiyomi.extension.class"
    private const val METADATA_SOURCE_FACTORY = "tachiyomi.extension.factory"
    private const val METADATA_NSFW = "tachiyomi.extension.nsfw"

    private const val METADATA_NAME = "tachiyomix.name"
    private const val METADATA_EXTENSION_LIB = "tachiyomix.extensionLib"
    private const val METADATA_CONTENT_WARNING = "tachiyomix.contentWarning"

    private val SUPPORTED_LIB_VERSIONS = listOf(1.4, 1.6)

    const val LIB_VERSION_MIN = 1.4
    const val LIB_VERSION_MAX = 1.6

    @Suppress("DEPRECATION")
    private val PACKAGE_FLAGS = PackageManager.GET_CONFIGURATIONS or
        PackageManager.GET_META_DATA or
        PackageManager.GET_SIGNATURES or
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    private const val PRIVATE_EXTENSION_EXTENSION = "ext"

    private fun getPrivateExtensionDir(context: Context) = File(context.filesDir, "exts")

    fun installPrivateExtensionFile(context: Context, file: File): Boolean {
        val extension = context.packageManager.getPackageArchiveInfo(file.absolutePath, PACKAGE_FLAGS)
            ?.takeIf { isPackageAnExtension(it) } ?: return false
        val currentExtension = getExtensionPackageInfoFromPkgName(context, extension.packageName)

        if (currentExtension != null) {
            if (PackageInfoCompat.getLongVersionCode(extension) <
                PackageInfoCompat.getLongVersionCode(currentExtension)
            ) {
                logcat(LogPriority.ERROR) { "Installed extension version is higher. Downgrading is not allowed." }
                return false
            }

            val extensionSignatures = getSignatures(extension)
            if (extensionSignatures.isNullOrEmpty()) {
                logcat(LogPriority.ERROR) { "Extension to be installed is not signed." }
                return false
            }

            if (!extensionSignatures.containsAll(getSignatures(currentExtension)!!)) {
                logcat(LogPriority.ERROR) { "Installed extension signature is not matched." }
                return false
            }
        }

        val target = File(getPrivateExtensionDir(context), "${extension.packageName}.$PRIVATE_EXTENSION_EXTENSION")
        return try {
            target.delete()
            file.copyAndSetReadOnlyTo(target, overwrite = true)
            if (currentExtension != null) {
                ExtensionInstallReceiver.notifyReplaced(context, extension.packageName)
            } else {
                ExtensionInstallReceiver.notifyAdded(context, extension.packageName)
            }
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to copy extension file." }
            target.delete()
            false
        }
    }

    fun uninstallPrivateExtension(context: Context, pkgName: String) {
        File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION").delete()
    }

    /**
     * Return a list of all the available extensions initialized concurrently.
     *
     * @param context The application context.
     */
    fun loadExtensions(context: Context): List<LoadResult> {
        val pkgManager = context.packageManager

        val installedPkgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pkgManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()))
        } else {
            pkgManager.getInstalledPackages(PACKAGE_FLAGS)
        }

        val sharedExtPkgs = installedPkgs
            .asSequence()
            .filter { isPackageAnExtension(it) }
            .map { ExtensionInfo(packageInfo = it, isShared = true) }

        val privateExtPkgs = getPrivateExtensionDir(context)
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension == PRIVATE_EXTENSION_EXTENSION }
            ?.mapNotNull {
                // Just in case, since Android 14+ requires them to be read-only
                if (it.canWrite()) {
                    it.setReadOnly()
                }

                val path = it.absolutePath
                pkgManager.getPackageArchiveInfo(path, PACKAGE_FLAGS)
                    ?.apply { applicationInfo!!.fixBasePaths(path) }
            }
            ?.filter { isPackageAnExtension(it) }
            ?.map { ExtensionInfo(packageInfo = it, isShared = false) }
            ?: emptySequence()

        val extPkgs = (sharedExtPkgs + privateExtPkgs)
            // Remove duplicates. Shared takes priority than private by default
            .distinctBy { it.packageInfo.packageName }
            // Compare version number
            .mapNotNull { sharedPkg ->
                val privatePkg = privateExtPkgs
                    .singleOrNull { it.packageInfo.packageName == sharedPkg.packageInfo.packageName }
                selectExtensionPackage(sharedPkg, privatePkg)
            }
            .toList()

        if (extPkgs.isEmpty()) return emptyList()

        // Load each extension concurrently and wait for completion
        return runBlocking {
            // KMK -->
            val extStores = getExtensionStores.get()
            // KMK <--
            val deferred = extPkgs.map {
                async {
                    loadExtension(
                        context,
                        it,
                        // KMK -->
                        extStores,
                        // KMK <--
                    )
                }
            }
            deferred.awaitAll()
        }
    }

    /**
     * Attempts to load an extension from the given package name. It checks if the extension
     * contains the required feature flag before trying to load it.
     */
    suspend fun loadExtensionFromPkgName(context: Context, pkgName: String): LoadResult {
        val extensionPackage = getExtensionInfoFromPkgName(context, pkgName)
        if (extensionPackage == null) {
            logcat(LogPriority.ERROR) { "Extension package is not found ($pkgName)" }
            return LoadResult.Error
        }
        return loadExtension(context, extensionPackage)
    }

    fun getExtensionPackageInfoFromPkgName(context: Context, pkgName: String): PackageInfo? {
        return getExtensionInfoFromPkgName(context, pkgName)?.packageInfo
    }

    private fun getExtensionInfoFromPkgName(context: Context, pkgName: String): ExtensionInfo? {
        val privateExtensionFile = File(getPrivateExtensionDir(context), "$pkgName.$PRIVATE_EXTENSION_EXTENSION")
        val privatePkg = if (privateExtensionFile.isFile) {
            context.packageManager.getPackageArchiveInfo(privateExtensionFile.absolutePath, PACKAGE_FLAGS)
                ?.takeIf { isPackageAnExtension(it) }
                ?.let {
                    it.applicationInfo!!.fixBasePaths(privateExtensionFile.absolutePath)
                    ExtensionInfo(
                        packageInfo = it,
                        isShared = false,
                    )
                }
        } else {
            null
        }

        val sharedPkg = try {
            context.packageManager.getPackageInfo(pkgName, PACKAGE_FLAGS)
                .takeIf { isPackageAnExtension(it) }
                ?.let {
                    ExtensionInfo(
                        packageInfo = it,
                        isShared = true,
                    )
                }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        return selectExtensionPackage(sharedPkg, privatePkg)
    }

    /**
     * Loads an extension
     *
     * @param context The application context.
     * @param extensionInfo The extension to load.
     */
    private suspend fun loadExtension(
        context: Context,
        extensionInfo: ExtensionInfo,
        // KMK -->
        extStores: List<ExtensionStore>? = null,
        // KMK <--
    ): LoadResult {
        // KMK -->
        val stores = extStores ?: getExtensionStores.get()
        // KMK <--
        val pkgManager = context.packageManager
        val pkgInfo = extensionInfo.packageInfo
        val appInfo = pkgInfo.applicationInfo!!
        val pkgName = pkgInfo.packageName
        val contentType = getContentType(pkgInfo) ?: return LoadResult.Error

        val extName = when (contentType) {
            Extension.ContentType.MANGA -> appInfo.metaData.getString(METADATA_NAME)
                ?: pkgManager.getApplicationLabel(appInfo).toString().substringAfter("Tachiyomi: ")
            Extension.ContentType.NOVEL -> appInfo.metaData
                ?.getString(IReaderExtensionConstants.METADATA_SOURCE_NAME)
                ?.takeIf { it.isNotBlank() }
                ?: pkgManager.getApplicationLabel(appInfo).toString().substringAfter("IReader: ").substringBefore(" (")
        }
        val versionName = pkgInfo.versionName
        val versionCode = PackageInfoCompat.getLongVersionCode(pkgInfo)

        if (versionName.isNullOrEmpty()) {
            logcat(LogPriority.WARN) { "Missing versionName for extension $extName" }
            return LoadResult.Error
        }

        // Validate lib version
        val libVersion = when (contentType) {
            Extension.ContentType.MANGA -> appInfo.metaData.getFloat(METADATA_EXTENSION_LIB)
                .takeUnless { it == 0.0f }
                ?.toString()
                ?.toDouble()
                ?: versionName.substringBeforeLast('.').toDoubleOrNull()
            Extension.ContentType.NOVEL -> versionName.substringBeforeLast('.', versionName).toDoubleOrNull()
        }
        val libVersionOk = when (contentType) {
            Extension.ContentType.MANGA -> libVersion != null && libVersion in SUPPORTED_LIB_VERSIONS
            Extension.ContentType.NOVEL ->
                libVersion != null &&
                    libVersion in IReaderExtensionConstants.LIB_VERSION_MIN..IReaderExtensionConstants.LIB_VERSION_MAX
        }
        if (!libVersionOk) {
            logcat(LogPriority.WARN) {
                "Lib version is $libVersion, while only version(s) ${SUPPORTED_LIB_VERSIONS.joinToString()} are supported"
            }
            return LoadResult.Error
        }

        val signatures = getSignatures(pkgInfo)
        if (signatures.isNullOrEmpty()) {
            logcat(LogPriority.WARN) { "Package $pkgName isn't signed" }
            return LoadResult.Error
        } else if (!isTrusted(contentType, pkgInfo, signatures)) {
            val extension = Extension.Untrusted(
                extName,
                pkgName,
                versionName,
                versionCode,
                libVersion,
                signatures.last(),
                // KMK -->
                storeName = stores.firstOrNull { store ->
                    signatures.all { it == store.signingKey }
                }?.let { store ->
                    store.badgeLabel.takeIf(String::isNotBlank) ?: store.name
                } ?: iReaderStoreName(contentType, signatures),
                // KMK <--
                contentType = contentType,
            )
            logcat(LogPriority.WARN) { "Extension $pkgName isn't trusted" }
            return LoadResult.Untrusted(extension)
        }

        val isNsfw = when (contentType) {
            Extension.ContentType.MANGA -> appInfo.metaData.getInt(METADATA_CONTENT_WARNING) > 0 ||
                appInfo.metaData.getInt(METADATA_NSFW) == 1
            Extension.ContentType.NOVEL ->
                appInfo.metaData?.getInt(IReaderExtensionConstants.METADATA_SOURCE_NSFW, 0) == 1
        }
        if (!loadNsfwSource && isNsfw) {
            logcat(LogPriority.WARN) { "NSFW extension $pkgName not allowed" }
            return LoadResult.Error
        }

        val classLoader = try {
            ChildFirstPathClassLoader(appInfo.sourceDir, null, context.classLoader)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Extension load error: $extName ($pkgName)" }
            return LoadResult.Error
        }

        val loadedSources = when (contentType) {
            Extension.ContentType.MANGA -> {
                val mangaSources = loadMangaSources(appInfo, pkgInfo, classLoader, extName)
                    ?: return LoadResult.Error
                val langs = mangaSources.filterIsInstance<CatalogueSource>()
                    .map { it.lang }
                    .toSet()
                val sourceLang = when (langs.size) {
                    0 -> ""
                    1 -> langs.first()
                    else -> "all"
                }
                LoadedExtensionSources(mangaSources = mangaSources, lang = sourceLang)
            }
            Extension.ContentType.NOVEL -> {
                val runtime = getIReaderRuntime(context)
                val source = loadIReaderSource(appInfo, pkgInfo, classLoader, extName, runtime)
                    ?: return LoadResult.Error
                LoadedExtensionSources(
                    novelSources = listOf(IReaderNovelSource(source, runtime)),
                    lang = source.lang.ifBlank {
                        appInfo.metaData
                            ?.getString(IReaderExtensionConstants.METADATA_SOURCE_LANG)
                            .orEmpty()
                    },
                )
            }
        }

        val extension = Extension.Installed(
            name = extName,
            pkgName = pkgName,
            versionName = versionName,
            versionCode = versionCode,
            libVersion = libVersion,
            lang = loadedSources.lang,
            isNsfw = isNsfw,
            sources = loadedSources.mangaSources,
            pkgFactory = if (contentType == Extension.ContentType.MANGA) {
                appInfo.metaData.getString(METADATA_SOURCE_FACTORY)
            } else {
                null
            },
            icon = appInfo.loadIcon(pkgManager),
            isShared = extensionInfo.isShared,
            // KMK -->
            signatureHash = signatures.last(),
            storeName = stores.firstOrNull { store ->
                signatures.all { it == store.signingKey }
            }?.let { store ->
                store.badgeLabel.takeIf(String::isNotBlank) ?: store.name
            } ?: iReaderStoreName(contentType, signatures),
            // KMK <--
            novelSources = loadedSources.novelSources,
            contentType = contentType,
        )
        return LoadResult.Success(extension)
    }

    private fun loadMangaSources(
        appInfo: ApplicationInfo,
        pkgInfo: PackageInfo,
        classLoader: ClassLoader,
        extName: String,
    ): List<Source>? {
        val sourceClasses = appInfo.metaData.getString(METADATA_SOURCE_CLASS) ?: return null
        return sourceClasses
            .split(";")
            .map { it.toAbsoluteClassName(pkgInfo.packageName) }
            .flatMap {
                try {
                    when (val obj = Class.forName(it, false, classLoader).getDeclaredConstructor().newInstance()) {
                        is Source -> listOf(obj)
                        is SourceFactory -> obj.createSources()
                        else -> throw Exception("Unknown source class type: ${obj.javaClass}")
                    }
                } catch (e: Throwable) {
                    logcat(LogPriority.ERROR, e) { "Extension load error: $extName ($it)" }
                    return null
                }
            }
    }

    private fun loadIReaderSource(
        appInfo: ApplicationInfo,
        pkgInfo: PackageInfo,
        classLoader: ClassLoader,
        extName: String,
        runtime: IReaderRuntime,
    ): CatalogSource? {
        val sourceClass = appInfo.metaData
            ?.getString(IReaderExtensionConstants.METADATA_SOURCE_CLASS)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        val className = sourceClass.toAbsoluteClassName(pkgInfo.packageName)
        return try {
            Class.forName(className, false, classLoader)
                .getConstructor(Dependencies::class.java)
                .newInstance(runtime.dependencies(pkgInfo.packageName)) as CatalogSource
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "IReader extension load error: $extName ($className)" }
            null
        }
    }

    private suspend fun getIReaderRuntime(context: Context): IReaderRuntime {
        iReaderRuntime?.let { return it }
        return iReaderRuntimeMutex.withLock {
            iReaderRuntime ?: withContext(Dispatchers.Main.immediate) {
                IReaderRuntime(context.applicationContext, preferenceStore)
            }.also { iReaderRuntime = it }
        }
    }

    private suspend fun isTrusted(
        contentType: Extension.ContentType,
        pkgInfo: PackageInfo,
        signatures: List<String>,
    ): Boolean {
        if (
            contentType == Extension.ContentType.NOVEL &&
            signatures.all { it == IReaderExtensionConstants.SIGNATURE_HASH }
        ) {
            return true
        }
        return trustExtension.isTrusted(pkgInfo, signatures)
    }

    private fun iReaderStoreName(
        contentType: Extension.ContentType,
        signatures: List<String>,
    ): String? {
        return IReaderExtensionConstants.REPO_NAME.takeIf {
            contentType == Extension.ContentType.NOVEL &&
                signatures.all { signature -> signature == IReaderExtensionConstants.SIGNATURE_HASH }
        }
    }

    /**
     * Choose which extension package to use based on version code
     *
     * @param shared extension installed to system
     * @param private extension installed to data directory
     */
    private fun selectExtensionPackage(shared: ExtensionInfo?, private: ExtensionInfo?): ExtensionInfo? {
        when {
            private == null && shared != null -> return shared
            shared == null && private != null -> return private
            shared == null && private == null -> return null
        }

        return if (PackageInfoCompat.getLongVersionCode(shared!!.packageInfo) >=
            PackageInfoCompat.getLongVersionCode(private!!.packageInfo)
        ) {
            shared
        } else {
            private
        }
    }

    /**
     * Returns true if the given package is an extension.
     *
     * @param pkgInfo The package info of the application.
     */
    private fun isPackageAnExtension(pkgInfo: PackageInfo): Boolean {
        return getContentType(pkgInfo) != null
    }

    private fun getContentType(pkgInfo: PackageInfo): Extension.ContentType? {
        val features = pkgInfo.reqFeatures.orEmpty().map { it.name }.toSet()
        return when {
            EXTENSION_FEATURE in features -> Extension.ContentType.MANGA
            IReaderExtensionConstants.FEATURE in features -> Extension.ContentType.NOVEL
            else -> null
        }
    }

    private fun String.toAbsoluteClassName(packageName: String): String {
        val sourceClass = trim()
        return if (sourceClass.startsWith(".")) packageName + sourceClass else sourceClass
    }

    /**
     * Returns the signatures of the package or null if it's not signed.
     *
     * @param pkgInfo The package info of the application.
     * @return List SHA256 digest of the signatures
     */
    private fun getSignatures(pkgInfo: PackageInfo): List<String>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo!!
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        }
            ?.map { Hash.sha256(it.toByteArray()) }
            ?.toList()
    }

    /**
     * On Android 13+ the ApplicationInfo generated by getPackageArchiveInfo doesn't
     * have sourceDir which breaks assets loading (used for getting icon here).
     */
    private fun ApplicationInfo.fixBasePaths(apkPath: String) {
        if (sourceDir == null) {
            sourceDir = apkPath
        }
        if (publicSourceDir == null) {
            publicSourceDir = apkPath
        }
    }

    private data class ExtensionInfo(
        val packageInfo: PackageInfo,
        val isShared: Boolean,
    )

    private data class LoadedExtensionSources(
        val mangaSources: List<Source> = emptyList(),
        val novelSources: List<NovelSource> = emptyList(),
        val lang: String,
    )
}