package eu.kanade.tachiyomi.ui.dictionary.bench

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import chimahon.diagnostics.LookupProfiler
import org.json.JSONArray
import org.json.JSONObject

/** In-memory timers only. Serialization and file I/O happen after the measured frame. */
internal class LookupReplayTimings(
    private val clock: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    val documentDrawn: () -> Unit,
) : LookupProfiler.Sink {
    data class Mark(val phase: String, val start: Long, val end: Long, val detail: String)
    private val marks = ArrayList<Mark>()
    private val functions = ArrayList<Pair<Long, LookupProfiler.FunctionTiming>>()
    var requestId = 0L
    val started = now()
    var firstDraw = 0L
        private set
    override val enabled = true
    override fun now() = clock()
    override fun cpu() = Debug.threadCpuTimeNanos()
    override fun threadId() = Process.myTid().toLong()
    override fun recordsEvent(phase: String) = phase in PHASES

    override fun event(id: Long, phase: String, start: Long, end: Long, cpu: Long, detail: String) {
        if (requestId != 0L && id != requestId) return
        val first = synchronized(this) {
            marks.add(Mark(phase, start, end, detail))
            (phase == "document.first_draw" && firstDraw == 0L).also { if (it) firstDraw = start }
        }
        if (first) documentDrawn()
    }

    override fun functions(id: Long, thread: Long, totals: List<LookupProfiler.FunctionTiming>) {
        if (id != requestId) return
        synchronized(this) {
            for (timing in totals) functions.add(thread to timing.copy())
        }
    }

    fun result(commit: Long?): JSONObject = synchronized(this) {
        val prepare = marks.firstOrNull { it.phase == "plan.prepare" }
        val ready = marks.firstOrNull { it.phase == "plan.ready" }?.start
        require(prepare != null && ready != null && firstDraw >= ready) {
            "No complete preparation followed by a glossary draw; container draws are not success"
        }
        fun ms(a: Long, b: Long) = (b - a) / 1e6
        JSONObject().put("ok", true).put("requestId", requestId)
            .put("mountQueueMs", ms(started, prepare.start))
            .put("prepareMs", ms(prepare.start, ready))
            .put("readyToDrawMs", ms(ready, firstDraw))
            .put("firstDrawTotalMs", ms(started, firstDraw))
            .put("drawToCommitMs", commit?.let { ms(firstDraw, it) } ?: JSONObject.NULL)
            .put("frameCommitTotalMs", commit?.let { ms(started, it) } ?: JSONObject.NULL)
            .put("commitIsDisplayPresentation", false)
            .put("marks", JSONArray(marks.map {
                JSONObject().put("phase", it.phase).put("startNs", it.start).put("endNs", it.end).put("detail", it.detail)
            })).put("functions", JSONArray(functions.map { (thread, timing) ->
                JSONObject().put("thread", thread).put("name", timing.name).put("calls", timing.calls)
                    .put("wallNs", timing.wallNs).put("cpuNs", timing.cpuNs)
                    .put("selfCpuNs", timing.selfCpuNs).put("maxWallNs", timing.maxWallNs)
            }))
    }

    companion object {
        private val PHASES = setOf("plan.prepare", "plan.css", "plan.cards_parse")
    }
}
