package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import chimahon.audio.WordAudioService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import eu.kanade.tachiyomi.util.system.copyToClipboard
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * JavaScript bridge for signaling content readiness.
 * Uses postVisualStateCallback to fire onContentReady only after the frame is actually painted.
 */
internal class DictionaryReadyBridge(
    private val webViewProvider: () -> WebView?,
    private val stateProvider: () -> DictionaryWebViewState?,
) {
    @JavascriptInterface
    fun contentReady() {
        val webView = webViewProvider() ?: return
        val state = stateProvider() ?: return
        val generation = state.contentReadyGeneration
        val requestId = ++state.nextContentReadyRequestId
        // postVisualStateCallback must be called on the main thread;
        // @JavascriptInterface methods run on the JavaBridge thread.
        webView.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                webView.postVisualStateCallback(
                    requestId,
                    object : android.webkit.WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if (state.contentReadyGeneration != generation ||
                                state.nextContentReadyRequestId != requestId
                            ) return
                            stateProvider()?.onContentReady?.invoke()
                        }
                    },
                )
            } else {
                stateProvider()?.onContentReady?.invoke()
            }
        }
    }
}

/**
 * JavaScript bridge for passing large payloads (JSON, HTML) from Kotlin to JS without
 * embedding them in evaluateJavascript() strings. The JS side calls
 * PayloadBridge.getPayloadJson() or PayloadBridge.getEntry(index) to pull
 * the data natively — avoids the overhead of encoding 300KB+ as a JS string literal.
 */
internal class PayloadBridge {
    @Volatile var rawPayloadJson: String = ""
    @Volatile var rawEntryJsons: List<String> = emptyList()

    @JavascriptInterface fun getPayloadJson(): String = rawPayloadJson
    @JavascriptInterface fun getEntryCount(): Int = rawEntryJsons.size

    @JavascriptInterface fun getEntry(index: Int): String? = rawEntryJsons.getOrNull(index)
}

/**
 * JavaScript bridge for Anki card creation.
 * Replaces URL scheme navigation with direct native calls via @JavascriptInterface,
 * avoiding WebView navigation pipeline overhead.
 */
internal class AnkiJsBridge(
    private val webViewProvider: () -> WebView?,
) {
    var onAnkiLookup: ((Int, Int?, String?, String?, Boolean) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    @JavascriptInterface
    fun addToAnki(index: String, glossary: String, selectedDict: String, popupSelection: String) {
        val idx = index.toIntOrNull() ?: return
        val gloss = glossary.toIntOrNull()
        scope.launch {
            onAnkiLookup?.invoke(idx, gloss, selectedDict, popupSelection, false)
        }
    }

    @JavascriptInterface
    fun openInAnki(index: String, glossary: String, selectedDict: String, popupSelection: String) {
        val idx = index.toIntOrNull() ?: return
        val gloss = glossary.toIntOrNull()
        scope.launch {
            onAnkiLookup?.invoke(idx, gloss, selectedDict, popupSelection, true)
        }
    }
}

/** JavaScript bridge for copying a rendered dictionary translation. */
internal class DictionaryClipboardBridge(
    private val context: Context,
) {
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    @JavascriptInterface
    fun copyTranslation(content: String) {
        scope.launch {
            context.copyToClipboard("Dictionary translation", content)
        }
    }
}

/**
 * JavaScript bridge for word audio playback.
 */
internal class WordAudioBridge(
    private val context: Context,
    private val webViewProvider: () -> WebView?,
) {
    private val wordAudioService: WordAudioService by Injekt.injectLazy()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var currentAudioFd: ParcelFileDescriptor? = null

    @JavascriptInterface
    fun fetchAudio(term: String, reading: String, callbackId: String) {
        scope.launch {
            val results = wordAudioService.findWordAudio(term, reading)
            val jsonArray = org.json.JSONArray().apply {
                results.forEach { result ->
                    put(org.json.JSONObject().apply {
                        put("name", result.name)
                        put("url", result.url)
                    })
                }
            }.toString()
            webViewProvider()?.evaluateJavascript(
                "window.DictionaryRenderer && window.DictionaryRenderer.onAudioResults('$callbackId', $jsonArray);",
                null,
            )
        }
    }

    @JavascriptInterface
    fun playAudio(url: String) {
        scope.launch {
            try {
                stopAudio()
                val player = mediaPlayer ?: android.media.MediaPlayer().also { mediaPlayer = it }
                player.reset()
                if (url.startsWith("chimahon-local://")) {
                    val uri = Uri.parse(url)
                    val sourceId = uri.host ?: return@launch
                    val filePath = uri.path?.substring(1) ?: return@launch
                    val pfd = withContext(Dispatchers.IO) {
                        wordAudioService.getAudioDataFd(filePath, sourceId)
                    } ?: return@launch
                    currentAudioFd = pfd
                    player.setDataSource(pfd.fileDescriptor)
                } else {
                    player.setDataSource(url)
                }
                player.prepareAsync()
                player.setOnPreparedListener { it.start() }
                player.setOnCompletionListener { /* reuse on next call */ }
            } catch (e: Exception) {
                Log.e("WordAudioBridge", "Error playing audio: $url", e)
            }
        }
    }

    fun stopAudio() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.reset()
        } catch (_: Exception) { }
        try {
            currentAudioFd?.close()
        } catch (_: Exception) { }
        currentAudioFd = null
    }
}
