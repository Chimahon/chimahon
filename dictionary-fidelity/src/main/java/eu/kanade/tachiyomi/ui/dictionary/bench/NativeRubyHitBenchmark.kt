package eu.kanade.tachiyomi.ui.dictionary.bench

import android.os.Debug
import android.text.Layout
import android.text.Spanned
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeLookupLink
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeNoLookup
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeRubySpan
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeTextFlow
import eu.kanade.tachiyomi.ui.dictionary.compose.lookupWordAt
import org.json.JSONObject

internal data class NativeRubyHitProbe(val flow: NativeTextFlow, val x: Float, val y: Float)

/** Test-only frozen pre-optimization ruby hit path; never used by the app. */
private fun referenceRubyLookup(probe: NativeRubyHitProbe): String? {
    val flow = probe.flow
    val layout = flow.layout
    if (!flow.interactive || probe.x < flow.x || probe.y < flow.y ||
        probe.x >= flow.x + layout.width || probe.y >= flow.y + layout.height) return null
    val x = probe.x - flow.x
    val y = probe.y - flow.y
    val line = layout.getLineForVertical(y.toInt())
    if (x < layout.getLineLeft(line) || x >= layout.getLineRight(line)) return null
    val text = layout.text as Spanned
    var baseOffset: Int? = null
    for (ruby in text.getSpans(0, text.length, NativeRubySpan::class.java)) {
        val start = text.getSpanStart(ruby)
        val rubyLine = layout.getLineForOffset(start)
        val metrics = ruby.metrics
        val primary = layout.getPrimaryHorizontal(start)
        val left = if (layout.getParagraphDirection(rubyLine) == Layout.DIR_RIGHT_TO_LEFT) primary - metrics.widthPx else primary
        val baseline = layout.getLineBaseline(rubyLine).toFloat()
        val reading = metrics.readingLayout
        val readingBaseline = baseline - metrics.baseLayout.firstBaseline - metrics.heightPx +
            metrics.readingOffsetPx + reading.firstBaseline
        val readingLeft = left + metrics.inlineOffsetPx + (metrics.widthPx - reading.advancePx) / 2f
        if (x >= readingLeft && x < readingLeft + reading.advancePx &&
            y >= readingBaseline - (reading.emAscentPx ?: reading.ascentPx) &&
            y < readingBaseline + (reading.emDescentPx ?: reading.descentPx)) return null
        val base = metrics.baseLayout
        if (x >= left && x < left + metrics.widthPx &&
            y >= baseline - (base.emAscentPx ?: base.ascentPx) &&
            y < baseline + (base.emDescentPx ?: base.descentPx)) {
            baseOffset = start + base.characterOffset(x - left - metrics.inlineOffsetPx - (metrics.widthPx - base.advancePx) / 2f)
        }
    }
    baseOffset?.let { offset ->
        if (text.getSpans(offset, offset + 1, NativeNoLookup::class.java).isNotEmpty()) return null
        return text.getSpans(offset, offset + 1, NativeLookupLink::class.java).firstOrNull()?.target
            ?: lookupWordAt(text.toString(), offset)
    }
    var offset = layout.getOffsetForHorizontal(line, x).coerceIn(0, (text.length - 1).coerceAtLeast(0))
    if (offset > layout.getLineStart(line)) {
        val previous = Character.offsetByCodePoints(text, offset, -1)
        val a = layout.getPrimaryHorizontal(previous)
        val b = layout.getPrimaryHorizontal(offset)
        if (x >= minOf(a, b) && x < maxOf(a, b)) offset = previous
    }
    if (text.getSpans(offset, (offset + 1).coerceAtMost(text.length), NativeNoLookup::class.java).isNotEmpty()) return null
    if (text.getSpans(offset, (offset + 1).coerceAtMost(text.length), NativeRubySpan::class.java).isNotEmpty()) return null
    return text.getSpans(offset, (offset + 1).coerceAtMost(text.length), NativeLookupLink::class.java).firstOrNull()?.target
        ?: lookupWordAt(text.toString(), offset)
}

internal fun nativeRubyHitBenchmark(probes: List<NativeRubyHitProbe>): JSONObject {
    var mismatches = 0
    for (probe in probes) if (referenceRubyLookup(probe) != probe.flow.lookupAt(probe.x, probe.y)) mismatches++
    fun measure(reference: Boolean): Long {
        val start = Debug.threadCpuTimeNanos()
        for (probe in probes) {
            if (reference) referenceRubyLookup(probe) else probe.flow.lookupAt(probe.x, probe.y)
        }
        return Debug.threadCpuTimeNanos() - start
    }
    val old = ArrayList<Long>(5)
    val current = ArrayList<Long>(5)
    // Alternate ordering; compare identical probes in the same process, not APK startup times.
    repeat(5) { index ->
        if (index % 2 == 0) { old.add(measure(true)); current.add(measure(false)) }
        else { current.add(measure(false)); old.add(measure(true)) }
    }
    return JSONObject().put("scope", "ruby-hit-function/thread-cpu/not-popup-latency")
        .put("probes", probes.size).put("result_mismatches", mismatches)
        .put("reference_ns_per_probe", if (probes.isEmpty()) 0 else old.sorted()[2] / probes.size)
        .put("retained_ns_per_probe", if (probes.isEmpty()) 0 else current.sorted()[2] / probes.size)
}
