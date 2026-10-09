package eu.kanade.tachiyomi.data.panel

import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.data.model.ModelBundleInstaller
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notify
import mihon.domain.panel.repository.PanelDetectionRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Downloads the panel detection model and the LiteRT native libraries at runtime.
 *
 * The panel-by-panel navigation feature is disabled by default, so the model and
 * native libraries are not bundled in the APK. They are fetched on first use and
 * stored under [MODEL_ROOT_DIR] in app files.
 */
class PanelModelDownloader(
    private val context: Context,
    private val httpClient: OkHttpClient,
    preferenceStore: PreferenceStore,
) {
    companion object {
        private const val MODEL_ROOT_DIR = "panel_detector"

        private const val LITERT_VERSION = "2.1.6"
        private const val LITERT_AAR_URL =
            "https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert/$LITERT_VERSION/litert-$LITERT_VERSION.aar"
        private const val LITERT_API_AAR_URL =
            "https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert-api/$LITERT_VERSION/litert-api-$LITERT_VERSION.aar"
        private const val MODEL_URL =
            "https://huggingface.co/leoxs22/manga-panel-detector-yolo26n/resolve/main/manga_panel_detector_int8.tflite"

        private const val LITE_RT_SO = "libLiteRt.so"
        private const val LITERT_JNI_SO = "liblitert_jni.so"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val installedRevision = preferenceStore.getString(Preference.appStateKey("panel_model_revision"), "")
    private val knownRevision = preferenceStore.getString(Preference.appStateKey("panel_model_known_revision"), "")

    private val installedHash = preferenceStore.getString(Preference.appStateKey("panel_model_hash"), "")
    private val knownHash = preferenceStore.getString(Preference.appStateKey("panel_model_known_hash"), "")

    val hasUpdate: Boolean
        get() = isDownloaded && knownHash.get().isNotBlank() && installedHash.get() != knownHash.get()

    suspend fun checkForUpdates(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("https://huggingface.co/api/models/leoxs22/manga-panel-detector-yolo26n?blobs=true").build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use false
                val metadata = Json.parseToJsonElement(response.body?.string() ?: return@use false).jsonObject
                val revision = metadata["sha"]?.jsonPrimitive?.content ?: return@use false
                val model = metadata["siblings"]?.jsonArray?.firstOrNull {
                    it.jsonObject["rfilename"]?.jsonPrimitive?.content == "manga_panel_detector_int8.tflite"
                }?.jsonObject ?: return@use false
                val hash = model["lfs"]?.jsonObject?.get("sha256")?.jsonPrimitive?.content ?: return@use false
                if (isDownloaded && installedHash.get().isBlank()) installedHash.set(fileHash(modelFile()))
                knownRevision.set(revision)
                knownHash.set(hash)
                true
            }
        }.getOrDefault(false)
    }

    val isDownloaded: Boolean
        get() = supportedAbi()?.let { abi ->
            ModelBundleInstaller.recover(context.filesDir, MODEL_ROOT_DIR)
            nativesReady(abi) && modelFile().isFile && modelFile().length() > 0L
        } ?: false

    /** Removes the installed model, native libraries and recorded revision. */
    fun deleteModel(): Boolean = synchronized(ModelBundleInstaller) {
        val dir = root()
        val previous = File(context.filesDir, ".$MODEL_ROOT_DIR.previous")
        val deleted = (!dir.exists() || dir.deleteRecursively()) &&
            (!previous.exists() || previous.deleteRecursively())
        if (deleted) {
            installedRevision.delete()
            installedHash.delete()
        }
        deleted
    }

    private fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun root(): File = File(context.filesDir, MODEL_ROOT_DIR)

    private fun abiDir(abi: String): File = File(root(), "lib/$abi")

    private fun nativesReady(abi: String): Boolean =
        File(abiDir(abi), LITE_RT_SO).isFile && File(abiDir(abi), LITERT_JNI_SO).isFile

    private fun modelFile(): File = File(root(), "model.tflite")

    private fun supportedAbi(): String? = when {
        "arm64-v8a" in Build.SUPPORTED_ABIS -> "arm64-v8a"
        "armeabi-v7a" in Build.SUPPORTED_ABIS -> "armeabi-v7a"
        "x86_64" in Build.SUPPORTED_ABIS -> "x86_64"
        else -> null
    }

    /**
     * [force] refetches over an existing install. It also defeats the
     * per-file skip in [downloadTo]/[downloadFile], which would otherwise make a
     * forced refresh a silent no-op.
     */
    fun triggerDownload(force: Boolean = false) {
        if (isDownloaded && !force) return
        scope.launch { downloadWithNotifications(force) }
    }

    suspend fun downloadWithNotifications(force: Boolean): Result<Unit> {
        context.notify(
            Notifications.ID_PANEL_PROGRESS,
            Notifications.CHANNEL_PANEL_MODEL_DOWNLOAD,
        ) {
            setSmallIcon(android.R.drawable.stat_sys_download)
            setContentTitle("Downloading panel detection model")
            setContentText("Downloading panel detection runtime...")
            setOngoing(true)
            setOnlyAlertOnce(true)
        }
        val result = downloadAndExtract(force)
        context.cancelNotification(Notifications.ID_PANEL_PROGRESS)
        if (result.isSuccess) {
            context.notify(
                Notifications.ID_PANEL_PROGRESS,
                Notifications.CHANNEL_PANEL_MODEL_DOWNLOAD,
            ) {
                setSmallIcon(android.R.drawable.stat_sys_download_done)
                setContentTitle("Panel detection ready")
                setContentText("Panel detection model downloaded successfully")
                setAutoCancel(true)
                setOngoing(false)
            }
        } else {
            context.notify(
                Notifications.ID_PANEL_PROGRESS,
                Notifications.CHANNEL_PANEL_MODEL_DOWNLOAD,
            ) {
                setSmallIcon(android.R.drawable.stat_sys_warning)
                setContentTitle("Panel detection model download failed")
                setContentText(result.exceptionOrNull()?.message ?: "Unknown error")
                setAutoCancel(true)
                setOngoing(false)
            }
        }
        return result
    }

    suspend fun downloadAndExtract(force: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        if (!force && isDownloaded) return@withContext Result.success(Unit)
        val staging = File.createTempFile(".panel_detector-", ".staging", context.filesDir)
        check(staging.delete() && staging.mkdir())
        try {
            val abi = supportedAbi() ?: error("Unsupported device architecture")
            val targetAbiDir = File(staging, "lib/$abi")
            val revision = knownRevision.get()
            val expectedHash = knownHash.get()
            downloadTo(targetAbiDir, LITE_RT_SO, LITERT_AAR_URL, "jni/$abi/libLiteRt.so", true)
            downloadTo(targetAbiDir, LITERT_JNI_SO, LITERT_API_AAR_URL, "jni/$abi/liblitert_jni.so", true)
            val url = if (revision.isBlank()) MODEL_URL else MODEL_URL.replace("/resolve/main/", "/resolve/$revision/")
            val model = File(staging, "model.tflite")
            downloadFile(url, model, true)
            val hash = fileHash(model)
            check(expectedHash.isBlank() || hash == expectedHash) { "Panel model checksum mismatch" }
            ModelBundleInstaller.replace(
                staging, context.filesDir, MODEL_ROOT_DIR,
                listOf("model.tflite", "lib/$abi/$LITE_RT_SO", "lib/$abi/$LITERT_JNI_SO"),
            )
            installedRevision.set(revision)
            installedHash.set(hash)
            Injekt.get<PanelDetectionRepository>().invalidateModels()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Downloads [aarUrl], opens it as a zip and extracts [entryName] into
     * [targetDir]/[fileName]. Skips the fetch when the file is already there
     * unless [force], which is what lets a refresh actually replace it.
     */
    private fun downloadTo(
        targetDir: File,
        fileName: String,
        aarUrl: String,
        entryName: String,
        force: Boolean = false,
    ) {
        val target = File(targetDir, fileName)
        if (!force && target.isFile && target.length() > 0L) return

        target.parentFile?.mkdirs()

        val request = Request.Builder().url(aarUrl).build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Download failed: HTTP ${response.code} for $aarUrl" }
            val body = response.body ?: checkNotNull(null) { "Empty response body for $aarUrl" }
            body.byteStream().use { input ->
                extractEntry(input, entryName, target)
            }
        }
    }

    private fun downloadFile(url: String, target: File, force: Boolean = false) {
        if (!force && target.isFile && target.length() > 0L) return

        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Download failed: HTTP ${response.code} for $url" }
            val body = response.body ?: checkNotNull(null) { "Empty response body for $url" }
            target.parentFile?.mkdirs()
            body.byteStream().use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
        }
    }

    private fun extractEntry(input: InputStream, entryName: String, target: File) {
        val targetCanonical = target.canonicalFile
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == entryName && !entry.isDirectory) {
                    val dir = targetCanonical.parentFile
                    dir?.mkdirs()
                    FileOutputStream(targetCanonical).use { out -> zip.copyTo(out) }
                    return
                }
                zip.closeEntry()
            }
        }
        error("Entry not found in archive: $entryName")
    }
}

private fun Context.cancelNotification(id: Int) {
    val manager = androidx.core.app.NotificationManagerCompat.from(this)
    manager.cancel(id)
}