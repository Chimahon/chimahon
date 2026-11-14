package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import eu.kanade.domain.ui.UiPreferences
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Keeps a dictionary popup WebView warmed without attaching it to a reader view tree.
 *
 * Android's ViewGroup attach pass assumes its child array is stable while dispatching
 * attached state. Warming the popup by adding a hidden WebView to a reader root can race
 * with that pass on Android 16, so the warm shell lives here until a popup actually mounts.
 */
internal object DictionaryPopupWebViewWarmup {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var warmedWebView: WebView? = null

    fun warm(context: Context, languageCode: String = "") {
        if (!usesWebView()) return
        val appContext = context.applicationContext
        runOnMain {
            if (!usesWebView()) return@runOnMain
            val webView = warmedWebView?.takeIf { it.parent == null } ?: createWebView(appContext).also {
                warmedWebView = it
            }
            webView.setBaseContext(appContext)
            val shell = buildWarmShell(appContext, languageCode)
            prepareDictionaryWebViewShell(appContext, webView, shell.bootstrapHtml, shell.backgroundColor)
        }
    }

    private fun usesWebView() =
        Injekt.get<DictionaryPreferences>().renderer().get() == DictionaryPreferences.RENDERER_WEBVIEW

    fun acquire(context: Context, languageCode: String = ""): WebView {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Dictionary popup WebView must be acquired on the main thread"
        }

        val appContext = context.applicationContext
        val webView = warmedWebView?.takeIf { it.parent == null }?.also {
            warmedWebView = null
        } ?: createWebView(appContext)

        webView.setBaseContext(context)
        val shell = buildWarmShell(appContext, languageCode)
        prepareDictionaryWebViewShell(appContext, webView, shell.bootstrapHtml, shell.backgroundColor)
        return webView
    }

    fun recycle(context: Context, webView: WebView?) {
        if (webView == null) return
        val appContext = context.applicationContext
        runOnMain {
            (webView.parent as? ViewGroup)?.removeView(webView)
            stopDictionaryAudio(webView)
            webView.alpha = 0f
            webView.isClickable = false
            webView.isLongClickable = false
            webView.isFocusable = false
            webView.isFocusableInTouchMode = false
            webView.setOnKeyListener(null)
            webView.setOnLongClickListener(null)
            webView.resetDictionaryCallbacks()
            webView.setBaseContext(appContext)

            if (warmedWebView == null || warmedWebView === webView) {
                warmedWebView = webView
            } else {
                webView.destroy()
            }
        }
    }

    private fun createWebView(context: Context): WebView {
        return WebView(MutableContextWrapper(context.applicationContext))
    }

    private fun WebView.setBaseContext(context: Context) {
        (this.context as? MutableContextWrapper)?.baseContext = context
    }

    private fun buildWarmShell(context: Context, languageCode: String): WarmShell {
        val dictionaryPreferences = Injekt.get<DictionaryPreferences>()
        val uiPreferences = Injekt.get<UiPreferences>()
        val themeMode = dictionaryPreferences.themeMode().get()
        val customColor = dictionaryPreferences.customColor().get()
        val eInkMode = dictionaryPreferences.eInkMode().get()
        val resolved = resolveDictionaryTheme(
            themeMode = themeMode,
            customColor = customColor,
            eInkMode = eInkMode,
            forceDefaultTheme = false,
            systemIsDark = context.isSystemDark(),
            appThemeMode = uiPreferences.themeMode().get(),
            appAmoled = uiPreferences.themeDarkAmoled().get(),
            appColorTheme = uiPreferences.colorTheme().get(),
        )
        val colorScheme = if (resolved.useAppTheme) {
            getAppThemeColorScheme(context, resolved.isDark, resolved.isAmoled)
        } else {
            getDictionaryColorScheme(resolved.isDark, resolved.isAmoled, resolved.seedColor)
        }
        val backgroundColor = if (resolved.isAmoled && resolved.isDark) Color.Black else colorScheme.surface
        val bootstrapHtml = getDictionaryBootstrapHtml(
            context = context,
            colorScheme = colorScheme,
            isDark = resolved.isDark,
            isAmoled = resolved.isAmoled,
            seedColor = resolved.seedColor,
            fontFamily = dictionaryPreferences.fontFamily().get(),
            eInkMode = eInkMode,
            paginatedScrolling = dictionaryPreferences.paginatedScrolling().get(),
            paginatedScrollStepSize = dictionaryPreferences.paginatedScrollStepSize().get(),
            languageCode = languageCode,
        )

        return WarmShell(bootstrapHtml, backgroundColor.toArgb())
    }

    private fun Context.isSystemDark(): Boolean {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightMode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun WebView.resetDictionaryCallbacks() {
        val state = tag as? DictionaryWebViewState ?: return
        state.onAnkiLookup = null
        state.ankiJsBridge.onAnkiLookup = null
        state.onRecursiveLookup = null
        state.onTabSelect = null
        state.onBack = null
        state.onContentInvalidated = null
        state.onContentReady = null
        state.lastPayload = null
        state.lastResults = null
        state.lastExistingExpressions = null
        state.lastMediaDataUris = null
        state.lastRenderSignature = null
        state.pendingPayload = null
        state.pendingEntryJsons = null
        state.pendingResults = null
        state.pendingExistingExpressions = null
        state.pendingMediaDataUris = null
        state.pendingRenderSignature = null
        state.payloadBridge.rawPayloadJson = ""
        state.payloadBridge.rawEntryJsons = emptyList()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private data class WarmShell(
        val bootstrapHtml: String,
        val backgroundColor: Int,
    )
}

@Composable
internal fun rememberDictionaryPopupWebView(languageCode: String): WebView? {
    val context = LocalContext.current
    val preferences = remember { Injekt.get<DictionaryPreferences>() }
    val renderer by preferences.renderer().collectAsState()
    val webView = if (renderer == DictionaryPreferences.RENDERER_WEBVIEW) {
        remember(context, languageCode) { DictionaryPopupWebViewWarmup.acquire(context, languageCode) }
    } else null
    DisposableEffect(context, webView) {
        onDispose { DictionaryPopupWebViewWarmup.recycle(context, webView) }
    }
    return webView
}
