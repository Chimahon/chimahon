package eu.kanade.tachiyomi.ui.dictionary.bench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Debug
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeGlossaryDocument
import org.json.JSONObject

/** All prepared text flows, including ruby; excludes clearing, preparation, media and GPU work. */
internal fun nativeCorpusTextDrawBenchmark(documents: List<NativeGlossaryDocument>): JSONObject {
    val flows = documents.flatMap { document ->
        document.commandSurfaces
            .map { it.moved(-it.x, -it.y) }
    }
    check(flows.isNotEmpty())
    val bitmap = Bitmap.createBitmap(flows.maxOf { it.width }.coerceAtLeast(1),
        flows.maxOf { it.height }.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun draw(): Double {
            bitmap.eraseColor(Color.WHITE)
            val start = Debug.threadCpuTimeNanos()
            for (flow in flows) flow.draw(canvas, paint)
            return (Debug.threadCpuTimeNanos() - start) / 1e6
        }
        val first = draw()
        repeat(5) { draw() }
        val samples = List(15) { draw() }
        return JSONObject().apply {
            put("disclaimer", "Software Canvas text flows or native command streams (including their primitives); excludes preparation, media, GPU and popup presentation.")
            put("documents", documents.size)
            put("flows", flows.size)
            put("firstDrawCpuMs", first)
            put("medianCpuMs", samples.sorted()[samples.size / 2])
            put("samplesCpuMs", org.json.JSONArray(samples))
        }
    } finally {
        bitmap.recycle()
    }
}
