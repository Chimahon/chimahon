package eu.kanade.tachiyomi.ui.dictionary.bench

import android.os.Build
import android.os.Debug
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeGlossaryCompiler
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeGlossaryResources
import eu.kanade.tachiyomi.ui.dictionary.compose.StructuredEntry
import eu.kanade.tachiyomi.ui.dictionary.compose.StructuredNode
import eu.kanade.tachiyomi.ui.dictionary.compose.dictionaryBaseStyle
import eu.kanade.tachiyomi.ui.dictionary.compose.parseDictionaryCss
import eu.kanade.tachiyomi.ui.dictionary.compose.parseStructuredGlossary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Preparation benchmark for native-flow-v1, run on the emulator with no perfetto tracing attached.
 *
 * Complements phone traces with isolated, repeated Android preparation. A desktop CSS-only
 * measurement is not comparable to whole-document lowering, and this benchmark does not measure
 * popup composition, drawing, repository latency or the production device's cold execution.
 *
 * Mirrors the app's worker-shared resources: one [NativeGlossaryResources] per worker, one
 * document prepared at a time, exactly as `prepareGlossaryBatch` does.
 */
internal fun nativePrepareBenchmark(
    context: android.content.Context,
    cases: JSONArray,
    fontFile: File,
    density: Float,
    iterations: Int = 40,
    warmups: Int = 10,
    drawOnly: Boolean = false,
    stageTimings: Boolean = true,
): String {
    val resolver = createFontFamilyResolver(context)
    val family = FontFamily(android.graphics.Typeface.createFromFile(fontFile))
    val direction = LayoutDirection.Ltr

    data class Case(
        val id: String,
        val nodes: List<StructuredNode>,
        val css: String,
        val width: Int,
        val fontSize: Int,
        val glossaryJson: String,
    )

    val prepared = ArrayList<Case>(cases.length())
    val stylesheets = HashMap<String, eu.kanade.tachiyomi.ui.dictionary.compose.ParsedCss>()
    for (i in 0 until cases.length()) {
        val c = cases.getJSONObject(i)
        val raw = c.getString("glossary")
        val nodes = when (val parsed = parseStructuredGlossary(raw)) {
            is StructuredEntry.Tree -> parsed.nodes
            StructuredEntry.PlainText -> listOf(StructuredNode.Text(raw))
        }
        prepared += Case(c.getString("id"), nodes, c.optString("css", ""),
            c.getInt("width"), c.optInt("fontSize", 14), raw)
    }

    // Stylesheet parsing is done ONCE, exactly as the app does it: `plan.css` compiles the
    // dictionary stylesheets and every document then shares the result. Parsing inside the timed
    // loop would re-parse 75 full stylesheets per iteration and swamp the measurement.
    val cssStart = System.nanoTime()
    for (c in prepared) {
        stylesheets.getOrPut(c.css) { parseDictionaryCss(c.css) }
    }
    val cssCompileMs = (System.nanoTime() - cssStart) / 1e6

    // The real `plan.css` path, measured cold and warm. The parsed stylesheet is cached, but the
    // cache key is rebuilt on every call and the merged ParsedCss is rebuilt per dictionary, so
    // "cached" does not mean "free".
    val themeVars = mapOf("--bg" to "#ffffff", "--fg" to "#1f1f1f")
    val styles = prepared.map { chimahon.DictionaryStyle(it.id, it.css) }
    val warmCssStart = System.nanoTime()
        repeat(5) { for (s in styles) eu.kanade.tachiyomi.ui.dictionary.compose.parseDictionaryCss(s.styles, themeVars) }
    val warmCssMs = (System.nanoTime() - warmCssStart) / 1e6 / 5
    // A unique theme variable forces a cache miss, so this is the cold parse of every stylesheet.
    val coldCssStart = System.nanoTime()
        for (s in styles) eu.kanade.tachiyomi.ui.dictionary.compose.parseDictionaryCss(
            s.styles, themeVars + ("__bench_unique" to System.nanoTime().toString()))
    val coldCssMs = (System.nanoTime() - coldCssStart) / 1e6

    // Isolated: the cost of a single CACHE HIT, i.e. parseDictionaryCss with everything already
    // parsed. This is the per-lookup cost the cache is supposed to make free.
    val hitStart = System.nanoTime()
    val hits = 50
    repeat(hits) { eu.kanade.tachiyomi.ui.dictionary.compose.parseDictionaryCss(prepared[0].css, themeVars) }
    val cssHitMs = (System.nanoTime() - hitStart) / 1e6 / hits

    if (drawOnly) {
        val resources = NativeGlossaryResources(Density(density), direction, resolver)
        val documents = prepared.map { c ->
            NativeGlossaryCompiler(stylesheets.getValue(c.css), Density(density), direction,
                resolver, Color.Gray, Color.LightGray, resources = resources).prepare(c.nodes,
                dictionaryBaseStyle(Color.Black, c.fontSize, family), emptyList(),
                (c.width * density).toInt().coerceAtLeast(1))
        }
        return nativePlainTextDrawBenchmark(documents)
    }

    fun runOnce(perWorker: Boolean): BenchmarkSample {
        val resources = NativeGlossaryResources(Density(density), direction, resolver, collectStageTimings = stageTimings)
        val cpuStart = Debug.threadCpuTimeNanos()
        val wallStart = System.nanoTime()
        var parseNs = 0L
        for (c in prepared) {
            // Fresh nodes per iteration, like a real lookup: the app parses the glossary JSON
            // for every lookup, so that cost belongs in the measurement - but it is measured
            // separately so it cannot be confused with preparation.
            val parseStart = System.nanoTime()
            val raw = c.glossaryJson
            val nodes = when (val parsed = parseStructuredGlossary(raw)) {
                is StructuredEntry.Tree -> parsed.nodes
                StructuredEntry.PlainText -> listOf(StructuredNode.Text(raw))
            }
            parseNs += System.nanoTime() - parseStart
            // A fresh compiler per document, exactly like the app: the compiler owns the traversal
            // and the resources own the font/paint caches.
            NativeGlossaryCompiler(
                stylesheets.getValue(c.css), Density(density), direction, resolver, Color.Gray, Color.LightGray,
                resources = resources,
            ).prepare(
                nodes,
                dictionaryBaseStyle(Color.Black, c.fontSize, family),
                emptyList(),
                (c.width * density).toInt().coerceAtLeast(1),
            )
        }
        val wall = (System.nanoTime() - wallStart) / 1e6
        val cpu = Debug.threadCpuTimeNanos()
        return BenchmarkSample(
            wallMs = wall,
            cpuMs = if (cpu >= cpuStart) (cpu - cpuStart) / 1e6 else -1.0,
            parseMs = parseNs / 1e6,
            elements = resources.elements,
            cssMatches = resources.cssMatches,
            paragraphs = resources.paragraphs,
            singleLineParagraphs = resources.singleLineParagraphs,
            plainTextParagraphs = resources.plainTextParagraphs,
            rubyPairs = resources.rubyPairs,
            tables = resources.tables,
            fontProfiles = Regex("fontProfiles=(\\d+)").find(resources.summary())?.groupValues?.get(1)?.toIntOrNull() ?: 0,
            lineLayoutMs = resources.lineLayoutNanos / 1e6,
            paragraphFinishMs = resources.paragraphFinishNanos / 1e6,
            paragraphGeometryMs = resources.paragraphGeometryNanos / 1e6,
            decorationMs = resources.decorationNanos / 1e6,
            rubyPlacementMs = resources.rubyPlacementNanos / 1e6,
            ancestorMs = resources.ancestorNanos / 1e6,
            rubyLowerMs = resources.rubyLowerNanos / 1e6,
            tableMs = resources.tableNanos / 1e6,
            typographyMisses = resources.typographyMisses,
            boxMisses = resources.boxMisses,
            profileMisses = resources.profileMisses,
            declarationsMs = resources.declarationsNanos / 1e6,
            resourceMs = resources.resourceNanos / 1e6,
            appends = resources.appends,
            summary = resources.summary(),
        )
    }

    repeat(warmups) { runOnce(true) }
    val samples = ArrayList<BenchmarkSample>(iterations)
    repeat(iterations) { samples += runOnce(true) }

    fun median(values: List<Double>): Double {
        val usable = values.filter { it >= 0.0 }.sorted()
        return if (usable.isEmpty()) -1.0 else usable[usable.size / 2]
    }

    val last = samples.last()
    val medianWall = median(samples.map { it.wallMs })
    val medianCpu = median(samples.map { it.cpuMs })
    val medianLine = median(samples.map { it.lineLayoutMs })
    val medianParse = median(samples.map { it.parseMs })
    val own = medianWall - medianLine - medianParse
    // The emulator is noisy, so the minimum is the least-perturbed sample and the honest
    // figure to compare across code changes. Median alone moves by 38% between runs.
    val minWall = samples.map { it.wallMs }.min()
    val minLine = samples.map { it.lineLayoutMs }.min()
    val minParse = samples.map { it.parseMs }.min()
    val minOwn = minWall - minLine - minParse

    // Timer cost on this target. Stage counters are only worth reading when this is small.
    val timerStart = System.nanoTime()
    var timerSink = 0L
    repeat(10000) { timerSink += System.nanoTime() }
    val nanoTimeNs = (System.nanoTime() - timerStart) / 10000.0

    return JSONObject().apply {
        put("nanoTimeNs", nanoTimeNs)
        put("nanoTimeSink", timerSink)
        put("benchmark", "native-flow-v1-preparation")
        put("disclaimer", "Preparation only, no drawing and no perfetto tracing. Wall time for the whole corpus.")
        put("device", JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("sdk", Build.VERSION.SDK_INT)
            put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            put("density", density)
            put("cores", Runtime.getRuntime().availableProcessors())
        })
        put("documents", prepared.size)
        put("iterations", iterations)
        put("warmups", warmups)
        put("elements", last.elements)
        put("cssMatches", last.cssMatches)
        put("paragraphs", last.paragraphs)
        put("singleLineParagraphs", last.singleLineParagraphs)
        put("plainTextParagraphs", last.plainTextParagraphs)
        put("staticParagraphs", last.paragraphs - last.singleLineParagraphs)
        put("rubyPairs", last.rubyPairs)
        put("tables", last.tables)
        put("fontProfiles", last.fontProfiles)
        put("typographyMisses", last.typographyMisses)
        put("boxMisses", last.boxMisses)
        put("profileMisses", last.profileMisses)
        put("missesPerElement", if (last.elements > 0)
            (last.typographyMisses + last.boxMisses + last.profileMisses).toDouble() / last.elements else -1.0)
        put("declarationsMs", last.declarationsMs)
        put("resourceMs", last.resourceMs)
        put("appends", last.appends)
        put("appendsPerElement", if (last.elements > 0) last.appends.toDouble() / last.elements else -1.0)
        put("declarationsUsPerElement", if (last.elements > 0) last.declarationsMs * 1000.0 / last.elements else -1.0)
        put("resourceUsPerElement", if (last.elements > 0) last.resourceMs * 1000.0 / last.elements else -1.0)
        put("unaccountedUsPerElement", if (last.elements > 0)
            (own - median(samples.map { it.declarationsMs }) - median(samples.map { it.resourceMs })) * 1000.0 / last.elements else -1.0)
        put("cssCompileMsOnce", cssCompileMs)
        put("planCssColdMs", coldCssMs)
        put("planCssWarmMs", warmCssMs)
        put("cssCacheHitMs", cssHitMs)
        put("medianWallMs", medianWall)
        put("medianCpuMs", medianCpu)
        put("medianGlossaryParseMs", medianParse)
        put("medianLineLayoutMs", medianLine)
        put("medianParagraphFinishMs", median(samples.map { it.paragraphFinishMs }))
        put("medianParagraphGeometryMs", median(samples.map { it.paragraphGeometryMs }))
        put("medianDecorationMs", median(samples.map { it.decorationMs }))
        put("medianRubyPlacementMs", median(samples.map { it.rubyPlacementMs }))
        put("medianAncestorMs", median(samples.map { it.ancestorMs }))
        put("medianRubyLowerMs", median(samples.map { it.rubyLowerMs }))
        put("medianTableMs", median(samples.map { it.tableMs }))
        put("medianOwnMs", own)
        put("minWallMs", minWall)
        put("minLineLayoutMs", minLine)
        put("minGlossaryParseMs", minParse)
        put("minOwnMs", minOwn)
        put("usPerElement", if (last.elements > 0) own * 1000.0 / last.elements else -1.0)
        put("usPerElementMin", if (last.elements > 0) minOwn * 1000.0 / last.elements else -1.0)
        put("usPerElementWall", if (last.elements > 0) medianWall * 1000.0 / last.elements else -1.0)
        put("msPerParagraph", if (last.paragraphs > 0) medianWall / last.paragraphs else -1.0)
        put("msPerDocument", if (prepared.isNotEmpty()) medianWall / prepared.size else -1.0)
        put("summary", last.summary)
    }.toString(2)
}

private class BenchmarkSample(
    val wallMs: Double,
    val cpuMs: Double,
    val parseMs: Double,
    val elements: Int,
    val cssMatches: Int,
    val paragraphs: Int,
    val singleLineParagraphs: Int,
    val plainTextParagraphs: Int,
    val rubyPairs: Int,
    val tables: Int,
    val fontProfiles: Int,
    val lineLayoutMs: Double,
    val paragraphFinishMs: Double,
    val paragraphGeometryMs: Double,
    val decorationMs: Double,
    val rubyPlacementMs: Double,
    val ancestorMs: Double,
    val rubyLowerMs: Double,
    val tableMs: Double,
    val typographyMisses: Int,
    val boxMisses: Int,
    val profileMisses: Int,
    val declarationsMs: Double,
    val resourceMs: Double,
    val appends: Int,
    val summary: String,
)
