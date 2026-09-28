package eu.kanade.tachiyomi.ui.dictionary.compose

import android.media.MediaPlayer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import chimahon.audio.WordAudioService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Native word-audio playback for the Compose dictionary renderer — the Kotlin
 * counterpart of the WebView's `WordAudioBridge`. Uses the same
 * [WordAudioService] backend so both renderers play identical audio.
 */
object WordAudioPlayer {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaPlayer: MediaPlayer? = null
    private var currentAudioFd: ParcelFileDescriptor? = null

    private val service: WordAudioService by Injekt.injectLazy()

    /** Look up the first available audio URL for [term]/[reading] (suspend). */
    suspend fun findAudio(term: String, reading: String): String? =
        runCatching { service.findWordAudio(term, reading).firstOrNull()?.url }.getOrNull()

    /** Play a resolved audio [url]; handles `chimahon-local://` file descriptors. */
    fun playUrl(url: String) {
        scope.launch {
            try {
                stop()
                val player = mediaPlayer ?: MediaPlayer().also { mediaPlayer = it }
                player.reset()
                if (url.startsWith("chimahon-local://")) {
                    val uri = Uri.parse(url)
                    val sourceId = uri.host ?: return@launch
                    val filePath = uri.path?.substring(1) ?: return@launch
                    val pfd = withContext(Dispatchers.IO) {
                        service.getAudioDataFd(filePath, sourceId)
                    } ?: return@launch
                    currentAudioFd = pfd
                    player.setDataSource(pfd.fileDescriptor)
                } else {
                    player.setDataSource(url)
                }
                player.prepareAsync()
                player.setOnPreparedListener { it.start() }
            } catch (e: Exception) {
                Log.e("WordAudioPlayer", "Error playing audio: $url", e)
            }
        }
    }

    fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.reset()
        } catch (_: Exception) { }
        try {
            currentAudioFd?.close()
        } catch (_: Exception) { }
    }
}
