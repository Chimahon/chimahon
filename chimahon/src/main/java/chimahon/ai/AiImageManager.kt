package chimahon.ai

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.LruCache
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

data class AiWebImageItem(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val imageUrl: String,
    val width: Int = 0,
    val height: Int = 0,
)

enum class StagedImageSource(val storageValue: String, val label: String) {
    WEB_IMAGE("online", "Online image"),
    SCREENSHOT("screenshot", "Screenshot"),
    ;

    companion object {
        fun fromStorageValue(raw: String?): StagedImageSource =
            if (raw.equals("screenshot", ignoreCase = true)) SCREENSHOT else WEB_IMAGE
    }
}

data class StagedAiImage(
    val source: StagedImageSource,
    val previewBitmap: Bitmap,
    val jpegBytes: ByteArray,
    val webImageUrl: String? = null,
)

data class PersistedWordAttachment(
    val collectionId: String,
    val itemId: String,
    val imageFileName: String,
    val source: StagedImageSource,
    val previewBitmap: Bitmap?,
)

object AiImageManager {

    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val DDG_REFERER = "https://duckduckgo.com/"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_STORED_DIMENSION_PX = 1280
    private const val MAX_SCREENSHOT_DIMENSION_PX = 2400
    private const val MAX_THUMBNAIL_DIMENSION_PX = 360

    private val thumbnailCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
    }

    private val searchResultCache = LruCache<String, List<AiWebImageItem>>(32)

    fun getCachedThumbnail(url: String): Bitmap? = thumbnailCache.get(url)

    fun getCachedSearchResults(query: String): List<AiWebImageItem>? {
        val key = query.trim().lowercase()
        if (key.isEmpty()) return null
        return searchResultCache.get(key)?.takeIf { it.isNotEmpty() }
    }

    suspend fun loadThumbnailBitmap(url: String): Bitmap? {
        if (url.isBlank()) return null
        thumbnailCache.get(url)?.let { if (!it.isRecycled) return it }

        return withContext(Dispatchers.IO) {
            thumbnailCache.get(url)?.let { if (!it.isRecycled) return@withContext it }
            val bytes = downloadBytes(url, referer = DDG_REFERER, maxBytes = 4 * 1024 * 1024) ?: return@withContext null
            val decoded = decodeSampledBitmap(bytes, MAX_THUMBNAIL_DIMENSION_PX) ?: return@withContext null
            thumbnailCache.put(url, decoded)
            decoded
        }
    }

    suspend fun searchDuckDuckGoImages(
        context: Context,
        query: String,
        forceRefresh: Boolean = false,
    ): Result<List<AiWebImageItem>> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return Result.failure(IllegalArgumentException("No search term available."))
        }

        val cacheKey = cleanQuery.lowercase()
        if (!forceRefresh) {
            searchResultCache.get(cacheKey)?.takeIf { it.isNotEmpty() }?.let { cached ->
                return Result.success(cached)
            }
        }

        val httpAttempt = withContext(Dispatchers.IO) {
            searchViaHttp(cleanQuery)
        }
        val httpItems = httpAttempt.getOrNull().orEmpty()
        if (httpItems.isNotEmpty()) {
            searchResultCache.put(cacheKey, httpItems)
            return Result.success(httpItems)
        }

        val error = httpAttempt.exceptionOrNull()
        if (error is UnknownHostException) {
            return Result.failure(IllegalStateException("Offline — unable to reach image search."))
        }

        // Fallback 1: offscreen WebView with real layout bounds & live i.js interception
        val webViewResults = runCatching {
            searchViaWebViewFallback(context.applicationContext, cleanQuery)
        }.getOrDefault(emptyList())
        if (webViewResults.isNotEmpty()) {
            searchResultCache.put(cacheKey, webViewResults)
            return Result.success(webViewResults)
        }

        // Fallback 2: Bing async image search if DuckDuckGo temporarily throttles the IP
        val bingFallbackResults = withContext(Dispatchers.IO) {
            runCatching { searchViaBingImagesFallback(cleanQuery) }.getOrDefault(emptyList())
        }
        if (bingFallbackResults.isNotEmpty()) {
            searchResultCache.put(cacheKey, bingFallbackResults)
            return Result.success(bingFallbackResults)
        }

        return Result.failure(
            error ?: IllegalStateException("No images found for \"$cleanQuery\"."),
        )
    }

    private fun searchViaHttp(query: String): Result<List<AiWebImageItem>> {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val tokenPageUrl = "https://duckduckgo.com/?q=$encodedQuery&iax=images&ia=images"
            val html = fetchText(tokenPageUrl, referer = DDG_REFERER, syncCookiesWithDomain = DDG_REFERER)
                ?: return Result.failure(IllegalStateException("Failed to contact DuckDuckGo."))

            val vqd = extractVqdToken(html)
                ?: return Result.failure(IllegalStateException("Unable to initialize DuckDuckGo image token."))

            val encodedVqd = URLEncoder.encode(vqd, "UTF-8")
            val apiUrl = "https://duckduckgo.com/i.js?l=us-en&o=json&q=$encodedQuery&vqd=$encodedVqd&f=,,,&p=1"
            val jsonBody = fetchText(
                urlString = apiUrl,
                referer = tokenPageUrl,
                isXhr = true,
                syncCookiesWithDomain = DDG_REFERER,
            ) ?: return Result.failure(IllegalStateException("DuckDuckGo image request failed."))

            val items = parseDdgImageResultsJson(jsonBody)
            if (items.isEmpty()) {
                Result.failure(IllegalStateException("No images returned for \"$query\"."))
            } else {
                Result.success(items)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnknownHostException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(e.message ?: "Image search failed."))
        }
    }

    private suspend fun searchViaWebViewFallback(
        appContext: Context,
        query: String,
    ): List<AiWebImageItem> = withContext(Dispatchers.Main) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val targetUrl = "https://duckduckgo.com/?q=$encodedQuery&iax=images&ia=images"
        var webView: WebView? = null
        try {
            val capturedToken = withTimeoutOrNull(7_000L) {
                suspendCancellableCoroutine<String?> { cont ->
                    var resolved = false
                    fun completeOnce(token: String?) {
                        if (!resolved && !token.isNullOrBlank() && cont.isActive) {
                            resolved = true
                            runCatching { CookieManager.getInstance().flush() }
                            cont.resume(token)
                        }
                    }

                    val wv = WebView(appContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadsImagesAutomatically = false
                        settings.userAgentString = BROWSER_USER_AGENT
                        // Give the offscreen WebView realistic viewport bounds so DDG JS fires i.js
                        layout(0, 0, 1080, 1920)
                    }
                    webView = wv
                    cont.invokeOnCancellation {
                        runCatching {
                            wv.stopLoading()
                            wv.destroy()
                        }
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString().orEmpty()
                            if (!resolved && reqUrl.contains("duckduckgo.com/i.js")) {
                                request?.url?.getQueryParameter("vqd")?.takeIf { it.isNotBlank() }?.let {
                                    completeOnce(it)
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (resolved || view == null) return
                            val handler = Handler(Looper.getMainLooper())
                            var attempts = 0
                            val pollRunnable = object : Runnable {
                                override fun run() {
                                    if (resolved || !cont.isActive) return
                                    view.evaluateJavascript(
                                        """
                                        (function() {
                                            try {
                                                var html = document.documentElement ? document.documentElement.innerHTML : '';
                                                var m = html.match(/vqd=["']([^"']+)["']/) || html.match(/vqd=([0-9a-zA-Z_-]+)/);
                                                return m ? m[1] : '';
                                            } catch (e) {
                                                return '';
                                            }
                                        })();
                                        """.trimIndent(),
                                    ) { raw ->
                                        val jsVqd = raw?.trim()?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                                        if (!jsVqd.isNullOrBlank()) {
                                            completeOnce(jsVqd)
                                        } else if (attempts++ < 8 && !resolved && cont.isActive) {
                                            handler.postDelayed(this, 350L)
                                        }
                                    }
                                }
                            }
                            handler.postDelayed(pollRunnable, 250L)
                        }
                    }
                    wv.loadUrl(targetUrl)
                }
            }

            if (!capturedToken.isNullOrBlank()) {
                withContext(Dispatchers.IO) {
                    val apiUrl =
                        "https://duckduckgo.com/i.js?l=us-en&o=json&q=$encodedQuery&vqd=${URLEncoder.encode(capturedToken, "UTF-8")}&f=,,,&p=1"
                    val jsonBody = fetchText(
                        urlString = apiUrl,
                        referer = targetUrl,
                        isXhr = true,
                        syncCookiesWithDomain = DDG_REFERER,
                    ).orEmpty()
                    parseDdgImageResultsJson(jsonBody)
                }
            } else {
                emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching {
                webView?.stopLoading()
                webView?.destroy()
            }
        }
    }

    private fun searchViaBingImagesFallback(query: String): List<AiWebImageItem> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "https://www.bing.com/images/async?q=$encodedQuery&first=1&count=24&adlt=moderate"
        val html = fetchText(urlString = url, referer = "https://www.bing.com/images/search?q=$encodedQuery")
            ?: return emptyList()

        val metaAttrRegex = Regex("""\bm="(\{[^"]+\})"""")
        val results = mutableListOf<AiWebImageItem>()
        for (match in metaAttrRegex.findAll(html)) {
            if (results.size >= 24) break
            val encodedJson = match.groupValues.getOrNull(1) ?: continue
            val decodedJson = Html.fromHtml(encodedJson, Html.FROM_HTML_MODE_LEGACY).toString()
            val obj = runCatching { JSONObject(decodedJson) }.getOrNull() ?: continue
            val fullUrl = normalizeImageUrl(obj.optString("murl", ""))
            val thumbUrl = normalizeImageUrl(obj.optString("turl", "")).ifEmpty { fullUrl }
            val resolvedFull = fullUrl.ifEmpty { thumbUrl }
            if (thumbUrl.isEmpty()) continue
            results.add(
                AiWebImageItem(
                    id = "bing_${results.size}_${resolvedFull.hashCode()}",
                    title = obj.optString("t", "").trim(),
                    thumbnailUrl = thumbUrl,
                    imageUrl = resolvedFull,
                ),
            )
        }
        return results
    }

    private fun extractVqdToken(html: String): String? {
        val patterns = listOf(
            Regex("""vqd=["']([^"']+)["']"""),
            Regex("""vqd=([0-9a-zA-Z_-]+)"""),
            Regex(""""vqd"\s*:\s*"([^"]+)""""),
            Regex("""vqd%3D([0-9a-zA-Z_-]+)"""),
        )
        for (regex in patterns) {
            val match = regex.find(html)?.groupValues?.getOrNull(1)?.trim()
            if (!match.isNullOrEmpty()) return match
        }
        return null
    }

    private fun parseDdgImageResultsJson(rawJson: String): List<AiWebImageItem> {
        if (rawJson.isBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(rawJson)
            val arr = root.optJSONArray("results") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    if (size >= 24) break
                    val obj = arr.optJSONObject(i) ?: continue
                    val thumb = normalizeImageUrl(obj.optString("thumbnail", ""))
                    val full = normalizeImageUrl(obj.optString("image", ""))
                    val resolvedThumb = thumb.ifEmpty { full }
                    val resolvedFull = full.ifEmpty { thumb }
                    if (resolvedThumb.isEmpty()) continue
                    add(
                        AiWebImageItem(
                            id = "${i}_${resolvedFull.hashCode()}",
                            title = obj.optString("title", "").trim(),
                            thumbnailUrl = resolvedThumb,
                            imageUrl = resolvedFull,
                            width = obj.optInt("width", 0),
                            height = obj.optInt("height", 0),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun normalizeImageUrl(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> trimmed
        }
    }

    suspend fun downloadAndPrepareWebImage(item: AiWebImageItem): Result<StagedAiImage> =
        withContext(Dispatchers.IO) {
            try {
                val primaryBytes = downloadBytes(item.imageUrl, referer = DDG_REFERER, maxBytes = 8 * 1024 * 1024)
                val rawBytes = primaryBytes
                    ?: item.thumbnailUrl.takeIf { it.isNotBlank() && it != item.imageUrl }
                        ?.let { downloadBytes(it, referer = DDG_REFERER, maxBytes = 4 * 1024 * 1024) }
                    ?: return@withContext Result.failure(IllegalStateException("Failed to download selected image."))

                val bitmap = decodeSampledBitmap(rawBytes, MAX_STORED_DIMENSION_PX)
                    ?: return@withContext Result.failure(IllegalStateException("Could not decode image."))
                val jpegBytes = compressBitmapToJpeg(bitmap, quality = 85)
                    ?: return@withContext Result.failure(IllegalStateException("Could not encode image."))

                Result.success(
                    StagedAiImage(
                        source = StagedImageSource.WEB_IMAGE,
                        previewBitmap = bitmap,
                        jpegBytes = jpegBytes,
                        webImageUrl = item.imageUrl,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(IllegalStateException(e.message ?: "Failed to prepare image."))
            }
        }

    suspend fun prepareScreenshotImage(rawScreenshot: Bitmap): Result<StagedAiImage> =
        withContext(Dispatchers.Default) {
            try {
                val scaled = downscaleBitmapIfNeeded(rawScreenshot, MAX_SCREENSHOT_DIMENSION_PX)
                val jpegBytes = compressBitmapToJpeg(scaled, quality = 94)
                    ?: return@withContext Result.failure(IllegalStateException("Failed to encode screenshot."))
                Result.success(
                    StagedAiImage(
                        source = StagedImageSource.SCREENSHOT,
                        previewBitmap = scaled,
                        jpegBytes = jpegBytes,
                        webImageUrl = null,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(IllegalStateException(e.message ?: "Failed to process screenshot."))
            }
        }

    /**
     * Captures the underlying Activity window at native full-screen resolution via PixelCopy
     * (excluding separate Compose Popup windows) so the screenshot shows the live reading
     * screen without closing or dismissing the popup.
     */
    suspend fun loadBitmapFromFile(file: File?): Bitmap? = withContext(Dispatchers.IO) {
        if (file == null || !file.exists() || file.length() == 0L) return@withContext null
        runCatching {
            val bytes = file.readBytes()
            decodeSampledBitmap(bytes, MAX_STORED_DIMENSION_PX)
        }.getOrNull()
    }

    private fun fetchText(
        urlString: String,
        referer: String,
        isXhr: Boolean = false,
        syncCookiesWithDomain: String? = null,
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            val cookieManager = if (syncCookiesWithDomain != null) {
                runCatching { CookieManager.getInstance() }.getOrNull()
            } else {
                null
            }
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", BROWSER_USER_AGENT)
                setRequestProperty("Referer", referer)
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                if (syncCookiesWithDomain != null) {
                    val existingCookies = runCatching { cookieManager?.getCookie(syncCookiesWithDomain) }.getOrNull()
                    if (!existingCookies.isNullOrBlank()) {
                        setRequestProperty("Cookie", existingCookies)
                    }
                }
                if (isXhr) {
                    setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
                    setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    setRequestProperty("Sec-Fetch-Dest", "empty")
                    setRequestProperty("Sec-Fetch-Mode", "cors")
                    setRequestProperty("Sec-Fetch-Site", "same-origin")
                } else {
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    setRequestProperty("Sec-Fetch-Dest", "document")
                    setRequestProperty("Sec-Fetch-Mode", "navigate")
                    setRequestProperty("Sec-Fetch-Site", "none")
                }
            }
            val code = conn.responseCode
            if (syncCookiesWithDomain != null && cookieManager != null) {
                conn.headerFields["Set-Cookie"]?.forEach { rawCookie ->
                    runCatching { cookieManager.setCookie(syncCookiesWithDomain, rawCookie) }
                }
            }
            if (code !in 200..299) return null
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn?.disconnect()
        }
    }

    private fun downloadBytes(
        urlString: String,
        referer: String,
        maxBytes: Int,
    ): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", BROWSER_USER_AGENT)
                setRequestProperty("Referer", referer)
                setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            }
            val code = conn.responseCode
            if (code !in 200..299) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) return null
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun decodeSampledBitmap(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
        val rawW = boundsOpts.outWidth
        val rawH = boundsOpts.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        var sampleSize = 1
        while (rawW / sampleSize > maxDimension * 2 || rawH / sampleSize > maxDimension * 2) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null
        return downscaleBitmapIfNeeded(decoded, maxDimension)
    }

    private fun downscaleBitmapIfNeeded(source: Bitmap, maxDimension: Int): Bitmap {
        val w = source.width
        val h = source.height
        val longest = max(w, h)
        if (longest <= maxDimension) return source
        val scale = maxDimension.toFloat() / longest.toFloat()
        val targetW = (w * scale).roundToInt().coerceAtLeast(1)
        val targetH = (h * scale).roundToInt().coerceAtLeast(1)
        return runCatching {
            Bitmap.createScaledBitmap(source, targetW, targetH, true)
        }.getOrDefault(source)
    }

    private fun compressBitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray? = runCatching {
        ByteArrayOutputStream().use { out ->
            if (bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                out.toByteArray()
            } else {
                null
            }
        }
    }.getOrNull()
}

@Composable
fun AiRemoteThumbnail(
    url: String,
    contentDescription: String?,
    placeholderColor: Color,
    indicatorColor: Color,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(url) { mutableStateOf(AiImageManager.getCachedThumbnail(url)) }
    var loadFailed by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url) {
        if (bitmap == null || bitmap?.isRecycled == true) {
            loadFailed = false
            val fetched = AiImageManager.loadThumbnailBitmap(url)
            if (fetched != null) {
                bitmap = fetched
            } else {
                loadFailed = true
            }
        }
    }

    Box(
        modifier = modifier.background(placeholderColor),
        contentAlignment = Alignment.Center,
    ) {
        val activeBitmap = bitmap
        when {
            activeBitmap != null && !activeBitmap.isRecycled -> {
                Image(
                    bitmap = activeBitmap.asImageBitmap(),
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            loadFailed -> {
                Icon(
                    imageVector = Icons.Rounded.BrokenImage,
                    contentDescription = null,
                    tint = indicatorColor.copy(alpha = 0.55f),
                    modifier = Modifier.size(20.dp),
                )
            }
            else -> {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = indicatorColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
