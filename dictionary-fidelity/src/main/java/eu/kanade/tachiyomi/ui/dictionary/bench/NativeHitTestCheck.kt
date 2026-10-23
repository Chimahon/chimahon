package eu.kanade.tachiyomi.ui.dictionary.bench

import android.text.Spanned
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeGlossaryDocument
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeNoLookup
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeRubySpan
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeTextFlow
import org.json.JSONArray
import org.json.JSONObject

/** Optional real-layout hit probes; not a substitute for popup gesture/callback integration. */
internal fun nativeHitTestCheck(document: NativeGlossaryDocument): JSONObject {
    var ordinary = 0
    var ordinaryHits = 0
    var ruby = 0
    var rubyHits = 0
    var annotation = 0
    var annotationHits = 0
    var staticHits = 0
    var staticProbes = 0
    val misses = JSONArray()
    for (flow in document.items.filterIsInstance<NativeTextFlow>()) {
        if (!flow.interactive) {
            staticProbes++
            if (flow.lookupAt(flow.x + flow.width / 2f, flow.y + flow.height / 2f) != null) staticHits++
            continue
        }
        val layout = flow.layout
        val text = layout.text
        val spanned = text as? Spanned
        for (offset in text.indices) {
            if (!text[offset].isLetter()) continue
            if (spanned?.getSpans(offset, offset + 1, NativeNoLookup::class.java)?.isNotEmpty() == true) continue
            val span = spanned?.getSpans(offset, offset + 1, NativeRubySpan::class.java)?.firstOrNull()
            if (span != null && spanned.getSpanStart(span) != offset) continue
            val end = if (span != null) spanned.getSpanEnd(span) else offset + 1
            val line = layout.getLineForOffset(offset)
            val startX = layout.getPrimaryHorizontal(offset)
            val endX = if (layout.getLineForOffset(end) == line) layout.getPrimaryHorizontal(end)
                else layout.getLineRight(line)
            val x = startX + (endX - startX) * 0.25f
            val y = layout.getLineBaseline(line) - layout.paint.textSize / 2f
            val hit = flow.lookupAt(flow.x + x, flow.y + y)
            if (span == null) {
                ordinary++
                if (hit != null) ordinaryHits++
                else if (misses.length() < 12) misses.put(JSONObject().put("char", text[offset].toString())
                    .put("offset", offset).put("line", line).put("x", x).put("y", y))
            } else {
                ruby++
                if (hit != null) rubyHits++
                val metrics = span.metrics
                val reading = metrics.readingLayout
                val readingY = layout.getLineBaseline(line) - metrics.baseLayout.firstBaseline - metrics.heightPx +
                    metrics.readingOffsetPx + reading.firstBaseline - (reading.emAscentPx ?: reading.ascentPx) / 2f
                annotation++
                if (flow.lookupAt(flow.x + (startX + endX) / 2f, flow.y + readingY) != null) annotationHits++
            }
        }
    }
    return JSONObject().put("scope", "layout-hit-testing/not-popup-gestures")
        .put("ordinary_probes", ordinary).put("ordinary_hits", ordinaryHits)
        .put("ruby_base_probes", ruby).put("ruby_base_hits", rubyHits).put("ordinary_misses", misses)
        .put("annotation_probes", annotation).put("annotation_hits", annotationHits)
        .put("static_probes", staticProbes).put("static_hits", staticHits)
}
