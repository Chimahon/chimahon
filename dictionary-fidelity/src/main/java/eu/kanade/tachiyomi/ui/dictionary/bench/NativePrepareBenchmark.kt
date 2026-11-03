package eu.kanade.tachiyomi.ui.dictionary.bench

import android.os.Build
import android.os.Debug
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import eu.kanade.tachiyomi.ui.dictionary.compose.NativePreparationStats
import eu.kanade.tachiyomi.ui.dictionary.compose.dictionaryBaseStyle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal fun nativePrepareBenchmark(context: android.content.Context, cases: JSONArray, fontFile: File,
    density: Float, iterations: Int = 40, warmups: Int = 10, drawOnly: Boolean = false,
    stageTimings: Boolean = true): String {
    val resolver = createFontFamilyResolver(context)
    val family = FontFamily(android.graphics.Typeface.createFromFile(fontFile))
    val scale = Density(density)
    val baseCss = context.assets.open("dictionary/base.css").bufferedReader().use { it.readText() }
    data class Input(val raw: String, val css: String, val style: TextStyle, val width: Int)
    val inputs = List(cases.length()) { i ->
        val c = cases.getJSONObject(i)
        Input(c.getString("glossary"), eu.kanade.tachiyomi.ui.dictionary.compose.nativeCoreStylesheet(baseCss, c.optString("css")),
            dictionaryBaseStyle(Color.Black, c.optInt("fontSize", 14), family), (c.getInt("width") * density).toInt())
    }
    val samples = ArrayList<Pair<Double, Double>>()
    val stages = ArrayList<eu.kanade.tachiyomi.ui.dictionary.compose.NativeDrawingTiming>()
    val grouped = inputs.groupBy { it.style }
    var lastItems = 0; var lastParagraphs = 0; var lastRuby = 0
    repeat(warmups + iterations) { pass ->
        val resources = NativePreparationStats()
        eu.kanade.tachiyomi.ui.dictionary.compose.NativeCoreCompiler(scale, LayoutDirection.Ltr, resolver, Color.Gray, Color.LightGray, resources).use { core ->
            grouped.forEach { (style, group) -> core.precompileBatch(group.map { it.css }, style) }
            val cpu = Debug.threadCpuTimeNanos(); val start = System.nanoTime()
            var timing = eu.kanade.tachiyomi.ui.dictionary.compose.NativeDrawingTiming()
            val documents = grouped.flatMap { (style, group) ->
                core.prepareBatch(group.map { eu.kanade.tachiyomi.ui.dictionary.compose.NativeDrawingInput(it.raw, it.css, it.width) }, style).also {
                    val next = core.lastTiming
                    timing = eu.kanade.tachiyomi.ui.dictionary.compose.NativeDrawingTiming(timing.inputMs + next.inputMs,
                        timing.measureMs + next.measureMs, timing.finishMs + next.finishMs, timing.transferMs + next.transferMs,
                        timing.releaseMs + next.releaseMs, timing.configurationMs + next.configurationMs)
                }
            }
            val wallMs = (System.nanoTime() - start) / 1e6
            val cpuMs = (Debug.threadCpuTimeNanos() - cpu) / 1e6
            if (drawOnly) return JSONObject().put("benchmark", "native-core-corpus-drawing")
                .put("corpusTextDrawing", nativeCorpusTextDrawBenchmark(documents)).toString(2)
            if (pass >= warmups) { samples.add(wallMs to cpuMs); stages.add(timing) }
            lastItems = documents.sumOf { it.items.size }; lastParagraphs = resources.paragraphs; lastRuby = resources.rubyPairs
        }
    }
    fun median(values: List<Double>) = values.sorted()[values.size / 2]
    return JSONObject().put("benchmark", "native-bulk-command-preparation")
        .put("disclaimer", "Fresh native JSON/CSS lowering, Android text shaping and native placement; no painting/popup; CSS compilation excluded")
        .put("documents", inputs.size).put("iterations", iterations).put("warmups", warmups)
        .put("medianWallMs", median(samples.map { it.first })).put("medianCpuMs", median(samples.map { it.second }))
        .put("minWallMs", samples.minOf { it.first }).put("paragraphs", lastParagraphs).put("rubyPairs", lastRuby).put("drawItems", lastItems)
        .put("medianNativeInputMs", median(stages.map { it.inputMs }))
        .put("medianPlatformMeasureMs", median(stages.map { it.measureMs }))
        .put("medianNativeFinishMs", median(stages.map { it.finishMs }))
        .put("medianOutputTransferMs", median(stages.map { it.transferMs }))
        .put("medianNativeReleaseMs", median(stages.map { it.releaseMs }))
        .put("medianBatchConfigurationMs", median(stages.map { it.configurationMs }))
        .put("workerBatches", grouped.size)
        .put("samples", JSONArray().apply { samples.forEach { (wall, cpu) -> put(JSONObject().put("wallMs", wall).put("cpuMs", cpu)) } }).toString(2)
}
