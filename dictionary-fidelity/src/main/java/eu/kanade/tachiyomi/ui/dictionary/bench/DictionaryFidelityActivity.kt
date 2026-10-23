package eu.kanade.tachiyomi.ui.dictionary.bench

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Spanned
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import coil3.Image as CoilImage
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.svg.SvgDecoder
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import eu.kanade.tachiyomi.ui.dictionary.compose.BASE_CSS_VARIABLES
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeGlossaryCompiler
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeGlossaryDocument
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeGlossaryImage
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeGlossaryRect
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeRubySpan
import eu.kanade.tachiyomi.ui.dictionary.compose.NativeTextFlow
import eu.kanade.tachiyomi.ui.dictionary.compose.StructuredEntry
import eu.kanade.tachiyomi.ui.dictionary.compose.StructuredNode
import eu.kanade.tachiyomi.ui.dictionary.compose.dictionaryBaseStyle
import eu.kanade.tachiyomi.ui.dictionary.compose.parseCssColor2
import eu.kanade.tachiyomi.ui.dictionary.compose.parseDictionaryCss
import eu.kanade.tachiyomi.ui.dictionary.compose.parseStructuredGlossary
import eu.kanade.tachiyomi.ui.dictionary.compose.plainTextNodes
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Debug-only paired BODY captures. Its separate process never changes production WebView settings. */
class DictionaryFidelityActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var browser: WebView? = null
    private var bundle: ZipFile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runId = intent.getStringExtra("run_id").orEmpty()
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "Missing or invalid fidelity run_id" }
        val root = File(filesDir, "native-fidelity/$runId")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scope.launch {
            val report = JSONObject().put("run_id", runId).put("backend", "native-flow-v1")
                .put("capture_geometry", "css-dpr-v2")
                .put("scope", "glossary-body/software-canvas").put("done", false).put("results", JSONArray())
            fun save() { File(root, "status.json").writeText(report.toString(), Charsets.UTF_8) }
            try {
                val archive = ZipFile(File(root, "bundle.zip"))
                bundle = archive
                val input = JSONObject(archive.getInputStream(archive.getEntry("input.json")).bufferedReader().use { it.readText() })
                require(input.getInt("protocol") == 1)
                val fontFile = File(root, "font.otf")
                val fontSha = MessageDigest.getInstance("SHA-256").digest(fontFile.readBytes()).joinToString("") { "%02x".format(it) }
                require(fontSha == input.getString("font_sha256")) { "Font checksum mismatch" }
                report.put("font_sha256", fontSha).put("input_sha256", input.getString("input_sha256"))
                    .put("sdk", Build.VERSION.SDK_INT).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("apk_updated", packageManager.getPackageInfo(packageName, 0).lastUpdateTime)
                // Do NOT call WebView.setDataDirectorySuffix here. App.onCreate already does it for every
                // non-main process (App.kt: `if (packageName != process) WebView.setDataDirectorySuffix(process)`),
                // which initialises WebViewFactory before this activity runs. A second call throws
                // IllegalStateException("Can't set data directory suffix: WebView already initialized").
                // The ":dictionaryFidelity" suffix from the manifest already isolates this process's WebView data.
                WebView.enableSlowWholeDocumentDraw()
                val web = WebView(this@DictionaryFidelityActivity)
                browser = web
                web.settings.javaScriptEnabled = true
                web.settings.textZoom = 100
                web.settings.allowFileAccess = false
                web.settings.allowContentAccess = false
                web.settings.setSupportZoom(false)
                web.settings.useWideViewPort = true
                // Serve the reference page from disk over a real origin instead of loadDataWithBaseURL.
                // loadDataWithBaseURL percent-encodes the whole document into a data: URL; this corpus is
                // CJK-heavy, so every glyph becomes 9 bytes and the encoded form exceeds what WebView
                // accepts. WebView then SILENTLY TRUNCATES: a 167 KB document arrived with only ~1.9 KB
                // of DOM, the render script was simply absent, and __rendered stayed false with no
                // __renderError because the code that sets it never executed.
                val site = File(root, "site").apply { mkdirs() }
                web.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val url = request.url
                        if (url.host != "fidelity.invalid") return blocked()
                        if (url.path == "/font.otf") {
                            return WebResourceResponse("font/otf", "utf-8", 200, "OK", NO_STORE, fontFile.inputStream())
                        }
                        // Dictionary media staged into the bundle, so the reference page can load the
                        // same image the native side will draw. Emulator cannot read the host tree.
                        if (url.path.orEmpty().startsWith("/$MEDIA_PREFIX/")) {
                            val archive = bundle
                            val entry = url.path.orEmpty().removePrefix("/")
                            val zipEntry = archive?.getEntry(entry)
                            if (archive != null && zipEntry != null) {
                                return WebResourceResponse(mimeOf(entry), "utf-8", 200, "OK", NO_STORE,
                                    BufferedInputStream(archive.getInputStream(zipEntry)))
                            }
                            return blocked()
                        }
                        val target = File(site, url.path.orEmpty().removePrefix("/").ifEmpty { "index.html" })
                        // Reject any path that escapes the per-run site directory.
                        if (!target.canonicalPath.startsWith(site.canonicalPath) || !target.isFile) return blocked()
                        return WebResourceResponse(mimeOf(target.name), "utf-8", 200, "OK", NO_STORE, BufferedInputStream(FileInputStream(target)))
                    }

                    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                }
                val frame = FrameLayout(this@DictionaryFidelityActivity)
                frame.addView(web)
                setContentView(frame)
                report.put("webview", WebView.getCurrentWebViewPackage()?.versionName ?: "unknown")
                val resolver = createFontFamilyResolver(this@DictionaryFidelityActivity)
                val family = FontFamily(Typeface.createFromFile(fontFile))
                                val cases = input.getJSONArray("cases")
                report.put("total", cases.length())
                save()
                if (intent.getBooleanExtra("native_prepare_bench_only", false)) {
                    // Preparation benchmark over the SAME staged corpus, with no perfetto tracing attached.
                    // This isolates preparation, not the phone popup's cold end-to-end latency.
                    val bench = nativePrepareBenchmark(this@DictionaryFidelityActivity, cases, fontFile, resources.displayMetrics.density)
                    File(filesDir, "native-prepare-bench.json").writeText(bench, Charsets.UTF_8)
                    Log.i("DictFidelity", "native prepare benchmark written to files/native-prepare-bench.json")
                    report.put("prepare_benchmark", JSONObject(bench))
                    report.put("done", true)
                    save()
                    return@launch
                }
                for (index in 0 until cases.length()) {
                    val case = cases.getJSONObject(index)
                    val result = JSONObject().put("id", case.getString("id")).put("file", "case-${index.toString().padStart(3, '0')}")
                    try {
                        withTimeout(45_000L) {
                            val viewportWidth = ceil(case.getDouble("width") * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                            val viewportHeight = resources.displayMetrics.heightPixels
                            web.layoutParams = FrameLayout.LayoutParams(viewportWidth, viewportHeight)
                            web.measure(exact(viewportWidth), exact(viewportHeight))
                            web.layout(0, 0, viewportWidth, viewportHeight)
                            val token = "$runId:$index"
                            val htmlFile = case.getString("html_file")
                            require(htmlFile.matches(Regex("case-[0-9]+\\.html")))
                            val html = archive.getInputStream(archive.getEntry(htmlFile)).bufferedReader().use { it.readText() }
                                .replace("<head>", "<head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><script>window.__fidelityToken=${JSONObject.quote(token)};</script>")
                                .replace("</body>", "<script>document.fonts.load('14px RefCJK').then(()=>document.fonts.ready).then(()=>requestAnimationFrame(()=>requestAnimationFrame(()=>window.__fidelityFontReady=true))).catch(e=>window.__fidelityError=String(e));</script></body>")
                            // Written to disk and loaded from an intercepted origin; see the webViewClient comment above.
                            File(site, "index.html").writeText(html, Charsets.UTF_8)
                            web.loadUrl("https://fidelity.invalid/index.html")
                            var geometry = awaitGeometry(web, token)
                            // innerWidth is an integer CSS viewport width, not a pixel scale.
                            // Dividing by it shrank both native fonts and reference crop bounds.
                            val scale = geometry.getDouble("dpr")
                            require(scale.isFinite() && scale > 0.0)
                            // #entries has shipping padding outside its specified content width.
                            // Expose the full glossary body without changing its CSS or wrapping.
                            val requiredViewport = ceil((geometry.getDouble("x") + geometry.getDouble("width")) * scale).toInt()
                            if (requiredViewport > viewportWidth) {
                                web.layoutParams = FrameLayout.LayoutParams(requiredViewport, viewportHeight)
                                web.measure(exact(requiredViewport), exact(viewportHeight))
                                web.layout(0, 0, requiredViewport, viewportHeight)
                                awaitVisualState(web, index.toLong())
                                geometry = awaitGeometry(web, token)
                            }
                            // Kept before the native render: a native failure must not take the
                            // reference's own geometry down with it. The reference is the ground
                            // truth, and a case that fails natively still has to show what the
                            // browser computed.
                            result.put("webview", geometry)
                            val width = ceil(geometry.getDouble("width") * scale).toInt().coerceAtLeast(1)
                            val height = ceil(geometry.getDouble("height") * scale).toInt().coerceAtLeast(1)
                            checkSize(width, height)
                            awaitVisualState(web, index.toLong())
                            val prefix = result.getString("file")
                            if (index == 0 && intent.getBooleanExtra("headword_geometry_check", false)) {
                                val rawHeader = javascript(web, """JSON.stringify((() => {
                                    const e = document.querySelector('.headword');
                                    if (!e) throw new Error('Missing WebView headword');
                                    const r = e.getBoundingClientRect();
                                    const term = e.querySelector('.headword-term');
                                    if (!term) throw new Error('Missing WebView term');
                                    const s = getComputedStyle(term), base = term.cloneNode(true);
                                    base.querySelectorAll('rt,rp').forEach(n => n.remove());
                                    return {x:r.x,y:r.y,width:r.width,height:r.height,
                                        fontSize:parseFloat(s.fontSize),color:s.color,text:e.textContent,
                                        expression:base.textContent,
                                        reading:Array.from(term.querySelectorAll('rt')).map(n=>n.textContent).join('')};
                                })())""")
                                val header = JSONObject(JSONTokener(rawHeader).nextValue() as String)
                                val headerWidth = ceil(header.getDouble("width") * scale).toInt()
                                val headerHeight = ceil(header.getDouble("height") * scale).toInt()
                                checkSize(headerWidth, headerHeight)
                                bitmap(headerWidth, headerHeight, case.getBoolean("isDark"), File(root, "headword-webview.png")) { canvas ->
                                    canvas.translate((-header.getDouble("x") * scale).toFloat(), (-header.getDouble("y") * scale).toFloat())
                                    web.draw(canvas)
                                }
                                val verdict = headwordGeometryCheck(this@DictionaryFidelityActivity, fontFile, root,
                                    header.getString("expression"), header.getString("reading"), scale.toFloat(),
                                    header.getDouble("fontSize").toFloat(), parseCssColor2(header.getString("color")) ?: Color.Black)
                                report.put("headword_check", verdict.put("webview", header))
                            }
                            bitmap(width, height, case.getBoolean("isDark"), File(root, "$prefix-webview.png")) { canvas ->
                                canvas.translate((-geometry.getDouble("x") * scale).toFloat(), (-geometry.getDouble("y") * scale).toFloat())
                                web.draw(canvas)
                            }
                            val variablesJson = geometry.getJSONObject("variables")
                            val variables = variablesJson.keys().asSequence().associateWith { variablesJson.getString(it) }
                            val nativeMeta = withContext(Dispatchers.Default) {
                                val raw = case.getString("glossary")
                                val nodes = when (val parsed = parseStructuredGlossary(raw)) {
                                    is StructuredEntry.Tree -> parsed.nodes
                                    StructuredEntry.PlainText -> plainTextNodes(raw)
                                }
                                val style = dictionaryBaseStyle(parseCssColor2(geometry.getString("color")) ?: Color.Black,
                                    case.getInt("fontSize"), family).copy(lineHeight = geometry.getDouble("lineHeight").toFloat().sp)
                                val native = NativeGlossaryCompiler(parseDictionaryCss(case.getString("css"), variables),
                                    Density(scale.toFloat(), 1f), if (geometry.getString("direction") == "rtl") LayoutDirection.Rtl else LayoutDirection.Ltr,
                                    resolver, parseCssColor2(variables["secondary"].orEmpty()) ?: Color.Gray,
                                    parseCssColor2(variables["border"].orEmpty()) ?: Color.Gray).prepare(nodes, style, emptyList(), width)
                                checkSize(native.width, native.height.coerceAtLeast(1))
                                if (intent.getBooleanExtra("hit_geometry_check", false)) {
                                    result.put("hit_check", nativeHitTestCheck(native))
                                }
                                // Dictionary media is staged into the bundle under media/<prefix>/<path>, so the
                                // native side draws the same bytes as the reference. Missing or undecodable media
                                // fails the case rather than scoring an incomplete bitmap.
                                val undrawable = ArrayList<String>()
                                // Decoded before the bitmap lambda because that lambda is not suspending.
                                // Raster formats go through BitmapFactory; SVG has no Android decoder, so it
                                // goes through Coil, which is already a project dependency (libs.bundles.coil
                                // includes coil-svg) and produces an Image that draws to a software Canvas.
                                val media = ArrayList<Pair<NativeGlossaryImage, StagedMedia>>()
                                for (item in native.images) {
                                    val path = item.source.attributes.properties["path"] ?: item.source.attributes.data["path"]
                                    val entry = if (path == null) null else "$MEDIA_PREFIX/$prefix/$path"
                                    val decoded = entry?.let { decodeStagedMedia(it, item.width, item.height) }
                                    if (decoded == null) undrawable.add(path ?: "<no path>") else media += item to decoded
                                }
                                bitmap(native.width, native.height.coerceAtLeast(1), case.getBoolean("isDark"), File(root, "$prefix-native.png")) { canvas ->
                                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                                    for (item in native.items) {
                                        if (item !is NativeGlossaryImage) item.draw(canvas, paint)
                                    }
                                    val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                                    for ((item, staged) in media) {
                                        canvas.save()
                                        canvas.translate(item.x.toFloat(), item.y.toFloat())
                                        if (staged.width > 0 && staged.height > 0) {
                                            // Match AsyncImage's centered Fit and base.css object-fit: contain.
                                            val fit = minOf(item.width.toFloat() / staged.width, item.height.toFloat() / staged.height)
                                            canvas.translate((item.width - staged.width * fit) / 2f,
                                                (item.height - staged.height * fit) / 2f)
                                            canvas.scale(fit, fit)
                                        }
                                        staged.draw(canvas, imagePaint)
                                        canvas.restore()
                                    }
                                }
                                if (undrawable.isNotEmpty()) {
                                    val verdict = verifyImageGeometry(native)
                                    result.put("image_geometry", verdict)
                                    error("Image-overlay case: geometry $verdict; ${undrawable.size} media file(s) not decodable to a software bitmap: ${undrawable.joinToString()}")
                                }
                                JSONObject().put("width", native.width).put("height", native.height)
                                    .put("images", JSONArray(media.map { (item, staged) ->
                                        JSONObject().put("x", item.x).put("y", item.y).put("w", item.width).put("h", item.height)
                                            .put("intrinsicWidth", staged.width).put("intrinsicHeight", staged.height)
                                    }))
                                    .put("rects", JSONArray(native.items.filterIsInstance<NativeGlossaryRect>()
                                        .filter { it.height > 0 && it.width > 0 }
                                        .map { JSONObject().put("x", it.x).put("y", it.y).put("w", it.width).put("h", it.height) }))
                                    .put("texts", JSONArray(native.items.filterIsInstance<NativeTextFlow>().map {
                                        val ruby = JSONArray()
                                        val spanned = it.layout.text as? Spanned
                                        if (spanned != null) {
                                            for (span in spanned.getSpans(0, spanned.length, NativeRubySpan::class.java)) {
                                                val start = spanned.getSpanStart(span)
                                                val line = it.layout.getLineForOffset(start)
                                                ruby.put(JSONObject().put("line", line)
                                                    .put("base", span.metrics.baseText)
                                                    .put("reading", span.metrics.readingText)
                                                    .put("baseWeight", span.metrics.baseLayout.style.fontWeight?.weight ?: 400)
                                                    .put("readingWeight", span.metrics.readingLayout.style.fontWeight?.weight ?: 400)
                                                    .put("readingColor", span.metrics.readingLayout.style.color.toArgb())
                                                    .put("readingSize", span.metrics.readingLayout.style.fontSize.value)
                                                    .put("top", it.layout.getLineTop(line))
                                                    .put("baseLine", it.layout.getLineBaseline(line))
                                                    .put("lineHeight", it.layout.getLineBottom(line) - it.layout.getLineTop(line))
                                                    .put("height", span.metrics.heightPx)
                                                    .put("baseOffset", span.metrics.baseOffsetPx)
                                                    .put("readOffset", span.metrics.readingOffsetPx)
                                                    .put("baseFirst", span.metrics.baseLayout.firstBaseline)
                                                    .put("readFirst", span.metrics.readingLayout.firstBaseline)
                                                    .put("baseEmA", span.metrics.baseLayout.emAscentPx ?: -1f)
                                                    .put("readEmD", span.metrics.readingLayout.emDescentPx ?: -1f)
                                                    .put("baseH", span.metrics.baseLayout.heightPx)
                                                    .put("readH", span.metrics.readingLayout.heightPx)
                                                    .put("baseFont", span.metrics.baseLayout.debugFonts)
                                                    .put("readFont", span.metrics.readingLayout.debugFonts))
                                            }
                                        }
                                        JSONObject().put("x", it.x).put("y", it.y).put("w", it.width).put("h", it.height)
                                            .put("text", it.layout.text.toString())
                                            .put("fs", it.layout.paint.textSize)
                                            .put("locales", it.layout.paint.textLocales.toLanguageTags())
                                            .put("punctuationWidths", JSONObject(listOf("」。", "」「", "。。", "（（", "ああ").associateWith { pair ->
                                                it.layout.paint.measureText(pair)
                                            }))
                                            .put("lineInfo", JSONArray((0 until it.layout.lineCount).map { line ->
                                                JSONObject().put("s", it.layout.getLineStart(line)).put("e", it.layout.getLineEnd(line))
                                                    .put("w", Math.round(it.layout.getLineWidth(line)))
                                                    .put("t", it.layout.text.subSequence(it.layout.getLineStart(line), it.layout.getLineEnd(line)).toString())
                                            }))
                                            .put("lines", it.layout.lineCount)
                                            .put("line0", it.layout.getLineBottom(0) - it.layout.getLineTop(0))
                                            .put("tops", JSONArray((0 until it.layout.lineCount).map { line ->
                                                JSONObject().put("t", it.layout.getLineTop(line))
                                                    .put("b", it.layout.getLineBaseline(line))
                                                    .put("h", it.layout.getLineBottom(line) - it.layout.getLineTop(line))
                                            }))
                                            .put("ruby", ruby)
                                    }))
                            }
                            result.put("ok", true).put("webview", geometry).put("native", nativeMeta).put("density", scale)
                        }
                    } catch (error: Exception) {
                        if (error is CancellationException && !scopeIsActive()) throw error
                        // A bare "timed out" does not say WHICH readiness gate never flipped. Sample all
                        // four on failure so the report names the cause instead of only the symptom.
                        browser?.let { web -> runCatching { withTimeout(4_000L) { javascript(web, GATE_PROBE) } }
                            .onSuccess { state -> result.put("gate_state", state) } }
                        result.put("ok", false).put("error", error.stackTraceToString().take(4000))
                    }
                    report.getJSONArray("results").put(result)
                    Log.i("DictFidelity", "${index + 1}/${cases.length()} ${case.getString("id")} ok=${result.optBoolean("ok")}")
                    save()
                }
                report.put("done", true)
            } catch (error: Exception) {
                report.put("done", true).put("error", error.stackTraceToString().take(4000))
                Log.e("DictFidelity", "Fidelity capture failed", error)
            } finally {
                save()
                browser?.destroy()
                browser = null
                bundle?.close()
                bundle = null
                finish()
            }
        }
    }

    private fun scopeIsActive() = scope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true

    private suspend fun awaitGeometry(web: WebView, token: String): JSONObject {
        while (true) {
            val raw = javascript(web, "(window.__fidelityToken === ${JSONObject.quote(token)} ? $GEOMETRY : null)")
            if (raw != "null") {
                val result = JSONObject(JSONTokener(raw).nextValue() as String)
                if (result.has("error")) error(result.getString("error"))
                if (result.optBoolean("ready")) return result
            }
            delay(75)
        }
    }

    private suspend fun javascript(web: WebView, script: String): String = suspendCancellableCoroutine { continuation ->
        web.evaluateJavascript(script) { if (continuation.isActive) continuation.resume(it) }
    }

    private suspend fun awaitVisualState(web: WebView, id: Long): Unit = suspendCancellableCoroutine { continuation ->
        web.postVisualStateCallback(id, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) { if (continuation.isActive) continuation.resume(Unit) }
        })
    }

    /**
     * Checks the placement arithmetic `image()` performed, which is the fragile part of the image path:
     * a `minOf(cursor.width, wanted)` clamp or a wrong em conversion silently resizes every image.
     * Reports problems instead of throwing so a bad rect is named, not merely excluded.
     */
    private fun verifyImageGeometry(doc: NativeGlossaryDocument): String {
        val problems = ArrayList<String>()
        for ((index, image) in doc.images.withIndex()) {
            if (image.width <= 0 || image.height <= 0) problems += "img#$index size ${image.width}x${image.height} is not positive"
            if (image.x < 0 || image.y < 0) problems += "img#$index origin ${image.x},${image.y} is negative"
            if (image.x + image.width > doc.width) problems += "img#$index overflows width (${image.x}+${image.width} > ${doc.width})"
            if (image.y + image.height > doc.height) problems += "img#$index overflows height (${image.y}+${image.height} > ${doc.height})"
            // A clamp is legal but must be reported: it means the declared width exceeded the column.
            val declared = image.source.attributes.properties["preferredWidth"] ?: image.source.attributes.data["width"]
            if (declared != null && image.width == doc.width) problems += "img#$index clamped to full column width $doc.width"
        }
        val rects = doc.images.joinToString(";") { "${it.x},${it.y},${it.width},${it.height}" }
        val header = "${doc.images.size} image(s) at [$rects] in ${doc.width}x${doc.height}"
        return if (problems.isEmpty()) "OK $header" else "PROBLEMS $header :: " + problems.joinToString("; ")
    }

    /** A staged dictionary image decoded to something drawable on a software Canvas. */
    private sealed interface StagedMedia {
        val width: Int
        val height: Int
        fun draw(canvas: Canvas, paint: Paint)

        class Raster(override val width: Int, override val height: Int, private val bitmap: Bitmap) : StagedMedia {
            override fun draw(canvas: Canvas, paint: Paint) {
                canvas.drawBitmap(bitmap, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
            }
        }

        class Vector(override val width: Int, override val height: Int, private val image: CoilImage) : StagedMedia {
            override fun draw(canvas: Canvas, paint: Paint) = image.draw(canvas)
        }
    }

    private val mediaLoader: ImageLoader by lazy(LazyThreadSafetyMode.NONE) {
        ImageLoader.Builder(this).components { add(SvgDecoder.Factory()) }.build()
    }

    /** Decodes a staged dictionary image, or returns null if the format cannot be rasterised here. */
    private suspend fun decodeStagedMedia(entryName: String, targetWidth: Int, targetHeight: Int): StagedMedia? {
        val archive = bundle ?: return null
        val entry = archive.getEntry(entryName) ?: return null
        val bytes = archive.getInputStream(entry).use { it.readBytes() }
        if (!entryName.endsWith(".svg", ignoreCase = true)) {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            return StagedMedia.Raster(bitmap.width, bitmap.height, bitmap)
        }
        val request = ImageRequest.Builder(this)
            .data(bytes)
            .size(targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
            .build()
        val image = mediaLoader.execute(request).image ?: return null
        return StagedMedia.Vector(image.width, image.height, image)
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun checkSize(width: Int, height: Int) {
        require(width in 1..16384 && height in 1..16384 && width.toLong() * height <= 12_000_000L) { "Oversize capture ${width}x$height; refusing to truncate or exhaust the heap" }
    }
    private fun bitmap(width: Int, height: Int, dark: Boolean, file: File, draw: (Canvas) -> Unit) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (dark) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            draw(canvas)
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    override fun onDestroy() { scope.cancel(); browser?.destroy(); browser = null; bundle?.close(); bundle = null; super.onDestroy() }

    companion object {
        private const val MEDIA_PREFIX = "media"
        private val NO_STORE = mapOf("Cache-Control" to "no-store")

        private fun mimeOf(name: String): String = when {
            name.endsWith(".html") || name.endsWith(".htm") -> "text/html"
            name.endsWith(".js") -> "application/javascript"
            name.endsWith(".css") -> "text/css"
            name.endsWith(".json") -> "application/json"
            name.endsWith(".otf") -> "font/otf"
            name.endsWith(".svg") -> "image/svg+xml"
            name.endsWith(".png") -> "image/png"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".webp") -> "image/webp"
            name.endsWith(".avif") -> "image/avif"
            name.endsWith(".woff2") -> "font/woff2"
            else -> "application/octet-stream"
        }

        /** Read on the failure path only: reports which readiness gate is stuck instead of just "timed out". */
        private val GATE_PROBE = """
            (() => {
              const f = document.fonts;
              return JSON.stringify({
                readyState: document.readyState,
                rendered: !!window.__rendered,
                renderError: window.__renderError ? String(window.__renderError).slice(0, 300) : null,
                fidelityError: window.__fidelityError ? String(window.__fidelityError).slice(0, 300) : null,
                fontReady: !!window.__fidelityFontReady,
                fontsStatus: f ? f.status : 'no-fonts-api',
                fontsSize: f ? f.size : -1,
                refCjkCheck: f ? f.check('14px RefCJK') : null,
                structuredRoots: document.querySelectorAll('.structured-content').length,
                glossRoots: document.querySelectorAll('.gloss-content').length,
                bodyLen: (document.body ? document.body.innerHTML.length : -1),
                rafTicks: window.__rafSeen === undefined ? -1 : window.__rafSeen
              });
            })()
        """.trimIndent()

        private val GEOMETRY = """
            (() => {
              if (!window.__rendered) return window.__renderError ? JSON.stringify({error:window.__renderError}) : null;
              if (window.__fidelityError) return JSON.stringify({error:window.__fidelityError});
              if (!window.__fidelityFontReady) return null;
              if (document.fonts.status !== 'loaded') return null;
              if (!document.fonts.check('14px RefCJK')) return JSON.stringify({error:'Reference font failed to load'});
              const roots = Array.from(document.querySelectorAll('.structured-content'));
              const candidates = roots.length ? roots : Array.from(document.querySelectorAll('.gloss-content'));
              if (candidates.length !== 1) return JSON.stringify({error:'Expected exactly one glossary body, found '+candidates.length});
              const el = candidates[0], r = el.getBoundingClientRect(), s = getComputedStyle(el);
              if (r.width <= 0 || r.height <= 0) return JSON.stringify({error:'Empty reference content box'});
              const variables = {}, props = getComputedStyle(document.documentElement);
              // Older WebViews omit custom properties from CSSStyleDeclaration enumeration.
              const names = new Set(${JSONArray(BASE_CSS_VARIABLES.keys.map { "--$it" })});
              for (const name of props) if (name.startsWith('--')) names.add(name);
              for (const name of names) {
                const value = props.getPropertyValue(name).trim();
                if (value) variables[name.slice(2)] = value;
              }
              // Debug probe: the computed layout of every table, so a grid-table case can be
              // checked against the reference instead of against a desktop browser's guess.
              const tables = Array.from(document.querySelectorAll('table')).map((t, i) => {
                const cs = getComputedStyle(t), tr = t.getBoundingClientRect();
                return {
                  i, attrs: Array.from(t.attributes).map(a => a.name),
                  display: cs.display, gtc: cs.gridTemplateColumns, gtr: cs.gridTemplateRows,
                  af: cs.gridAutoFlow, w: +tr.width.toFixed(1), h: +tr.height.toFixed(1),
                  fs: cs.fontSize, lh: cs.lineHeight, ta: cs.tableLayout,
                  rows: Array.from(t.querySelectorAll('tr')).map((r, j) => {
                    const c = getComputedStyle(r), b = r.getBoundingClientRect();
                    return { j, display: c.display, x: +(b.x - tr.x).toFixed(1), y: +(b.y - tr.y).toFixed(1),
                             w: +b.width.toFixed(1), h: +b.height.toFixed(1) };
                  }),
                  cells: Array.from(t.querySelectorAll('td')).map((td, k) => {
                    const c = getComputedStyle(td), b = td.getBoundingClientRect();
                    // What the browser thinks this cell's min-content width is, measured through
                    // the same cascade in an offscreen shrink-to-fit box.
                    const probe = (() => {
                      const host = document.createElement('div');
                      host.style.cssText = 'position:absolute;visibility:hidden;left:-99999px;top:0;width:min-content;';
                      host.style.font = c.font; host.style.padding = c.padding; host.style.border = c.border;
                      host.style.boxSizing = c.boxSizing; host.style.textAlign = c.textAlign;
                      host.style.wordBreak = c.wordBreak; host.style.lineBreak = c.lineBreak;
                      host.innerHTML = td.innerHTML;
                      document.body.appendChild(host);
                      const w = host.getBoundingClientRect().width;
                      host.remove();
                      return +w.toFixed(1);
                    })();
                    return { k, x: +(b.x - tr.x).toFixed(1), y: +(b.y - tr.y).toFixed(1),
                             w: +b.width.toFixed(1), h: +b.height.toFixed(1), minw: probe,
                             gc: c.gridColumn, gr: c.gridRow, disp: c.display,
                             wm: c.writingMode, fs: c.fontSize, lh: c.lineHeight,
                             txt: (td.innerText || '').slice(0, 12) };
                  })
                };
              });
              // TEMPORARY layout probe: exact WebView boxes for ruby line-box research.
              const baselines = Array.from(el.querySelectorAll('div.gloss-sc-div')).map((d, i) => {
                const m = document.createElement('span');
                m.style.cssText = 'display:inline-block;width:0;height:0;vertical-align:baseline;';
                d.appendChild(m);
                const q = d.getBoundingClientRect(), b = m.getBoundingClientRect();
                m.remove();
                return { i, y: +(q.y - r.y).toFixed(2), h: +q.height.toFixed(2),
                  base: +(b.bottom - r.y).toFixed(2), cls: d.className, txt: (d.textContent||'').trim().slice(0,12) };
              });
              const lineRects = (() => {
                const out = [];
                const walk = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
                let node;
                while ((node = walk.nextNode())) {
                  const txt = node.textContent;
                  if (!txt.trim()) continue;
                  const range = document.createRange(); range.selectNodeContents(node);
                  for (const q of range.getClientRects()) {
                    out.push({ y: +(q.y - r.y).toFixed(2), h: +q.height.toFixed(2), w: +q.width.toFixed(2),
                      t: txt.trim().slice(0, 8) });
                  }
                }
                return out;
              })();
              const boxes = [el, ...el.querySelectorAll('div, ruby, rt')].map((b, i) => {
                const q = b.getBoundingClientRect(), c = getComputedStyle(b);
                const target = b.tagName === 'RT' ? b : b.tagName === 'RUBY' ? b.querySelector(':scope > span') : null;
                let baseline = null;
                if (target) {
                  const marker = document.createElement('span');
                  marker.style.cssText = 'display:inline-block;width:0;height:0;vertical-align:baseline;';
                  target.appendChild(marker);
                  baseline = +(marker.getBoundingClientRect().bottom - r.y).toFixed(4);
                  marker.remove();
                }
                return { i, tag: b.tagName, cls: b.className, txt: (b.textContent || '').trim().slice(0, 16),
                  x: +(q.x - r.x).toFixed(2), y: +(q.y - r.y).toFixed(2), w: +q.width.toFixed(2), h: +q.height.toFixed(2),
                  fs: c.fontSize, fw: c.fontWeight, color: c.color, ls: c.letterSpacing,
                  lh: c.lineHeight, mt: c.marginTop, mb: c.marginBottom, disp: c.display, baseline };
              });
              const images = Array.from(el.querySelectorAll('img')).map(img => {
                const b = img.getBoundingClientRect(), c = getComputedStyle(img);
                return { x: +(b.x - r.x).toFixed(2), y: +(b.y - r.y).toFixed(2),
                  w: +b.width.toFixed(2), h: +b.height.toFixed(2), fit: c.objectFit,
                  intrinsicWidth: img.naturalWidth, intrinsicHeight: img.naturalHeight };
              });
              const punctuationWidths = {};
              const punctuation = document.createElement('span');
              punctuation.style.cssText = 'position:absolute;visibility:hidden;white-space:pre;width:max-content;';
              el.appendChild(punctuation);
              for (const pair of ['」。', '」「', '。。', '（（', 'ああ']) {
                punctuation.textContent = pair;
                punctuation.style.textSpacingTrim = '';
                const normal = punctuation.getBoundingClientRect().width;
                punctuation.style.textSpacingTrim = 'space-all';
                punctuationWidths[pair] = { normal, spaceAll: punctuation.getBoundingClientRect().width };
              }
              punctuation.remove();
              return JSON.stringify({ready:true,x:r.x,y:r.y,width:r.width,height:r.height,innerWidth:innerWidth,
                dpr:devicePixelRatio,color:s.color,lineHeight:parseFloat(s.lineHeight)||parseFloat(s.fontSize)*1.4,
                direction:s.direction,variables,tables,boxes,baselines,lineRects,images,
                textSpacingTrim:s.textSpacingTrim, punctuationWidths, lineBreak:s.lineBreak, wordBreak:s.wordBreak,
                language:el.closest('[lang]')?.lang || navigator.language});
            })()
        """.trimIndent()
    }
}
