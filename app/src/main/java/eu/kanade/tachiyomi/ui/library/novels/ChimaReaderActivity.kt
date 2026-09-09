package eu.kanade.tachiyomi.ui.library.novels

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import chimahon.novel.data.BookStorage
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import chimahon.DictionaryRepository
import chimahon.ocr.OcrLanguage
import chimahon.ocr.OcrResult
import chimahon.novel.ui.reader.NovelReaderActivity
import eu.kanade.tachiyomi.data.ocr.recognizePage
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPopupWebViewWarmup
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryPaths
import eu.kanade.tachiyomi.ui.reader.viewer.OcrLookupPopup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * App-side subclass of [NovelReaderActivity] that wires text-selection events
 * from the EPUB reader WebView into the [OcrLookupPopup] — no screenshot needed,
 * no OCR bitmap involved: just a plain text selection → dictionary lookup.
 *
 * [NovelReaderActivity.activityClass] is pointed at this class from [AppModule]
 * so that `NovelReaderActivity.launch()` calls automatically land here.
 */
class ChimaReaderActivity : NovelReaderActivity() {

    private val readerPreferences: eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences by uy.kohesive.injekt.injectLazy()
    private var popupWebView: WebView? = null
    private val novelReaderSettings by lazy { chimahon.novel.data.NovelReaderSettings(this, getSettingsNamespace()) }

    private var cachedActiveProfile: chimahon.anki.AnkiProfile? = null
    private var cachedTermPaths: chimahon.DictionaryPaths? = null

    private fun getOrRefreshLookupPaths(): Pair<chimahon.anki.AnkiProfile, chimahon.DictionaryPaths> {
        val prefs = Injekt.get<DictionaryPreferences>()
        val novelId = bookMetadata?.id ?: ""
        val novelSourceId = bookMetadata?.novelSourceId ?: 0L
        val novelLang = bookMetadata?.lang?.takeIf { it.isNotBlank() }
            ?: sourceLangOf(novelSourceId)
        val profile = cachedActiveProfile ?: prefs.profileResolver.resolve(
            novelId = novelId,
            sourceId = novelSourceId,
            sourceLang = novelLang,
        ).also { cachedActiveProfile = it }
        val paths = cachedTermPaths ?: getDictionaryPaths(this, profile).also { cachedTermPaths = it }
        return profile to paths
    }

    /** Source lang fallback so empty-lang books still hit language match. */
    private fun sourceLangOf(sourceId: Long): String {
        if (sourceId == 0L) return ""
        return runCatching {
            Injekt.get<chimahon.novel.manager.NovelSourceManager>().getOrStub(sourceId).lang
        }.getOrNull().orEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = Injekt.get<DictionaryPreferences>()
        val path = intent.getStringExtra("extra_book_dir")
        if (!path.isNullOrEmpty()) {
            val root = java.io.File(path)
            if (root.exists() && root.isDirectory) {
                // Profile keys stay folder-based so existing overrides keep working.
                val novelId = intent.getLongExtra("extra_novel_id", -1L).takeIf { it >= 0L }
                val row = runBlocking(Dispatchers.IO) {
                    runCatching {
                        val repos = Injekt.get<tachiyomi.domain.novel.repository.NovelRepository>()
                        if (novelId != null) {
                            runCatching { repos.getNovelById(novelId) }.getOrNull()
                        } else {
                            runCatching { repos.getNovelByLocalFolder(root.name) }.getOrNull()
                        }
                    }.getOrNull()
                }
                if (row != null) {
                    // Novel override > source default > book lang, else
                    // source lang > global. Keys stay folder-based so
                    // existing overrides keep working.
                    val profile = prefs.profileResolver.resolve(
                        novelId = row.localFolder ?: row.id.toString(),
                        sourceId = row.source,
                        sourceLang = row.lang?.takeIf { it.isNotBlank() }
                            ?: sourceLangOf(row.source),
                    )
                    cachedActiveProfile = profile
                    cachedTermPaths = getDictionaryPaths(this, profile)
                }
            }
        }

        super.onCreate(savedInstanceState)

        val activeProfile = cachedActiveProfile ?: getOrRefreshLookupPaths().first
        val warmPaths = cachedTermPaths ?: getDictionaryPaths(this, activeProfile)
        cachedActiveProfile = activeProfile
        cachedTermPaths = warmPaths
        DictionaryPopupWebViewWarmup.warm(this, activeProfile.languageCode)

        lifecycleScope.launch(Dispatchers.Default) {
            Injekt.get<DictionaryRepository>().warmUp(warmPaths, activeProfile.id)
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                novelReaderSettings.verticalWriting.collect {
                    isVerticalWriting = it
                }
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                prefs.rawActiveProfileId().changes().collect {
                    cachedActiveProfile = null
                    cachedTermPaths = null
                }
            }
        }

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                prefs.rawProfiles().changes().collect {
                    cachedActiveProfile = null
                    cachedTermPaths = null
                }
            }
        }
    }

    override fun getSettingsNamespace(): String? {
        val profile = cachedActiveProfile ?: getOrRefreshLookupPaths().first
        return profile.id.takeIf { it.isNotEmpty() }
    }

    override fun getSelectionRectsCallback(): ((String) -> Unit) = { json ->
        runOnUiThread {
            pendingLookupRects.clear()
            var minX = Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxR = Double.MIN_VALUE
            var maxB = Double.MIN_VALUE
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val x = obj.getDouble("x")
                    val y = obj.getDouble("y")
                    val w = obj.getDouble("width")
                    val h = obj.getDouble("height")
                    pendingLookupRects.add(SelectionRect(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat()))
                    val r = x + w
                    val b = y + h
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (r > maxR) maxR = r
                    if (b > maxB) maxB = b
                }
                if (arr.length() > 0) {
                    lookupState = lookupState?.copy(
                        anchorX = minX.toFloat(),
                        anchorY = minY.toFloat(),
                        anchorWidth = (maxR - minX).toFloat(),
                        anchorHeight = (maxB - minY).toFloat(),
                    )
                }
            } catch (_: Exception) {}

            val elapsed = SystemClock.elapsedRealtime() - lookupStartTime
            if (elapsed > 0) {
                Log.i("DictionaryLookup", "rects_ms=$elapsed rects=${pendingLookupRects.size}")
            }

            if (pendingShowByRects) {
                pendingShowByRects = false
                cancelActiveLookup()
                popupVisible = true
                isPopupActive = true
            }
        }
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun ensurePopupWebView(): WebView {
        return popupWebView ?: DictionaryPopupWebViewWarmup.acquire(
            this,
            getOrRefreshLookupPaths().first.languageCode,
        ).also { popupWebView = it }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (isPopupActive) {
            popupWebView?.let { webView ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                            webView.evaluateJavascript("window.DictionaryRenderer?.navigate(-1);", null)
                            return true
                        }
                        android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                            webView.evaluateJavascript("window.DictionaryRenderer?.navigate(1);", null)
                            return true
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun handleVolumeKey(forward: Boolean): Boolean {
        if (!readerPreferences.readWithVolumeKeys().get()) {
            return false
        }
        
        val inverted = readerPreferences.readWithVolumeKeysInverted().get()
        val finalForward = if (inverted) !forward else forward
        return super.handleVolumeKey(finalForward)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (isPopupActive) return super.onKeyDown(keyCode, event)
        if (!readerPreferences.readWithVolumeKeys().get()) return super.onKeyDown(keyCode, event)
        
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> return true
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (isPopupActive) return super.onKeyUp(keyCode, event)
        if (!readerPreferences.readWithVolumeKeys().get()) return super.onKeyUp(keyCode, event)

        return super.onKeyUp(keyCode, event)
    }

    @Composable
    override fun AdditionalAppearanceSettings() {
        val volumeKeys by remember { readerPreferences.readWithVolumeKeys().changes() }.collectAsState(readerPreferences.readWithVolumeKeys().get())
        val volumeKeysInverted by remember { readerPreferences.readWithVolumeKeysInverted().changes() }.collectAsState(readerPreferences.readWithVolumeKeysInverted().get())

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Navigation", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Use volume keys to navigate", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = volumeKeys,
                    onCheckedChange = { readerPreferences.readWithVolumeKeys().set(it) }
                )
            }

            if (volumeKeys) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Invert volume keys", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = volumeKeysInverted,
                        onCheckedChange = { readerPreferences.readWithVolumeKeysInverted().set(it) }
                    )
                }
            }
        }
    }

    /**
     * Backing state for the lookup popup. Using Activity-level `mutableStateOf`
     * (not inside a composable) means it survives re-compositions correctly and
     * can be set from the non-Composable [onLookupRequested] override.
     */
    private var lookupState by mutableStateOf<LookupState?>(null)
    private var popupVisible by mutableStateOf(false)
    private var isVerticalWriting = true

    /** Selection rects received from JS for Compose highlight overlay. */
    private data class SelectionRect(val x: Float, val y: Float, val width: Float, val height: Float)
    private val selectionRects = mutableStateListOf<SelectionRect>()

    /** Pending rects — applied to [selectionRects] only when popup content is ready. */
    private val pendingLookupRects = mutableStateListOf<SelectionRect>()

    private data class LookupState(
        val word: String,
        val sentence: String,
        val anchorX: Float,
        val anchorY: Float,
        val anchorWidth: Float,
        val anchorHeight: Float,
        val isVertical: Boolean,
        val activeProfile: chimahon.anki.AnkiProfile,
        val screenshot: Bitmap? = null,
        val sentenceOffset: Int = 0,
    )

    private var lookupDeferred: kotlinx.coroutines.Deferred<chimahon.DictionaryRepository.LookupResult2>? = null
    private var pendingShowByRects = false
    private var lookupStartTime: Long = 0L

    /** Called by [NovelReaderActivity] whenever the user selects text in the WebView. */
    override fun onLookupRequested(word: String, sentence: String, x: Float, y: Float, w: Float, h: Float) {
        startLookup(word, sentence, x, y, w, h, isVerticalWriting, screenshot = null, resolveAnchorFromWebView = true)
    }

    override suspend fun recognizeImage(bitmap: Bitmap, language: OcrLanguage): List<OcrResult> {
        val profileLanguage = getOrRefreshLookupPaths().first.languageCode
        val resolvedLanguage = OcrLanguage.entries.firstOrNull {
            it.bcp47.equals(profileLanguage, ignoreCase = true) ||
                profileLanguage.startsWith("${it.bcp47}-", ignoreCase = true)
        } ?: language
        return recognizePage(bitmap, resolvedLanguage)
    }

    override fun onImageOcrLookupRequested(
        word: String,
        sentence: String,
        sentenceOffset: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        vertical: Boolean,
        bitmap: Bitmap,
    ) {
        startLookup(
            word, sentence, x, y, w, h, vertical,
            screenshot = bitmap,
            resolveAnchorFromWebView = false,
            sentenceOffset = sentenceOffset,
        )
    }

    private fun startLookup(
        word: String,
        sentence: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        vertical: Boolean,
        screenshot: Bitmap?,
        resolveAnchorFromWebView: Boolean,
        sentenceOffset: Int = sentence.indexOf(word).coerceAtLeast(0),
    ) {
        val (profile, termPaths) = getOrRefreshLookupPaths()
        cancelActiveLookup()
        pendingShowByRects = false
        selectionRects.clear()
        lookupStartTime = SystemClock.elapsedRealtime()
        pendingLookupRects.clear()
        lookupDeferred = lifecycleScope.async(Dispatchers.Default) {
            Injekt.get<DictionaryRepository>().lookup(word.trim(), termPaths, profile.languageCode)
        }
        // Set preliminary state with tap coordinates; refined to exact
        // character position when rects arrive from JS (match path).
        lookupState = LookupState(word, sentence, x, y, w, h, vertical, profile, screenshot, sentenceOffset)

        lifecycleScope.launch(Dispatchers.Default) {
            val result = try { lookupDeferred?.await() } catch (_: Exception) { null }
            val firstMatched = result?.results?.firstOrNull()?.matched
            if (firstMatched != null && resolveAnchorFromWebView) {
                val matchOffset = word.indexOf(firstMatched).coerceAtLeast(0)
                val charCount = firstMatched.codePointCount(0, firstMatched.length)
                withContext(Dispatchers.Main) {
                    pendingShowByRects = true
                    readerViewModel?.bridge?.send(chimahon.novel.ui.reader.WebViewCommand.GetSelectionRects(charCount, matchOffset))
                }
            } else {
                withContext(Dispatchers.Main) {
                    lookupState = lookupState?.copy(anchorX = x, anchorY = y, anchorWidth = w, anchorHeight = h)
                    popupVisible = true
                    isPopupActive = true
                }
            }
        }
    }

    /** Legacy bridge hook; current reader JS sends the sentence with onLookupRequested. */
    override fun onSentenceReady(sentence: String) {
        val current = lookupState ?: return
        if (current.sentence.isBlank() && sentence.isNotBlank()) {
            lookupState = current.copy(sentence = sentence)
        }
    }

    override fun onDismissPopupRequested() {
        super.onDismissPopupRequested()
        popupVisible = false
        selectionRects.clear()
        pendingLookupRects.clear()
        cancelActiveLookup()
        isPopupActive = false
    }

    private fun cancelActiveLookup() {
        lookupDeferred?.cancel()
        lookupDeferred = null
    }

    /** Apply pending rects to highlight only when popup content is ready. */
    private fun onPopupContentReady(ready: Boolean) {
        if (ready && pendingLookupRects.isNotEmpty()) {
            val elapsed = SystemClock.elapsedRealtime() - lookupStartTime
            Log.i("DictionaryLookup", "pipeline_ms=$elapsed")
            selectionRects.clear()
            selectionRects.addAll(pendingLookupRects)
            pendingLookupRects.clear()
        }
    }

    /**
     * Injected into the parent's `setContent {}` via the [PopupOverlay] hook.
     * Renders the lookup popup over the reader content when text is selected.
     * No screenshot / crop — those are OCR-only features.
     *
     * The popup mounts only after the first lookup. The WebView shell itself is
     * warmed outside the reader hierarchy by [DictionaryPopupWebViewWarmup].
     */
    @Composable
    override fun PopupOverlay() {
        val repo = remember { Injekt.get<DictionaryRepository>() }

        BackHandler(enabled = popupVisible) {
            popupVisible = false
            selectionRects.clear()
            pendingLookupRects.clear()
            cancelActiveLookup()
            isPopupActive = false
        }

        val mediaInfo = readerViewModel?.let { vm ->
            chimahon.MediaInfo(
                mangaTitle = vm.document.title ?: "",
                chapterName = vm.getCurrentChapterTitle() ?: ""
            )
        }
        eu.kanade.presentation.theme.TachiyomiTheme {
            SelectionHighlightOverlay()
            val state = lookupState
            if (state != null || popupVisible) {
                val popupState = state ?: LookupState("", "", 0f, 0f, 0f, 0f, isVerticalWriting, chimahon.anki.AnkiProfile.EMPTY)
                val webView = remember { ensurePopupWebView() }

                OcrLookupPopup(
                    visible = popupVisible,
                    lookupString = if (popupVisible) popupState.word else "",
                    fullText = popupState.sentence,
                    charOffset = popupState.sentenceOffset,
                    onDismiss = {
                        popupVisible = false
                        selectionRects.clear()
                        pendingLookupRects.clear()
                        cancelActiveLookup()
                        isPopupActive = false
                    },
                    webView = webView,
                    repository = repo,
                    anchorX = popupState.anchorX,
                    anchorY = popupState.anchorY,
                    anchorWidth = popupState.anchorWidth,
                    anchorHeight = popupState.anchorHeight,
                    isVertical = popupState.isVertical,
                    activeProfile = if (popupVisible) getOrRefreshLookupPaths().first else popupState.activeProfile,
                    type = "novel",
                    mediaInfo = mediaInfo,
                    onRequestScreenshot = if (popupState.screenshot != null) {
                        { popupState.screenshot }
                    } else {
                        null
                    },
                    onCropTriggered = null,
                    initialLookupDeferred = if (popupVisible) lookupDeferred else null,
                    usePopup = false,
                    // Novel reader: rects already sent by onLookupRequested; no OCR block to refine
                    onTermMatched = null,
                    onContentReadyChange = ::onPopupContentReady,
                    // The novel SSIV owns outside-tap routing so another OCR word can be
                    // selected while this popup is already visible.
                    dismissOnOutsideTap = false,
                    modifier = Modifier,
                    titleId = bookMetadata?.id,
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelActiveLookup()
        DictionaryPopupWebViewWarmup.recycle(this, popupWebView)
        popupWebView = null
        lookupState = null
        selectionRects.clear()
        pendingLookupRects.clear()
    }

    /**
     * Compose overlay that paints selection highlights from JS geometry.
     */
    @Composable
    private fun SelectionHighlightOverlay() {
        if (selectionRects.isEmpty()) return
        val rects = selectionRects.toList()
        val highlightColor = Color(0x66A0A0A0)

        Canvas(modifier = Modifier.fillMaxSize()) {
            for (rect in rects) {
                drawRect(
                    color = highlightColor,
                    topLeft = Offset(rect.x, rect.y),
                    size = Size(rect.width, rect.height),
                )
            }
        }
    }
}
