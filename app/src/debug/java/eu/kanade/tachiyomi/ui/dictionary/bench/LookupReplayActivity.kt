package eu.kanade.tachiyomi.ui.dictionary.bench

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import chimahon.DictionaryRepository
import chimahon.LookupResult
import chimahon.anki.AnkiProfile
import chimahon.diagnostics.LookupProfiler
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.compose.DictionaryEntryCompose
import eu.kanade.tachiyomi.ui.dictionary.compose.DictionaryRenderEnvironment
import eu.kanade.tachiyomi.ui.dictionary.compose.LocalDictionaryRenderEnvironment
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryPaths
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryTitle
import eu.kanade.tachiyomi.ui.dictionary.orderLookupResultsForDisplay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import eu.kanade.domain.ui.UiPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume

/** Debug/benchmark only. No WebView, live OCR, artificial delay or dictionary-engine changes. */
class LookupReplayActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job by mutableStateOf<Job?>(null)
    private var savedSink: LookupProfiler.Sink? = null
    private class Inputs(val source: JSONObject) {
        val styles = LookupReplayData.styles(source.getJSONArray("styles"))
        val profile = AnkiProfile.fromJson(source.getJSONObject("profile"))
        val media = LookupReplayData.stringMap(source.getJSONObject("media"))
        val rawResults = source.getJSONArray("results").toString()
        private val appearance = source.getJSONObject("environment")
        val environment = DictionaryRenderEnvironment(appearance.getBoolean("dark"), appearance.getBoolean("amoled"),
            appearance.getInt("seedColor"), LookupReplayData.stringMap(appearance.getJSONObject("dictionaryTitles")))
    }
    private data class Job(val key: Int, val inputs: Inputs, val results: List<LookupResult>, val requestId: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runId = intent.getStringExtra("run_id").orEmpty()
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Invalid replay run id" }
        val root = File(filesDir, "lookup-replay/$runId")
        require(root.isDirectory) { "Stage request.json first" }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        savedSink = LookupProfiler.sink
        val viewportReady = CompletableDeferred<Unit>()
        val report = JSONObject().put("protocol", LookupReplayData.PROTOCOL).put("scope", LookupReplayData.SCOPE)
            .put("runId", runId).put("done", false).put("samples", JSONArray())
        scope.launch {
            suspend fun save() = withContext(Dispatchers.IO) {
                File(root, "status.json").writeText(report.toString(2))
            }
            try {
                val request = withContext(Dispatchers.IO) { JSONObject(File(root, "request.json").readText()) }
                report.put("runtime", withContext(Dispatchers.IO) { runtime() })
                if (request.optString("action") == "capture") {
                    val bundle = withContext(Dispatchers.IO) { capture(request) }
                    LookupReplayData.validate(bundle)
                    withContext(Dispatchers.IO) { File(root, "bundle.json").writeText(bundle.toString(2)) }
                    report.put("capturedCases", bundle.getJSONArray("cases").length()).put("done", true)
                    save()
                    return@launch
                }
                val bundleFile = File(root, "bundle.json")
                val bundle = withContext(Dispatchers.IO) { JSONObject(bundleFile.readText()) }
                LookupReplayData.validate(bundle)
                report.put("bundleSha256", withContext(Dispatchers.IO) { digest(bundleFile) })
                    .put("source", bundle.getString("source"))
                    .put("regime", request.getString("regime"))
                    .put("compilationMode", request.getString("compilationMode"))
                    .put("timers", "in-memory-function-timers/no-perfetto/no-logcat-stream")
                val all = LookupReplayData.objects(bundle.getJSONArray("cases"))
                val selected = request.optString("caseId")
                val cases = if (selected.isEmpty()) all else all.filter { it.getString("id") == selected }
                require(cases.isNotEmpty()) { "Unknown replay case" }
                val inputs = cases.associate { it.getString("id") to Inputs(it) }
                val warmups = request.getInt("warmups")
                val iterations = request.getInt("iterations")
                require(warmups in 0..50 && iterations in 1..100)
                require(request.getString("regime") in setOf("cold-process", "warm-process"))
                if (request.getString("regime") == "cold-process") require(cases.size == 1 && warmups == 0 && iterations == 1)
                val viewport = cases.first()
                require(cases.all { it.getInt("widthPx") == viewport.getInt("widthPx") &&
                    it.getInt("heightPx") == viewport.getInt("heightPx") &&
                    it.getDouble("density") == viewport.getDouble("density") &&
                    it.getDouble("fontScale") == viewport.getDouble("fontScale") }) { "Split runs with different viewports" }
                val density = Density(viewport.getDouble("density").toFloat(), viewport.getDouble("fontScale").toFloat())
                setContent {
                    TachiyomiTheme {
                        CompositionLocalProvider(LocalDensity provides density) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                                Box(Modifier.requiredSize((viewport.getInt("widthPx") / density.density).dp,
                                    (viewport.getInt("heightPx") / density.density).dp).onSizeChanged {
                                    if (it.width == viewport.getInt("widthPx") && it.height == viewport.getInt("heightPx"))
                                        viewportReady.complete(Unit)
                                }) {
                                    val current = job
                                    if (current != null) key(current.key) { Popup(current) }
                                }
                            }
                        }
                    }
                }
                withTimeout(10_000) { viewportReady.await() }
                require(viewport.getInt("widthPx") <= window.decorView.width &&
                    viewport.getInt("heightPx") <= window.decorView.height) { "Replay viewport exceeds the visible activity window" }
                nextFrame()
                var sequence = 0
                for (iteration in 0 until warmups + iterations) for (case in cases) {
                    val input = inputs.getValue(case.getString("id"))
                    val decodeStart = SystemClock.elapsedRealtimeNanos()
                    val results = withContext(Dispatchers.Default) { LookupReplayData.decode(JSONArray(input.rawResults)) }
                    val decodeMs = (SystemClock.elapsedRealtimeNanos() - decodeStart) / 1e6
                    val drawn = CompletableDeferred<Unit>()
                    val committed = CompletableDeferred<Long>()
                    var commitCallback: Runnable? = null
                    val timings = LookupReplayTimings {
                        drawn.complete(Unit)
                        if (Build.VERSION.SDK_INT >= 29 && window.decorView.isHardwareAccelerated) {
                            Runnable { committed.complete(SystemClock.elapsedRealtimeNanos()) }.also {
                                commitCallback = it
                                window.decorView.viewTreeObserver.registerFrameCommitCallback(it)
                            }
                        }
                    }
                    LookupProfiler.sink = timings
                    timings.requestId = LookupProfiler.start(case.getString("id"))
                    job = Job(++sequence, input, results, timings.requestId)
                    val sample = try {
                        withTimeout(30_000) { drawn.await() }
                        val commit = if (Build.VERSION.SDK_INT >= 29) withTimeoutOrNull(3_000) { committed.await() } else null
                        LookupProfiler.flush()
                        timings.result(commit)
                    } catch (e: Exception) {
                        JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
                    } finally {
                        if (Build.VERSION.SDK_INT >= 29) commitCallback?.let {
                            window.decorView.viewTreeObserver.unregisterFrameCommitCallback(it)
                        }
                        job = null
                    }
                    sample.put("caseId", case.getString("id")).put("iteration", iteration - warmups)
                        .put("warmup", iteration < warmups).put("payloadDecodeAndDispatchMs", decodeMs)
                        .put("results", results.size).put("glossaries", results.sumOf { it.term.glossaries.size })
                    report.getJSONArray("samples").put(sample)
                    // Teardown and serialization are outside every reported sample interval.
                    nextFrame()
                    delay(32)
                    save()
                    require(sample.getBoolean("ok")) { sample.optString("error") }
                }
                report.put("done", true)
                save()
            } catch (e: Exception) {
                report.put("error", e.message ?: e.javaClass.simpleName).put("done", true)
                save()
            } finally { LookupProfiler.sink = savedSink }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Popup(job: Job) {
        val case = job.inputs.source
        val options = case.getJSONObject("options")
        CompositionLocalProvider(
            LocalLayoutDirection provides if (case.getString("direction") == "rtl") LayoutDirection.Rtl else LayoutDirection.Ltr,
            LocalDictionaryRenderEnvironment provides job.inputs.environment,
        ) {
            DictionaryEntryCompose(results = job.results, styles = job.inputs.styles,
                mediaDataUris = job.inputs.media, placeholder = "",
                activeProfile = job.inputs.profile, fontSize = case.getInt("fontSize"),
                groupTerms = options.getBoolean("groupTerms"), groupPitches = options.getBoolean("groupPitches"),
                showFrequencyHarmonic = options.getBoolean("showFrequencyHarmonic"),
                showFrequencyAverage = options.getBoolean("showFrequencyAverage"),
                showPitchDiagram = options.getBoolean("showPitchDiagram"), showPitchNumber = options.getBoolean("showPitchNumber"),
                showPitchText = options.getBoolean("showPitchText"), customCss = options.getString("customCss"),
                eInkMode = options.getBoolean("eInkMode"), wordAudioEnabled = options.getBoolean("wordAudioEnabled"),
                wordAudioAutoplayOverride = false, resultsAreOrdered = true, profilingId = job.requestId,
                onAnkiLookup = { _, _, _, _, _ -> }, onRecursiveLookup = { _, _, _, _, _, _ -> },
                modifier = Modifier.fillMaxSize())
        }
    }

    private fun capture(request: JSONObject): JSONObject {
        val prefs = Injekt.get<DictionaryPreferences>()
        val profile = prefs.profileStore.getActiveProfile()
        val customColor = prefs.customColor().get()
        val seed = if (customColor != 0) customColor else Injekt.get<UiPreferences>().colorTheme().get()
        val theme = prefs.themeMode().get()
        val dark = when (theme) {
            "dark", "pure_black" -> true
            "light" -> false
            else -> if (customColor != 0) Color(seed).luminance() < 0.5f
                else resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val density = resources.displayMetrics.density
        val cases = JSONArray()
        val repo = DictionaryRepository(getExternalFilesDir(null), { prefs.koreanParserMode().get() })
        try {
            for ((index, query) in LookupReplayData.strings(request.getJSONArray("queries")).withIndex()) {
                val started = SystemClock.elapsedRealtimeNanos()
                val result = repo.lookupWithSearchResolution(query, getDictionaryPaths(this, profile), profile)
                require(result.error == null && result.results.isNotEmpty()) { result.error ?: "No results for $query" }
                val lookupMs = (SystemClock.elapsedRealtimeNanos() - started) / 1e6
                val shown = orderLookupResultsForDisplay(result.results, profile, this)
                val names = (profile.dictionaryOrder + result.styles.map { it.dictName } +
                    shown.flatMap { it.term.glossaries.map { gloss -> gloss.dictName } }).distinct()
                val options = JSONObject().put("groupTerms", prefs.groupTerms().get()).put("groupPitches", prefs.groupPitches().get())
                    .put("showFrequencyHarmonic", prefs.showFrequencyHarmonic().get())
                    .put("showFrequencyAverage", prefs.showFrequencyAverage().get()).put("showPitchDiagram", prefs.showPitchDiagram().get())
                    .put("showPitchNumber", prefs.showPitchNumber().get()).put("showPitchText", prefs.showPitchText().get())
                    .put("customCss", prefs.customCss().get()).put("eInkMode", prefs.eInkMode().get())
                    .put("wordAudioEnabled", prefs.wordAudioEnabled().get()).put("fontFamily", prefs.fontFamily().get())
                cases.put(JSONObject().put("id", "lookup-$index").put("query", query).put("capturedRepositoryMs", lookupMs)
                    .put("widthPx", request.optInt("widthPx", (prefs.popupWidth().get() * density).toInt()))
                    .put("heightPx", request.optInt("heightPx", (prefs.popupHeight().get() * density).toInt()))
                    .put("geometrySource", if (request.has("widthPx")) "explicit-content-viewport" else "popup-preferences")
                    .put("density", density).put("fontScale", resources.configuration.fontScale).put("fontSize", prefs.fontSize().get())
                    .put("direction", if (resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr")
                    .put("profile", LookupReplayData.renderProfile(profile)).put("options", options)
                    .put("environment", JSONObject().put("dark", dark).put("amoled", theme == "pure_black").put("seedColor", seed)
                        .put("dictionaryTitles", JSONObject(names.associateWith { getDictionaryTitle(this, it) })))
                    .put("results", LookupReplayData.encode(shown)).put("styles", JSONArray(result.styles.map {
                        JSONObject().put("dictName", it.dictName).put("styles", it.styles)
                    })).put("media", JSONObject(result.mediaDataUris + repo.loadMediaAsync(query, result.results))))
            }
        } finally { repo.close() }
        return JSONObject().put("protocol", LookupReplayData.PROTOCOL).put("scope", LookupReplayData.SCOPE)
            .put("source", "hoshidicts-output").put("capturedRuntime", runtime()).put("cases", cases)
    }

    private fun runtime(): JSONObject = JSONObject().put("sdk", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("abi", Build.SUPPORTED_ABIS.first())
        .put("buildType", BuildConfig.BUILD_TYPE).put("rendererSourceSha256", BuildConfig.DICTIONARY_REPLAY_SOURCE_SHA256)
        .put("package", packageName).put("version", BuildConfig.VERSION_NAME).put("cores", Runtime.getRuntime().availableProcessors())
        .put("actualDensity", resources.displayMetrics.density).put("actualFontScale", resources.configuration.fontScale)
        .put("refreshRateHz", windowManager.defaultDisplay.refreshRate)
        .put("fonts", JSONObject(listOf("/system/etc/fonts.xml", "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/NotoSansCJK-VF.ttc", "/system/fonts/Roboto-Regular.ttf").map(::File)
            .filter { it.isFile }.associate { it.name to digest(it) }))

    override fun onDestroy() {
        scope.cancel()
        if (LookupProfiler.sink is LookupReplayTimings) LookupProfiler.sink = savedSink
        super.onDestroy()
    }
}

private suspend fun nextFrame() = suspendCancellableCoroutine<Unit> { continuation ->
    val choreographer = android.view.Choreographer.getInstance()
    val callback = android.view.Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
    choreographer.postFrameCallback(callback)
    continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
}

private fun digest(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val bytes = ByteArray(64 * 1024)
        while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
