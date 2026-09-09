package chimahon.novel.ui.reader

import android.R.attr.overScrollMode
import android.R.attr.visibility
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.common.io.Files.append
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File

@Composable
fun ReaderWebView(
    modifier: Modifier = Modifier,
    bridge: WebViewBridge,
    continuousMode: Boolean = false,
    isImageOnly: Boolean = false,
    readerSettings: ReaderSettings = ReaderSettings(),
    focusMode: Boolean = false,
    onNextChapter: () -> Boolean,
    onPreviousChapter: () -> Boolean,
    onProgressChanged: (Double) -> Unit,
    onLoadFailed: (String) -> Unit,
    onTap: () -> Unit = {},
    onTapTop: () -> Unit = {},
    onTapBottom: () -> Unit = {},
    swipeThreshold: Int = 96,
    tapZonePx: Int = 100,
    isPopupActive: Boolean = false,
    onTextSelected: (word: String, sentence: String, x: Float, y: Float, w: Float, h: Float) -> Unit = { _, _, _, _, _, _ -> },
    onSentenceReady: (sentence: String) -> Unit = {},
    onDismissPopupRequested: () -> Unit = {},
    onInternalLinkClicked: (url: String) -> Unit = {},
    onSelectionRectsReceived: ((String) -> Unit)? = null,
    onPageTurned: () -> Unit = {},
    onScrollMoved: () -> Unit = {},
    onImageTapped: (imageUrl: String) -> Unit = {},
) {
    val pendingCommands = remember(bridge) { bridge.pendingCommands }

    LaunchedEffect(bridge.chapterUrl) {
        val chapterUrl = bridge.chapterUrl ?: return@LaunchedEffect
        if (pendingCommands.isEmpty()) {
            bridge.send(WebViewCommand.LoadChapter(chapterUrl, bridge.progress))
        }
    }

    val isFirstContinuous = remember { mutableStateOf(true) }
    LaunchedEffect(continuousMode) {
        if (isFirstContinuous.value) {
            isFirstContinuous.value = false
            return@LaunchedEffect
        }
        bridge.chapterUrl?.let { url ->
            bridge.send(WebViewCommand.LoadChapter(url, bridge.progress))
        }
    }

    LaunchedEffect(focusMode) {
        bridge.send(WebViewCommand.ChangeFocusMode(focusMode))
    }

    val isFirstComposition = remember { mutableStateOf(true) }
    LaunchedEffect(readerSettings) {
        if (isFirstComposition.value) {
            isFirstComposition.value = false
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(100)
        bridge.send(WebViewCommand.ApplySettings(readerSettings))
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            ReaderAndroidWebView(
                context = context,
                readerJs = loadAssetText(context, "novel/reader.js"),
                continuousMode = continuousMode,
                isImageOnly = isImageOnly,
                readerSettings = readerSettings,
                focusMode = focusMode,
                onNextChapter = onNextChapter,
                onPreviousChapter = onPreviousChapter,
                onProgressChanged = onProgressChanged,
                onLoadFailed = onLoadFailed,
                onTap = onTap,
                onTapTop = { if (!isPopupActive) onTapTop() },
                onTapBottom = { if (!isPopupActive) onTapBottom() },
                isPopupActive = isPopupActive,
                onTextSelectedCallback = onTextSelected,
                onSentenceReadyCallback = onSentenceReady,
                onDismissPopupRequested = onDismissPopupRequested,
                onInternalLinkClicked = onInternalLinkClicked,
                onPageTurned = onPageTurned,
                onScrollMoved = onScrollMoved,
                onImageTappedCallback = onImageTapped,
            ).apply {
                setSelectionRectsCallback(onSelectionRectsReceived)
                settings.allowFileAccess = true
                settings.allowContentAccess = true
                settings.allowFileAccessFromFileURLs = true
                settings.allowUniversalAccessFromFileURLs = true
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.loadWithOverviewMode = false
                settings.useWideViewPort = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.d("HoshiReader", "${message.message()} [line ${message.lineNumber()}]")
                        return true
                    }
                }
                webViewClient = object : WebViewClient() {

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val resourceRequest = request ?: return null
                        if (!resourceRequest.method.equals("GET", ignoreCase = true)) return null

                        val uri = resourceRequest.url
                        if (uri.scheme != "file" || !uri.path.orEmpty().endsWith(".css", ignoreCase = true)) {
                            return null
                        }

                        val cssFile = File(uri.path ?: return null)
                        if (!cssFile.isFile) return null

                        return runCatching {
                            val sanitized = sanitizeReaderResource("text/css", cssFile.readBytes())
                            WebResourceResponse(
                                "text/css",
                                "UTF-8",
                                sanitized.inputStream(),
                            )
                        }.onFailure {
                            Log.w("ReaderWebView", "Failed to sanitize stylesheet ${cssFile.name}", it)
                        }.getOrNull()
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        val currentFile = currentUrl
                            ?.removePrefix("file://")
                            ?.substringBefore("#")
                        val targetFile = url
                            .removePrefix("file://")
                            .substringBefore("#")
                        val fragment = url.substringAfter("#", missingDelimiterValue = "")

                        Log.d("ReaderWebView", "shouldOverrideUrlLoading: url=$url currentFile=$currentFile targetFile=$targetFile fragment=$fragment")

                        if (currentFile != null && currentFile == targetFile) {
                            // Same chapter file – just scroll to the fragment via JS (no reload)
                            if (fragment.isNotEmpty()) {
                                val escapedId = fragment.replace("\\", "\\\\").replace("'", "\\'")
                                val js = """
                                    (function() {
                                        var el = document.getElementById('$escapedId')
                                            || document.querySelector('[name="$escapedId"]');
                                        if (el) el.scrollIntoView({ behavior: 'instant', block: 'start' });
                                    })();
                                """.trimIndent()
                                post { evaluateJavascript(js, null) }
                            }
                            return true // always intercept – no full reload
                        }

                        // Different chapter file (or external URL) – hand off to ViewModel
                        post { onInternalLinkClicked(url) }
                        return true
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        Log.d("ReaderWebView", "onPageStarted: url=$url")
                        alpha = 0f
                        visibility = View.VISIBLE
                        lastLoadedUrl = null
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.d("ReaderWebView", "onPageFinished: url=$url, size=${view?.width}x${view?.height}")
                        injectReader()
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: android.webkit.RenderProcessGoneDetail?,
                    ): Boolean {
                        val reason = if (detail?.didCrash() == true) {
                            "WebView crashed"
                        } else {
                            "WebView killed by system (OOM)"
                        }
                        Log.e("ReaderWebView", "onRenderProcessGone: $reason")
                        post {
                            onLoadFailed("Renderer died ($reason). Try disabling hardware acceleration or 'Avoid page breaks'.")
                        }
                        return true
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        Log.e("ReaderWebView", "onReceivedError: url=${request?.url}, error=$error")
                        if (request?.isForMainFrame == true) {
                            onLoadFailed(error?.description?.toString() ?: "Failed to load chapter")
                        }
                    }
                }
            }
        },
        update = { androidWebView ->
            val v = androidWebView as ReaderAndroidWebView
            v.isImageOnly = isImageOnly
            v.continuousMode = continuousMode
            v.readerSettings = readerSettings
            v.focusMode = focusMode
            v.isPopupActive = isPopupActive
            v.setSelectionRectsCallback(onSelectionRectsReceived)
            v.setBackgroundColor(readerSettings.backgroundColor)

            if (pendingCommands.isEmpty()) return@AndroidView

            val commands = pendingCommands.toList()
            pendingCommands.clear()

            val deduped = buildList {
                var lastLoadChapter: WebViewCommand? = null
                for (cmd in commands) {
                    if (cmd is WebViewCommand.LoadChapter || cmd is WebViewCommand.LoadChapterHtml) {
                        lastLoadChapter = cmd
                    } else {
                        lastLoadChapter?.let { add(it) }
                        lastLoadChapter = null
                        add(cmd)
                    }
                }
                lastLoadChapter?.let { add(it) }
            }

            deduped.forEach { command ->
                when (command) {
                    is WebViewCommand.LoadChapter -> {
                        v.pendingProgress = command.progress
                        v.currentUrl = command.url
                        v.loadChapter(command.url)
                    }
                    is WebViewCommand.LoadChapterHtml -> {
                        v.pendingProgress = command.progress
                        v.currentUrl = command.url
                        v.loadChapterHtml(command.url, command.html)
                    }
                    is WebViewCommand.ChangeMode -> {
                        if (v.currentUrl != null) {
                            v.continuousMode = command.continuous
                            v.loadChapter(v.currentUrl!!)
                        }
                    }
                    is WebViewCommand.ApplySettings -> {
                        val old = v.lastAppliedSettings
                        val new = command.settings
                        v.readerSettings = new
                        v.lastAppliedSettings = new

                        if (old.avoidPageBreak != new.avoidPageBreak ||
                            old.verticalWriting != new.verticalWriting
                        ) {
                            v.injectReader()
                        } else {
                            v.applySettings(new)
                        }
                    }
                    is WebViewCommand.ChangeFocusMode -> {
                        v.focusMode = command.focusMode
                    }
                    is WebViewCommand.Paginate -> {
                        v.paginate(command.forward)
                    }
                    is WebViewCommand.JumpToFragment -> {
                        v.jumpToFragment(command.fragment)
                    }
                    is WebViewCommand.ClearSelection -> {
                        v.evaluateJavascript("if(window.hoshiReader && window.hoshiReader.clearSelection) { window.hoshiReader.clearSelection(); }", null)
                    }
                    is WebViewCommand.HighlightSelection -> {
                        v.evaluateJavascript("if(window.hoshiReader && window.hoshiReader.highlightSelection) { window.hoshiReader.highlightSelection(${command.charCount}); }", null)
                    }
                    is WebViewCommand.GetSelectionRects -> {
                        v.evaluateJavascript("(function() { try { return window.hoshiReader.getSelectionRects(${command.charCount}, ${command.startOffset}); } catch(e) { return []; } })()") { result ->
                            val json = result ?: "[]"
                            try {
                                val loc = IntArray(2)
                                v.getLocationOnScreen(loc)
                                val scale = v.scale.coerceAtLeast(0.1f)
                                val arr = org.json.JSONArray(json)
                                for (i in 0 until arr.length()) {
                                    val obj = arr.getJSONObject(i)
                                    obj.put("x", obj.getDouble("x") * scale + loc[0])
                                    obj.put("y", obj.getDouble("y") * scale + loc[1])
                                    obj.put("width", obj.getDouble("width") * scale)
                                    obj.put("height", obj.getDouble("height") * scale)
                                }
                                onSelectionRectsReceived?.invoke(arr.toString())
                            } catch (_: Exception) {
                                onSelectionRectsReceived?.invoke(json)
                            }
                        }
                    }
                    else -> {}
                }
            }
        },
    )
}

private class ReaderAndroidWebView(
    context: Context,
    private val readerJs: String,
    var continuousMode: Boolean = false,
    var isImageOnly: Boolean = false,
    var readerSettings: ReaderSettings = ReaderSettings(),
    var focusMode: Boolean = false,
    private val onNextChapter: () -> Boolean,
    private val onPreviousChapter: () -> Boolean,
    private val onProgressChanged: (Double) -> Unit,
    private val onLoadFailed: (String) -> Unit,
    private val onTap: () -> Unit = {},
    private val onTapTop: () -> Unit = {},
    private val onTapBottom: () -> Unit = {},
    private val swipeThreshold: Int = 96,
    private val tapZonePx: Int = 100,
    var isPopupActive: Boolean = false,
    private val onTextSelectedCallback: (word: String, sentence: String, x: Float, y: Float, w: Float, h: Float) -> Unit = { _, _, _, _, _, _ -> },
    private val onSentenceReadyCallback: (sentence: String) -> Unit = {},
    private val onDismissPopupRequested: () -> Unit = {},
    internal val onInternalLinkClicked: (url: String) -> Unit = {},
    private val onPageTurned: () -> Unit = {},
    private val onScrollMoved: () -> Unit = {},
    private val onImageTappedCallback: (imageUrl: String) -> Unit = {},
) : WebView(context) {

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var totalMovement = 0f

    var currentUrl: String? = null
    var pendingProgress: Double = 0.0
    var lastAppliedSettings: ReaderSettings = readerSettings

    internal var lastLoadedUrl: String? = null
    internal var lastLoadedWidth: Int = -1
    internal var lastLoadedHeight: Int = -1
    internal var lastLoadedVerticalWriting: Boolean? = null

    private var restoreEpoch: Int = 0

    private var lastProgressReportTime = 0L
    private val reportProgressRunnable = Runnable {
        if (continuousMode && !isImageOnly) {
            evaluateJavascript("(function() { return window.hoshiReader.calculateProgress(); })()") { p ->
                p?.trim()?.trim('"')?.toDoubleOrNull()?.let {
                    pendingProgress = it
                    onProgressChanged(it)
                }
            }
        }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)

        if (isPopupActive) {
            onDismissPopupRequested()
        }

        if (t != oldt) {
            onScrollMoved()
        }

        if (continuousMode && !isImageOnly) {
            val now = System.currentTimeMillis()
            if (now - lastProgressReportTime > 1000L) {
                lastProgressReportTime = now
                removeCallbacks(reportProgressRunnable)
                post(reportProgressRunnable)
            } else {
                removeCallbacks(reportProgressRunnable)
                postDelayed(reportProgressRunnable, 1000L)
            }
        }
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                val start = e1 ?: return false
                val deltaX = e2.x - start.x
                val deltaY = e2.y - start.y
                val isVerticalSwipe = kotlin.math.abs(deltaY) > kotlin.math.abs(deltaX)
                val expectsVerticalSwipe = continuousMode && !readerSettings.verticalWriting
                if (isVerticalSwipe != expectsVerticalSwipe) return false

                val primaryDelta = if (isVerticalSwipe) deltaY else deltaX
                val primaryVelocity = if (isVerticalSwipe) velocityY else velocityX
                if (kotlin.math.abs(primaryDelta) < swipeThreshold.toFloat()) return false
                if (kotlin.math.abs(primaryVelocity) < 400f) return false

                return if (expectsVerticalSwipe) {
                    handleSwipe(forward = deltaY < 0f)
                } else if (readerSettings.verticalWriting) {
                    // Vertical RTL: swipe right → forward
                    handleSwipe(forward = deltaX > 0f)
                } else {
                    // Horizontal LTR: swipe left → forward
                    handleSwipe(forward = deltaX < 0f)
                }
            }
        },
    )

    private val jsBridge = ReaderJavascriptBridge(
        onRestoreCompleted = { epoch ->
            post {
                if (epoch == restoreEpoch || epoch == 0) {
                    alpha = 0f
                    visibility = View.VISIBLE
                    animate().cancel()
                    animate().alpha(1f).setDuration(160).start()
                }
            }
        },
        onTextSelectedCallback = { word, sentence, x, y, w, h ->
            post {
                val density = context.resources.displayMetrics.density
                val loc = IntArray(2)
                getLocationOnScreen(loc)
                onTextSelectedCallback(word, sentence, x * density + loc[0], y * density + loc[1], w * density, h * density)
            }
        },
        onBackgroundTap = { x, y ->
            post {
                if (isPopupActive) {
                    onDismissPopupRequested()
                } else {
                    val density = context.resources.displayMetrics.density
                    val eventX = x * density
                    val eventY = y * density
                    when {
                        eventY < tapZonePx || eventY > height - tapZonePx -> onTapTop()
                        eventX < width * (readerSettings.tapZonePercent / 100f) ->
                            handleSwipe(forward = readerSettings.verticalWriting)
                        eventX > width * (1f - readerSettings.tapZonePercent / 100f) ->
                            handleSwipe(forward = !readerSettings.verticalWriting)
                    }
                }
            }
        },
        onSentenceReadyCallback = { sentence ->
            post { onSentenceReadyCallback(sentence) }
        },
        onImageTappedCallback = { src ->
            post { onImageTappedCallback(src) }
        },
    )

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        addJavascriptInterface(jsBridge, "ReaderAndroid")
        setBackgroundColor(readerSettings.backgroundColor)
    }

    /** Set callback for selection rects from JS (used by Compose highlight overlay). */
    fun setSelectionRectsCallback(callback: ((String) -> Unit)?) {
        jsBridge.onSelectionRectsCallback = callback
    }

    fun loadChapter(url: String) {
        Log.d("ReaderWebView", "loadChapter: url=$url size=${width}x$height")
        currentUrl = url

        if (width <= 0 || height <= 0) {
            postDelayed({ loadChapter(url) }, 100L)
            return
        }

        val vw = readerSettings.verticalWriting
        if (url == lastLoadedUrl &&
            width == lastLoadedWidth &&
            height == lastLoadedHeight &&
            vw == lastLoadedVerticalWriting
        ) {
            Log.d("ReaderWebView", "loadChapter skipped: duplicate")
            return
        }
        lastLoadedUrl = url
        lastLoadedWidth = width
        lastLoadedHeight = height
        lastLoadedVerticalWriting = vw

        visibility = View.INVISIBLE

        try {
            if (url.startsWith("file://") || !url.contains("://")) {
                val file = File(url.removePrefix("file://"))
                if (file.exists()) {
                    // Content loads as a string; the base URL keeps relative resources
                    // resolving and the history URL keeps navigation identity file-based.
                    val html = file.readText()
                    val baseUrl = file.parentFile?.toURI()?.toString()
                    loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", url)
                } else {
                    Log.e("ReaderWebView", "File not found: $url")
                    onLoadFailed("File not found: $url")
                }
            } else {
                loadUrl(url)
            }
        } catch (e: Exception) {
            Log.e("ReaderWebView", "loadChapter error", e)
            onLoadFailed(e.message ?: "Failed to load chapter")
        }
    }

    fun loadChapterHtml(url: String, html: String) {
        Log.d("ReaderWebView", "loadChapterHtml: url=$url size=${width}x$height len=${html.length}")
        currentUrl = url

        if (width <= 0 || height <= 0) {
            postDelayed({ loadChapterHtml(url, html) }, 100L)
            return
        }

        val vw = readerSettings.verticalWriting
        if (url == lastLoadedUrl &&
            width == lastLoadedWidth &&
            height == lastLoadedHeight &&
            vw == lastLoadedVerticalWriting
        ) {
            Log.d("ReaderWebView", "loadChapterHtml skipped: duplicate")
            return
        }
        lastLoadedUrl = url
        lastLoadedWidth = width
        lastLoadedHeight = height
        lastLoadedVerticalWriting = vw

        visibility = View.INVISIBLE

        try {
            val baseUrl = if (url.startsWith("file://") || !url.contains("://")) {
                File(url.removePrefix("file://")).parentFile?.toURI()?.toString()
            } else {
                null
            }
            loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", url)
        } catch (e: Exception) {
            Log.e("ReaderWebView", "loadChapterHtml error", e)
            onLoadFailed(e.message ?: "Failed to load chapter")
        }
    }

    fun jumpToFragment(fragment: String) {
        val escapedId = fragment.replace("\\", "\\\\").replace("'", "\\'")
        val js = """
            (function() {
                var el = document.getElementById('$escapedId')
                    || document.querySelector('[name="$escapedId"]');
                if (el) el.scrollIntoView({ behavior: 'instant', block: 'start' });
            })();
        """.trimIndent()
        evaluateJavascript(js, null)
    }

    private fun buildBaseCSS(): String = buildString {
        val vw = readerSettings.verticalWriting

        if (vw) {
            appendLine("html, body { writing-mode: vertical-rl !important; }")
        } else {
            appendLine("html, body { writing-mode: horizontal-tb !important; }")
        }

        appendLine("html, body { margin: 0 !important; padding: 0 !important; }")
        appendLine("html { -webkit-line-box-contain: block glyphs replaced; }")
        appendLine("::highlight(hoshi-selection) { background-color: rgba(130, 150, 200, 0.4); color: inherit; }")
        appendLine("p { margin-block-start: 0 !important; margin-block-end: ${readerSettings.paragraphSpacing}em !important; }")
        appendLine("body * { font-family: inherit !important; }")
        appendLine("img.block-img, svg.block-img { position: static !important; }")
    }

    private fun paragraphSpacingJS(settings: ReaderSettings): String = """
        var paragraphStyle = document.getElementById('hoshi-paragraph-spacing-style');
        if (!paragraphStyle) {
            paragraphStyle = document.createElement('style');
            paragraphStyle.id = 'hoshi-paragraph-spacing-style';
            document.head.appendChild(paragraphStyle);
        }
        paragraphStyle.textContent = 'p { margin-block-start: 0 !important; margin-block-end: ${settings.paragraphSpacing}em !important; }';
    """.trimIndent()

    fun injectReader() {
        Log.d("ReaderWebView", "injectReader: ${width}x$height continuous=$continuousMode imageOnly=$isImageOnly")

        if (height <= 0 || width <= 0) {
            post { injectReader() }
            return
        }

        restoreEpoch++
        val script = when {
            isImageOnly -> buildImageOnlyScript()
            continuousMode -> buildContinuousScript()
            else -> buildPagedScript()
        }
        evaluateJavascript(script, null)
    }

    private fun buildImageOnlyScript(): String {
        val epoch = restoreEpoch
        val bg = readerSettings.resolvedBgHex()
        return """
            (function() {
                window.__readerRestoreEpoch = $epoch;

                var vp = document.querySelector('meta[name="viewport"]');
                if (vp) vp.remove();
                var nvp = document.createElement('meta');
                nvp.name = 'viewport';
                nvp.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                document.head.appendChild(nvp);

                window.hoshiReader = {
                    handleTap: function(clientX, clientY) {
                        var img = document.querySelector('img, svg');
                        if (img && window.ReaderAndroid && window.ReaderAndroid.onImageTapped) {
                            var rect = img.getBoundingClientRect();
                            var contentWidth = rect.width;
                            var contentHeight = rect.height;
                            var naturalWidth = img.naturalWidth || 0;
                            var naturalHeight = img.naturalHeight || 0;
                            if ((!naturalWidth || !naturalHeight) && img.tagName.toLowerCase() === 'svg') {
                                var viewBox = img.viewBox && img.viewBox.baseVal;
                                naturalWidth = viewBox && viewBox.width || 0;
                                naturalHeight = viewBox && viewBox.height || 0;
                            }
                            if (naturalWidth > 0 && naturalHeight > 0) {
                                var scale = Math.min(rect.width / naturalWidth, rect.height / naturalHeight);
                                contentWidth = naturalWidth * scale;
                                contentHeight = naturalHeight * scale;
                            }
                            var contentLeft = rect.left + (rect.width - contentWidth) / 2;
                            var contentTop = rect.top + (rect.height - contentHeight) / 2;
                            var tappedImage = clientX >= contentLeft && clientX <= contentLeft + contentWidth &&
                                clientY >= contentTop && clientY <= contentTop + contentHeight;
                            if (tappedImage) {
                                var src = img.currentSrc || img.getAttribute('src') ||
                                    img.getAttribute('data-src') || img.getAttribute('data-original') || '';
                                if (!src && img.tagName.toLowerCase() === 'svg') {
                                    var nested = img.querySelector('image');
                                    if (nested) {
                                        src = nested.getAttribute('href') ||
                                            nested.getAttributeNS('http://www.w3.org/1999/xlink', 'href') ||
                                            nested.getAttribute('xlink:href') || '';
                                    }
                                }
                                if (src) {
                                    try { src = new URL(src, document.baseURI).href; } catch (e) {}
                                    window.ReaderAndroid.onImageTapped(src);
                                    return true;
                                }
                            }
                        }
                        if (window.ReaderAndroid && window.ReaderAndroid.onBackgroundTap)
                            window.ReaderAndroid.onBackgroundTap(clientX, clientY);
                        return false;
                    },
                    paginate: function(direction) {
                        return 'limit';
                    },
                    calculateProgress: function() {
                        return 0;
                    }
                };

                var w = window.innerWidth;
                var h = window.innerHeight;

                document.documentElement.style.cssText =
                    'margin:0!important;padding:0!important;' +
                    'width:' + w + 'px!important;height:' + h + 'px!important;' +
                    'overflow:hidden!important;background:$bg!important;';

                function notifyComplete() {
                    requestAnimationFrame(function() {
                        requestAnimationFrame(function() {
                            if (window.ReaderAndroid) window.ReaderAndroid.restoreCompleted(window.__readerRestoreEpoch);
                        });
                    });
                }

                if (!document.body) {
                    notifyComplete();
                    return;
                }

                var target = document.querySelector('img, svg');
                if (!target) {
                    notifyComplete();
                    return;
                }

                Array.from(document.body.children).forEach(function(child) {
                    if (!child.contains(target)) child.style.display = 'none';
                });

                document.body.style.cssText =
                    'margin:0!important;padding:0!important;' +
                    'width:' + w + 'px!important;height:' + h + 'px!important;' +
                    'display:flex!important;align-items:center!important;justify-content:center!important;' +
                    'background:$bg!important;touch-action:none!important;overflow:hidden!important;';

                var curr = target.parentElement;
                while (curr && curr !== document.body) {
                    curr.style.cssText =
                        'display:flex!important;align-items:center!important;justify-content:center!important;' +
                        'margin:0!important;padding:0!important;border:none!important;' +
                        'width:' + w + 'px!important;height:' + h + 'px!important;overflow:hidden!important;';
                    curr = curr.parentElement;
                }

                var imgStyle =
                    'width:' + w + 'px!important;height:' + h + 'px!important;' +
                    'max-width:' + w + 'px!important;max-height:' + h + 'px!important;' +
                    'object-fit:contain!important;display:block!important;margin:auto!important;padding:0!important;';

                if (target.tagName.toLowerCase() === 'svg') {
                    target.setAttribute('preserveAspectRatio', 'xMidYMid meet');
                    target.style.cssText = imgStyle;
                    notifyComplete();
                } else {
                    target.style.cssText = imgStyle;
                    if (target.complete) {
                        if (target.naturalWidth > 0) {
                            notifyComplete();
                        } else {
                            notifyComplete();
                        }
                    } else {
                        target.onload  = function() { notifyComplete(); };
                        target.onerror = function() { notifyComplete(); };
                    }
                }
            })();
        """.trimIndent()
    }

    private fun buildContinuousScript(): String {
        val epoch = restoreEpoch
        val vw = readerSettings.verticalWriting
        val css = buildBaseCSS()
        val bg = readerSettings.resolvedBgHex()
        val tc = readerSettings.resolvedTextHex()

        return """
            (function() {
                window.__readerRestoreEpoch = $epoch;

                window.webkit = window.webkit || {};
                window.webkit.messageHandlers = window.webkit.messageHandlers || {};
                window.webkit.messageHandlers.restoreCompleted = {
                    postMessage: function(_) {
                        if (window.ReaderAndroid) window.ReaderAndroid.restoreCompleted(window.__readerRestoreEpoch);
                    }
                };

                var vp = document.querySelector('meta[name="viewport"]');
                if (vp) vp.remove();
                var nvp = document.createElement('meta');
                nvp.name = 'viewport';
                nvp.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                document.head.appendChild(nvp);

                var ih = window.innerHeight;
                var iw = window.innerWidth;

                var hPad = Math.round(iw * ${readerSettings.horizontalPadding} / 200);
                var vPad = Math.round(ih * ${readerSettings.verticalPadding} / 200);

                var contentW = Math.max(1, Math.round(iw * (100 - ${readerSettings.horizontalPadding}) / 100));
                var contentH = Math.max(1, Math.round(ih * (100 - ${readerSettings.verticalPadding}) / 100));
                document.documentElement.style.setProperty('--reader-image-max-width', contentW + 'px');
                document.documentElement.style.setProperty('--reader-image-max-height', contentH + 'px');

                var s = document.getElementById('hoshi-style');
                if (s) s.remove();
                s = document.createElement('style');
                s.id = 'hoshi-style';
                s.textContent = ${jsString(css)};
                document.head.appendChild(s);

                var contImgStyle = document.getElementById('reader-cont-img-style');
                if (contImgStyle) contImgStyle.remove();
                contImgStyle = document.createElement('style');
                contImgStyle.id = 'reader-cont-img-style';
                contImgStyle.textContent = [
                    'img.block-img, svg.block-img {',
                    '  max-width: var(--reader-image-max-width, ${100 - readerSettings.horizontalPadding}vw) !important;',
                    '  max-height: var(--reader-image-max-height, ${100 - readerSettings.verticalPadding}vh) !important;',
                    '  width: auto !important;',
                    '  height: auto !important;',
                    '  min-width: 0 !important;',
                    '  min-height: 0 !important;',
                    '  display: block !important;',
                    '  margin: 0 auto !important;',
                    '  object-fit: contain !important;',
                    '}',
                    'img:not(.block-img), svg:not(.block-img) {',
                    '  max-width: min(var(--reader-image-max-width, ${100 - readerSettings.horizontalPadding}vw), 100%) !important;',
                    '  width: auto !important;',
                    '  height: auto !important;',
                    '  min-width: 1em !important;',
                    '  vertical-align: middle !important;',
                    '  display: inline-block !important;',
                    '}',
                    'img.gaiji, img.gaiji-line {',
                    '  height: 1em !important;',
                    '  width: auto !important;',
                    '  vertical-align: baseline !important;',
                    '  margin: 0 !important;',
                    '  padding: 0 !important;',
                    '}',
                ].join(' ');
                document.head.appendChild(contImgStyle);

                $readerJs

                var b = document.body;
                if (!b) { window.hoshiReader.notifyRestoreComplete(); return; }

                window.hoshiReader.pageHeight = ih;
                window.hoshiReader.pageWidth = iw;

                b.style.setProperty('padding', vPad + 'px ' + hPad + 'px', 'important');
                b.style.setProperty('margin', '0', 'important');
                b.style.setProperty('box-sizing', 'border-box', 'important');

                b.style.setProperty('font-size', '${readerSettings.fontSize}px', 'important');
                b.style.setProperty('line-height', '${readerSettings.lineHeight}', 'important');
                ${paragraphSpacingJS(readerSettings)}
                ${if (readerSettings.layoutAdvanced) {
            """
                b.style.setProperty('letter-spacing', '${readerSettings.characterSpacing}em', 'important');
                """
        } else {
            ""
        }}
                b.style.setProperty('text-align', ${if (readerSettings.justifyText) "'justify'" else "'left'"}, 'important');

                ${fontJS(readerSettings, "b")}
                ${themeJS(bg, tc)}
                ${furiganaJS(readerSettings)}

                var vw = ${if (vw) "true" else "false"};
                if (vw) {
                    b.style.setProperty('touch-action', 'pan-x', 'important');
                    document.documentElement.style.setProperty('overflow-x', 'auto', 'important');
                    document.documentElement.style.setProperty('overflow-y', 'hidden', 'important');
                } else {
                    b.style.setProperty('touch-action', 'pan-y', 'important');
                    document.documentElement.style.setProperty('overflow-x', 'hidden', 'important');
                    document.documentElement.style.setProperty('overflow-y', 'auto', 'important');
                }
                b.style.setProperty('box-sizing', 'border-box', 'important');
                b.style.setProperty('width', iw + 'px', 'important');
                b.style.setProperty('min-height', ih + 'px', 'important');
                b.style.setProperty('height', 'auto', 'important');
                document.documentElement.style.setProperty('height', 'auto', 'important');

                window.hoshiReader.registerCopyText();
                window.hoshiReader.continuousMode = true;

                var allMediaCont = Array.from(document.querySelectorAll('img, svg'));
                var imagePromises = allMediaCont.map(function(el) {
                    return new Promise(function(resolve) {
                        var tag = el.tagName.toLowerCase();
                        var isGaiji = el.classList.contains('gaiji') || el.classList.contains('gaiji-line');
                        var classify = function() {
                            if (!isGaiji) {
                                var isLarge = false;
                                if (tag === 'img') {
                                    isLarge = el.naturalWidth > 256 || el.naturalHeight > 256;
                                } else if (tag === 'svg') {
                                    var vb = el.viewBox && el.viewBox.baseVal;
                                    var w = vb ? vb.width : (el.width ? el.width.baseVal.value : 0);
                                    var h = vb ? vb.height : (el.height ? el.height.baseVal.value : 0);
                                    isLarge = w > 256 || h > 256;
                                }
                                if (isLarge) {
                                    el.classList.add('block-img');
                                }
                            }
                            resolve();
                        };
                        if (tag === 'img') {
                            if (el.complete) {
                                if (el.naturalWidth > 0) { classify(); }
                                else { resolve(); }
                            } else {
                                el.onload = classify;
                                el.onerror = function() { resolve(); };
                            }
                        } else {
                            classify();
                        }
                    });
                });
                var settlePromise = imagePromises.length > 0
                    ? new Promise(function(r) { setTimeout(r, 50); })
                    : Promise.resolve();
                Promise.all(imagePromises)
                    .then(function() { return settlePromise; })
                    .then(function() {
                        window.hoshiReader.restoreProgress($pendingProgress, ${if (vw) "true" else "false"});
                    });
            })();
        """.trimIndent()
    }

    private fun buildPagedScript(): String {
        val epoch = restoreEpoch
        val vw = readerSettings.verticalWriting
        val css = buildBaseCSS()
        val bg = readerSettings.resolvedBgHex()
        val tc = readerSettings.resolvedTextHex()
        val bottomOverlapPx = if (vw) readerSettings.fontSize else 0

        return """
            (function() {
                window.__readerRestoreEpoch = $epoch;

                window.webkit = window.webkit || {};
                window.webkit.messageHandlers = window.webkit.messageHandlers || {};
                window.webkit.messageHandlers.restoreCompleted = {
                    postMessage: function(_) {
                        if (window.ReaderAndroid) window.ReaderAndroid.restoreCompleted(window.__readerRestoreEpoch);
                    }
                };

                var vp = document.querySelector('meta[name="viewport"]');
                if (vp) vp.remove();
                var nvp = document.createElement('meta');
                nvp.name = 'viewport';
                nvp.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                document.head.appendChild(nvp);

                var ih = window.innerHeight;
                var iw = window.innerWidth;

                var hPad = Math.round(iw * ${readerSettings.horizontalPadding} / 200);
                var vPad = Math.round(ih * ${readerSettings.verticalPadding} / 200);
                var bottomOverlap = $bottomOverlapPx;
                var pageHeight = ih + bottomOverlap;

                var contentW = Math.max(1, Math.round(iw * (100 - ${readerSettings.horizontalPadding}) / 100)${if (vw) " - 1" else ""});
                var contentH = Math.max(1, Math.round(ih * (100 - ${readerSettings.verticalPadding}) / 100) - bottomOverlap - 2);
                document.documentElement.style.setProperty('--reader-image-max-width', contentW + 'px');
                document.documentElement.style.setProperty('--reader-image-max-height', contentH + 'px');
                document.documentElement.style.setProperty('--page-height', pageHeight + 'px');
                document.documentElement.style.setProperty('--hoshi-reader-visible-height', ih + 'px');
                document.documentElement.style.setProperty('--page-width', iw + 'px');
                document.documentElement.style.setProperty('--hoshi-vertical-padding-block', vPad + 'px');
                document.documentElement.style.setProperty('--hoshi-vertical-padding-gap', (2 * vPad) + 'px');

                var s = document.getElementById('hoshi-style');
                if (s) s.remove();
                s = document.createElement('style');
                s.id = 'hoshi-style';
                s.textContent = ${jsString(css)};
                document.head.appendChild(s);

                var blockImgStyle = document.getElementById('reader-block-img-style');
                if (blockImgStyle) blockImgStyle.remove();
                blockImgStyle = document.createElement('style');
                blockImgStyle.id = 'reader-block-img-style';
                blockImgStyle.textContent = [
                    'img.block-img, svg.block-img {',
                    '  max-width: var(--reader-image-max-width, ${100 - readerSettings.horizontalPadding}vw) !important;',
                    '  max-height: var(--reader-image-max-height, ${100 - readerSettings.verticalPadding}vh) !important;',
                    '  width: auto !important;',
                    '  height: auto !important;',
                    '  min-width: 0 !important;',
                    '  min-height: 0 !important;',
                    '  display: block !important;',
                    '  margin: 0 auto !important;',
                    '  break-inside: avoid !important;',
                    '  -webkit-column-break-inside: avoid !important;',
                    '  object-fit: contain !important;',
                    '}',
                    'img:not(.block-img), svg:not(.block-img) {',
                    '  max-width: min(var(--reader-image-max-width, ${100 - readerSettings.horizontalPadding}vw), 100%) !important;',
                    '  width: auto !important;',
                    '  height: auto !important;',
                    '  min-width: 1em !important;',
                    '  vertical-align: middle !important;',
                    '  display: inline-block !important;',
                    '}',
                    'img.gaiji, img.gaiji-line {',
                    '  height: 1em !important;',
                    '  width: auto !important;',
                    '  vertical-align: baseline !important;',
                    '  margin: 0 !important;',
                    '  padding: 0 !important;',
                    '}',
                ].join(' ');
                document.head.appendChild(blockImgStyle);

                $readerJs

                var b = document.body;
                if (!b) { window.hoshiReader.notifyRestoreComplete(); return; }

                window.hoshiReader.pageHeight = ih + bottomOverlap;
                window.hoshiReader.pageWidth = iw;

                b.style.setProperty(
                    'padding',
                    vPad + 'px ' + hPad + 'px ' + (vPad + bottomOverlap) + 'px ' + hPad + 'px',
                    'important'
                );
                b.style.setProperty('margin', '0', 'important');

                b.style.setProperty('font-size', '${readerSettings.fontSize}px', 'important');
                b.style.setProperty('line-height', '${readerSettings.lineHeight}', 'important');
                ${paragraphSpacingJS(readerSettings)}
                ${if (readerSettings.layoutAdvanced) {
            """
                b.style.setProperty('letter-spacing', '${readerSettings.characterSpacing}em', 'important');
                """
        } else {
            ""
        }}
                b.style.setProperty('text-align', ${if (readerSettings.justifyText) "'justify'" else "'left'"}, 'important');

                ${if (readerSettings.avoidPageBreak) {
            """
                var abStyle = document.createElement('style');
                abStyle.textContent = [
                    'img, svg, figure, table, tr, td, th,',
                    'p:has(> img:only-child), div:has(> img:only-child), span.img, div.img, p.img {',
                    '  break-inside: avoid !important;',
                    '  -webkit-column-break-inside: avoid !important;',
                    '  page-break-inside: avoid !important;',
                    '}'
                ].join(' ');
                document.head.appendChild(abStyle);
                """
        } else {
            ""
        }}

                ${fontJS(readerSettings, "b")}
                ${themeJS(bg, tc)}
                ${furiganaJS(readerSettings)}

                var overrideStyle = document.getElementById('hoshi-override-style');
                if (!overrideStyle) {
                    overrideStyle = document.createElement('style');
                    overrideStyle.id = 'hoshi-override-style';
                    document.head.appendChild(overrideStyle);
                }
                overrideStyle.textContent = [
                    '[class*="pt"] { margin-top: 0 !important; }',
                    '[class*="pb"] { margin-bottom: 0 !important; }',
                    'span.img.fpage, span.img.fblk { padding-bottom: 0 !important; }',
                    'body * { column-count: auto !important; -webkit-column-count: auto !important; }',
                    'body, body * { orphans: 1 !important; widows: 1 !important; }',
                    '@page { margin: 0 !important; }'
                ].join(' ');

                document.documentElement.style.setProperty('height', pageHeight + 'px', 'important');
                document.documentElement.style.setProperty('width', iw + 'px', 'important');
                var vw = ${if (vw) "true" else "false"};
                if (vw) {
                    b.style.setProperty('column-width', pageHeight + 'px', 'important');
                    b.style.setProperty('height', pageHeight + 'px', 'important');
                    b.style.setProperty('column-gap', (2 * vPad + bottomOverlap) + 'px', 'important');
                } else {
                    b.style.setProperty('column-width', iw + 'px', 'important');
                    b.style.setProperty('height', pageHeight + 'px', 'important');
                    b.style.setProperty('column-gap', (2 * hPad) + 'px', 'important');
                }
                b.style.setProperty('box-sizing', 'border-box', 'important');
                b.style.setProperty('width', iw + 'px', 'important');
                b.style.setProperty('column-fill', 'auto', 'important');
                b.style.setProperty('touch-action', 'none', 'important');
                b.style.setProperty('-webkit-text-size-adjust', 'none', 'important');
                document.documentElement.style.setProperty('overflow', 'hidden', 'important');
                b.style.setProperty('overflow', 'hidden', 'important');

                window.hoshiReader.registerCopyText();

                var allMediaPaged = Array.from(document.querySelectorAll('img, svg'));
                var imagePromises = allMediaPaged.map(function(el) {
                    return new Promise(function(resolve) {
                        var tag = el.tagName.toLowerCase();
                        var isGaiji = el.classList.contains('gaiji') || el.classList.contains('gaiji-line');
                        var classify = function() {
                            if (!isGaiji) {
                                var isLarge = false;
                                if (tag === 'img') {
                                    isLarge = el.naturalWidth > 256 || el.naturalHeight > 256;
                                } else if (tag === 'svg') {
                                    var vb = el.viewBox && el.viewBox.baseVal;
                                    var w = vb ? vb.width : (el.width ? el.width.baseVal.value : 0);
                                    var h = vb ? vb.height : (el.height ? el.height.baseVal.value : 0);
                                    isLarge = w > 256 || h > 256;
                                }
                                if (isLarge) {
                                    el.classList.add('block-img');
                                    var wrapH = ih - 2 * vPad - bottomOverlap;
                                    var wrapper = document.createElement('div');
                                    wrapper.style.cssText = 'display:flex !important;align-items:center !important;justify-content:center !important;min-height:' + wrapH + 'px !important;width:100% !important;max-width:100% !important;break-inside:avoid !important;-webkit-column-break-inside:avoid !important';
                                    el.parentNode.insertBefore(wrapper, el);
                                    wrapper.appendChild(el);
                                }
                            }
                            resolve();
                        };
                        if (tag === 'img') {
                            if (el.complete) {
                                if (el.naturalWidth > 0) { classify(); }
                                else { resolve(); }
                            } else {
                                el.onload = classify;
                                el.onerror = function() { resolve(); };
                            }
                        } else {
                            classify();
                        }
                    });
                });
                var settlePromise = imagePromises.length > 0
                    ? new Promise(function(r) { setTimeout(r, 50); })
                    : Promise.resolve();
                Promise.all(imagePromises)
                    .then(function() { return settlePromise; })
                    .then(function() {
                        window.hoshiReader.restoreProgress($pendingProgress, ${if (vw) "true" else "false"});
                    });
            })();
        """.trimIndent()
    }

    fun applySettings(settings: ReaderSettings) {
        if (settings.continuousMode != continuousMode) {
            continuousMode = settings.continuousMode
            currentUrl?.let { url ->
                evaluateJavascript("window.hoshiReader.calculateProgress()") { p ->
                    pendingProgress = p?.toDoubleOrNull() ?: 0.0
                    loadChapter(url)
                }
            }
            return
        }

        readerSettings = settings
        val bg = settings.resolvedBgHex()
        val tc = settings.resolvedTextHex()
        setBackgroundColor(settings.backgroundColor)

        val script = """
            (function() {
                var b = document.body;
                if (!b) return;
                var iw = window.innerWidth;
                var ih = window.innerHeight;
                var hPad = Math.round(iw * ${settings.horizontalPadding} / 200);
                var vPad = Math.round(ih * ${settings.verticalPadding} / 200);
                var bottomOverlap = ${if (settings.verticalWriting && !continuousMode) settings.fontSize else 0};
                var pageHeight = ih + bottomOverlap;
                var contentW = Math.max(1, Math.round(iw * (100 - ${settings.horizontalPadding}) / 100)${if (settings.verticalWriting) " - 1" else ""});
                var contentH = Math.max(1, Math.round(ih * (100 - ${settings.verticalPadding}) / 100) - bottomOverlap - 2);
                document.documentElement.style.setProperty('--reader-image-max-width', contentW + 'px');
                document.documentElement.style.setProperty('--reader-image-max-height', contentH + 'px');
                document.documentElement.style.setProperty('--page-height', pageHeight + 'px');
                document.documentElement.style.setProperty('--hoshi-reader-visible-height', ih + 'px');
                document.documentElement.style.setProperty('--page-width', iw + 'px');
                document.documentElement.style.setProperty('--hoshi-vertical-padding-block', vPad + 'px');
                document.documentElement.style.setProperty('--hoshi-vertical-padding-gap', (2 * vPad) + 'px');
                b.style.setProperty(
                    'padding',
                    vPad + 'px ' + hPad + 'px ' + (vPad + bottomOverlap) + 'px ' + hPad + 'px',
                    'important'
                );
                b.style.setProperty('margin', '0', 'important');
                b.style.setProperty('box-sizing', 'border-box', 'important');
                if (!${if (continuousMode) "true" else "false"}) {
                    b.style.setProperty(
                        'column-gap',
                        (${if (settings.verticalWriting) "2 * vPad + bottomOverlap" else "2 * hPad"}) + 'px',
                        'important'
                    );
                    b.style.setProperty('width', iw + 'px', 'important');
                    b.style.setProperty('column-fill', 'auto', 'important');
                    b.style.setProperty('touch-action', 'none', 'important');
                    b.style.setProperty('-webkit-text-size-adjust', 'none', 'important');
                    document.documentElement.style.setProperty('height', pageHeight + 'px', 'important');
                    document.documentElement.style.setProperty('width', iw + 'px', 'important');
                    document.documentElement.style.setProperty('overflow', 'hidden', 'important');
                    b.style.setProperty('overflow', 'hidden', 'important');
                    ${if (settings.verticalWriting) """
                    b.style.setProperty('column-width', pageHeight + 'px', 'important');
                    b.style.setProperty('height', pageHeight + 'px', 'important');
                    """ else """
                    b.style.setProperty('column-width', iw + 'px', 'important');
                    b.style.setProperty('height', pageHeight + 'px', 'important');
                    """}
                }

                b.style.setProperty('font-size', '${settings.fontSize}px', 'important');

                b.style.setProperty('line-height', '${settings.lineHeight}', 'important');
                ${paragraphSpacingJS(settings)}
                ${if (settings.layoutAdvanced) {
            """
                b.style.setProperty('letter-spacing', '${settings.characterSpacing}em', 'important');
                """
        } else {
            ""
        }}

                b.style.setProperty('text-align', ${if (settings.justifyText) "'justify'" else "'left'"}, 'important');

                ${fontJS(settings, "b")}

                b.style.setProperty('background-color', '$bg', 'important');
                b.style.setProperty('color', '$tc', 'important');
                document.documentElement.style.setProperty('background-color', '$bg', 'important');

                ${furiganaJS(settings)}
            })();
        """.trimIndent()

        evaluateJavascript(script, null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                totalMovement = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                totalMovement += kotlin.math.abs(event.x - touchStartX) + kotlin.math.abs(event.y - touchStartY)
                touchStartX = event.x
                touchStartY = event.y
            }
            MotionEvent.ACTION_UP -> {
                if (totalMovement < 20f) {
                    val cssX = event.x / resources.displayMetrics.density
                    val cssY = event.y / resources.displayMetrics.density
                    evaluateJavascript(
                        "if (window.hoshiReader && window.hoshiReader.handleTap) { window.hoshiReader.handleTap($cssX, $cssY); }",
                        null,
                    )
                }
                performClick()
            }
        }
        val gestureHandled = gestureDetector.onTouchEvent(event)
        val webViewHandled = super.onTouchEvent(event)
        return gestureHandled || webViewHandled
    }

    override fun performClick(): Boolean = super.performClick()

    fun paginate(forward: Boolean) {
        if (forward) {
            navigate("forward", onNextChapter)
        } else {
            navigate("backward", onPreviousChapter)
        }
    }

    private fun handleSwipe(forward: Boolean): Boolean {
        return when {
            isImageOnly -> {
                // Image-only chapter = single page, any swipe changes chapter
                val changed = if (forward) onNextChapter() else onPreviousChapter()
                if (changed) visibility = View.INVISIBLE
                true
            }
            continuousMode -> navigateContinuous(forward)
            else -> if (forward) {
                navigate("forward", onNextChapter)
            } else {
                navigate("backward", onPreviousChapter)
            }
        }
    }

    /**
     * For continuous mode: check via JS whether we are at the scroll boundary.
     * If yes, call the chapter callback; if not, let the WebView handle the scroll.
     */
    private fun navigateContinuous(forward: Boolean): Boolean {
        val script = """
            (function() {
                var el = document.scrollingElement || document.documentElement;
                var ph = window.innerHeight;
                var pw = window.innerWidth;
                var vOver = el.scrollHeight - ph > 1;
                var hOver = el.scrollWidth  - pw > 1;
                if (vOver) {
                    var y = Math.round(window.scrollY);
                    var maxY = el.scrollHeight - ph;
                    if ('$forward' === 'true')  return y >= maxY - 2 ? 'limit' : 'scrolling';
                    if ('$forward' === 'false') return y <= 2       ? 'limit' : 'scrolling';
                }
                if (hOver) {
                    var x = window.scrollX;
                    var maxX = el.scrollWidth - pw;
                    var absX = Math.abs(x);
                    if ('$forward' === 'true')  return absX >= maxX - 2 ? 'limit' : 'scrolling';
                    if ('$forward' === 'false') return absX <= 2        ? 'limit' : 'scrolling';
                }
                return 'limit';
            })()
        """.trimIndent()

        evaluateJavascript(script) { result ->
            if (result?.trim('"') == "limit") {
                // No synthesized progress here: the completed turn persists its
                // own (nextChapter=1.0, previousChapter/jump=current). Writing
                // 1.0/0.0 optimistically corrupted resume on dismissed turns
                // and marked chapters read on backward exits.
                val changed = if (forward) onNextChapter() else onPreviousChapter()
                if (changed) visibility = View.INVISIBLE
            }
            // else: still content to scroll — the WebView's own fling handles it
        }
        return true
    }

    private fun navigate(direction: String, fallback: () -> Boolean): Boolean {
        val script = """
            (function() {
                if (!window.hoshiReader || typeof window.hoshiReader.paginate !== 'function') {
                    return "limit";
                }
                return window.hoshiReader.paginate('$direction');
            })()
        """.trimIndent()

        evaluateJavascript(script) { result ->
            if (result?.trim('"') == "scrolled") {
                onPageTurned()
                evaluateJavascript(
                    "(function() { return window.hoshiReader.calculateProgress(); })()",
                ) { progressResult ->
                    progressResult
                        ?.trim()
                        ?.trim('"')
                        ?.toDoubleOrNull()
                        ?.let {
                            pendingProgress = it
                            onProgressChanged(it)
                        }
                }
            } else {
                val chapterChanged = fallback()
                if (chapterChanged) {
                    visibility = View.INVISIBLE
                }
            }
        }
        return true
    }
}

private class ReaderJavascriptBridge(
    private val onRestoreCompleted: (Int) -> Unit,
    private val onTextSelectedCallback: (word: String, sentence: String, x: Float, y: Float, w: Float, h: Float) -> Unit = { _, _, _, _, _, _ -> },
    private val onBackgroundTap: (x: Float, y: Float) -> Unit = { _, _ -> },
    private val onSentenceReadyCallback: (sentence: String) -> Unit = {},
    private val onImageTappedCallback: (imageUrl: String) -> Unit = {},
) {
    /** Callback for selection rects from JS. Set by the hosting Activity. */
    var onSelectionRectsCallback: ((String) -> Unit)? = null

    @JavascriptInterface
    fun restoreCompleted(epoch: Int = 0) {
        onRestoreCompleted(epoch)
    }

    @JavascriptInterface
    fun onTextSelected(word: String, sentence: String, x: Float, y: Float, w: Float, h: Float) {
        if (word.isNotBlank()) onTextSelectedCallback.invoke(word, sentence, x, y, w, h)
    }

    @JavascriptInterface
    fun onBackgroundTap(x: Float, y: Float) {
        onBackgroundTap.invoke(x, y)
    }

    @JavascriptInterface
    fun onSentenceReady(sentence: String) {
        onSentenceReadyCallback.invoke(sentence)
    }

    @JavascriptInterface
    fun onSelectionRects(json: String) {
        onSelectionRectsCallback?.invoke(json)
    }

    @JavascriptInterface
    fun onImageTapped(imageUrl: String) {
        if (imageUrl.isNotBlank()) onImageTappedCallback.invoke(imageUrl)
    }
}

private fun loadAssetText(context: Context, path: String): String {
    return context.assets.open(path).use { input ->
        BufferedReader(input.reader()).readText()
    }
}

private fun jsString(value: String): String {
    return buildString {
        append('\'')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('\'')
    }
}

private fun jsEscape(value: String): String = value
    .replace("\\", "\\\\")
    .replace("'", "\\'")
    .replace("\n", "\\n")
    .replace("\r", "\\r")

private fun fontJS(settings: ReaderSettings, targetVar: String): String = buildString {
    val fontUrl = settings.fontUrl
    if (!fontUrl.isNullOrBlank()) {
        appendLine(
            """
            var fontFace = document.createElement('style');
            fontFace.textContent = "@font-face { font-family: 'HoshiCustomFont'; src: url('${jsEscape(fontUrl)}'); }";
            document.head.appendChild(fontFace);
            $targetVar.style.setProperty('font-family', 'HoshiCustomFont', 'important');
            """.trimIndent(),
        )
    } else {
        var ff = settings.selectedFont
        if (ff == "System Serif") {
            ff = "serif"
        } else if (ff == "System Sans-Serif") {
            ff = "sans-serif"
        }
        appendLine("$targetVar.style.setProperty('font-family', '${jsEscape(ff)}', 'important');")
    }
}

private fun themeJS(bg: String, tc: String): String = """
    b.style.setProperty('background-color', '$bg', 'important');
    b.style.setProperty('color', '$tc', 'important');
    document.documentElement.style.setProperty('background-color', '$bg', 'important');
""".trimIndent()

private fun furiganaJS(settings: ReaderSettings): String = if (settings.hideFurigana) {
    """
    var furiganaStyle = document.getElementById('hoshi-furigana-style');
    if (!furiganaStyle) {
        furiganaStyle = document.createElement('style');
        furiganaStyle.id = 'hoshi-furigana-style';
        furiganaStyle.textContent = 'rt { display: none !important; }';
        document.head.appendChild(furiganaStyle);
    }
    """.trimIndent()
} else {
    """
    var furiganaStyle = document.getElementById('hoshi-furigana-style');
    if (furiganaStyle) furiganaStyle.remove();
    """.trimIndent()
}

private fun ReaderSettings.resolvedBgHex(): String = when (theme) {
    "dark" -> "#1a1a1a"
    "sepia" -> "#f4ecd8"
    "light" -> "#ffffff"
    else -> "#${String.format("%06X", 0xFFFFFF and backgroundColor)}"
}

private fun ReaderSettings.resolvedTextHex(): String = when (theme) {
    "dark" -> "#ffffff"
    "sepia" -> "#5b4636"
    "light" -> "#000000"
    else -> "#${String.format("%06X", 0xFFFFFF and textColor)}"
}
