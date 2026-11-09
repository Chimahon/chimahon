package eu.kanade.tachiyomi.ui.dictionary

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import chimahon.DictionaryStyle
import chimahon.LookupResult
import eu.kanade.domain.ui.UiPreferences
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DictionaryEntryWebView(
    results: List<LookupResult>,
    styles: List<DictionaryStyle>,
    mediaDataUris: Map<String, String>,
    placeholder: String,
    headerText: String = "",
    fontSize: Int = 16,
    showFrequencyHarmonic: Boolean = false,
    showFrequencyAverage: Boolean = false,
    groupTerms: Boolean = true,
    activeProfile: chimahon.anki.AnkiProfile,
    existingExpressions: Set<String> = emptySet(),
    tabs: List<TabInfo> = emptyList(),
    showPitchDiagram: Boolean = true,
    showPitchNumber: Boolean = true,
    showPitchText: Boolean = true,
    recursiveNavMode: String = "tabs",
    renderRecursiveChrome: Boolean = true,
    wordAudioEnabled: Boolean = true,
    wordAudioAutoplayOverride: Boolean? = null,
    entryJsons: List<String>? = null,
    customCss: String = "",
    groupPitches: Boolean = false,
    modifier: Modifier = Modifier,
    webViewProvider: ((android.content.Context) -> WebView)? = null,
    onAnkiLookup: ((Int, Int?, String?, String?, Boolean) -> Unit)? = null,
    onRecursiveLookup: ((String, String?, Int?, Float?, Float?, String?) -> Unit)? = null,
    onTabSelect: ((Int) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onContentReadyChange: ((Boolean) -> Unit)? = null,
    hideOnContentInvalidated: Boolean = true,
    forceDefaultTheme: Boolean = false,
    requestFocusOnMount: Boolean = false,
    isLoading: Boolean = false,
) {
    val context = LocalContext.current
    val dictionaryPreferences = remember { Injekt.get<DictionaryPreferences>() }
    val themeMode by dictionaryPreferences.themeMode().collectAsState()
    val compactGlossary by dictionaryPreferences.compactGlossary().collectAsState()
    val customColor by dictionaryPreferences.customColor().collectAsState()

    val prefs = remember { Injekt.get<DictionaryPreferences>() }
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val fontFamily by prefs.fontFamily().collectAsState()
    val eInkMode by prefs.eInkMode().collectAsState()
    val paginatedScrolling by prefs.paginatedScrolling().collectAsState()
    val appAmoled by uiPreferences.themeDarkAmoled().collectAsState()
    val appThemeMode by uiPreferences.themeMode().collectAsState()
    val appColorTheme by uiPreferences.colorTheme().collectAsState()

    val systemIsDark = isSystemInDarkTheme()
    val resolvedTheme = remember(
        themeMode,
        customColor,
        eInkMode,
        forceDefaultTheme,
        systemIsDark,
        appThemeMode,
        appAmoled,
        appColorTheme,
    ) {
        resolveDictionaryTheme(
            themeMode = themeMode,
            customColor = customColor,
            eInkMode = eInkMode,
            forceDefaultTheme = forceDefaultTheme,
            systemIsDark = systemIsDark,
            appThemeMode = appThemeMode,
            appAmoled = appAmoled,
            appColorTheme = appColorTheme,
        )
    }
    val isDark = resolvedTheme.isDark
    val isAmoled = resolvedTheme.isAmoled
    val seedColor = resolvedTheme.seedColor
    val colorScheme = if (resolvedTheme.useAppTheme) {
        MaterialTheme.colorScheme
    } else {
        remember(isDark, isAmoled, seedColor) {
            getDictionaryColorScheme(isDark, isAmoled, seedColor)
        }
    }
    val BgColor = remember(isDark, isAmoled, seedColor, colorScheme) {
        if (isAmoled && isDark) Color.Black else colorScheme.surface
    }
    val wordAudioAutoplay by prefs.wordAudioAutoplay().collectAsState()
    val effectiveWordAudioAutoplay = wordAudioAutoplayOverride ?: wordAudioAutoplay
    val showNavigationButtons by prefs.showNavigationButtons().collectAsState()
    val paginatedScrollStepSize by prefs.paginatedScrollStepSize().collectAsState()
    val scrollBehavior by prefs.scrollBehavior().collectAsState()
    val dictionaryDisplayNames by prefs.displayNames().collectAsState()

    val renderSignature = remember(
        results, styles, placeholder, isDark,
        showFrequencyHarmonic, showFrequencyAverage, groupTerms,
        showPitchDiagram, showPitchNumber, showPitchText,
        activeProfile, tabs, recursiveNavMode, wordAudioEnabled,
        effectiveWordAudioAutoplay, showNavigationButtons, groupPitches,
        renderRecursiveChrome, entryJsons, compactGlossary, dictionaryDisplayNames,
    ) {
        DictionaryRenderSignature(
            results = results, styles = styles, placeholder = placeholder, isDark = isDark,
            showFrequencyHarmonic = showFrequencyHarmonic,
            showFrequencyAverage = showFrequencyAverage,
            groupTerms = groupTerms,
            showPitchDiagram = showPitchDiagram, showPitchNumber = showPitchNumber,
            showPitchText = showPitchText, activeProfile = activeProfile, tabs = tabs,
            recursiveNavMode = recursiveNavMode, renderRecursiveChrome = renderRecursiveChrome,
            wordAudioEnabled = wordAudioEnabled,
            wordAudioAutoplay = effectiveWordAudioAutoplay,
            showNavigationButtons = showNavigationButtons, groupPitches = groupPitches,
            dictionaryDisplayNames = dictionaryDisplayNames,
        )
    }

    var configPayloadPair by remember { mutableStateOf<Pair<String, DictionaryRenderSignature>?>(null) }
    var entryJsonsPair by remember { mutableStateOf<Pair<List<String>, DictionaryRenderSignature>?>(null) }
    val currentConfigPayload = configPayloadPair
    val currentEntryJsons = entryJsonsPair
    LaunchedEffect(renderSignature) {
        val buildStart = SystemClock.elapsedRealtime()
        val (configJson, builtEntryJsons) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val config = buildConfigPayload(
                context, styles, emptyMap(), placeholder, isDark,
                showFrequencyHarmonic, showFrequencyAverage, groupTerms,
                showPitchDiagram, showPitchNumber, showPitchText,
                effectiveWordAudioAutoplay, activeProfile, emptySet(), tabs, recursiveNavMode,
                renderRecursiveChrome = renderRecursiveChrome,
                wordAudioEnabled = wordAudioEnabled,
                showNavigationButtons = showNavigationButtons,
                groupPitches = groupPitches,
                compactGlossary = compactGlossary,
            )
            val entries = entryJsons ?: buildResultEntryJsonStrings(results, activeProfile, context, groupPitches)
            config.toString() to entries
        }
        Log.i(
            "DictionaryRender",
            "payload_build_ms=${SystemClock.elapsedRealtime() - buildStart} results=${results.size} tabs=${tabs.size}",
        )
        configPayloadPair = configJson to renderSignature
        entryJsonsPair = builtEntryJsons to renderSignature
    }

    val bootstrapHtml = remember(context, isDark, isAmoled, seedColor, colorScheme, fontFamily, eInkMode, paginatedScrolling, paginatedScrollStepSize, scrollBehavior, activeProfile.languageCode, compactGlossary) {
        getDictionaryBootstrapHtml(
            context = context, colorScheme = colorScheme, isDark = isDark,
            isAmoled = isAmoled, seedColor = seedColor, fontFamily = fontFamily,
            eInkMode = eInkMode, paginatedScrolling = paginatedScrolling, paginatedScrollStepSize = paginatedScrollStepSize, scrollBehavior = scrollBehavior, languageCode = activeProfile.languageCode,
            compactGlossary = compactGlossary,
        )
    }

    Box(modifier = modifier.background(BgColor)) {
        AndroidView<WebView>(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx: android.content.Context ->
                val webView = webViewProvider?.invoke(ctx) ?: WebView(ctx)
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)

                if (webView.tag !is DictionaryWebViewState) {
                    prepareDictionaryWebViewShell(ctx, webView, bootstrapHtml, BgColor.toArgb())
                }
                (webView.tag as? DictionaryWebViewState)?.let { state ->
                    state.onContentInvalidated = {
                        onContentReadyChange?.invoke(false)
                        if (hideOnContentInvalidated) webView.alpha = 0f
                    }
                    state.onContentReady = {
                        onContentReadyChange?.invoke(true)
                        webView.alpha = 1f
                    }
                    if (state.pageReady) {
                        state.flush(webView)
                    }
                }

                webView.isClickable = true
                webView.isLongClickable = true
                webView.isFocusable = true
                webView.isFocusableInTouchMode = true
                if (requestFocusOnMount) {
                    webView.requestFocus()
                }
                webView.setOnLongClickListener { false }
                val dictPrefs = Injekt.get<DictionaryPreferences>()
                webView.setOnKeyListener { v, keyCode, event ->
                    if (event.action == android.view.KeyEvent.ACTION_DOWN && dictPrefs.volumeKeyNavigation().get()) {
                        when (keyCode) {
                            android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                                (v as? WebView)?.evaluateJavascript("window.DictionaryRenderer && window.DictionaryRenderer.navigate(-1);", null)
                                true
                            }
                            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                                (v as? WebView)?.evaluateJavascript("window.DictionaryRenderer && window.DictionaryRenderer.navigate(1);", null)
                                true
                            }
                            else -> false
                        }
                    } else false
                }

                webView
            },
            update = { webView: WebView ->
                val state = webView.tag as? DictionaryWebViewState ?: return@AndroidView
                if (state.bootstrapHtml != bootstrapHtml) {
                    state.reloadShell(webView, bootstrapHtml)
                }
                state.onAnkiLookup = onAnkiLookup
                state.ankiJsBridge.onAnkiLookup = onAnkiLookup
state.onRecursiveLookup = onRecursiveLookup
                    state.onTabSelect = onTabSelect
                state.onBack = onBack
                state.customCss = customCss
                state.fontSize = fontSize
                state.onContentInvalidated = {
                    onContentReadyChange?.invoke(false)
                    if (hideOnContentInvalidated) webView.alpha = 0f
                }
                state.onContentReady = {
                    onContentReadyChange?.invoke(true)
                    webView.alpha = 1f
                }

                webView.setBackgroundColor(BgColor.toArgb())

                if (state.pageReady) {
                    val enableRecursive = onRecursiveLookup != null
                    webView.evaluateJavascript("window.DictionaryRenderer && window.DictionaryRenderer.setRecursiveLookupEnabled($enableRecursive);", null)
                    state.injectCustomCss(webView)
                    state.injectFontSize(webView)
                    if (isLoading) {
                        state.clear(webView)
                    } else {
                        val (configPayload, configSig) = currentConfigPayload ?: (null to null)
                        val (builtEntryJsons, entriesSig) = currentEntryJsons ?: (null to null)
                        if (configPayload != null && builtEntryJsons != null && configSig == renderSignature && entriesSig == renderSignature) {
                            state.flush(webView, results, existingExpressions, mediaDataUris, renderSignature, configPayload, builtEntryJsons)
                        }
                    }
                } else {
                    val (configPayloadVal, configPayloadSig) = currentConfigPayload ?: (null to null)
                    val (builtEntryJsonsVal, entryJsonsSig) = currentEntryJsons ?: (null to null)
                    if (configPayloadVal != null && builtEntryJsonsVal != null && configPayloadSig == renderSignature && entryJsonsSig == renderSignature) {
                        state.pendingPayload = configPayloadVal
                        state.pendingEntryJsons = builtEntryJsonsVal
                        state.pendingResults = results
                        state.pendingExistingExpressions = existingExpressions
                        state.pendingMediaDataUris = mediaDataUris
                        state.pendingRenderSignature = renderSignature
                    }
                }
            },
            onRelease = { webView ->
                val state = webView.tag as? DictionaryWebViewState
                state?.clear(webView)
                state?.onContentInvalidated = null
                state?.onContentReady = null
                state?.lastPayload = null
                state?.lastResults = null
                state?.lastExistingExpressions = null
                state?.lastMediaDataUris = null
                state?.lastRenderSignature = null
                state?.pendingPayload = null
                state?.pendingEntryJsons = null
                state?.pendingResults = null
                state?.pendingExistingExpressions = null
                state?.pendingMediaDataUris = null
                state?.pendingRenderSignature = null
                state?.ankiJsBridge?.onAnkiLookup = null
                state?.payloadBridge?.rawPayloadJson = ""
                state?.payloadBridge?.rawEntryJsons = emptyList()
            },
        )
    }
}
