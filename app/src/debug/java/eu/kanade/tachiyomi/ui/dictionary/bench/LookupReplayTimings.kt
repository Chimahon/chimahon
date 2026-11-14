package eu.kanade.tachiyomi.ui.dictionary.bench

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import chimahon.diagnostics.LookupProfiler
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-memory phase timers for one replay sample; no logcat stream, no Perfetto, no file I/O inside a
 * measured interval.
 *
 * Everything is read from the instrumentation the production popup already emits, so a replay sample
 * measures the real renderer rather than a copy of it:
 *
 *  - `plan.queued`     [eu.kanade.tachiyomi.ui.dictionary.compose.DictionaryPlanBuilder.start] marks
 *                      the instant the first content mount is enqueued (its plan job is dispatched).
 *  - `plan.prepare`    [DictionaryPlanBuilder.prepare] wraps the whole preparation pass
 *                      (CSS parse, `buildCards`, native glossary batches, card assembly).
 *  - `plan.ready`      emitted at the end of that pass, before the plan is published to composition.
 *  - `document.first_draw` [NativeGlossaryView.onDraw] the first frame that painted a prepared
 *                      glossary document, i.e. popup content, not the empty container.
 *  - `document.frame_commit` / `document.frame_presented` are registered inside that same
 *                      `onDraw` via `ViewRootImpl.registerFrameCommitCallback`, so a late callback
 *                      from a blank frame cannot claim the sample; the next vsync is the closest
 *                      app-side estimate of display presentation.
 */
internal class LookupReplayTimings(
    private val clock: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val onFirstDraw: () -> Unit = {},
    private val onFrameCommit: () -> Unit = {},
) : LookupProfiler.Sink {
    data class Mark(val phase: String, val start: Long, val end: Long, val detail: String)

    private val lock = Any()
    private val marks = ArrayList<Mark>()
    @Volatile private var requestId = 0L
    @Volatile private var armed = false
    @Volatile private var firstDrawNs = 0L
    @Volatile private var frameCommitNs = 0L
    @Volatile private var framePresentedNs = 0L

    /** Sample origin: installed before the new popup state is published, so the mount is enqueued after it. */
    val started: Long = now()

    val firstDraw: Long get() = firstDrawNs
    val frameCommit: Long get() = frameCommitNs
    val framePresented: Long get() = framePresentedNs

    /** Ignore every event until the sample's own request id is known. */
    fun arm(id: Long) {
        requestId = id
        armed = true
    }

    override val enabled = true
    override fun now() = clock()
    override fun cpu() = Debug.threadCpuTimeNanos()
    override fun threadId() = Process.myTid().toLong()

    /**
     * Section timers (`native.*`, `document.*` sections, function summaries) are not part of the
     * harness contract; skipping them keeps the measured frame free of instrumentation overhead.
     * Marks and the two plan timers arrive through `event()` regardless of this gate.
     */
    override fun recordsEvent(phase: String) = false

    override fun event(id: Long, phase: String, start: Long, end: Long, cpu: Long, detail: String) {
        if (!armed || id != requestId) return
        var drew = false
        var committed = false
        synchronized(lock) {
            if (phase in RETAINED_PHASES) marks.add(Mark(phase, start, end, detail))
            when (phase) {
                "document.first_draw" -> if (firstDrawNs == 0L) {
                    firstDrawNs = start
                    drew = true
                }
                "document.frame_commit" -> if (frameCommitNs == 0L || start < frameCommitNs) {
                    frameCommitNs = start
                    committed = true
                }
                "document.frame_presented" -> if (framePresentedNs == 0L) framePresentedNs = start
            }
        }
        // Host callbacks run outside the sink's lock; they only complete deferreds.
        if (drew) onFirstDraw()
        if (committed) onFrameCommit()
    }

    /**
     * Phase breakdown for `status.json`, matching `tools/perf/lookup-replay/replay.py`:
     * `mountQueueMs` (sample start -> mount enqueued), `prepareMs` (plan preparation wall time),
     * `readyToDrawMs` (plan ready -> first content draw), `drawToCommitMs` (first draw -> frame
     * commit/presentation), `firstDrawTotalMs` and `frameCommitTotalMs` (totals from the sample
     * origin). Throws with a descriptive message when a phase never happened, so the activity can
     * report the failure honestly instead of inventing a number.
     */
    fun sample(): JSONObject {
        val snapshot = synchronized(lock) { marks.toList() }
        val firstDraw = firstDrawNs
        val commit = frameCommitNs.takeIf { it > 0 } ?: framePresentedNs.takeIf { it > 0 }
        val queued = snapshot.firstOrNull { it.phase == "plan.queued" }
        /* With progressive publish the first card can draw before the plan finishes preparing, so
           neither mark is guaranteed at sample time; report what exists and leave the rest null. */
        val prepare = snapshot.firstOrNull { it.phase == "plan.prepare" }
        val ready = snapshot.firstOrNull { it.phase == "plan.ready" }?.start
        check(firstDraw > 0L) { "No prepared glossary document drew" }
        /* Progressive publish draws the first card before the plan is complete, so the draw may
           legitimately precede plan.ready; measure from the partial publish in that case. */
        val partial = snapshot.firstOrNull { it.phase == "plan.ui.partial" }?.start
        val drawFrom = if (ready != null && firstDraw >= ready) ready else partial ?: queued?.start ?: started
        val drewBeforeReady = ready != null && firstDraw < ready

        fun ms(from: Long, to: Long) = (to - from) / 1e6
        // Fall back to the preparation start when a caller supplies a pre-started plan (there is then
        // no enqueue of our own to observe).
        val mountEnd = queued?.start ?: prepare?.start ?: started
        return JSONObject()
            .put("mountQueueMs", ms(started, mountEnd))
            .put("prepareMs", if (prepare != null && ready != null) ms(prepare.start, ready) else JSONObject.NULL)
            .put("readyToDrawMs", ms(drawFrom, firstDraw))
            .put("drewBeforePlanReady", drewBeforeReady)
            .put("drawToCommitMs", commit?.let { ms(firstDraw, it) } ?: JSONObject.NULL)
            .put("firstDrawTotalMs", ms(started, firstDraw))
            .put("frameCommitTotalMs", commit?.let { ms(started, it) } ?: JSONObject.NULL)
            .put("commitSignal", when {
                frameCommitNs > 0L -> "document.frame_commit"
                framePresentedNs > 0L -> "document.frame_presented"
                else -> "unavailable"
            })
            .put("queueWaitMs", if (queued != null && prepare != null) ms(queued.start, prepare.start) else 0.0)
            .put("marks", JSONArray(snapshot.map {
                JSONObject().put("phase", it.phase).put("startNs", it.start).put("endNs", it.end)
                    .put("detail", it.detail)
            }))
    }

    private companion object {
        /** Bounded allowlist: the marks that explain a replay sample, not the whole profile stream. */
        val RETAINED_PHASES = setOf(
            "plan.queued", "plan.config", "plan.prepare", "plan.ready",
            "plan.ui.await", "plan.ui.resume", "plan.ui.publish", "plan.native.batch", "plan.ui.partial",
            "document.attached", "document.bind", "document.first_measure", "document.first_layout",
            "document.first_draw.begin", "document.first_draw", "document.first_paint",
            "document.frame_commit", "document.frame_presented",
        )
    }
}
