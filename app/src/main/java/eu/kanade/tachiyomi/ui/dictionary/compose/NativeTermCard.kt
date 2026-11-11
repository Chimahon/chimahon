package eu.kanade.tachiyomi.ui.dictionary.compose

import android.util.Base64
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import chimahon.anki.AnkiProfile
import chimahon.diagnostics.LookupProfiler
import chimahon.renderer.DictionaryCore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.IdentityHashMap
import kotlin.math.roundToInt

internal data class NativeCardOptions(val harmonic: Boolean = false, val average: Boolean = false,
    val pitchDiagram: Boolean = true, val pitchNumber: Boolean = true, val pitchText: Boolean = true,
    val groupPitches: Boolean = false)

internal class NativeCardPiece(val nodes: List<StructuredNode>, val css: ParsedCss, val width: Int,
    val dictionary: String = "", val group: Int = -1, val expanded: Boolean? = null,
    val left: Int = 0, val before: Int = 0, val after: Int = 0, val action: String? = null)

internal class NativeTermCard(val card: TermCard, val pieces: List<NativeCardPiece>,
    val presentation: TermCardPresentation, val layout: NativeGlossaryLayoutContext,
    val documents: Map<List<StructuredNode>, NativeGlossaryDocument>, val profilingId: Long,
    val appearance: DictionaryRenderAppearance, val index: Int) {
    val initiallyOpen = card.dictGroups.indices.filter { presentation.groups[it].initiallyExpanded }.toSet()
    /* Only the visible card is ever shown, so flattening every card's pieces during plan assembly
       is wasted work; build it on first use instead. */
    val initial: NativeGlossaryDocument by lazy { assemble(documents, initiallyOpen, false) }

    fun visible(piece: NativeCardPiece, open: Set<Int>, details: Boolean): Boolean = when {
        piece.action == "process-details" -> details
        piece.action == "process" -> piece.expanded == details
        piece.group < 0 || piece.expanded == null -> true
        else -> (piece.group in open) == piece.expanded
    }

    fun assemble(ready: Map<List<StructuredNode>, NativeGlossaryDocument>, open: Set<Int>, details: Boolean,
        selected: String? = null): NativeGlossaryDocument {
        val fragments = pieces.filter { visible(it, open, details) }.map { piece ->
            val document = checkNotNull(ready[piece.nodes])
            val action = piece.action?.let { name -> StructuredNode.Element(StructuredTag.Summary,
                listOf(StructuredNode.Text(if (piece.group >= 0) card.dictGroups[piece.group].title else card.expression)),
                StructuredAttributes(properties = mapOf("cardAction" to name))) }
            val highlighted = piece.action?.startsWith("group:") == true && card.dictGroups[piece.group].title == selected
            NativeCardFragment(document, piece.dictionary, piece.left, piece.before, piece.after, action,
                headword = if (piece === pieces.first()) card.expression else null,
                background = if (highlighted) appearance.hover.toArgb() else 0,
                stripe = if (highlighted) appearance.accent.toArgb() else 0,
                tint = if (highlighted) appearance.accent.toArgb() else null)
        }
        return placeNativeCard(fragments, BooleanArray(fragments.size) { true },
            (layout.viewportWidth - 16 * layout.density.density).roundToInt().coerceAtLeast(1), profilingId,
            separator = if (index > 0) layout.border.toArgb() else 0)
    }
}

private fun Color.css(): String = "#" + Integer.toUnsignedString(Integer.rotateLeft(toArgb(), 8), 16).padStart(8, '0')

/** Lookup-owned chrome rules. Repeated pieces carry classes, not fresh inline CSS. */
internal class NativeCardTheme(appearance: DictionaryRenderAppearance) {
    private val foreground: String = appearance.onBg.css()
    private val secondary: String = appearance.secondary.css()
    private val accent: String = appearance.accent.css()
    val pitchColor: String = accent.take(7)
    val css: ParsedCss = ParsedCss("body{margin:0;line-height:1.4;color:$foreground}rt{font-size:55%;line-height:1}" +
        ".chip{font-size:.75em;background:${appearance.secondary.copy(alpha = .18f).css()};border-radius:4px;padding:2px 5px;margin-right:3px}" +
        ".card-head{font-size:1.6em;font-weight:bold;white-space:nowrap;color:$foreground}" +
        ".card-popular{color:$accent}.card-rare{color:${appearance.secondary.copy(alpha = .7f).css()}}" +
        ".card-process{font-size:.8em;color:$secondary}" +
        ".card-process-step{padding:7px 10px;margin-bottom:6px;background:${appearance.hover.css()};border:1px solid ${appearance.border.css()};border-radius:8px;font-size:.88em}" +
        ".card-pitch-name{font-size:10px;color:$secondary}.card-pitch-number{font-size:12px;color:$accent}" +
        ".card-pitch-mora{font-size:11px;border-top:0}.card-pitch-high{border-top:1px solid $foreground}" +
        ".card-pitch-drop{border-right:1px solid $foreground}" +
        ".card-group{font-size:.94em;font-weight:600;color:$secondary;padding:4px 0 4px 12px}")
}

private val cardTagWhitespace = Regex("\\s+")
private fun NativeCardBuilder.chip(value: String) { element("span", "chip") { text(value) } }

internal fun nativeCardPieces(card: TermCard, presentation: TermCardPresentation,
    layout: NativeGlossaryLayoutContext, theme: NativeCardTheme,
    options: NativeCardOptions): List<NativeCardPiece> {
    val parts = ArrayList<NativeCardPiece>()
    val scale = layout.density.density
    fun px(dp: Int) = (dp * scale).roundToInt()
    val width = (layout.viewportWidth - px(16)).coerceAtLeast(1)
    val css = theme.css
    fun add(group: Int = -1, expanded: Boolean? = null, left: Int = 0,
        before: Int = 0, after: Int = 0, action: String? = null, assignedWidth: Int = width,
        content: NativeCardBuilder.() -> Unit) {
        parts.add(NativeCardPiece(nativeCardSource(content), css, assignedWidth, group = group, expanded = expanded,
            left = px(left), before = px(before), after = px(after), action = action))
    }
    fun addSource(nodes: List<StructuredNode>, group: Int = -1, expanded: Boolean? = null, left: Int = 0,
        before: Int = 0, after: Int = 0, action: String? = null, assignedWidth: Int = width) {
        parts.add(NativeCardPiece(nodes, css, assignedWidth, group = group,
            expanded = expanded, left = px(left), before = px(before), after = px(after), action = action))
    }
    val tags = card.termTags.split(cardTagWhitespace).filter { it.isNotBlank() }.distinct()
    val headClass = when {
        tags.any { it.contains("popular", true) } -> "card-head card-popular"
        tags.any { it.contains("rare", true) || it.contains("arch", true) || it.contains("obs", true) } -> "card-head card-rare"
        else -> "card-head"
    }
    addSource(headwordCardSource(card.expression, card.reading, headClass),
        before = 10, after = 2, assignedWidth = (width - px(84)).coerceAtLeast(1))
    if (tags.isNotEmpty()) add(before = 4) { element("div") { tags.take(8).forEach { chip(it) } } }
    if (card.process.isNotEmpty() && card.process.map { it.name } != listOf(card.expression)) {
        val summary = card.process.asReversed().joinToString(" \u00bb ") { it.name }
        for (expanded in listOf(false, true)) add(before = 4, action = "process", expanded = expanded) {
            element("div", "card-process") { text(summary + if (expanded) " \u25be" else " \u25b8") }
        }
        val rules = card.rules.split(cardTagWhitespace).filter { it.isNotBlank() }.distinct()
        add(before = 2, action = "process-details") {
            element("div") {
                if (rules.isNotEmpty()) element("div") { rules.forEach { chip(it) } }
                card.process.asReversed().forEach { step ->
                    element("div", "card-process-step") { text("${step.name}: ${step.description}") }
                }
            }
        }
    }
    val frequencies = buildFrequencyChips(card.frequencies, options.harmonic, options.average)
    if (frequencies.isNotEmpty()) add(before = 4) {
        element("div") { frequencies.forEach { chip(listOfNotNull(it.label, it.body).joinToString(" ")) } }
    }
    if (options.pitchDiagram || options.pitchNumber || options.pitchText) {
        val reading = card.reading.ifBlank { card.expression }
        val morae = if (reading.isNotBlank()) DictionaryCore.splitMorae(reading).toList() else emptyList()
        val pitches = if (options.groupPitches) listOf(card.pitches.joinToString(", ") { it.dictName } to
            card.pitches.flatMap { it.pitchPositions.toList() }.distinct()) else card.pitches.map { it.dictName to it.pitchPositions.toList().distinct() }
        for ((name, positions) in pitches) for (position in positions) {
            val high = DictionaryCore.moraHighFlags(reading, position)
            add(before = 2) {
                element("div") {
                    element("span", "card-pitch-name") { text(name + " ") }
                    if (options.pitchDiagram && morae.isNotEmpty()) pitchImage(morae, high, theme.pitchColor)
                    if (options.pitchText) morae.forEachIndexed { i, mora ->
                        element("span", "card-pitch-mora" + (if (high[i]) " card-pitch-high" else "") +
                            (if (high[i] && !high[i + 1]) " card-pitch-drop" else "")) { text(mora) }
                    }
                    if (options.pitchNumber) element("span", "card-pitch-number") { text(" [$position]") }
                }
            }
        }
    }
    card.dictGroups.forEachIndexed { groupIndex, group ->
        for (expanded in listOf(true, false)) add(group = groupIndex, expanded = expanded,
            before = if (groupIndex == 0) 2 else 4, action = "group:$groupIndex") {
            element("div", "card-group") { text((if (expanded) "\u25be  " else "\u25b8  ") + group.title) }
        }
        group.glosses.forEachIndexed { glossIndex, _ ->
            val defTags = presentation.groups[groupIndex].definitionTags[glossIndex]
            val left = if (group.glosses.size == 1) 0 else 16
            if (defTags.isNotEmpty()) add(group = groupIndex, expanded = true, left = left, before = 3, after = 2,
                assignedWidth = layout.glossaryWidth(left != 0)) { element("div") { defTags.forEach { chip(it) } } }
            parts.add(NativeCardPiece(group.parsedGlosses[glossIndex], presentation.groups[groupIndex].css,
                layout.glossaryWidth(left != 0), group.dictName, groupIndex, true, px(left),
                if (defTags.isEmpty()) px(3) else 0, px(3)))
        }
    }
    return parts
}

private fun NativeCardBuilder.pitchImage(morae: List<String>, high: BooleanArray, color: String) {
    val width = morae.size * 18
    val points = morae.indices.map { i -> "${i * 18 + 9},${if (high[i]) 7.48 else 19.72}" }
    val svg = "<svg xmlns='http://www.w3.org/2000/svg' width='$width' height='34'><g fill='$color' stroke='$color' stroke-width='1.19'>" +
        "<polyline fill='none' points='${points.joinToString(" ")}'/>" + points.joinToString("") { point ->
            val (x, y) = point.split(','); "<path opacity='.55' d='M$x 26.52 L$x $y'/><circle cx='$x' cy='$y' r='1.9'/>"
        } + "</g></svg>"
    element("img") {
        property("path", "data:image/svg+xml;base64," + Base64.encodeToString(svg.toByteArray(), Base64.NO_WRAP))
        property("width", width.toString()); property("height", "34"); property("sizeUnits", "px")
        text("")
    }
}

@Composable
internal fun NativeTermCardContent(model: NativeTermCard, index: Int, profile: AnkiProfile,
    onAnkiLookup: ((Int, Int?, String?, String?, Boolean) -> Unit)?,
    onRecursiveLookup: ((String, String?, Int?, Float?, Float?, String?) -> Unit)?,
    audioEnabled: Boolean, autoplay: Boolean, accent: Color, onBg: Color) {
    var open by remember(model) { mutableStateOf(model.initiallyOpen) }
    var processDetails by remember(model) { mutableStateOf(false) }
    var selected by remember(model) { mutableStateOf<String?>(null) }
    var document by remember(model) { mutableStateOf(model.initial) }
    var ready by remember(model) { mutableStateOf(model.documents) }
    LaunchedEffect(model, open, processDetails, selected) {
        if (open == model.initiallyOpen && !processDetails && selected == null && document === model.initial) return@LaunchedEffect
        val snapshot = ready
        val wantedOpen = open
        val wantedDetails = processDetails
        val wantedSelection = selected
        val result = withContext(glossaryPreparationDispatcher) {
            val caller = currentCoroutineContext()
            val preparedDocuments = IdentityHashMap<List<StructuredNode>, NativeGlossaryDocument>().apply { putAll(snapshot) }
            val missing = model.pieces.filter { model.visible(it, wantedOpen, wantedDetails) && !preparedDocuments.containsKey(it.nodes) }
            if (missing.isNotEmpty()) NativeCoreCompiler(model.layout.density, model.layout.direction, model.layout.resolver,
                model.layout.secondary, model.layout.border).use { core ->
                val prepared = core.prepareBatch(missing.map { NativeDrawingInput(nativeCoreRaw(it.nodes), it.css.nativeSourceCss,
                    it.width, it.nodes, it.css) }, model.layout.style, dark = onBg.luminance() > .5f,
                    checkCancellation = { caller.ensureActive() })
                missing.forEachIndexed { i, part -> preparedDocuments[part.nodes] = prepared[i] }
            }
            preparedDocuments to model.assemble(preparedDocuments, wantedOpen, wantedDetails, wantedSelection)
        }
        ready = result.first
        document = result.second
    }
    val sink = rememberUpdatedState(onRecursiveLookup)
    val lookup = remember(model) { { text: String -> sink.value?.invoke(text, null, null, null, null, "term"); Unit } }
    Box(Modifier.fillMaxWidth()) {
        NativeDocumentContent(document, model.card.expression, lookup, onAction = { node ->
            val action = node.attributes.properties["cardAction"].orEmpty()
            if (action == "process") processDetails = !processDetails
            else if (action.startsWith("group:")) {
                val group = action.substringAfter(':').toInt()
                open = if (group in open) open - group else open + group
            }
        }, onLongAction = { node ->
            node.attributes.properties["cardAction"]?.removePrefix("group:")?.toIntOrNull()?.let { group ->
                val title = model.card.dictGroups[group].title
                selected = if (selected == title) null else title
            }
        }, onKanjiLookup = { text -> sink.value?.invoke(text, null, null, null, null, "kanji") },
            onRecursiveLookup = { hit -> hit.lookup?.let { text ->
                sink.value?.invoke(text, hit.sentence, hit.sentenceOffset, hit.tapX, hit.tapY, if (hit.kanji) "kanji" else "term")
            } })
        val headHeight = with(model.layout.density) { model.documents.getValue(model.pieces.first().nodes).height.toDp() }
        /* Chrome follows the drawing by one frame: the first composition of a card then only builds
           the native view, which is what the popup must show; the audio and Anki controls arrive on
           the next frame instead of delaying the first draw. */
        var chrome by remember(model) { mutableStateOf(false) }
        LaunchedEffect(model) { androidx.compose.runtime.withFrameNanos { }; chrome = true }
        if (chrome) Row(Modifier.align(Alignment.TopEnd).offset(y = 10.dp + (headHeight - 28.dp) / 2)) {
            if (audioEnabled) WordAudioButton(model.card.expression, model.card.reading, accent, onBg, autoplay, Modifier.size(28.dp))
            if (onAnkiLookup != null) TermCardAnkiActions(index, model.card.expression,
                model.card.dictGroups.firstOrNull()?.title, selected, profile.ankiDupAction, onAnkiLookup, onBg)
        }
    }
}
