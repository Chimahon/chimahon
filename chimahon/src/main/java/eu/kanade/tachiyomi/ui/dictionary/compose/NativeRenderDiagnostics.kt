package eu.kanade.tachiyomi.ui.dictionary.compose

import chimahon.diagnostics.LookupProfiler
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicLong

/** Temporary aggregate diagnostics; disabled captures do not read clocks or retain timings. */
public object NativeRenderDiagnostics {
    private val sequence = AtomicLong()
    public fun nextPlanId(): Long = sequence.incrementAndGet()

    public fun mark(request: Long, plan: Long, phase: String, detail: String = "") {
        LookupProfiler.mark(request, phase, "plan=$plan $detail")
    }

    public fun now(): Long = LookupProfiler.sink?.takeIf { it.enabled }?.now() ?: 0L

    public fun queueStarted(request: Long, plan: Long, worker: Int, queued: Long) {
        val sink = LookupProfiler.sink?.takeIf { it.enabled && queued != 0L } ?: return
        sink.event(request, "plan.batch.queue", queued, sink.now(), 0L, "plan=$plan worker=$worker elapsed_only")
    }

    public fun <T> section(request: Long, plan: Long, phase: String, detail: String = "", block: () -> T): T {
        val sink = LookupProfiler.sink?.takeIf { it.enabled && request != 0L } ?: return block()
        val start = sink.now()
        val cpu = sink.cpu()
        try {
            return block()
        } finally {
            sink.event(request, phase, start, sink.now(), sink.cpu() - cpu, "plan=$plan $detail inclusive")
        }
    }

    public suspend fun <T> elapsed(request: Long, plan: Long, phase: String, block: suspend () -> T): T {
        val sink = LookupProfiler.sink?.takeIf { it.enabled && request != 0L } ?: return block()
        val start = sink.now()
        try {
            return block()
        } catch (error: CancellationException) {
            if (phase == "plan.prepare") mark(request, plan, "plan.cancelled")
            throw error
        } catch (error: Exception) {
            if (phase == "plan.prepare") mark(request, plan, "plan.failed", "${error.javaClass.simpleName}: ${error.message}")
            throw error
        } finally {
            sink.event(request, phase, start, sink.now(), 0L, "plan=$plan elapsed_only wall_only")
        }
    }
}

internal class NativeMeasurementTrace(private val request: Long, private val plan: Long, private val worker: Int) {
    private val sink = LookupProfiler.sink?.takeIf { it.enabled && request != 0L }
    val enabled: Boolean get() = sink != null
    private class Total(var wall: Long = 0, var cpu: Long = 0, var calls: Int = 0)
    private val totals = if (enabled) linkedMapOf<String, Total>() else null
    private var previousWall = sink?.now() ?: 0L
    private var previousCpu = sink?.cpu() ?: 0L
    private var sampleWall = 0L
    private var sampleCpu = 0L

    fun begin() {
        val target = sink ?: return
        sampleWall = target.now()
        sampleCpu = target.cpu()
    }

    fun end(name: String) {
        val target = sink ?: return
        val now = target.now()
        val cpu = target.cpu()
        val total = checkNotNull(totals).getOrPut(name) { Total() }
        total.wall += now - sampleWall
        total.cpu += cpu - sampleCpu
        total.calls++
    }

    fun checkpoint(name: String) {
        val target = sink ?: return
        val now = target.now()
        val cpu = target.cpu()
        val total = checkNotNull(totals).getOrPut(name) { Total() }
        total.wall += now - previousWall
        total.cpu += cpu - previousCpu
        total.calls++
        previousWall = now
        previousCpu = cpu
    }

    fun <T> section(name: String, block: () -> T): T {
        val target = sink ?: return block()
        val start = target.now()
        val cpu = target.cpu()
        try {
            return block()
        } finally {
            val end = target.now()
            val usedCpu = target.cpu() - cpu
            val total = checkNotNull(totals).getOrPut(name) { Total() }
            total.wall += end - start
            total.cpu += usedCpu
            total.calls++
        }
    }

    fun report(detail: String) {
        if (!enabled) return
        val stages = checkNotNull(totals).entries.joinToString(" ") { (name, total) ->
            "${name}WallMs=${total.wall / 1e6} ${name}CpuMs=${total.cpu / 1e6} ${name}Calls=${total.calls}"
        }
        NativeRenderDiagnostics.mark(request, plan, "plan.measurement", "worker=$worker $detail $stages clockOverhead=unmeasured")
    }
}
