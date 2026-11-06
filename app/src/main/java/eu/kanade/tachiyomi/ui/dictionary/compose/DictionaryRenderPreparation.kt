package eu.kanade.tachiyomi.ui.dictionary.compose

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import chimahon.DictionaryStyle
import chimahon.LookupResult
import chimahon.anki.AnkiProfile
import chimahon.diagnostics.LookupProfiler
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryColorScheme
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tachiyomi.presentation.core.util.collectAsState
import androidx.compose.runtime.getValue
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.IdentityHashMap

private val definitionTagWhitespace = Regex("\\s+")

internal data class DictionaryRenderAppearance(
    val colorScheme: ColorScheme, val isDark: Boolean, val isAmoled: Boolean,
    val seedColor: Int, val bg: Color, val onBg: Color, val accent: Color,
    val secondary: Color, val border: Color, val hover: Color, val variables: Map<String, String>,
)

@Composable
internal fun rememberDictionaryRenderAppearance(eInkMode: Boolean): DictionaryRenderAppearance {
    val environment = LocalDictionaryRenderEnvironment.current
    val prefs = remember { Injekt.get<DictionaryPreferences>() }
    val themeMode by prefs.themeMode().collectAsState()
    val customColor by prefs.customColor().collectAsState()
    val seed = environment?.seedColor ?: if (customColor != 0) customColor else Injekt.get<UiPreferences>().colorTheme().get()
    val systemDark = isSystemInDarkTheme()
    val amoled = environment?.amoled ?: (themeMode == "pure_black")
    val dark = environment?.dark ?: when (themeMode) {
        "dark", "pure_black" -> true
        "light" -> false
        else -> if (customColor != 0) Color(seed).luminance() < 0.5f else systemDark
    }
    return remember(dark, amoled, seed, eInkMode) {
        val colors = getDictionaryColorScheme(dark, amoled, seed)
        val fg = if (eInkMode) { if (dark) Color.White else Color.Black } else colors.onSurface
        DictionaryRenderAppearance(colors, dark, amoled, seed,
            if (eInkMode) { if (dark) Color.Black else Color.White } else if (amoled && dark) Color.Black else colors.surface,
            fg, if (eInkMode) fg else colors.primary, if (eInkMode) fg else colors.onSurfaceVariant,
            if (eInkMode) fg else if (amoled && dark) Color.White.copy(alpha = 0.10f) else colors.outlineVariant,
            if (eInkMode) { if (dark) Color.Black else Color.White } else if (amoled && dark) Color.White.copy(alpha = 0.07f) else colors.surfaceVariant,
            dictionaryThemeVars(colors, dark, amoled))
    }
}

@Composable
internal fun rememberDictionaryPlanBuilder(width: Int, fontSize: Int, eInkMode: Boolean,
    customCss: String, profile: AnkiProfile, groupTerms: Boolean,
    cardOptions: NativeCardOptions = NativeCardOptions(),
    suppliedAppearance: DictionaryRenderAppearance? = null): DictionaryPlanBuilder {
    val context = LocalContext.current
    val environment = LocalDictionaryRenderEnvironment.current
    val appearance = suppliedAppearance ?: rememberDictionaryRenderAppearance(eInkMode)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val resolver = LocalFontFamilyResolver.current
    return remember(context, environment, appearance, density, direction, resolver, width, fontSize, customCss, profile, groupTerms, cardOptions) {
        DictionaryPlanBuilder(context, appearance,
            NativeGlossaryLayoutContext(dictionaryBaseStyle(appearance.onBg, fontSize, null), density, direction,
                resolver, appearance.secondary, appearance.border, width), customCss, profile, groupTerms, cardOptions) { name ->
            if (environment == null) getDictionaryTitle(context, name) else environment.dictionaryTitles[name] ?: name
        }
    }
}

/** One lookup's job; no cross-term cache and no mutable renderer resources cross worker threads. */
class DictionaryPlanPreparation internal constructor(
    private val builder: DictionaryPlanBuilder, private val results: List<LookupResult>,
    private val styles: List<DictionaryStyle>, private val entryJsons: List<String>?,
    private val task: Deferred<Result<RenderPlan>>,
) {
    internal fun matches(builder: DictionaryPlanBuilder, results: List<LookupResult>, styles: List<DictionaryStyle>, entryJsons: List<String>?) =
        this.builder.compatible(builder) && this.results === results && this.styles == styles && this.entryJsons == entryJsons && !task.isCancelled
    internal suspend fun await(): RenderPlan = task.await().getOrThrow()
    internal fun cancel() = task.cancel()
}

/**
 * Owns one NativeCoreCompiler at a time under a mutex, so parallel preparation
 * workers serialize but the compiler (and its CSS openBatch contexts, font
 * resources and em-ratio cache) survives across opens of the same builder.
 */
internal class SharedNativeCore(
    private val density: androidx.compose.ui.unit.Density,
    private val direction: androidx.compose.ui.unit.LayoutDirection,
    private val resolver: androidx.compose.ui.text.font.FontFamily.Resolver,
    private val secondary: androidx.compose.ui.graphics.Color,
    private val border: androidx.compose.ui.graphics.Color,
) {
    private val lock = Any()
    private var core: NativeCoreCompiler? = null

    fun withCore(block: (NativeCoreCompiler) -> Unit) {
        synchronized(lock) {
            val c = core ?: NativeCoreCompiler(density, direction, resolver, secondary, border).also { core = it }
            block(c)
        }
    }

    suspend fun prepareBatch(
        profilingId: Long,
        nextWork: () -> GlossaryLayoutWork?,
        layout: eu.kanade.tachiyomi.ui.dictionary.compose.NativeGlossaryLayoutContext,
    ): IdentityHashMap<List<eu.kanade.tachiyomi.ui.dictionary.compose.StructuredNode>, eu.kanade.tachiyomi.ui.dictionary.compose.NativeGlossaryDocument> {
        val caller = kotlinx.coroutines.currentCoroutineContext()
        val pending = ArrayList<GlossaryLayoutWork>()
        while (true) { caller.ensureActive(); pending.add(nextWork() ?: break) }
        val stats = layout.createResources()
        val out = IdentityHashMap<List<eu.kanade.tachiyomi.ui.dictionary.compose.StructuredNode>, eu.kanade.tachiyomi.ui.dictionary.compose.NativeGlossaryDocument>()
        withCore { core ->
            val documents = core.prepareBatch(pending.map { item ->
                eu.kanade.tachiyomi.ui.dictionary.compose.NativeDrawingInput(
                    eu.kanade.tachiyomi.ui.dictionary.compose.nativeCoreRaw(item.nodes),
                    item.css.nativeSourceCss, item.width, item.nodes, item.css)
            }, layout.style, dark = layout.style.color.luminance() > 0.5f, checkCancellation = { caller.ensureActive() })
            documents.forEachIndexed { index, document -> out[pending[index].nodes] = document }
            if (profilingId != 0L) chimahon.diagnostics.LookupProfiler.mark(profilingId, "plan.native.batch",
                "documents=${pending.size} paragraphs=${stats.paragraphs} ruby=${stats.rubyPairs} " +
                    "inputMs=${core.lastTiming.inputMs} measureMs=${core.lastTiming.measureMs} finishMs=${core.lastTiming.finishMs} transferMs=${core.lastTiming.transferMs} releaseMs=${core.lastTiming.releaseMs} configurationMs=${core.lastTiming.configurationMs}")
        }
        return out
    }

    fun close() {
        synchronized(lock) {
            core?.close()
            core = null
        }
    }
}

internal class DictionaryPlanBuilder(
    private val context: Context, val appearance: DictionaryRenderAppearance,
    val layout: NativeGlossaryLayoutContext, private val customCss: String,
    private val profile: AnkiProfile, private val groupTerms: Boolean,
    private val cardOptions: NativeCardOptions = NativeCardOptions(),
    private val resolveTitle: (String) -> String,
) {
    /**
     * One SharedNativeCore per builder. Each preparation worker still runs on its own
     * coroutine (its own NativeCoreCompiler) per the existing thread-confinement
     * rule, but reusing a single SharedCore across opens means the CSS/index
     * openBatch is skipped after the first prepare — the ~61.7ms configuration
     * cost was paid fresh on every open.
     */
    private val sharedCore = SharedNativeCore(
        layout.density, layout.direction, layout.resolver, layout.secondary, layout.border
    )

    fun compatible(other: DictionaryPlanBuilder): Boolean = context === other.context &&
        appearance.variables == other.appearance.variables && customCss == other.customCss && profile == other.profile && groupTerms == other.groupTerms && cardOptions == other.cardOptions &&
        layout.style == other.layout.style && layout.density == other.layout.density && layout.direction == other.layout.direction &&
        layout.resolver === other.layout.resolver && layout.viewportWidth == other.layout.viewportWidth &&
        layout.secondary == other.layout.secondary && layout.border == other.layout.border

    fun start(scope: CoroutineScope, results: List<LookupResult>, styles: List<DictionaryStyle>, id: Long,
        cards: DictionaryCardPreparation?, entryJsons: List<String>?): DictionaryPlanPreparation {
        LookupProfiler.mark(id, "plan.queued", "origin=popup-result")
        return DictionaryPlanPreparation(this, results, styles, entryJsons, scope.async(Dispatchers.Default) {
            try {
                Result.success(prepare(results, styles, id, cards, entryJsons))
            } catch (error: CancellationException) {
                LookupProfiler.mark(id, "plan.cancelled")
                throw error
            } catch (error: Exception) {
                LookupProfiler.mark(id, "plan.failed", "${error.javaClass.simpleName}: ${error.message}")
                Result.failure(error)
            }
        })
    }

    suspend fun prepare(results: List<LookupResult>, styles: List<DictionaryStyle>, id: Long,
        cards: DictionaryCardPreparation?, entryJsons: List<String>?): RenderPlan {
        LookupProfiler.mark(id, "plan.config", "schema=native-cpp-draw-v1 widthPolicy=assigned tablePolicy=allocated-grid rubyLookup=base-only workerLimit=4 popupWidth=${layout.viewportWidth} fontSize=${layout.style.fontSize.value} density=${layout.density.density} fontScale=${layout.density.fontScale} direction=${layout.direction} results=${results.size}")
        val plan = LookupProfiler.elapsedSection(id, "plan.prepare") {
            val (cssMap, planCards) = coroutineScope {
                val css = async { LookupProfiler.section(id, "plan.css") { buildParsedCssMap(styles, customCss, context, appearance.variables) } }
                val parsed = cards?.await() ?: LookupProfiler.elapsedSection(id, "plan.cards_parse") {
                    val priority = profile.dictionaryOrder.map(resolveTitle).withIndex().associate { it.value to it.index }
                    buildCards(results, groupTerms, resolveTitle, priority, id)
                }
                css.await() to parsed
            }
            val documents = IdentityHashMap<List<StructuredNode>, NativeGlossaryDocument>()
            val presentation = ArrayList<TermCardPresentation>(planCards.size)
            val cardPieces = ArrayList<List<NativeCardPiece>>(planCards.size)
            val work = ArrayList<GlossaryLayoutWork>()
            val seen = IdentityHashMap<List<StructuredNode>, Boolean>()
            for (card in planCards) {
                val customOpen = customOpenDictionaryNames(card, profile)
                val groups = ArrayList<GlossaryPresentation>(card.dictGroups.size)
                for ((groupIndex, group) in card.dictGroups.withIndex()) {
                    val expanded = dictionaryInitiallyExpanded(group, groupIndex, profile.dictionaryCollapseMode, customOpen)
                    val css = cssMap[group.dictName] ?: cssMap[group.dictName.lowercase()] ?: cssMap[""] ?: ParsedCss.EMPTY
                    val width = layout.glossaryWidth(group.glosses.size != 1)
                    groups.add(GlossaryPresentation(css, width, expanded, group.glosses.map { gloss ->
                        gloss.definitionTags.split(definitionTagWhitespace).filter { it.isNotBlank() }.take(8)
                    }))
                    if (!expanded) continue
                    for ((index, nodes) in group.parsedGlosses.withIndex()) {
                        if (seen.put(nodes, true) != null) continue
                        work.add(GlossaryLayoutWork(nodes, group.dictName, group.glosses[index].glossary.length, css, width, work.size))
                    }
                }
                val shown = TermCardPresentation(customOpen, groups)
                presentation.add(shown)
                val pieces = nativeCardPieces(card, shown, layout, appearance, cardOptions, cardPieces.size)
                cardPieces.add(pieces)
                for (piece in pieces) {
                    if (piece.action == "process-details") continue
                    if (piece.action == "process" && piece.expanded == true) continue
                    if (piece.group >= 0 && piece.expanded != null && piece.expanded != groups[piece.group].initiallyExpanded) continue
                    if (seen.put(piece.nodes, true) != null) continue
                    work.add(GlossaryLayoutWork(piece.nodes, piece.dictionary, nativeCoreSourceSize(piece.nodes),
                        piece.css, piece.width, work.size))
                }
            }
            work.sortByDescending { it.sourceLength }
            val next = java.util.concurrent.atomic.AtomicInteger()
            val nextWork = { work.getOrNull(next.getAndIncrement()) }
            val batches = if (work.size > 1) coroutineScope {
                val count = Runtime.getRuntime().availableProcessors().coerceIn(1, minOf(4, work.size))
                val buckets = List(count) { ArrayList<GlossaryLayoutWork>() }
                val weights = LongArray(count)
                for (item in work) {
                    val worker = weights.indices.minBy { weights[it] }
                    buckets[worker].add(item)
                    weights[worker] += item.sourceLength
                }
                buckets.map { bucket -> async(glossaryPreparationDispatcher) {
                    val iterator = bucket.iterator()
                    sharedCore.prepareBatch(id, { if (iterator.hasNext()) iterator.next() else null }, layout)
                } }.awaitAll()
            } else if (work.size <= 1) listOf(sharedCore.prepareBatch(id, nextWork, layout)) else coroutineScope {
                List(Runtime.getRuntime().availableProcessors().coerceIn(1, minOf(4, work.size))) {
                    async(glossaryPreparationDispatcher) { sharedCore.prepareBatch(id, nextWork, layout) }
                }.awaitAll()
            }
            for (batch in batches) documents.putAll(batch)
            LookupProfiler.mark(id, "plan.work", "documents=${documents.size} glosses=${work.size} batches=${batches.size} scheduling=largest-source-first")
            val nativeCards = LookupProfiler.section(id, "plan.native.cards") {
                planCards.mapIndexed { i, card ->
                    val own = IdentityHashMap<List<StructuredNode>, NativeGlossaryDocument>()
                    cardPieces[i].forEach { piece -> documents[piece.nodes]?.let { own[piece.nodes] = it } }
                    NativeTermCard(card, cardPieces[i], presentation[i], layout, own, id, appearance, i)
                }
            }
            RenderPlan(entryJsons?.mapNotNull { parseKanjiEntryJson(it) } ?: emptyList(), cssMap, planCards,
                documents, presentation, layout, nativeCards = nativeCards)
        }
        LookupProfiler.mark(id, "plan.ready")
        return plan
    }
}
