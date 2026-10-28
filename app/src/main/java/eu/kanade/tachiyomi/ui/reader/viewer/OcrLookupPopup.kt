package eu.kanade.tachiyomi.ui.reader.viewer

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.style.TextOverflow
import eu.kanade.domain.ui.UiPreferences
import chimahon.DictionaryRepository
import chimahon.KanjiEntry
import chimahon.KanjiResult
import chimahon.LookupResult
import chimahon.MediaInfo
import chimahon.anki.AnkiCardCreator
import chimahon.anki.AnkiMediaRequest
import chimahon.anki.AnkiMediaWarning
import chimahon.anki.AnkiResult
import chimahon.util.ImageEncoder
import eu.kanade.tachiyomi.ui.dictionary.buildKanjiEntryJson
import eu.kanade.tachiyomi.ui.dictionary.DictionaryEntryWebView
import eu.kanade.tachiyomi.ui.dictionary.compose.DictionaryEntryCompose
import eu.kanade.tachiyomi.ui.dictionary.compose.DictionaryCardPreparation
import eu.kanade.tachiyomi.ui.dictionary.compose.prepareDictionaryCards
import eu.kanade.tachiyomi.ui.dictionary.compose.GlossTab
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryColorScheme
import eu.kanade.tachiyomi.ui.dictionary.resolveDictionaryTheme
import eu.kanade.tachiyomi.ui.dictionary.TabInfo
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryPaths
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryTitle
import eu.kanade.tachiyomi.ui.dictionary.orderLookupResultsForDisplay
import eu.kanade.tachiyomi.ui.dictionary.stopDictionaryAudio
import eu.kanade.tachiyomi.util.system.toast
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID
import kotlin.math.roundToInt

/** Fire-and-forget scope for Anki mining jobs — survives popup dismissals */
private val miningScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * Measures the two numbers the user actually feels: when the first frame carrying real glossary
 * text reached the display, and when the popup stopped changing.
 *
 * The previous attempt used `ViewTreeObserver.OnDrawListener`. That fires at the top of
 * `View.draw()`, so it also fires for the spinner/empty frame the popup paints before its content
 * has been composed - it claimed 310ms for a frame the user saw at 744ms. Nothing is reported here
 * until [onContentDraw] has proven that real content was drawn, and the timestamp comes from a
 * Choreographer callback: the first frame callback after a content draw runs at the start of the
 * following traversal, which is the vsync the display was presenting that content on.
 *
 * Instrumentation only. It holds no Compose state and writes nothing, so it cannot affect layout,
 * composition or the oracle.
 */
private object ContentFrameTracer {
    private var profilingId = 0L
    /** Consecutive frames that drew nothing new before the popup counts as settled. */
    private const val SETTLE_FRAMES = 4

    /** Give up watching after this long so a permanently animating popup cannot log forever. */
    private const val WATCH_MS = 4000L

    // System.nanoTime() when armed. Choreographer's frameTimeNanos uses the same CLOCK_MONOTONIC
    // domain, so popupToContent/Settled are real durations. SystemClock.elapsedRealtime() is a
    // different domain (advances during deep sleep) and produced multi-day negatives against it.
    private var start = 0L
    private var label = ""
    private var terms = 0
    private var armed = false
    private var sawContent = false
    private var frames = 0
    private var stable = 0
    private var lastHeight = Int.MIN_VALUE
    private var drewSinceFrame = false
    private var settledLogged = false

    private val callback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!armed) return
            val elapsedMs = (frameTimeNanos - start) / 1_000_000L

            if (!sawContent) {
                // Only claim a content frame once a draw has proven content exists. Until then this
                // is still the spinner, and reporting it is exactly the bug this replaced.
                if (drewSinceFrame) {
                    sawContent = true
                    chimahon.diagnostics.LookupProfiler.mark(profilingId, "content.container.callback")
                    Log.i(
                        "DictPerf",
                        "contentFrame lookup='$label' popupToContentMs=$elapsedMs terms=$terms",
                    )
                }
            } else {
                frames++
                if (stable >= SETTLE_FRAMES && !settledLogged) {
                    settledLogged = true
                    Log.i(
                        "DictPerf",
                        "settled lookup='$label' popupToSettledMs=$elapsedMs terms=$terms frames=$frames",
                    )
                }
                if (elapsedMs > WATCH_MS) {
                    Log.i(
                        "DictPerf",
                        "watchTimeout lookup='$label' afterMs=$elapsedMs frames=$frames settled=$settledLogged",
                    )
                    armed = false
                    return
                }
            }

            drewSinceFrame = false
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun arm(startAt: Long, query: String, termCount: Int, requestId: Long = 0) {
        profilingId = requestId
        if (startAt == 0L) return
        armed = false
        start = startAt
        label = query
        terms = termCount
        sawContent = false
        frames = 0
        stable = 0
        lastHeight = Int.MIN_VALUE
        drewSinceFrame = false
        settledLogged = false
        armed = true
        android.view.Choreographer.getInstance().postFrameCallback(callback)
    }

    fun disarm() {
        armed = false
    }

    /**
     * Invoked from `drawWithContent` on the results container, so it only runs when that container
     * actually drew. A height change means content is still arriving (media decoded, ruby rewrapped,
     * a table finished measuring), which resets the settle counter.
     */
    fun onContentDraw(heightPx: Int) {
        if (!armed) return
        if (!sawContent && !drewSinceFrame) chimahon.diagnostics.LookupProfiler.mark(profilingId, "content.container.draw", "height=$heightPx")
        drewSinceFrame = true
        if (heightPx != lastHeight) {
            lastHeight = heightPx
            stable = 0
            settledLogged = false
        }
    }
}

/** One entry in the recursive-lookup history stack. */
private data class LookupFrame(
    val id: String = UUID.randomUUID().toString(),
    val query: String,
    val sentence: String,
    val sentenceOffset: Int,
    val results: List<LookupResult>,
    val styles: List<chimahon.DictionaryStyle>,
    val mediaDataUris: Map<String, String>,
    val existingExpressions: Set<String>,
    val existingCardIds: Map<String, Long> = emptyMap(),
    val entryJsons: List<String>? = null,
    val profilingId: Long = 0,
)

private data class RecursivePopupRequest(
    val id: String = UUID.randomUUID().toString(),
    val query: String,
    val sentence: String,
    val sentenceOffset: Int,
    val tapX: Float? = null,
    val tapY: Float? = null,
    val anchorWidth: Float = 0f,
    val anchorHeight: Float = 0f,
    val deferredResult: kotlinx.coroutines.Deferred<chimahon.DictionaryRepository.LookupResult2>,
    val entryJsons: List<String>? = null,
)


@Composable
fun OcrLookupPopup(
    visible: Boolean = true,
    lookupString: String,
    fullText: String,
    charOffset: Int,
    onDismiss: () -> Unit,
    webView: WebView?,
    repository: DictionaryRepository,
    anchorX: Float,
    anchorY: Float,
    anchorWidth: Float = 0f,
    anchorHeight: Float = 0f,
    isVertical: Boolean,
    activeProfile: chimahon.anki.AnkiProfile,
    type: String = "manga",
    mediaInfo: MediaInfo? = null,
    screenshot: Bitmap? = null,
    onRequestScreenshot: (suspend () -> Bitmap?)? = null,
    onRequestAnimatedScene: (suspend () -> ByteArray?)? = null,
    onRequestSentenceAudio: (suspend () -> ByteArray?)? = null,
    mediaRequest: AnkiMediaRequest? = null,
    onAnkiMediaWarnings: (List<AnkiMediaWarning>) -> Unit = {},
    onCropTriggered: ((Long, Int?) -> Unit)? = null,
    initialLookupDeferred: kotlinx.coroutines.Deferred<chimahon.DictionaryRepository.LookupResult2>? = null,
    initialEntryJsons: List<String>? = null,
    usePopup: Boolean = true,
    onTermMatched: ((Int, Int) -> Unit)? = null,
    onContentReadyChange: ((Boolean) -> Unit)? = null,
    dismissOnOutsideTap: Boolean = false,
    isRecursiveChild: Boolean = false,
    modifier: Modifier = Modifier,
    titleId: String? = null,
) {
    val context = LocalContext.current
    remember(context) { eu.kanade.tachiyomi.ui.dictionary.LookupProfilingAndroid.install(context) }
    val scope = rememberCoroutineScope()
    val dictionaryPrefs = remember { Injekt.get<DictionaryPreferences>() }
    val rendererMode by dictionaryPrefs.renderer().collectAsState()
    var isLoading by remember {
        mutableStateOf(initialLookupDeferred?.isCompleted != true)
    }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // ── Lookup history stack (single state to batch updates) ──────────────
    data class LookupStackState(
        val stack: List<LookupFrame> = emptyList(),
        val activeIndex: Int = 0,
    ) {
        val currentFrame: LookupFrame? get() = stack.getOrNull(activeIndex)
        fun buildTabs(): List<TabInfo> = stack.mapIndexed { i, frame ->
            TabInfo(label = frame.query.take(16), active = i == activeIndex)
        }
    }
    var lookupStackState by remember { mutableStateOf(LookupStackState()) }
    val currentFrame = lookupStackState.currentFrame
    val results = currentFrame?.results ?: emptyList()
    val styles = currentFrame?.styles ?: emptyList()
    val mediaDataUris = currentFrame?.mediaDataUris ?: emptyMap()
    val existingExpressions = currentFrame?.existingExpressions ?: emptySet()
    val entryJsons = currentFrame?.entryJsons
    var childPopupRequest by remember { mutableStateOf<RecursivePopupRequest?>(null) }
    var contentReady by remember(visible) { mutableStateOf(false) }
    // Tracks the lookupGeneration value at the time content was last painted.
    // Used to distinguish a fresh lookup (hide stale content) from a same-lookup
    // invalidation like an Anki-status patch (keep content visible).
    var lastRenderedLookupGeneration by remember { mutableIntStateOf(-1) }
    var lookupGeneration by remember { mutableIntStateOf(0) }
    // The lookup this popup started itself, so a newer keystroke can abandon it instead of letting a
    // stale query run to completion. Caller-supplied deferreds are never cancelled - the caller may
    // still want that result.
    var ownedLookup by remember { mutableStateOf<kotlinx.coroutines.Deferred<chimahon.DictionaryRepository.LookupResult2>?>(null) }
    var cardPreparation by remember { mutableStateOf<DictionaryCardPreparation?>(null) }
    val visibleRef = rememberUpdatedState(visible)

    fun dismissPopup() {
        if (!isRecursiveChild) {
            childPopupRequest = null
            webView?.evaluateJavascript(
                "window.DictionaryRenderer && window.DictionaryRenderer.clearRecursiveSelection();",
                null,
            )
        }
        onDismiss()
    }

    androidx.compose.runtime.DisposableEffect(webView, childPopupRequest) {
        if (childPopupRequest != null) {
            webView?.setOnScrollChangeListener { _, _, _, _, _ ->
                childPopupRequest = null
                webView?.evaluateJavascript(
                    "window.DictionaryRenderer && window.DictionaryRenderer.clearRecursiveSelection();",
                    null,
                )
            }
        }
        onDispose { webView?.setOnScrollChangeListener(null) }
    }


    val density = LocalDensity.current

    val dictionaryPreferences = remember { Injekt.get<DictionaryPreferences>() }
    val popupWidthPref by dictionaryPreferences.popupWidth().collectAsState()
    val popupHeightPref by dictionaryPreferences.popupHeight().collectAsState()
    val popupModePref by dictionaryPreferences.popupMode().collectAsState()
    val popupFontSizePref by dictionaryPreferences.fontSize().collectAsState()
    val eInkMode by dictionaryPreferences.eInkMode().collectAsState()
    val popupSwipeToDismissPref by dictionaryPreferences.popupSwipeToDismiss().collectAsState()
    val popupSwipeThresholdPref by dictionaryPreferences.popupSwipeThreshold().collectAsState()

    val ankiEnabled = activeProfile.ankiEnabled
    val ankiDeck = activeProfile.ankiDeck
    val ankiModel = activeProfile.ankiModel
    val ankiFieldMap = activeProfile.ankiFieldMap
    val ankiDupCheck = activeProfile.ankiDupCheck
    val ankiDupScope = activeProfile.ankiDupScope
    val ankiDupAction = activeProfile.ankiDupAction
    val ankiTags = activeProfile.ankiTags
    val cropMode = activeProfile.ankiCropMode
    val ankiSyncOnCreate = activeProfile.ankiSyncOnCreate

    val showFreqHarmonic by dictionaryPreferences.showFrequencyHarmonic().collectAsState()
    val showFreqAverage by dictionaryPreferences.showFrequencyAverage().collectAsState()
    val groupTerms by dictionaryPreferences.groupTerms().collectAsState()
    val showPitchDiagram by dictionaryPreferences.showPitchDiagram().collectAsState()
    val showPitchNumber by dictionaryPreferences.showPitchNumber().collectAsState()
    val showPitchText by dictionaryPreferences.showPitchText().collectAsState()
    val customCss by dictionaryPreferences.customCss().collectAsState()
    val wordAudioEnabled by dictionaryPreferences.wordAudioEnabled().collectAsState()
    val wordAudioAutoplay by dictionaryPreferences.wordAudioAutoplay().collectAsState()
    val groupPitches by dictionaryPreferences.groupPitches().collectAsState()

    val systemIsDark = isSystemInDarkTheme()
    val themeMode by dictionaryPreferences.themeMode().collectAsState()
    val customColor by dictionaryPreferences.customColor().collectAsState()

    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val appAmoled by uiPreferences.themeDarkAmoled().collectAsState()
    val appThemeMode by uiPreferences.themeMode().collectAsState()
    val appColorTheme by uiPreferences.colorTheme().collectAsState()
    val resolvedTheme = remember(
        themeMode,
        customColor,
        eInkMode,
        systemIsDark,
        appThemeMode,
        appAmoled,
        appColorTheme,
    ) {
        resolveDictionaryTheme(
            themeMode = themeMode,
            customColor = customColor,
            eInkMode = eInkMode,
            forceDefaultTheme = false,
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

    /** Perform a dictionary lookup and push a new frame onto the stack. */
    fun pushLookup(
        query: String,
        isRecursive: Boolean = false,
        sentenceContext: String = fullText,
        sentenceOffsetContext: Int = charOffset,
        deferredResult: kotlinx.coroutines.Deferred<chimahon.DictionaryRepository.LookupResult2>? = null,
        entryJsons: List<String>? = null,
    ) {
        if (entryJsons != null) {
            val frame = LookupFrame(
                id = UUID.randomUUID().toString(),
                query = query,
                sentence = sentenceContext,
                sentenceOffset = sentenceOffsetContext,
                results = emptyList(),
                styles = emptyList(),
                mediaDataUris = emptyMap(),
                existingExpressions = emptySet(),
                entryJsons = entryJsons,
            )
            val truncated = lookupStackState.stack.take(lookupStackState.activeIndex + 1) + frame
            lookupStackState = LookupStackState(stack = truncated, activeIndex = truncated.size - 1)
            errorMessage = null
            isLoading = false
            return
        }

        val cleanQuery = if (isRecursive) {
            query.replace(Regex("[\\s\\p{Punct}「」『』【】（）〔〕［］｛｝〈〉《》…、。！？!?]+"), "").trim()
        } else {
            query.trim()
        }

        if (isRecursive) {
            if (cleanQuery.isBlank()) return
            // Ignore if entirely ascii/english letters and numbers
            if (cleanQuery.all { it.code <= 127 }) return
        }

        val finalQuery = if (isRecursive) cleanQuery else query
        val nativeLookup = rendererMode == DictionaryPreferences.RENDERER_COMPOSE
        val generation = ++lookupGeneration
        cardPreparation?.cancel()
        cardPreparation = null
        contentReady = false
        val shouldShowLoading = !isRecursive && lastRenderedLookupGeneration < 0

        fun handleResult(result: chimahon.DictionaryRepository.LookupResult2, orderedResults: List<LookupResult>, lookupStart: Long,
            preparedCards: DictionaryCardPreparation? = null) {
            chimahon.diagnostics.LookupProfiler.mark(result.profilingId, "popup.main.resume")
            if (generation != lookupGeneration || nativeLookup && !visibleRef.value) {
                preparedCards?.cancel()
                return
            }
            if (isRecursive && result.results.isEmpty()) {
                preparedCards?.cancel()
                if (shouldShowLoading) isLoading = false
                return
            }

            // Create frame and push immediately — popup shows NOW
            val frame = LookupFrame(
                id = UUID.randomUUID().toString(),
                query = finalQuery,
                sentence = sentenceContext,
                sentenceOffset = sentenceOffsetContext,
                results = orderedResults,
                styles = result.styles,
                mediaDataUris = result.mediaDataUris,
                existingExpressions = emptySet(),
                profilingId = result.profilingId,
            )

            // Truncate any forward history past the current index, then push
            val truncated = (if (nativeLookup && !isRecursive) emptyList()
                else lookupStackState.stack.take(lookupStackState.activeIndex + 1)) + frame
            cardPreparation = preparedCards
            lookupStackState = LookupStackState(stack = truncated, activeIndex = truncated.size - 1)
            chimahon.diagnostics.LookupProfiler.mark(result.profilingId, "results.publish")
            errorMessage = result.error

            // Arm popup -> content-frame tracing now that results are published. The popup owns this
            // clock: traversal, composition and draw happen here, while lookup origin timestamps live
            // in whichever host opened the popup.
            ContentFrameTracer.arm(
                java.lang.System.nanoTime(),
                finalQuery,
                orderedResults.size,
                result.profilingId,
            )

            isLoading = false

            if (!isRecursive && orderedResults.isNotEmpty()) {
                val firstMatched = orderedResults.firstOrNull()?.matched
                if (firstMatched != null) {
                    val charCount = firstMatched.codePointCount(0, firstMatched.length)
                    val matchOffset = finalQuery.indexOf(firstMatched).coerceAtLeast(0)
                    scope.launch(Dispatchers.Main) {
                        onTermMatched?.invoke(charCount, matchOffset)
                    }
                }
            }

            // Anki duplicate check runs in background, doesn't block UI
            if (ankiEnabled && orderedResults.isNotEmpty()) {
                val uniqueExpressions = orderedResults.map { it.term.expression }.distinct()
                scope.launch(Dispatchers.IO) {
                    val existingCardIds = AnkiCardCreator.checkExistingCardIds(
                        context = context,
                        expressions = uniqueExpressions,
                        deckName = ankiDeck,
                        dupScope = ankiDupScope,
                    )
                    withContext(Dispatchers.Main) {
                        val stack = lookupStackState.stack.toMutableList()
                        val frameIndex = stack.indexOfFirst { it.id == frame.id }
                        if (frameIndex >= 0) {
                            stack[frameIndex] = stack[frameIndex].copy(
                                existingExpressions = existingCardIds.keys,
                                existingCardIds = existingCardIds,
                            )
                            lookupStackState = lookupStackState.copy(stack = stack)
                        }
                        Log.i(
                            "DictionaryPopup",
                            "anki_check_ms=${android.os.SystemClock.elapsedRealtime() - lookupStart} expressions=${uniqueExpressions.size}",
                        )
                    }
                }
            }

            // Load media in background
            scope.launch(Dispatchers.IO) {
                val media = chimahon.diagnostics.LookupProfiler.section(result.profilingId, "media.load") { repository.loadMediaAsync(finalQuery, orderedResults) }
                if (media.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        val stack = lookupStackState.stack.toMutableList()
                        val frameIndex = stack.indexOfFirst { it.id == frame.id }
                        if (frameIndex >= 0) {
                            stack[frameIndex] = stack[frameIndex].copy(mediaDataUris = media)
                            lookupStackState = lookupStackState.copy(stack = stack)
                        }
                    }
                }
            }
        }

        // Every lookup now goes through Dispatchers.IO, recursive ones included. This used to run
        // inline on the calling thread - a click handler, so the native lookup AND
        // orderLookupResultsForDisplay executed on the main thread, blocking on the
        // DictionaryRepository monitor whenever a background lookup already held it. The "skip the
        // dispatch, it only costs ~5-20ms" comment described the uncontended case; contended, it
        // meant a frozen UI on every word tap.
        val lookupStart = android.os.SystemClock.elapsedRealtime()
        // Abandon the previous popup-owned lookup. Cancelling cannot interrupt an in-flight JNI
        // call, so this does not cut short the native query itself - it does stop the deinflection
        // loop, skips result handling, and prevents a backlog of stale queries piling up while the
        // user types.
        ownedLookup?.cancel()
        val profilingId = if (deferredResult == null) chimahon.diagnostics.LookupProfiler.start(finalQuery) else 0L
        chimahon.diagnostics.LookupProfiler.mark(profilingId, "repo.queued")
        val deferred = deferredResult ?: scope.async(Dispatchers.IO) {
            val termPaths = chimahon.diagnostics.LookupProfiler.section(profilingId, "popup.paths") { getDictionaryPaths(context, activeProfile) }
            repository.lookupWithSearchResolution(finalQuery, termPaths, activeProfile, profilingId)
        }.also { if (deferredResult == null) ownedLookup = it }

        if (!deferred.isCompleted || nativeLookup) {
            // Must await in a coroutine
            scope.launch {
                if (shouldShowLoading) {
                    isLoading = true
                }
                errorMessage = null
                try {
                    val result = deferred.await()
                    val (orderedResults, preparedCards) = withContext(Dispatchers.Default) {
                        val ordered = chimahon.diagnostics.LookupProfiler.section(result.profilingId, "results.order") {
                            orderLookupResultsForDisplay(result.results, activeProfile, context)
                        }
                        val preparation = if (nativeLookup && generation == lookupGeneration && visibleRef.value) {
                            scope.prepareDictionaryCards(ordered, groupTerms, activeProfile.dictionaryOrder, result.profilingId) {
                                getDictionaryTitle(context, it)
                            }
                        } else null
                        ordered to preparation
                    }
                    Log.i(
                        "DictPerf",
                        "popup lookup='$finalQuery' recursive=$isRecursive " +
                            "ms=${android.os.SystemClock.elapsedRealtime() - lookupStart} " +
                            "terms=${result.results.size} " +
                            "dicts=${result.results.flatMap { r -> r.term.glossaries.map { it.dictName } }.distinct().size} " +
                            "cssBytes=${result.styles.sumOf { it.styles.length }} " +
                            "stale=${generation != lookupGeneration}",
                    )
                    if (generation == lookupGeneration) ownedLookup = null
                    handleResult(result, orderedResults, lookupStart, preparedCards)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (generation != lookupGeneration) return@launch
                    errorMessage = e.message ?: "Lookup failed"
                    isLoading = false
                }
            }
        } else {
            // Deferred already finished, so there is nothing to wait for and no blocking risk. Kept
            // synchronous deliberately: this is the WebView main path, and routing it through a
            // launch would add a dispatch before handleResult updates state - a frame of extra
            // latency on the hot initial-load path for no benefit.
            errorMessage = null
            try {
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                val result = deferred.getCompleted()
                val orderedResults = chimahon.diagnostics.LookupProfiler.section(result.profilingId, "results.order") { orderLookupResultsForDisplay(result.results, activeProfile, context) }
                if (generation == lookupGeneration) ownedLookup = null
                Log.i(
                    "DictPerf",
                    "popup(pre-completed) lookup='$finalQuery' recursive=$isRecursive " +
                        "ms=${android.os.SystemClock.elapsedRealtime() - lookupStart} " +
                        "terms=${result.results.size}",
                )
                handleResult(result, orderedResults, lookupStart)
            } catch (e: Exception) {
                if (generation != lookupGeneration) return
                errorMessage = e.message ?: "Lookup failed"
                isLoading = false
            }
        }
    }
    val recursiveNavMode by dictionaryPreferences.recursiveLookupMode().collectAsState()

    val windowManager = context.getSystemService(android.view.WindowManager::class.java)!!
    val (screenWidthPx, screenHeightPx) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = windowManager.currentWindowMetrics.bounds
        Pair(bounds.width().toFloat(), bounds.height().toFloat())
    } else {
        @Suppress("DEPRECATION")
        val point = android.graphics.Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(point)
        Pair(point.x.toFloat(), point.y.toFloat())
    }

    val (maxWidthDp, maxHeightDp) = with(density) {
        val sw = screenWidthPx.toDp()
        val sh = screenHeightPx.toDp()
        val childMaxWidth = (sw - 12.dp).coerceAtLeast(120.dp)
        val childMaxHeight = maxOf(
            anchorY - with(density) { 12.dp.toPx() },
            screenHeightPx - anchorY - anchorHeight - with(density) { 12.dp.toPx() },
        ).toDp().coerceAtLeast(120.dp)
        Pair(
            if (isRecursiveChild) popupWidthPref.dp.coerceAtMost(childMaxWidth).coerceAtLeast(120.dp)
            else popupWidthPref.dp.coerceAtLeast(280.dp).coerceAtMost(sw * 0.9f),
            if (isRecursiveChild) popupHeightPref.dp.coerceAtMost(childMaxHeight).coerceAtLeast(120.dp)
            else popupHeightPref.dp.coerceAtLeast(200.dp).coerceAtMost(sh * 0.8f),
        )
    }

    val popupWidthPx = with(density) { maxWidthDp.toPx() }
    val popupHeightPx = with(density) { maxHeightDp.toPx() }

    val paddingPx = with(density) { 8.dp.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }

    val screenshotFieldMapped = remember(ankiFieldMap) {
        try {
            val fieldMap = org.json.JSONObject(ankiFieldMap)
            fieldMap.keys().asSequence().any { key ->
                val value = fieldMap.getString(key)
                value.contains(chimahon.anki.Marker.SCREENSHOT)
            }
        } catch (e: Exception) {
            false
        }
    }

    val sentenceAudioFieldMapped = remember(ankiFieldMap) {
        try {
            val fieldMap = org.json.JSONObject(ankiFieldMap)
            fieldMap.keys().asSequence().any { key ->
                val value = fieldMap.getString(key)
                value.contains(chimahon.anki.Marker.SENTENCE_AUDIO)
            }
        } catch (e: Exception) {
            false
        }
    }

    fun performAnkiLookup(
        index: Int,
        glossaryIndex: Int?,
        selectedDict: String? = null,
        popupSelection: String? = null,
        forceOpen: Boolean = false,
    ) {
        val result = results.getOrNull(index) ?: return
        val miningFrame = currentFrame
        val miningSentence = miningFrame?.sentence ?: fullText
        val miningOffset = result.matched
            .takeIf { it.isNotBlank() }
            ?.let { miningSentence.indexOf(it) }
            ?.takeIf { it >= 0 }
            ?: (miningFrame?.sentenceOffset ?: charOffset)

        // Local helper to update the state, which triggers the optimized JS call via DictionaryEntryWebView
        fun updateStatus(expression: String, noteId: Long? = null) {
            val frame = currentFrame ?: return
            val stack = lookupStackState.stack.toMutableList()
            val frameIndex = stack.indexOfFirst { it.id == frame.id }
            if (frameIndex >= 0) {
                val newExisting = frame.existingExpressions + expression
                val newCardIds = noteId?.let { frame.existingCardIds + (expression to it) } ?: frame.existingCardIds
                stack[frameIndex] = stack[frameIndex].copy(
                    existingExpressions = newExisting,
                    existingCardIds = newCardIds,
                )
                lookupStackState = lookupStackState.copy(stack = stack)
            }
        }

        if (forceOpen) {
            val expression = result.term.expression
            val noteId = currentFrame?.existingCardIds?.get(expression)
            if (noteId != null) {
                chimahon.anki.AnkiDroidBridge(context).guiEditNote(noteId)
                return
            }
            miningScope.launch {
                val fallbackNoteId = AnkiCardCreator.findExistingCardId(
                    context = context,
                    expression = expression,
                    deckName = ankiDeck,
                    dupScope = ankiDupScope,
                ) ?: return@launch
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    updateStatus(expression, fallbackNoteId)
                    chimahon.anki.AnkiDroidBridge(context).guiEditNote(fallbackNoteId)
                }
            }
            return
        }

        val shouldUseCropMode = screenshotFieldMapped && cropMode == "crop" && onCropTriggered != null

        if (shouldUseCropMode) {
            miningScope.launch {
                val sentenceAudioBytes = if (sentenceAudioFieldMapped && mediaRequest == null) {
                    onRequestSentenceAudio?.invoke()
                } else {
                    null
                }
                val ankiResult = AnkiCardCreator.addToAnki(
                    context = context,
                    result = result,
                    deck = ankiDeck,
                    model = ankiModel,
                    fieldMapJson = ankiFieldMap,
                    tags = ankiTags,
                    dupCheck = ankiDupCheck,
                    dupScope = ankiDupScope,
                    dupAction = ankiDupAction,
                    sentence = miningSentence,
                    offset = miningOffset,
                    media = mediaInfo,
                    sentenceAudioBytes = sentenceAudioBytes,
                    glossaryIndex = glossaryIndex,
                    selection = result.matched,
                    selectedDict = selectedDict,
                    popupSelection = popupSelection,
                    styles = styles,
                    forceOpen = forceOpen,
                    type = type,
                    syncOnCreate = ankiSyncOnCreate,
                    profileId = activeProfile.id,
                    titleId = titleId,
                    mediaRequest = mediaRequest,
                )
                if (ankiResult is AnkiResult.Success || ankiResult is AnkiResult.CardExists || ankiResult is AnkiResult.OpenCard) {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        when (ankiResult) {
                            is AnkiResult.Success -> {
                                updateStatus(result.term.expression, ankiResult.noteId)
                                onAnkiMediaWarnings(ankiResult.mediaWarnings)
                                dismissPopup()
                                onCropTriggered.invoke(ankiResult.noteId, glossaryIndex)
                            }
                            is AnkiResult.CardExists -> {
                                updateStatus(result.term.expression, ankiResult.noteId)
                                context.toast(MR.strings.anki_card_exists)
                            }
                            is AnkiResult.OpenCard -> {
                                updateStatus(result.term.expression, ankiResult.noteId)
                                chimahon.anki.AnkiDroidBridge(context).guiEditNote(ankiResult.noteId)
                            }
                            else -> {}
                        }
                    }
                } else {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        when (ankiResult) {
                            is AnkiResult.PermissionDenied -> context.toast(MR.strings.pref_anki_permission_denied)
                            is AnkiResult.Error -> context.toast(
                                context.stringResource(MR.strings.anki_card_error, ankiResult.message),
                            )
                            is AnkiResult.NotConfigured -> context.toast(MR.strings.anki_not_configured)
                            else -> {}
                        }
                    }
                }
            }
        } else {
            miningScope.launch {
                val encoding = if (screenshotFieldMapped && cropMode != "no_screenshot") {
                    onRequestScreenshot?.invoke()?.let { ImageEncoder.encode(it) }
                } else {
                    null
                }
                val screenshotBytes = if (
                    cropMode == chimahon.anki.AnkiScreenshotMode.ANIMATED_SCENE.storageValue &&
                    onRequestAnimatedScene != null
                ) {
                    onRequestAnimatedScene.invoke()
                } else {
                    encoding?.bytes
                }
                val sentenceAudioBytes = if (sentenceAudioFieldMapped && mediaRequest == null) {
                    onRequestSentenceAudio?.invoke()
                } else {
                    null
                }
                val ankiResult = AnkiCardCreator.addToAnki(
                    context = context,
                    result = result,
                    deck = ankiDeck,
                    model = ankiModel,
                    fieldMapJson = ankiFieldMap,
                    tags = ankiTags,
                    dupCheck = ankiDupCheck,
                    dupScope = ankiDupScope,
                    dupAction = ankiDupAction,
                    sentence = miningSentence,
                    offset = miningOffset,
                    media = mediaInfo,
                    glossaryIndex = glossaryIndex,
                    screenshotBytes = screenshotBytes,
                    sentenceAudioBytes = sentenceAudioBytes,
                    selection = result.matched,
                    selectedDict = selectedDict,
                    popupSelection = popupSelection,
                    styles = styles,
                    forceOpen = forceOpen,
                    type = type,
                    syncOnCreate = ankiSyncOnCreate,
                    profileId = activeProfile.id,
                    titleId = titleId,
                    mediaRequest = mediaRequest,
                )
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    when (ankiResult) {
                        is AnkiResult.Success -> {
                            updateStatus(result.term.expression, ankiResult.noteId)
                            onAnkiMediaWarnings(ankiResult.mediaWarnings)
                            context.toast(MR.strings.anki_card_added)
                        }
                        is AnkiResult.CardExists -> {
                            updateStatus(result.term.expression, ankiResult.noteId)
                            context.toast(MR.strings.anki_card_exists)
                        }
                        is AnkiResult.OpenCard -> {
                            updateStatus(result.term.expression, ankiResult.noteId)
                            chimahon.anki.AnkiDroidBridge(context).guiEditNote(ankiResult.noteId)
                        }
                        is AnkiResult.PermissionDenied -> context.toast(MR.strings.pref_anki_permission_denied)
                        is AnkiResult.Error -> context.toast(
                            context.stringResource(MR.strings.anki_card_error, ankiResult.message),
                        )
                        is AnkiResult.NotConfigured -> context.toast(MR.strings.anki_not_configured)
                    }
                }
            }
        }
    }

    // Impl stays fresh per recomposition (it captures the current `performAnkiLookup`
    // instance, which closes over the current results/frame/field-map); the ref + remembered
    // wrapper give downstream a stable identity. Remembering the forwarding lambda directly
    // would pin the first lookup's results and mine the wrong card after a re-lookup.
    val onAnkiLookupImpl: ((Int, Int?, String?, String?, Boolean) -> Unit)? = if (ankiEnabled) {
        { index, glossaryIndex, selectedDict, popupSelection, forceOpen ->
            performAnkiLookup(index, glossaryIndex, selectedDict, popupSelection, forceOpen)
        }
    } else {
        null
    }
    val onAnkiLookupRef = rememberUpdatedState(onAnkiLookupImpl)
    val onAnkiLookup: ((Int, Int?, String?, String?, Boolean) -> Unit)? = remember(ankiEnabled) {
        if (ankiEnabled) {
            { a, b, c, d, e -> onAnkiLookupRef.value?.invoke(a, b, c, d, e) }
        } else {
            null
        }
    }

    // === Popup Positioning: priority-based 4-direction ===
    data class PopupLayoutResult(val x: Float, val y: Float, val widthPx: Float, val heightPx: Float)

    val layoutResult = remember(
        anchorX, anchorY, anchorWidth, anchorHeight,
        screenWidthPx, screenHeightPx, popupWidthPx, popupHeightPx, isVertical, popupModePref,
        isRecursiveChild,
    ) {
        val w: Float
        val h: Float
        val bestX: Float
        val bestY: Float

        when (if (isRecursiveChild) "floating" else popupModePref) {
            "full_width" -> {
                w = screenWidthPx
                h = minOf(popupHeightPx, screenHeightPx)
                bestX = 0f
                val expH = anchorHeight
                val bottomY = screenHeightPx - h
                val overlapsWord = anchorWidth > 0f && anchorHeight > 0f &&
                    bottomY < anchorY + expH
                bestY = if (overlapsWord) 0f else bottomY
            }
            "full_height" -> {
                w = minOf(popupWidthPx, screenWidthPx * 0.5f, screenWidthPx - paddingPx * 2)
                h = screenHeightPx - paddingPx * 2
                val acx = anchorX + anchorWidth / 2f
                bestY = paddingPx
                bestX = if (acx < screenWidthPx / 2f) {
                    (screenWidthPx - w - paddingPx).coerceAtLeast(paddingPx)
                } else {
                    paddingPx
                }
            }
            else -> {
            w = minOf(popupWidthPx, screenWidthPx)
            h = minOf(popupHeightPx, screenHeightPx)

            val ax = anchorX
            val ay = anchorY
            val aw = anchorWidth
            val ah = anchorHeight
            val acx = ax + aw / 2f
            val acy = ay + ah / 2f

            val expW = maxOf(aw, 1f)
            val expH = maxOf(ah, 1f)

            // 4 candidate positions (top-left corner of popup)
            data class Pos(val x: Float, val y: Float)

            val right  = Pos(ax + expW + gapPx, acy - h / 2f) // Right of full term
            val left   = Pos(ax - w - gapPx, acy - h / 2f) // Left of anchor
            val below  = Pos(acx - w / 2f, ay + expH + gapPx) // Below full term
            val above  = Pos(acx - w / 2f, ay - h - gapPx) // Above anchor

            val all = listOf(right, left, below, above)

            // Priority order: 0=Right, 1=Left, 2=Below, 3=Above
            val order = if (isRecursiveChild) {
                if (acy < screenHeightPx / 2f) listOf(2, 3, 0, 1) else listOf(3, 2, 0, 1)
            } else if (isVertical) {
                if (acx < screenWidthPx / 2f) listOf(0, 1, 2, 3) else listOf(1, 0, 2, 3)
            } else {
                if (acy < screenHeightPx / 2f) listOf(2, 3, 0, 1) else listOf(3, 2, 0, 1)
            }

            var bx = paddingPx
            var by = paddingPx
            var found = false

            for (idx in order) {
                val p = all[idx]
                val maxCx = (screenWidthPx - w - paddingPx).coerceAtLeast(paddingPx)
                val maxCy = (screenHeightPx - h - paddingPx).coerceAtLeast(paddingPx)
                val cx = p.x.coerceIn(paddingPx, maxCx)
                val cy = p.y.coerceIn(paddingPx, maxCy)

                val overlaps = cx < ax + expW && cx + w > ax &&
                    cy < ay + expH && cy + h > ay

                if (!overlaps) {
                    bx = cx
                    by = cy
                    found = true
                    break
                }
            }

            if (!found) {
                val pref = all[order[0]]
                bestX = pref.x.coerceIn(paddingPx, (screenWidthPx - w - paddingPx).coerceAtLeast(paddingPx))
                bestY = pref.y.coerceIn(paddingPx, (screenHeightPx - h - paddingPx).coerceAtLeast(paddingPx))
            } else {
                bestX = bx
                bestY = by
            }
            }
        }

        PopupLayoutResult(bestX, bestY, w, h)
    }

    val actualWidthDp = with(density) { layoutResult.widthPx.toDp() }
    val actualHeightDp = with(density) { layoutResult.heightPx.toDp() }


    LaunchedEffect(visible) {
        if (!visible) {
            cardPreparation?.cancel()
            cardPreparation = null
            webView?.let { stopDictionaryAudio(it) }
            if (!isRecursiveChild) {
                childPopupRequest = null
                webView?.evaluateJavascript(
                    "window.DictionaryRenderer && window.DictionaryRenderer.clearRecursiveSelection();",
                    null,
                )
            }
        }
    }

    // Keys are exactly what determines the lookup: Anki flags only affect the status patch
    // applied after results land, so toggling them must not repeat the native query (it used to
    // re-fire the whole lookup each time the model or deck changed while open).
    LaunchedEffect(lookupString, visible, initialLookupDeferred) {
        if (!visible) return@LaunchedEffect
        if (lookupString.isBlank()) {
            lookupGeneration++
            lookupStackState = LookupStackState()
            isLoading = false
            contentReady = false
            lastRenderedLookupGeneration = -1
            return@LaunchedEffect
        }
        // Native keeps the previous subtree mounted until replacement results arrive;
        // handleResult resets top-level history atomically with publishing the new frame.
        if (rendererMode != DictionaryPreferences.RENDERER_COMPOSE) lookupStackState = LookupStackState()
        isLoading = false
        pushLookup(lookupString, deferredResult = initialLookupDeferred, entryJsons = initialEntryJsons)
    }

    fun recursiveSentence(sentence: String?): String {
        return sentence?.takeIf { it.isNotBlank() } ?: currentFrame?.sentence ?: fullText
    }

    fun recursiveOffset(sentence: String?, offset: Int?): Int {
        val targetSentence = recursiveSentence(sentence)
        return offset?.coerceIn(0, targetSentence.length)
            ?: currentFrame?.sentenceOffset?.coerceIn(0, targetSentence.length)
            ?: charOffset.coerceIn(0, targetSentence.length)
    }

    // Callbacks forwarded from the WebView bridge
    val onRecursiveLookupImpl: (String, String?, Int?, Float?, Float?, String?) -> Unit = { word, sentence, offset, x, y, type ->
        val targetSentence = recursiveSentence(sentence)
        val targetOffset = recursiveOffset(sentence, offset)
        if (type == "kanji") {
            val paths = getDictionaryPaths(context, activeProfile)
            val kanjiResult = runCatching {
                repository.queryKanji(word)
            }.getOrNull()
            val jsonStrings = kanjiResult?.entries?.map { e ->
                buildKanjiEntryJson(kanjiResult.character, e).toString()
            } ?: emptyList()
            if (recursiveNavMode == "popup") {
                val resolvedResult = CompletableDeferred(
                    chimahon.DictionaryRepository.LookupResult2(
                        results = emptyList(),
                        styles = emptyList(),
                        mediaDataUris = emptyMap(),
                        error = null,
                    )
                )
                childPopupRequest = RecursivePopupRequest(
                    query = word,
                    sentence = targetSentence,
                    sentenceOffset = targetOffset,
                    deferredResult = resolvedResult,
                    entryJsons = jsonStrings,
                )
            } else {
                pushLookup(
                    query = word,
                    isRecursive = true,
                    sentenceContext = targetSentence,
                    sentenceOffsetContext = targetOffset,
                    entryJsons = jsonStrings,
                )
            }
        } else if (recursiveNavMode == "popup") {
            // Sync lookup (same warm path as pushLookup's recursive branch),
            // then create a child popup with the results — no parent WebView update.
            val cleanQuery = word.replace(Regex("[\\s\\p{Punct}「」『』【】（）〔〕［］｛｝〈〉《》…、。！？!?]+"), "").trim()
            if (cleanQuery.isNotBlank() && cleanQuery.any { it.code > 127 }) {
                val termPaths = getDictionaryPaths(context, activeProfile)
                val result = runCatching {
                    repository.lookup(cleanQuery, termPaths, activeProfile.languageCode)
                }.getOrElse {
                    chimahon.DictionaryRepository.LookupResult2(
                        results = emptyList(),
                        styles = emptyList(),
                        mediaDataUris = emptyMap(),
                        error = it.message,
                    )
                }
                val orderedResults = orderLookupResultsForDisplay(result.results, activeProfile, context)
                val matched = orderedResults.firstOrNull()?.matched
                if (!matched.isNullOrBlank()) {
                    val resolvedResult = CompletableDeferred(
                        chimahon.DictionaryRepository.LookupResult2(
                            results = orderedResults,
                            styles = result.styles,
                            mediaDataUris = result.mediaDataUris,
                            error = result.error,
                        ),
                    )
                    val matchedLength = matched.codePointCount(0, matched.length)
                    val openChild: (String?) -> Unit = { encodedRect ->
                        val rect = runCatching {
                            val decoded = org.json.JSONTokener(encodedRect).nextValue() as? String
                            decoded?.let { org.json.JSONObject(it) }
                        }.getOrNull()
                        val cssScale = density.density
                        childPopupRequest = RecursivePopupRequest(
                            query = matched,
                            sentence = targetSentence,
                            sentenceOffset = targetOffset,
                            tapX = (rect?.optDouble("x")?.toFloat() ?: x ?: 0f) * cssScale,
                            tapY = (rect?.optDouble("y")?.toFloat() ?: y ?: 0f) * cssScale,
                            anchorWidth = (rect?.optDouble("width")?.toFloat() ?: 0f) * cssScale,
                            anchorHeight = (rect?.optDouble("height")?.toFloat() ?: 0f) * cssScale,
                            deferredResult = resolvedResult,
                        )
                    }
                    if (webView == null) {
                        openChild(null)
                    } else {
                        webView.evaluateJavascript(
                            "window.DictionaryRenderer && window.DictionaryRenderer.resolveRecursiveSelection($matchedLength);",
                            openChild,
                        )
                    }
                } else {
                    webView?.evaluateJavascript(
                        "window.DictionaryRenderer && window.DictionaryRenderer.clearRecursiveSelection();",
                        null,
                    )
                }
            }
        } else {
            pushLookup(
                query = word,
                isRecursive = true,
                sentenceContext = targetSentence,
                sentenceOffsetContext = targetOffset,
            )
        }
    }
    // Stable identity across recompositions. The body above dereferences current state at call
    // time, so taps always see fresh data while remember keys and strong skipping stay calm -
    // without this every popup recomposition (e.g. an Anki-status patch) hands a fresh lambda
    // down and recomposes the whole card tree.
    val onRecursiveLookupRef = rememberUpdatedState(onRecursiveLookupImpl)
    val onRecursiveLookup: (String, String?, Int?, Float?, Float?, String?) -> Unit = remember {
        { word, sentence, offset, x, y, type ->
            onRecursiveLookupRef.value(word, sentence, offset, x, y, type)
        }
    }
    val onTabSelect: (Int) -> Unit = remember {
        { idx ->
            if (idx in lookupStackState.stack.indices) {
                lookupStackState = lookupStackState.copy(activeIndex = idx)
            }
        }
    }
    val onBack: () -> Unit = remember {
        {
            if (lookupStackState.activeIndex > 0) lookupStackState = lookupStackState.copy(activeIndex = lookupStackState.activeIndex - 1)
        }
    }
    // Same stability treatment for the ready callbacks crossing into both renderer branches:
    // all state reads happen at callback time, so the remembered instances always see fresh
    // data while the renderer itself can skip. The outer host callback goes through a ref so
    // its identity never matters downstream.
    val contentReadyChangeRef = rememberUpdatedState(onContentReadyChange)
    // Keyed on `visible` to match `contentReady`'s own `remember(visible)`: visibility swaps the
    // state object, so an unkeyed callback would keep writing the detached one and the popup
    // would stick in the wrong readiness. Identity still never changes within a visibility
    // epoch, which is all downstream skipping needs.
    val composeContentReady: (Boolean) -> Unit = remember(visible) {
        { ready ->
            // Only the "not ready" edge comes from the loading flag. The ready
            // edge comes from onPlanReady below, because the flag clears while
            // the render plan is still being built - reporting ready there
            // published an empty popup a frame before any glossary existed,
            // which the user saw as a blank surface flash.
            if (!ready && lookupGeneration != lastRenderedLookupGeneration) {
                contentReady = false
            }
            if (!ready) contentReadyChangeRef.value?.invoke(false)
        }
    }
    val composePlanReady: () -> Unit = remember(visible) {
        {
            contentReady = true
            lastRenderedLookupGeneration = lookupGeneration
            chimahon.diagnostics.LookupProfiler.mark(lookupStackState.currentFrame?.profilingId ?: 0L, "popup.plan.ready")
            contentReadyChangeRef.value?.invoke(true)
        }
    }
    val webContentReady: (Boolean) -> Unit = remember(visible) {
        { ready ->
            if (ready) {
                contentReady = true
                lastRenderedLookupGeneration = lookupGeneration
            } else {
                // Hide stale content only when a new top-level lookup
                // has started. Same-generation invalidations (e.g. Anki
                // status patches) keep the current content visible.
                if (lookupGeneration != lastRenderedLookupGeneration) {
                    contentReady = false
                }
            }
            contentReadyChangeRef.value?.invoke(ready)
        }
    }

    val outsideTapInteraction = remember { MutableInteractionSource() }
    val revealedFrame = remember { longArrayOf(0L) }
    val recursiveTabs = lookupStackState.buildTabs()
    val showRecursiveChrome = when (recursiveNavMode) {
        "stack" -> lookupStackState.activeIndex > 0
        "popup" -> false
        else -> recursiveTabs.size > 1
    }

    @Composable
    fun RecursiveLookupChrome() {
        if (!showRecursiveChrome) return
        val scrollState = rememberScrollState()
        val chromeBackground = if (eInkMode) {
            if (isDark) Color.Black else Color.White
        } else {
            BgColor
        }
        val chromeText = if (eInkMode) {
            if (isDark) Color.White else Color.Black
        } else {
            colorScheme.onSurface
        }
        val chromeBorder = if (eInkMode) chromeText else colorScheme.outlineVariant
        val chromeAccent = if (eInkMode) chromeText else colorScheme.primary
        val chromeOnAccent = if (eInkMode) chromeBackground else colorScheme.onPrimary
        val tabShape = RoundedCornerShape(if (eInkMode) 0.dp else 20.dp)

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    drawLine(
                        color = chromeBorder,
                        start = androidx.compose.ui.geometry.Offset(0f, size.height - stroke / 2f),
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height - stroke / 2f),
                        strokeWidth = stroke,
                    )
                },
            color = chromeBackground,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (recursiveNavMode == "stack") {
                    Surface(
                        modifier = Modifier
                            .height(30.dp)
                            .clickable {
                                onBack()
                            },
                        shape = tabShape,
                        color = chromeBackground,
                        contentColor = chromeText,
                        border = BorderStroke(1.dp, chromeBorder),
                    ) {
                        Text(
                            text = "\u2190 Back",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                } else {
                    recursiveTabs.forEachIndexed { index, tab ->
                        val active = tab.active
                        Surface(
                            modifier = Modifier
                                .height(30.dp)
                                .padding(end = 6.dp)
                                .clickable(enabled = !active) {
                                    onTabSelect(index)
                                },
                            shape = tabShape,
                            color = if (active) chromeAccent else Color.Transparent,
                            contentColor = if (active) chromeOnAccent else chromeText,
                            border = BorderStroke(
                                1.dp,
                                if (active) chromeAccent else chromeBorder,
                            ),
                            shadowElevation = if (active && !eInkMode) 2.dp else 0.dp,
                        ) {
                            Text(
                                text = tab.label.take(20) + if (tab.label.length > 20) "\u2026" else "",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun PopupCloseChrome() {
        val chromeBackground = if (eInkMode) {
            if (isDark) Color.Black else Color.White
        } else {
            BgColor
        }
        val chromeText = if (eInkMode) {
            if (isDark) Color.White else Color.Black
        } else {
            colorScheme.onSurface
        }
        val chromeBorder = if (eInkMode) chromeText else colorScheme.outlineVariant
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    drawLine(
                        color = chromeBorder,
                        start = Offset(0f, size.height - stroke / 2f),
                        end = Offset(size.width, size.height - stroke / 2f),
                        strokeWidth = stroke,
                    )
                },
            color = chromeBackground,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = lookupString.take(20) + if (lookupString.length > 20) "…" else "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = chromeText,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { dismissPopup() },
                    shape = RoundedCornerShape(if (eInkMode) 0.dp else 14.dp),
                    color = Color.Transparent,
                    contentColor = chromeText,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "\u2715",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun PopupContent() {
        val swipeThreshold = with(density) { popupSwipeThresholdPref.dp.toPx() }
        val revealContent = contentReady || isLoading || errorMessage != null
        val readyRequestId = if (visible && contentReady && !isLoading) currentFrame?.profilingId ?: 0L else 0L
        Surface(
            modifier = modifier
                .width(actualWidthDp)
                .height(actualHeightDp)
                .alpha(if (revealContent) 1f else 0f)
                .drawWithContent {
                    drawContent()
                    if (revealContent && readyRequestId != 0L && revealedFrame[0] != readyRequestId) {
                        revealedFrame[0] = readyRequestId
                        chimahon.diagnostics.LookupProfiler.mark(readyRequestId, "popup.ready_container.draw")
                    }
                }
                .pointerInput(Unit) {
                    if (!popupSwipeToDismissPref) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            val ignoreHeight = with(density) { 48.dp.toPx() }
                            if (down.position.y < ignoreHeight) continue

                            var totalDragX = 0f
                            var totalDragY = 0f
                            try {
                                drag(down.id) { change ->
                                    val delta = change.positionChange()
                                    totalDragX += delta.x
                                    totalDragY += delta.y
                                    // If horizontal swipe is dominant and exceeds threshold, dismiss
                                    if (kotlin.math.abs(totalDragX) > swipeThreshold &&
                                        kotlin.math.abs(totalDragX) > kotlin.math.abs(totalDragY) * 1.75f
                                    ) {
                                        dismissPopup()
                                        throw CancellationException("Dismissed by swipe")
                                    }
                                }
                            } catch (e: CancellationException) {
                                if (e.message != "Dismissed by swipe") throw e
                            }
                        }
                    }
                }
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    // Consume taps on the popup itself to prevent them from falling through to the reader
                },
            shape = RoundedCornerShape(if (eInkMode) 0.dp else 8.dp),
            border = if (eInkMode) BorderStroke(1.dp, if (isDark) Color.White else Color.Black) else null,
            color = BgColor,
            tonalElevation = 0.dp,
            shadowElevation = if (eInkMode) 0.dp else 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                RecursiveLookupChrome()
                if (recursiveNavMode == "popup" && dismissOnOutsideTap) {
                    PopupCloseChrome()
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    val plainText = rendererMode == DictionaryPreferences.RENDERER_PLAIN_TEXT
                    if (rendererMode == DictionaryPreferences.RENDERER_COMPOSE || plainText) {
                        DictionaryEntryCompose(
                            cardPreparation = cardPreparation,
                            viewportWidthPx = with(density) { actualWidthDp.roundToPx() },
                            profilingId = currentFrame?.profilingId ?: 0L,
                            results = results,
                            styles = styles,
                            mediaDataUris = mediaDataUris,
                            placeholder = if (isLoading || currentFrame == null) "" else "No results found",
                            fontSize = popupFontSizePref,
                            showFrequencyHarmonic = showFreqHarmonic,
                            showFrequencyAverage = showFreqAverage,
                            groupTerms = groupTerms,
                            showPitchDiagram = showPitchDiagram,
                            showPitchNumber = showPitchNumber,
                            showPitchText = showPitchText,
                            activeProfile = activeProfile,
                            existingExpressions = existingExpressions,
                            entryJsons = entryJsons,
                            customCss = customCss,
                            eInkMode = eInkMode,
                            recursiveNavMode = recursiveNavMode,
                            // Empty: RecursiveLookupChrome() above already renders the tab strip
                            // for every mode. Passing tabs here as well made the renderer draw a
                            // second RecursiveNavBar, so "tabs" mode showed two chrome bars.
                            tabs = emptyList(),
                            wordAudioEnabled = wordAudioEnabled,
                            wordAudioAutoplayOverride = if (visible) wordAudioAutoplay else false,
                            groupPitches = groupPitches,
                            onAnkiLookup = onAnkiLookup,
                            onRecursiveLookup = onRecursiveLookup,
                            onTabSelect = onTabSelect,
                            onBack = onBack,
                            isLoading = isLoading,
                            usePlainTextBody = plainText,
                            resultsAreOrdered = true,
                            onContentReadyChange = composeContentReady,
                            onPlanReady = composePlanReady,
                            modifier = Modifier
                                .fillMaxSize()
                                // Content-frame tracer. This node only draws once the popup body
                                // actually paints, which is what makes the reported tap-to-content
                                // time real rather than the spinner frame. `size.height` changing
                                // means content is still arriving, so the settle counter resets.
                                .drawWithContent {
                                    drawContent()
                                    ContentFrameTracer.onContentDraw(size.height.toInt())
                                },
                        )
                    } else if (webView != null) {
                        DictionaryEntryWebView(
                            results = results,
                            styles = styles,
                            mediaDataUris = mediaDataUris,
                            placeholder = if (isLoading || currentFrame == null) "" else "No results found",
                            headerText = lookupString.take(20) + if (lookupString.length > 20) "…" else "",
                            fontSize = popupFontSizePref,
                            showFrequencyHarmonic = showFreqHarmonic,
                            showFrequencyAverage = showFreqAverage,
                            groupTerms = groupTerms,
                            showPitchDiagram = showPitchDiagram,
                            showPitchNumber = showPitchNumber,
                            showPitchText = showPitchText,
                            activeProfile = activeProfile,
                            existingExpressions = existingExpressions,
                            tabs = lookupStackState.buildTabs(),
                            recursiveNavMode = recursiveNavMode,
                            renderRecursiveChrome = false,
                            customCss = customCss,
                            wordAudioEnabled = wordAudioEnabled,
                            // Suppress autoplay when the popup is hidden (warm shell still in
                            // composition). Without this the WebView fires audio on a new lookup
                            // result even while the popup is invisible to the user.
                            wordAudioAutoplayOverride = if (visible) wordAudioAutoplay else false,
                            groupPitches = groupPitches,
                            requestFocusOnMount = true,
                            entryJsons = entryJsons,
                            webViewProvider = { webView },
                            onAnkiLookup = onAnkiLookup,
                            onRecursiveLookup = onRecursiveLookup,
                            onTabSelect = onTabSelect,
                            onBack = onBack,
                            hideOnContentInvalidated = true,
                            isLoading = isLoading,
                            onContentReadyChange = webContentReady,
                            modifier = Modifier.fillMaxSize(),
                        )
                        }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp).align(Alignment.Center)
                    )
                }
                }
            }
        }
    }

    // Warm shell: when not visible, render offscreen with alpha=0.
    // The WebView stays in composition, pageReady stays true.
    val hideOffset = (-10_000).dp

    if (usePopup) {
        if (visible) {
            Popup(
                offset = IntOffset(layoutResult.x.roundToInt(), layoutResult.y.roundToInt()),
                onDismissRequest = { dismissPopup() },
                properties = PopupProperties(
                    focusable = dismissOnOutsideTap,
                    dismissOnClickOutside = dismissOnOutsideTap,
                ),
            ) {
                PopupContent()
            }
        } else {
            // Offscreen — keeps composable alive
            Box(Modifier.offset(x = hideOffset, y = hideOffset).alpha(0f)) {
                PopupContent()
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (visible && (contentReady || isLoading || errorMessage != null)) 1f else 0f)
                .offset(
                    x = with(LocalDensity.current) { if (visible) layoutResult.x.toDp() else hideOffset },
                    y = with(LocalDensity.current) { if (visible) layoutResult.y.toDp() else hideOffset },
                )
        ) {
            if (dismissOnOutsideTap) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) {
                            dismissPopup()
                        }
                )
            }
            PopupContent()
        }
    }

    childPopupRequest?.let { request ->
        val childPopupWebView = eu.kanade.tachiyomi.ui.dictionary.rememberDictionaryPopupWebView(activeProfile.languageCode)
        val closeChromePx = if (dismissOnOutsideTap && recursiveNavMode == "popup") {
            with(density) { 32.dp.toPx() }
        } else {
            0f
        }
        val tapScreenX = layoutResult.x + (request.tapX ?: layoutResult.widthPx / 2f)
        val tapScreenY = layoutResult.y + closeChromePx + (request.tapY ?: layoutResult.heightPx / 2f)
        OcrLookupPopup(
            visible = visible,
            lookupString = request.query,
            fullText = request.sentence,
            charOffset = request.sentenceOffset,
            onDismiss = {
                childPopupRequest = null
                webView?.evaluateJavascript(
                    "window.DictionaryRenderer && window.DictionaryRenderer.clearRecursiveSelection();",
                    null,
                )
            },
            dismissOnOutsideTap = true,
            webView = childPopupWebView,
            repository = repository,
            anchorX = tapScreenX,
            anchorY = tapScreenY,
            anchorWidth = request.anchorWidth,
            anchorHeight = request.anchorHeight,
            isVertical = isVertical,
            activeProfile = activeProfile,
            type = type,
            mediaInfo = mediaInfo,
            screenshot = screenshot,
            onRequestScreenshot = onRequestScreenshot,
            onRequestSentenceAudio = onRequestSentenceAudio,
            mediaRequest = mediaRequest,
            onAnkiMediaWarnings = onAnkiMediaWarnings,
            onCropTriggered = onCropTriggered,
            usePopup = usePopup,
            onTermMatched = null,
            onContentReadyChange = null,
            initialLookupDeferred = request.deferredResult,
            initialEntryJsons = request.entryJsons,
            isRecursiveChild = true,
            titleId = titleId,
        )
    }
}
