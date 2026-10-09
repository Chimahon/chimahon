package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import chimahon.dictionary.hasNewerRevision
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.model.ModelBundleInstaller
import eu.kanade.tachiyomi.data.model.ModelVersionResolver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notify
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ModelDownloader(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val versionResolver: ModelVersionResolver,
    preferenceStore: PreferenceStore,
) {
    companion object {
        /**
         * Floor for the bundled model release. Bump this when publishing new
         * models so installs with no network (or a failed check) still resolve to
         * a real release; [latestOcrVersion] moves it forward afterwards.
         */
        private const val MODELS_VERSION = "2.5"
        private const val RELEASE_URL =
            "https://github.com/Chimahon/chimahon-local-models/releases/download/v"
        private const val LENS_ZIP = "models.zip"
        private const val PADDLE_ZIP = "paddle-ocr.zip"
        private const val PADDLE_DIR = "paddle_ocr"
        private const val LENS_DIR = "screenai_models"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // App state, so installed/known model versions stay out of backups.
    private val installedLensVersion =
        preferenceStore.getString(Preference.appStateKey("ocr_lens_model_version"), "")
    private val installedPaddleVersion =
        preferenceStore.getString(Preference.appStateKey("ocr_paddle_model_version"), "")
    private val knownVersion =
        preferenceStore.getString(Preference.appStateKey("ocr_models_known_version"), "")

    /**
     * Release this build would download from: the newest one a check has seen,
     * falling back to [MODELS_VERSION]. Both payloads live in the same release,
     * so this is a single value for Lens and Paddle alike.
     */
    val latestVersion: String
        get() = knownVersion.get().ifBlank { MODELS_VERSION }

    /**
     * Version actually on disk. Installs that predate version tracking recorded
     * none, so a blank is reported as the latest rather than nagging every
     * existing user with a phantom update.
     */
    val lensVersionInstalled: String
        get() = installedLensVersion.get().ifBlank { latestVersion }

    val paddleVersionInstalled: String
        get() = installedPaddleVersion.get().ifBlank { latestVersion }

    val hasLensUpdate: Boolean
        get() = isDownloaded && hasNewerRevision(lensVersionInstalled, latestVersion)

    val hasPaddleUpdate: Boolean
        get() = isPaddleDownloaded && hasNewerRevision(paddleVersionInstalled, latestVersion)

    /**
     * Records the newest published release so subsequent downloads pull it.
     * Never moves backwards: releases are created in dispatch order, so a
     * re-dispatched older tag must not become the known version.
     */
    suspend fun checkForUpdates(): Boolean {
        val latest = versionResolver.latestOcrVersion() ?: return false
        if (isDownloaded && installedLensVersion.get().isBlank()) installedLensVersion.set(MODELS_VERSION)
        if (isPaddleDownloaded && installedPaddleVersion.get().isBlank()) installedPaddleVersion.set(MODELS_VERSION)
        if (knownVersion.get().isBlank()) {
            // First successful check pins the floor so later comparisons are real.
            knownVersion.set(MODELS_VERSION)
        }
        if (hasNewerRevision(latestVersion, latest)) {
            knownVersion.set(latest)
        }
        return true
    }

    val isDownloaded: Boolean
        get() = supportedAbi()?.let { abi ->
            ModelBundleInstaller.recover(context.filesDir, LENS_DIR)
            requiredLensFiles(abi).all { it.isFile && it.length() > 0L }
        } ?: false

    val isPaddleDownloaded: Boolean
        get() = supportedAbi()?.let { abi ->
            ModelBundleInstaller.recover(context.filesDir, PADDLE_DIR)
            requiredPaddleFiles(abi).all { it.isFile && it.length() > 0L }
        } ?: false

    /** Removes the on-device OCR models and forgets the recorded version. */
    fun deleteLensModels(): Boolean = deleteDir(File(context.filesDir, LENS_DIR)) {
        installedLensVersion.delete()
    }

    /** Removes the Paddle OCR models and forgets the recorded version. */
    fun deletePaddleModels(): Boolean = deleteDir(File(context.filesDir, PADDLE_DIR)) {
        installedPaddleVersion.delete()
    }

    private fun deleteDir(dir: File, onDeleted: () -> Unit): Boolean = synchronized(ModelBundleInstaller) {
        val previous = File(dir.parentFile, ".${dir.name}.previous")
        val deleted = (!dir.exists() || dir.deleteRecursively()) &&
            (!previous.exists() || previous.deleteRecursively())
        if (deleted) onDeleted()
        deleted
    }

    private fun supportedAbi(): String? = when {
        "arm64-v8a" in Build.SUPPORTED_ABIS -> "arm64-v8a"
        "armeabi-v7a" in Build.SUPPORTED_ABIS -> "armeabi-v7a"
        else -> null
    }

    private fun requiredLensFiles(abi: String): List<File> {
        val root = File(context.filesDir, LENS_DIR)
        return listOf(
            File(root, "lots_multiscript_v8_runner.binarypb"),
            File(root, "lots_multiscript_v8_engine_patched.binarypb"),
            File(root, "third_party/lens/line_detector/v688492737/gocr_group_rpn_text_detection_config_2024_q4.binarypb"),
            File(root, "third_party/lens/line_recognition/v678672708/recognizer_jpan.tflite"),
            File(root, "third_party/lens/line_recognition/v678672708/recognizer_jpan_lm.compact_fst.gz"),
            File(root, "lib/$abi/liblens_ondevice_engine_base.so"),
            File(root, "lib/$abi/liblens_ondevice_engine_play_ml.so"),
        )
    }

    private fun requiredPaddleFiles(abi: String): List<File> {
        val root = File(context.filesDir, PADDLE_DIR)
        return listOf(
            File(root, "manga_det_fp16.param"),
            File(root, "manga_det_fp16.bin"),
            File(root, "manga_rec_fp16.param"),
            File(root, "manga_rec_fp16.bin"),
            File(root, "ppocrv6_dict.txt"),
            File(root, "lib/$abi/libpaddle_ocr.so"),
            File(root, "lib/$abi/libomp.so"),
            File(root, "lib/$abi/libc++_shared.so"),
        )
    }

    /**
     * [force] re-downloads over an existing install, which is what an update
     * needs; without it the guard keeps an up-to-date set from being refetched.
     */
    fun triggerDownload(force: Boolean = false) {
        if (isDownloaded && !force) return
        scope.launch {
            downloadWithNotifications(
                progressText = "Downloading on-device OCR models...",
                successText = "On-device OCR models downloaded successfully",
            ) {
                downloadAndExtract()
            }
        }
    }

    fun triggerPaddleDownload(force: Boolean = false) {
        if (isPaddleDownloaded && !force) return
        scope.launch {
            downloadWithNotifications(
                progressText = "Downloading Paddle OCR models...",
                successText = "Paddle OCR models downloaded successfully",
            ) {
                downloadPaddleAndExtract()
            }
        }
    }

    /**
     * Release the URLs are built from. Both model sets ship in the same GitHub
     * release, so one known version covers both.
     */
    private fun versionDownloadedFrom(): String = latestVersion

    private suspend fun downloadWithNotifications(
        progressText: String,
        successText: String,
        download: suspend () -> Result<Unit>,
    ): Result<Unit> {
        context.notify(
            Notifications.ID_OCR_PROGRESS,
            Notifications.CHANNEL_OCR_MODEL_DOWNLOAD,
        ) {
            setSmallIcon(android.R.drawable.stat_sys_download)
            setContentTitle("Downloading OCR models")
            setContentText(progressText)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }
        val result = download()
        context.cancelNotification(Notifications.ID_OCR_PROGRESS)
        if (result.isSuccess) {
            context.notify(
                Notifications.ID_OCR_PROGRESS,
                Notifications.CHANNEL_OCR_MODEL_DOWNLOAD,
            ) {
                setSmallIcon(android.R.drawable.stat_sys_download_done)
                setContentTitle("OCR models ready")
                setContentText(successText)
                setAutoCancel(true)
                setOngoing(false)
            }
        } else {
            context.notify(
                Notifications.ID_OCR_PROGRESS,
                Notifications.CHANNEL_OCR_MODEL_DOWNLOAD,
            ) {
                setSmallIcon(android.R.drawable.stat_sys_warning)
                setContentTitle("OCR model download failed")
                setContentText(result.exceptionOrNull()?.message ?: "Unknown error")
                setAutoCancel(true)
                setOngoing(false)
            }
        }
        return result
    }

    suspend fun updateInstalledModels(): Result<Unit> {
        if (hasLensUpdate) {
            val result = downloadWithNotifications(
                "Updating on-device OCR models...", "On-device OCR models updated",
            ) { downloadAndExtract() }
            if (result.isFailure) return result
        }
        if (hasPaddleUpdate) {
            val result = downloadWithNotifications(
                "Updating Paddle OCR models...", "Paddle OCR models updated",
            ) { downloadPaddleAndExtract() }
            if (result.isFailure) return result
        }
        return Result.success(Unit)
    }

    suspend fun downloadAndExtract(): Result<Unit> =
        downloadZip(LENS_ZIP)

    suspend fun downloadPaddleAndExtract(): Result<Unit> =
        downloadZip(PADDLE_ZIP)

    private suspend fun downloadZip(zipName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val version = versionDownloadedFrom()
            val bundle = if (zipName == LENS_ZIP) LENS_DIR else PADDLE_DIR
            val url = "$RELEASE_URL$version/$zipName"
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        RuntimeException("Download failed: HTTP ${response.code}")
                    )
                }

                val body = response.body ?: return@withContext Result.failure(
                    RuntimeException("Empty response body")
                )

                body.byteStream().use { input -> extractZip(input, bundle) }
            }
            if (zipName == LENS_ZIP) {
                installedLensVersion.set(version)
                Injekt.get<LocalOcrBridge>().invalidateModels()
            } else {
                installedPaddleVersion.set(version)
                Injekt.get<PaddleOcrBridge>().invalidateModels()
            }

            return@withContext Result.success(Unit)
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    suspend fun importFromUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val source = UniFile.fromUri(context, uri)
                ?: return@withContext Result.failure(RuntimeException("Could not open file"))
            source.openInputStream().use { extractZip(it) }
            // A hand-imported archive is the pinned release unless the user
            // fetched it elsewhere; treat it as such rather than as unknown.
            installedLensVersion.set(versionDownloadedFrom())
            Injekt.get<LocalOcrBridge>().invalidateModels()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractZip(input: InputStream, bundle: String = LENS_DIR) {
        val abi = supportedAbi() ?: error("Unsupported device architecture")
        val bundleRoot = File(context.filesDir, bundle)
        val required = if (bundle == LENS_DIR) requiredLensFiles(abi) else requiredPaddleFiles(abi)
        ModelBundleInstaller.install(input, context.filesDir, bundle, required.map { it.relativeTo(bundleRoot).path })
    }

}

private fun Context.cancelNotification(id: Int) {
    val manager = androidx.core.app.NotificationManagerCompat.from(this)
    manager.cancel(id)
}
