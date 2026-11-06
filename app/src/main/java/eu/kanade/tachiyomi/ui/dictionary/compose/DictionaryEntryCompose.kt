package eu.kanade.tachiyomi.ui.dictionary.compose

import android.content.Context

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import java.util.IdentityHashMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chimahon.DictionaryStyle
import chimahon.FrequencyEntry
import chimahon.GlossaryEntry
import chimahon.LookupResult
import chimahon.PitchEntry
import chimahon.anki.AnkiProfile
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
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableMap
import kotlin.math.roundToInt

private val WHITESPACE_REGEX = Regex("\\s+")

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
     * Opaque glossary source identities, aligned with [glosses]. C++ parses each source
     * on the preparation worker; card placement reuses the finished fragments.
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
internal fun parseKanjiEntryJson(json: String): KanjiCard? {
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

internal val glossaryPreparationDispatcher = Dispatchers.Default.limitedParallelism(4)

internal class GlossaryLayoutWork(
    val nodes: List<StructuredNode>,
    val dictionary: String,
    val sourceLength: Int,
    val css: ParsedCss,
    val width: Int,
    val index: Int,
)


@Immutable
internal class GlossaryPresentation(
    val css: ParsedCss,
    val width: Int,
    val initiallyExpanded: Boolean,
    val definitionTags: List<List<String>>,
)

@Immutable
internal class TermCardPresentation(
    val customOpenNames: Set<String>,
    val groups: List<GlossaryPresentation>,
)

/**
 * Prepares glosses drawn from the shared queue on the calling worker.
 *
 * Owns its native compiler so font and style resources are never touched by two
 * threads at once. `checkCancellation` is bound to the caller's
 * coroutine context so a cancelled lookup stops the work in flight.
 */
internal suspend fun prepareGlossaryBatch(profilingId: Long, nextWork: () -> GlossaryLayoutWork?,
    layout: NativeGlossaryLayoutContext): IdentityHashMap<List<StructuredNode>, NativeGlossaryDocument> {
    val caller = currentCoroutineContext()
    val pending = ArrayList<GlossaryLayoutWork>()
    while (true) { caller.ensureActive(); pending.add(nextWork() ?: break) }
    val stats = layout.createResources()
    val out = IdentityHashMap<List<StructuredNode>, NativeGlossaryDocument>()
    NativeCoreCompiler(layout.density, layout.direction, layout.resolver, layout.secondary, layout.border, stats).use { core ->
        val documents = core.prepareBatch(pending.map { item ->
            NativeDrawingInput(nativeCoreRaw(item.nodes), item.css.nativeSourceCss, item.width, item.nodes, item.css)
        }, layout.style, dark = layout.style.color.luminance() > 0.5f, checkCancellation = { caller.ensureActive() })
        documents.forEachIndexed { index, document -> out[pending[index].nodes] = document }
        if (profilingId != 0L) chimahon.diagnostics.LookupProfiler.mark(profilingId, "plan.native.batch",
            "documents=${pending.size} paragraphs=${stats.paragraphs} ruby=${stats.rubyPairs} " +
                "inputMs=${core.lastTiming.inputMs} measureMs=${core.lastTiming.measureMs} finishMs=${core.lastTiming.finishMs} transferMs=${core.lastTiming.transferMs} releaseMs=${core.lastTiming.releaseMs} configurationMs=${core.lastTiming.configurationMs}")
    }
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
    profilingId: Long = 0,
    /** Actual bounded content width supplied by a host that already calculated its geometry. */
    viewportWidthPx: Int? = null,
    cardPreparation: DictionaryCardPreparation? = null,
    renderPreparation: DictionaryPlanPreparation? = null,
) {
    val context = LocalContext.current
    val appearance = rememberDictionaryRenderAppearance(eInkMode)
    val bgColor = appearance.bg
    val onBg = appearance.onBg
    val accent = appearance.accent
    val secondary = appearance.secondary
    val border = appearance.border
    val hoverBg = appearance.hover

    LaunchedEffect(results, isLoading) {
        onContentReadyChange?.invoke(!isLoading)
    }

    // Theme variable values for CSS `var()` resolution: byte-identical formulas to the WebView's
    // `dynamicThemeCss` injection ([DictionaryBootstrapHtml]), so both engines resolve the same
    // names to the same colors in every mode and seed. Remembered: the map is rebuilt only when
    // the theme actually changes, and it joins the plan keys below for the same reason.
    val themeVars = appearance.variables
    DictionaryViewport(modifier, viewportWidthPx) { popupWidthPx ->
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val fontResolver = LocalFontFamilyResolver.current
    val builder = rememberDictionaryPlanBuilder(popupWidthPx, fontSize, eInkMode, customCss, activeProfile, groupTerms,
        NativeCardOptions(showFrequencyHarmonic, showFrequencyAverage, showPitchDiagram, showPitchNumber, showPitchText, groupPitches), appearance)
    val suppliedPlan = renderPreparation?.takeIf { resultsAreOrdered && it.matches(builder, results, styles, entryJsons) }

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
        suppliedPlan,
    ) {
        if (isLoading) {
            value = RenderPlan.Empty
            return@produceState
        }
        val snapshotCardPreparation = cardPreparation?.takeIf {
            resultsAreOrdered && it.matches(results, groupTerms, activeProfile.dictionaryOrder)
        }
        chimahon.diagnostics.LookupProfiler.mark(profilingId, "plan.ui.await", "supplied=${suppliedPlan != null}")
        value = suppliedPlan?.await() ?: withContext(Dispatchers.Default) {
            chimahon.diagnostics.LookupProfiler.mark(profilingId, "plan.queued", "origin=renderer")
            val shown = if (resultsAreOrdered) results else orderLookupResultsForDisplay(results, activeProfile, context)
            builder.prepare(shown, styles, profilingId, snapshotCardPreparation, entryJsons)
        }
        chimahon.diagnostics.LookupProfiler.mark(profilingId, "plan.ui.publish")
        onPlanReady?.invoke()
    }
    val effectivePlan = plan ?: RenderPlan.Empty

    // Structural-equality maps for the card subtree. Plain Map parameters are unstable-typed, so
    // under strong skipping they compare by instance: every plan rebuild or media commit (new
    // instance, often equal content) would recompose the whole card tree. ImmutableMap compares
    // structurally, so equal-content passes skip. Recomputed only when the source instance
    // changes, i.e. exactly when content may have changed.
    val stableMedia = remember(mediaDataUris) { mediaDataUris.toImmutableMap() }

    // `popup` is handled by the host, which opens a child popup instead of pushing a frame here.
    val showTabStrip = recursiveNavMode == "tabs" && tabs.size > 1
    val showBackChip = recursiveNavMode == "stack" && tabs.size > 1

    CompositionLocalProvider(
        LocalLookupProfilingId provides profilingId,
        // Phase 3b: decoded media, read ONLY by the image leaf (StructuredImage) and the table
        // intrinsic memo key. Providing it here (not threading it as a param) means a late media
        // arrival recomposes just the images, not every card - same idea as LocalExistingExpressions.
        LocalMediaDataUris provides stableMedia,
        // Complete worker-prepared document geometry; the glossary host only paints and handles taps.
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
        val popupListState = androidx.compose.runtime.key(effectivePlan) {
            rememberLazyListState(cacheWindow = LazyLayoutCacheWindow(ahead = 0.dp, behind = 0.dp))
        }
        Box(Modifier.fillMaxSize()) {
            when {
                isLoading || effectivePlan == RenderPlan.Empty -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.MenuBook, contentDescription = null, tint = secondary, modifier = Modifier.size(32.dp))
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
                            Icon(Icons.Outlined.MenuBook, contentDescription = null, tint = secondary, modifier = Modifier.size(32.dp))
                        } else {
                            Text(placeholder, color = onBg.copy(alpha = 0.7f), fontSize = (fontSize - 2).sp)
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = popupListState,
                        contentPadding = PaddingValues(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                    ) {
                        items(count = effectivePlan.nativeCards.size, key = { "native-card-$it" },
                            contentType = { "native-card" }) { index ->
                            NativeTermCardContent(effectivePlan.nativeCards[index], index, activeProfile,
                                onAnkiLookup, onRecursiveLookup, wordAudioEnabled, wordAudioAutoplayOverride == true,
                                accent, onBg)
                        }
                    }
                }
        }
        }
    }
    }
    }
}

@Composable
private fun DictionaryViewport(modifier: Modifier, widthPx: Int?, content: @Composable (Int) -> Unit) {
    if (widthPx != null) {
        require(widthPx >= 0 && widthPx != androidx.compose.ui.unit.Constraints.Infinity)
        Box(modifier) { content(widthPx) }
    } else {
        BoxWithConstraints(modifier) { content(constraints.maxWidth) }
    }
}

/**
 * Everything the renderer needs, derived off the composition thread. Building this in one
 * background pass keeps JSON, CSS and native layout work out of composition and
 * gives every downstream `remember` a stable identity to hit.
 */
@Immutable
internal class RenderPlan(
    val kanjiCards: List<KanjiCard>,
    val parsedCssMap: Map<String, ParsedCss>,
    val cards: List<TermCard>,
    val documents: Map<List<StructuredNode>, NativeGlossaryDocument> = emptyMap(),
    val presentation: List<TermCardPresentation> = emptyList(),
    val layoutContext: NativeGlossaryLayoutContext? = null,
    val nativeCards: List<NativeTermCard> = emptyList(),
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
internal fun buildParsedCssMap(styles: List<DictionaryStyle>, customCss: String, context: Context,
    themeVars: Map<String, String> = emptyMap()): Map<String, ParsedCss> {
    val base = context.assets.open("dictionary/base.css").bufferedReader().use { it.readText() }
    val result = mutableMapOf("" to ParsedCss(nativeCoreStylesheet(base, customCss, themeVars)))
    for (sheet in styles) {
        val css = ParsedCss(nativeCoreStylesheet(base, sheet.styles + "\n" + customCss, themeVars))
        for (name in listOf(sheet.dictName, getDictionaryTitle(context, sheet.dictName))) {
            result[name] = css
            result[name.lowercase()] = css
        }
    }
    return result
}

internal suspend fun buildCards(
    results: List<LookupResult>,
    groupTerms: Boolean,
    resolveTitle: (String) -> String,
    priority: Map<String, Int>,
    profilingId: Long = 0,
    checkCancellation: () -> Unit = {},
): List<TermCard> {
    if (results.isEmpty()) return emptyList()

    fun rank(title: String): Int = priority[title] ?: Int.MAX_VALUE
    val titles = HashMap<String, String>()
    val parsed = HashMap<Triple<String, String, Boolean>, List<StructuredNode>>()
    fun parseSource(source: Triple<String, String, Boolean>): List<StructuredNode> {
        if (profilingId == 0L) return parseGlossaryNodes(source.second)
        val start = chimahon.diagnostics.LookupProfiler.timestamp()
        val cpu = chimahon.diagnostics.LookupProfiler.cpuTimestamp()
        val nodes = chimahon.diagnostics.LookupProfiler.section(profilingId, "fn.glossary.parse") {
            parseGlossaryNodes(source.second)
        }
        val end = chimahon.diagnostics.LookupProfiler.timestamp()
        val usedCpu = chimahon.diagnostics.LookupProfiler.cpuTimestamp() - cpu
        chimahon.diagnostics.LookupProfiler.section(profilingId, "fn.diagnostics.source") {
        chimahon.diagnostics.LookupProfiler.mark(profilingId, "plan.source", JSONObject()
            .put("dictionary", source.first).put("start_ns", start).put("end_ns", end).put("cpu_ns", usedCpu)
            .put("sourceUtf16Units", source.second.length).put("singleGloss", source.third).put("rootNodes", nodes.size).toString())
        }
        return nodes
    }

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
                    parsed.getValue(Triple(group.dictName, gloss.glossary, group.glosses.size == 1))
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
        checkCancellation()
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
            val group = acc.glosses.getOrPut(g.dictName) {
                DictionaryGroup(g.dictName, titles.getOrPut(g.dictName) { resolveTitle(g.dictName) }, mutableListOf())
            }
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
    // Parse every unique glossary once. Each source is independent, so spread them over the
    // available cores the same way the layout phase does; card order stays the source order.
    val sources = LinkedHashSet<Triple<String, String, Boolean>>()
    for (acc in out) for (group in acc.glosses.values) {
        for (gloss in group.glosses) {
            sources.add(Triple(group.dictName, gloss.glossary, group.glosses.size == 1))
        }
    }
    if (sources.isNotEmpty()) {
        val pending = sources.toList()
        val workers = if (pending.size <= 1) 1 else
            Runtime.getRuntime().availableProcessors().coerceIn(1, minOf(4, pending.size))
        if (workers == 1) {
            for (source in pending) {
                checkCancellation()
                parsed[source] = parseSource(source)
            }
        } else {
            val next = java.util.concurrent.atomic.AtomicInteger()
            val chunks = coroutineScope {
                List(workers) {
                    async(Dispatchers.Default) {
                        val done = ArrayList<Pair<Triple<String, String, Boolean>, List<StructuredNode>>>(pending.size / workers + 1)
                        while (true) {
                            val index = next.getAndIncrement()
                            if (index >= pending.size) break
                            checkCancellation()
                            val source = pending[index]
                            done.add(source to parseSource(source))
                        }
                        done
                    }
                }.awaitAll()
            }
            for (chunk in chunks) for ((source, nodes) in chunk) parsed[source] = nodes
        }
    }
    return out.map { it.finish() }
}

/**
 * Glossary text to render nodes. Pure function of the text (including throw behavior: no
 * catch here, matching [GlossRow]'s structured path), so parsing once per plan source is
 * output-identical to parsing per composition.
 */
internal fun parseGlossaryNodes(glossary: String): List<StructuredNode> = nativeCoreSource(glossary)

/**
 * Expressions already in Anki, provided at the [DictionaryEntryCompose] root and read ONLY by
 * [TermCardAnkiActions]. See the provider for why this is a local instead of a parameter.
 */
private val LocalExistingExpressions = compositionLocalOf { emptySet<String>() }

/**
 * The per-card Anki add/view buttons in their own recomposition scope.
 *
 * `alreadyAdded` is read here, not by the native document host, so when an Anki add swaps the
 * `existingExpressions` set, only these two IconButtons recompose. The card body (gloss, ruby,
 * tables) keeps identical parameters and is skipped. [selectedDict] stays owned by the card (the
 * group headers write it on long-press); it is only *passed* here.
 */
@Composable
internal fun TermCardAnkiActions(
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
    val addAction = rememberUpdatedState { onAnkiLookup(index, null, targetDict, null, false) }
    val openAction = rememberUpdatedState { onAnkiLookup(index, null, targetDict, null, true) }
    val onAdd = remember { { addAction.value() } }
    val onOpen = remember { { openAction.value() } }
    if (showAdd) {
        IconButton(
            onClick = onAdd,
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
            onClick = onOpen,
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


internal fun dictionaryInitiallyExpanded(group: DictionaryGroup, index: Int, mode: String, custom: Set<String>) =
    when (mode) {
        AnkiProfile.DICTIONARY_COLLAPSE_EXPAND_ALL -> true
        AnkiProfile.DICTIONARY_COLLAPSE_EXPAND_FIRST_AVAILABLE -> index == 0
        AnkiProfile.DICTIONARY_COLLAPSE_CUSTOM -> group.dictName in custom
        else -> false
    }

internal fun customOpenDictionaryNames(card: TermCard, profile: AnkiProfile): Set<String> {
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


@Immutable
internal data class FrequencyChip(val label: String, val body: String?)

internal fun buildFrequencyChips(
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
internal fun WordAudioButton(
    expression: String,
    reading: String,
    accent: Color,
    onBg: Color,
    autoplay: Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var playing by remember(expression, reading) { mutableStateOf(false) }
    var playbackRequest by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    androidx.compose.runtime.DisposableEffect(expression, reading) {
        onDispose { playbackRequest?.cancel() }
    }

    LaunchedEffect(expression, reading, autoplay) {
        if (!autoplay) return@LaunchedEffect
        val url = WordAudioPlayer.findAudio(expression, reading)
        if (url != null) WordAudioPlayer.playUrl(url)
    }

    IconButton(
        onClick = {
            if (playing) return@IconButton
            playbackRequest = scope.launch {
                playing = true
                try {
                    val url = WordAudioPlayer.findAudio(expression, reading)
                    if (url != null) WordAudioPlayer.playUrl(url)
                } finally {
                    playing = false
                }
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
