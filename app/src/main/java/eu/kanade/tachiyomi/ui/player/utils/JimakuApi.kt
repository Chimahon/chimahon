package eu.kanade.tachiyomi.ui.player.utils

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class JimakuApi(
    private val client: OkHttpClient = Injekt.get<NetworkHelper>().client,
    private val json: Json = Injekt.get(),
) {
    suspend fun searchEntries(apiKey: String, query: String): List<JimakuEntry> {
        return (searchEntries(apiKey, query, anime = true) + searchEntries(apiKey, query, anime = false))
            .distinctBy { it.id }
    }

    private suspend fun searchEntries(apiKey: String, query: String, anime: Boolean): List<JimakuEntry> {
        val url = "$BASE_URL/entries/search".toHttpUrl().newBuilder()
            .addQueryParameter("anime", anime.toString())
            .addQueryParameter("query", query)
            .build()
        return with(json) {
            client.newCall(GET(url, authorizationHeaders(apiKey)))
                .awaitSuccess()
                .parseAs<List<JimakuEntry>>()
        }
    }

    suspend fun getFiles(apiKey: String, entryId: Long, episode: Int?): List<JimakuFile> {
        val urlBuilder = "$BASE_URL/entries/$entryId/files".toHttpUrl().newBuilder()
        episode?.let { urlBuilder.addQueryParameter("episode", it.toString()) }
        return with(json) {
            client.newCall(GET(urlBuilder.build(), authorizationHeaders(apiKey)))
                .awaitSuccess()
                .parseAs<List<JimakuFile>>()
        }
    }

    suspend fun downloadFile(apiKey: String, file: JimakuFile, outputDir: File): File {
        outputDir.mkdirs()
        val outputFile = File(outputDir, file.name.toSafeFileName())
        val temporary = File.createTempFile(".jimaku-", ".tmp", outputDir)
        try {
            client.newCall(GET(file.url, authorizationHeaders(apiKey)))
                .awaitSuccess()
                .use { response ->
                    temporary.outputStream().use { output ->
                        response.body.byteStream().use { input -> input.copyTo(output) }
                    }
                }
            check(temporary.length() > 0) { "Empty Jimaku subtitle" }
            check(temporary.renameTo(outputFile)) { "Could not save Jimaku subtitle" }
            return outputFile
        } finally {
            temporary.delete()
        }
    }

    private fun authorizationHeaders(apiKey: String): Headers {
        return Headers.Builder()
            .add("Authorization", apiKey.trim())
            .build()
    }

    private fun String.toSafeFileName(): String {
        return replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { "jimaku-subtitle.srt" }
    }

    companion object {
        private const val BASE_URL = "https://jimaku.cc/api"
    }
}

@Serializable
data class JimakuEntry(
    val id: Long,
    val name: String,
    @SerialName("english_name")
    val englishName: String? = null,
    @SerialName("japanese_name")
    val japaneseName: String? = null,
)

@Serializable
data class JimakuFile(
    val url: String,
    val name: String,
    val size: Long,
    @SerialName("last_modified")
    val lastModified: String,
)
