package eu.kanade.tachiyomi.ui.dictionary.compose

import android.content.Context

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import java.util.IdentityHashMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chimahon.DictionaryStyle
import chimahon.FrequencyEntry
import chimahon.GlossaryEntry
import chimahon.LookupResult
import chimahon.PitchEntry
import chimahon.anki.AnkiProfile
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryColorScheme
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryTitle
import eu.kanade.tachiyomi.ui.dictionary.orderLookupResultsForDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlin.math.roundToInt

private val WHITESPACE_REGEX = Regex("\\s+")
private val BLANK_LINES_REGEX = Regex("\\n[ \\t]*\\n+")
private val MULTI_SPACE_REGEX = Regex("[ \\t]{2,}")

@Immutable
internal data class TermCard(
    val expression: String,
    val reading: String,
    val matched: String,
    val deinflected: String,
    val termTags: String,
    val rules: String,
    val process: List<ProcessStep>,
    val dictGroups: List<DictionaryGroup>,
    val frequencies: List<FrequencyEntry>,
    val pitches: List<PitchEntry>,
)

@Immutable
internal data class ProcessStep(val name: String, val description: String)

@Immutable
internal data class DictionaryGroup(
    val dictName: String,
    val title: String,
    val glosses: List<GlossaryEntry>,
    /**
     * Glossary texts parsed during card preparation, off Main, aligned
     * by index with [glosses]. [GlossRow] consumes these instead of parsing on Main, where every
     * lazy pass (layout, prefetch, measure) runs its own composition with a cold `remember`
     * cache and would otherwise pay the full parse again.
     */
    val parsedGlosses: List<List<StructuredNode>> = emptyList(),
)

/** A parsed kanji result, mirroring the WebView's `kanji-renderer.js` model. */
@Immutable
internal data class KanjiCard(
    val character: String,
    val dictName: String,
    val onyomi: List<String>,
    val kunyomi: List<String>,
    val tags: List<String>,
    val definitions: List<String>,
    val stats: List<Pair<String, String>>,
)

/**
 * Parse a kanji entry JSON string (the same `buildKanjiEntryJson` payload the
 * WebView consumes) into a [KanjiCard] for native rendering.
 */
private fun parseKanjiEntryJson(json: String): KanjiCard? {
    val kanji: JSONObject = runCatching {
        JSONObject(json).optJSONObject("kanji")
    }.getOrNull() ?: return null
    return runCatching {
        val onyomi = kanji.optJSONArray("onyomi")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } ?: emptyList()
        val kunyomi = kanji.optJSONArray("kunyomi")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } ?: emptyList()
        val definitions = kanji.optJSONArray("definitions")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } ?: emptyList()
        val stats = mutableListOf<Pair<String, String>>()
        kanji.optJSONObject("stats")?.let { obj ->
            obj.keys().forEach { key ->
                val v = obj.optString(key)
                if (v.isNotBlank()) stats.add(key to v)
            }
        }
        KanjiCard(
            character = kanji.optString("character", ""),
            dictName = kanji.optString("dictName", ""),
            onyomi = onyomi,
            kunyomi = kunyomi,
            tags = kanji.optString("tags", "").split(" ").filter { it.isNotBlank() },
            definitions = definitions,
            stats = stats,
        )
    }.getOrNull()
}

/** One entry in the recursive-lookup tab strip. */
data class GlossTab(val label: String, val active: Boolean)

private val glossaryPreparationDispatcher = Dispatchers.Default.limitedParallelism(4)

private class GlossaryLayoutWork(
    val nodes: List<StructuredNode>,
    val dictionary: String,
    val sourceLength: Int,
    val css: ParsedCss,
    val width: Int,
    val index: Int,
)

/**
 * Prepares glosses drawn from the shared queue on the calling worker.
 *
 * Owns its [NativeGlossaryResources] so font and style resources are never touched by two
 * threads at once. `checkCancellation` is bound to the caller's
 * coroutine context so a cancelled lookup stops the work in flight.
 */
private suspend fun prepareGlossaryBatch(
    profilingId: Long,
    nextWork: () -> GlossaryLayoutWork?,
    fontResolver: FontFamily.Resolver,
    density: Density,
    layoutDirection: LayoutDirection,
    secondary: Color,
    border: Color,
    baseStyle: TextStyle,
): IdentityHashMap<List<StructuredNode>, NativeGlossaryDocument> {
    val preparation = chimahon.diagnostics.LookupProfiler.section(profilingId, "plan.worker.context") {
        NativeGlossaryResources(density, layoutDirection, fontResolver)
    }
    val caller = currentCoroutineContext()
    val out = IdentityHashMap<List<StructuredNode>, NativeGlossaryDocument>()
    var firstIndex = -1
    if (profilingId != 0L) chimahon.diagnostics.LookupProfiler.mark(profilingId, "plan.worker.begin",
        "scheduling=largest-source-first")
    chimahon.diagnostics.LookupProfiler.section(profilingId, "plan.worker.prepare") { while (true) {
        caller.ensureActive()
        val item = nextWork() ?: break
        if (firstIndex == -1) firstIndex = item.index
        val compiler = chimahon.diagnostics.LookupProfiler.section(profilingId, "document.compiler.create") {
            NativeGlossaryCompiler(item.css, density, layoutDirection,
                fontResolver, secondary, border, checkCancellation = { caller.ensureActive() },
                profilingId = profilingId, resources = preparation)
        }
        val paragraphStart = preparation.paragraphs
        val rubyStart = preparation.rubyPairs
        val start = if (profilingId != 0L) chimahon.diagnostics.LookupProfiler.timestamp() else 0L
        val doc = chimahon.diagnostics.LookupProfiler.section(profilingId, "document.prepare") {
            compiler.prepare(item.nodes, baseStyle, emptyList(), item.width)
        }
        out[item.nodes] = doc
        if (profilingId != 0L) chimahon.diagnostics.LookupProfiler.mark(profilingId, "plan.document",
            JSONObject().put("index", item.index).put("dictionary", item.dictionary)
                .put("start_ns", start).put("end_ns", chimahon.diagnostics.LookupProfiler.timestamp())
                .put("width", item.width).put("height", doc.height)
                .put("paragraphs", preparation.paragraphs - paragraphStart)
                .put("rubyPairs", preparation.rubyPairs - rubyStart).toString())
    } }
    if (profilingId != 0L) chimahon.diagnostics.LookupProfiler.mark(profilingId, "plan.worker",
        "documents=${out.size} first=$firstIndex " + preparation.summary())
    return out
}

/**
 * Native Compose dictionary renderer for the reader popup — a drop-in for
 * [eu.kanade.tachiyomi.ui.dictionary.DictionaryEntryWebView]. Renders the same
 * underlying [chimahon.LookupResult] model directly, without the JSON/JS round-trip.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DictionaryEntryCompose(
    results: List<LookupResult>,
    styles: List<DictionaryStyle>,
    mediaDataUris: Map<String, String>,
    placeholder: String,
    fontSize: Int = 16,
    showFrequencyHarmonic: Boolean = false,
    showFrequencyAverage: Boolean = false,
    groupTerms: Boolean = true,
    activeProfile: AnkiProfile,
    existingExpressions: Set<String> = emptySet(),
    showPitchDiagram: Boolean = true,
    showPitchNumber: Boolean = true,
    showPitchText: Boolean = true,
    /**
     * How recursive lookups are presented: `tabs` (a tab strip), `stack` (a back chip), or `popup`
     * (the host opens a child popup, so this renderer draws no chrome). Mirrors the
     * `recursiveLookupMode` preference.
     */
    recursiveNavMode: String = "tabs",
    wordAudioEnabled: Boolean = true,
    wordAudioAutoplayOverride: Boolean? = null,
    customCss: String = "",
    groupPitches: Boolean = false,
    entryJsons: List<String>? = null,
    eInkMode: Boolean = false,
    /** Recursive-lookup stack. Tapping the active tab pops it; tapping another switches to it. */
    tabs: List<GlossTab> = emptyList(),
    modifier: Modifier = Modifier,
    onAnkiLookup: ((Int, Int?, String?, String?, Boolean) -> Unit)? = null,
    onRecursiveLookup: ((String, String?, Int?, Float?, Float?, String?) -> Unit)? = null,
    onTabSelect: ((Int) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onContentReadyChange: ((Boolean) -> Unit)? = null,
    /**
     * Fired once the [RenderPlan] exists, i.e. after the gloss parse, stylesheet parse and card
     * build have finished. Distinct from [onContentReadyChange], which only reports that the
     * composable ran and so fires while the plan is still being built.
     */
    onPlanReady: (() -> Unit)? = null,
    /**
     * True when the caller already ran [orderLookupResultsForDisplay] with the same profile and
     * context. Ordering is idempotent, so this only skips redundant work; callers passing true must
     * not hand the renderer a differently sorted list.
     */
    resultsAreOrdered: Boolean = false,
    isLoading: Boolean = false,
    usePlainTextBody: Boolean = false,
    profilingId: Long = 0,
) {
    val context = LocalContext.current
    val renderEnvironment = LocalDictionaryRenderEnvironment.current
    val prefs = remember { Injekt.get<DictionaryPreferences>() }
    val themeMode by prefs.themeMode().collectAsState()
    val customColor by prefs.customColor().collectAsState()
    val seedColor = renderEnvironment?.seedColor
        ?: if (customColor != 0) customColor else Injekt.get<UiPreferences>().colorTheme().get()

    val systemIsDark = isSystemInDarkTheme()
    val isAmoled = renderEnvironment?.amoled ?: (themeMode == "pure_black")
    val isDark = renderEnvironment?.dark ?: when (themeMode) {
        "dark", "pure_black" -> true
        "light" -> false
        else -> if (customColor != 0) Color(seedColor).luminance() < 0.5f else systemIsDark
    }
    val colorScheme = remember(isDark, isAmoled, seedColor) {
        chimahon.diagnostics.LookupProfiler.section(profilingId, "theme.colors") { getDictionaryColorScheme(isDark, isAmoled, seedColor) }
    }
    val bgColor = if (eInkMode) (if (isDark) Color.Black else Color.White) else if (isAmoled && isDark) Color.Black else colorScheme.surface
    val onBg = if (eInkMode) (if (isDark) Color.White else Color.Black) else colorScheme.onSurface
    val accent = if (eInkMode) (if (isDark) Color.White else Color.Black) else colorScheme.primary
    val secondary = if (eInkMode) (if (isDark) Color.White else Color.Black) else colorScheme.onSurfaceVariant
    val border = if (eInkMode) (if (isDark) Color.White else Color.Black) else if (isAmoled && isDark) Color.White.copy(alpha = 0.10f) else colorScheme.outlineVariant
    val hoverBg = if (eInkMode) (if (isDark) Color.Black else Color.White) else if (isAmoled && isDark) Color.White.copy(alpha = 0.07f) else colorScheme.surfaceVariant

    LaunchedEffect(results, isLoading) {
        onContentReadyChange?.invoke(!isLoading)
    }

    val resolveTitle: (String) -> String = remember(context, renderEnvironment) { { name ->
        if (renderEnvironment == null) getDictionaryTitle(context, name)
        else renderEnvironment.dictionaryTitles[name] ?: name
    } }
    val priority = remember(activeProfile, resolveTitle) {
        activeProfile.dictionaryOrder.map { resolveTitle(it) }.withIndex().associate { it.value to it.index }
    }
    // Theme variable values for CSS `var()` resolution: byte-identical formulas to the WebView's
    // `dynamicThemeCss` injection ([DictionaryBootstrapHtml]), so both engines resolve the same
    // names to the same colors in every mode and seed. Remembered: the map is rebuilt only when
    // the theme actually changes, and it joins the plan keys below for the same reason.
    val themeVars = remember(colorScheme, isDark, isAmoled) {
        dictionaryThemeVars(colorScheme, isDark, isAmoled)
    }
    BoxWithConstraints(modifier) {
    val popupWidthPx = constraints.maxWidth
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val fontResolver = LocalFontFamilyResolver.current
    val rubyRenderer = remember(density, layoutDirection, fontResolver) {
        RubyTextRenderer(density, layoutDirection, fontResolver)
    }
    rubyRenderer.profilingId = profilingId
    // Shared by worker document preparation and the drawing host.
    val baseStyle = remember(onBg, fontSize) { dictionaryBaseStyle(onBg, fontSize, null) }

    val plan by produceState(
        initialValue = if (isLoading) RenderPlan.Empty else null,
        results,
        styles,
        customCss,
        activeProfile,
        groupTerms,
        isLoading,
        entryJsons,
        resultsAreOrdered,
        themeVars,
        onBg,
        secondary,
        border,
        fontSize,
        density,
        layoutDirection,
        fontResolver,
        popupWidthPx,
    ) {
        if (isLoading) {
            value = RenderPlan.Empty
            return@produceState
        }
        val snapshot = results
        val snapshotStyles = styles
        val snapshotCustomCss = customCss
        val snapshotProfile = activeProfile
        val snapshotEntryJsons = entryJsons
        val snapshotGroupTerms = groupTerms
        val snapshotPriority = priority
        val snapshotResolveTitle = resolveTitle
        val snapshotThemeVars = themeVars
        chimahon.diagnostics.LookupProfiler.mark(profilingId, "plan.queued")
        if (profilingId != 0L) chimahon.diagnostics.LookupProfiler.mark(profilingId, "plan.config",
            "schema=native-flow-v1 widthPolicy=assigned tablePolicy=allocated-grid rubyLookup=base-only workerLimit=4 popupWidth=$popupWidthPx fontSize=$fontSize density=${density.density} " +
                "fontScale=${density.fontScale} direction=$layoutDirection results=${snapshot.size}")
        value = withContext(Dispatchers.Default) { chimahon.diagnostics.LookupProfiler.elapsedSection(profilingId, "plan.prepare") {
            val shown = if (resultsAreOrdered) snapshot else {
                orderLookupResultsForDisplay(snapshot, snapshotProfile, context)
            }
            val planCssMap = chimahon.diagnostics.LookupProfiler.section(profilingId, "plan.css") { buildParsedCssMap(snapshotStyles, snapshotCustomCss, context, snapshotThemeVars) }
            val planCards = chimahon.diagnostics.LookupProfiler.section(profilingId, "plan.cards_parse") {
                buildCards(shown, snapshotGroupTerms, snapshotResolveTitle, snapshotPriority)
            }
            val documents = IdentityHashMap<List<StructuredNode>, NativeGlossaryDocument>()
            val work = ArrayList<GlossaryLayoutWork>()
            val seen = IdentityHashMap<List<StructuredNode>, Boolean>()
            for (card in planCards) {
                val customOpen = customOpenDictionaryNames(card, snapshotProfile)
                for ((groupIndex, group) in card.dictGroups.withIndex()) {
                    val expanded = dictionaryInitiallyExpanded(group, groupIndex, snapshotProfile.dictionaryCollapseMode, customOpen)
                    if (!expanded || popupWidthPx == androidx.compose.ui.unit.Constraints.Infinity) continue
                    val groupCss = planCssMap[group.dictName]
                        ?: planCssMap[group.dictName.lowercase()]
                        ?: ParsedCss.EMPTY
                    // One width per group, not per gloss: it depends only on the popup width and on
                    // whether the group holds a single gloss.
                    val width = run {
                        val inset = with(density) {
                            4.dp.roundToPx() + 12.dp.roundToPx() + 12.dp.roundToPx() +
                                if (group.glosses.size == 1) 0 else 16.dp.roundToPx()
                        }
                        (popupWidthPx - inset).coerceAtLeast(0)
                    }
                    for ((index, nodes) in group.parsedGlosses.withIndex()) {
                        if (seen.put(nodes, true) != null) continue
                        work.add(GlossaryLayoutWork(nodes, group.dictName, group.glosses[index].glossary.length,
                            groupCss, width, work.size))
                    }
                }
            }
            // Start larger documents before the queue drains; display order stays in planCards.
            // The source size is already known, so scheduling adds no tree traversal.
            work.sortByDescending { it.sourceLength }
            val next = java.util.concurrent.atomic.AtomicInteger()
            val nextWork = { work.getOrNull(next.getAndIncrement()) }
            val batches = if (work.size <= 1) {
                // Single gloss is the common case and cannot parallelise; skip the fan-out entirely.
                listOf(prepareGlossaryBatch(profilingId, nextWork, fontResolver, density, layoutDirection,
                    secondary, border, baseStyle))
            } else {
                val workers = Runtime.getRuntime().availableProcessors().coerceIn(1, minOf(4, work.size))
                coroutineScope {
                    List(workers) {
                        async(glossaryPreparationDispatcher) {
                            prepareGlossaryBatch(profilingId,
                                nextWork,
                                fontResolver, density, layoutDirection, secondary, border, baseStyle)
                        }
                    }.awaitAll()
                }
            }
            for (batch in batches) documents.putAll(batch)
            chimahon.diagnostics.LookupProfiler.mark(profilingId, "plan.work",
                "documents=${documents.size} glosses=${work.size} batches=${batches.size} scheduling=largest-source-first")
            RenderPlan(
                kanjiCards = snapshotEntryJsons?.mapNotNull { parseKanjiEntryJson(it) } ?: emptyList(),
                parsedCssMap = planCssMap,
                cards = planCards,
                documents = documents,
            )
        } }
        chimahon.diagnostics.LookupProfiler.mark(profilingId, "plan.ready")
        onPlanReady?.invoke()
    }
    val effectivePlan = plan ?: RenderPlan.Empty

    // Structural-equality maps for the card subtree. Plain Map parameters are unstable-typed, so
    // under strong skipping they compare by instance: every plan rebuild or media commit (new
    // instance, often equal content) would recompose the whole card tree. ImmutableMap compares
    // structurally, so equal-content passes skip. Recomputed only when the source instance
    // changes, i.e. exactly when content may have changed.
    val stableCssMap = remember(effectivePlan.parsedCssMap) { effectivePlan.parsedCssMap.toImmutableMap() }
    val stableMedia = remember(mediaDataUris) { mediaDataUris.toImmutableMap() }

    // `popup` is handled by the host, which opens a child popup instead of pushing a frame here.
    val showTabStrip = recursiveNavMode == "tabs" && tabs.size > 1
    val showBackChip = recursiveNavMode == "stack" && tabs.size > 1

    CompositionLocalProvider(
        LocalLookupProfilingId provides profilingId,
        LocalRubyTextRenderer provides rubyRenderer,
        // Phase 3b: decoded media, read ONLY by the image leaf (StructuredImage) and the table
        // intrinsic memo key. Providing it here (not threading it as a param) means a late media
        // arrival recomposes just the images, not every card - same idea as LocalExistingExpressions.
        LocalMediaDataUris provides stableMedia,
        // Complete worker-prepared document geometry; the glossary host only paints and handles taps.
        LocalNativeGlossaryDocuments provides effectivePlan.documents,
        // Read ONLY by the per-card Anki button leaf ([TermCardAnkiActions]). Providing the set
        // here instead of threading it through TermCardView means an Anki add (which swaps the
        // set instance) recomposes just the two IconButtons: every card body skips, because none
        // of TermCardView's parameters changed.
        LocalExistingExpressions provides existingExpressions,
    ) {
    Column(modifier = Modifier.background(bgColor)) {
        if (showTabStrip || showBackChip) {
            RecursiveNavBar(
                tabs = tabs,
                showTabs = showTabStrip,
                onBg = onBg,
                secondary = secondary,
                border = border,
                hoverBg = hoverBg,
                accent = accent,
                fontSize = fontSize,
                onTabSelect = onTabSelect,
                onBack = onBack,
            )
        }
        // Bounded popup, opens at top: no item ahead to prefetch. Zero window removes the 2.1-2.3s
        // of off-screen card composition the default did on open (compose:lazy:prefetch:idle_frame).
        val popupListState = rememberLazyListState(
            cacheWindow = LazyLayoutCacheWindow(ahead = 0.dp, behind = 0.dp),
        )
        Box(Modifier.fillMaxSize()) {
            when {
                isLoading || effectivePlan == RenderPlan.Empty -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (isLoading) CircularProgressIndicator(color = accent)
                        else Icon(Icons.Outlined.MenuBook, contentDescription = null, tint = secondary, modifier = Modifier.size(32.dp))
                    }
                }
                effectivePlan.kanjiCards.isNotEmpty() -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = popupListState,
                        contentPadding = PaddingValues(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    ) {
                        itemsIndexed(effectivePlan.kanjiCards, key = { i, kanji -> "kanji-${kanji.character}-$i" }, contentType = { _, _ -> "kanji" }) { i, kanji ->
                            KanjiEntryCard(
                                kanji = kanji,
                                fontSize = fontSize,
                                accent = accent,
                                secondary = secondary,
                                border = border,
                                onBg = onBg,
                                eInkMode = eInkMode,
                                onRecursiveLookup = onRecursiveLookup,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                effectivePlan.cards.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (placeholder.isBlank()) {
                            CircularProgressIndicator(color = accent)
                        } else {
                            Text(placeholder, color = onBg.copy(alpha = 0.7f), fontSize = (fontSize - 2).sp)
                        }
                    }
                }
                else -> {
                    val cardStates = remember(effectivePlan.cards) {
                        effectivePlan.cards.map { TermCardUiState() }
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = popupListState,
                        contentPadding = PaddingValues(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                    ) {
                        effectivePlan.cards.forEachIndexed { i, card ->
                            val cardKey = "$i-${card.expression}\u0000${card.reading}"
                            val cardState = cardStates[i]
                            item(key = "$cardKey-header", contentType = "term-header") {
                            TermCardView(
                                card = card,
                                index = i,
                                fontSize = fontSize,
                                accent = accent,
                                secondary = secondary,
                                border = border,
                                hoverBg = hoverBg,
                                onBg = onBg,
                                eInkMode = eInkMode,
                                parsedCssMap = stableCssMap,
                                showFrequencyHarmonic = showFrequencyHarmonic,
                                showFrequencyAverage = showFrequencyAverage,
                                showPitchDiagram = showPitchDiagram,
                                showPitchNumber = showPitchNumber,
                                showPitchText = showPitchText,
                                groupPitches = groupPitches,
                                activeProfile = activeProfile,
                                onAnkiLookup = onAnkiLookup,
                                onRecursiveLookup = onRecursiveLookup,
                                wordAudioEnabled = wordAudioEnabled,
                                autoplay = wordAudioAutoplayOverride == true,
                                modifier = Modifier.fillMaxWidth(),
                                usePlainTextBody = usePlainTextBody,
                                uiState = cardState,
                                showDictionaryGroups = false,
                            )
                            }
                            val customOpen = customOpenDictionaryNames(card, activeProfile)
                            card.dictGroups.forEachIndexed { groupIndex, group ->
                                val groupKey = "$cardKey-dict-$groupIndex"
                                val initial = dictionaryInitiallyExpanded(group, groupIndex,
                                    activeProfile.dictionaryCollapseMode, customOpen)
                                item(key = "$groupKey-header", contentType = "dictionary-header") {
                                    DictGroupView(
                                        group, groupIndex, activeProfile.dictionaryCollapseMode, customOpen,
                                        cardState.overrides, cardState.selectedDict, { cardState.selectedDict = it },
                                        fontSize, accent, secondary, hoverBg, onBg, border, eInkMode,
                                        stableCssMap, onRecursiveLookup, usePlainTextBody, showGlosses = false,
                                    )
                                }
                                if (cardState.overrides[group.title] ?: initial) {
                                    itemsIndexed(group.glosses, key = { glossIndex, _ -> "$groupKey-gloss-$glossIndex" },
                                        contentType = { _, _ -> "gloss" }) { glossIndex, _ ->
                                        DictGroupView(
                                            group, groupIndex, activeProfile.dictionaryCollapseMode, customOpen,
                                            cardState.overrides, cardState.selectedDict, { cardState.selectedDict = it },
                                            fontSize, accent, secondary, hoverBg, onBg, border, eInkMode,
                                            stableCssMap, onRecursiveLookup, usePlainTextBody,
                                            showHeader = false, glossIndex = glossIndex,
                                        )
                                    }
                                }
                            }
                            item(key = "$cardKey-footer", contentType = "term-footer") { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
        }
        }
    }
    }
    }
}

/**
 * Everything the renderer needs, derived off the composition thread. Building this in one
 * background pass keeps [DictionaryCssParser] / org.json / jsoup work out of composition and
 * gives every downstream `remember` a stable identity to hit.
 */
@Immutable
internal class RenderPlan(
    val kanjiCards: List<KanjiCard>,
    val parsedCssMap: Map<String, ParsedCss>,
    val cards: List<TermCard>,
    val documents: Map<List<StructuredNode>, NativeGlossaryDocument> = emptyMap(),
) {
    companion object {
        val Empty = RenderPlan(emptyList(), emptyMap(), emptyList())
    }
}

/**
 * Theme-driven CSS variable values for `var()` resolution, computed with byte-identical formulas
 * to the WebView's `dynamicThemeCss` injection ([DictionaryBootstrapHtml.getDictionaryColorScheme]
 * inputs → same hexes) from the same color scheme - so both engines resolve the same names to
 * the same colors in every mode, seed and AMOLED setting. Keys omit the `--` prefix, matching
 * [parseCssVariables]. 8-digit hexes are RRGGBBAA order, which is what [parseCssColor2] reads.
 */
internal fun dictionaryThemeVars(
    colorScheme: ColorScheme,
    isDark: Boolean,
    isAmoled: Boolean,
): Map<String, String> {
    // `#RRGGBB` from float channels (avoids `Color.toArgb`, which the render harness's
    // desktop Compose build does not resolve).
    fun hex(color: Color): String {
        fun v(f: Float) = (f.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return "#%06X".format((v(color.red) shl 16) or (v(color.green) shl 8) or v(color.blue))
    }
    // RRGGBBAA order, matching what [parseCssColor2] reads; 0.10 -> 0x1A, 0.07 -> 0x12.
    val fgHex = hex(colorScheme.onSurface)
    return mapOf(
        "accent" to hex(colorScheme.primary),
        "on-accent" to hex(colorScheme.onPrimary),
        "fg" to fgHex,
        "bg" to if (isAmoled && isDark) "#000000" else hex(colorScheme.surface),
        "secondary" to hex(colorScheme.onSurfaceVariant),
        "border" to if (isAmoled && isDark) "#FFFFFF1A" else hex(colorScheme.outlineVariant),
        "hover-bg" to if (isAmoled && isDark) "#FFFFFF12" else hex(colorScheme.surfaceVariant),
        "tab-bg" to if (isAmoled && isDark) "#0d0d0d" else hex(colorScheme.surfaceContainer),
        "pronunciation-annotation-color" to fgHex,
    )
}
/** Parses dictionary styles with theme variables and the user's custom CSS. */
internal fun buildParsedCssMap(
    styles: List<DictionaryStyle>,
    customCss: String,
    context: Context,
    themeVars: Map<String, String> = emptyMap(),
): Map<String, ParsedCss> {
    val map = mutableMapOf<String, ParsedCss>()
    val customParsed = if (customCss.isNotBlank()) parseDictionaryCss(customCss, themeVars) else null

    fun merge(base: ParsedCss, extra: ParsedCss?): ParsedCss {
        if (extra == null || extra == ParsedCss.EMPTY) return base
        val mergedStyles = base.selectorStyles.toMutableMap()
        extra.selectorStyles.forEach { (selector, extraStyles) ->
            mergedStyles[selector] = extraStyles + (base.selectorStyles[selector] ?: emptyMap())
        }
        return ParsedCss(
            boxSelectors = base.boxSelectors + extra.boxSelectors,
            selectorStyles = mergedStyles,
            presenceRules = base.presenceRules + extra.presenceRules,
            nestedTagRules = base.nestedTagRules + extra.nestedTagRules,
            weakValueKeys = base.weakValueKeys + extra.weakValueKeys,
            variables = base.variables + extra.variables,
        )
    }

    for (s in styles) {
        val parsed = merge(parseDictionaryCss(s.styles, themeVars), customParsed)
        map[s.dictName] = parsed
        // dictName may be dirName or display title; index by both so lookup never misses
        val title = runCatching { getDictionaryTitle(context, s.dictName) }.getOrNull()
        if (title != null && title != s.dictName) map[title] = parsed
        // also index by lowercased variants for robustness
        map[s.dictName.lowercase()] = parsed
        if (title != null) map[title.lowercase()] = parsed
    }
    // if there are no styles but customCss exists, still ensure glosses get custom styles
    if (map.isEmpty() && customParsed != null && customParsed != ParsedCss.EMPTY) {
        map[""] = customParsed
    }
    return map
}

internal fun buildCards(
    results: List<LookupResult>,
    groupTerms: Boolean,
    resolveTitle: (String) -> String,
    priority: Map<String, Int>,
): List<TermCard> {
    if (results.isEmpty()) return emptyList()

    fun rank(title: String): Int = priority[title] ?: Int.MAX_VALUE
    val parsed = HashMap<Triple<String, String, Boolean>, List<StructuredNode>>()

    class Acc {
        var expression = ""
        var reading = ""
        var matched = ""
        var deinflected = ""
        var termTags = ""
        var rules = ""
        val process = LinkedHashSet<ProcessStep>()
        val glosses = LinkedHashMap<String, DictionaryGroup>()
        val seenGlosses = HashMap<String, MutableSet<Pair<String, String>>>()
        val frequencies = LinkedHashMap<String, FrequencyEntry>()
        val pitches = LinkedHashMap<String, PitchEntry>()

        fun finish(): TermCard {
            val groups = glosses.values.sortedBy { rank(it.title) }.map { group ->
                group.copy(parsedGlosses = group.glosses.map { gloss ->
                    val source = Triple(group.dictName, gloss.glossary, group.glosses.size == 1)
                    parsed.getOrPut(source) { parseGlossaryNodes(gloss.glossary) }
                })
            }
            return TermCard(
                expression = expression,
                reading = reading,
                matched = matched,
                deinflected = deinflected,
                termTags = termTags,
                rules = rules,
                process = process.toList(),
                dictGroups = groups,
                frequencies = frequencies.values.toList(),
                pitches = pitches.values.toList(),
            )
        }
    }

    val out = mutableListOf<Acc>()
    for (result in results) {
        val acc: Acc
        val last = out.lastOrNull()
        if (groupTerms && last != null &&
            last.expression == result.term.expression &&
            last.reading == result.term.reading
        ) {
            acc = last
        } else {
            acc = Acc().apply {
                expression = result.term.expression
                reading = result.term.reading
                matched = result.matched
                deinflected = result.deinflected
                termTags = result.term.glossaries.firstOrNull()?.termTags ?: ""
                rules = result.term.rules
            }
            out.add(acc)
        }
        if (acc.termTags.isBlank()) acc.termTags = result.term.glossaries.firstOrNull()?.termTags ?: ""
        if (acc.rules.isBlank()) acc.rules = result.term.rules
        if (acc.matched.isBlank()) acc.matched = result.matched
        for (p in result.process) acc.process.add(ProcessStep(p.name, p.description))
        for (g in result.term.glossaries) {
            val group = acc.glosses.getOrPut(g.dictName) { DictionaryGroup(g.dictName, resolveTitle(g.dictName), mutableListOf()) }
            @Suppress("UNCHECKED_CAST")
            val list = group.glosses as MutableList<GlossaryEntry>
            if (acc.seenGlosses.getOrPut(g.dictName) { HashSet() }.add(g.glossary to g.definitionTags)) {
                list.add(g)
            }
        }
        for (f in result.term.frequencies) {
            val existing = acc.frequencies[f.dictName]
            acc.frequencies[f.dictName] = if (existing != null) f.copy(frequencies = existing.frequencies + f.frequencies) else f
        }
        for (p in result.term.pitches) acc.pitches[p.dictName] = p
    }
    return out.map { it.finish() }
}

/**
 * Glossary text to render nodes. Pure function of the text (including throw behavior: no
 * catch here, matching [GlossRow]'s structured path), so parsing once per plan source is
 * output-identical to parsing per composition.
 */
internal fun parseGlossaryNodes(glossary: String): List<StructuredNode> =
    when (val parsed = parseStructuredGlossary(glossary)) {
        is StructuredEntry.Tree -> parsed.nodes
        is StructuredEntry.PlainText -> plainTextNodes(glossary)
    }

/**
 * Expressions already in Anki, provided at the [DictionaryEntryCompose] root and read ONLY by
 * [TermCardAnkiActions]. See the provider for why this is a local instead of a parameter.
 */
private val LocalExistingExpressions = compositionLocalOf { emptySet<String>() }

/**
 * The per-card Anki add/view buttons in their own recomposition scope.
 *
 * `alreadyAdded` is read HERE — not in [TermCardView] — so when an Anki add swaps the
 * `existingExpressions` set, only these two IconButtons recompose. The card body (gloss, ruby,
 * tables) keeps identical parameters and is skipped. [selectedDict] stays owned by the card (the
 * group headers write it on long-press); it is only *passed* here.
 */
@Composable
private fun TermCardAnkiActions(
    index: Int,
    expression: String,
    firstDictTitle: String?,
    selectedDict: String?,
    dupAction: String,
    onAnkiLookup: (Int, Int?, String?, String?, Boolean) -> Unit,
    onBg: Color,
) {
    val alreadyAdded = expression in LocalExistingExpressions.current
    val showAdd = !alreadyAdded || dupAction != "prevent"
    val targetDict = selectedDict ?: firstDictTitle
    if (showAdd) {
        IconButton(
            onClick = { onAnkiLookup(index, null, targetDict, null, false) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = if (alreadyAdded) Icons.Outlined.Check else Icons.Outlined.Add,
                contentDescription = null,
                tint = if (alreadyAdded) Color(0xFF4CAF50) else onBg.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
    if (alreadyAdded) {
        IconButton(
            onClick = { onAnkiLookup(index, null, targetDict, null, true) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.MenuBook,
                contentDescription = null,
                tint = onBg.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TermCardView(
    card: TermCard,
    index: Int,
    fontSize: Int,
    accent: Color,
    secondary: Color,
    border: Color,
    hoverBg: Color,
    onBg: Color,
    eInkMode: Boolean,
    parsedCssMap: ImmutableMap<String, ParsedCss>,
    showFrequencyHarmonic: Boolean,
    showFrequencyAverage: Boolean,
    showPitchDiagram: Boolean,
    showPitchNumber: Boolean,
    showPitchText: Boolean,
    groupPitches: Boolean,
    activeProfile: AnkiProfile,
    onAnkiLookup: ((Int, Int?, String?, String?, Boolean) -> Unit)?,
    onRecursiveLookup: ((String, String?, Int?, Float?, Float?, String?) -> Unit)?,
    wordAudioEnabled: Boolean,
    autoplay: Boolean,
    modifier: Modifier,
    usePlainTextBody: Boolean = false,
    uiState: TermCardUiState? = null,
    showDictionaryGroups: Boolean = true,
) {
    val cardState = uiState ?: remember(card) { TermCardUiState() }
    val overrideState = cardState.overrides
    val collapseMode = activeProfile.dictionaryCollapseMode
    // Stays here (not in the Anki leaf): the dict-group headers below read AND write it
    // (long-press picks the add target + highlight), so a change must recompose the card.
    // An Anki add only swaps the `existingExpressions` local, which this scope never reads.
    val selectedDict = cardState.selectedDict

    val termTagList = remember(card.termTags) {
        card.termTags.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
    }
    val headColor = when {
        termTagList.any { it.contains("popular", true) || it.contains(" p ", true) } -> accent
        termTagList.any { it.contains("rare", true) || it.contains("arch", true) || it.contains("obs", true) } ->
            secondary.copy(alpha = 0.7f)
        else -> onBg
    }

    Column(
        modifier = modifier
            .then(
                if (index > 0) {
                    Modifier.drawBehind {
                        drawLine(
                            color = border,
                            start = Offset.Zero,
                            end = Offset(size.width, 0f),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                } else {
                    Modifier
                },
            )
            .padding(top = 10.dp, bottom = if (showDictionaryGroups) 8.dp else 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (onRecursiveLookup != null) {
                            Modifier.clickable { onRecursiveLookup(card.expression, null, null, null, null, "term") }
                        } else Modifier,
                    ),
            ) {
                Headword(
                    expression = card.expression,
                    reading = card.reading,
                    color = headColor,
                    fontSize = (fontSize * 1.6).sp,
                    fontWeight = FontWeight.Bold,
                    onKanjiTap = if (onRecursiveLookup != null) {
                        { ch -> onRecursiveLookup(ch, null, null, null, null, "kanji") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (wordAudioEnabled) {
                WordAudioButton(
                    expression = card.expression,
                    reading = card.reading,
                    accent = accent,
                    onBg = onBg,
                    autoplay = autoplay,
                    modifier = Modifier.size(28.dp),
                )
            }
            if (onAnkiLookup != null) {
                TermCardAnkiActions(
                    index = index,
                    expression = card.expression,
                    firstDictTitle = card.dictGroups.firstOrNull()?.title,
                    selectedDict = selectedDict,
                    dupAction = activeProfile.ankiDupAction,
                    onAnkiLookup = onAnkiLookup,
                    onBg = onBg,
                )
            }
        }

        if (termTagList.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                termTagList.take(8).forEach { tag ->
                    dictionaryTag(label = tag, secondary = secondary, eInk = eInkMode, fontSize = fontSize)
                }
            }
        }

        val inflected = card.process.isNotEmpty() && card.process.map { it.name } != listOf(card.expression)
        if (inflected) {
            var showDetails by remember(card) { mutableStateOf(false) }
            // Left→right: surface→base. process[0] is the last step applied so reverse.
            val labelText = remember(card.process) { card.process.asReversed().joinToString(" » ") { "${it.name}: ${it.description}" } }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDetails = !showDetails }
                    .padding(top = 4.dp, bottom = 2.dp),
            ) {
                Text(
                    text = labelText,
                    color = onBg.copy(alpha = 0.6f),
                    fontSize = (fontSize - 3).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (showDetails) "\u25B2" else "\u25BC",
                    color = onBg.copy(alpha = 0.5f),
                    fontSize = (fontSize - 4).sp,
                )
            }
            // Plain `if`, not AnimatedVisibility: every card put an
            // AnimatedEnterExitMeasurePolicy + BlockGraphicsLayerModifier in the measure chain
            // (traces showed them nesting inside the LazyColumn item), for a disclosure that
            // cannot be seen animating while scrolling anyway.
            if (showDetails) {
                Column(Modifier.padding(top = 2.dp)) {
                    val ruleSet = remember(card.rules) { card.rules.split(WHITESPACE_REGEX).filter { it.isNotBlank() } }
                    if (ruleSet.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(bottom = 6.dp),
                        ) {
                            ruleSet.forEach { rule ->
                                Surface(
                                    shape = if (eInkMode) RectangleShape else RoundedCornerShape(4.dp),
                                    color = if (eInkMode) Color.Transparent else accent.copy(alpha = 0.12f),
                                    border = if (eInkMode) BorderStroke(1.dp, border) else null,
                                ) {
                                    Text(
                                        text = rule,
                                        color = if (eInkMode) onBg else accent,
                                        fontSize = (fontSize - 4).sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                    }
                    card.process.asReversed().forEachIndexed { i, step ->
                        Surface(
                            shape = if (eInkMode) RectangleShape else RoundedCornerShape(8.dp),
                            color = hoverBg,
                            border = BorderStroke(1.dp, border),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .background(
                                                if (eInkMode) Color.Black else accent,
                                                if (eInkMode) RectangleShape else CircleShape,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "${i + 1}",
                                            color = Color.White,
                                            fontSize = (fontSize * 0.75).sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = step.name,
                                        color = onBg,
                                        fontSize = (fontSize * 0.88).sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                if (step.description.isNotBlank()) {
                                    Text(
                                        text = step.description,
                                        color = secondary,
                                        fontSize = (fontSize * 0.88).sp,
                                        lineHeight = (fontSize * 0.88).sp * 1.4f,
                                        modifier = Modifier.padding(top = 3.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val freqChips = buildFrequencyChips(card.frequencies, showFrequencyHarmonic, showFrequencyAverage)
        if (freqChips.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                freqChips.forEach { chip ->
                    dictionaryTag(
                        label = chip.label,
                        body = chip.body,
                        secondary = secondary,
                        eInk = eInkMode,
                        category = "frequency",
                        fontSize = fontSize,
                    )
                }
            }
        }

        if (showPitchDiagram || showPitchNumber || showPitchText) {
            PitchSection(
                card = card,
                accent = accent,
                secondary = secondary,
                onBg = onBg,
                showPitchDiagram = showPitchDiagram,
                showPitchNumber = showPitchNumber,
                showPitchText = showPitchText,
                groupPitches = groupPitches,
            )
        }

        val customOpenNames = remember(card, activeProfile, collapseMode) {
            when (collapseMode) {
                AnkiProfile.DICTIONARY_COLLAPSE_CUSTOM -> {
                    val displayModes = activeProfile.dictionaryDisplayModes
                    val open = mutableSetOf<String>()
                    var contentOpened = false
                    for (group in card.dictGroups) {
                        val mode = displayModes[group.dictName] ?: "fallback"
                        if (mode == "always_collapsed") continue
                        if (mode == "always_expanded" || !contentOpened) {
                            open.add(group.dictName)
                            contentOpened = true
                        }
                    }
                    open
                }
                else -> emptySet()
            }
        }

        if (showDictionaryGroups) for ((i, group) in card.dictGroups.withIndex()) {
            DictGroupView(
                group = group,
                groupIndex = i,
                collapseMode = collapseMode,
                customOpenNames = customOpenNames,
                overrideState = overrideState,
                selectedDict = selectedDict,
                onSelectDict = { cardState.selectedDict = it },
                fontSize = fontSize,
                accent = accent,
                secondary = secondary,
                hoverBg = hoverBg,
                onBg = onBg,
                border = border,
                eInkMode = eInkMode,
                parsedCssMap = parsedCssMap,
                onRecursiveLookup = onRecursiveLookup,
                usePlainTextBody = usePlainTextBody,
            )
        }
    }
}

/**
 * One collapsible dictionary group: header plus, when expanded, its gloss rows.
 *
 * The collapse override for THIS title is read here — not in [TermCardView] — so tapping a
 * group header recomposes only that group: sibling groups keep identical parameters and skip,
 * and the card's ruby/table measurement is not re-walked. [CollapseOverrideState] itself is a
 * stable holder (its identity never changes); only the per-title read subscribes.
 */
@Composable
private fun DictGroupView(
    group: DictionaryGroup,
    groupIndex: Int,
    collapseMode: String,
    customOpenNames: Set<String>,
    overrideState: CollapseOverrideState,
    selectedDict: String?,
    onSelectDict: (String?) -> Unit,
    fontSize: Int,
    accent: Color,
    secondary: Color,
    hoverBg: Color,
    onBg: Color,
    border: Color,
    eInkMode: Boolean,
    parsedCssMap: ImmutableMap<String, ParsedCss>,
    onRecursiveLookup: ((String, String?, Int?, Float?, Float?, String?) -> Unit)?,
    usePlainTextBody: Boolean,
    showHeader: Boolean = true,
    glossIndex: Int? = null,
    showGlosses: Boolean = true,
) {
    val override = overrideState[group.title]
    val initial = dictionaryInitiallyExpanded(group, groupIndex, collapseMode, customOpenNames)
    val expanded = override ?: initial

    Column(modifier = Modifier.padding(top = if (!showHeader) 0.dp else if (groupIndex == 0) 2.dp else 4.dp)) {
        if (showHeader) {
        val isSelected = selectedDict == group.title
        val headerBg = when {
            eInkMode && isSelected -> accent
            isSelected -> hoverBg
            else -> Color.Transparent
        }
        val borderColor = when {
            isSelected -> accent
            else -> Color.Transparent
        }
        val headerLabelColor = when {
            eInkMode && isSelected -> hoverBg
            isSelected -> accent
            else -> secondary
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(headerBg)
                .pointerInput(group.title) {
                    detectTapGestures(
                        onLongPress = { onSelectDict(if (isSelected) null else group.title) },
                        onTap = { overrideState.toggle(initial, group.title) },
                    )
                }
                .padding(vertical = 4.dp)
                .drawBehind {
                    drawRect(
                        color = borderColor,
                        topLeft = Offset.Zero,
                        size = Size(4.dp.toPx(), size.height),
                    )
                }
                .padding(start = 12.dp),
        ) {
            GroupCaret(expanded = expanded, secondary = secondary, fontSize = fontSize)
            Spacer(Modifier.width(6.dp))
            Text(
                text = group.title,
                color = headerLabelColor,
                fontSize = (fontSize - 1).sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        }
        if (expanded && showGlosses) {
            Column(Modifier.padding(start = if (group.glosses.size == 1) 0.dp else 16.dp, end = 12.dp)) {
                group.glosses.forEachIndexed { i, gloss ->
                    if (glossIndex != null && i != glossIndex) return@forEachIndexed
                    GlossRow(
                        glossary = gloss.glossary,
                        definitionTags = gloss.definitionTags,
                        dictName = gloss.dictName,
                        fontSize = fontSize,
                        onBg = onBg,
                        secondary = secondary,
                        border = border,
                        eInkMode = eInkMode,
                        parsedCssMap = parsedCssMap,
                        onRecursiveLookup = onRecursiveLookup,
                        usePlainTextBody = usePlainTextBody,
                        parsedNodes = group.parsedGlosses.getOrNull(i).orEmpty(),
                    )
                }
            }
        }
    }
}

/**
 * The `▸` collapse caret on its own scope: the expand/collapse rotation animates over several
 * frames, and reading the animated value here keeps those frames off the group body (which would
 * otherwise recompose — re-resolving styles — 60×/s for the animation duration).
 */
@Composable
private fun GroupCaret(expanded: Boolean, secondary: Color, fontSize: Int) {
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, label = "caret")
    Text("▸", color = secondary.copy(alpha = 0.7f), fontSize = (fontSize - 2).sp, modifier = Modifier.rotate(rotation))
}

internal class TermCardUiState {
    val overrides = CollapseOverrideState(emptyMap())
    var selectedDict by mutableStateOf<String?>(null)
}

private fun dictionaryInitiallyExpanded(group: DictionaryGroup, index: Int, mode: String, custom: Set<String>) =
    when (mode) {
        AnkiProfile.DICTIONARY_COLLAPSE_EXPAND_ALL -> true
        AnkiProfile.DICTIONARY_COLLAPSE_EXPAND_FIRST_AVAILABLE -> index == 0
        AnkiProfile.DICTIONARY_COLLAPSE_CUSTOM -> group.dictName in custom
        else -> false
    }

private fun customOpenDictionaryNames(card: TermCard, profile: AnkiProfile): Set<String> {
    if (profile.dictionaryCollapseMode != AnkiProfile.DICTIONARY_COLLAPSE_CUSTOM) return emptySet()
    val open = mutableSetOf<String>()
    var contentOpened = false
    for (group in card.dictGroups) {
        val mode = profile.dictionaryDisplayModes[group.dictName] ?: "fallback"
        if (mode == "always_collapsed") continue
        if (mode == "always_expanded" || !contentOpened) { open.add(group.dictName); contentOpened = true }
    }
    return open
}

internal class CollapseOverrideState(initialByTitle: Map<String, Boolean>) {
    var enabled by mutableStateOf(initialByTitle)
        private set

    fun toggle(initial: Boolean, title: String) {
        val current = enabled[title] ?: initial
        enabled = enabled + (title to !current)
    }

    operator fun get(title: String): Boolean? = enabled[title]
}

@Composable
private fun PitchSection(
    card: TermCard,
    accent: Color,
    secondary: Color,
    onBg: Color,
    showPitchDiagram: Boolean,
    showPitchNumber: Boolean,
    showPitchText: Boolean,
    groupPitches: Boolean,
) {
    val all = if (groupPitches && card.pitches.isNotEmpty()) {
        listOf(
            PitchEntry(
                dictName = card.pitches.joinToString(", ") { it.dictName },
                pitchPositions = card.pitches.flatMap { it.pitchPositions.toList() }.distinct().toIntArray(),
            ),
        )
    } else card.pitches
    if (all.isEmpty()) return

    Column(Modifier.padding(top = 6.dp)) {
        val pitchText = if (card.reading.isNotBlank() && card.reading != card.expression) card.reading else card.expression
        val compact = all.size == 1
        all.forEach { pitch ->
            val hasTag = pitch.dictName.isNotBlank()
            if (hasTag && !compact) {
                Text(
                    pitch.dictName,
                    color = secondary.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            val positions = pitch.pitchPositions.toList().distinct()
            positions.forEach { pos ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = if (hasTag && !compact) 0.dp else 2.dp),
                ) {
                    if (hasTag && compact) {
                        Text(
                            pitch.dictName,
                            color = secondary.copy(alpha = 0.8f),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(end = 2.dp),
                        )
                    }
                    if (showPitchDiagram) {
                        PitchAccentDiagram(
                            text = pitchText,
                            downsteps = listOf(pos),
                            accentColor = accent,
                        )
                    }
                    if (showPitchText) {
                        PitchTextLine(
                            text = pitchText,
                            positions = listOf(pos),
                            color = onBg.copy(alpha = 0.8f),
                            fontSize = 11,
                        )
                    }
                    if (showPitchNumber) {
                        Text(
                            text = "[$pos]",
                            color = accent,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}

@Immutable
private data class FrequencyChip(val label: String, val body: String?)

private fun buildFrequencyChips(
    frequencies: List<FrequencyEntry>,
    showHarmonic: Boolean,
    showAverage: Boolean,
): List<FrequencyChip> {
    if (frequencies.isEmpty()) return emptyList()

    fun collectNumbers(): List<Int> {
        val out = mutableListOf<Int>()
        val seen = mutableSetOf<String>()
        for (group in frequencies) {
            if (!seen.add(group.dictName)) continue
            group.frequencies.forEach { freq ->
                if (freq.value > 0) {
                    out.add(freq.value)
                    return@forEach
                }
            }
        }
        return out
    }

    fun harmonic(): Int? {
        val numbers = collectNumbers()
        if (numbers.isEmpty()) return null
        val n = numbers.size
        val reciprocalSum = numbers.sumOf { 1.0 / it }
        return if (reciprocalSum == 0.0) null else (n / reciprocalSum).toInt()
    }

    fun averageRank(): Double? {
        val numbers = collectNumbers()
        if (numbers.isEmpty()) return null
        return numbers.sum() / numbers.size.toDouble()
    }

    fun formatRank(v: Double): String? {
        if (!v.isFinite() || v <= 0) return null
        val rounded = (v * 10).roundToInt() / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    val chips = mutableListOf<FrequencyChip>()

    if (showHarmonic) {
        harmonic()?.let { chips.add(FrequencyChip("freq", "harmonic: $it")) }
        if (showAverage) {
            averageRank()?.let { avg -> formatRank(avg)?.let { chips.add(FrequencyChip("avg", it)) } }
        }
        return chips
    }

    if (showAverage) {
        averageRank()?.let { avg -> formatRank(avg)?.let { chips.add(FrequencyChip("avg", it)) } }
    }

    // Default: compact per-dictionary chips like Yomitan frequency groups.
    for (group in frequencies) {
        val dictName = group.dictName.trim()
        val values = group.frequencies
            .mapNotNull { freq ->
                freq.displayValue.takeIf { it.isNotBlank() } ?: freq.value.takeIf { it > 0 }?.toString()
            }
            .distinct()
            .joinToString(", ")
        if (dictName.isNotBlank() && values.isNotBlank()) {
            chips.add(FrequencyChip(dictName, values))
        }
    }
    return chips
}

internal fun isMoraPitchHigh(moraIndex: Int, pitchAccentValue: Int): Boolean = when (pitchAccentValue) {
    0 -> moraIndex > 0
    1 -> moraIndex < 1
    else -> moraIndex > 0 && moraIndex < pitchAccentValue
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun GlossRow(
    glossary: String,
    definitionTags: String,
    dictName: String,
    fontSize: Int,
    onBg: Color,
    secondary: Color,
    border: Color,
    eInkMode: Boolean,
    parsedCssMap: ImmutableMap<String, ParsedCss>,
    onRecursiveLookup: ((String, String?, Int?, Float?, Float?, String?) -> Unit)?,
    usePlainTextBody: Boolean = false,
    /**
     * Pre-parsed nodes from the plan phase ([DictionaryGroup.parsedGlosses]). When present the
     * structured path below costs a lookup instead of a parse; the `remember` still keys on
     * both so a plan rebuild with new nodes re-resolves correctly.
     */
    parsedNodes: List<StructuredNode> = emptyList(),
) {
    if (usePlainTextBody) {
        val plain = remember(glossary) {
            val raw = glossary
            val t = raw.trimStart()
            val candidate = if (t.startsWith("[") || t.startsWith("{") || t.contains('<')) {
                try {
                    when (val e = parseStructuredGlossary(t)) {
                        is StructuredEntry.Tree -> e.nodes.joinToString("") { it.flatten() }
                        is StructuredEntry.PlainText -> raw
                    }
                } catch (_: Exception) { raw }
            } else raw
            // collapse weird spacing: multiple newlines/blank lines -> single newline, trim
            candidate.replace(BLANK_LINES_REGEX, "\n").replace(MULTI_SPACE_REGEX, " ").trim()
        }
        if (plain.isBlank()) return
        Column(Modifier.padding(vertical = 2.dp)) {
            val defTags = remember(definitionTags) {
                definitionTags.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
            }
            if (defTags.isNotEmpty()) {
                FlowRow(modifier = Modifier.padding(bottom = 2.dp)) {
                    defTags.take(8).forEach { tag ->
                        dictionaryTag(label = tag, secondary = secondary, eInk = eInkMode, fontSize = fontSize)
                    }
                }
            }
            Text(text = plain, color = onBg, fontSize = (fontSize - 1).sp, lineHeight = (fontSize * 1.35).sp)
        }
        return
    }
    val glossCss = remember(dictName, parsedCssMap) {
        parsedCssMap[dictName] ?: parsedCssMap[dictName.lowercase()] ?: ParsedCss.EMPTY
    }
    // The Compose renderer is a user option, so every gloss goes through it. A gloss that is not
    // structured content is just a text node. Parsing runs once per gloss in the plan phase; this
    // remember only covers producers that bypass the plan (none in production).
    val structuredNodes: List<StructuredNode> = remember(glossary, parsedNodes) {
        parsedNodes.ifEmpty { parseGlossaryNodes(glossary) }
    }
    // definitionTags (part-of-speech / field / archaic chips) must render for BOTH paths —
    // the structured branch previously returned before them, hiding half the entry info.
    val defTags = remember(definitionTags) {
        definitionTags.split(WHITESPACE_REGEX).filter { it.isNotBlank() }
    }
    // One stable lookup callback for both body paths. `onRecursiveLookup` is a fresh lambda on
    // every host recomposition, so it must never appear directly in a `remember` key — strong
    // skipping compares by identity and `remember` keys are never rescued by it.
    val lookupSink = rememberUpdatedState(onRecursiveLookup)
    val onLookup: ((String) -> Unit)? = remember(lookupSink) {
        { text -> lookupSink.value?.invoke(text, null, null, null, null, "term") }
    }
    Column(Modifier.padding(vertical = 3.dp)) {
        if (defTags.isNotEmpty()) {
            FlowRow(modifier = Modifier.padding(bottom = 2.dp)) {
                defTags.take(8).forEach { tag ->
                    dictionaryTag(label = tag, secondary = secondary, eInk = eInkMode, fontSize = fontSize)
                }
            }
        }
        StructuredGlossaryContent(
            nodes = structuredNodes,
            parsedCss = glossCss,
            dictName = dictName,
            fontSize = fontSize,
            onBg = onBg,
            secondary = secondary,
            border = border,
            onRecursiveLookup = onLookup,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun WordAudioButton(
    expression: String,
    reading: String,
    accent: Color,
    onBg: Color,
    autoplay: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var playing by remember(expression) { mutableStateOf(false) }

    LaunchedEffect(expression, autoplay) {
        if (!autoplay) return@LaunchedEffect
        val url = WordAudioPlayer.findAudio(expression, reading)
        if (url != null) WordAudioPlayer.playUrl(url)
    }

    IconButton(
        onClick = {
            if (playing) return@IconButton
            scope.launch {
                playing = true
                val url = WordAudioPlayer.findAudio(expression, reading)
                if (url != null) WordAudioPlayer.playUrl(url)
                playing = false
            }
        },
        modifier = modifier.size(28.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.VolumeUp,
            contentDescription = null,
            tint = if (playing) accent.copy(alpha = 0.7f) else onBg.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun KanjiEntryCard(
    kanji: KanjiCard,
    fontSize: Int,
    accent: Color,
    secondary: Color,
    border: Color,
    onBg: Color,
    eInkMode: Boolean,
    onRecursiveLookup: ((String, String?, Int?, Float?, Float?, String?) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(start = 10.dp, end = 0.dp, top = 12.dp, bottom = 6.dp)) {
        // Glyph
        Text(
            text = kanji.character,
            color = onBg,
            fontSize = (fontSize * 7).sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.padding(bottom = 6.dp),
        )

        // Dictionary tag
        if (kanji.dictName.isNotBlank()) {
            dictionaryTag(
                label = kanji.dictName,
                secondary = secondary,
                eInk = eInkMode,
                category = "dictionary",
                fontSize = fontSize,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        // Meanings
        if (kanji.definitions.isNotEmpty()) {
            KanjiSectionHeader("Meanings", secondary, baseFontSize = fontSize)
            Column(Modifier.padding(start = (fontSize * 1.2).dp)) {
                kanji.definitions.forEachIndexed { i, def ->
                    val defModifier = if (onRecursiveLookup != null && def.isNotBlank()) {
                        Modifier
                            .clickable { onRecursiveLookup(def, null, null, null, null, "term") }
                            .padding(vertical = 1.dp)
                    } else {
                        Modifier
                    }
                    Text(
                        text = def,
                        color = onBg,
                        fontSize = (fontSize - 1).sp,
                        lineHeight = (fontSize + 3).sp,
                        modifier = defModifier.padding(bottom = 4.dp),
                    )
                }
            }
        }

        // Readings
        if (kanji.onyomi.isNotEmpty() || kanji.kunyomi.isNotEmpty()) {
            KanjiSectionHeader("Readings", secondary, modifier = Modifier.padding(top = 8.dp), baseFontSize = fontSize)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                if (kanji.onyomi.isNotEmpty()) {
                    Text(
                        text = kanji.onyomi.joinToString(", "),
                        color = accent,
                        fontSize = (fontSize - 1).sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (kanji.kunyomi.isNotEmpty()) {
                    Text(
                        text = kanji.kunyomi.joinToString(", "),
                        color = onBg,
                        fontSize = (fontSize - 1).sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        // Statistics
        if (kanji.stats.isNotEmpty()) {
            KanjiSectionHeader("Statistics", secondary, modifier = Modifier.padding(top = 8.dp), baseFontSize = fontSize)
            Column(Modifier.padding(top = 2.dp)) {
                kanji.stats.forEach { (label, value) ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        // border-collapse: 0.5.dp avoids doubled 1dp lines between stacked rows
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(width = 0.5.dp, color = border, shape = RectangleShape),
                    ) {
                        Text(
                            text = label,
                            color = secondary,
                            fontSize = (fontSize * 0.9).sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .width(96.dp)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                        Text(
                            text = value,
                            color = onBg,
                            fontSize = (fontSize * 0.9).sp,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KanjiSectionHeader(
    title: String,
    accent: Color,
    fontScale: Float = 0.85f,
    baseFontSize: Int = 14,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title.uppercase(),
        color = accent,
        fontSize = (baseFontSize * fontScale).sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (baseFontSize * 0.05f).sp,
        modifier = modifier,
    )
}

/**
 * Recursive-lookup chrome. In `tabs` mode it shows one chip per open frame; in `stack` mode a single
 * back chip. Tapping the active tab pops the stack, matching the WebView renderer.
 */
@Composable
private fun RecursiveNavBar(
    tabs: List<GlossTab>,
    showTabs: Boolean,
    onBg: Color,
    secondary: Color,
    border: Color,
    hoverBg: Color,
    accent: Color,
    fontSize: Int,
    onTabSelect: ((Int) -> Unit)?,
    onBack: (() -> Unit)?,
) {
    val activeIndex = tabs.indexOfFirst { it.active }.takeIf { it >= 0 } ?: 0
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(hoverBg)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showTabs) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == activeIndex
                    Text(
                        text = tab.label,
                        color = if (selected) accent else secondary,
                        fontSize = (fontSize - 3).sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (selected) onBg.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { if (selected) onBack?.invoke() else onTabSelect?.invoke(index) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            } else {
                val previous = tabs.getOrNull(activeIndex - 1) ?: tabs.first()
                Text(
                    text = "← " + previous.label,
                    color = accent,
                    fontSize = (fontSize - 3).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onBack?.invoke() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        HorizontalDivider(color = border)
    }
}
