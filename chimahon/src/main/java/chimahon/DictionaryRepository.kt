package chimahon

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import chimahon.diagnostics.LookupProfiler
import chimahon.anki.AnkiProfile
import chimahon.dictionary.ko.KoreanAnalyzerDeinflector
import chimahon.dictionary.ko.KoreanParserMode
import chimahon.ocr.effectiveSearchResolution
import chimahon.ocr.nextWordBoundarySubstring
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap

class DictionaryRepository(
    private val externalFilesDir: File?,
    private val koreanParserMode: () -> String = { KoreanParserMode.Legacy },
) {
    private var session: Long? = null
    val lookupSession: Long? get() = session
    private var configuredPaths: DictionaryPaths = DictionaryPaths()
    private var cachedStyles: List<DictionaryStyle> = emptyList()
    /** ID of the profile that was last used to call [warmUp].  Prevents redundant engine rebuilds. */
    private var lastWarmedProfileId: String = ""

    /**
     * Exact-match slot for the most recently completed search-resolution lookup.
     *
     * Hosts often query once for anchor/highlight geometry and the popup queries the same word
     * immediately afterward. Keeping only the latest result means the second identical call does not
     * repeat the native query, without building a long-lived dictionary cache. A hit is consumed, so
     * the slot cannot serve stale results to an unbounded sequence of later lookups.
     */
    private data class CoalescedLookupKey(
        val query: String,
        val languageCode: String,
        val profile: AnkiProfile,
        val koreanParserMode: String,
        val paths: DictionaryPaths,
    )

    private data class CoalescedLookup(
        val key: CoalescedLookupKey,
        val result: LookupResult2,
    )

    private val coalescedLookupLock = Any()
    private var coalescedLookup: CoalescedLookup? = null

    private fun clearCoalescedLookup() {
        synchronized(coalescedLookupLock) {
            coalescedLookup = null
        }
    }

    /**
     * Guards the native query session. Lookups take the READ lock so any number of them run
     * concurrently; anything that can invalidate the session (engine rebuild, destroy) takes the
     * WRITE lock and therefore waits for in-flight lookups to finish. This replaces
     * `@Synchronized` on every method, which serialised every lookup behind the slowest one and -
     * because the popup ran recursive lookups on the main thread - froze the UI whenever a
     * background lookup happened to be mid-query.
     *
     * `ReentrantReadWriteLock` cannot upgrade read -> write, so [lookup] warms up BEFORE taking the
     * read lock rather than calling [warmUp] from inside it.
     */
    private val sessionLock = java.util.concurrent.locks.ReentrantReadWriteLock()

    /**
     * Warm up the query engine for [paths].
     * An engine rebuild is only triggered when [paths] or [profileId] differ
     * from the last warmed state, so calling this at every reader-open is safe.
     *
     * @param profileId optional profile ID for dedup tracking (empty = legacy callers)
     */
    fun warmUp(paths: DictionaryPaths, profileId: String = "", profilingId: Long = 0) {
        // Cheap check first, under the read lock, so the common "nothing changed" case never
        // contends with concurrent lookups. rebuildQuery + getStyles are the expensive part and
        // only belong behind the write lock.
        val needsRebuild = sessionLock.readLock().run {
            LookupProfiler.section(profilingId, "lock.warmup_read.wait") { lock() }
            try {
                session == null || paths != configuredPaths ||
                    (profileId.isNotEmpty() && profileId != lastWarmedProfileId)
            } finally {
                unlock()
            }
        }
        if (!needsRebuild) return

        LookupProfiler.section(profilingId, "lock.write.wait") { sessionLock.writeLock().lock() }
        try {
            val activeSession = session ?: LookupProfiler.section(profilingId, "engine.create") { HoshiDicts.createLookupObject() }.also { session = it }
            // Re-check: another thread may have warmed it while we waited for the write lock.
            val pathsChanged = paths != configuredPaths
            val profileChanged = profileId.isNotEmpty() && profileId != lastWarmedProfileId
            if (!pathsChanged && !profileChanged) return
            LookupProfiler.section(profilingId, "engine.rebuild") { HoshiDicts.rebuildQuery(
                session = activeSession,
                termPaths = paths.termPaths.toTypedArray(),
                freqPaths = paths.freqPaths.toTypedArray(),
                pitchPaths = paths.pitchPaths.toTypedArray(),
                kanjiPaths = paths.kanjiPaths.toTypedArray(),
            ) }
            cachedStyles = LookupProfiler.section(profilingId, "engine.styles") { HoshiDicts.getStyles(activeSession).toList() }
            configuredPaths = paths
            if (profileId.isNotEmpty()) lastWarmedProfileId = profileId
            clearCoalescedLookup()
        } finally {
            sessionLock.writeLock().unlock()
        }
    }

    fun lookup(query: String, paths: DictionaryPaths, languageCode: String = "", profilingId: Long = 0): LookupResult2 {
        val requestId = profilingId.takeIf { it != 0L } ?: LookupProfiler.start(query)
        val t0 = SystemClock.elapsedRealtime()

        // Warm up BEFORE taking the read lock. `ReentrantReadWriteLock` cannot upgrade a held read
        // lock to a write lock, so calling warmUp from inside readLocked would deadlock whenever a
        // rebuild turned out to be needed - which is exactly the case warmUp exists for.
        LookupProfiler.section(requestId, "repo.warmup") { warmUp(paths, profilingId = requestId) }

        return LookupProfiler.section(requestId, "repo.lookup") { readLocked(requestId) {
            // warmUp above always creates the session (it treats session == null as "needs
            // rebuild"), so by here it exists. Creating it here instead would be a bug: this is a
            // read lock, so two concurrent lookups could both observe null and each call
            // createLookupObject, leaking a native session. Taking the write lock to create it would
            // deadlock against the read lock already held.
            val activeSession = session ?: return@readLocked LookupResult2(
                results = emptyList(),
                styles = emptyList(),
                mediaDataUris = emptyMap(),
                error = null,
                profilingId = requestId,
            )

            val tLookupStart = SystemClock.elapsedRealtime()

        val effectiveLang = languageCode.lowercase()

        val genericDeinflector = if (effectiveLang == "ja") null else LookupProfiler.section(requestId, "deinflector.init") { if (effectiveLang == "ko" && koreanParserMode() == KoreanParserMode.Analyzer) {
            KoreanAnalyzerDeinflector
        } else {
            chimahon.dictionary.DeinflectorRegistry.get(effectiveLang)
        } }

        fun nativeLookup() = LookupProfiler.section(requestId, "jni.lookup") {
            if (requestId != 0L) HoshiDicts.lookupProfiled(activeSession, query, 20, 25, requestId).toList()
            else HoshiDicts.lookup(activeSession, query, 20, 25).toList()
        }

        val results = if (effectiveLang == "ja") {
            nativeLookup()
        } else if (genericDeinflector != null) {
            val finalResults = mutableListOf<chimahon.LookupResult>()
            for (i in query.length downTo 1) {
                val substring = query.substring(0, i)
                val candidates = mutableMapOf<String, chimahon.dictionary.DeinflectionResult>()
                for (preprocessed in genericDeinflector.preProcess(substring).distinct()) {
                    for (deinflected in genericDeinflector.deinflect(preprocessed, effectiveLang)) {
                        if (deinflected.text !in candidates) {
                            candidates[deinflected.text] = deinflected
                        }
                    }
                }
                if (candidates.isNotEmpty()) {
                    val substringResults = candidates.flatMap { (candidateText, deinflected) ->
                        LookupProfiler.section(requestId, "jni.query") { HoshiDicts.query(activeSession, candidateText) }.map { termResult ->
                            chimahon.LookupResult(
                                matched = substring,
                                deinflected = candidateText,
                                process = emptyArray(),
                                term = termResult,
                                preprocessorSteps = 0,
                            )
                        }
                    }
                    finalResults.addAll(substringResults)
                }
            }
            finalResults.distinctBy { it.term.expression to it.term.reading }.take(20)
        } else {
            nativeLookup()
        }

        val lookupMs = SystemClock.elapsedRealtime() - tLookupStart

        // Media loading is now deferred — don't block on I/O here
        val totalMs = SystemClock.elapsedRealtime() - t0
        Log.i(
            "DictionaryRepo",
            "lookup_ms=$totalMs lookup_hoshidicts_ms=$lookupMs results=${results.size}",
        )
        // Last expression, not a `return`: a non-local return would make the lambda's inferred type
        // Nothing and `readLocked` would fail to unify with LookupResult2.
        LookupResult2(
            results = results,
            styles = cachedStyles,
            mediaDataUris = emptyMap(),  // Empty on critical path
            error = null,
            profilingId = requestId,
        )
        } }
    }

    /**
     * Runs [lookup] honoring the profile's effective search resolution.
     * With "word" resolution (yomitan `translation.searchResolution='word'`), when
     * a query yields no results it is retried cutting at word boundaries. Returns
     * the last empty (or first non-empty) result.
     *
     * This is also the duplicate-query handoff used by reader hosts and [OcrLookupPopup]:
     * both call the same canonical query, so the popup can consume the host's immediately
     * preceding identical lookup instead of repeating the native query. Only the latest
     * successful result is retained, and a hit is consumed rather than cached indefinitely.
     */
    fun lookupWithSearchResolution(
        query: String,
        paths: DictionaryPaths,
        activeProfile: AnkiProfile,
        profilingId: Long = 0,
    ): LookupResult2 {
        val key = CoalescedLookupKey(
            query = query,
            languageCode = activeProfile.languageCode,
            profile = activeProfile,
            koreanParserMode = koreanParserMode(),
            paths = paths,
        )
        synchronized(coalescedLookupLock) {
            val pending = coalescedLookup
            if (pending != null && pending.key == key) {
                coalescedLookup = null
                LookupProfiler.mark(pending.result.profilingId, "repo.coalesced")
                LookupProfiler.mark(profilingId, "request.coalesced", "source=${pending.result.profilingId}")
                Log.i(
                    "DictionaryRepo",
                    "lookup_coalesced query='$query' results=${pending.result.results.size}",
                )
                return pending.result
            }
        }

        val requestId = profilingId.takeIf { it != 0L } ?: LookupProfiler.start(query)
        LookupProfiler.mark(requestId, "repo.started", activeProfile.languageCode)
        paths.termPaths.forEachIndexed { index, path -> LookupProfiler.mark(requestId, "dictionary.term.$index", path) }
        paths.freqPaths.forEachIndexed { index, path -> LookupProfiler.mark(requestId, "dictionary.freq.$index", path) }
        paths.pitchPaths.forEachIndexed { index, path -> LookupProfiler.mark(requestId, "dictionary.pitch.$index", path) }
        fun lookupOne(text: String) = lookup(text, paths, activeProfile.languageCode, requestId)
        val result = if (effectiveSearchResolution(activeProfile.searchResolution, activeProfile.languageCode) != AnkiProfile.SEARCH_RESOLUTION_WORD) {
            lookupOne(query)
        } else {
            var current = query
            var retryResult = lookupOne(current)
            var guard = 0
            while (
                current.isNotEmpty() &&
                retryResult.error == null &&
                retryResult.results.isEmpty() &&
                guard++ < 8
            ) {
                current = nextWordBoundarySubstring(current)
                if (current.isEmpty()) break
                retryResult = lookupOne(current)
            }
            retryResult
        }

        if (result.error == null) {
            synchronized(coalescedLookupLock) {
                coalescedLookup = CoalescedLookup(key, result)
            }
        }
        LookupProfiler.mark(requestId, "repo.ready", "results=${result.results.size}")
        return result
    }

    /** Runs [block] holding the session read lock, so concurrent lookups proceed in parallel. */
    private inline fun <T> readLocked(profilingId: Long = 0, block: () -> T): T {
        LookupProfiler.section(profilingId, "lock.read.wait") { sessionLock.readLock().lock() }
        return try {
            block()
        } finally {
            sessionLock.readLock().unlock()
        }
    }

    fun loadMediaAsync(query: String, results: List<LookupResult>): Map<String, String> = readLocked {
        val t0 = SystemClock.elapsedRealtime()
        val activeSession = session ?: return emptyMap()
        val mediaDataUris = buildMediaDataUris(activeSession, results)
        val mediaMs = SystemClock.elapsedRealtime() - t0
        Log.i(
            "DictionaryRepo",
            "loadMediaAsync_ms=$mediaMs query='$query' media_count=${mediaDataUris.size}",
        )
        return mediaDataUris
    }

    fun queryKanji(char: String): KanjiResult? = readLocked {
        val activeSession = session ?: return null
        return try {
            HoshiDicts.queryKanji(activeSession, char)
        } catch (e: Exception) {
            Log.e("DictionaryRepo", "queryKanji failed for '$char'", e)
            null
        }
    }

    fun close() {
        sessionLock.writeLock().lock()
        try {
            session?.let(HoshiDicts::destroyLookupObject)
            session = null
            configuredPaths = DictionaryPaths()
            cachedStyles = emptyList()
            lastWarmedProfileId = ""
            clearCoalescedLookup()
        } finally {
            sessionLock.writeLock().unlock()
        }
    }

    private fun buildMediaDataUris(
        activeSession: Long,
        results: List<LookupResult>,
    ): Map<String, String> {
        val requested = linkedSetOf<Pair<String, String>>()
        run loop@{
            results.forEach { lookup ->
                lookup.term.glossaries.forEach { glossary ->
                    extractImagePaths(glossary.glossary)
                        .forEach { path ->
                            requested += glossary.dictName to path
                            if (requested.size >= MAX_PRELOADED_MEDIA_ITEMS) return@loop
                        }
                }
            }
        }

        val limitedRequested = requested.take(MAX_PRELOADED_MEDIA_ITEMS)
        val out = LinkedHashMap<String, String>(limitedRequested.size)
        limitedRequested.forEach { (dictName, path) ->
            val bytes = HoshiDicts.getMediaFile(activeSession, dictName, path) ?: return@forEach
            if (bytes.size > MAX_PRELOADED_MEDIA_BYTES) return@forEach

            val mime = guessMimeType(path)
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUri = "data:$mime;base64,$b64"
            for (candidate in normalizeMediaPathCandidates(path)) {
                out[mediaKey(dictName, candidate)] = dataUri
            }
        }

        return out
    }

    private fun extractImagePaths(glossary: String): Set<String> {
        val text = glossary.trim()
        if (text.isEmpty() || !(text.startsWith("{") || text.startsWith("["))) return emptySet()
        if (!text.contains("\"img\"") && !text.contains("\"image\"")) return emptySet()

        return runCatching {
            val root: Any = if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
            val found = linkedSetOf<String>()
            walkJson(root) { node ->
                val isImageNode = node.optString("tag") == "img" || node.optString("type") == "image"
                if (isImageNode) {
                    val candidate = node.optString("path").ifBlank { node.optString("src") }
                    candidate.takeIf { it.isNotBlank() }?.let(found::add)
                }
            }
            found
        }.getOrDefault(emptySet())
    }

    private fun walkJson(element: Any?, onObject: (JSONObject) -> Unit) {
        when (element) {
            is JSONObject -> {
                onObject(element)
                val keys = element.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val next = element.opt(key)
                    if (next is JSONObject || next is JSONArray) walkJson(next, onObject)
                }
            }
            is JSONArray -> {
                for (i in 0 until element.length()) {
                    val next = element.opt(i)
                    if (next is JSONObject || next is JSONArray) walkJson(next, onObject)
                }
            }
        }
    }

    private fun guessMimeType(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            else -> "image/png"
        }
    }

    private fun mediaKey(dictName: String, path: String): String = "$dictName\u0000$path"

    private fun normalizeMediaPathCandidates(rawPath: String): Set<String> {
        val candidates = linkedSetOf(
            rawPath,
            rawPath.removePrefix("./"),
            rawPath.removePrefix("/"),
            rawPath.replace('\\', '/'),
        )
        runCatching { URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name()) }
            .getOrNull()
            ?.let {
                candidates += it
                candidates += it.removePrefix("./")
                candidates += it.removePrefix("/")
                candidates += it.replace('\\', '/')
            }
        return candidates
    }

    private fun currentUsedRamMb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
    }

    data class LookupResult2(
        val results: List<LookupResult>,
        val styles: List<DictionaryStyle>,
        val mediaDataUris: Map<String, String>,
        val error: String?,
        val profilingId: Long = 0,
    )

    companion object {
        private const val MAX_PRELOADED_MEDIA_ITEMS = 20
        private const val MAX_PRELOADED_MEDIA_BYTES = 256 * 1024

        private val TYPE_SUBDIRS = listOf("term", "frequency", "pitch", "kanji")

        @Synchronized
        fun migrateFlatDictionaries(dictionariesDir: File) {
            if (!dictionariesDir.isDirectory) return

            val typeSubdirSet = TYPE_SUBDIRS.toSet()
            val flatDirs = dictionariesDir.listFiles()
                ?.filter { it.isDirectory && it.name !in typeSubdirSet }
                .orEmpty()

            if (flatDirs.isEmpty()) return

            for (flatDir in flatDirs) {
                migrateSingleFlatDictionary(flatDir, dictionariesDir)
            }
        }

        private fun migrateSingleFlatDictionary(flatDir: File, dictionariesDir: File) {
            val dictName = flatDir.name
            if (!File(flatDir, "hash.table").isFile) {
                Log.w("DictMigration", "Skipping '$dictName' — no hash.table")
                return
            }
            try {
                val counts = HoshiDicts.probeEntryTypes(flatDir.absolutePath)
                val types = buildList {
                    if (counts[0] > 0) add("term")
                    if (counts[1] > 0) add("frequency")
                    if (counts[2] > 0) add("pitch")
                    if (counts.size > 3 && counts[3] > 0) add("kanji")
                }.ifEmpty { TYPE_SUBDIRS }

                for (type in types) {
                    val typeDir = File(dictionariesDir, type)
                    typeDir.mkdirs()
                    val targetDir = File(typeDir, dictName)
                    if (targetDir.exists()) targetDir.deleteRecursively()
                    flatDir.copyRecursively(targetDir, overwrite = true)
                }

                flatDir.deleteRecursively()
                Log.i("DictMigration", "Migrated '$dictName' into ${types.joinToString(", ")}")
            } catch (e: Exception) {
                Log.e("DictMigration", "Failed to migrate '$dictName': ${e.message}")
            }
        }
    }
}
