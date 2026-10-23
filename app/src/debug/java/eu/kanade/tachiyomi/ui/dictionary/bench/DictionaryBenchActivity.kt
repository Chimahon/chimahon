package eu.kanade.tachiyomi.ui.dictionary.bench

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chimahon.DictionaryStyle
import chimahon.GlossaryEntry
import chimahon.LookupResult
import chimahon.TermResult
import chimahon.TransformGroup
import chimahon.anki.AnkiProfile
import eu.kanade.tachiyomi.ui.dictionary.DictionaryEntryWebView
import eu.kanade.tachiyomi.ui.dictionary.compose.DictionaryEntryCompose
import eu.kanade.tachiyomi.ui.dictionary.compose.buildCards
import eu.kanade.tachiyomi.ui.dictionary.compose.buildParsedCssMap
import eu.kanade.tachiyomi.ui.dictionary.compose.parseDictionaryCss
import eu.kanade.tachiyomi.ui.dictionary.compose.parseStructuredGlossary
import eu.kanade.tachiyomi.ui.dictionary.compose.nativeGlossaryChecks
import eu.kanade.tachiyomi.ui.dictionary.compose.nativeSpanBenchmark
import eu.kanade.tachiyomi.ui.dictionary.orderLookupResultsForDisplay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.system.measureNanoTime

private const val TAG = "DictBench"

/** Cases timed per mode. Keeps a full run to roughly a minute. */
private const val DEFAULT_CASES_PER_MODE = 5

/** Repeats per case; the first is discarded as warm-up. */
private const val DEFAULT_ITERS = 5

/** A mount that has not signalled within this is counted as a failure, not a slow frame. */
private const val MOUNT_TIMEOUT_MS = 10_000L

private const val EXTRA_CASES = "cases"
private const val EXTRA_ITERS = "iters"
private const val EXTRA_MODES = "modes"
private const val EXTRA_STAGES = "stages"
private const val EXTRA_NATIVE_SPAN_BENCH = "native_span_bench_only"

private enum class BenchMode(val id: String) {
    WEBVIEW("webview"),
    COMPOSE("compose"),
    PLAIN_TEXT("plain_text"),
}

/**
 * Run shape, overridable from the launch intent.
 *
 * The defaults are the full sweep a human gets from the launcher icon. Macrobenchmark cannot use
 * that shape: it needs one short, repeatable Compose mount per iteration so the difference between
 * two `CompilationMode` arms is the only variable. `DictionaryRenderBenchmark` passes
 * `-e modes compose -e cases 1 -e iters 3 -e stages false` to get exactly that.
 */
private data class BenchConfig(
    val casesPerMode: Int,
    val iters: Int,
    val stages: Boolean,
    val modes: List<BenchMode>,
)

private fun parseConfig(intent: Intent?): BenchConfig {
    fun positive(key: String, fallback: Int) =
        intent?.getIntExtra(key, fallback)?.takeIf { it > 0 } ?: fallback
    return BenchConfig(
        casesPerMode = positive(EXTRA_CASES, DEFAULT_CASES_PER_MODE),
        iters = positive(EXTRA_ITERS, DEFAULT_ITERS),
        stages = intent?.getBooleanExtra(EXTRA_STAGES, true) ?: true,
        modes = intent?.getStringExtra(EXTRA_MODES)
            ?.split(',')
            ?.mapNotNull { name -> BenchMode.entries.firstOrNull { it.id == name.trim() } }
            ?.ifEmpty { null }
            ?: BenchMode.entries.toList(),
    )
}

private class BenchCase(
    val id: String,
    val dictName: String,
    val term: String,
    val glossary: String,
    val dictCss: String,
    val fontSize: Int,
    val styles: List<DictionaryStyle>,
    val result: LookupResult,
)

/**
 * One mount to measure. [key] changes every iteration so the renderer is torn down and rebuilt,
 * which is what a fresh lookup does. [cssSalt] makes the stylesheet text unique for this mount, so
 * the CSS cache misses and the mount pays a real parse - that is how the cold/warm difference is
 * measured end to end.
 */
private class BenchJob(
    val key: Int,
    val case: BenchCase,
    val mode: BenchMode,
    val shown: CompletableDeferred<Unit>,
    val cssSalt: String? = null,
)

/**
 * Debug-only harness that times all three dictionary renderers on a real device.
 *
 * "Slow" has two shapes here, so this measures both:
 *
 *  1. **Stage cost**, with no UI: stylesheet parse, structured-glossary parse, and the
 *     order + card build that `DictionaryEntryCompose` runs inside `produceState` on
 *     `Dispatchers.Default`. This is the part that scales with entry size and the cheapest to fix.
 *  2. **Time to visible content**: mount each renderer under a fresh key, then time from mount to
 *     the content being on screen. For the WebView that is its JS-ready signal; for the Compose
 *     paths it is layout followed by the next drawn frame. Compared across all three modes.
 *
 * Results stream to logcat under [TAG] and are shown on screen.
 */
class DictionaryBenchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = parseConfig(intent)
        // A run is a couple of minutes of mounting. If the screen sleeps, the window stops drawing,
        // nothing composes, and every remaining case silently times out.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val context = LocalContext.current
            val fontResolver = LocalFontFamilyResolver.current
            val report = remember { mutableStateListOf<String>() }
            var job by remember { mutableStateOf<BenchJob?>(null) }

            LaunchedEffect(context, config) {
                if (intent?.getBooleanExtra(EXTRA_NATIVE_SPAN_BENCH, false) == true) {
                    val json = withContext(Dispatchers.Default) {
                        runCatching {
                            val checks = nativeGlossaryChecks(fontResolver)
                            JSONObject(nativeSpanBenchmark(fontResolver)).put("nativeChecks", checks).toString(2)
                        }.getOrElse { error ->
                            JSONObject().apply {
                                put("benchmark", "native-span-microbenchmark")
                                put("error", error.message ?: error::class.java.name)
                            }.toString(2)
                        }
                    }
                    report += json
                    Log.i(TAG, json)
                    runCatching {
                        context.getExternalFilesDir(null)?.let { dir ->
                            val file = java.io.File(dir, "native-span-bench.json")
                            file.writeText(json)
                            Log.i(TAG, "wrote $file")
                        }
                    }
                    return@LaunchedEffect
                }
                val modes = config.modes.joinToString(",") { it.id }
                report += header()
                report += ""
                val cases = loadCases(context)
                report += "loaded ${cases.size} cases from assets/bench/cases.json"
                report += "modes=$modes cases=${config.casesPerMode} iters=${config.iters}"
                report += ""
                if (config.stages) {
                    val nativeChecks = withContext(Dispatchers.Default) { nativeGlossaryChecks(fontResolver) }
                    report += nativeChecks
                    Log.i(TAG, nativeChecks)
                    report += stagePass(context, cases, config)
                    report += ""
                }
                report += modePass(cases, config) { newJob -> job = newJob }
                Log.i(TAG, "\n── end ──")
                // logcat rolls during a long run, so the report is also written to a file the
                // caller can pull: adb pull /sdcard/Android/data/app.chimahon.dev/files/bench.txt
                runCatching {
                    context.getExternalFilesDir(null)?.let { dir ->
                        java.io.File(dir, "bench.txt").writeText(report.joinToString("\n"))
                        Log.i(TAG, "wrote ${java.io.File(dir, "bench.txt")}")
                    }
                }
            }

            // The renderer hosts its own LazyColumn, so it must get a bounded height. The report
            // scrolls inside its own capped box rather than wrapping the renderer.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF101014)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    val current = job
                    if (current != null) {
                        key(current.key) {
                            MountedRenderer(current)
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    report.forEach { line ->
                        Text(
                            text = line.ifEmpty { " " },
                            color = Color(0xFFE6E6EA),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

private fun header(): List<String> = listOf(
    "dictionary renderer benchmark",
    "device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}",
    "cpus=${Runtime.getRuntime().availableProcessors()} heap=${Runtime.getRuntime().maxMemory() / 1048576}MB",
)

private fun loadCases(context: Context): List<BenchCase> {
    val raw = context.assets.open("bench/cases.json").bufferedReader().readText()
    val arr = JSONArray(raw)
    // Sort by id so a run is reproducible; asset order is not something to depend on.
    return (0 until arr.length()).map { arr.getJSONObject(it) }
        .sortedBy { it.getString("id") }
        .mapNotNull { c ->
            runCatching {
                val id = c.getString("id")
                val dictName = c.getString("dictName")
                val cssName = c.optString("dictCss", "")
                val dictCss = if (cssName.isEmpty()) {
                    ""
                } else {
                    runCatching { context.assets.open("bench/$cssName").bufferedReader().readText() }.getOrDefault("")
                }
                val term = c.getString("term")
                val gloss = c.getString("glossary")
                val styles = listOf(DictionaryStyle(dictName = dictName, styles = dictCss))
                BenchCase(
                    id = id,
                    dictName = dictName,
                    term = term,
                    glossary = gloss,
                    dictCss = dictCss,
                    fontSize = c.optInt("fontSize", 14),
                    styles = styles,
                    result = LookupResult(
                        matched = term,
                        deinflected = term,
                        process = emptyArray<TransformGroup>(),
                        term = TermResult(
                            expression = term,
                            reading = "",
                            rules = "",
                            glossaries = arrayOf(
                                GlossaryEntry(dictName, gloss, "", ""),
                            ),
                            frequencies = emptyArray(),
                            pitches = emptyArray(),
                        ),
                        preprocessorSteps = 0,
                    ),
                )
            }.getOrNull()
        }
}

// ---------------------------------------------------------------------------------------------
// Pass 1 - stage cost, no UI
// ---------------------------------------------------------------------------------------------

private suspend fun stagePass(context: Context, cases: List<BenchCase>, config: BenchConfig): String = withContext(Dispatchers.Default) {
    val out = StringBuilder()
    val withCss = cases.firstOrNull { it.dictCss.isNotEmpty() }
    val biggest = cases.maxByOrNull { it.glossary.length }!!
    val profile = AnkiProfile(id = "bench", name = "bench")

    out.appendLine("--- stage cost (no ui, ${config.iters} iters after 2 warmup) ---")

    // Cold vs warm is the whole point of the stylesheet cache: the first parse of a dictionary's
    // CSS costs real time, every later one is a map hit. Report both so the saving is visible.
    val styled = cases.filter { it.dictCss.isNotEmpty() }.distinctBy { it.dictCss }
    if (styled.isEmpty()) {
        out.appendLine("  (no case carried a stylesheet)")
    }
    // Measure every cold parse first, before any of them is cached, so the total is a true cold
    // figure rather than a sum of cache hits.
    val coldTotalMs = styled.sumOf { measureNanoTime { parseDictionaryCss(it.dictCss) } } / 1_000_000.0
    for (c in styled) {
        val bytes = c.dictCss.length
        val warm = time("  ${c.id.take(24)} warm/cached", "$bytes chars", config.iters) { parseDictionaryCss(c.dictCss) }
        out.append("  %-27s cold=%7.2fms  %s".format(c.id.take(27), coldFor(c.dictCss), warm.trimStart()))
    }
    if (styled.isNotEmpty()) {
        out.appendLine(
            "  all ${styled.size} stylesheets (${styled.sumOf { it.dictCss.length }} chars): " +
                "cold=${"%.1f".format(coldTotalMs)}ms, warm=0ms (cached)",
        )
    }

    val glossRow = time("parseStructuredGlossary", "${biggest.glossary.length} chars", config.iters) {
        parseStructuredGlossary(biggest.glossary)
    }
    out.append(glossRow)
    out.appendLine()
    out.appendLine("full plan for ${biggest.id} (${biggest.glossary.length} chars, ${biggest.dictName}):")
    // Card preparation includes parsing, but excludes native document layout.
    val planRow = time("  order+cssMap+cards", "excludes layout; ${cases.size} dicts", config.iters) {
        val shown = orderLookupResultsForDisplay(listOf(biggest.result), profile, context)
        buildParsedCssMap(cases.map { it.styles }.flatten(), "", context)
        buildCards(shown, groupTerms = true, resolveTitle = { it }, priority = emptyMap())
        Unit
    }
    out.append(planRow)
    out.toString()
}

/** Cold parse time, bypassing the cache by salting the text. */
private fun coldFor(cssText: String): Double =
    measureNanoTime { parseDictionaryCss(cssText + "/*cold" + System.nanoTime() + "*/") } / 1_000_000.0

private inline fun time(label: String, note: String, iters: Int, block: () -> Any): String {
    // Two warm-up passes, but not more than the run actually asks for - a one-iteration run must
    // not silently measure four.
    repeat(minOf(2, iters)) { block() }
    val samples = DoubleArray(iters)
    for (i in 0 until iters) samples[i] = measureNanoTime { block() } / 1_000_000.0
    val s = samples.sorted()
    return "  %-26s p50=%7.3fms  min=%7.3f  max=%7.3f  %s\n".format(
        label,
        s[s.size / 2],
        s.first(),
        s.last(),
        note,
    )
}

// ---------------------------------------------------------------------------------------------
// Pass 2 - time to visible content, per mode
// ---------------------------------------------------------------------------------------------

private fun emit(text: String) {
    Log.i(TAG, text)
}

private suspend fun modePass(
    cases: List<BenchCase>,
    config: BenchConfig,
    setJob: (BenchJob?) -> Unit,
): String {
    val out = StringBuilder()
    out.appendLine("--- time to visible content (mount -> content on screen) ---")
    out.appendLine("  cold = stylesheet cache misses every mount (unique CSS text)")
    out.appendLine("  warm = cache hit, the steady state after the first lookup")
    out.appendLine("  %-10s %-26s %9s %9s %9s".format("mode", "case", "cold p50", "warm p50", "delta"))
    emit("\n$out")

    // Sample deliberately: the styled dictionaries are where the CSS cache matters, and the
    // biggest glosses are where per-node work matters. Taking the first N by id would have picked
    // four cases with no stylesheet at all and measured none of the effect.
    val styled = cases.filter { it.dictCss.isNotEmpty() }.sortedByDescending { it.dictCss.length }
    val biggest = cases.sortedByDescending { it.glossary.length }
    val sample = (styled + biggest)
        .distinctBy { it.id }
        .sortedBy { it.id }
        .take(config.casesPerMode)
        .ifEmpty { cases.take(config.casesPerMode) }

    // Let the device reach a steady state before timing; the first passes run hot from the stage
    // work above and would otherwise bias every mode equally.
    runCatching { mountOnce(sample.first(), BenchMode.COMPOSE, -1, setJob) }
    runCatching { mountOnce(sample.first(), BenchMode.COMPOSE, -2, setJob) }

    var key = 0
    for (mode in config.modes) {
        for (case in sample) {
            val pair = measurePair(case, mode, key, config, setJob)
            key += config.iters * 2
            if (pair == null) {
                emit("  ${mode.id}/${case.id} timed out")
                continue
            }
            val (cold, warm) = pair
            val line = "  %-10s %-26s %8.1fms %8.1fms %8.1fms".format(
                mode.id,
                case.id.take(26),
                cold,
                warm,
                cold - warm,
            )
            emit(line)
            out.append(line).append('\n')
        }
    }
    setJob(null)
    return out.toString()
}

/**
 * p50 for both arms of one (case, mode) pair.
 *
 * Cold and warm are interleaved within each iteration and the order alternates, because running
 * all the cold mounts first and then all the warm ones lets the device drift (thermal, scheduler)
 * straight into the delta - which showed up as warm measuring 149ms SLOWER than cold. Interleaving
 * makes both arms see the same conditions.
 */
private suspend fun measurePair(
    case: BenchCase,
    mode: BenchMode,
    keyStart: Int,
    config: BenchConfig,
    setJob: (BenchJob?) -> Unit,
): Pair<Double, Double>? {
    val cold = ArrayList<Double>(config.iters)
    val warm = ArrayList<Double>(config.iters)
    for (i in 0 until config.iters) {
        val coldFirst = i % 2 == 0
        val salt = "$keyStart-$i"
        val a = mountOnce(case, mode, keyStart + i * 2, setJob, salt)
        val b = mountOnce(case, mode, keyStart + i * 2 + 1, setJob, null)
        if (a == null || b == null) return null
        if (i == 0) continue // warm-up
        if (coldFirst) {
            cold += a
            warm += b
        } else {
            cold += b
            warm += a
        }
    }
    if (cold.isEmpty() || warm.isEmpty()) return null
    return p50(cold) to p50(warm)
}

private fun p50(values: List<Double>): Double {
    val s = values.sorted()
    return s[s.size / 2]
}

// ---------------------------------------------------------------------------------------------
// The thing under test
// ---------------------------------------------------------------------------------------------

/**
 * Mounts [case] in [mode] under a fresh key and returns milliseconds until its content is on
 * screen, or null on timeout. A non-null [cssSalt] forces the stylesheet cache to miss.
 */
private suspend fun mountOnce(
    case: BenchCase,
    mode: BenchMode,
    key: Int,
    setJob: (BenchJob?) -> Unit,
    cssSalt: String? = null,
): Double? {
    val shown = CompletableDeferred<Unit>()
    val start = System.nanoTime()
    setJob(BenchJob(key, case, mode, shown, cssSalt))
    // The renderer only completes shown once it is composed with this job, so this doubles as the
    // "did the fresh mount actually happen" barrier.
    val ok = withTimeoutOrNull(MOUNT_TIMEOUT_MS) { shown.await(); true } ?: false
    if (!ok) {
        setJob(null)
        return null
    }
    // shown resolves during a side effect, before the frame that puts pixels down.
    withFrameNanos { }
    val elapsed = (System.nanoTime() - start) / 1_000_000.0
    setJob(null)
    delay(32) // let the teardown land before the next mount
    return elapsed
}

@Composable
private fun MountedRenderer(job: BenchJob) {
    val context = LocalContext.current
    val profile = remember { AnkiProfile(id = "bench", name = "bench") }
    // One WebView for the whole run: we are timing content delivery, not WebView construction.
    val webView = remember { WebView(context).apply { setBackgroundColor(AndroidColor.TRANSPARENT) } }
    val case = job.case
    // A salt makes this stylesheet text unique, so the CSS cache misses and this mount pays a
    // genuine parse - the "cold" arm of the comparison.
    val styles = remember(case.styles, job.cssSalt) {
        if (job.cssSalt == null) {
            case.styles
        } else {
            listOf(DictionaryStyle(case.dictName, case.dictCss + "/*" + job.cssSalt + "*/"))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                // The Compose paths report the plan (parse + stylesheet + cards) through
                // onPlanReady; onContentReadyChange only fires when the composable first runs,
                // which is while the plan is still being built. The WebView's callback is its real
                // JS-ready signal, so it stays authoritative there.
                if (job.mode == BenchMode.WEBVIEW && !job.shown.isCompleted) job.shown.complete(Unit)
            },
    ) {
        when (job.mode) {
            BenchMode.COMPOSE -> DictionaryEntryCompose(
                results = listOf(case.result),
                styles = styles,
                mediaDataUris = emptyMap(),
                placeholder = "",
                fontSize = case.fontSize,
                activeProfile = profile,
                groupTerms = true,
                onContentReadyChange = { if (it) job.shown.complete(Unit) },
                onPlanReady = { job.shown.complete(Unit) },
                modifier = Modifier.fillMaxWidth(),
            )

            BenchMode.PLAIN_TEXT -> DictionaryEntryCompose(
                results = listOf(case.result),
                styles = styles,
                mediaDataUris = emptyMap(),
                placeholder = "",
                fontSize = case.fontSize,
                activeProfile = profile,
                groupTerms = true,
                usePlainTextBody = true,
                onContentReadyChange = { if (it) job.shown.complete(Unit) },
                onPlanReady = { job.shown.complete(Unit) },
                modifier = Modifier.fillMaxWidth(),
            )

            BenchMode.WEBVIEW -> DictionaryEntryWebView(
                results = listOf(case.result),
                styles = styles,
                mediaDataUris = emptyMap(),
                placeholder = "",
                fontSize = case.fontSize,
                activeProfile = profile,
                groupTerms = true,
                webViewProvider = { webView },
                onContentReadyChange = { if (it) job.shown.complete(Unit) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
