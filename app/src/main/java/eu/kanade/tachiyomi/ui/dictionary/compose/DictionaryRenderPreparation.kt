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
    internal val planId: Long = 0L,
) {
    internal fun matches(builder: DictionaryPlanBuilder, results: List<LookupResult>, styles: List<DictionaryStyle>, entryJsons: List<String>?) =
        this.builder.compatible(builder) && this.results === results && this.styles == styles && this.entryJsons == entryJsons && !task.isCancelled
    internal suspend fun await(): RenderPlan = task.await().getOrThrow()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal fun ready(): RenderPlan? = if (task.isCompleted && !task.isCancelled) task.getCompleted().getOrNull() else null
    internal fun cancel() = task.cancel()

    internal fun mismatchReasons(builder: DictionaryPlanBuilder, results: List<LookupResult>, styles: List<DictionaryStyle>, entryJsons: List<String>?) = buildList {
        addAll(this@DictionaryPlanPreparation.builder.differences(builder))
        if (this@DictionaryPlanPreparation.results !== results) add("resultsIdentity")
        if (this@DictionaryPlanPreparation.styles != styles) add("styles")
        if (this@DictionaryPlanPreparation.entryJsons != entryJsons) add("entryJsons")
        if (task.isCancelled) add("cancelled")
    }
}

/** One live history frame owns its preparation, including environment-triggered replacements. */
class DictionaryPlanSlot internal constructor(private val scope: CoroutineScope,
    private var preparation: DictionaryPlanPreparation? = null) {
    internal fun matching(builder: DictionaryPlanBuilder, results: List<LookupResult>,
        styles: List<DictionaryStyle>, entryJsons: List<String>?): DictionaryPlanPreparation? =
        preparation?.takeIf { it.matches(builder, results, styles, entryJsons) }

    internal fun start(builder: DictionaryPlanBuilder, results: List<LookupResult>, styles: List<DictionaryStyle>,
        id: Long, cards: DictionaryCardPreparation?, entryJsons: List<String>?): DictionaryPlanPreparation {
        matching(builder, results, styles, entryJsons)?.let { return it }
        preparation?.cancel()
        return builder.start(scope, results, styles, id, cards, entryJsons).also { preparation = it }
    }

    internal fun cancel() { preparation?.cancel() }
}

internal class DictionaryPlanBuilder(
    private val context: Context, val appearance: DictionaryRenderAppearance,
    val layout: NativeGlossaryLayoutContext, private val customCss: String,
    private val profile: AnkiProfile, private val groupTerms: Boolean,
    private val cardOptions: NativeCardOptions = NativeCardOptions(),
    private val resolveTitle: (String) -> String,
) {
    fun differences(other: DictionaryPlanBuilder): List<String> = buildList {
        if (context !== other.context) add("contextIdentity")
        if (appearance.variables != other.appearance.variables) add("themeVariables")
        if (customCss != other.customCss) add("customCss")
        if (profile != other.profile) add("profile")
        if (groupTerms != other.groupTerms) add("groupTerms")
        if (cardOptions != other.cardOptions) add("cardOptions")
        if (layout.style != other.layout.style) add("textStyle")
        if (layout.density != other.layout.density) add("density")
        if (layout.direction != other.layout.direction) add("direction")
        if (layout.resolver !== other.layout.resolver) add("fontResolverIdentity")
        if (layout.viewportWidth != other.layout.viewportWidth) add("width:${layout.viewportWidth}->${other.layout.viewportWidth}")
        if (layout.secondary != other.layout.secondary) add("secondaryColor")
        if (layout.border != other.layout.border) add("borderColor")
    }

    fun compatible(other: DictionaryPlanBuilder): Boolean = context === other.context &&
        appearance.variables == other.appearance.variables && customCss == other.customCss && profile == other.profile && groupTerms == other.groupTerms && cardOptions == other.cardOptions &&
        layout.style == other.layout.style && layout.density == other.layout.density && layout.direction == other.layout.direction &&
        layout.resolver === other.layout.resolver && layout.viewportWidth == other.layout.viewportWidth &&
        layout.secondary == other.layout.secondary && layout.border == other.layout.border

    fun start(scope: CoroutineScope, results: List<LookupResult>, styles: List<DictionaryStyle>, id: Long,
        cards: DictionaryCardPreparation?, entryJsons: List<String>?): DictionaryPlanPreparation {
        val planId = NativeRenderDiagnostics.nextPlanId()
        NativeRenderDiagnostics.mark(id, planId, "plan.queued", "origin=popup-result")
        return DictionaryPlanPreparation(this, results, styles, entryJsons, scope.async(Dispatchers.Default) {
            try {
                Result.success(prepare(results, styles, id, cards, entryJsons, planId))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
        }, planId)
    }

    suspend fun prepare(results: List<LookupResult>, styles: List<DictionaryStyle>, id: Long,
        cards: DictionaryCardPreparation?, entryJsons: List<String>?, planId: Long = NativeRenderDiagnostics.nextPlanId()): RenderPlan {
        NativeRenderDiagnostics.mark(id, planId, "plan.config", "schema=native-cpp-draw-v1 widthPolicy=assigned tablePolicy=allocated-grid rubyLookup=base-only workerLimit=$glossaryWorkerCount popupWidth=${layout.viewportWidth} fontSize=${layout.style.fontSize.value} density=${layout.density.density} fontScale=${layout.density.fontScale} direction=${layout.direction} results=${results.size}")
        val plan = NativeRenderDiagnostics.elapsed(id, planId, "plan.prepare") {
            val (cssMap, planCards) = NativeRenderDiagnostics.elapsed(id, planId, "plan.inputs.await") {
                coroutineScope {
                    val css = async { NativeRenderDiagnostics.section(id, planId, "plan.css") { buildParsedCssMap(styles, customCss, context, appearance.variables) } }
                    val parsed = if (cards != null) NativeRenderDiagnostics.elapsed(id, planId, "plan.cards.await") { cards.await() }
                    else NativeRenderDiagnostics.elapsed(id, planId, "plan.cards_parse") {
                        val priority = profile.dictionaryOrder.map(resolveTitle).withIndex().associate { it.value to it.index }
                        buildCards(results, groupTerms, resolveTitle, priority, id)
                    }
                    css.await() to parsed
                }
            }
            val documents = IdentityHashMap<List<StructuredNode>, NativeGlossaryDocument>()
            val presentation = ArrayList<TermCardPresentation>(planCards.size)
            val cardPieces = ArrayList<List<NativeCardPiece>>(planCards.size)
            val work = ArrayList<GlossaryLayoutWork>()
            val seen = IdentityHashMap<List<StructuredNode>, Boolean>()
            NativeRenderDiagnostics.elapsed(id, planId, "plan.work.build") {
                val cardTheme = NativeCardTheme(appearance)
                for (card in planCards) {
                    val built = buildCard(card, cssMap, layout, profile, cardTheme, cardOptions)
                    presentation.add(built.shown)
                    cardPieces.add(built.pieces)
                    for (piece in built.pieces) {
                        if (piece.action == "process-details") continue
                        if (piece.action == "process" && piece.expanded == true) continue
                        if (piece.group >= 0 && piece.expanded != null && piece.expanded != built.groups[piece.group].initiallyExpanded) continue
                        if (seen.put(piece.nodes, true) != null) continue
                        work.add(GlossaryLayoutWork(piece.nodes, piece.dictionary, nativeCoreSourceSize(piece.nodes),
                            piece.css, piece.width, work.size))
                    }
                }
                work.sortByDescending { it.sourceLength }
            }
            val next = java.util.concurrent.atomic.AtomicInteger()
            val nextWork = { work.getOrNull(next.getAndIncrement()) }
            val batches = NativeRenderDiagnostics.elapsed(id, planId, "plan.batches.await") {
                if (work.size > 1) coroutineScope {
                    val count = minOf(glossaryWorkerCount, work.size).coerceAtLeast(1)
                    // Balance text, not DOM size. A markup-heavy gloss has a large sourceWeight
                    // with almost no text (124 utf16), while a bucket of small glosses costs far
                    // more; text plus a per-document constant tracks the measured cost.
                    val buckets = List(count) { ArrayList<GlossaryLayoutWork>(work.size / count + 1) }
                    val weights = LongArray(count)
                    for (item in work) {
                        val worker = weights.indices.minBy { weights[it] }
                        buckets[worker].add(item)
                        weights[worker] += nativeCoreTextSize(item.nodes) + 1024
                    }
                    buckets.mapIndexed { worker, bucket ->
                        val queued = NativeRenderDiagnostics.now()
                        async(glossaryPreparationDispatcher) {
                            NativeRenderDiagnostics.queueStarted(id, planId, worker, queued)
                            val iterator = bucket.iterator()
                            prepareGlossaryBatch(id, { if (iterator.hasNext()) iterator.next() else null }, layout, planId, worker)
                        }
                    }.awaitAll()
                } else listOf(prepareGlossaryBatch(id, nextWork, layout, planId))
            }
            NativeRenderDiagnostics.section(id, planId, "plan.documents.merge") {
                for (batch in batches) documents.putAll(batch)
            }
            NativeRenderDiagnostics.mark(id, planId, "plan.work", "documents=${documents.size} glosses=${work.size} batches=${batches.size} scheduling=text-weight")
            val nativeCards = NativeRenderDiagnostics.section(id, planId, "plan.native.cards") {
                planCards.mapIndexed { i, card ->
                    val own = IdentityHashMap<List<StructuredNode>, NativeGlossaryDocument>()
                    cardPieces[i].forEach { piece -> documents[piece.nodes]?.let { own[piece.nodes] = it } }
                    NativeTermCard(card, cardPieces[i], presentation[i], layout, own, id, appearance, i)
                }
            }
            NativeRenderDiagnostics.section(id, planId, "plan.assemble") {
                RenderPlan(entryJsons?.mapNotNull { parseKanjiEntryJson(it) } ?: emptyList(), cssMap, planCards,
                    documents, presentation, layout, nativeCards = nativeCards, profilingPlanId = planId)
            }
        }
        NativeRenderDiagnostics.section(id, planId, "plan.diagnostics.flush") { LookupProfiler.flush() }
        NativeRenderDiagnostics.mark(id, planId, "plan.ready")
        return plan
    }
}

private class BuiltCard(
    val shown: TermCardPresentation,
    val groups: List<GlossaryPresentation>,
    val pieces: List<NativeCardPiece>,
)

/** One card's presentation and native pieces; independent of every other card. */
private fun buildCard(card: TermCard, cssMap: Map<String, ParsedCss>,
    layout: NativeGlossaryLayoutContext, profile: AnkiProfile,
    cardTheme: NativeCardTheme, cardOptions: NativeCardOptions): BuiltCard {
    val customOpen = customOpenDictionaryNames(card, profile)
    val groups = ArrayList<GlossaryPresentation>(card.dictGroups.size)
    for ((groupIndex, group) in card.dictGroups.withIndex()) {
        val expanded = dictionaryInitiallyExpanded(group, groupIndex, profile.dictionaryCollapseMode, customOpen)
        val css = cssMap[group.dictName] ?: cssMap[group.dictName.lowercase()] ?: cssMap[""] ?: ParsedCss.EMPTY
        val width = layout.glossaryWidth(group.glosses.size != 1)
        groups.add(GlossaryPresentation(css, width, expanded, group.glosses.map { gloss ->
            gloss.definitionTags.split(definitionTagWhitespace).filter { it.isNotBlank() }.take(8)
        }))
    }
    val shown = TermCardPresentation(customOpen, groups)
    return BuiltCard(shown, groups, nativeCardPieces(card, shown, layout, cardTheme, cardOptions))
}
