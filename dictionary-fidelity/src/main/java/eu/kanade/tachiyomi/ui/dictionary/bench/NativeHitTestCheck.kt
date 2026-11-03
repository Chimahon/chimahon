package eu.kanade.tachiyomi.ui.dictionary.bench

import eu.kanade.tachiyomi.ui.dictionary.compose.NativeGlossaryDocument
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
    var movedMismatches = 0
    val misses = JSONArray()
    for (probe in document.drawingHitProbes()) {
        val hit = document.hit(probe.x, probe.y)?.lookup
        if (hit != probe.movedLookup) movedMismatches++
        when (probe.category) {
            "ordinary" -> { ordinary++; if (hit == probe.expected) ordinaryHits++ }
            "ruby" -> { ruby++; if (hit == probe.expected) rubyHits++ }
            "annotation" -> { annotation++; if (hit != null) annotationHits++ }
            "static" -> { staticProbes++; if (hit != null) staticHits++ }
        }
    }
    return JSONObject().put("scope", "layout-hit-testing/not-popup-gestures")
        .put("ordinary_probes", ordinary).put("ordinary_hits", ordinaryHits)
        .put("ruby_base_probes", ruby).put("ruby_base_hits", rubyHits).put("ordinary_misses", misses)
        .put("annotation_probes", annotation).put("annotation_hits", annotationHits)
        .put("static_probes", staticProbes).put("static_hits", staticHits)
        .put("moved_result_mismatches", movedMismatches)
}
