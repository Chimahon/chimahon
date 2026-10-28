package eu.kanade.tachiyomi.ui.dictionary.bench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Debug
import android.text.BoringLayout
import android.text.Layout
import android.text.SpannableStringBuilder
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeGlossaryDocument
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeTextFlow
import org.json.JSONObject

/** Paired drawing CPU for actual plain corpus lines; excludes preparation and popup/GPU work. */
internal fun nativePlainTextDrawBenchmark(documents: List<NativeGlossaryDocument>): String {
    val lines = documents.flatMap { document ->
        document.items.filterIsInstance<NativeTextFlow>().mapNotNull { flow ->
            val layout = flow.layout
            if (layout !is BoringLayout || layout.text !is String ||
                layout.alignment != Layout.Alignment.ALIGN_NORMAL) return@mapNotNull null
            val metrics = checkNotNull(BoringLayout.isBoring(layout.text, layout.paint))
            // Freeze the already-resolved CSS line box; only text ownership differs.
            metrics.ascent = layout.getLineAscent(0)
            metrics.descent = layout.getLineDescent(0)
            metrics.top = metrics.ascent
            metrics.bottom = metrics.descent
            val wrapped = BoringLayout.make(SpannableStringBuilder(layout.text), layout.paint,
                layout.width, layout.alignment, 1f, 0f, metrics, false)
            check(wrapped.height == layout.height)
            layout to wrapped
        }
    }
    check(lines.isNotEmpty()) { "No plain normal-aligned BoringLayout lines in this corpus" }
    val width = lines.maxOf { it.first.width }.coerceAtLeast(1)
    val height = lines.maxOf { it.first.height }.coerceAtLeast(1)
    val directBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val wrappedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        val directCanvas = Canvas(directBitmap)
        val wrappedCanvas = Canvas(wrappedBitmap)
        for ((direct, wrapped) in lines) {
            directBitmap.eraseColor(Color.WHITE)
            wrappedBitmap.eraseColor(Color.WHITE)
            direct.draw(directCanvas)
            wrapped.draw(wrappedCanvas)
            check(directBitmap.sameAs(wrappedBitmap)) { "Plain/wrapped drawing pixels differ: ${direct.text}" }
        }
        fun draw(wrapped: Boolean): Double {
            val bitmap = if (wrapped) wrappedBitmap else directBitmap
            val canvas = if (wrapped) wrappedCanvas else directCanvas
            bitmap.eraseColor(Color.WHITE)
            val start = Debug.threadCpuTimeNanos()
            for (line in lines) (if (wrapped) line.second else line.first).draw(canvas)
            return (Debug.threadCpuTimeNanos() - start) / 1e6
        }
        val direct = ArrayList<Double>()
        val wrapped = ArrayList<Double>()
        repeat(20) { index ->
            val first = draw(index % 2 == 0)
            val second = draw(index % 2 != 0)
            if (index >= 5) {
                wrapped.add(if (index % 2 == 0) first else second)
                direct.add(if (index % 2 == 0) second else first)
            }
        }
        fun median(values: List<Double>) = values.sorted()[values.size / 2]
        return JSONObject().apply {
            put("benchmark", "plain-text-drawing-paired")
            put("disclaimer", "Software Canvas CPU for eligible corpus lines only; not preparation, GPU or popup latency.")
            put("documents", documents.size)
            put("lines", lines.size)
            put("pixelMismatches", 0)
            put("iterations", direct.size)
            put("warmups", 5)
            put("directMedianCpuMs", median(direct))
            put("wrappedMedianCpuMs", median(wrapped))
            put("directCpuMs", org.json.JSONArray(direct))
            put("wrappedCpuMs", org.json.JSONArray(wrapped))
        }.toString(2)
    } finally {
        directBitmap.recycle()
        wrappedBitmap.recycle()
    }
}
