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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
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
import chimahon.anki.AnkiProfile
import chimahon.anki.AnkiResult
import chimahon.ocr.effectiveSearchResolution
import chimahon.ocr.nextWordBoundarySubstring
import chimahon.util.ImageEncoder
import eu.kanade.tachiyomi.ui.dictionary.buildKanjiEntryJson
import eu.kanade.tachiyomi.ui.dictionary.DictionaryEntryWebView
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryColorScheme
import eu.kanade.tachiyomi.ui.dictionary.resolveDictionaryTheme
import eu.kanade.tachiyomi.ui.dictionary.TabInfo
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryPaths
import eu.kanade.tachiyomi.ui.dictionary.orderLookupResultsForDisplay
import eu.kanade.tachiyomi.ui.dictionary.stopDictionaryAudio
import eu.kanade.tachiyomi.util.system.copyToClipboard
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.i18n.stringResource as contextStringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.UUID
import kotlin.math.roundToInt

/** Fire-and-forget scope for Anki mining jobs — survives popup dismissals */
private val miningScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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
    webView: WebView,
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
    val scope = rememberCoroutineScope()
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
    val childPopupWebView = remember(context) { WebView(context) }
    var contentReady by remember(visible) { mutableStateOf(false) }
    // Tracks the lookupGeneration value at the time content was last painted.
    // Used to distinguish a fresh lookup (hide stale content) from a same-lookup
    // invalidation like an Anki-status patch (keep content visible).
    var lastRenderedLookupGeneration by remember { mutableIntStateOf(-1) }
    var lookupGeneration by remember { mutableIntStateOf(0) }

    androidx.compose.runtime.DisposableEffect(childPopupWebView) {
        onDispose { childPopupWebView.destroy() }
    }

    fun dismissPopup() {
        if (!isRecursiveChild) {
            childPopupRequest = null
            webView.evaluateJavascript(
                "window.DictionaryRenderer && window.DictionaryRenderer.clearRecursiveSelection();",
                null,
            )
        }
        onDismiss()
    }

    androidx.compose.runtime.DisposableEffect(webView, childPopupRequest) {
        if (childPopupRequest != null) {
            webView.setOnScrollChangeListener { _, _, _, _, _ ->
                childPopupRequest = null
                webView.evaluateJavascript(
                    "window.DictionaryRenderer && window.DictionaryRenderer.clearRecursiveSelection();",
                    null,
                )
            }
        }
        onDispose { webView.setOnScrollChangeListener(null) }
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
        val generation = ++lookupGeneration
        contentReady = false
        val shouldShowLoading = !isRecursive && lastRenderedLookupGeneration < 0

        fun handleResult(result: chimahon.DictionaryRepository.LookupResult2, orderedResults: List<LookupResult>, phaseStart: Long) {
            if (generation != lookupGeneration) return
            if (isRecursive && result.results.isEmpty()) {
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
            )

            // Truncate any forward history past the current index, then push
            val truncated = lookupStackState.stack.take(lookupStackState.activeIndex + 1) + frame
            lookupStackState = LookupStackState(stack = truncated, activeIndex = truncated.size - 1)
            errorMessage = result.error

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
                            "anki_check_ms=${android.os.SystemClock.elapsedRealtime() - phaseStart} expressions=${uniqueExpressions.size}",
                        )
                    }
                }
            }

            // Load media in background
            scope.launch(Dispatchers.IO) {
                val media = repository.loadMediaAsync(finalQuery, orderedResults)
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

        val deferred = deferredResult ?: if (isRecursive) {
            // Recursive lookups: session is warm (~5-20ms), run synchronously
            // to avoid coroutine dispatch overhead. getDictionaryPaths has
            // internal caching so the stat call is fast.
            val termPaths = getDictionaryPaths(context, activeProfile)
            val result = runCatching {
                lookupWithSearchResolution(repository, finalQuery, termPaths, activeProfile)
            }.getOrElse {
                chimahon.DictionaryRepository.LookupResult2(
                    results = emptyList(),
                    styles = emptyList(),
                    mediaDataUris = emptyMap(),
                    error = it.message,
                )
            }
            val orderedResults = orderLookupResultsForDisplay(result.results, activeProfile, context)
            handleResult(result, orderedResults, android.os.SystemClock.elapsedRealtime())
            return
        } else {
            scope.async(Dispatchers.IO) {
                val termPaths = getDictionaryPaths(context, activeProfile)
                lookupWithSearchResolution(repository, finalQuery, termPaths, activeProfile)
            }
        }

        if (!deferred.isCompleted) {
            // Must await in a coroutine
            scope.launch {
                if (shouldShowLoading) {
                    isLoading = true
                }
                errorMessage = null
                val phaseStart = android.os.SystemClock.elapsedRealtime()
                try {
                    val result = deferred.await()
                    val orderedResults = withContext(Dispatchers.Default) {
                        orderLookupResultsForDisplay(result.results, activeProfile, context)
                    }
                    handleResult(result, orderedResults, phaseStart)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (generation != lookupGeneration) return@launch
                    errorMessage = e.message ?: "Lookup failed"
                    isLoading = false
                }
            }
        } else {
            // Synchronous lookup (no nested coroutine)
            // Session is warm, lookup is fast (~10-20ms) — no need for Dispatchers.IO hop
            errorMessage = null
            val phaseStart = android.os.SystemClock.elapsedRealtime()
            try {
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                val result = deferred.getCompleted()
                val orderedResults = orderLookupResultsForDisplay(result.results, activeProfile, context)
                handleResult(result, orderedResults, phaseStart)
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
                                context.contextStringResource(MR.strings.anki_card_error, ankiResult.message),
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
                            context.contextStringResource(MR.strings.anki_card_error, ankiResult.message),
                        )
                        is AnkiResult.NotConfigured -> context.toast(MR.strings.anki_not_configured)
                    }
                }
            }
        }
    }

    val onAnkiLookup: ((Int, Int?, String?, String?, Boolean) -> Unit)? = if (ankiEnabled) {
        { index, glossaryIndex, selectedDict, popupSelection, forceOpen ->
            performAnkiLookup(index, glossaryIndex, selectedDict, popupSelection, forceOpen)
        }
    } else {
        null
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
            stopDictionaryAudio(webView)
            if (!isRecursiveChild) {
                childPopupRequest = null
                webView.evaluateJavascript(
                    "window.DictionaryRenderer && window.DictionaryRenderer.clearRecursiveSelection();",
                    null,
                )
            }
        }
    }

    LaunchedEffect(lookupString, ankiEnabled, ankiModel, visible) {
        if (!visible) return@LaunchedEffect
        if (lookupString.isBlank()) {
            lookupGeneration++
            lookupStackState = LookupStackState()
            isLoading = false
            contentReady = false
            lastRenderedLookupGeneration = -1
            return@LaunchedEffect
        }
        // Reset the stack and load the initial term.
        // contentReady is intentionally NOT reset here — the warm shell keeps
        // the popup visible between lookups so the WebView never goes blank.
        // The renderer's onContentInvalidated callback will hide stale content
        // when a genuinely new lookup generation begins (see onContentReadyChange).
        lookupStackState = LookupStackState()
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
    val onRecursiveLookup: (String, String?, Int?, Float?, Float?, String?) -> Unit = { word, sentence, offset, x, y, type ->
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
                    webView.evaluateJavascript(
                        "window.DictionaryRenderer && window.DictionaryRenderer.resolveRecursiveSelection($matchedLength);",
                    ) { encodedRect ->
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
                } else {
                    webView.evaluateJavascript(
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
    val onTabSelect: (Int) -> Unit = { idx ->
        if (idx in lookupStackState.stack.indices) {
            lookupStackState = lookupStackState.copy(activeIndex = idx)
        }
    }
    val onBack: () -> Unit = {
        if (lookupStackState.activeIndex > 0) lookupStackState = lookupStackState.copy(activeIndex = lookupStackState.activeIndex - 1)
    }

    val outsideTapInteraction = remember { MutableInteractionSource() }
    val recursiveTabs = lookupStackState.buildTabs()
    val showRecursiveChrome = when (recursiveNavMode) {
        "stack" -> lookupStackState.activeIndex > 0
        "popup" -> false
        else -> recursiveTabs.size > 1
    }
    val popupActionChromeHeight = 36.dp

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
    fun PopupActionChrome() {
        var copied by remember { mutableStateOf(false) }
        LaunchedEffect(copied) {
            if (copied) {
                delay(1_000)
                copied = false
            }
        }
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
                    text = fullText.take(20) + if (fullText.length > 20) "…" else "",
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
                        ) {
                            copyOcrPopupFullText(fullText) { label, content ->
                                context.copyToClipboard(label, content)
                            }
                            copied = true
                        },
                    shape = RoundedCornerShape(if (eInkMode) 0.dp else 14.dp),
                    color = Color.Transparent,
                    contentColor = chromeText,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = stringResource(MR.strings.action_copy_to_clipboard),
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun PopupContent() {
        val swipeThreshold = with(density) { popupSwipeThresholdPref.dp.toPx() }
        Surface(
            modifier = modifier
                .width(actualWidthDp)
                .height(actualHeightDp)
                .alpha(if (contentReady || isLoading || errorMessage != null) 1f else 0f)
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
                PopupActionChrome()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
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
                    onContentReadyChange = { ready ->
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
                        onContentReadyChange?.invoke(ready)
                    },
                    modifier = Modifier.fillMaxSize(),
                )

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
        val actionChromePx = with(density) { popupActionChromeHeight.toPx() }
        val fallbackY = (layoutResult.heightPx - actionChromePx).coerceAtLeast(0f) / 2f
        val tapScreenX = layoutResult.x + (request.tapX ?: layoutResult.widthPx / 2f)
        val tapScreenY = layoutResult.y + actionChromePx + (request.tapY ?: fallbackY)
        OcrLookupPopup(
            visible = visible,
            lookupString = request.query,
            fullText = request.sentence,
            charOffset = request.sentenceOffset,
            onDismiss = {
                childPopupRequest = null
                webView.evaluateJavascript(
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

/**
 * Runs [repository.lookup] honoring the profile's effective search resolution.
 * With "word" resolution (yomitan `translation.searchResolution='word'`), when
 * a query yields no results it is retried cutting at word boundaries. Returns
 * the last empty (or first non-empty) result.
 */
private fun lookupWithSearchResolution(
    repository: DictionaryRepository,
    query: String,
    termPaths: chimahon.DictionaryPaths,
    activeProfile: AnkiProfile,
): chimahon.DictionaryRepository.LookupResult2 {
    fun lookupOne(text: String) = repository.lookup(text, termPaths, activeProfile.languageCode)

    if (effectiveSearchResolution(activeProfile.searchResolution, activeProfile.languageCode) != AnkiProfile.SEARCH_RESOLUTION_WORD) {
        return lookupOne(query)
    }

    var current = query
    var result = lookupOne(current)
    var guard = 0
    while (
        current.isNotEmpty() &&
        result.error == null &&
        result.results.isEmpty() &&
        guard++ < 8
    ) {
        current = nextWordBoundarySubstring(current)
        if (current.isEmpty()) break
        result = lookupOne(current)
    }
    return result
}
