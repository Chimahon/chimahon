package chimahon.novel.plugin

import android.content.Context
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import chimahon.novel.plugin.runtime.NovelPluginRuntime
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.sourcenovel.HttpNovelSource
import eu.kanade.tachiyomi.sourcenovel.NovelConfigurableSource
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.NovelPage
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.Executors

class SimpleLNReaderSource(
    private val context: Context,
    val pluginId: String,
    val pluginName: String,
    val pluginLang: String,
    val siteUrl: String,
    private val jsCode: String,
    val codeUrl: String = "",
) : NovelsPageSource, NovelConfigurableSource, HttpNovelSource {
    override val id: Long = pluginId.hashCode().toLong() and Long.MAX_VALUE
    override val name: String = pluginName
    override val lang: String = pluginLang
    val baseUrl: String get() = siteUrl

    override fun getNovelUrl(novel: SNNovel): String? {
        val path = novel.url.trim()
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = siteUrl.trim().trimEnd('/')
        if (base.isBlank() || path.isBlank()) return null
        return base + "/" + path.trimStart('/')
    }

    override fun getChapterUrl(chapter: SNChapter): String? {
        val path = chapter.url.trim()
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = siteUrl.trim().trimEnd('/')
        if (base.isBlank() || path.isBlank()) return null
        return base + "/" + path.trimStart('/')
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    // Serializes JS executions of this source (see withPlugin).
    private val jsMutex = Mutex()
    // Novel: single shared executor per process to avoid thread leak (one per source → 50 threads)
    private var cachedJsCode: String? = if (jsCode.isNotBlank()) jsCode else null
    // Novel: bridgeCache for filters/settings so getFilterList never init QuickJS on main thread
    private val bridgeCache = context.getSharedPreferences("novel_plugin_bridge_cache", Context.MODE_PRIVATE)
    private val bridgeCacheKey = "$pluginId:${codeUrl.hashCode()}"
    @Volatile private var filtersJsonCache: String? = bridgeCache.getString("$bridgeCacheKey:filters", null)
    @Volatile private var pluginSettingsJsonCache: String? = bridgeCache.getString("$bridgeCacheKey:settings", null)

    companion object {
        private val sharedExecutor = Executors.newCachedThreadPool { r -> Thread(r, "LNReader-shared").apply { isDaemon = true } }
        private val sharedDispatcher = sharedExecutor.asCoroutineDispatcher()
        // QuickJS runtimes are single-threaded: all JNI calls for every plugin instance
        // are confined here, otherwise evaluate/close land on random pool threads and fail
        // ("Cannot get jni env because the vm is not cached") with thread-luck flakiness.
        private val jsDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "LNReader-js").apply { isDaemon = true } }.asCoroutineDispatcher()
    }

    private suspend fun ensureJsCode(): String {
        cachedJsCode?.let { return it }
        if (jsCode.isNotBlank()) {
            cachedJsCode = jsCode
            return jsCode
        }
        val localFile = java.io.File(java.io.File(context.filesDir, "novel-plugins"), "$pluginId.js")
        if (localFile.exists() && localFile.length() > 0) {
            val code = try { localFile.readText() } catch (_: Exception) { "" }
            if (code.isNotBlank()) {
                cachedJsCode = code
                return code
            }
        }
        val url = codeUrl.takeIf { it.isNotBlank() } ?: return ""
        return withContext(Dispatchers.IO) {
            try {
                val client = Injekt.get<NetworkHelper>().client
                val req = Request.Builder().url(url)
                    .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) {
                    logcat(LogPriority.WARN) { "[$pluginId] fetch JS failed: ${resp.code}" }
                    return@withContext ""
                }
                val body = resp.body.string()
                if (body.isNotBlank()) {
                    cachedJsCode = body
                    try { localFile.writeText(body) } catch (_: Exception) {}
                }
                body
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "[$pluginId] fetch JS error $url" }
                ""
            }
        }
    }

    private suspend fun <T> withPlugin(block: suspend (chimahon.novel.plugin.runtime.NovelPluginInstance) -> T): T {
        // One JS execution per source at a time: concurrent loads (e.g. Popular
        // + Latest firing together on first open) created two runtimes sharing
        // the single JS thread and tripped JNI affinity failures.
        return jsMutex.withLock {
            val code = ensureJsCode()
            if (code.isBlank()) throw IllegalStateException("JS code not available for $pluginId (site=$siteUrl codeUrl=$codeUrl)")
            val runtime = NovelPluginRuntime(context, pluginId, siteUrl, jsDispatcher)
            val instance = runtime.open(code)
            try {
                block(instance)
            } finally {
                instance.close()
            }
        }
    }

    private fun jsonQuote(raw: String): String = Json.encodeToString(kotlinx.serialization.serializer<String>(), raw)

    private fun mapStatus(raw: String?): Int = when (raw?.trim()) {
        "Ongoing", "ONGOING" -> SNNovel.ONGOING
        "Completed", "COMPLETED" -> SNNovel.COMPLETED
        "Licensed", "LICENSED" -> SNNovel.LICENSED
        "Publishing Finished", "PublishingFinished" -> SNNovel.PUBLISHING_FINISHED
        "Cancelled", "CANCELLED" -> SNNovel.CANCELLED
        "On Hiatus", "OnHiatus", "Hiatus" -> SNNovel.ON_HIATUS
        else -> SNNovel.UNKNOWN
    }

    private fun parseDateToLong(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        // try epoch millis string
        raw.toLongOrNull()?.let { if (it > 1_000_000_000L) return it; if (it > 0) return it * 1000 }
        return try {
            java.time.Instant.parse(raw).toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    java.time.LocalDate.parse(raw.trim()).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    try {
                        java.time.LocalDateTime.parse(raw.trim()).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    } catch (_: Exception) { 0L }
                }
            }
        }
    }

    private fun jsonObjectToSNNovel(obj: JsonObject, sourceId: Long = id): SNNovel {
        val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: obj["url"]?.jsonPrimitive?.contentOrNull ?: ""
        val title = obj["name"]?.jsonPrimitive?.contentOrNull ?: obj["title"]?.jsonPrimitive?.contentOrNull ?: ""
        val cover = obj["cover"]?.jsonPrimitive?.contentOrNull
        return SNNovel(
            url = path,
            title = title,
            thumbnail_url = cover?.takeIf { it.isNotBlank() },
            author = obj["author"]?.jsonPrimitive?.contentOrNull,
            artist = obj["artist"]?.jsonPrimitive?.contentOrNull,
            description = obj["summary"]?.jsonPrimitive?.contentOrNull ?: obj["description"]?.jsonPrimitive?.contentOrNull,
            genre = (obj["genres"]?.jsonPrimitive?.contentOrNull ?: obj["genre"]?.jsonPrimitive?.contentOrNull),
            status = mapStatus(obj["status"]?.jsonPrimitive?.contentOrNull),
            initialized = true,
            source = sourceId,
        )
    }

    private fun sourceNovelJsonToSNNovel(obj: JsonObject): SNNovel {
        val base = jsonObjectToSNNovel(obj)
        return base.copy(
            genre = obj["genres"]?.jsonPrimitive?.contentOrNull ?: base.genre,
            description = obj["summary"]?.jsonPrimitive?.contentOrNull ?: obj["description"]?.jsonPrimitive?.contentOrNull ?: base.description,
        )
    }

    private suspend fun evaluateToString(instance: chimahon.novel.plugin.runtime.NovelPluginInstance, script: String): String? {
        return try {
            val res = instance.evaluate(script)
            when (res) {
                null -> null
                is String -> res
                else -> res.toString()
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "[$pluginId] JS evaluate failed: ${script.take(200)}" }
            null
        }
    }

    private fun parseNovelItemsArray(jsonStr: String?): List<SNNovel> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            val element = json.parseToJsonElement(jsonStr)
            when (element) {
                is JsonArray -> element.mapNotNull { it as? JsonObject }.map { jsonObjectToSNNovel(it) }
                is JsonObject -> {
                    // some plugins return {novels: []}
                    val arr = element["novels"] as? JsonArray ?: element["items"] as? JsonArray
                    arr?.mapNotNull { it as? JsonObject }?.map { jsonObjectToSNNovel(it) } ?: emptyList()
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "[$pluginId] parseNovelItems failed" }
            emptyList()
        }
    }

    private suspend fun fetchPopular(page: Int, showLatest: Boolean, searchTerm: String? = null): NovelPage {
        return withPlugin { instance ->
            val isSearch = searchTerm != null
            val script = if (isSearch) {
                val q = jsonQuote(searchTerm!!)
                // searchNovels(searchTerm, page)
                "JSON.stringify(await plugin.searchNovels($q, $page))"
            } else {
                // popularNovels(page, {showLatestNovels: bool, filters: {}})
                val showLatestStr = if (showLatest) "true" else "false"
                "JSON.stringify(await plugin.popularNovels($page, {showLatestNovels: $showLatestStr, filters: (plugin.filters || {})}))"
            }
            val jsonStr = evaluateToString(instance, script) ?: "[]"
            if (jsonStr.contains("\"__error\"")) {
                logcat(LogPriority.WARN) { "[$pluginId] JS error: $jsonStr" }
                return@withPlugin NovelPage(emptyList(), false)
            }
            val novels = parseNovelItemsArray(jsonStr)
            // LNReader plugins do not report hasNextPage; infer via page size (assume 20? use non-empty as next)
            val hasNext = novels.isNotEmpty()
            NovelPage(novels, hasNext)
        }
    }

    // ---- NovelsPageSource ----

    override val supportsLatest: Boolean = true

    override suspend fun getPopularNovels(page: Int): NovelPage = fetchPopular(page, showLatest = false)

    override suspend fun getLatestUpdates(page: Int): NovelPage = fetchPopular(page, showLatest = true)

    override suspend fun getSearchNovels(page: Int, query: String, filters: FilterList): NovelPage {
        // If query blank and filters present, fall back to popular with filters
        if (query.isBlank()) {
            // Build filters object from FilterList
            val filtersJson = filterListToJsObject(filters)
            return withPlugin { instance ->
                val filtersLiteral = filtersJson.ifBlank { "{}" }
                val showLatestStr = "false"
                val script = if (filtersLiteral == "{}") {
                    "JSON.stringify(await plugin.popularNovels($page, {showLatestNovels: $showLatestStr, filters: (plugin.filters || {})}))"
                } else {
                    "JSON.stringify(await plugin.popularNovels($page, {showLatestNovels: $showLatestStr, filters: Object.assign({}, plugin.filters || {}, $filtersLiteral)}))"
                }
                val jsonStr = evaluateToString(instance, script) ?: "[]"
                val novels = parseNovelItemsArray(jsonStr)
                NovelPage(novels, novels.isNotEmpty())
            }
        }
        return fetchPopular(page, showLatest = false, searchTerm = query)
    }

    private fun filterListToJsObject(filters: FilterList): String {
        if (filters.isEmpty()) return "{}"
        // Build { key: {value: ..., type: ...} } from Filter states
        // We need the original filter keys; Filter.name holds label, not key.
        // For now map by label lowercased as key, value = state
        return try {
            val entries = filters.list.map { filter ->
                val key = filter.name.replace(" ", "_").lowercase()
                val valueJson: String = when (filter) {
                    is Filter.Select<*> -> {
                        val idx = filter.state
                        val v = filter.values.getOrNull(idx)?.toString() ?: ""
                        Json.encodeToString(kotlinx.serialization.serializer<String>(), v)
                    }
                    is Filter.Text -> Json.encodeToString(kotlinx.serialization.serializer<String>(), filter.state)
                    is Filter.CheckBox -> filter.state.toString()
                    is Filter.TriState -> filter.state.toString()
                    is Filter.Group<*> -> {
                        // CheckboxGroup -> array of selected values?
                        val selected = filter.state.mapNotNull { (it as? Filter.CheckBox)?.let { cb -> if (cb.state) cb.name else null } }
                        Json.encodeToString(kotlinx.serialization.serializer<List<String>>(), selected)
                    }
                    else -> Json.encodeToString(kotlinx.serialization.serializer<String>(), filter.state.toString())
                }
                val type = when (filter) {
                    is Filter.Select<*> -> "Picker"
                    is Filter.Text -> "TextInput"
                    is Filter.CheckBox -> "Switch"
                    is Filter.TriState -> "ExcludableCheckboxGroup"
                    is Filter.Group<*> -> "CheckboxGroup"
                    else -> "Text"
                }
                "\"$key\": {\"value\": $valueJson, \"type\": \"$type\"}"
            }
            "{${entries.joinToString(",")}}"
        } catch (_: Exception) { "{}" }
    }

    override fun getFilterList(): FilterList {
        // Novel: never init QuickJS on UI thread – read from bridgeCache, warmUp async if missing
        filtersJsonCache?.let { cached ->
            try { return parseFiltersJson(cached) } catch (_: Exception) {}
        }
        // Trigger async warmUp to populate cache for next call (fire-and-forget)
        try {
            kotlinx.coroutines.CoroutineScope(sharedDispatcher).launchWithExpeditedCheck()
        } catch (_: Exception) {}
        return FilterList()
    }

    private fun kotlinx.coroutines.CoroutineScope.launchWithExpeditedCheck() {
        launch {
            try {
                withPlugin { instance ->
                    val jsonStr = evaluateToString(instance, "JSON.stringify(plugin.filters ?? {})") ?: "{}"
                    if (jsonStr.isNotBlank() && jsonStr != "{}") {
                        filtersJsonCache = jsonStr
                        bridgeCache.edit().putString("$bridgeCacheKey:filters", jsonStr).apply()
                    }
                    val settingsStr = evaluateToString(instance, "JSON.stringify(plugin.pluginSettings ?? {})") ?: "{}"
                    if (settingsStr.isNotBlank() && settingsStr != "{}") {
                        pluginSettingsJsonCache = settingsStr
                        bridgeCache.edit().putString("$bridgeCacheKey:settings", settingsStr).apply()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun parseFiltersJson(jsonStr: String): FilterList {
        if (jsonStr.isBlank() || jsonStr == "{}") return FilterList()
        return try {
            val obj = json.parseToJsonElement(jsonStr) as? JsonObject ?: return FilterList()
            if (obj.isEmpty()) return FilterList()
            val filters = mutableListOf<Filter<*>>()
            // Preserve header
            filters.add(Filter.Header("LNReader Filters"))
            for ((key, defEl) in obj) {
                val def = defEl as? JsonObject ?: continue
                val label = def["label"]?.jsonPrimitive?.contentOrNull ?: key
                val type = def["type"]?.jsonPrimitive?.contentOrNull ?: "TextInput"
                when (type) {
                    "Picker" -> {
                        val options = (def["options"] as? JsonArray)?.mapNotNull {
                            (it as? JsonObject)?.get("label")?.jsonPrimitive?.contentOrNull ?: it.jsonPrimitive.contentOrNull
                        } ?: emptyList()
                        val values = (def["options"] as? JsonArray)?.mapNotNull {
                            (it as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull
                        } ?: options
                        val defaultValue = def["value"]?.jsonPrimitive?.contentOrNull ?: ""
                        val idx = values.indexOf(defaultValue).coerceAtLeast(0)
                        val display = options.ifEmpty { values }.toTypedArray()
                        filters.add(object : Filter.Select<String>(label, display, idx) {})
                    }
                    "TextInput", "Text" -> {
                        val defaultValue = def["value"]?.jsonPrimitive?.contentOrNull ?: ""
                        filters.add(object : Filter.Text(label, defaultValue) {})
                    }
                    "Switch" -> {
                        val defaultValue = def["value"]?.let {
                            when (it) {
                                is JsonPrimitive -> it.booleanOrNull ?: (it.contentOrNull == "true")
                                else -> false
                            }
                        } ?: false
                        filters.add(object : Filter.CheckBox(label, defaultValue) {})
                    }
                    "CheckboxGroup" -> {
                        val options = (def["options"] as? JsonArray)?.mapNotNull {
                            val o = it as? JsonObject ?: return@mapNotNull null
                            val lab = o["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            val v = o["value"]?.jsonPrimitive?.contentOrNull ?: lab
                            lab to v
                        } ?: emptyList()
                        val selectedVals = when (val v = def["value"]) {
                            is JsonArray -> v.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                            is JsonPrimitive -> setOf(v.contentOrNull ?: "")
                            else -> emptySet()
                        }
                        val groupItems = options.map { (lab, _) ->
                            object : Filter.CheckBox(lab, selectedVals.contains(lab)) {}
                        }
                        filters.add(object : Filter.Group<Filter.CheckBox>(label, groupItems) {})
                    }
                    "ExcludableCheckboxGroup", "XCheckbox" -> {
                        // Map to tri-state group
                        val options = (def["options"] as? JsonArray)?.mapNotNull {
                            val o = it as? JsonObject ?: return@mapNotNull null
                            o["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        } ?: emptyList()
                        // For tri-state we create TriState per option
                        val groupItems = options.map { lab ->
                            object : Filter.TriState(lab, 0) {}
                        }
                        filters.add(object : Filter.Group<Filter.TriState>(label, groupItems) {})
                    }
                    else -> {
                        val defaultValue = def["value"]?.jsonPrimitive?.contentOrNull ?: ""
                        filters.add(object : Filter.Text(label, defaultValue) {})
                    }
                }
            }
            FilterList(filters)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "[$pluginId] parseFiltersJson failed: $jsonStr" }
            FilterList()
        }
    }

    // ---- NovelSource ----

    override suspend fun getNovelDetails(novel: SNNovel): SNNovel {
        return withPlugin { instance ->
            val path = jsonQuote(novel.url)
            val script = "JSON.stringify(await plugin.parseNovel($path))"
            val jsonStr = evaluateToString(instance, script) ?: "{}"
            if (jsonStr.contains("\"__error\"")) {
                logcat(LogPriority.WARN) { "[$pluginId] parseNovel error: $jsonStr" }
                return@withPlugin novel
            }
            try {
                val obj = json.parseToJsonElement(jsonStr) as? JsonObject ?: return@withPlugin novel
                val mapped = sourceNovelJsonToSNNovel(obj).copy(url = novel.url, title = novel.title.ifBlank { obj["name"]?.jsonPrimitive?.contentOrNull ?: novel.title })
                // Preserve incoming id/source if needed
                mapped.copy(id = novel.id, source = id)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "[$pluginId] getNovelDetails parse failed" }
                novel
            }
        }
    }

    override suspend fun getChapterList(novel: SNNovel): List<SNChapter> {
        return withPlugin { instance ->
            val path = jsonQuote(novel.url)
            val script = "JSON.stringify(await plugin.parseNovel($path))"
            val jsonStr = evaluateToString(instance, script) ?: "{}"
            if (jsonStr.contains("\"__error\"")) {
                logcat(LogPriority.WARN) { "[$pluginId] parseNovel error: $jsonStr" }
                return@withPlugin emptyList()
            }
            try {
                val obj = json.parseToJsonElement(jsonStr) as? JsonObject ?: return@withPlugin emptyList()
                val arr = obj["chapters"] as? JsonArray ?: obj["chapterList"] as? JsonArray ?: obj["chaptersList"] as? JsonArray ?: run {
                    logcat(LogPriority.WARN) { "[$pluginId] getChapterList: no chapters field in $jsonStr" }
                    return@withPlugin emptyList()
                }
                val chapters = arr.mapIndexedNotNull { idx, el ->
                    val o = el as? JsonObject ?: return@mapIndexedNotNull null
                    val name = o["name"]?.jsonPrimitive?.contentOrNull ?: o["title"]?.jsonPrimitive?.contentOrNull ?: "Chapter ${idx + 1}"
                    val path2 = o["path"]?.jsonPrimitive?.contentOrNull ?: o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
                    val releaseTime = o["releaseTime"]?.jsonPrimitive?.contentOrNull ?: o["date"]?.jsonPrimitive?.contentOrNull
                    val chapterNumber = o["chapterNumber"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
                        ?: o["chapterNumber"]?.jsonPrimitive?.let { runCatching { it.toString().toFloat() }.getOrNull() } ?: (idx + 1).toFloat()
                    val scanlator = (o["scanlator"] as? JsonPrimitive)?.contentOrNull
                        ?: (o["scanlator"] as? JsonArray)?.joinToString(",") { it.jsonPrimitive.contentOrNull ?: "" }
                    SNChapter(
                        name = name,
                        url = path2,
                        chapter_number = chapterNumber,
                        date_upload = parseDateToLong(releaseTime),
                        scanlator = scanlator,
                    )
                }.toMutableList()

                // Novel parity: handle paged sources (totalPages / parsePage) – if chapters empty or totalPages>1, probe parsePage
                val totalPages = try {
                    obj["totalPages"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                } catch (_: Exception) { 1 }
                if (totalPages > 1 || chapters.isEmpty()) {
                    val hasParsePage = evaluateToString(instance, "typeof plugin.parsePage === 'function'") == "true"
                    val maxPages = if (totalPages > 0) totalPages else 1
                    val startPage = if (chapters.isEmpty()) 1 else 2
                    if (hasParsePage && startPage <= maxPages) {
                        for (page in startPage..maxPages) {
                            try {
                                val pageScript = "JSON.stringify(await plugin.parsePage($path, $page))"
                                val pageJson = evaluateToString(instance, pageScript) ?: continue
                                if (pageJson.contains("\"__error\"")) continue
                                val pageObj = json.parseToJsonElement(pageJson) as? JsonObject ?: continue
                                val pageArr = pageObj["chapters"] as? JsonArray ?: pageObj["chapterList"] as? JsonArray ?: continue
                                val pageChapters = pageArr.mapIndexedNotNull { idx, el ->
                                    val o = el as? JsonObject ?: return@mapIndexedNotNull null
                                    val name = o["name"]?.jsonPrimitive?.contentOrNull ?: "Chapter ${idx + 1}"
                                    val p2 = o["path"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
                                    SNChapter(name = name, url = p2, chapter_number = (chapters.size + idx + 1).toFloat())
                                }
                                chapters.addAll(pageChapters)
                            } catch (e: Exception) {
                                logcat(LogPriority.WARN, e) { "[$pluginId] parsePage $page failed" }
                            }
                        }
                    }
                }
                // Newest-first, so sourceOrder 0 = latest.
                // LNReader plugins list oldest-first; reverse to match.
                if (chapters.size > 1 && chapters[0].chapter_number < chapters.last().chapter_number) {
                    chapters.reverse()
                }
                logcat(LogPriority.DEBUG) { "[$pluginId] getChapterList parsed ${chapters.size} chapters for ${novel.url}" }
                chapters
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "[$pluginId] getChapterList failed for ${novel.url}" }
                emptyList()
            }
        }
    }

    override suspend fun getChapterContent(chapter: SNChapter): ChapterContent {
        return withPlugin { instance ->
            val path = jsonQuote(chapter.url)
            val script = "await plugin.parseChapter($path)"
            val raw = evaluateToString(instance, script) ?: ""
            // plugin returns HTML string; detect if error object
            if (raw.contains("\"__error\"")) {
                logcat(LogPriority.WARN) { "[$pluginId] parseChapter error: $raw" }
                return@withPlugin ChapterContent.Text("")
            }
            // The raw is the JS string result (already stringified once if needed). If it's JSON-quoted, decode.
            val html = try {
                // evaluateToString already returns string; if plugin returned string, JS returns string directly.
                // If we used JSON.stringify wrapper, it would be quoted; but we didn't.
                raw
            } catch (_: Exception) { raw }
            if (html.isBlank()) return@withPlugin ChapterContent.Text("")
            // Return Html if contains tags, else Text
            if (html.contains("<") && html.contains(">")) ChapterContent.Html(html) else ChapterContent.Text(html)
        }
    }

    // ---- File/download sources (PluginBase.getDownloadUrl) ----

    suspend fun hasDownloadSupport(): Boolean {
        return try {
            withPlugin { instance ->
                evaluateToString(instance, "typeof plugin.getDownloadUrl === 'function'") == "true"
            }
        } catch (_: Exception) { false }
    }

    /**
     * Resolves the direct file URL for a book path via the plugin's
     * `getDownloadUrl(path, format?)`. Returns null when unsupported,
     * failed, or empty (e.g. file-less metadata entries).
     */
    suspend fun getDownloadUrl(path: String, format: String? = null): String? {
        return try {
            withPlugin { instance ->
                val quotedPath = jsonQuote(path)
                val script = if (format.isNullOrBlank()) {
                    "await plugin.getDownloadUrl($quotedPath)"
                } else {
                    "await plugin.getDownloadUrl($quotedPath, ${jsonQuote(format)})"
                }
                val raw = evaluateToString(instance, script) ?: return@withPlugin null
                if (raw.contains("\"__error\"")) {
                    logcat(LogPriority.WARN) { "[$pluginId] getDownloadUrl error: $raw" }
                    return@withPlugin null
                }
                // evaluateToString returns the raw JS string; decode once in
                // case it arrived JSON-quoted.
                val url = try {
                    json.parseToJsonElement(raw).let {
                        (it as? JsonPrimitive)?.contentOrNull ?: raw
                    }
                } catch (_: Exception) { raw }
                url.trim().takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "[$pluginId] getDownloadUrl failed for $path" }
            null
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Bind to the same SharedPreferences that NovelPluginLibrary uses for storage
        screen.preferenceManager.sharedPreferencesName = "jsplugin_storage_$pluginId"
        screen.preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        try {
            val settingsJson = kotlinx.coroutines.runBlocking {
                withContext(Dispatchers.IO) {
                    try {
                        withPlugin { instance ->
                            evaluateToString(instance, "JSON.stringify(plugin.pluginSettings ?? {})") ?: "{}"
                        }
                    } catch (_: Exception) { "{}" }
                }
            }
            if (settingsJson.isBlank() || settingsJson == "{}") return
            val obj = try { json.parseToJsonElement(settingsJson) as? JsonObject } catch (_: Exception) { null } ?: return
            if (obj.isEmpty()) return
            for ((key, defEl) in obj) {
                val def = defEl as? JsonObject ?: continue
                val label = def["label"]?.jsonPrimitive?.contentOrNull ?: key
                val type = def["type"]?.jsonPrimitive?.contentOrNull ?: "Text"
                val defaultValue = def["value"]?.let {
                    when (it) {
                        is JsonPrimitive -> it.contentOrNull ?: ""
                        else -> it.toString()
                    }
                } ?: ""
                when (type) {
                    "Switch" -> {
                        val pref = SwitchPreferenceCompat(screen.context).apply {
                            this.key = key
                            this.title = label
                            setDefaultValue(defaultValue == "true" || defaultValue == "1")
                            isPersistent = true
                        }
                        screen.addPreference(pref)
                    }
                    else -> {
                        val pref = EditTextPreference(screen.context).apply {
                            this.key = key
                            this.title = label
                            setDefaultValue(defaultValue)
                            isPersistent = true
                            summaryProvider = androidx.preference.Preference.SummaryProvider<EditTextPreference> { p ->
                                p.text?.takeIf { it.isNotBlank() } ?: "Not set"
                            }
                        }
                        screen.addPreference(pref)
                    }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "[$pluginId] setupPreferenceScreen failed" }
        }
    }
}


