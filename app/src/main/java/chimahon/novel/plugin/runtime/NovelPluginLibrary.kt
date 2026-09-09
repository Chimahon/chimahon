package chimahon.novel.plugin.runtime

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.webkit.CookieManager
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.Elements
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Provides JavaScript libraries and APIs to plugins.
 * Based on iReader's JSLibraryProvider pattern.
 *
 * Sets up:
 * - require() function for module loading
 * - fetch() API backed by OkHttp
 * - cheerio HTML parsing backed by Jsoup
 * - storage API
 * - Browser polyfills (URL, URLSearchParams, etc.)
 */
internal class NovelPluginLibrary(
    context: Context,
    private val pluginId: String,
    private val siteUrl: String? = null,
) {
    private val networkHelper: NetworkHelper = Injekt.get()
    private val client = networkHelper.client
    private val context = context.applicationContext

    // Prefer the bundled LNReader-compatible Cheerio implementation. The Jsoup bridge remains
    // available as a fallback if the asset cannot be loaded or evaluated on a device.
    private val useNativeCheerio: Boolean = true

    // Persistent storage per plugin via SharedPreferences
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("jsplugin_storage_$pluginId", Context.MODE_PRIVATE)
    }

    // In-memory cache backed by SharedPreferences
    private val storage = ConcurrentHashMap<String, String>()

    // Element cache for cheerio bridge
    private val elementCache = ConcurrentHashMap<Int, Any>()
    private var handleCounter = 0

    /**
     * Setup all native bindings and the JS runtime environment.
     */
    suspend fun setup(runtime: QuickJs) {
        setupLogging(runtime)
        setupFetch(runtime)
        setupTimers(runtime)
        setupCrypto(runtime)
        setupCheerio(runtime)
        setupStorage(runtime)
        setupCacheManagement(runtime)

        // Inject the protobuf runtime bundle, then the minimal JS runtime (polyfills + require).
        // require('cheerio') prefers the native bundle once globalThis.__useNativeCheerio is set.
        val runtimeJs =
            buildString {
                append(loadProtobufJsBundle())
                append('\n')
                append("globalThis.__useNativeCheerio = false;\n")
                append(getMinimalRuntime())
            }
        runtime.evaluate<Any?>(runtimeJs, "runtime.js", asModule = false)

        // Optionally eval the real-cheerio bundle AFTER the polyfills above (it relies on Array.from,
        // Object.fromEntries, etc.). Isolated in its own try/catch so a parse/eval failure degrades
        // gracefully to the Jsoup wrapper instead of killing the runtime. Only flip the flag once the
        // bundle actually exported globalThis.__realCheerio.
        var nativeCheerioActive = false
        if (useNativeCheerio) {
            val bundle = loadCheerioBundle()
            if (bundle.isNotEmpty()) {
                nativeCheerioActive =
                    try {
                        runtime.evaluate<Any?>(bundle, "cheerio.bundle.js", asModule = false)
                        val loaded = (runtime.evaluate<Any?>("!!globalThis.__realCheerio") as? Boolean) == true
                        if (loaded) {
                            runtime.evaluate<Any?>("globalThis.__useNativeCheerio = true;")
                        }
                        loaded
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "[$pluginId] cheerio bundle eval failed; using Jsoup wrapper" }
                        false
                    }
            }
        }

        logcat(LogPriority.DEBUG) {
            "[$pluginId] JSLibraryProvider setup complete (nativeCheerio=$nativeCheerioActive)"
        }
    }

    // ============ Cache Management ============

    private suspend fun setupCacheManagement(runtime: QuickJs) {
        runtime.function("__clearCheerioCache") { _ ->
            elementCache.clear()
            handleCounter = 0
            true
        }

        runtime.function("__trimCheerioCache") { _ ->
            trimCache(10)
            true
        }
    }

    private fun loadProtobufJsBundle(): String =
        try {
            val bundle =
                context.assets
                    .open("js/vendor/protobuf.min.js")
                    .bufferedReader(StandardCharsets.UTF_8)
                    .use { it.readText() }

            buildString {
                append("(function(){\n")
                append("var module = { exports: {} };\n")
                append("var exports = module.exports;\n")
                append(bundle)
                append("\n;globalThis.protobufjs = module.exports;\n")
                append("globalThis.parseProto = function(proto) { return module.exports.parse(proto); };\n")
                append("})();\n")
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "[$pluginId] Failed to load protobufjs bundle from assets" }
            ""
        }

    /**
     * Load the bundled real-cheerio IIFE from assets. The bundle string is read once and cached in
     * memory (it is ~330 KB); each QuickJS runtime still re-evaluates it since JS heaps are not
     * shared across runtimes. Returns "" if the asset is missing so the caller falls back to the
     * Jsoup wrapper. The bundle sets globalThis.__realCheerio = { load }.
     */
    private fun loadCheerioBundle(): String {
        cachedCheerioBundle?.let { return it }
        return synchronized(cheerioBundleLock) {
            cachedCheerioBundle?.let { return it }
            val loaded =
                try {
                    context.assets
                        .open("js/vendor/cheerio.bundle.js")
                        .bufferedReader(StandardCharsets.UTF_8)
                        .use { it.readText() }
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "[$pluginId] Failed to load cheerio bundle from assets" }
                    ""
                }
            cachedCheerioBundle = loaded
            loaded
        }
    }

    // ============ Logging ============

    private suspend fun setupLogging(runtime: QuickJs) {
        runtime.function("__log") { args ->
            val level = args.getOrNull(0)?.toString() ?: "LOG"
            val message = args.drop(1).joinToString(" ") { it?.toString() ?: "null" }
            val priority =
                when (level) {
                    "ERROR" -> LogPriority.ERROR
                    "WARN" -> LogPriority.WARN
                    "DEBUG" -> LogPriority.DEBUG
                    else -> LogPriority.INFO
                }
            logcat(priority) { "[$pluginId] $message" }
        }
    }

    // ============ Fetch API ============

    private suspend fun setupFetch(runtime: QuickJs) {
        // Return JSON string to avoid Map conversion issues
        @Suppress("UNCHECKED_CAST")
        runtime.asyncFunction("__fetch") { args ->
            val url = args.getOrNull(0)?.toString() ?: ""
            val init = args.getOrNull(1) as? Map<String, Any?>
            val result = doFetch(url, init)
            // Convert to JSON string for reliable JS parsing
            kotlinx.serialization.json.Json
                .encodeToString(result)
        }
    }

    // ============ Timers ============

    private suspend fun setupTimers(runtime: QuickJs) {
        // Backs the JS setTimeout/setInterval polyfills. Capped so a long timer can't hold
        // the evaluate() job queue past the executePluginMethod poll timeout.
        runtime.asyncFunction("__delay") { args ->
            val ms =
                (args.getOrNull(0) as? Number)?.toLong()
                    ?: args
                        .getOrNull(0)
                        ?.toString()
                        ?.toDoubleOrNull()
                        ?.toLong()
                    ?: 0L
            if (ms > 0) {
                kotlinx.coroutines.delay(ms.coerceAtMost(MAX_TIMER_DELAY_MS))
            }
            true
        }
    }

    // ============ Crypto API ============

    private suspend fun setupCrypto(runtime: QuickJs) {
        runtime.function("__aesGcmDecrypt") { args ->
            val keyB64 = args.getOrNull(0)?.toString().orEmpty()
            val ivB64 = args.getOrNull(1)?.toString().orEmpty()
            val cipherB64 = args.getOrNull(2)?.toString().orEmpty()

            if (keyB64.isBlank() || ivB64.isBlank() || cipherB64.isBlank()) {
                return@function ""
            }

            try {
                val keyRaw = Base64.decode(keyB64, Base64.DEFAULT)
                val iv = Base64.decode(ivB64, Base64.DEFAULT)
                val cipherBytes = Base64.decode(cipherB64, Base64.DEFAULT)

                val keyBytes = ByteArray(32)
                val copyLength = minOf(keyRaw.size, keyBytes.size)
                System.arraycopy(keyRaw, 0, keyBytes, 0, copyLength)

                val keySpec = SecretKeySpec(keyBytes, "AES")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val gcmSpec = GCMParameterSpec(128, iv)
                cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

                val plainBytes = cipher.doFinal(cipherBytes)
                Base64.encodeToString(plainBytes, Base64.NO_WRAP)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "[$pluginId] AES-GCM decrypt failed" }
                ""
            }
        }
    }

    @kotlinx.serialization.Serializable
    private data class FetchResponse(
        val ok: Boolean,
        val status: Int,
        val statusText: String,
        val url: String,
        val text: String,
        val headers: Map<String, String>,
        val error: String? = null,
    )

    private suspend fun doFetch(
        url: String,
        init: Map<String, Any?>?,
    ): FetchResponse =
        withContext(Dispatchers.IO) {
            val normalizedUrl = resolveFetchUrl(url)

            try {
                logcat(LogPriority.DEBUG) { "[$pluginId] Fetching: $normalizedUrl" }

                val method = (init?.get("method") as? String)?.uppercase() ?: "GET"
                val headersMap = extractHeaders(init).toMutableMap()

                // Bridge cookies solved in WebView challenges into JS plugin fetch requests.
                if (headersMap.keys.none { it.equals("cookie", ignoreCase = true) }) {
                    try {
                        val webViewCookies = CookieManager.getInstance().getCookie(normalizedUrl)
                        if (!webViewCookies.isNullOrBlank()) {
                            headersMap["Cookie"] = webViewCookies

                            // Browser-like behavior for Laravel/Inertia stacks:
                            // if X-XSRF-TOKEN isn't explicitly provided, derive it from XSRF-TOKEN cookie.
                            if (headersMap.keys.none { it.equals("x-xsrf-token", ignoreCase = true) }) {
                                val xsrfToken =
                                    webViewCookies
                                        .split(';')
                                        .asSequence()
                                        .map { it.trim() }
                                        .firstOrNull { it.startsWith("XSRF-TOKEN=") }
                                        ?.substringAfter('=')
                                        ?.let { token ->
                                            try {
                                                URLDecoder.decode(token, StandardCharsets.UTF_8.name())
                                            } catch (_: Exception) {
                                                token
                                            }
                                        }

                                if (!xsrfToken.isNullOrBlank()) {
                                    headersMap["X-XSRF-TOKEN"] = xsrfToken
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Ignore CookieManager failures and continue without WebView cookies.
                    }
                }

                val body = extractBody(init, headersMap)

                // Check if this is binary data encoded as base64
                val isBinaryBase64 =
                    headersMap.entries.any {
                        it.key.equals("x-binary-base64", ignoreCase = true) &&
                            it.value.equals("true", ignoreCase = true)
                    }

                val requestBuilder = Request.Builder().url(normalizedUrl)

                // Build headers - skip certain headers that OkHttp handles
                val headersBuilder = Headers.Builder()
                headersMap.forEach { (key, value) ->
                    val lowerKey = key.lowercase()
                    if (lowerKey !in
                        listOf("accept-encoding", "host", "connection", "content-length", "x-binary-base64")
                    ) {
                        headersBuilder.add(key, value)
                    }
                }
                requestBuilder.headers(headersBuilder.build())

                // Set body for non-GET requests
                if (method != "GET" && body != null) {
                    if (isBinaryBase64) {
                        try {
                            val binaryData = Base64.decode(body, Base64.DEFAULT)
                            requestBuilder.method(
                                method,
                                binaryData.toRequestBody("application/grpc-web+proto".toMediaType()),
                            )
                        } catch (e: Exception) {
                            requestBuilder.method(method, body.toRequestBody(detectContentType(headersMap)))
                        }
                    } else {
                        requestBuilder.method(method, body.toRequestBody(detectContentType(headersMap)))
                    }
                } else if (method != "GET") {
                    requestBuilder.method(method, "".toRequestBody(null))
                }

                val response = client.newCall(requestBuilder.build()).execute()
                response.use { resp ->
                    val responseBody = resp.body
                    val declaredLength = responseBody.contentLength()
                    require(declaredLength <= MAX_FETCH_BYTES) { "Plugin response exceeds $MAX_FETCH_BYTES bytes" }
                    val responseBytes =
                        responseBody.byteStream().use { input ->
                            val output =
                                java.io.ByteArrayOutputStream(
                                    if (declaredLength in
                                        1..MAX_FETCH_BYTES
                                    ) {
                                        declaredLength.toInt()
                                    } else {
                                        8192
                                    },
                                )
                            val buffer = ByteArray(8192)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_FETCH_BYTES) { "Plugin response exceeds $MAX_FETCH_BYTES bytes" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                    val responseText =
                        if (isBinaryBase64) {
                            Base64.encodeToString(responseBytes, Base64.NO_WRAP)
                        } else {
                            String(responseBytes, StandardCharsets.UTF_8)
                        }

                    // Keep WebView cookie store in sync with OkHttp responses.
                    try {
                        val cookieManager = CookieManager.getInstance()
                        resp.headers.values("Set-Cookie").forEach { setCookie ->
                            cookieManager.setCookie(resp.request.url.toString(), setCookie)
                        }
                        cookieManager.flush()
                    } catch (_: Exception) {
                        // Non-fatal: plugin fetch should still proceed if cookie sync fails.
                    }

                    // Build response headers map
                    val responseHeaders = mutableMapOf<String, String>()
                    resp.headers.forEach { (name, value) ->
                        responseHeaders[name.lowercase()] = value
                    }
                    if (isBinaryBase64) {
                        responseHeaders["x-binary-base64"] = "true"
                    }

                    FetchResponse(
                        ok = resp.isSuccessful,
                        status = resp.code,
                        statusText = resp.message,
                        url = resp.request.url.toString(),
                        text = responseText,
                        headers = responseHeaders,
                    )
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "[$pluginId] Fetch error: $normalizedUrl" }
                FetchResponse(
                    ok = false,
                    status = 0,
                    statusText = "Network Error",
                    url = normalizedUrl,
                    text = "",
                    headers = emptyMap(),
                    error = e.message ?: "Unknown error",
                )
            }
        }

    private fun resolveFetchUrl(rawUrl: String): String {
        val value = rawUrl.trim()
        if (value.isBlank()) return value

        val base = siteUrl.orEmpty().trim().trimEnd('/')
        val baseHttpUrl = base.toHttpUrlOrNull()

        if (value.startsWith("http://") || value.startsWith("https://")) {
            if (base.isNotBlank() && value.startsWith(base)) {
                val suffix = value.removePrefix(base)
                if (suffix.isNotEmpty() &&
                    !suffix.startsWith("/") &&
                    !suffix.startsWith("?") &&
                    !suffix.startsWith("#")
                ) {
                    return "$base/${suffix.removePrefix("/")}"
                }
            }

            val absolute = value.toHttpUrlOrNull()
            if (absolute != null && baseHttpUrl != null) {
                val hostLooksInvalidForPublicSite = !absolute.host.contains('.') && absolute.host != "localhost"
                if (hostLooksInvalidForPublicSite) {
                    val relative =
                        buildString {
                            append(absolute.encodedPath)
                            absolute.encodedQuery?.let { append('?').append(it) }
                            absolute.encodedFragment?.let { append('#').append(it) }
                        }.ifBlank { "/" }
                    return baseHttpUrl.resolve(relative)?.toString() ?: value
                }
            }

            return value
        }

        if (baseHttpUrl != null) {
            val relative = if (value.startsWith('/')) value else "/$value"
            return baseHttpUrl.resolve(relative)?.toString() ?: (base + relative)
        }

        return if (value.startsWith('/')) value else "/$value"
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractHeaders(init: Map<String, Any?>?): Map<String, String> {
        val headers = init?.get("headers")
        return when (headers) {
            is Map<*, *> ->
                headers
                    .mapNotNull { (k, v) ->
                        if (k != null && v != null) k.toString() to v.toString() else null
                    }.toMap()
            else -> emptyMap()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractBody(
        init: Map<String, Any?>?,
        headers: Map<String, String>,
    ): String? {
        val body = init?.get("body") ?: return null
        return when (body) {
            is String -> body
            is Map<*, *> -> {
                // FormData-like object
                val data = (body as Map<String, Any?>)["data"] as? Map<String, Any?>
                data?.entries?.joinToString("&") { (k, v) ->
                    val values =
                        when (v) {
                            is List<*> -> v.filterNotNull()
                            else -> listOf(v)
                        }
                    values.joinToString("&") {
                        "${java.net.URLEncoder.encode(
                            k,
                            "UTF-8",
                        )}=${java.net.URLEncoder.encode(it.toString(), "UTF-8")}"
                    }
                }
            }
            else -> body.toString()
        }
    }

    private fun detectContentType(headers: Map<String, String>): okhttp3.MediaType? {
        val contentType = headers.entries.find { it.key.equals("content-type", ignoreCase = true) }?.value
        return contentType?.toMediaType() ?: "application/x-www-form-urlencoded".toMediaType()
    }

    // ============ Cheerio API ============

    private suspend fun setupCheerio(runtime: QuickJs) {
        // Load HTML and return document handle
        runtime.function("__cheerioLoad") { args ->
            val html = args.getOrNull(0)?.toString() ?: ""
            cheerioLoad(html)
        }

        // Select elements
        runtime.function("__cheerioSelect") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            val selectorArg = args.getOrNull(1)
            val selector =
                when {
                    selectorArg is String -> selectorArg
                    selectorArg == null -> ""
                    else -> {
                        val str = selectorArg.toString()
                        if (str.contains("@") && str.contains(".")) "" else str
                    }
                }
            cheerioSelect(handle, selector)
        }

        // Get text content
        runtime.function("__cheerioText") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            cheerioText(handle)
        }

        // Get HTML content
        runtime.function("__cheerioHtml") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            cheerioHtml(handle)
        }

        // Get attribute
        runtime.function("__cheerioAttr") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            val name = args.getOrNull(1)?.toString() ?: ""
            cheerioAttr(handle, name)
        }

        // Get length
        runtime.function("__cheerioLength") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            cheerioLength(handle)
        }

        // Get element at index
        runtime.function("__cheerioEq") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            val index = args.getOrNull(1)?.toString()?.toIntOrNull() ?: 0
            cheerioEq(handle, index)
        }

        // First element
        runtime.function("__cheerioFirst") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            cheerioFirst(handle)
        }

        // Last element
        runtime.function("__cheerioLast") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            cheerioLast(handle)
        }

        // Parent element
        runtime.function("__cheerioParent") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            cheerioParent(handle)
        }

        // Children
        runtime.function("__cheerioChildren") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            val selector = args.getOrNull(1)?.toString() ?: ""
            cheerioChildren(handle, selector)
        }

        // Next sibling
        runtime.function("__cheerioNext") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            cheerioNext(handle)
        }

        // Previous sibling
        runtime.function("__cheerioPrev") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            cheerioPrev(handle)
        }

        // Has class
        runtime.function("__cheerioHasClass") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            val className = args.getOrNull(1)?.toString() ?: ""
            cheerioHasClass(handle, className)
        }

        // Filter
        runtime.function("__cheerioFilter") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            // Handle both string selectors and potential function/object arguments
            val selectorArg = args.getOrNull(1)
            when {
                selectorArg == null -> cheerioFilter(handle, "")
                selectorArg is String -> cheerioFilter(handle, selectorArg)
                // If it's a JsObject or function, we can't use it as CSS selector.
                // Return the original handle unchanged since filter functions aren't supported.
                selectorArg.toString().startsWith("com.dokar.quickjs.binding.JsObject") -> {
                    handle
                }
                else -> cheerioFilter(handle, selectorArg.toString())
            }
        }

        // Is
        runtime.function("__cheerioIs") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            val selector = args.getOrNull(1)?.toString() ?: ""
            cheerioIs(handle, selector)
        }

        // Get DOM tree as JSON for toArray() support
        runtime.function("__cheerioDomTree") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            cheerioDomTree(handle)
        }

        // Get all attributes as JSON object string for .attribs support
        runtime.function("__cheerioGetAttrs") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            cheerioGetAttrs(handle)
        }

        // Remove elements from DOM
        runtime.function("__cheerioRemove") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            cheerioRemove(handle)
        }

        // Insert HTML after elements
        runtime.function("__cheerioAfter") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            val html = args.getOrNull(1)?.toString() ?: ""
            cheerioAfter(handle, html)
        }

        // Insert HTML before elements
        runtime.function("__cheerioBefore") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            val html = args.getOrNull(1)?.toString() ?: ""
            cheerioBefore(handle, html)
        }

        // Append HTML inside elements
        runtime.function("__cheerioAppend") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            val html = args.getOrNull(1)?.toString() ?: ""
            cheerioAppend(handle, html)
        }

        // Prepend HTML inside elements
        runtime.function("__cheerioPrepend") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            val html = args.getOrNull(1)?.toString() ?: ""
            cheerioPrepend(handle, html)
        }

        // Empty (remove children)
        runtime.function("__cheerioEmpty") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            cheerioEmpty(handle)
        }

        // Wrap each matched element with the given HTML structure
        runtime.function("__cheerioWrap") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            val html = args.getOrNull(1)?.toString() ?: ""
            cheerioWrap(handle, html)
        }

        // Outer HTML of first element ($.html(el) support)
        runtime.function("__cheerioOuterHtml") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            cheerioOuterHtml(handle)
        }

        // Lowercase tag name of first element
        runtime.function("__cheerioTagName") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            cheerioTagName(handle)
        }

        // Child nodes (including text/comment nodes) as JSON for contents() support
        runtime.function("__cheerioContents") { args ->
            val handle = args.getOrNull(0)?.toString()?.toIntOrNull() ?: -1
            cheerioContents(handle)
        }
    }

    private fun cheerioLoad(html: String): Int {
        val doc = Jsoup.parse(html)
        val handle = ++handleCounter
        elementCache[handle] = doc
        return handle
    }

    private fun cheerioSelect(
        parentHandle: Int,
        selector: String,
    ): Int {
        if (selector.isBlank()) return parentHandle // No selector = return parent itself
        val parent = elementCache[parentHandle] ?: return -1
        val normalizedSelector = normalizeSelectorForJsoup(selector)
        val elements =
            try {
                when (parent) {
                    is Document -> parent.select(normalizedSelector)
                    is Element -> parent.select(normalizedSelector)
                    is Elements ->
                        Elements().also { results ->
                            parent.forEach { results.addAll(it.select(normalizedSelector)) }
                        }
                    else -> Elements()
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) {
                    "[$pluginId] cheerioSelect: invalid selector '$selector' (normalized='$normalizedSelector')"
                }
                Elements()
            }
        val handle = ++handleCounter
        elementCache[handle] = elements
        return handle
    }

    private fun cheerioText(handle: Int): String {
        // Cheerio .text() concatenates raw text nodes without whitespace normalization; Jsoup
        // .text() normalizes, which breaks plugins that split on newline sequences.
        fun elementText(el: Element): String {
            val tagName = el.tagName().lowercase()
            return if (tagName == "script" || tagName == "style") {
                el.data()
            } else {
                el.wholeText()
            }
        }
        return when (val el = elementCache[handle]) {
            is Document -> el.wholeText()
            is Element -> elementText(el)
            is Elements -> el.joinToString("") { elementText(it) }
            else -> ""
        }
    }

    private fun cheerioHtml(handle: Int): String {
        val el = elementCache[handle]
        val result =
            when (el) {
                is Document -> el.body()?.html() ?: ""
                is Element -> {
                    // For script/style tags, use data() to get raw content
                    val tagName = el.tagName().lowercase()
                    if (tagName == "script" || tagName == "style") {
                        val data = el.data()
                        data
                    } else {
                        el.html()
                    }
                }
                is Elements -> {
                    val first = el.firstOrNull()
                    if (first != null) {
                        val tagName = first.tagName().lowercase()
                        if (tagName == "script" || tagName == "style") {
                            val data = first.data()
                            data
                        } else {
                            first.html()
                        }
                    } else {
                        ""
                    }
                }
                else -> ""
            }
        return result
    }

    /**
     * Convert a Jsoup element to a DOM-like JSON tree (htmlparser2/domhandler format)
     * for use with toArray(). Returns JSON string representing array of DOM nodes.
     * Each node has: type, name, children, data, attribs
     */
    private fun cheerioDomTree(handle: Int): String {
        val el = elementCache[handle] ?: return "[]"
        val elements =
            when (el) {
                is Document -> listOf(el)
                is Element -> listOf(el)
                is Elements -> el.toList()
                else -> emptyList()
            }
        val sb = StringBuilder()
        sb.append('[')
        elements.forEachIndexed { idx, elem ->
            if (idx > 0) sb.append(',')
            // Live handle so $(node) from toArray() resolves back to the real element
            val nodeHandle = ++handleCounter
            elementCache[nodeHandle] = elem
            appendDomNode(sb, elem, nodeHandle)
        }
        sb.append(']')
        return sb.toString()
    }

    private fun appendDomNode(
        sb: StringBuilder,
        element: Element,
        handle: Int? = null,
    ) {
        sb.append("{\"type\":\"tag\",")
        if (handle != null) {
            sb.append("\"_h\":").append(handle).append(',')
        }
        val tag = escapeJsonString(element.tagName().lowercase())
        sb.append("\"name\":")
        sb.append('"').append(tag).append('"')
        sb.append(",\"tagName\":\"").append(tag).append('"')
        sb.append(",\"attribs\":{")
        element.attributes().forEachIndexed { i, attr ->
            if (i > 0) sb.append(',')
            sb.append('"').append(escapeJsonString(attr.key)).append("\":\"")
            sb.append(escapeJsonString(attr.value)).append('"')
        }
        sb.append("},\"children\":[")
        // Include both text nodes and element nodes as children
        var childIdx = 0
        for (node in element.childNodes()) {
            if (childIdx > 0) sb.append(',')
            when (node) {
                is TextNode -> {
                    val text = node.wholeText
                    if (text.isNotEmpty()) {
                        sb.append("{\"type\":\"text\",\"data\":\"")
                        sb.append(escapeJsonString(text))
                        sb.append("\",\"children\":[]}")
                        childIdx++
                    }
                }
                is Element -> {
                    appendDomNode(sb, node)
                    childIdx++
                }
                is org.jsoup.nodes.DataNode -> {
                    // For script/style content
                    val data = node.wholeData
                    if (data.isNotEmpty()) {
                        sb.append("{\"type\":\"text\",\"data\":\"")
                        sb.append(escapeJsonString(data))
                        sb.append("\",\"children\":[]}")
                        childIdx++
                    }
                }
            }
        }
        sb.append("]}")
    }

    private fun escapeJsonString(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u%04x".format(c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun cheerioAttr(
        handle: Int,
        name: String,
    ): String? =
        when (val el = elementCache[handle]) {
            is Element -> el.attr(name).takeIf { it.isNotEmpty() }
            is Elements -> el.firstOrNull()?.attr(name)?.takeIf { it.isNotEmpty() }
            else -> null
        }

    /**
     * Returns all attributes of the element as a JSON object string, e.g. {"href":"/foo","class":"bar"}.
     * Used to support cheerio's `.attribs` property (htmlparser2/dom-style attribute access).
     */
    private fun cheerioGetAttrs(handle: Int): String {
        val element: Element? =
            when (val el = elementCache[handle]) {
                is Element -> el
                is Elements -> el.firstOrNull()
                is Document -> el // Document extends Element in Jsoup
                else -> null
            }
        if (element == null) return "{}"
        val attrs = element.attributes()
        if (attrs.size() == 0) return "{}"
        val sb = StringBuilder("{")
        var first = true
        for (attr in attrs) {
            if (!first) sb.append(",")
            first = false
            sb.append("\"").append(escapeJsonString(attr.key)).append("\":\"")
            sb.append(escapeJsonString(attr.value)).append("\"")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun cheerioOuterHtml(handle: Int): String =
        when (val el = elementCache[handle]) {
            is Element -> el.outerHtml()
            is Elements -> el.firstOrNull()?.outerHtml() ?: ""
            else -> ""
        }

    private fun cheerioTagName(handle: Int): String =
        when (val el = elementCache[handle]) {
            is Element -> el.tagName().lowercase()
            is Elements -> el.firstOrNull()?.tagName()?.lowercase() ?: ""
            else -> ""
        }

    /**
     * Child nodes of each element in the set, including text and comment nodes, as a JSON array
     * of DOM-like nodes. Element nodes get a live `_h` handle so $(node) and $.html(node) resolve
     * back to the real Jsoup element. Backs cheerio's contents().
     */
    private fun cheerioContents(handle: Int): String {
        val el = elementCache[handle] ?: return "[]"
        val elements =
            when (el) {
                is Element -> listOf(el)
                is Elements -> el.toList()
                else -> emptyList()
            }
        val sb = StringBuilder("[")
        var first = true
        for (elem in elements) {
            for (node in elem.childNodes()) {
                val piece = StringBuilder()
                when (node) {
                    is TextNode -> {
                        piece.append("{\"type\":\"text\",\"data\":\"")
                        piece.append(escapeJsonString(node.wholeText))
                        piece.append("\",\"children\":[]}")
                    }
                    is org.jsoup.nodes.Comment -> {
                        piece.append("{\"type\":\"comment\",\"data\":\"")
                        piece.append(escapeJsonString(node.data))
                        piece.append("\",\"children\":[]}")
                    }
                    is org.jsoup.nodes.DataNode -> {
                        piece.append("{\"type\":\"text\",\"data\":\"")
                        piece.append(escapeJsonString(node.wholeData))
                        piece.append("\",\"children\":[]}")
                    }
                    is Element -> {
                        val nodeHandle = ++handleCounter
                        elementCache[nodeHandle] = node
                        appendDomNode(piece, node, nodeHandle)
                    }
                    else -> {}
                }
                if (piece.isNotEmpty()) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append(piece)
                }
            }
        }
        sb.append(']')
        return sb.toString()
    }

    // ---- DOM manipulation methods ----

    private fun cheerioRemove(handle: Int) {
        when (val el = elementCache[handle]) {
            is Element -> if (el !is Document) el.remove()
            is Elements -> el.remove()
            else -> {}
        }
    }

    private fun cheerioAfter(
        handle: Int,
        html: String,
    ) {
        if (html.isBlank()) return
        when (val el = elementCache[handle]) {
            is Element -> if (el !is Document) el.after(html)
            is Elements -> el.forEach { it.after(html) }
            else -> {}
        }
    }

    private fun cheerioBefore(
        handle: Int,
        html: String,
    ) {
        if (html.isBlank()) return
        when (val el = elementCache[handle]) {
            is Element -> if (el !is Document) el.before(html)
            is Elements -> el.forEach { it.before(html) }
            else -> {}
        }
    }

    private fun cheerioAppend(
        handle: Int,
        html: String,
    ) {
        if (html.isBlank()) return
        when (val el = elementCache[handle]) {
            is Document -> el.body().append(html)
            is Element -> el.append(html)
            is Elements -> el.forEach { it.append(html) }
            else -> {}
        }
    }

    private fun cheerioPrepend(
        handle: Int,
        html: String,
    ) {
        if (html.isBlank()) return
        when (val el = elementCache[handle]) {
            is Document -> el.body().prepend(html)
            is Element -> el.prepend(html)
            is Elements -> el.forEach { it.prepend(html) }
            else -> {}
        }
    }

    private fun cheerioEmpty(handle: Int) {
        when (val el = elementCache[handle]) {
            is Document -> el.body().empty()
            is Element -> el.empty()
            is Elements -> el.forEach { it.empty() }
            else -> {}
        }
    }

    private fun cheerioWrap(
        handle: Int,
        html: String,
    ) {
        if (html.isBlank()) return
        when (val el = elementCache[handle]) {
            is Element -> if (el !is Document) el.wrap(html)
            is Elements -> el.forEach { it.wrap(html) }
            else -> {}
        }
    }

    private fun cheerioLength(handle: Int): Int =
        when (val el = elementCache[handle]) {
            is Document -> 1
            is Element -> 1
            is Elements -> el.size
            else -> 0
        }

    private fun cheerioEq(
        handle: Int,
        index: Int,
    ): Int {
        val el = elementCache[handle]
        val result =
            when (el) {
                is Elements -> el.getOrNull(index)
                is Element -> if (index == 0) el else null
                is Document -> if (index == 0) el else null
                else -> null
            }
        return if (result != null) {
            val newHandle = ++handleCounter
            elementCache[newHandle] = result
            newHandle
        } else {
            -1
        }
    }

    private fun cheerioFirst(handle: Int): Int = cheerioEq(handle, 0)

    private fun cheerioLast(handle: Int): Int {
        val len = cheerioLength(handle)
        return if (len > 0) cheerioEq(handle, len - 1) else -1
    }

    private fun cheerioParent(handle: Int): Int {
        val el = elementCache[handle]
        val parent =
            when (el) {
                is Element -> el.parent()
                is Elements -> el.firstOrNull()?.parent()
                else -> null
            }
        return if (parent != null) {
            val newHandle = ++handleCounter
            elementCache[newHandle] = parent
            newHandle
        } else {
            -1
        }
    }

    private fun cheerioChildren(
        handle: Int,
        selector: String,
    ): Int {
        val el = elementCache[handle]
        val children =
            when (el) {
                is Element -> if (selector.isEmpty()) el.children() else el.children().select(selector)
                is Elements ->
                    Elements().also { results ->
                        el.forEach { e ->
                            if (selector.isEmpty()) {
                                results.addAll(e.children())
                            } else {
                                results.addAll(e.children().select(selector))
                            }
                        }
                    }
                else -> Elements()
            }
        val newHandle = ++handleCounter
        elementCache[newHandle] = children
        return newHandle
    }

    private fun cheerioNext(handle: Int): Int {
        val el = elementCache[handle]
        val next =
            when (el) {
                is Element -> el.nextElementSibling()
                is Elements -> el.firstOrNull()?.nextElementSibling()
                else -> null
            }
        return if (next != null) {
            val newHandle = ++handleCounter
            elementCache[newHandle] = next
            newHandle
        } else {
            -1
        }
    }

    private fun cheerioPrev(handle: Int): Int {
        val el = elementCache[handle]
        val prev =
            when (el) {
                is Element -> el.previousElementSibling()
                is Elements -> el.firstOrNull()?.previousElementSibling()
                else -> null
            }
        return if (prev != null) {
            val newHandle = ++handleCounter
            elementCache[newHandle] = prev
            newHandle
        } else {
            -1
        }
    }

    private fun cheerioHasClass(
        handle: Int,
        className: String,
    ): Boolean =
        when (val el = elementCache[handle]) {
            is Element -> el.hasClass(className)
            is Elements -> el.any { it.hasClass(className) }
            else -> false
        }

    private fun normalizeSelectorForJsoup(selector: String): String {
        if (selector.isBlank()) return selector

        // jQuery allows :contains("text") / :contains('text'), while Jsoup expects :contains(text).
        var normalized =
            selector
                .replace(Regex(":contains\\(\"([^\"]*)\"\\)")) { matchResult ->
                    ":contains(${matchResult.groupValues[1]})"
                }.replace(Regex(":contains\\('([^']*)'\\)")) { matchResult ->
                    ":contains(${matchResult.groupValues[1]})"
                }

        // Same normalization for :containsOwn(...)
        normalized =
            normalized
                .replace(Regex(":containsOwn\\(\"([^\"]*)\"\\)")) { matchResult ->
                    ":containsOwn(${matchResult.groupValues[1]})"
                }.replace(Regex(":containsOwn\\('([^']*)'\\)")) { matchResult ->
                    ":containsOwn(${matchResult.groupValues[1]})"
                }

        return normalized
    }

    private fun cheerioFilter(
        handle: Int,
        selector: String,
    ): Int {
        val normalizedSelector = normalizeSelectorForJsoup(selector)
        val el = elementCache[handle]
        val filtered =
            when (el) {
                is Elements -> Elements(el.filter { it.`is`(normalizedSelector) })
                is Element -> if (el.`is`(normalizedSelector)) Elements(listOf(el)) else Elements()
                else -> Elements()
            }
        val newHandle = ++handleCounter
        elementCache[newHandle] = filtered
        return newHandle
    }

    private fun cheerioIs(
        handle: Int,
        selector: String,
    ): Boolean {
        val normalizedSelector = normalizeSelectorForJsoup(selector)
        return when (val el = elementCache[handle]) {
            is Element -> el.`is`(normalizedSelector)
            is Elements -> el.any { it.`is`(normalizedSelector) }
            else -> false
        }
    }

    // ============ Storage API ============

    private fun loadStorageFromPrefs() {
        // Load all stored values into memory cache
        prefs.all.forEach { (key, value) ->
            if (value is String) storage[key] = value
        }
    }

    private suspend fun setupStorage(runtime: QuickJs) {
        // Hydrate in-memory cache from persistent storage
        loadStorageFromPrefs()

        runtime.function("__storageGet") { args ->
            val key = args.getOrNull(0)?.toString() ?: ""
            val value = storage[key]
            value
        }

        runtime.function("__storageSet") { args ->
            val key = args.getOrNull(0)?.toString() ?: ""
            val value = args.getOrNull(1)?.toString() ?: ""
            storage[key] = value
            prefs.edit().putString(key, value).apply()
            logcat(LogPriority.DEBUG) { "[$pluginId] storage.set('$key', '$value')" }
        }

        runtime.function("__storageDelete") { args ->
            val key = args.getOrNull(0)?.toString() ?: ""
            storage.remove(key)
            prefs.edit().remove(key).apply()
        }

        runtime.function("__storageGetAllKeys") { _ ->
            storage.keys.joinToString(",")
        }

        runtime.function("__storageClearAll") { _ ->
            storage.clear()
            prefs.edit().clear().apply()
            true
        }
    }

    // ============ Cleanup ============

    fun cleanup() {
        val cleared = elementCache.size
        elementCache.clear()
        handleCounter = 0
        logcat(LogPriority.DEBUG) { "[$pluginId] JSLibraryProvider cleanup: cleared $cleared cached elements" }
    }

    /**
     * Clear old cached elements to prevent memory buildup.
     * @param keepRecent Number of recent elements to keep
     */
    fun trimCache(keepRecent: Int = 10) {
        val cacheSize = elementCache.size
        if (cacheSize > keepRecent) {
            // Sort keys and keep only the most recent ones
            val sortedKeys = elementCache.keys.sorted()
            val toRemove = sortedKeys.take(maxOf(0, sortedKeys.size - keepRecent))
            toRemove.forEach { elementCache.remove(it) }
            if (toRemove.isNotEmpty()) {
                logcat(LogPriority.DEBUG) {
                    "[$pluginId] Trimmed cache: removed ${toRemove.size} old elements, kept ${elementCache.size}"
                }
            }
        }
    }

    // ============ Minimal JS Runtime ============

    private fun getMinimalRuntime(): String =
        """
        // CommonJS module system
        var module = { exports: {} };
        var exports = module.exports;

        // Console
        var console = {
            log: function() { __log('LOG', Array.prototype.slice.call(arguments).join(' ')); },
            warn: function() { __log('WARN', Array.prototype.slice.call(arguments).join(' ')); },
            error: function() { __log('ERROR', Array.prototype.slice.call(arguments).join(' ')); },
            info: function() { __log('INFO', Array.prototype.slice.call(arguments).join(' ')); },
            debug: function() { __log('DEBUG', Array.prototype.slice.call(arguments).join(' ')); }
        };

        // Array.from override - native QuickJS Array.from may fail with mapFn argument
        Array.from = function(arrayLike, mapFn, thisArg) {
            if (arrayLike == null) return [];
            var arr = [];
            // Handle iterables with Symbol.iterator (Sets, Maps, etc.)
            if (typeof Symbol !== 'undefined' && arrayLike[Symbol.iterator] && typeof arrayLike[Symbol.iterator] === 'function') {
                try {
                    var iter = arrayLike[Symbol.iterator]();
                    if (iter && typeof iter.next === 'function') {
                        var i = 0, next;
                        while (!(next = iter.next()).done) {
                            arr.push(mapFn ? mapFn.call(thisArg, next.value, i++) : next.value);
                        }
                        return arr;
                    }
                } catch (e) {
                    // Fall through to array-like handling
                }
            }
            // Handle array-likes with .length
            var len = arrayLike.length >>> 0;
            for (var i = 0; i < len; i++) {
                arr.push(mapFn ? mapFn.call(thisArg, arrayLike[i], i) : arrayLike[i]);
            }
            return arr;
        };

        // Timers backed by Kotlin __delay (QuickJS has no event-loop timers)
        var __timerSeq = 1;
        var __cancelledTimers = {};
        function setTimeout(cb, ms) {
            if (typeof cb !== 'function') return 0;
            var id = __timerSeq++;
            var args = Array.prototype.slice.call(arguments, 2);
            __delay(ms > 0 ? ms : 0).then(function() {
                var cancelled = __cancelledTimers[id];
                delete __cancelledTimers[id];
                if (cancelled) return;
                try { cb.apply(null, args); } catch (e) { console.error('setTimeout callback: ' + e); }
            });
            return id;
        }
        function clearTimeout(id) { if (id) __cancelledTimers[id] = true; }
        function setInterval(cb, ms) {
            if (typeof cb !== 'function') return 0;
            var id = __timerSeq++;
            var args = Array.prototype.slice.call(arguments, 2);
            function tick() {
                // Clamp like browsers; ms=0 would recurse with no delay and starve the job queue
                __delay(ms >= 4 ? ms : 4).then(function() {
                    if (__cancelledTimers[id]) { delete __cancelledTimers[id]; return; }
                    try { cb.apply(null, args); } catch (e) { console.error('setInterval callback: ' + e); }
                    tick();
                });
            }
            tick();
            return id;
        }
        function clearInterval(id) { clearTimeout(id); }
        function queueMicrotask(fn) { Promise.resolve().then(fn); }
        globalThis.setTimeout = setTimeout;
        globalThis.clearTimeout = clearTimeout;
        globalThis.setInterval = setInterval;
        globalThis.clearInterval = clearInterval;
        globalThis.queueMicrotask = queueMicrotask;

        // TextDecoder polyfill for encoding support
        function TextDecoder(encoding) {
            this.encoding = (encoding || 'utf-8').toLowerCase();
        }
        TextDecoder.prototype.decode = function(buffer) {
            // For ArrayBuffer or Uint8Array
            var bytes = buffer instanceof ArrayBuffer ? new Uint8Array(buffer) : buffer;
            if (!bytes || !bytes.length) return '';
            // UTF-8 decoding
            var result = '', i = 0;
            while (i < bytes.length) {
                var b = bytes[i++], cp, extra;
                if (b < 0x80) { cp = b; extra = 0; }
                else if ((b & 0xE0) === 0xC0) { cp = b & 0x1F; extra = 1; }
                else if ((b & 0xF0) === 0xE0) { cp = b & 0x0F; extra = 2; }
                else if ((b & 0xF8) === 0xF0) { cp = b & 0x07; extra = 3; }
                else { cp = 0xFFFD; extra = 0; }
                while (extra-- > 0 && i < bytes.length) {
                    cp = (cp << 6) | (bytes[i++] & 0x3F);
                }
                if (cp > 0xFFFF) {
                    cp -= 0x10000;
                    result += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 0x3FF));
                } else {
                    result += String.fromCharCode(cp);
                }
            }
            return result;
        };
        globalThis.TextDecoder = TextDecoder;

        // TextEncoder polyfill (UTF-8)
        function TextEncoder() {
            this.encoding = 'utf-8';
        }
        TextEncoder.prototype.encode = function(str) {
            var out = [];
            for (var i = 0; i < str.length; i++) {
                var cp = str.codePointAt(i);
                if (cp > 0xFFFF) i++;
                if (cp < 0x80) { out.push(cp); }
                else if (cp < 0x800) { out.push(0xC0 | (cp >> 6), 0x80 | (cp & 63)); }
                else if (cp < 0x10000) { out.push(0xE0 | (cp >> 12), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63)); }
                else { out.push(0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 63), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63)); }
            }
            return new Uint8Array(out);
        };
        globalThis.TextEncoder = TextEncoder;

        // Buffer polyfill for Node.js compatibility
        var Buffer = {
            from: function(data, encoding) {
                if (typeof data === 'string') {
                    if (encoding === 'base64') {
                        return { toString: function() { return atob(data); }, data: atob(data) };
                    }
                    return { toString: function() { return data; }, data: data };
                }
                if (data instanceof ArrayBuffer || ArrayBuffer.isView(data)) {
                    var bytes = data instanceof ArrayBuffer ? new Uint8Array(data) : data;
                    var str = '';
                    for (var i = 0; i < bytes.length; i++) str += String.fromCharCode(bytes[i]);
                    return {
                        toString: function(enc) { return enc === 'base64' ? btoa(str) : str; },
                        data: str
                    };
                }
                return { toString: function() { return ''; }, data: '' };
            },
            isBuffer: function(obj) { return false; }
        };
        globalThis.Buffer = Buffer;

        // Fetch API wrapper
        async function fetch(url, init) {
            console.log('[FETCH] Calling URL: ' + url);
            var jsonStr = await __fetch(url, init || {});
            var r = JSON.parse(jsonStr);
            console.log('[FETCH] Response status=' + r.status + ', ok=' + r.ok + ', textLen=' + (r.text || '').length);
            var isBinary = !!(r.headers && (r.headers['x-binary-base64'] === 'true' || r.headers['x-binary-base64'] === true));
            return {
                ok: !!r.ok,
                status: r.status || 0,
                statusText: r.statusText || '',
                url: r.url || url,
                headers: new Headers(r.headers || {}),
                text: async function() {
                    if (!isBinary) return r.text || '';
                    try {
                        return atob(r.text || '');
                    } catch (e) {
                        return '';
                    }
                },
                json: async function() {
                    var t = r.text || '';
                    if (!t) return {};
                    try {
                        return JSON.parse(t);
                    } catch (e) {
                        throw new Error('fetch json() parse failed (status=' + (r.status || 0) + ', url=' + (r.url || url) + '): ' + String(t).slice(0, 200));
                    }
                },
                arrayBuffer: async function() {
                    if (isBinary) {
                        var b64 = r.text || '';
                        var bin = atob(b64);
                        var out = new Uint8Array(bin.length);
                        for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i) & 255;
                        return out.buffer;
                    }
                    var text = r.text || '';
                    var buf = new ArrayBuffer(text.length);
                    var view = new Uint8Array(buf);
                    for (var j = 0; j < text.length; j++) view[j] = text.charCodeAt(j);
                    return buf;
                },
                blob: async function() { return new Blob([r.text || '']); },
                clone: function() { return this; }
            };
        }

        // Headers polyfill
        function Headers(init) {
            this._headers = {};
            if (init) {
                for (var k in init) {
                    if (init.hasOwnProperty(k)) {
                        this._headers[k.toLowerCase()] = init[k];
                    }
                }
            }
        }
        Headers.prototype.get = function(name) { return this._headers[name.toLowerCase()] || null; };
        Headers.prototype.set = function(name, value) { this._headers[name.toLowerCase()] = value; };
        Headers.prototype.has = function(name) { return name.toLowerCase() in this._headers; };
        Headers.prototype.append = function(name, value) {
            var key = name.toLowerCase();
            this._headers[key] = this._headers[key] ? this._headers[key] + ', ' + value : value;
        };
        Headers.prototype.delete = function(name) { delete this._headers[name.toLowerCase()]; };
        Headers.prototype.forEach = function(callback, thisArg) {
            for (var k in this._headers) {
                if (this._headers.hasOwnProperty(k)) {
                    callback.call(thisArg, this._headers[k], k, this);
                }
            }
        };
        Headers.prototype.entries = function() {
            var arr = [];
            for (var k in this._headers) {
                if (this._headers.hasOwnProperty(k)) arr.push([k, this._headers[k]]);
            }
            return arr[Symbol.iterator]();
        };
        globalThis.Headers = Headers;

        // Blob polyfill
        function Blob(parts, options) {
            this._data = parts ? parts.join('') : '';
            this.type = options && options.type || '';
            this.size = this._data.length;
        }
        Blob.prototype.text = async function() { return this._data; };
        Blob.prototype.arrayBuffer = async function() {
            var buf = new ArrayBuffer(this._data.length);
            var view = new Uint8Array(buf);
            for (var i = 0; i < this._data.length; i++) view[i] = this._data.charCodeAt(i);
            return buf;
        };
        globalThis.Blob = Blob;

        // Cheerio wrapper - minimal JS, logic in Kotlin

        // DOM-node-like shim for $(sel)[i] / toArray() interop, backed by a live handle
        function __nodeAt(h, idx) {
            var eh = __cheerioEq(h, idx);
            if (eh < 0) return undefined;
            var node = { _h: eh, type: 'tag' };
            Object.defineProperty(node, 'name', {
                get: function() { return __cheerioTagName(eh); },
                enumerable: false, configurable: true
            });
            Object.defineProperty(node, 'tagName', {
                get: function() { return __cheerioTagName(eh); },
                enumerable: false, configurable: true
            });
            Object.defineProperty(node, 'attribs', {
                get: function() { try { return JSON.parse(__cheerioGetAttrs(eh)); } catch (e) { return {}; } },
                enumerable: false, configurable: true
            });
            return node;
        }

        // Serialize a plain DOM-tree JSON node (no live handle) back to HTML
        function __domNodeHtml(node) {
            if (!node) return '';
            if (node.type === 'text') return node.data || '';
            if (node.type === 'comment') return '';
            if (node.type === 'tag') {
                var attrs = '';
                if (node.attribs) {
                    for (var k in node.attribs) {
                        attrs += ' ' + k + '="' + String(node.attribs[k]).replace(/"/g, '&quot;') + '"';
                    }
                }
                var inner = '';
                if (node.children) {
                    for (var i = 0; i < node.children.length; i++) inner += __domNodeHtml(node.children[i]);
                }
                return '<' + node.name + attrs + '>' + inner + '</' + node.name + '>';
            }
            return '';
        }

        function __wrapHandle(h) {
            if (h < 0) return __emptySelection();
            var obj = {
                _h: h,
                find: function(s) {
                    // Wrapped cheerio object or DOM-node shim: resolve to a wrapper
                    if (s && s._h !== undefined) return (typeof s.find === 'function') ? s : __wrapHandle(s._h);
                    var next = __wrapHandle(__cheerioSelect(h, typeof s === 'string' ? s : ''));
                    next._prev = this;
                    return next;
                },
                text: function() { return __cheerioText(h); },
                html: function() { return __cheerioHtml(h); },
                attr: function(n) { var v = __cheerioAttr(h, n); return v === null ? undefined : v; },
                get attribs() { try { return JSON.parse(__cheerioGetAttrs(h)); } catch(e) { return {}; } },
                data: function(n) { var v = __cheerioAttr(h, 'data-' + n); return v === null ? undefined : v; },
                get length() { return __cheerioLength(h); },
                eq: function(i) { return __wrapHandle(__cheerioEq(h, i)); },
                first: function() { return __wrapHandle(__cheerioFirst(h)); },
                last: function() { return __wrapHandle(__cheerioLast(h)); },
                parent: function() { return __wrapHandle(__cheerioParent(h)); },
                parents: function(s) { return __wrapHandle(__cheerioParent(h)); }, // Simplified
                children: function(s) { return __wrapHandle(__cheerioChildren(h, s || '')); },
                next: function() { return __wrapHandle(__cheerioNext(h)); },
                nextAll: function() { return __wrapHandle(__cheerioNext(h)); }, // Simplified
                prev: function() { return __wrapHandle(__cheerioPrev(h)); },
                prevAll: function() { return __wrapHandle(__cheerioPrev(h)); }, // Simplified
                hasClass: function(c) { return __cheerioHasClass(h, c); },
                is: function(s) { return __cheerioIs(h, s); },
                filter: function(s) {
                    if (typeof s === 'function') {
                        // Function filter - iterate and apply
                        var results = [];
                        var len = __cheerioLength(h);
                        for (var i = 0; i < len; i++) {
                            var el = __wrapHandle(__cheerioEq(h, i));
                            if (s.call(el, i, el)) results.push(el);
                        }
                        return __arrayToCheerio(results);
                    }
                    return __wrapHandle(__cheerioFilter(h, s));
                },
                not: function(s) {
                    var results = [];
                    var len = __cheerioLength(h);
                    for (var i = 0; i < len; i++) {
                        var el = __wrapHandle(__cheerioEq(h, i));
                        if (typeof s === 'function') {
                            if (!s.call(el, i, el)) results.push(el);
                        } else if (!el.is(s)) {
                            results.push(el);
                        }
                    }
                    return __arrayToCheerio(results);
                },
                each: function(cb) {
                    var len = __cheerioLength(h);
                    for (var i = 0; i < len; i++) { var el = __wrapHandle(__cheerioEq(h, i)); cb.call(el, i, el); }
                    return this;
                },
                map: function(cb) {
                    var results = [], len = __cheerioLength(h);
                    for (var i = 0; i < len; i++) {
                        var el = __wrapHandle(__cheerioEq(h, i));
                        var result = cb.call(el, i, el);
                        if (result != null) results.push(result);
                    }
                    // Return array-like object with common array methods + cheerio methods
                    results.get = function(idx) { return typeof idx === 'undefined' ? results : results[idx]; };
                    results.toArray = function() { return results.slice(); };
                    results.join = function(sep) { return results.slice().join(sep); };
                    results.text = function() { return results.join(''); };
                    results.filter = function(fn) { return results.slice().filter(fn); };
                    return results;
                },
                toArray: function() {
                    try {
                        var json = __cheerioDomTree(h);
                        return JSON.parse(json);
                    } catch(e) {
                        // Fallback to wrapped handles if DOM tree fails
                        var arr = [], len = __cheerioLength(h);
                        for (var i = 0; i < len; i++) arr.push(__wrapHandle(__cheerioEq(h, i)));
                        return arr;
                    }
                },
                get: function(i) { return typeof i === 'undefined' ? this.toArray() : __wrapHandle(__cheerioEq(h, i)); },
                prop: function(n) { var v = __cheerioAttr(h, n); return v === null ? undefined : v; },
                val: function() { var v = __cheerioAttr(h, 'value'); return v === null ? undefined : v; },
                trim: function() { return (__cheerioText(h) || '').trim(); },
                contents: function() {
                    // Real cheerio contents() includes text/comment nodes, not just elements
                    var nodes;
                    try { nodes = JSON.parse(__cheerioContents(h)); } catch (e) { nodes = []; }
                    return __arrayToCheerio(nodes);
                },
                siblings: function(s) {
                    var p = this.parent();
                    return s ? p.children(s).not(this) : p.children().not(this);
                },
                closest: function(s) {
                    var el = this;
                    while (el && el.length > 0) {
                        if (el.is(s)) return el;
                        el = el.parent();
                    }
                    return __emptySelection();
                },
                remove: function() { __cheerioRemove(h); return this; },
                after: function(content) { __cheerioAfter(h, typeof content === 'string' ? content : ''); return this; },
                before: function(content) { __cheerioBefore(h, typeof content === 'string' ? content : ''); return this; },
                append: function(content) { __cheerioAppend(h, typeof content === 'string' ? content : ''); return this; },
                prepend: function(content) { __cheerioPrepend(h, typeof content === 'string' ? content : ''); return this; },
                empty: function() { __cheerioEmpty(h); return this; },
                wrap: function(content) { __cheerioWrap(h, typeof content === 'string' ? content : ''); return this; },
                clone: function() { return this; },
                addClass: function() { return this; },
                removeClass: function() { return this; },
                replaceWith: function(content) { if (typeof content === 'string') { __cheerioAfter(h, content); __cheerioRemove(h); } return this; },
                addBack: function() { return this; },
                end: function() { return this._prev || this; },
                slice: function(start, end) {
                    var arr = this.toArray();
                    return __arrayToCheerio(arr.slice(start, end));
                },
                index: function() {
                    var siblings = this.parent().children().toArray();
                    for (var i = 0; i < siblings.length; i++) {
                        if (siblings[i]._h === h) return i;
                    }
                    return -1;
                }
            };
            // Numeric indexing parity: $(sel)[0] returns a DOM-node-like shim
            var __len = __cheerioLength(h);
            for (var __i = 0; __i < __len; __i++) (function(idx) {
                Object.defineProperty(obj, idx, {
                    get: function() { return __nodeAt(h, idx); },
                    enumerable: false,
                    configurable: true
                });
            })(__i);
            return obj;
        }

        // Helper to convert array of cheerio objects back to cheerio-like object
        function __arrayToCheerio(arr) {
            if (!arr || arr.length === 0) return __emptySelection();
            function __collect(sel) {
                var out = [];
                if (!sel || typeof sel.length !== 'number') return out;
                for (var i = 0; i < sel.length; i++) {
                    if (typeof sel.get === 'function') {
                        var item = sel.get(i);
                        if (item) out.push(item);
                    } else if (typeof sel.eq === 'function') {
                        var eqItem = sel.eq(i);
                        if (eqItem) out.push(eqItem);
                    }
                }
                return out;
            }
            var obj = {
                _arr: arr,
                _prev: null,
                get length() { return arr.length; },
                eq: function(i) { return arr[i] || __emptySelection(); },
                first: function() { return arr[0] || __emptySelection(); },
                last: function() { return arr[arr.length - 1] || __emptySelection(); },
                each: function(cb) { arr.forEach(function(el, i) { cb.call(el, i, el); }); return this; },
                map: function(cb) {
                    var results = arr.map(function(el, i) { return cb.call(el, i, el); });
                    results.get = function(idx) { return typeof idx === 'undefined' ? results.slice() : results[idx]; };
                    results.toArray = function() { return results.slice(); };
                    results.text = function() { return results.join(''); };
                    return results;
                },
                toArray: function() { return arr.slice(); },
                get: function(i) { return typeof i === 'undefined' ? arr.slice() : arr[i]; },
                parent: function() {
                    var out = [];
                    arr.forEach(function(el) { if (el && el.parent) out = out.concat(__collect(el.parent())); });
                    var next = __arrayToCheerio(out);
                    next._prev = this;
                    return next;
                },
                children: function(s) {
                    var out = [];
                    arr.forEach(function(el) { if (el && el.children) out = out.concat(__collect(el.children(s || ''))); });
                    var next = __arrayToCheerio(out);
                    next._prev = this;
                    return next;
                },
                next: function() {
                    var out = [];
                    arr.forEach(function(el) { if (el && el.next) out = out.concat(__collect(el.next())); });
                    var next = __arrayToCheerio(out);
                    next._prev = this;
                    return next;
                },
                prev: function() {
                    var out = [];
                    arr.forEach(function(el) { if (el && el.prev) out = out.concat(__collect(el.prev())); });
                    var next = __arrayToCheerio(out);
                    next._prev = this;
                    return next;
                },
                find: function(s) {
                    var results = [];
                    arr.forEach(function(el) { if (el && el.find) results = results.concat(__collect(el.find(s))); });
                    var next = __arrayToCheerio(results);
                    next._prev = this;
                    return next;
                },
                text: function() {
                    return arr.map(function(el) {
                        if (!el) return '';
                        if (typeof el.text === 'function') return el.text();
                        if (el._h !== undefined) return __cheerioText(el._h);
                        if (typeof el.data === 'string') return el.data;
                        return '';
                    }).join('');
                },
                html: function() { return arr.length > 0 ? arr[0].html() : ''; },
                attr: function(n) { return arr.length > 0 ? arr[0].attr(n) : undefined; },
                data: function(n) { return arr.length > 0 && arr[0].data ? arr[0].data(n) : undefined; },
                prop: function(n) { return arr.length > 0 && arr[0].prop ? arr[0].prop(n) : undefined; },
                val: function() { return arr.length > 0 && arr[0].val ? arr[0].val() : undefined; },
                hasClass: function(c) { return arr.length > 0 && arr[0].hasClass ? arr[0].hasClass(c) : false; },
                is: function(s) { return arr.length > 0 && arr[0].is ? arr[0].is(s) : false; },
                trim: function() { return (this.text() || '').trim(); },
                filter: function(s) {
                    var filtered;
                    if (typeof s === 'function') {
                        filtered = arr.filter(function(el, i) { return !!s.call(el, i, el); });
                    } else {
                        filtered = arr.filter(function(el) { return el.is(s); });
                    }
                    var next = __arrayToCheerio(filtered);
                    next._prev = this;
                    return next;
                },
                not: function(s) {
                    var filtered;
                    if (typeof s === 'function') {
                        filtered = arr.filter(function(el, i) { return !s.call(el, i, el); });
                    } else {
                        filtered = arr.filter(function(el) { return !el.is(s); });
                    }
                    var next = __arrayToCheerio(filtered);
                    next._prev = this;
                    return next;
                },
                remove: function() { arr.forEach(function(el) { if (el && el.remove) el.remove(); }); return this; },
                replaceWith: function(content) {
                    arr.forEach(function(el) { if (el && el.replaceWith) el.replaceWith(content); });
                    return this;
                },
                contents: function() { return this.children(''); },
                siblings: function(s) {
                    var out = [];
                    arr.forEach(function(el) {
                        if (!el || !el.parent || !el._h) return;
                        var sib = s ? el.parent().children(s) : el.parent().children();
                        out = out.concat(__collect(sib).filter(function(x) { return x && x._h !== el._h; }));
                    });
                    var next = __arrayToCheerio(out);
                    next._prev = this;
                    return next;
                },
                closest: function(s) { return arr.length > 0 && arr[0].closest ? arr[0].closest(s) : __emptySelection(); },
                addBack: function() { return this; },
                end: function() { return this._prev || this; },
                slice: function(start, end) {
                    var next = __arrayToCheerio(arr.slice(start, end));
                    next._prev = this;
                    return next;
                },
                index: function() { return arr.length > 0 && arr[0].index ? arr[0].index() : -1; },
                clone: function() { return this; },
                addClass: function() { return this; },
                removeClass: function() { return this; },
                append: function(content) { arr.forEach(function(el) { if (el && el.append) el.append(content); }); return this; },
                prepend: function(content) { arr.forEach(function(el) { if (el && el.prepend) el.prepend(content); }); return this; },
                before: function(content) { arr.forEach(function(el) { if (el && el.before) el.before(content); }); return this; },
                after: function(content) { arr.forEach(function(el) { if (el && el.after) el.after(content); }); return this; },
                empty: function() { arr.forEach(function(el) { if (el && el.empty) el.empty(); }); return this; },
                wrap: function(content) { arr.forEach(function(el) { if (el && el.wrap) el.wrap(content); }); return this; }
            };
            // Numeric indexing parity with __wrapHandle
            for (var __i = 0; __i < arr.length; __i++) obj[__i] = arr[__i];
            return obj;
        }

        function __emptySelection() {
            return {
                _h: -1,
                find: function() { return this; }, text: function() { return ''; }, html: function() { return ''; },
                attr: function() { return undefined; }, data: function() { return undefined; }, length: 0,
                eq: function() { return this; }, first: function() { return this; }, last: function() { return this; },
                parent: function() { return this; }, parents: function() { return this; },
                children: function() { return this; },
                next: function() { return this; }, nextAll: function() { return this; },
                prev: function() { return this; }, prevAll: function() { return this; },
                hasClass: function() { return false; }, is: function() { return false; }, filter: function() { return this; },
                not: function() { return this; },
                each: function() { return this; },
                map: function() { var r = []; r.get = function() { return r; }; r.toArray = function() { return r; }; r.join = function() { return ''; }; r.text = function() { return ''; }; r.filter = function() { return r; }; return r; },
                toArray: function() { return []; }, get: function() { return []; },
                prop: function() { return undefined; }, val: function() { return undefined; }, trim: function() { return ''; },
                contents: function() { return this; }, siblings: function() { return this; }, closest: function() { return this; },
                remove: function() { return this; }, after: function() { return this; }, before: function() { return this; },
                append: function() { return this; }, prepend: function() { return this; }, empty: function() { return this; },
                wrap: function() { return this; },
                clone: function() { return this; },
                addClass: function() { return this; }, removeClass: function() { return this; },
                replaceWith: function() { return this; }, addBack: function() { return this; },
                end: function() { return this; }, slice: function() { return this; }, index: function() { return -1; }
            };
        }

        // Require function - LNReader module compatibility
        function require(name) {
            switch(name) {
                case 'cheerio':
                    // Experimental: hand plugins the bundled real cheerio when enabled and loaded.
                    if (globalThis.__useNativeCheerio && globalThis.__realCheerio) {
                        return globalThis.__realCheerio;
                    }
                    return {
                        load: function(html) {
                            var docId = __cheerioLoad(html);
                            var $ = function(arg1, arg2) {
                                if (arg1 && arg1._h !== undefined) {
                                    // $(wrapper) or $(domNode) from toArray()/contents()/[i]
                                    return (typeof arg1.find === 'function') ? arg1 : __wrapHandle(arg1._h);
                                }
                                if (arg2 && arg2._h !== undefined) {
                                    var ctx = (typeof arg2.find === 'function') ? arg2 : __wrapHandle(arg2._h);
                                    return ctx.find(arg1); // $(selector, context)
                                }
                                if (typeof arg1 === 'string') return __wrapHandle(__cheerioSelect(docId, arg1)); // $(selector)
                                // cheerio: $(), $(undefined), $(null) = empty selection, never the document
                                return __emptySelection();
                            };
                            $.html = function(el) {
                                if (el === undefined || el === null) return __cheerioHtml(docId);
                                if (el._h !== undefined) return __cheerioOuterHtml(el._h); // $.html(el) = outer HTML
                                if (typeof el === 'object') return __domNodeHtml(el);
                                return '';
                            };
                            $.text = function() { return __cheerioText(docId); };
                            $.root = function() { return __wrapHandle(docId); };
                            return $;
                        }
                    };
                case 'htmlparser2':
                    // Create a proper constructor function for Parser
                    var HtmlParser = function(handlers, options) {
                        if (!(this instanceof HtmlParser)) {
                            return new HtmlParser(handlers, options);
                        }
                        this.handlers = handlers || {};
                        this.options = options || {};
                    };
                    HtmlParser.prototype.write = function(html) {
                        var self = this;
                        // Use a simple regex-based HTML parser
                        var tagRegex = /<(\/?)(\w+)([^>]*)>/g;
                        var lastIndex = 0;
                        var match;

                        while ((match = tagRegex.exec(html)) !== null) {
                            // Text before this tag
                            if (match.index > lastIndex) {
                                var text = html.substring(lastIndex, match.index);
                                if (text && self.handlers.ontext) {
                                    self.handlers.ontext(text);
                                }
                            }

                            var isClosing = match[1] === '/';
                            var tagName = match[2].toLowerCase();
                            var attrStr = match[3];

                            if (isClosing) {
                                if (self.handlers.onclosetag) {
                                    self.handlers.onclosetag(tagName);
                                }
                            } else {
                                // Parse attributes - handle various formats
                                var attrs = {};
                                var attrRegex = /([\w\-:]+)(?:=(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g;
                                var attrMatch;
                                while ((attrMatch = attrRegex.exec(attrStr)) !== null) {
                                    var attrName = attrMatch[1];
                                    var attrValue = attrMatch[2] !== undefined ? attrMatch[2] :
                                                   (attrMatch[3] !== undefined ? attrMatch[3] :
                                                   (attrMatch[4] !== undefined ? attrMatch[4] : ''));
                                    attrs[attrName] = attrValue;
                                }
                                if (self.handlers.onopentag) {
                                    self.handlers.onopentag(tagName, attrs);
                                }
                                // Self-closing tags - only detect slash OUTSIDE of attribute values
                                // Check for trailing / at end of the attribute string (e.g. <br /> or <img src="x" />)
                                var selfClosing = ['br', 'hr', 'img', 'input', 'meta', 'link', 'area', 'base', 'col', 'embed', 'param', 'source', 'track', 'wbr'];
                                var isSelfClose = selfClosing.indexOf(tagName) !== -1 || /\/\s*${'$'}/.test(attrStr);
                                if (isSelfClose) {
                                    if (self.handlers.onclosetag) {
                                        self.handlers.onclosetag(tagName);
                                    }
                                }
                            }
                            lastIndex = tagRegex.lastIndex;
                        }

                        // Remaining text
                        if (lastIndex < html.length) {
                            var remainingText = html.substring(lastIndex);
                            if (remainingText && self.handlers.ontext) {
                                self.handlers.ontext(remainingText);
                            }
                        }
                        return this;
                    };
                    HtmlParser.prototype.end = function() {
                        if (this.handlers.onend) {
                            this.handlers.onend();
                        }
                        return this;
                    };
                    HtmlParser.prototype.reset = function() {
                        return this;
                    };
                    HtmlParser.prototype.parseComplete = function(html) {
                        this.write(html);
                        this.end();
                        return this;
                    };
                    // isVoidElement check - used by some plugins to detect self-closing tags
                    HtmlParser.prototype.isVoidElement = function(tagName) {
                        var voidElements = ['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr'];
                        return voidElements.indexOf(tagName.toLowerCase()) !== -1;
                    };
                    return { Parser: HtmlParser };
                case '@libs/fetch':
                    return {
                        fetchApi: fetch,
                        fetchText: async function(u, i, enc) {
                            var r = await fetch(u, i);
                            var text = await r.text();
                            return text;
                        },
                        fetchFile: async function(u, i) {
                            try {
                                var r = await fetch(u, i);
                                if (!r.ok) return '';
                                var text = await r.text();
                                return btoa(text);
                            } catch (e) {
                                return '';
                            }
                        },
                        /**
                         * Generic fetchProto using protobufjs for encoding/decoding.
                         * This avoids Kotlin-side JsObject conversion issues.
                         */
                        fetchProto: async function(protoConfig, url, options) {
                            // Generic fetchProto using protobufjs for encoding/decoding
                            // protoConfig: { proto: string, requestType: string, responseType: string, requestData: object }
                            if (!protoConfig || !protoConfig.requestData) {
                                console.warn('fetchProto: missing config or requestData');
                                return { items: [] };
                            }

                            try {
                                // Parse proto schema with protobufjs
                                var protoRoot = parseProto(protoConfig.proto).root;
                                if (!protoRoot || !protoRoot.lookupType) {
                                    console.warn('fetchProto: failed to parse proto schema');
                                    return { items: [] };
                                }

                                // Encode request with protobufjs
                                var RequestMessage = protoRoot.lookupType(protoConfig.requestType);
                                if (!RequestMessage) {
                                    console.warn('fetchProto: unknown request type: ' + protoConfig.requestType);
                                    return { items: [] };
                                }

                                // Verify and encode request data
                                var verification = RequestMessage.verify(protoConfig.requestData);
                                if (verification) {
                                    console.warn('fetchProto: invalid request data: ' + verification);
                                    return { items: [] };
                                }

                                var encodedMsg = RequestMessage.encode(protoConfig.requestData).finish();
                                console.warn('fetchProto: encoded ' + protoConfig.requestType + ': ' + encodedMsg.length + ' bytes');

                                // Wrap in gRPC-web frame: [compression:1][length:4][data]
                                var frame = new Uint8Array(5 + encodedMsg.length);
                                frame[0] = 0;  // compression flag
                                frame[1] = (encodedMsg.length >>> 24) & 0xff;
                                frame[2] = (encodedMsg.length >>> 16) & 0xff;
                                frame[3] = (encodedMsg.length >>> 8) & 0xff;
                                frame[4] = encodedMsg.length & 0xff;
                                for (var i = 0; i < encodedMsg.length; i++) {
                                    frame[5 + i] = encodedMsg[i];
                                }

                                // Send request
                                var mergedOptions = options || {};
                                mergedOptions.headers = mergedOptions.headers || {};
                                mergedOptions.headers['Content-Type'] = 'application/grpc-web+proto';
                                mergedOptions.headers['X-Binary-Base64'] = 'true';
                                mergedOptions.method = 'POST';
                                var frameBin = '';
                                for (var f = 0; f < frame.length; f++) {
                                    frameBin += String.fromCharCode(frame[f] & 255);
                                }
                                mergedOptions.body = btoa(frameBin);

                                console.warn('fetchProto: sending to ' + url);
                                var response = await fetch(url, mergedOptions);

                                if (!response.ok) {
                                    console.warn('fetchProto: response not ok: ' + response.status);
                                    return { items: [] };
                                }

                                // Get response as binary via arrayBuffer so we can unwrap grpc-web frames safely
                                var arrayBuf = await response.arrayBuffer();
                                if (!arrayBuf || arrayBuf.byteLength === 0) {
                                    console.warn('fetchProto: empty response');
                                    return { items: [] };
                                }

                                var respBytes = new Uint8Array(arrayBuf);
                                console.warn('fetchProto: received ' + respBytes.length + ' bytes');

                                // Unwrap gRPC-web frames
                                var offset = 0;
                                var payloadData = null;
                                while (offset + 5 <= respBytes.length) {
                                    var isCompressed = respBytes[offset];
                                    var len = ((respBytes[offset + 1] << 24) | (respBytes[offset + 2] << 16) |
                                              (respBytes[offset + 3] << 8) | respBytes[offset + 4]) >>> 0;
                                    offset += 5;

                                    if (len > 0 && offset + len <= respBytes.length) {
                                        if (isCompressed === 0) {  // uncompressed
                                            payloadData = respBytes.subarray(offset, offset + len);
                                        }
                                        offset += len;
                                    } else if (len === 0) {
                                        // trailers
                                        break;
                                    } else {
                                        break;
                                    }
                                }

                                if (!payloadData || payloadData.length === 0) {
                                    console.warn('fetchProto: no payload after unwrap');
                                    return { items: [] };
                                }

                                console.warn('fetchProto: unwrapped payload: ' + payloadData.length + ' bytes');

                                // Decode response with protobufjs
                                var ResponseMessage = protoRoot.lookupType(protoConfig.responseType);
                                if (!ResponseMessage) {
                                    console.warn('fetchProto: unknown response type: ' + protoConfig.responseType);
                                    return { items: [] };
                                }

                                var decodedMsg = ResponseMessage.decode(payloadData);
                                var result = ResponseMessage.toObject(decodedMsg, {
                                    longs: String,
                                    enums: String,
                                    bytes: String,
                                    defaults: true
                                });

                                console.warn('fetchProto: successfully decoded ' + protoConfig.responseType);
                                return result;

                            } catch (e) {
                                console.warn('fetchProto error: ' + (e && e.message ? e.message : String(e)));
                                console.warn('fetchProto stack: ' + (e && e.stack ? e.stack : 'no stack'));
                                return { items: [] };
                            }
                        }
                    };
                case '@libs/novelStatus':
                    return { NovelStatus: { Unknown: 'Unknown', Ongoing: 'Ongoing', Completed: 'Completed', Licensed: 'Licensed', PublishingFinished: 'Publishing Finished', Cancelled: 'Cancelled', OnHiatus: 'On Hiatus' } };
                case '@libs/filterInputs':
                    return { FilterTypes: { TextInput: 'Text', Picker: 'Picker', CheckboxGroup: 'Checkbox', Switch: 'Switch', ExcludableCheckboxGroup: 'XCheckbox' } };
                case '@libs/defaultCover':
                    return { defaultCover: '' };
                case '@libs/aes':
                    return {
                        gcm: function(keyBytes, ivBytes) {
                            function toB64(u8) {
                                if (!u8 || u8.length === 0) return '';
                                var bin = '';
                                for (var i = 0; i < u8.length; i++) {
                                    bin += String.fromCharCode(u8[i] & 255);
                                }
                                return btoa(bin);
                            }
                            function fromB64(b64) {
                                if (!b64) return new Uint8Array(0);
                                var bin = atob(b64);
                                var out = new Uint8Array(bin.length);
                                for (var i = 0; i < bin.length; i++) {
                                    out[i] = bin.charCodeAt(i) & 255;
                                }
                                return out;
                            }
                            return {
                                decrypt: function(cipherBytes) {
                                    var keyB64 = toB64(keyBytes);
                                    var ivB64 = toB64(ivBytes);
                                    var cipherB64 = toB64(cipherBytes);
                                    var plainB64 = __aesGcmDecrypt(keyB64, ivB64, cipherB64);
                                    if (!plainB64) {
                                        throw new Error('AES-GCM decrypt failed');
                                    }
                                    return fromB64(plainB64);
                                }
                            };
                        }
                    };
                case '@libs/isAbsoluteUrl':
                    return { isUrlAbsolute: function(u) { return u && (u.startsWith('http://') || u.startsWith('https://')); } };
                case '@libs/storage':
                    return {
                        storage: {
                            get: function(k, raw) {
                                var v = __storageGet(k);
                                if (v === null || v === undefined) return undefined;
                                if (raw) return { created: new Date(), value: v };
                                return v;
                            },
                            set: function(k, v) {
                                if (v === undefined || v === null) { __storageDelete(k); return; }
                                if (typeof v === 'object') v = JSON.stringify(v);
                                __storageSet(k, String(v));
                            },
                            delete: function(k) { __storageDelete(k); },
                            getAllKeys: function() {
                                var keys = __storageGetAllKeys();
                                return keys ? keys.split(',').filter(function(k) { return k.length > 0; }) : [];
                            },
                            clearAll: function() { __storageClearAll(); }
                        },
                        localStorage: { get: function() { return {}; } },
                        sessionStorage: { get: function() { return {}; } }
                    };
                case 'dayjs':
                    var dayjs = function(d) {
                        var dt = d ? new Date(d) : new Date();
                        return {
                            format: function(f) {
                                if (!f) return dt.toISOString();
                                // Basic format support
                                return f.replace('YYYY', dt.getFullYear())
                                    .replace('MM', String(dt.getMonth() + 1).padStart(2, '0'))
                                    .replace('DD', String(dt.getDate()).padStart(2, '0'))
                                    .replace('HH', String(dt.getHours()).padStart(2, '0'))
                                    .replace('mm', String(dt.getMinutes()).padStart(2, '0'))
                                    .replace('ss', String(dt.getSeconds()).padStart(2, '0'));
                            },
                            toISOString: function() { return dt.toISOString(); },
                            valueOf: function() { return dt.getTime(); },
                            unix: function() { return Math.floor(dt.getTime() / 1000); },
                            add: function(n, unit) {
                                var newDt = new Date(dt);
                                if (unit === 'day' || unit === 'd') newDt.setDate(newDt.getDate() + n);
                                else if (unit === 'month' || unit === 'M') newDt.setMonth(newDt.getMonth() + n);
                                else if (unit === 'year' || unit === 'y') newDt.setFullYear(newDt.getFullYear() + n);
                                else if (unit === 'hour' || unit === 'h') newDt.setHours(newDt.getHours() + n);
                                else if (unit === 'minute' || unit === 'm') newDt.setMinutes(newDt.getMinutes() + n);
                                return dayjs(newDt);
                            },
                            subtract: function(n, unit) { return this.add(-n, unit); },
                            isBefore: function(d) { return dt < new Date(d); },
                            isAfter: function(d) { return dt > new Date(d); },
                            diff: function(d, unit) {
                                var diff = dt.getTime() - new Date(d).getTime();
                                if (unit === 'day' || unit === 'd') return Math.floor(diff / 86400000);
                                if (unit === 'hour' || unit === 'h') return Math.floor(diff / 3600000);
                                if (unit === 'minute' || unit === 'm') return Math.floor(diff / 60000);
                                if (unit === 'second' || unit === 's') return Math.floor(diff / 1000);
                                return diff;
                            }
                        };
                    };
                    dayjs.extend = function() { return dayjs; };
                    dayjs.unix = function(t) { return dayjs(new Date(t * 1000)); };
                    return dayjs;
                case 'urlencode':
                    return { encode: encodeURIComponent, decode: decodeURIComponent };
                case 'protobufjs':
                    return globalThis.protobufjs || {
                        parse: function() { return { root: {} }; },
                        Root: { fromJSON: function() { return {}; } }
                    };
                case '@/types/constants':
                    return {
                        NovelStatus: { Unknown: 'Unknown', Ongoing: 'Ongoing', Completed: 'Completed', Licensed: 'Licensed', PublishingFinished: 'Publishing Finished', Cancelled: 'Cancelled', OnHiatus: 'On Hiatus' },
                        defaultCover: 'https://github.com/LNReader/lnreader-plugins/blob/main/icons/src/coverNotAvailable.jpg?raw=true'
                    };
                default:
                    console.warn('Unknown module: ' + name);
                    return {};
            }
        }

        // URL polyfill
        function URL(url, base) {
            var full = url;
            if (base && !url.match(/^https?:\/\//)) {
                base = String(base).replace(/\/+${'$'}/, '');
                url = String(url).replace(/^\/+/, '');
                full = base + '/' + url;
            }
            var m = full.match(/^(https?):\/\/([^\/\?#]+)(\/[^\?#]*)?(\?[^#]*)?(#.*)?$/);
            if (!m) throw new Error('Invalid URL: ' + full);
            this.protocol = m[1] + ':'; this.host = m[2];
            var hp = m[2].split(':'); this.hostname = hp[0]; this.port = hp[1] || '';
            this.pathname = m[3] || '/'; this.search = m[4] || ''; this.hash = m[5] || '';
            this.href = full; this.origin = m[1] + '://' + m[2];
            this.searchParams = new URLSearchParams(this.search);
        }
        URL.prototype.toString = function() { return this.href; };
        URL.prototype.toJSON = function() { return this.href; };

        // URLSearchParams polyfill
        function URLSearchParams(q) {
            this.params = {};
            if (typeof q === 'string') {
                q = q.startsWith('?') ? q.substring(1) : q;
                q.split('&').forEach(function(p) {
                    var kv = p.split('=');
                    if (kv[0]) {
                        var k = decodeURIComponent(kv[0]), v = kv.length > 1 ? decodeURIComponent(kv[1] || '') : '';
                        if (!this.params[k]) this.params[k] = [];
                        this.params[k].push(v);
                    }
                }.bind(this));
            } else if (q && typeof q === 'object') {
                // Handle object input: new URLSearchParams({ key: 'value', ... })
                if (Array.isArray(q)) {
                    // Handle array of [key, value] pairs
                    q.forEach(function(pair) {
                        if (Array.isArray(pair) && pair.length >= 2) {
                            var k = String(pair[0]), v = String(pair[1]);
                            if (!this.params[k]) this.params[k] = [];
                            this.params[k].push(v);
                        }
                    }.bind(this));
                } else {
                    // Handle plain object
                    var keys = Object.keys(q);
                    for (var i = 0; i < keys.length; i++) {
                        var k = keys[i];
                        this.params[k] = [String(q[k])];
                    }
                }
            }
        }
        URLSearchParams.prototype.get = function(k) { return this.params[k] ? this.params[k][0] : null; };
        URLSearchParams.prototype.getAll = function(k) { return this.params[k] || []; };
        URLSearchParams.prototype.set = function(k, v) { this.params[k] = [String(v)]; };
        URLSearchParams.prototype.append = function(k, v) { if (!this.params[k]) this.params[k] = []; this.params[k].push(String(v)); };
        URLSearchParams.prototype.delete = function(k) { delete this.params[k]; };
        URLSearchParams.prototype.has = function(k) { return k in this.params; };
        URLSearchParams.prototype.toString = function() {
            var r = [];
            for (var k in this.params) this.params[k].forEach(function(v) { r.push(encodeURIComponent(k) + '=' + encodeURIComponent(v)); });
            return r.join('&');
        };

        // Base64
        var atob = function(s) {
            var c = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=', o = '';
            s = String(s).replace(/=+${'$'}/, '');
            for (var i = 0; i < s.length;) {
                var e1 = c.indexOf(s.charAt(i++)), e2 = c.indexOf(s.charAt(i++)), e3 = c.indexOf(s.charAt(i++)), e4 = c.indexOf(s.charAt(i++));
                o += String.fromCharCode((e1 << 2) | (e2 >> 4));
                if (e3 !== 64 && e3 !== -1) o += String.fromCharCode(((e2 & 15) << 4) | (e3 >> 2));
                if (e4 !== 64 && e4 !== -1) o += String.fromCharCode(((e3 & 3) << 6) | e4);
            }
            return o;
        };
        var btoa = function(s) {
            var c = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=', o = '';
            s = String(s);
            for (var i = 0; i < s.length;) {
                var c1 = s.charCodeAt(i++), c2 = s.charCodeAt(i++), c3 = s.charCodeAt(i++);
                o += c.charAt(c1 >> 2) + c.charAt(((c1 & 3) << 4) | (c2 >> 4));
                o += isNaN(c2) ? '==' : (c.charAt(((c2 & 15) << 2) | (c3 >> 6)) + (isNaN(c3) ? '=' : c.charAt(c3 & 63)));
            }
            return o;
        };

        // Iterator polyfill for TS generators
        if (typeof Iterator === 'undefined') {
            var Iterator = function() {};
            Iterator.prototype = Object.prototype;
            Iterator.prototype[Symbol.iterator] = function() { return this; };
            globalThis.Iterator = Iterator;
        }

        // FormData polyfill for POST requests
        function FormData() {
            this.data = {};
        }
        FormData.prototype.append = function(key, value) {
            if (!this.data[key]) {
                this.data[key] = [];
            }
            this.data[key].push(value);
        };
        FormData.prototype.set = function(key, value) {
            this.data[key] = [value];
        };
        FormData.prototype.get = function(key) {
            return this.data[key] ? this.data[key][0] : null;
        };
        FormData.prototype.getAll = function(key) {
            return this.data[key] || [];
        };
        FormData.prototype.has = function(key) {
            return key in this.data;
        };
        FormData.prototype.delete = function(key) {
            delete this.data[key];
        };
        FormData.prototype.keys = function() {
            return Object.keys(this.data);
        };
        FormData.prototype.values = function() {
            var self = this;
            var result = [];
            Object.keys(this.data).forEach(function(k) {
                self.data[k].forEach(function(v) { result.push(v); });
            });
            return result;
        };
        FormData.prototype.entries = function() {
            var self = this;
            var result = [];
            Object.keys(this.data).forEach(function(k) {
                self.data[k].forEach(function(v) { result.push([k, v]); });
            });
            return result;
        };
        FormData.prototype.forEach = function(callback, thisArg) {
            var self = this;
            Object.keys(this.data).forEach(function(k) {
                self.data[k].forEach(function(v) {
                    callback.call(thisArg, v, k, self);
                });
            });
        };
        FormData.prototype.toString = function() {
            var parts = [];
            Object.keys(this.data).forEach(function(k) {
                this.data[k].forEach(function(v) {
                    parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
                });
            }.bind(this));
            return parts.join('&');
        };

        // String.prototype polyfills
        if (!String.prototype.padStart) {
            String.prototype.padStart = function(len, str) {
                str = str || ' ';
                var s = this;
                while (s.length < len) s = str + s;
                return s.slice(-len);
            };
        }
        if (!String.prototype.padEnd) {
            String.prototype.padEnd = function(len, str) {
                str = str || ' ';
                var s = this;
                while (s.length < len) s = s + str;
                return s.slice(0, len);
            };
        }
        if (!String.prototype.replaceAll) {
            String.prototype.replaceAll = function(search, replace) {
                return this.split(search).join(replace);
            };
        }

        // Array.prototype polyfills
        if (!Array.prototype.flat) {
            Array.prototype.flat = function(depth) {
                depth = depth === undefined ? 1 : depth;
                var result = [];
                function flatten(arr, d) {
                    arr.forEach(function(item) {
                        if (Array.isArray(item) && d > 0) flatten(item, d - 1);
                        else result.push(item);
                    });
                }
                flatten(this, depth);
                return result;
            };
        }
        if (!Array.prototype.flatMap) {
            Array.prototype.flatMap = function(fn) {
                return this.map(fn).flat();
            };
        }

        // Object.fromEntries polyfill
        if (!Object.fromEntries) {
            Object.fromEntries = function(entries) {
                var obj = {};
                entries.forEach(function(entry) {
                    obj[entry[0]] = entry[1];
                });
                return obj;
            };
        }

        // Global assignments
        globalThis.fetch = fetch;
        globalThis.URL = URL;
        globalThis.URLSearchParams = URLSearchParams;
        globalThis.FormData = FormData;
        globalThis.atob = atob;
        globalThis.btoa = btoa;
        globalThis.console = console;

        // Helper to resolve relative URLs
        function resolveUrl(url, baseUrl) {
            if (!url) return '';
            if (url.startsWith('http://') || url.startsWith('https://')) return url;
            if (url.startsWith('data:') || url.startsWith('blob:')) return url;
            if (baseUrl) {
                var base = baseUrl.replace(/\/+${'$'}/, '');
                if (url.startsWith('/')) return base + url;
                return base + '/' + url;
            }
            return url;
        }
        globalThis.resolveUrl = resolveUrl;
        """.trimIndent()

    companion object {
        // Longest single setTimeout/setInterval delay honored. Keeps a stray long timer from
        // blocking evaluate() job-draining longer than the 30s executePluginMethod poll window.
        private const val MAX_TIMER_DELAY_MS = 15_000L
        private const val MAX_FETCH_BYTES = 16L * 1024 * 1024

        // Real-cheerio bundle string, read from assets once and reused across plugin runtimes.
        @Volatile
        private var cachedCheerioBundle: String? = null
        private val cheerioBundleLock = Any()
    }
}

