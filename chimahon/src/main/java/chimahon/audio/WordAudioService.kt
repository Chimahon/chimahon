package chimahon.audio

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class WordAudioService(
    private val context: Context,
    private val preferences: WordAudioPreferences = Injekt.get(),
    private val network: NetworkHelper = Injekt.get(),
) {
    private val localDatabase = WordAudioDatabase(context)
    private val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // Browser-like UA so endpoints such as Google's translate_tts accept the request
    // (the app's default User-Agent is rejected with a 403).
    private val browserUserAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private var cachedSources: List<WordAudioSource>? = null
    private var cachedSourcesRaw: String = ""

    companion object {
        private const val TAG = "WordAudioService"
    }

    /**
     * Scans all enabled sources in priority order to find audio for a term.
     */
    suspend fun findWordAudio(term: String, reading: String): List<WordAudioResult> = withContext(Dispatchers.IO) {
        if (!preferences.wordAudioEnabled().get()) return@withContext emptyList()

        val results = mutableListOf<WordAudioResult>()
        val sources = getEnabledSources()

        for (source in sources) {
            try {
                when (source.type) {
                    WordAudioSource.SourceType.LOCAL -> {
                        val localEntries = findLocalAudio(term, reading)
                        results.addAll(
                            localEntries.map { entry ->
                                WordAudioResult(
                                    name = "${entry.sourceId} (${entry.speaker ?: entry.display ?: "Local"})",
                                    url = "chimahon-local://${entry.sourceId}/${entry.file}",
                                    source = source,
                                    data = null, // Fetched on demand via getAudioData
                                )
                            },
                        )
                    }
                    WordAudioSource.SourceType.ONLINE -> {
                        val onlineResults = fetchOnlineAudio(term, reading, source)
                        results.addAll(onlineResults)
                    }
                }

                if (results.isNotEmpty()) break
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching audio from source: ${source.name}", e)
            }
        }

        results
    }

    suspend fun getAudioData(filePath: String, sourceId: String): ByteArray? = withContext(Dispatchers.IO) {
        localDatabase.getAudioData(filePath, sourceId)
    }

    private val tempAudioFile by lazy { File(context.cacheDir, "word_audio_track") }

    suspend fun getAudioDataFd(filePath: String, sourceId: String): ParcelFileDescriptor? = withContext(Dispatchers.IO) {
        val blob = localDatabase.getAudioData(filePath, sourceId) ?: return@withContext null
        tempAudioFile.writeBytes(blob)
        ParcelFileDescriptor.open(tempAudioFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun getEnabledSources(): List<WordAudioSource> {
        val raw = preferences.wordAudioSources().get()
        if (raw != cachedSourcesRaw || cachedSources == null) {
            cachedSources = try {
                json.decodeFromString<List<WordAudioSource>>(raw)
            } catch (e: Exception) {
                emptyList()
            }
            cachedSourcesRaw = raw
        }
        val sources = cachedSources!!

        val localEnabled = preferences.wordAudioLocalEnabled().get()
        val finalSources = sources.toMutableList()

        if (localEnabled && finalSources.none { it.type == WordAudioSource.SourceType.LOCAL }) {
            finalSources.add(0, WordAudioSource.createLocal())
        }

        return finalSources.filter { it.isEnabled }
    }

    private fun findLocalAudio(term: String, reading: String): List<WordAudioDatabase.LocalEntry> {
        // New path: SAF Uri
        val uriStr = preferences.wordAudioLocalUri().get()
        if (uriStr.isNotBlank()) {
            // SAF providers can invalidate or replace a descriptor while the
            // process is alive. Re-probe the schema before using a cached
            // native handle so we can reopen and use the stable-copy fallback.
            if (!localDatabase.isOpenFor(uriStr) || !localDatabase.testConnection()) {
                localDatabase.close()
                if (!localDatabase.updateUri(android.net.Uri.parse(uriStr)) || !localDatabase.testConnection()) {
                    return emptyList()
                }
                if (localDatabase.fallbackUsed) {
                    // Keep using the verified private copy if the provider
                    // supplied a virtual or unstable descriptor.
                    preferences.wordAudioLocalPath().set(
                        File(context.getExternalFilesDir(null), "word_audio.db").absolutePath,
                    )
                    preferences.wordAudioLocalUri().set("")
                }
            }
            return localDatabase.findEntries(term, reading)
        }

        // Legacy path: file path
        val path = preferences.wordAudioLocalPath().get()
        if (path.isBlank()) return emptyList()
        localDatabase.updatePath(path)
        return localDatabase.findEntries(term, reading)
    }

    private suspend fun fetchOnlineAudio(term: String, reading: String, source: WordAudioSource): List<WordAudioResult> {
        val url = source.url
            .replace("{term}", term)
            .replace("{reading}", reading)

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", browserUserAgent)
            .build()

        return try {
            network.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()

                val body = response.body?.string() ?: return emptyList()

                if (body.trim().startsWith("{")) {
                    parseOnlineJson(body, source)
                } else {
                    listOf(WordAudioResult(name = source.name, url = url, source = source))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error fetching online audio", e)
            emptyList()
        }
    }

    private fun parseOnlineJson(jsonStr: String, source: WordAudioSource): List<WordAudioResult> {
        return try {
            val jsonObj = json.parseToJsonElement(jsonStr) as? JsonObject ?: return emptyList()

            if ((jsonObj["type"] as? JsonPrimitive)?.content != "audioSourceList") {
                val items = jsonObj["items"] as? JsonArray ?: return emptyList()
                return items.mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    val url = sequenceOf("pathmp3", "pathogg")
                        .mapNotNull { (item[it] as? JsonPrimitive)?.contentOrNull }
                        .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
                        ?: return@mapNotNull null
                    val username = (item["username"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    WordAudioResult(
                        name = username?.let { "${source.name} ($it)" } ?: source.name,
                        url = url,
                        source = source,
                    )
                }
            }

            val sourcesArray = jsonObj["audioSources"] as? JsonArray ?: return emptyList()

            sourcesArray.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val name = (obj["name"] as? JsonPrimitive)?.content ?: source.name
                val url = (obj["url"] as? JsonPrimitive)?.content ?: return@mapNotNull null

                WordAudioResult(name = name, url = url, source = source)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing online audio JSON", e)
            emptyList()
        }
    }

    suspend fun fetchRemoteAudioData(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", browserUserAgent)
            .build()
        try {
            network.client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching remote audio data", e)
            null
        }
    }
}
