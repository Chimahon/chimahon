package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Debug
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** Real Android text/layout checks, run by the debug bench without opening the reader or OCR. */
internal fun nativeGlossaryChecks(resolver: FontFamily.Resolver): String {
    var checks = 0
    fun verify(value: Boolean, message: String) { check(value) { "Native glossary: $message" }; checks++ }
    fun parsedContent(raw: String): StructuredNode.Element =
        GlossaryJsonReader("""{"tag":"span","content":$raw}""").read().single() as StructuredNode.Element
    verify(parsedContent("\" a \"").children == listOf(StructuredNode.Text(" a ")), "scalar whitespace changed")
    verify(parsedContent("\"\"").children.isEmpty(), "empty scalar content changed")
    verify(parsedContent("true").children == listOf(StructuredNode.Text("true")), "boolean scalar content changed")
    verify(parsedContent("12").children == listOf(StructuredNode.Text("12")), "numeric scalar content changed")
    verify(parsedContent("null").children.isEmpty(), "null scalar content changed")
    verify(parsedContent("[\"a\",[\"b\",null],2]").children ==
        listOf(StructuredNode.Text("a"), StructuredNode.Text("b"), StructuredNode.Text("2")), "nested content changed")
    verify(parsedContent("\"a\"").attributes === StructuredAttributes.Empty, "empty parser attributes not shared")
    val style = dictionaryBaseStyle(Color.White, 16, FontFamily.Default)
    fun element(tag: StructuredTag, children: List<StructuredNode>, properties: Map<String, String> = emptyMap(),
        css: Map<String, String> = emptyMap()) = StructuredNode.Element(tag, children, StructuredAttributes(style = css, properties = properties))
    fun text(value: String) = StructuredNode.Text(value)
    fun render(nodes: List<StructuredNode>, width: Int = 240, expanded: Set<StructuredNode.Element> = emptySet(),
        direction: LayoutDirection = LayoutDirection.Ltr): NativeGlossaryDocument =
        NativeGlossaryCompiler(ParsedCss.EMPTY, Density(1f), direction, resolver, Color.Gray, Color.Gray, expanded)
            .prepare(nodes, style, emptyList(), width)
    fun bounds(flow: NativeTextFlow, start: Int, end: Int): RectF {
        val path = Path()
        flow.layout.getSelectionPath(start, end, path)
        return RectF().also { path.computeBounds(it, true); it.offset(flow.x.toFloat(), flow.y.toFloat()) }
    }

    val basic = render(listOf(text("alpha "), element(StructuredTag.Bold, listOf(text("beta"))), text(" gamma")))
    val basicFlows = basic.items.filterIsInstance<NativeTextFlow>()
    verify(basicFlows.size == 1, "inline styling fragmented a flow")
    verify(basicFlows.single().layout.text.toString() == "alpha beta gamma", "inline text changed")
    val flow = basicFlows.single()
    val word = bounds(flow, 1, 2)
    verify(basic.hit(word.centerX(), word.centerY())?.lookup == "alpha", "ordinary text lookup")
    verify(basic.hit(239f, 1f) == null, "blank space looked up the last word")

    val profiles = render(listOf(
        element(StructuredTag.Span, listOf(text("red")), css = mapOf("color" to "red")),
        element(StructuredTag.Span, listOf(text("blue")), css = mapOf("color" to "blue")),
        element(StructuredTag.Span, listOf(text("red")), css = mapOf("color" to "red")),
    )).items.filterIsInstance<NativeTextFlow>().single()
    val spans = profiles.layout.text as Spanned
    verify(spans.getSpans(0, spans.length, NativePaintStyleSpan::class.java).size == 3, "a reused span moved off its earlier range")
    verify(spans.getSpans(0, spans.length, MetricAffectingSpan::class.java).isEmpty(), "paint-only styling still changed line metrics")

    val spanResources = NativeGlossaryResources(Density(1f), LayoutDirection.Ltr, resolver)
    val paragraphProfile = spanResources.profile(style)
    verify(paragraphProfile.spanFor(paragraphProfile) == null, "paragraph paint still emits a redundant span")
    verify(spanResources.profile(style.copy(background = Color.Red)).spanFor(paragraphProfile) is NativePaintStyleSpan,
        "background difference lost its span")
    val backgroundProfile = spanResources.profile(style.copy(background = Color.Red))
    verify(backgroundProfile.spanFor(backgroundProfile) is NativePaintStyleSpan,
        "paragraph background was mistaken for an existing paint background")
    val plain = render(listOf(element(StructuredTag.Div, listOf(text("first"))),
        element(StructuredTag.Div, listOf(text("second"))))).items.filterIsInstance<NativeTextFlow>()
    verify(plain.size == 2 && plain[0].layout.text.toString() == "first" && plain[1].layout.text.toString() == "second",
        "later paragraph mutated the completed text")
    verify(plain.all { it.layout.text is SpannableStringBuilder } && plain[0].layout.text !== plain[1].layout.text,
        "paragraphs lost or shared their owned span index")
    verify(plain.all { (it.layout.text as Spanned).getSpans(0, it.layout.text.length, CharacterStyle::class.java).isEmpty() },
        "ordinary paragraphs still contain redundant style spans")
    val parsed = GlossaryJsonReader("""{"tag":"span","data":{"headword":"","count":2,"flag":true,"skip":{"nested":[1]},"nil":null},"style":{"marginTop":0.5,"fontSize":"99em","color":"red","textDecorationLine":["underline","line-through"],"padding":{"invalid":true}},"content":"after"}""")
        .read().single() as StructuredNode.Element
    verify(parsed.attributes.data == mapOf("headword" to "", "count" to "2", "flag" to "true"),
        "streamed metadata lost scalar/presence attributes or retained containers")
    verify(parsed.attributes.style == mapOf("marginTop" to "0.5em", "color" to "red", "textDecoration" to "underline line-through"),
        "streamed styles changed whitelist or decoration semantics")
    verify(parsed.children == listOf(text("after")), "skipping metadata/style containers consumed following content")
    val redStyle = style.copy(color = Color(0xFFD32F2F))
    val redProfile = spanResources.profile(redStyle)
    val paintSpan = redProfile.spanFor(paragraphProfile)
    verify(paintSpan is NativePaintStyleSpan, "same font and zero shift did not use a paint-only span")
    val paint = TextPaint(paragraphProfile.resource.paint).apply { wordSpacing = 3.25f }
    checkNotNull(paintSpan).updateDrawState(paint)
    verify(paint.color == redStyle.color.toArgb(), "paint-only span lost its colour")
    verify(paint.typeface === redProfile.resource.font.paint.typeface, "paint-only span lost its font resource")
    verify(paint.wordSpacing == 3.25f, "paint-only span overwrote layout-provided word spacing")

    val boldProfile = spanResources.profile(style.copy(fontWeight = FontWeight.Bold))
    verify(boldProfile.resource.font !== paragraphProfile.resource.font, "bold did not select a different font resource")
    verify(boldProfile.spanFor(paragraphProfile) is NativeStyleSpan, "bold font change became paint-only")

    val shiftProfile = spanResources.profile(style.copy(baselineShift = BaselineShift.Superscript))
    verify(shiftProfile.resource.font === paragraphProfile.resource.font, "baseline shift unexpectedly changed the font resource")
    val shiftSpan = shiftProfile.spanFor(paragraphProfile)
    verify(shiftSpan is NativeStyleSpan, "nonzero baseline shift became paint-only")
    val shiftPaint = TextPaint()
    checkNotNull(shiftSpan).updateDrawState(shiftPaint)
    verify(shiftPaint.baselineShift != 0, "baseline shift span lost its shift")

    val ruby = element(StructuredTag.Ruby, listOf(text("\u6606\u866b"), element(StructuredTag.Rt, listOf(text("\u3053\u3093\u3061\u3085\u3046")))))
    val rubyDoc = render(listOf(text("above\n"), ruby, text(" next")))
    val rubyFlow = rubyDoc.items.filterIsInstance<NativeTextFlow>().single()
    val rubyText = rubyFlow.layout.text as Spanned
    val rubySpan = rubyText.getSpans(0, rubyText.length, NativeRubySpan::class.java).single()
    val rubyStart = rubyText.getSpanStart(rubySpan)
    val rubyLine = rubyFlow.layout.getLineForOffset(rubyStart)
    verify(rubyFlow.layout.getLineBaseline(rubyLine) - rubySpan.metrics.heightPx >= rubyFlow.layout.getLineTop(rubyLine), "annotation escapes its line box")
    val rubyBase = rubySpan.metrics.baseLayout
    val rubyX = rubyFlow.x + rubyFlow.layout.getPrimaryHorizontal(rubyStart) + rubySpan.metrics.widthPx / 2f
    val rubyY = rubyFlow.y + rubyFlow.layout.getLineBaseline(rubyLine) +
        ((rubyBase.emDescentPx ?: rubyBase.descentPx) - (rubyBase.emAscentPx ?: rubyBase.ascentPx)) / 2f
    verify(rubyDoc.hit(rubyX, rubyY)?.lookup != null, "ruby base lost recursive lookup")
    val reading = rubySpan.metrics.readingLayout
    val readingY = rubyFlow.y + rubyFlow.layout.getLineBaseline(rubyLine) - rubyBase.firstBaseline -
        rubySpan.metrics.heightPx + rubySpan.metrics.readingOffsetPx + reading.firstBaseline -
        (reading.emAscentPx ?: reading.ascentPx) / 2f
    verify(rubyDoc.hit(rubyX, readingY) == null, "ruby annotation started recursive lookup")
    val linkDoc = render(listOf(element(StructuredTag.Link, listOf(text("lookup")), mapOf("href" to "?query=target"))))
    val linkFlow = linkDoc.items.filterIsInstance<NativeTextFlow>().single()
    val linkBounds = bounds(linkFlow, 1, 2)
    verify(linkDoc.hit(linkBounds.centerX(), linkBounds.centerY())?.lookup == "target", "explicit link target")
    val rubyLink = render(listOf(element(StructuredTag.Link, listOf(ruby), mapOf("href" to "?query=target"))))
    verify("target" in rubyLink.links, "ruby-base link lost its accessibility lookup action")
    val blockLink = render(listOf(element(StructuredTag.Link,
        listOf(element(StructuredTag.Div, listOf(text("first"))), text("second")), mapOf("href" to "?query=target"))))
    verify(blockLink.items.filterIsInstance<NativeTextFlow>().all { item ->
        val rect = bounds(item, 1, 2)
        blockLink.hit(rect.centerX(), rect.centerY())?.lookup == "target"
    }, "link target lost across a block boundary")

    val row = element(StructuredTag.Tr, listOf(
        element(StructuredTag.Td, listOf(text("first cell wraps into several lines at narrow widths"))),
        element(StructuredTag.Td, listOf(ruby)),
    ))
    val table = render(listOf(element(StructuredTag.Table, listOf(row))), 180)
    val cells = table.items.filterIsInstance<NativeTextFlow>()
    verify(cells.size == 2 && cells.none { it.interactive }, "table contains lookup targets")
    verify(cells.all { it.x >= 0 && it.x + it.width <= table.width && it.y + it.height <= table.height }, "table cell escapes allocated bounds")
    verify(cells.all { table.hit(it.x + 2f, it.y + 2f) == null }, "table text starts recursive lookup")

    val details = element(StructuredTag.Details, listOf(element(StructuredTag.Summary, listOf(text("caption"))), text("hidden body")))
    val closed = render(listOf(details))
    val opened = render(listOf(details), expanded = setOf(details))
    verify("hidden body" !in closed.text && "hidden body" in opened.text, "disclosure state")
    verify(closed.hit(2f, 2f)?.disclosure === details, "disclosure header target")

    val nested = render(listOf(element(StructuredTag.Span, listOf(text("before"), element(StructuredTag.Div, listOf(text("inside"))), text("after")))))
    verify(nested.text.replace("\n", "") == "beforeinsideafter", "nested block drops or duplicates inline text")
    verify(render(listOf(text("narrow")), 0).height > 0, "zero available width crashes")

    val bitmap = Bitmap.createBitmap(rubyDoc.width, rubyDoc.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        rubyDoc.items.forEach { it.draw(canvas, paint) }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        verify(pixels.any { it ushr 24 != 0 }, "native drawing is blank")
    } finally { bitmap.recycle() }
    return "native-flow-v1 checks=$checks passed (Android StaticLayout, ruby, taps, tables, disclosures, pixels)"
}

private class NativeSpanRun(val text: String, val style: TextStyle)
private class NativeSpanFixture(val name: String, val runs: List<NativeSpanRun>)
private class NativeSpanWorkload(val name: String, val fixture: NativeSpanFixture, val width: Int)
private class NativeSpanTiming(val wallMs: Double, val cpuMs: Double)

private fun nativeSpanText(
    runs: List<NativeSpanRun>,
    resources: NativeGlossaryResources,
    paragraph: NativeTextProfile,
    paintOnly: Boolean,
    reference: Boolean = false,
): SpannableStringBuilder {
    val builder = SpannableStringBuilder()
    fun TextStyle.metrics() = toSpanStyle().copy(color = Color.Unspecified, background = Color.Unspecified,
        textDecoration = null, shadow = null)
    val paragraphMetrics = paragraph.resource.style.metrics()
    for (run in runs) {
        val start = builder.length
        builder.append(run.text)
        val profile = resources.profile(run.style)
        val span = when {
            reference && run.style.metrics() == paragraphMetrics -> {
                require(run.style.background == paragraph.resource.style.background &&
                    run.style.textDecoration == paragraph.resource.style.textDecoration &&
                    run.style.shadow == paragraph.resource.style.shadow) { "Reference fixture supports foreground colour changes only" }
                ForegroundColorSpan(run.style.color.toArgb())
            }
            reference -> NativeStyleSpan(profile)
            paintOnly -> profile.spanFor(paragraph)
            else -> NativeStyleSpan(profile)
        }
        if (span != null) builder.setSpan(span, start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return builder
}

private fun nativeSpanMedian(values: DoubleArray): Double {
    val usable = values.filter { it >= 0.0 }.sorted()
    return if (usable.isEmpty()) -1.0 else usable[usable.size / 2]
}

private fun nativeSpanBitmap(layout: Layout): Bitmap {
    val bitmap = Bitmap.createBitmap(layout.width.coerceAtLeast(1), layout.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    layout.draw(Canvas(bitmap))
    return bitmap
}

internal fun nativeSpanBenchmark(resolver: FontFamily.Resolver): String {
    var checks = 0
    fun verify(value: Boolean, message: String) { check(value) { "Native span bench: $message" }; checks++ }

    val resources = NativeGlossaryResources(Density(1f), LayoutDirection.Ltr, resolver)
    val base = dictionaryBaseStyle(Color(0xFFE6E6EA), 16, FontFamily.Default)
    val paragraph = resources.profile(base)
    val red = Color(0xFFD32F2F)
    val blue = Color(0xFF1565C0)
    val green = Color(0xFF2E7D32)
    val redStyle = base.copy(color = red)
    val blueStyle = base.copy(color = blue)
    val boldStyle = base.copy(color = blue, fontWeight = FontWeight.Bold)
    val shiftStyle = base.copy(color = red, baselineShift = BaselineShift.Superscript)
    val smallStyle = base.copy(color = green, fontSize = 12.sp)

    val colorFixture = NativeSpanFixture("color-runs", listOf(
        NativeSpanRun("Colour-only spans over ASCII and \u65e5\u672c\u8a9e text: ", base),
        NativeSpanRun("red \u8d64", redStyle),
        NativeSpanRun(", blue \u9752", blueStyle),
        NativeSpanRun(", red \u8d64 again", redStyle),
        NativeSpanRun(". Every word should wrap at the same offsets and paint the same pixels.", base),
    ))
    val mixedFixture = NativeSpanFixture("mixed-runs", listOf(
        NativeSpanRun("\u65e5\u672c\u8a9e baseline ", base),
        NativeSpanRun("bold", boldStyle),
        NativeSpanRun(" and ", base),
        NativeSpanRun("up", shiftStyle),
        NativeSpanRun(" plus ", base),
        NativeSpanRun("small", smallStyle),
        NativeSpanRun(" then ordinary ASCII text long enough to wrap over several lines at a realistic popup width.", base),
    ))

    for (fixture in listOf(colorFixture, mixedFixture)) {
        val legacy = nativeSpanText(fixture.runs, resources, paragraph, paintOnly = false)
        val paintOnly = nativeSpanText(fixture.runs, resources, paragraph, paintOnly = true)
        val legacyMetric = legacy.getSpans(0, legacy.length, MetricAffectingSpan::class.java).size
        val paintMetric = paintOnly.getSpans(0, paintOnly.length, MetricAffectingSpan::class.java).size
        val paintSpans = paintOnly.getSpans(0, paintOnly.length, NativePaintStyleSpan::class.java).size
        verify(legacyMetric == fixture.runs.size, "${fixture.name} legacy spans do not cover every run")
        if (fixture.name == "color-runs") {
            verify(paintMetric == 0, "color-only spans still affect metrics")
            verify(paintSpans == 3, "color fixture did not retain exactly its three colour changes")
        } else {
            verify(paintMetric > 0, "mixed fixture lost its metric-affecting spans")
            verify(paintSpans == 0, "mixed fixture retained redundant baseline paint spans")
        }
    }

    val workloads = listOf(
        NativeSpanWorkload("color-runs/220", colorFixture, 220),
        NativeSpanWorkload("color-runs/176", colorFixture, 176),
        NativeSpanWorkload("mixed-runs/220", mixedFixture, 220),
        NativeSpanWorkload("mixed-runs/176", mixedFixture, 176),
    )
    verify(workloads.size == 4, "sample does not build four layouts")

    val legacyTexts = workloads.map { nativeSpanText(it.fixture.runs, resources, paragraph, paintOnly = false) }
    val paintTexts = workloads.map { nativeSpanText(it.fixture.runs, resources, paragraph, paintOnly = true) }
    val referenceTexts = workloads.map { nativeSpanText(it.fixture.runs, resources, paragraph, paintOnly = false, reference = true) }
    val legacyGeometryDifferences = ArrayList<String>()
    val legacyPixelDifferences = ArrayList<String>()

    for (index in workloads.indices) {
        val workload = workloads[index]
        val legacyLayout = resources.layout(legacyTexts[index], workload.width, base)
        val paintLayout = resources.layout(paintTexts[index], workload.width, base)
        val referenceLayout = resources.layout(referenceTexts[index], workload.width, base)
        verify(legacyLayout.text.toString() == paintLayout.text.toString(), "${workload.name} changed the text")
        verify(referenceLayout.width == paintLayout.width && referenceLayout.height == paintLayout.height,
            "${workload.name} changed the layout bounds")
        verify(referenceLayout.lineCount == paintLayout.lineCount, "${workload.name} changed the line count")
        verify(referenceLayout.lineCount > 1, "${workload.name} did not wrap")
        for (line in 0 until referenceLayout.lineCount) {
            verify(referenceLayout.getLineStart(line) == paintLayout.getLineStart(line), "${workload.name} line $line start")
            verify(referenceLayout.getLineTop(line) == paintLayout.getLineTop(line), "${workload.name} line $line top")
            verify(referenceLayout.getLineBaseline(line) == paintLayout.getLineBaseline(line), "${workload.name} line $line baseline")
            verify(referenceLayout.getLineBottom(line) == paintLayout.getLineBottom(line), "${workload.name} line $line bottom")
            verify(referenceLayout.getLineLeft(line) == paintLayout.getLineLeft(line), "${workload.name} line $line left")
            verify(referenceLayout.getLineRight(line) == paintLayout.getLineRight(line), "${workload.name} line $line right")
        }
        if (legacyLayout.height != paintLayout.height || legacyLayout.lineCount != paintLayout.lineCount ||
            (0 until paintLayout.lineCount).any { legacyLayout.getLineStart(it) != paintLayout.getLineStart(it) ||
                legacyLayout.getLineRight(it) != paintLayout.getLineRight(it) }) legacyGeometryDifferences.add(workload.name)
        val legacyBitmap = nativeSpanBitmap(legacyLayout)
        val paintBitmap = nativeSpanBitmap(paintLayout)
        val referenceBitmap = nativeSpanBitmap(referenceLayout)
        try {
            verify(referenceBitmap.sameAs(paintBitmap), "${workload.name} differs from Android colour-span pixels")
            if (!legacyBitmap.sameAs(paintBitmap)) legacyPixelDifferences.add(workload.name)
        } finally {
            legacyBitmap.recycle()
            paintBitmap.recycle()
            referenceBitmap.recycle()
        }
    }

    val pairs = 25
    val warmups = 10
    fun sample(texts: List<SpannableStringBuilder>): NativeSpanTiming {
        val cpuStart = Debug.threadCpuTimeNanos()
        val wallStart = System.nanoTime()
        var heightSum = 0
        for (index in workloads.indices) {
            heightSum += resources.layout(texts[index], workloads[index].width, base).height
        }
        val wallMs = (System.nanoTime() - wallStart) / 1_000_000.0
        val cpuEnd = Debug.threadCpuTimeNanos()
        val cpuMs = if (cpuStart >= 0 && cpuEnd >= cpuStart) (cpuEnd - cpuStart) / 1_000_000.0 else -1.0
        check(heightSum >= 0)
        return NativeSpanTiming(wallMs, cpuMs)
    }

    repeat(warmups) { round ->
        if (round % 2 == 0) {
            sample(legacyTexts)
            sample(paintTexts)
        } else {
            sample(paintTexts)
            sample(legacyTexts)
        }
    }

    val legacyWall = DoubleArray(pairs)
    val legacyCpu = DoubleArray(pairs)
    val paintWall = DoubleArray(pairs)
    val paintCpu = DoubleArray(pairs)
    for (round in 0 until pairs) {
        if (round % 2 == 0) {
            val legacy = sample(legacyTexts)
            val paint = sample(paintTexts)
            legacyWall[round] = legacy.wallMs
            legacyCpu[round] = legacy.cpuMs
            paintWall[round] = paint.wallMs
            paintCpu[round] = paint.cpuMs
        } else {
            val paint = sample(paintTexts)
            val legacy = sample(legacyTexts)
            legacyWall[round] = legacy.wallMs
            legacyCpu[round] = legacy.cpuMs
            paintWall[round] = paint.wallMs
            paintCpu[round] = paint.cpuMs
        }
    }

    fun perLayout(value: Double): Double = if (value < 0) -1.0 else value / workloads.size
    return JSONObject().apply {
        put("benchmark", "native-span-microbenchmark")
        put("disclaimer", "StaticLayout construction with prebuilt spans; not popup end-to-end latency.")
        put("device", JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("sdk", Build.VERSION.SDK_INT)
            put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        })
        put("layoutsPerSample", workloads.size)
        put("warmupRounds", warmups)
        put("pairedSamples", pairs)
        put("workloads", JSONArray().apply { workloads.forEach { put(it.name) } })
        put("checks", checks)
        put("reference", "Android ForegroundColorSpan for paint-only runs; metric spans only for real metric changes")
        put("legacyGeometryDifferentWorkloads", JSONArray(legacyGeometryDifferences))
        put("legacyPixelDifferentWorkloads", JSONArray(legacyPixelDifferences))
        put("legacyMetricSpan", JSONObject().apply {
            put("wallMsPerLayout", perLayout(nativeSpanMedian(legacyWall)))
            put("cpuMsPerLayout", perLayout(nativeSpanMedian(legacyCpu)))
        })
        put("paintOnlySpan", JSONObject().apply {
            put("wallMsPerLayout", perLayout(nativeSpanMedian(paintWall)))
            put("cpuMsPerLayout", perLayout(nativeSpanMedian(paintCpu)))
        })
    }.toString(2)
}
