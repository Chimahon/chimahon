package chimahon.diagnostics

import java.util.concurrent.atomic.AtomicLong
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue

/** Removable DICT_PROFILE instrumentation. No Android dependencies; the default sink is absent. */
object LookupProfiler {
    interface Sink {
        val enabled: Boolean
        fun now(): Long
        fun cpu(): Long
        fun threadId(): Long = Thread.currentThread().id
        fun recordsEvent(phase: String): Boolean = true
        fun event(id: Long, phase: String, start: Long, end: Long, cpu: Long, detail: String)
        fun functions(id: Long, thread: Long, totals: List<FunctionTiming>) {}
    }

    data class FunctionTiming(
        val name: String,
        var calls: Long = 0,
        var wallNs: Long = 0,
        var cpuNs: Long = 0,
        var selfWallNs: Long = 0,
        var selfCpuNs: Long = 0,
        var maxWallNs: Long = 0,
        var maxCpuNs: Long = 0,
    )

    private class Frame {
        var childWall = 0L
        var childCpu = 0L
    }

    private class ThreadTimings(val tid: Long) {
        val owner = WeakReference(Thread.currentThread())
        val requests = LinkedHashMap<Long, MutableMap<String, FunctionTiming>>()
        val frames = ArrayList<Frame>()
        var depth = 0
    }

    private val threads = ConcurrentLinkedQueue<ThreadTimings>()
    private val local = ThreadLocal<ThreadTimings>()

    private fun threadTimings(target: Sink): ThreadTimings = local.get() ?: ThreadTimings(target.threadId()).also {
        local.set(it)
        threads.add(it)
    }

    /** Drain completed calls only. Suspended work is never placed on a thread's nesting stack. */
    fun flush() {
        val target = sink ?: return
        for (thread in threads) {
            val completed = synchronized(thread) {
                thread.requests.toMap().also { thread.requests.clear() }
            }
            for ((id, totals) in completed) target.functions(id, thread.tid, totals.values.toList())
            if (thread.owner.get()?.isAlive != true) threads.remove(thread)
        }
    }

    @Volatile var sink: Sink? = null
    @Volatile var latestRequestId: Long = 0
        private set
    private val sequence = AtomicLong()

    fun timestamp(): Long = sink?.now() ?: System.nanoTime()

    fun summaryOnly(phase: String): Boolean = phase.startsWith("native.") ||
        phase.startsWith("document.") || phase.startsWith("text.") ||
        phase.startsWith("ruby.") || phase.startsWith("style.") || phase.startsWith("node.") ||
        phase.startsWith("paragraph.") || phase.startsWith("table.") || phase.startsWith("plan.worker.") ||
        phase.startsWith("fn.")

    fun start(query: String): Long {
        val target = sink?.takeIf { it.enabled } ?: return 0
        flush()
        val id = sequence.incrementAndGet()
        latestRequestId = id
        val now = target.now()
        target.event(id, "request.start", now, now, 0, query)
        return id
    }

    fun mark(id: Long, phase: String, detail: String = "") {
        if (id == 0L) return
        val target = sink?.takeIf { it.enabled } ?: return
        val now = target.now()
        target.event(id, phase, now, now, 0, detail)
        if (phase == "document.first_draw") flush()
    }

    /** Wall time only: suspension may resume on another thread, so thread CPU/trace sections are invalid. */
    suspend fun <T> elapsedSection(id: Long, phase: String, block: suspend () -> T): T {
        if (id == 0L) return block()
        val target = sink?.takeIf { it.enabled } ?: return block()
        val start = target.now()
        try {
            return block()
        } finally {
            target.event(id, phase, start, target.now(), 0L, "wall_only;suspending")
            flush()
        }
    }

    fun <T> section(id: Long, phase: String, block: () -> T): T {
        if (id == 0L) return block()
        val target = sink?.takeIf { it.enabled } ?: return block()
        val thread = threadTimings(target)
        if (thread.depth == thread.frames.size) thread.frames.add(Frame())
        val frame = thread.frames[thread.depth++]
        frame.childWall = 0
        frame.childCpu = 0
        val start = target.now()
        val cpu = target.cpu()
        try {
            return block()
        } finally {
            val end = target.now()
            val elapsedCpu = (target.cpu() - cpu).coerceAtLeast(0)
            val elapsedWall = (end - start).coerceAtLeast(0)
            thread.depth--
            if (thread.depth > 0) {
                thread.frames[thread.depth - 1].let { it.childWall += elapsedWall; it.childCpu += elapsedCpu }
            }
            synchronized(thread) {
                val totals = thread.requests.getOrPut(id) { LinkedHashMap() }
                    .getOrPut(phase) { FunctionTiming(phase) }
                totals.calls++
                totals.wallNs += elapsedWall
                totals.cpuNs += elapsedCpu
                totals.selfWallNs += (elapsedWall - frame.childWall).coerceAtLeast(0)
                totals.selfCpuNs += (elapsedCpu - frame.childCpu).coerceAtLeast(0)
                totals.maxWallNs = maxOf(totals.maxWallNs, elapsedWall)
                totals.maxCpuNs = maxOf(totals.maxCpuNs, elapsedCpu)
            }
            if (target.recordsEvent(phase)) target.event(id, phase, start, end, elapsedCpu, "")
        }
    }
}
