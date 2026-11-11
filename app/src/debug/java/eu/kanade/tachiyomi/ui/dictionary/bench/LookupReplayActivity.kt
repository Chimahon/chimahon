package eu.kanade.tachiyomi.ui.dictionary.bench

import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Choreographer
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import chimahon.LookupResult
import chimahon.anki.AnkiProfile
import chimahon.diagnostics.LookupProfiler
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.dictionary.compose.DictionaryEntryCompose
import eu.kanade.tachiyomi.ui.dictionary.compose.DictionaryPlanSlot
import eu.kanade.tachiyomi.ui.dictionary.compose.DictionaryRenderEnvironment
import eu.kanade.tachiyomi.ui.dictionary.compose.LocalDictionaryRenderEnvironment
import eu.kanade.tachiyomi.util.view.setComposeContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume

/**
 * Debug-only replay entry point for `tools/perf/lookup-replay/replay.py`.
 *
 * The harness stages `request.json` and `bundle.json` under `files/lookup-replay/<run_id>/`, starts
 * this activity with `--es run_id <run_id>`, and polls `status.json` in the same directory. This
 * activity renders the *production* popup ([DictionaryEntryCompose]) through the same
 * `DictionaryPlanBuilder` / `DictionaryPlanSlot` / `buildCards` path the reader uses, at the bundle's
 * real density, layout direction and viewport, and reports the harness phases from the renderer's
 * existing `NativeRenderDiagnostics` / `LookupProfiler` marks.
 *
 * Nothing here is shipped: the class lives in `src/debug` and is declared in the debug manifest.
 * No new dependencies, no artificial delays inside a measured interval, no WebView.
 */
class LookupReplayActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job by mutableStateOf<ReplayJob?>(null)
    private var savedSink: LookupProfiler.Sink? = null

    /** One bundle case's immutable render inputs, decoded once per run. */
    private class Inputs(val source: JSONObject) {
        val styles = LookupReplayData.styles(source.getJSONArray("styles"))
        val profile = AnkiProfile.fromJson(source.getJSONObject("profile"))
        val media = LookupReplayData.stringMap(source.getJSONObject("media"))
        private val rawResults = source.getJSONArray("results")
        val environment: DictionaryRenderEnvironment = source.getJSONObject("environment").let {
            DictionaryRenderEnvironment(
                dark = it.getBoolean("dark"),
                amoled = it.getBoolean("amoled"),
                seedColor = it.getInt("seedColor"),
                dictionaryTitles = LookupReplayData.stringMap(it.getJSONObject("dictionaryTitles")),
            )
        }

        fun decodeResults(): List<LookupResult> = LookupReplayData.decode(rawResults)
    }

    /**
     * A mounted popup. `key` forces a fresh composition (and therefore a fresh plan) per sample so
     * one iteration can never reuse another iteration's prepared plan.
     */
    private class ReplayJob(
        val key: Int,
        val inputs: Inputs,
        val results: List<LookupResult>,
        val profilingId: Long,
        val planSlot: DictionaryPlanSlot,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runId = intent.getStringExtra("run_id").orEmpty()
        require(RUN_ID.matches(runId)) { "Invalid replay run id '$runId'" }
        val root = File(filesDir, "lookup-replay/$runId")
        require(root.isDirectory) { "Stage request.json and bundle.json first: ${root.path}" }
        // The harness removes this directory after archiving; never delete it here.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        savedSink = LookupProfiler.sink
        val viewportReady = CompletableDeferred<Unit>()
        val report = JSONObject()
            .put("runId", runId)
            .put("protocol", LookupReplayData.PROTOCOL)
            .put("scope", LookupReplayData.SCOPE)
            .put("done", false)
            .put("samples", JSONArray())
        scope.launch {
            suspend fun save() = withContext(Dispatchers.IO) {
                File(root, "status.json").writeText(report.toString(2))
            }
            try {
                // Acknowledge the run immediately so the harness can tell this run's output from a
                // stale file; `done` stays false until a terminal state is reached.
                save()
                val request = withContext(Dispatchers.IO) { JSONObject(File(root, "request.json").readText()) }
                require(request.optString("action") == "replay") {
                    "Unsupported replay action '${request.optString("action")}'"
                }
                val bundleBytes = withContext(Dispatchers.IO) { File(root, "bundle.json").readBytes() }
                // Hash of the staged bundle bytes, exactly as replay.py hashes its local copy.
                report.put("bundleSha256", sha256(bundleBytes))
                    .put("runtime", runtime())
                    .put("runtimeInfo", runtimeInfo())
                val bundle = JSONObject(String(bundleBytes, Charsets.UTF_8))
                LookupReplayData.validate(bundle)

                val all = LookupReplayData.objects(bundle.getJSONArray("cases"))
                val selectedId = request.optString("caseId")
                val cases = if (selectedId.isEmpty()) all else all.filter { it.getString("id") == selectedId }
                require(cases.isNotEmpty()) { "Unknown replay case '$selectedId'" }
                val regime = request.getString("regime")
                val warmups = request.getInt("warmups")
                val iterations = request.getInt("iterations")
                require(regime == "cold-process" || regime == "warm-process") { "Unsupported regime '$regime'" }
                require(warmups in 0..50) { "Invalid warmup count $warmups" }
                require(iterations in 1..100) { "Invalid iteration count $iterations" }
                if (regime == "cold-process") {
                    require(selectedId.isNotEmpty()) { "Cold-process replay measures one case per launch" }
                    require(cases.size == 1 && warmups == 0 && iterations == 1) {
                        "Cold-process replay takes one case, no warmups and one iteration"
                    }
                }
                report.put("regime", regime)
                    .put("compilationMode", request.optString("compilationMode"))

                val viewport = cases.first()
                val widthPx = viewport.getInt("widthPx")
                val heightPx = viewport.getInt("heightPx")
                require(cases.all {
                    it.getInt("widthPx") == widthPx && it.getInt("heightPx") == heightPx &&
                        it.getDouble("density") == viewport.getDouble("density") &&
                        it.getDouble("fontScale") == viewport.getDouble("fontScale")
                }) { "Split runs with different viewports" }
                val density = Density(viewport.getDouble("density").toFloat(), viewport.getDouble("fontScale").toFloat())
                val inputs = cases.associate { it.getString("id") to Inputs(it) }
                val widthDp = (widthPx / density.density).dp
                val heightDp = (heightPx / density.density).dp

                setComposeContent {
                    // The bundle's density (and its font scale) replace the device's for the whole
                    // popup, so px geometry and text metrics match the captured device exactly.
                    CompositionLocalProvider(LocalDensity provides density) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                            Box(
                                Modifier
                                    .requiredSize(widthDp, heightDp)
                                    .onSizeChanged {
                                        // One pixel of rounding slack: dp -> px conversion can land a
                                        // hair off for odd density values, and the renderer is driven by
                                        // the explicit `viewportWidthPx` anyway.
                                        if (it.width in (widthPx - 1)..(widthPx + 1) &&
                                            it.height in (heightPx - 1)..(heightPx + 1)
                                        ) {
                                            viewportReady.complete(Unit)
                                        }
                                    },
                            ) {
                                val current = job
                                if (current != null) key(current.key) { Popup(current) }
                            }
                        }
                    }
                }
                withTimeout(10_000) { viewportReady.await() }
                require(widthPx <= window.decorView.width && heightPx <= window.decorView.height) {
                    "Replay viewport ${widthPx}x$heightPx exceeds the visible activity window " +
                        "(${window.decorView.width}x${window.decorView.height})"
                }
                nextFrame()

                var sequence = 0
                for (iteration in 0 until warmups + iterations) for (caseJson in cases) {
                    val input = inputs.getValue(caseJson.getString("id"))
                    val decodeStart = SystemClock.elapsedRealtimeNanos()
                    val results = withContext(Dispatchers.Default) { input.decodeResults() }
                    val decodeMs = (SystemClock.elapsedRealtimeNanos() - decodeStart) / 1e6

                    val drawn = CompletableDeferred<Unit>()
                    val committed = CompletableDeferred<Unit>()
                    val timings = LookupReplayTimings(
                        onFirstDraw = { drawn.complete(Unit) },
                        onFrameCommit = { committed.complete(Unit) },
                    )
                    // A sample owns its own scope and plan slot, exactly like a fresh popup open.
                    val sampleScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
                    val slot = DictionaryPlanSlot(sampleScope)
                    LookupProfiler.sink = timings
                    val requestId = LookupProfiler.start(caseJson.getString("id"))
                    timings.arm(requestId)
                    job = ReplayJob(++sequence, input, results, requestId, slot)

                    val sample = try {
                        withTimeout(30_000) { drawn.await() }
                        // Commit callback is registered from the document's own first draw; three
                        // seconds is a generous bound for a render-thread commit after a draw.
                        withTimeoutOrNull(3_000) { committed.await() }
                        LookupProfiler.flush()
                        timings.sample().put("ok", true)
                    } catch (e: Exception) {
                        JSONObject().put("ok", false).put("error", e.message ?: e.javaClass.simpleName)
                    } finally {
                        // Teardown is outside every reported interval.
                        job = null
                        slot.cancel()
                        sampleScope.cancel()
                        LookupProfiler.sink = savedSink
                    }

                    sample.put("caseId", caseJson.getString("id"))
                        .put("iteration", iteration - warmups)
                        .put("warmup", iteration < warmups)
                        .put("results", results.size)
                        .put("glossaries", results.sumOf { it.term.glossaries.size })
                        .put("payloadDecodeMs", decodeMs)
                    report.getJSONArray("samples").put(sample)
                    nextFrame()
                    delay(32)
                    save()
                    // Surface the first failed sample instead of measuring on top of a broken popup.
                    require(sample.optBoolean("ok")) { sample.optString("error") }
                }

                report.put("done", true)
                save()
            } catch (e: Exception) {
                job = null
                report.put("error", e.message ?: e.javaClass.simpleName).put("done", true)
                save()
            } finally {
                LookupProfiler.sink = savedSink
            }
        }
    }

    /** The production popup, driven by the bundle case's own geometry, typography and environment. */
    @Composable
    private fun Popup(job: ReplayJob) {
        val caseJson = job.inputs.source
        val options = caseJson.getJSONObject("options")
        CompositionLocalProvider(
            LocalLayoutDirection provides
                if (caseJson.getString("direction") == "rtl") LayoutDirection.Rtl else LayoutDirection.Ltr,
            LocalDictionaryRenderEnvironment provides job.inputs.environment,
        ) {
            DictionaryEntryCompose(
                results = job.results,
                styles = job.inputs.styles,
                mediaDataUris = job.inputs.media,
                placeholder = "",
                activeProfile = job.inputs.profile,
                fontSize = caseJson.getInt("fontSize"),
                showFrequencyHarmonic = options.optBoolean("showFrequencyHarmonic", false),
                showFrequencyAverage = options.optBoolean("showFrequencyAverage", false),
                groupTerms = options.optBoolean("groupTerms", true),
                groupPitches = options.optBoolean("groupPitches", false),
                showPitchDiagram = options.optBoolean("showPitchDiagram", true),
                showPitchNumber = options.optBoolean("showPitchNumber", true),
                showPitchText = options.optBoolean("showPitchText", true),
                customCss = options.optString("customCss", ""),
                eInkMode = options.optBoolean("eInkMode", false),
                wordAudioEnabled = options.optBoolean("wordAudioEnabled", false),
                wordAudioAutoplayOverride = false,
                isLoading = false,
                // Bundle results are the ordered display list, like the reader's published frame.
                resultsAreOrdered = true,
                profilingId = job.profilingId,
                viewportWidthPx = caseJson.getInt("widthPx"),
                cardPreparation = null,
                planSlot = job.planSlot,
                // No-op chrome handlers, as in the reader: the Anki/audio actions are part of the
                // production card composition and must not change what gets measured.
                onAnkiLookup = { _, _, _, _, _ -> },
                onRecursiveLookup = { _, _, _, _, _, _ -> },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    /**
     * Stable identity of the measured runtime: the same string for every launch of one build on one
     * device, different when anything that could move the numbers changes. The harness compares it
     * across batches with plain equality.
     */
    @Suppress("DEPRECATION")
    private fun runtime(): String = listOf(
        "fingerprint=${Build.FINGERPRINT}",
        "abi=${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}",
        "sdk=${Build.VERSION.SDK_INT}",
        "device=${Build.MANUFACTURER} ${Build.MODEL}",
        "buildType=${BuildConfig.BUILD_TYPE}",
        "renderer=${BuildConfig.DICTIONARY_RENDERER_SOURCE_SHA256}",
        "density=${resources.displayMetrics.density}",
        "fontScale=${resources.configuration.fontScale}",
        "refreshRateHz=${windowManager.defaultDisplay.refreshRate}",
    ).joinToString("|")

    /** Structured provenance next to [runtime]; archived with the run for later inspection. */
    @Suppress("DEPRECATION")
    private fun runtimeInfo(): JSONObject = JSONObject()
        .put("sdk", Build.VERSION.SDK_INT)
        .put("release", Build.VERSION.RELEASE)
        .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        .put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
        .put("buildType", BuildConfig.BUILD_TYPE)
        .put("debuggable", applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        .put("rendererSourceSha256", BuildConfig.DICTIONARY_RENDERER_SOURCE_SHA256)
        .put("package", packageName)
        .put("version", BuildConfig.VERSION_NAME)
        .put("cores", Runtime.getRuntime().availableProcessors())
        .put("density", resources.displayMetrics.density)
        .put("fontScale", resources.configuration.fontScale)
        .put("refreshRateHz", windowManager.defaultDisplay.refreshRate)

    override fun onDestroy() {
        scope.cancel()
        job = null
        if (LookupProfiler.sink is LookupReplayTimings) LookupProfiler.sink = savedSink
        super.onDestroy()
    }

    private companion object {
        val RUN_ID = Regex("[A-Za-z0-9_-]{1,80}")
    }
}

private suspend fun nextFrame() = suspendCancellableCoroutine<Unit> { continuation ->
    val choreographer = Choreographer.getInstance()
    val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
    choreographer.postFrameCallback(callback)
    continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
