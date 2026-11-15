package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import chimahon.renderer.DictionaryCore
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.roundToInt

public data class NativeDrawingInput(val raw: String, val css: String, val width: Int,
    val identity: List<StructuredNode> = emptyList(), val cssIdentity: ParsedCss = ParsedCss.EMPTY)

public data class NativeDrawingTiming(val inputMs: Double = 0.0, val measureMs: Double = 0.0,
    val finishMs: Double = 0.0, val transferMs: Double = 0.0, val releaseMs: Double = 0.0,
    val configurationMs: Double = 0.0, val parseMs: Double = 0.0, val styleMs: Double = 0.0,
    val beginMs: Double = 0.0)

/** Native parse, shape, layout and retained glyphs. Android supplies fonts and a viewport only. */
public class NativeCoreCompiler(
    private val density: Density,
    private val direction: LayoutDirection,
    private val resolver: FontFamily.Resolver,
    private val secondary: Color,
    private val border: Color,
    private val resources: NativePreparationStats = NativePreparationStats(),
    private val profilingId: Long = 0L,
    private val planId: Long = 0L,
    private val workerId: Int = 0,
    private val fontFiles: List<Pair<String, Int>> = emptyList(),
    private val language: String = "ja",
) : Closeable {
    private data class BatchContext(val handle: Long, val sourceSheets: IntArray)
    private var batch: BatchContext? = null
    public var lastTiming: NativeDrawingTiming = NativeDrawingTiming()
        private set

    private fun context(css: List<String>, style: TextStyle, dark: Boolean, expanded: Boolean): BatchContext {
        batch?.let { return it }
        val sheets = ArrayList<String>(css.size)
        val indices = HashMap<String, Int>(css.size * 2)
        val sourceSheets = IntArray(css.size)
        for (i in css.indices) {
            val sheet = css[i]
            val index = indices[sheet]
            if (index == null) {
                indices[sheet] = sheets.size
                sourceSheets[i] = sheets.size
                sheets.add(sheet)
            } else sourceSheets[i] = index
        }
        val handle = DictionaryCore.openBatch(sheets.toTypedArray(), style.fontSize.value * density.fontScale, dark, expanded, language)
        check(handle != 0L)
        return BatchContext(handle, sourceSheets).also { batch = it }
    }


    public fun prepare(raw: String, cssText: String, style: TextStyle, width: Int,
        dark: Boolean = false, expanded: Boolean = false, identity: List<StructuredNode> = emptyList(),
        cssIdentity: ParsedCss = ParsedCss.EMPTY, checkCancellation: () -> Unit = {}): NativeGlossaryDocument =
        prepareBatch(listOf(NativeDrawingInput(raw, cssText, width, identity, cssIdentity)), style, dark, expanded, checkCancellation).single()

    public fun prepareBatch(inputs: List<NativeDrawingInput>, style: TextStyle, dark: Boolean = false,
        expanded: Boolean = false, checkCancellation: () -> Unit = {}): List<NativeGlossaryDocument> {
        if (inputs.isEmpty()) return emptyList()
        checkCancellation()
        val trace = NativeMeasurementTrace(profilingId, planId, workerId)
        val configuration = System.nanoTime()
        val context = context(inputs.map { it.css }, style, dark, expanded)
        trace.checkpoint("configuration")
        val start = System.nanoTime()
        if (profilingId != 0L) DictionaryCore.profileBegin(profilingId * 1000 + workerId)
        val handle = DictionaryCore.prepareDrawing(context.handle, inputs.map { it.raw }.toTypedArray(),
            Array(inputs.size) { nativeCardTape(inputs[it].identity) }, context.sourceSheets,
            FloatArray(inputs.size) { inputs[it].width / density.density }, style.color.toArgb(), border.toArgb())
        check(handle != 0L)
        val nativeTimings = DictionaryCore.drawingTimings(handle)
        trace.checkpoint("nativeInput")
        var owner: NativePortableDrawing? = null
        var transferred = false
        try {
            val prepared = System.nanoTime()
            DictionaryCore.measurePortable(handle, density.density,
                fontFiles.map { it.first }.toTypedArray(), IntArray(fontFiles.size) { fontFiles[it].second })
            trace.checkpoint("portableMeasure")
            val measured = System.nanoTime()
            checkCancellation()
            DictionaryCore.finishDrawing(handle)
            trace.checkpoint("nativeFinish")
            val finished = System.nanoTime()
            val counts = DictionaryCore.drawingCounts(handle)
            fun section(index: Int): ByteBuffer = DictionaryCore.drawingSection(handle, index).order(ByteOrder.nativeOrder())
            fun copied(index: Int, bytes: Int) = ByteBuffer.allocate(bytes).order(ByteOrder.nativeOrder()).apply {
                put(section(index)); rewind()
            }
            // Commands are only needed to place image nodes; hits, selection and copy text stay native.
            val commands = if (counts[8] == 0) null else copied(9, counts[5] * 64)
            val geometry = section(11)
            val imageNodes = if (counts[8] == 0) emptyList() else {
                val pool = ByteArray(counts[10]).also { section(12).get(it) }
                val properties = section(13)
                val images = section(14)
                fun text(offset: Int, count: Int) = String(pool, offset, count, Charsets.UTF_8)
                List(counts[8]) { image ->
                    val first = images.getInt(image * 12 + 4)
                    val count = images.getInt(image * 12 + 8)
                    StructuredNode.Element(StructuredTag.Image, attributes = StructuredAttributes(properties = buildMap {
                        repeat(count) { n -> val at = (first + n) * 16
                            put(text(properties.getInt(at), properties.getInt(at + 4)), text(properties.getInt(at + 8), properties.getInt(at + 12))) }
                    }))
                }
            }
            resources.paragraphs += counts[11]
            resources.rubyPairs += counts[12]
            resources.tables += counts[13]
            val drawing = NativePortableDrawing(handle).also { owner = it }
            val documents = inputs.mapIndexed { source, input ->
                checkCancellation()
                val at = source * 24
                val height = ceil(geometry.getFloat(at + 4) * density.density).toInt().coerceAtLeast(1)
                val first = geometry.getInt(at + 8)
                val count = geometry.getInt(at + 12)
                val surface = NativeCommandSurface(drawing, source, density.density, input.width, height)
                val items = ArrayList<NativeGlossaryItem>()
                items.add(surface)
                if (commands != null) {
                    for (i in first until first + count) {
                        val c = i * 64
                        if (commands.getInt(c) == 2) items.add(NativeGlossaryImage(
                            (commands.getFloat(c + 24) * density.density).roundToInt(), (commands.getFloat(c + 28) * density.density).roundToInt(),
                            (commands.getFloat(c + 32) * density.density).roundToInt().coerceAtLeast(1),
                            (commands.getFloat(c + 36) * density.density).roundToInt().coerceAtLeast(1), imageNodes[commands.getInt(c + 8)]))
                    }
                }
                NativeGlossaryDocument(input.identity, input.cssIdentity, style, emptyList(), density, direction, resolver,
                    secondary, border, input.width, height, items, profilingId, planId)
            }
            lastTiming = NativeDrawingTiming((prepared - start) / 1e6, (measured - prepared) / 1e6,
                (finished - measured) / 1e6, (System.nanoTime() - finished) / 1e6,
                configurationMs = (start - configuration) / 1e6,
                parseMs = nativeTimings[0].toDouble(), styleMs = nativeTimings[1].toDouble(),
                beginMs = nativeTimings[2].toDouble())
            transferred = true
            trace.checkpoint("transfer")
            if (trace.enabled) trace.report("backend=portable-cpp retained=true sources=${inputs.size} utf16=${counts[2]} requests=${counts[3]} shapingGroups=${counts[15]} paragraphs=${counts[11]} rubyPairs=${counts[12]} platformShapeCalls=0")
            return documents
        } finally {
            if (profilingId != 0L) DictionaryCore.profileEnd()
            if (!transferred) {
                val drawing = owner
                if (drawing != null) drawing.close() else DictionaryCore.closeDrawing(handle)
            }
        }
    }

    override fun close() { batch?.let { DictionaryCore.closeContext(it.handle) }; batch = null }
}

public fun nativeCoreSource(raw: String): List<StructuredNode> = listOf(
    StructuredNode.Element(StructuredTag.Unknown, attributes = StructuredAttributes(properties = mapOf("nativeSource" to raw))))

public fun nativeCoreRaw(nodes: List<StructuredNode>): String = if (nativeCardInput(nodes) != null) "" else
    requireNotNull((nodes.singleOrNull() as? StructuredNode.Element)?.attributes?.properties?.get("nativeSource")) { "Native source identity missing" }

public fun nativeCoreSourceSize(nodes: List<StructuredNode>): Int =
    nativeCardInput(nodes)?.sourceWeight ?: nativeCoreRaw(nodes).length

/** Text characters only; the per-document scheduling weight uses this instead of the DOM size. */
public fun nativeCoreTextSize(nodes: List<StructuredNode>): Int =
    nativeCardInput(nodes)?.textUnits ?: nativeCoreSourceSize(nodes)

public fun nativeCoreStylesheet(base: String, dictionary: String, variables: Map<String, String> = emptyMap()): String = buildString {
    append(base); append("\n:root{")
    variables.forEach { (name, value) -> append(if (name.startsWith("--")) name else "--$name"); append(':'); append(value); append(';') }
    append("}\n"); append(dictionary)
}
