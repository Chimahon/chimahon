package eu.kanade.tachiyomi.extension.api

import android.content.Context
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.ireader.IReaderExtensionConstants
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import exh.source.BlacklistedSources
import exh.source.ExhPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.extension.interactor.UpdateExtensionStores
import mihon.domain.extension.repository.ExtensionStoreRepository
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import kotlin.time.Duration.Companion.days

internal class ExtensionApi {

    private val repository: ExtensionStoreRepository by injectLazy()

    private val preferenceStore: PreferenceStore by injectLazy()
    private val updateExtensionStores: UpdateExtensionStores by injectLazy()
    private val extensionManager: ExtensionManager by injectLazy()

    private val networkService: NetworkHelper by injectLazy()

    private val json: Json by injectLazy()

    // SY -->
    private val sourcePreferences: SourcePreferences by injectLazy()
    // SY <--

    private val lastExtCheck: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_ext_check"), 0)
    }

    suspend fun findExtensions(): List<Extension.Available> {
        // KMK -->
        val disabledRepos = sourcePreferences.disabledRepos().get()
        // KMK <--
        return withIOContext {
            val mangaExtensions = async {
                repository.fetchExtensions(
                    // KMK -->
                    disabledRepos,
                    // KMK <--
                )
            }
            val iReaderExtensions = async { getIReaderExtensions() }
            mangaExtensions.await() + iReaderExtensions.await()
        }
    }

    private suspend fun getIReaderExtensions(): List<Extension.Available> {
        return try {
            val response = networkService.client
                .newCall(GET("${IReaderExtensionConstants.REPO_URL}/index.min.json"))
                .awaitSuccess()

            with(json) {
                response.parseAs<List<IReaderExtensionJsonObject>>()
                    .mapNotNull { it.toExtensionOrNull() }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to get IReader extensions" }
            emptyList()
        }
    }

    private fun IReaderExtensionJsonObject.toExtensionOrNull(): Extension.Available? {
        val libVersion = version.substringBeforeLast('.', version).toDoubleOrNull() ?: return null
        if (
            libVersion < IReaderExtensionConstants.LIB_VERSION_MIN ||
            libVersion > IReaderExtensionConstants.LIB_VERSION_MAX
        ) {
            return null
        }
        return Extension.Available(
            name = name,
            pkgName = pkg,
            versionName = version,
            versionCode = code,
            libVersion = libVersion,
            lang = lang,
            isNsfw = nsfw,
            signatureHash = IReaderExtensionConstants.SIGNATURE_HASH,
            storeName = IReaderExtensionConstants.REPO_NAME,
            sources = emptyList(),
            apkUrl = "${IReaderExtensionConstants.REPO_URL}/apk/$apk",
            iconUrl = "${IReaderExtensionConstants.REPO_URL}/icon/${apk.removeSuffix(".apk")}.png",
            contentType = Extension.ContentType.NOVEL,
        )
    }

    suspend fun checkForUpdates(
        context: Context,
        fromAvailableExtensionList: Boolean = false,
    ): List<Extension.Installed>? {
        // Limit checks to once a day at most
        if (!fromAvailableExtensionList &&
            Instant.now().toEpochMilli() < lastExtCheck.get() + 1.days.inWholeMilliseconds
        ) {
            return null
        }

        updateExtensionStores()

        val extensions = if (fromAvailableExtensionList) {
            extensionManager.availableExtensionsFlow.value
        } else {
            findExtensions().also { lastExtCheck.set(Instant.now().toEpochMilli()) }
        }

        // SY -->
        val blacklistEnabled = sourcePreferences.enableSourceBlacklist().get()
        // SY <--

        val installedExtensions = ExtensionLoader.loadExtensions(context)
            .filterIsInstance<LoadResult.Success>()
            .map { it.extension }
            // SY -->
            .filterNot { it.isBlacklisted(blacklistEnabled) }
        // SY <--

        val extensionsWithUpdate = mutableListOf<Extension.Installed>()
        for (installedExt in installedExtensions) {
            val pkgName = installedExt.pkgName
            val availableExt = extensions.find { it.pkgName == pkgName } ?: continue
            val hasUpdatedVer = availableExt.versionCode > installedExt.versionCode
            val hasUpdatedLib = availableExt.libVersion > installedExt.libVersion
            val hasUpdate = hasUpdatedVer || hasUpdatedLib
            if (hasUpdate) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        if (extensionsWithUpdate.isNotEmpty()) {
            ExtensionUpdateNotifier(context).promptUpdates(extensionsWithUpdate.map { it.name })
        }

        return extensionsWithUpdate
    }

    // SY -->
    private fun Extension.isBlacklisted(
        blacklistEnabled: Boolean = sourcePreferences.enableSourceBlacklist().get(),
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
    // SY <--
}

@Serializable
private data class IReaderExtensionJsonObject(
    val pkg: String,
    val apk: String,
    val name: String,
    val id: Long,
    val lang: String,
    val code: Long,
    val version: String,
    val description: String = "",
    val nsfw: Boolean = false,
)