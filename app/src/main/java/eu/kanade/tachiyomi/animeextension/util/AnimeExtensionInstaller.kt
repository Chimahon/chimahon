package eu.kanade.tachiyomi.animeextension.util

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.animeextension.AnimeExtensionManager
import eu.kanade.tachiyomi.animeextension.model.AnimeExtension
import eu.kanade.tachiyomi.extension.installer.Installer
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.util.ExtensionInstallActivity
import eu.kanade.tachiyomi.extension.util.ExtensionInstallService
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import logcat.LogPriority
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The installer which installs, updates and uninstalls anime extensions.
 * Mirrors [ExtensionInstaller]: the apk is downloaded with OkHttp and installed from
 * the cache dir, instead of going through DownloadManager and its completion broadcast
 * (which can fire while the app is in the background and get the install killed).
 *
 * @param context The application context.
 */
internal class AnimeExtensionInstaller(
    private val context: Context,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val activeSteps = ConcurrentHashMap<Long, MutableStateFlow<InstallStep>>()
    private val extensionInstaller = Injekt.get<BasePreferences>().extensionInstaller()

    private val httpClient: OkHttpClient = Injekt.get<NetworkHelper>().client

    private val animeExtensionManager = Injekt.get<AnimeExtensionManager>()

    /**
     * Adds the given extension to the downloads queue and returns an observable containing its
     * step in the installation process.
     *
     * @param url The url of the apk.
     * @param extension The extension to install.
     */
    fun downloadAndInstall(url: String, extension: AnimeExtension): Flow<InstallStep> {
        val pkgName = extension.pkgName + ":${extension.signatureHash}"
        val downloadId = pkgName.toDownloadId()
        cancelInstall(pkgName)

        val step = MutableStateFlow(InstallStep.Pending)
        activeSteps[downloadId] = step

        val job = scope.launch {
            val tmpFile = File(context.cacheDir, "anime_extension_$pkgName.apk")
            try {
                step.value = InstallStep.Downloading
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute()
                    .use { response ->
                        if (!response.isSuccessful) {
                            throw Exception("Failed to download anime extension")
                        }
                        tmpFile.outputStream().use { output ->
                            response.body.byteStream().use { input ->
                                input.copyTo(output)
                            }
                        }
                    }

                step.value = InstallStep.Installing
                installApk(downloadId, tmpFile)
            } catch (e: Exception) {
                if (e is InterruptedException) {
                    // Canceled
                } else {
                    logcat(LogPriority.ERROR, e)
                    step.value = InstallStep.Error
                }
                tmpFile.delete()
            }
        }

        activeJobs[pkgName] = job

        return step.asStateFlow()
            .onCompletion {
                activeJobs.remove(pkgName)
                activeSteps.remove(downloadId)
                job.cancel()
            }
    }

    /**
     * Starts an intent to install the extension at the given file.
     *
     * @param downloadId The id of the download.
     * @param tempFile The file of the extension to install. Delete after use.
     */
    private fun installApk(downloadId: Long, tempFile: File) {
        when (val installer = extensionInstaller.get()) {
            BasePreferences.ExtensionInstaller.LEGACY -> {
                val intent = Intent(context, ExtensionInstallActivity::class.java)
                    .setDataAndType(tempFile.getUriCompat(context), APK_MIME)
                    .putExtra(ExtensionInstaller.EXTRA_DOWNLOAD_ID, downloadId)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

                context.startActivity(intent)
            }
            BasePreferences.ExtensionInstaller.PRIVATE -> {
                try {
                    if (AnimeExtensionLoader.installPrivateExtensionFile(context, tempFile)) {
                        animeExtensionManager.updateInstallStep(downloadId, InstallStep.Installed)
                    } else {
                        animeExtensionManager.updateInstallStep(downloadId, InstallStep.Error)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to read downloaded anime extension file." }
                    animeExtensionManager.updateInstallStep(downloadId, InstallStep.Error)
                }

                tempFile.delete()
            }
            else -> {
                val intent = ExtensionInstallService.getIntent(
                    context,
                    downloadId,
                    tempFile.getUriCompat(context),
                    installer,
                )
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: RuntimeException) {
                    // App is in background and the system denies the foreground service start
                    // (ForegroundServiceStartNotAllowedException on Android 12+). Fail the install
                    // instead of crashing so the user can retry from the extension screen.
                    logcat(LogPriority.ERROR, e) { "Failed to start anime extension install service." }
                    animeExtensionManager.updateInstallStep(downloadId, InstallStep.Error)
                }
            }
        }
    }

    /**
     * Cancels extension install and remove from the install queue.
     */
    fun cancelInstall(pkgName: String) {
        activeJobs.remove(pkgName)?.cancel()
        Installer.cancelInstallQueue(context, pkgName.toDownloadId())
    }

    /**
     * Starts an intent to uninstall the extension by the given package name.
     *
     * @param pkgName The package name of the extension to uninstall
     */
    fun uninstallApk(pkgName: String) {
        if (context.isPackageInstalled(pkgName)) {
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, "package:$pkgName".toUri())
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            AnimeExtensionLoader.uninstallPrivateExtension(context, pkgName)
            AnimeExtensionInstallReceiver.notifyRemoved(context, pkgName)
        }
    }

    /**
     * Sets the step of the installation of an extension.
     *
     * @param downloadId The id of the download.
     * @param step New install step.
     */
    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        activeSteps[downloadId]?.let { it.value = step }
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"

        /** Convert packageName to download ID avoiding negative number */
        private fun String.toDownloadId(): Long = hashCode().toLong() and 0xFFFFFFFFL
    }
}
