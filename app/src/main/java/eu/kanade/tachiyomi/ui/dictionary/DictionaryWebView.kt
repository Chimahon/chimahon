package eu.kanade.tachiyomi.ui.dictionary

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.R
import chimahon.DictionaryStyle
import chimahon.LookupResult

@SuppressLint("SetJavaScriptEnabled")
internal fun prepareDictionaryWebViewShell(
    context: Context,
    webView: WebView = WebView(context),
    bootstrapHtml: String? = null,
    backgroundColor: Int = android.graphics.Color.TRANSPARENT,
    languageCode: String = "",
): WebView {
    val effectiveBootstrap = bootstrapHtml ?: getDictionaryBootstrapHtml(context, languageCode = languageCode)
    (webView.tag as? DictionaryWebViewState)?.let { state ->
        if (state.bootstrapHtml != effectiveBootstrap) {
            state.reloadShell(webView, effectiveBootstrap)
        }
        return webView
    }

    val state = DictionaryWebViewState(context, webViewProvider = { webView }, bootstrapHtml = effectiveBootstrap)
    webView.apply {
        alpha = 0f
        setBackgroundColor(backgroundColor)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.blockNetworkLoads = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.setSupportZoom(true)
        settings.displayZoomControls = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            settings.forceDark = WebSettings.FORCE_DARK_OFF
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.isAlgorithmicDarkeningAllowed = false
        }

        disableSafeBrowsingForDictionary(this)

        addJavascriptInterface(state.wordAudioBridge, "WordAudioBridge")
        addJavascriptInterface(state.readyBridge, "DictionaryReadyBridge")
        addJavascriptInterface(state.payloadBridge, "PayloadBridge")
        addJavascriptInterface(state.ankiJsBridge, "AnkiJsBridge")
        addJavascriptInterface(state.clipboardBridge, "DictionaryClipboardBridge")


        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val s = view?.tag as? DictionaryWebViewState ?: return
                s.pageReady = true
                view.setTag(R.id.chima_webview_warm, true)
                val enableRecursive = s.onRecursiveLookup != null
                view.evaluateJavascript("window.DictionaryRenderer && window.DictionaryRenderer.setRecursiveLookupEnabled($enableRecursive);", null)
                s.injectFontSize(view)
                s.injectCustomCss(view)
                s.flush(view)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url ?: return false
                val s = view?.tag as? DictionaryWebViewState

                if (url.scheme == CHIMA_SCHEME) {
                    when (url.host) {
                        CHIMA_HOST_LOOKUP -> {
                            val q = url.getQueryParameter("q") ?: return true
                            val sentence = url.getQueryParameter("sentence")
                            val sentenceOffset = url.getQueryParameter("offset")?.toIntOrNull()
                            val x = url.getQueryParameter("x")?.toFloatOrNull()
                            val y = url.getQueryParameter("y")?.toFloatOrNull()
                            if (q.isNotBlank()) s?.onRecursiveLookup?.invoke(q, sentence, sentenceOffset, x, y, null)
                            return true
                        }
                        CHIMA_HOST_TAB -> {
                            val idx = url.getQueryParameter("index")?.toIntOrNull()
                            if (idx != null) s?.onTabSelect?.invoke(idx)
                            return true
                        }
                        CHIMA_HOST_BACK -> {
                            s?.onBack?.invoke()
                            return true
                        }
                        CHIMA_HOST_KANJI -> {
                            val q = url.getQueryParameter("q") ?: return true
                            if (q.isNotBlank()) s?.onRecursiveLookup?.invoke(q, null, null, null, null, "kanji")
                            return true
                        }
                    }
                    return true
                }

                return false
            }
        }

        tag = state
        state.reloadShell(this, effectiveBootstrap)
    }
    return webView
}

internal class DictionaryWebViewState(
    val context: Context,
    webViewProvider: () -> WebView?,
    var bootstrapHtml: String = "",
) {
    val wordAudioBridge: WordAudioBridge = WordAudioBridge(context, webViewProvider)
    val readyBridge: DictionaryReadyBridge = DictionaryReadyBridge(webViewProvider) { this }
    val payloadBridge: PayloadBridge = PayloadBridge()
    val ankiJsBridge: AnkiJsBridge = AnkiJsBridge(webViewProvider)
    val clipboardBridge: DictionaryClipboardBridge = DictionaryClipboardBridge(context)
    var pageReady: Boolean = false
    var fontSize: Int = 16
    @Volatile var contentReadyGeneration: Long = 0
    @Volatile var nextContentReadyRequestId: Long = 0
    var onAnkiLookup: ((Int, Int?, String?, String?, Boolean) -> Unit)? = null
    var onRecursiveLookup: ((String, String?, Int?, Float?, Float?, String?) -> Unit)? = null
    var onTabSelect: ((Int) -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onContentInvalidated: (() -> Unit)? = null
    var onContentReady: (() -> Unit)? = null
    var lastPayload: String? = null
    var lastResults: List<LookupResult>? = null
    var lastExistingExpressions: Set<String>? = null
    var lastMediaDataUris: Map<String, String>? = null
    var lastRenderSignature: DictionaryRenderSignature? = null
    var pendingPayload: String? = null
    var pendingEntryJsons: List<String>? = null
    var pendingResults: List<LookupResult>? = null
    var pendingExistingExpressions: Set<String>? = null
    var pendingMediaDataUris: Map<String, String>? = null
    var pendingRenderSignature: DictionaryRenderSignature? = null
    var customCss: String = ""

    fun injectCustomCss(webView: WebView) {
        if (customCss.isEmpty()) {
            webView.evaluateJavascript(
                "var el = document.getElementById('chima-custom-css'); if (el) el.textContent = '';",
                null,
            )
            return
        }
        webView.evaluateJavascript(
            "(function(v) {" +
                "var el = document.getElementById('chima-custom-css');" +
                "if (el) el.textContent = v;" +
                "})(decodeURIComponent('${java.net.URLEncoder.encode(customCss, "UTF-8").replace("+", "%20")}'));",
            null,
        )
    }

    fun injectFontSize(webView: WebView) {
        webView.evaluateJavascript(
            "(function(v) {" +
                "v = v + 'px';" +
                "document.documentElement.style.fontSize = v;" +
                "document.body.style.fontSize = v;" +
                "document.documentElement.style.setProperty('--font-size-no-units', '$fontSize');" +
                "})('$fontSize');",
            null,
        )
    }

    fun clear(webView: WebView) {
        wordAudioBridge.stopAudio()
        onContentInvalidated?.invoke()
        payloadBridge.rawPayloadJson = ""
        payloadBridge.rawEntryJsons = emptyList()
        webView.evaluateJavascript("window.DictionaryRenderer && window.DictionaryRenderer.clear();", null)
        resetState()
    }

    private fun resetState() {
        lastPayload = null
        lastResults = null
        lastExistingExpressions = null
        lastMediaDataUris = null
        lastRenderSignature = null
        clearPendingPayload()
    }

    fun reloadShell(webView: WebView, html: String) {
        bootstrapHtml = html
        pageReady = false
        lastPayload = null
        lastResults = null
        lastExistingExpressions = null
        lastMediaDataUris = null
        lastRenderSignature = null
        contentReadyGeneration++
        nextContentReadyRequestId++
        clearPendingPayload()
        onContentInvalidated?.invoke()
        webView.loadDataWithBaseURL(
            "https://chima.local/popup/",
            html,
            "text/html",
            "UTF-8",
            null,
        )
    }

    fun flush(
        webView: WebView,
        results: List<LookupResult>? = null,
        existingExpressions: Set<String>? = null,
        mediaDataUris: Map<String, String>? = null,
        renderSignature: DictionaryRenderSignature? = null,
        configPayload: String? = null,
        entryJsons: List<String>? = null,
    ) {
        val p = configPayload ?: pendingPayload ?: return
        val entries = entryJsons ?: pendingEntryJsons ?: return
        val renderResults = results ?: pendingResults
        val renderExistingExpressions = existingExpressions ?: pendingExistingExpressions
        val renderMediaDataUris = mediaDataUris ?: pendingMediaDataUris
        val signature = renderSignature ?: pendingRenderSignature

        val canPatchExistingRender = lastResults != null &&
            renderResults != null &&
            lastResults === renderResults &&
            lastRenderSignature == signature
        if (canPatchExistingRender) {
            if (lastExistingExpressions != renderExistingExpressions && renderExistingExpressions != null) {
                val json = org.json.JSONArray(renderExistingExpressions).toString()
                webView.evaluateJavascript("DictionaryRenderer.updateAnkiStatus(${json.toJavascriptExpression()})", null)
                lastExistingExpressions = renderExistingExpressions
            }
            if (lastMediaDataUris != renderMediaDataUris && renderMediaDataUris != null) {
                val json = org.json.JSONObject(renderMediaDataUris).toString()
                webView.evaluateJavascript("DictionaryRenderer.updateMediaDataUris(${json.toJavascriptExpression()})", null)
                lastMediaDataUris = renderMediaDataUris
            }
            if (p != lastPayload) {
                lastPayload = p
            }
            clearPendingPayload()
            return
        }

        if (p == lastPayload && lastRenderSignature == signature) {
            if (lastExistingExpressions != renderExistingExpressions && renderExistingExpressions != null) {
                val json = org.json.JSONArray(renderExistingExpressions).toString()
                webView.evaluateJavascript("DictionaryRenderer.updateAnkiStatus(${json.toJavascriptExpression()})", null)
                lastExistingExpressions = renderExistingExpressions
            }
            if (lastMediaDataUris != renderMediaDataUris && renderMediaDataUris != null) {
                val json = org.json.JSONObject(renderMediaDataUris).toString()
                webView.evaluateJavascript("DictionaryRenderer.updateMediaDataUris(${json.toJavascriptExpression()})", null)
                lastMediaDataUris = renderMediaDataUris
            }
            clearPendingPayload()
            return
        }

        lastPayload = p
        lastResults = renderResults
        lastExistingExpressions = renderExistingExpressions
        lastMediaDataUris = renderMediaDataUris
        lastRenderSignature = signature
        contentReadyGeneration++
        nextContentReadyRequestId++
        clearPendingPayload()

        val renderStart = SystemClock.elapsedRealtime()

        onContentInvalidated?.invoke()
        injectFontSize(webView)

        // Keep the JS call tiny; the renderer pulls the heavier entry payloads
        // through PayloadBridge so evaluateJavascript does less work.
        payloadBridge.rawPayloadJson = p
        payloadBridge.rawEntryJsons = entries

        val js = "if(window.DictionaryRenderer){" +
            "window.DictionaryRenderer.replacePopupResults();" +
            "${renderExistingExpressions?.let { "window.DictionaryRenderer.updateAnkiStatus(${org.json.JSONArray(it).toString().toJavascriptExpression()});" }.orEmpty()}" +
            "${renderMediaDataUris?.takeIf { it.isNotEmpty() }?.let { "window.DictionaryRenderer.updateMediaDataUris(${org.json.JSONObject(it).toString().toJavascriptExpression()});" }.orEmpty()}" +
            "}"
        webView.evaluateJavascript(js, null)

        Log.i(
            "DictionaryRender",
            "webview_dispatch_ms=${SystemClock.elapsedRealtime() - renderStart}",
        )
    }

    private fun clearPendingPayload() {
        pendingPayload = null
        pendingEntryJsons = null
        pendingResults = null
        pendingExistingExpressions = null
        pendingMediaDataUris = null
        pendingRenderSignature = null
    }
}

private fun disableSafeBrowsingForDictionary(webView: WebView) {
    val settings = webView.settings

    val disabledViaAndroidX = runCatching {
        val featureClass = Class.forName("androidx.webkit.WebViewFeature")
        val featureValue = featureClass.getField("SAFE_BROWSING_ENABLE").get(null) as String
        val isSupportedMethod = featureClass.getMethod("isFeatureSupported", String::class.java)
        val supported = isSupportedMethod.invoke(null, featureValue) as? Boolean ?: false
        if (!supported) return@runCatching false

        val compatClass = Class.forName("androidx.webkit.WebSettingsCompat")
        val setter = compatClass.getMethod(
            "setSafeBrowsingEnabled",
            WebSettings::class.java,
            Boolean::class.javaPrimitiveType,
        )
        setter.invoke(null, settings, false)
        true
    }.getOrDefault(false)

    if (!disabledViaAndroidX && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        settings.safeBrowsingEnabled = false
    }
}

private const val CHIMA_SCHEME = "chima"
private const val CHIMA_HOST_LOOKUP = "lookup"
private const val CHIMA_HOST_TAB = "tab"
private const val CHIMA_HOST_BACK = "back"
private const val CHIMA_HOST_KANJI = "kanji"
