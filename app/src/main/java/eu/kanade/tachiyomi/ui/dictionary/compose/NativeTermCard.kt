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
    val initial = assemble(documents, initiallyOpen, false)

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

private fun cardElement(tag: String, content: Any, style: String = ""): NativeCardElement =
    NativeCardElement(tag, content, if (style.isEmpty()) emptyMap() else mapOf("style" to style))

private fun cardSource(content: Any): List<StructuredNode> = nativeCardSource(content)

private fun Color.css(): String = "#%08x".format((toArgb().toLong() and 0xffffffffL).let { ((it shl 8) or (it ushr 24)) and 0xffffffffL })

internal fun nativeCardPieces(card: TermCard, presentation: TermCardPresentation,
    layout: NativeGlossaryLayoutContext, appearance: DictionaryRenderAppearance,
    options: NativeCardOptions, index: Int): List<NativeCardPiece> {
    val parts = ArrayList<NativeCardPiece>()
    val scale = layout.density.density
    fun px(dp: Int) = (dp * scale).roundToInt()
    val width = (layout.viewportWidth - px(16)).coerceAtLeast(1)
    val css = ParsedCss("body{margin:0;line-height:1.4;color:${appearance.onBg.css()}}rt{font-size:55%;line-height:1}" +
        ".chip{font-size:.75em;background:${appearance.secondary.copy(alpha = .18f).css()};border-radius:4px;padding:2px 5px;margin-right:3px}" )
    fun add(content: Any, group: Int = -1, expanded: Boolean? = null, left: Int = 0,
        before: Int = 0, after: Int = 0, action: String? = null, assignedWidth: Int = width) {
        parts.add(NativeCardPiece(cardSource(content), css, assignedWidth, group = group, expanded = expanded,
            left = px(left), before = px(before), after = px(after), action = action))
    }
    fun chip(text: String): NativeCardElement = NativeCardElement("span", text, mapOf("class" to "chip"))
    val head = ArrayList<Any>()
    for (unit in splitHeadwordUnits(card.expression, card.reading)) {
        val reading = unit.readingOrNull()
        val text = when (unit) { is HeadwordUnit.Kanji -> unit.char; is HeadwordUnit.Ruby -> unit.text; is HeadwordUnit.Text -> unit.text }
        head.add(if (reading == null) text else cardElement("ruby", listOf(text, cardElement("rt", reading))))
    }
    val tags = card.termTags.split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()
    val headColor = when {
        tags.any { it.contains("popular", true) } -> appearance.accent
        tags.any { it.contains("rare", true) || it.contains("arch", true) || it.contains("obs", true) } -> appearance.secondary.copy(alpha = .7f)
        else -> appearance.onBg
    }
    add(cardElement("div", head, "font-size:1.6em;font-weight:bold;white-space:nowrap;color:${headColor.css()}"),
        before = 10, after = 2, assignedWidth = (width - px(84)).coerceAtLeast(1))
    if (tags.isNotEmpty()) add(cardElement("div", tags.take(8).map(::chip)), before = 4)
    if (card.process.isNotEmpty() && card.process.map { it.name } != listOf(card.expression)) {
        val summary = card.process.asReversed().joinToString(" \u00bb ") { it.name }
        for (expanded in listOf(false, true)) add(cardElement("div", summary + if (expanded) " \u25be" else " \u25b8",
            "font-size:.8em;color:${appearance.secondary.css()}"), before = 4, action = "process", expanded = expanded)
        val details = ArrayList<Any>()
        val rules = card.rules.split(Regex("\\s+")).filter { it.isNotBlank() }.distinct()
        if (rules.isNotEmpty()) details.add(cardElement("div", rules.map(::chip)))
        card.process.asReversed().forEach { step -> details.add(cardElement("div", "${step.name}: ${step.description}",
            "padding:7px 10px;margin-bottom:6px;background:${appearance.hover.css()};border:1px solid ${appearance.border.css()};border-radius:8px;font-size:.88em")) }
        add(cardElement("div", details), before = 2, action = "process-details")
    }
    val frequencies = buildFrequencyChips(card.frequencies, options.harmonic, options.average)
    if (frequencies.isNotEmpty()) add(cardElement("div", frequencies.map { chip(listOfNotNull(it.label, it.body).joinToString(" ")) }), before = 4)
    if (options.pitchDiagram || options.pitchNumber || options.pitchText) {
        val reading = card.reading.ifBlank { card.expression }
        val pitches = if (options.groupPitches) listOf(card.pitches.joinToString(", ") { it.dictName } to
            card.pitches.flatMap { it.pitchPositions.toList() }.distinct()) else card.pitches.map { it.dictName to it.pitchPositions.toList().distinct() }
        for ((name, positions) in pitches) for (position in positions) {
            val row = arrayListOf<Any>(cardElement("span", name + " ", "font-size:10px;color:${appearance.secondary.css()}"))
            if (options.pitchDiagram && reading.isNotBlank()) row.add(pitchImage(reading, position, appearance.accent))
            if (options.pitchText) {
                splitMorae(reading).forEachIndexed { i, mora -> row.add(cardElement("span", mora,
                    "font-size:11px;border-top:${if (isMoraPitchHigh(i, position)) "1px solid ${appearance.onBg.css()}" else "0"};" +
                        if (isMoraPitchHigh(i, position) && !isMoraPitchHigh(i + 1, position)) "border-right:1px solid ${appearance.onBg.css()}" else "")) }
            }
            if (options.pitchNumber) row.add(cardElement("span", " [$position]", "font-size:12px;color:${appearance.accent.css()}"))
            add(cardElement("div", row), before = 2)
        }
    }
    card.dictGroups.forEachIndexed { groupIndex, group ->
        for (expanded in listOf(true, false)) add(cardElement("div", (if (expanded) "\u25be  " else "\u25b8  ") + group.title,
            "font-size:.94em;font-weight:600;color:${appearance.secondary.css()};padding:4px 0 4px 12px"),
            group = groupIndex, expanded = expanded, before = if (groupIndex == 0) 2 else 4, action = "group:$groupIndex")
        group.glosses.forEachIndexed { glossIndex, _ ->
            val defTags = presentation.groups[groupIndex].definitionTags[glossIndex]
            val left = if (group.glosses.size == 1) 0 else 16
            if (defTags.isNotEmpty()) add(cardElement("div", defTags.map(::chip)), group = groupIndex,
                expanded = true, left = left, before = 3, after = 2, assignedWidth = layout.glossaryWidth(left != 0))
            parts.add(NativeCardPiece(group.parsedGlosses[glossIndex], presentation.groups[groupIndex].css,
                layout.glossaryWidth(left != 0), group.dictName, groupIndex, true, px(left),
                if (defTags.isEmpty()) px(3) else 0, px(3)))
        }
    }
    return parts
}

private fun pitchImage(reading: String, position: Int, color: Color): NativeCardElement {
    val morae = splitMorae(reading)
    val width = morae.size * 18
    val points = morae.indices.map { i -> "${i * 18 + 9},${if (isMoraPitchHigh(i, position)) 7.48 else 19.72}" }
    val svg = "<svg xmlns='http://www.w3.org/2000/svg' width='$width' height='34'><g fill='${color.css().take(7)}' stroke='${color.css().take(7)}' stroke-width='1.19'>" +
        "<polyline fill='none' points='${points.joinToString(" ")}'/>" + points.joinToString("") { point ->
            val (x, y) = point.split(','); "<path opacity='.55' d='M$x 26.52 L$x $y'/><circle cx='$x' cy='$y' r='1.9'/>"
        } + "</g></svg>"
    return NativeCardElement("img", "", properties = mapOf(
        "path" to "data:image/svg+xml;base64," + Base64.encodeToString(svg.toByteArray(), Base64.NO_WRAP),
        "width" to width.toString(), "height" to "34", "sizeUnits" to "px"))
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
        }, onKanjiLookup = { text -> sink.value?.invoke(text, null, null, null, null, "kanji") })
        val headHeight = with(model.layout.density) { model.documents.getValue(model.pieces.first().nodes).height.toDp() }
        Row(Modifier.align(Alignment.TopEnd).offset(y = 10.dp + (headHeight - 28.dp) / 2)) {
            if (audioEnabled) WordAudioButton(model.card.expression, model.card.reading, accent, onBg, autoplay, Modifier.size(28.dp))
            if (onAnkiLookup != null) TermCardAnkiActions(index, model.card.expression,
                model.card.dictGroups.firstOrNull()?.title, selected, profile.ankiDupAction, onAnkiLookup, onBg)
        }
    }
}
