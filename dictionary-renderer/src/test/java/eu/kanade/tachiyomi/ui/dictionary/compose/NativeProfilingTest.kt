package eu.kanade.tachiyomi.ui.dictionary.compose

import chimahon.diagnostics.LookupProfiler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NativeProfilingTest {
    @Test fun disabledSectionsExecuteDirectlyAndPreserveFailures() {
        val previous = LookupProfiler.sink
        LookupProfiler.sink = object : LookupProfiler.Sink {
            override val enabled: Boolean get() = error("Disabled section queried the sink")
            override fun now(): Long = error("Disabled section read the clock")
            override fun cpu(): Long = error("Disabled section read CPU time")
            override fun event(id: Long, phase: String, start: Long, end: Long, cpu: Long, detail: String) =
                error("Disabled section emitted an event")
        }
        try {
            var calls = 0
            val value = LookupProfiler.section(0, "native.text.layout") { ++calls; 42 }
            assertEquals(42, value)
            assertEquals(1, calls)
            assertThrows(IllegalArgumentException::class.java) {
                LookupProfiler.section(0, "native.text.layout") { throw IllegalArgumentException("expected") }
            }
        } finally {
            LookupProfiler.sink = previous
        }
    }

    @Test fun nativeFunctionsKeepSummariesWithoutPerParagraphEvents() {
        val previous = LookupProfiler.sink
        val events = ArrayList<String>()
        val functions = ArrayList<LookupProfiler.FunctionTiming>()
        var clock = 0L
        LookupProfiler.sink = object : LookupProfiler.Sink {
            override val enabled = true
            override fun now() = ++clock
            override fun cpu() = ++clock
            override fun recordsEvent(phase: String) = !LookupProfiler.summaryOnly(phase)
            override fun event(id: Long, phase: String, start: Long, end: Long, cpu: Long, detail: String) {
                events.add(phase)
            }
            override fun functions(id: Long, thread: Long, totals: List<LookupProfiler.FunctionTiming>) {
                functions.addAll(totals)
            }
        }
        try {
            LookupProfiler.section(41, "native.document.lower_layout") {
                repeat(100) { LookupProfiler.section(41, "native.text.layout") {} }
            }
            LookupProfiler.section(41, "plan.css") {}
            LookupProfiler.mark(41, "document.first_draw")
            assertFalse(events.any { it.startsWith("native.") })
            assertTrue("plan.css" in events)
            assertTrue("document.first_draw" in events)
            assertEquals(100L, functions.single { it.name == "native.text.layout" }.calls)
            assertEquals(1L, functions.single { it.name == "native.document.lower_layout" }.calls)
        } finally {
            LookupProfiler.flush()
            LookupProfiler.sink = previous
        }
    }
}
