package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import chimahon.diagnostics.LookupProfiler
import eu.kanade.tachiyomi.BuildConfig
import org.json.JSONObject
import java.util.concurrent.Executors

/** DICT_PROFILE: debug-only function timers; no per-call Perfetto sections. */
object LookupProfilingAndroid {
    private val output = Executors.newSingleThreadExecutor { task -> Thread(task, "DictTimerLog").apply { isDaemon = true } }
    fun install(context: Context) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (LookupProfiler.sink != null) return
        LookupProfiler.sink = object : LookupProfiler.Sink {
            override val enabled get() = BuildConfig.NATIVE_RENDERER_DIAGNOSTICS || Log.isLoggable("DictProfile", Log.DEBUG)
            override fun now() = SystemClock.elapsedRealtimeNanos()
            override fun cpu() = Debug.threadCpuTimeNanos()
            override fun threadId() = Process.myTid().toLong()
            override fun recordsEvent(phase: String) = !LookupProfiler.summaryOnly(phase)
            override fun functions(id: Long, thread: Long, totals: List<LookupProfiler.FunctionTiming>) {
                output.execute {
                    for (timing in totals) {
                        Log.d("DictProfile", JSONObject().put("id", id).put("pid", Process.myPid())
                            .put("tid", thread).put("function", timing.name).put("calls", timing.calls)
                            .put("wall_ns", timing.wallNs).put("cpu_ns", timing.cpuNs)
                            .put("self_wall_ns", timing.selfWallNs).put("self_cpu_ns", timing.selfCpuNs)
                            .put("max_wall_ns", timing.maxWallNs).put("max_cpu_ns", timing.maxCpuNs).toString())
                    }
                }
            }
            override fun event(id: Long, phase: String, start: Long, end: Long, cpu: Long, detail: String) {
                val tid = Process.myTid()
                output.execute {
                    if (phase == "request.start") {
                        Log.d("DictProfile", JSONObject().put("id", id).put("pid", Process.myPid()).put("tid", tid)
                            .put("phase", "capture.identity").put("start_ns", start).put("end_ns", start).put("cpu_ns", 0)
                            .put("detail", "source_sha256=${BuildConfig.DICTIONARY_REPLAY_SOURCE_SHA256} schema=native-flow-v2 work_schema=coarse-work-v1 timers=detailed-aggregate overhead=unmeasured").toString())
                    }
                    Log.d("DictProfile", JSONObject().put("id", id).put("phase", phase)
                        .put("start_ns", start).put("end_ns", end).put("cpu_ns", cpu)
                        .put("tid", tid).put("pid", Process.myPid()).put("detail", detail).toString())
                }
            }
        }
        if (LookupProfiler.sink?.enabled == true) {
            Log.i("DictProfile", JSONObject().put("phase", "profiler.installed")
                .put("pid", Process.myPid()).put("schema", "native-flow-v1")
                .put("source_sha256", BuildConfig.DICTIONARY_REPLAY_SOURCE_SHA256)
                .put("native_diagnostics", BuildConfig.NATIVE_RENDERER_DIAGNOSTICS)
                .put("timer_mode", "function-summary;wall-and-thread-cpu")
                .put("start_ns", SystemClock.elapsedRealtimeNanos()).toString())
        }
    }
}
