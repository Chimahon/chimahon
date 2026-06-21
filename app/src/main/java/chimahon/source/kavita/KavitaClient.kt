package chimahon.source.kavita

import chimahon.novel.model.NovelServer
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class KavitaClient(val server: NovelServer) {

    val json: Json = Json { ignoreUnknownKeys = true }
    val network: NetworkHelper by lazy { Injekt.get<NetworkHelper>() }

    val baseUrl: String get() = server.baseUrl.trimEnd('/')

    private var authToken: String? = null

    val authHeaders: Headers
        get() = Headers.Builder().apply {
            if (!server.apiKey.isNullOrBlank()) {
                add("Authorization", "Bearer ${server.apiKey}")
            } else if (authToken != null) {
                add("Authorization", "Bearer $authToken")
            }
        }.build()

    val client: OkHttpClient
        get() {
            val builder = network.client.newBuilder()
            if (!server.apiKey.isNullOrBlank()) {
                builder.addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${server.apiKey}").build())
                }
            } else if (!server.username.isNullOrBlank()) {
                builder.addInterceptor { chain ->
                    val req = chain.request()
                    if (req.url.encodedPath.endsWith("/login", ignoreCase = true)) {
                        chain.proceed(req)
                    } else {
                        val token = authToken ?: runBlocking { login() }
                        chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
                    }
                }
            }
            return builder.build()
        }

    suspend fun login(): String {
        val body = buildJsonObject {
            put("username", server.username ?: "")
            put("password", server.apiKey ?: "")
        }.toString()
        val response = network.client.newCall(
            POST(
                "$baseUrl/api/Account/login",
                headers = Headers.Builder().add("Content-Type", "application/json").build(),
                body = body.toRequestBody("application/json".toMediaType()),
            ),
        ).awaitSuccess()
        val token = json.decodeFromString<KavitaLoginResponse>(response.body.string()).token
        authToken = token
        return token
    }

    fun generateId(): Long {
        val key = "kavita:${server.baseUrl}:${server.name}"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    suspend fun getLibraries(): List<KavitaLibraryDto> {
        val response = client.newCall(GET("$baseUrl/api/Library")).awaitSuccess()
        return json.decodeFromString(response.body.string())
    }
}

// ===== Date utilities =====

private val threadLocalFormatter = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
}

fun parseDateTime(dateStr: String): Long = runCatching { threadLocalFormatter.get()!!.parse(dateStr)?.time ?: 0L }.getOrDefault(0L)

// ===== DTOs =====

@Serializable
data class KavitaLoginResponse(val token: String)

@Serializable
data class KavitaLibraryDto(
    val id: Int,
    val name: String,
    val type: Int,
)

@Serializable
data class KavitaSeriesDto(
    val id: Int,
    val name: String,
    val originalName: String = "",
    val localizedName: String? = "",
    val sortName: String? = "",
    val pages: Int = 0,
    val coverImageLocked: Boolean = true,
    val pagesRead: Int = 0,
    val userRating: Float = 0f,
    val userReview: String? = "",
    val format: Int = 0,
    val created: String? = "",
    val libraryId: Int = 0,
    val libraryName: String? = "",
) {
    fun toSManga(baseUrl: String): SManga = SManga.create().apply {
        url = "$baseUrl/api/Series/$id"
        title = localizedName?.takeIf { it.isNotBlank() } ?: sortName?.takeIf { it.isNotBlank() } ?: name
        thumbnail_url = "$baseUrl/api/Image/SeriesCover?seriesId=$id"
        initialized = true
    }

    fun toSNNovel(baseUrl: String): SNNovel = SNNovel(
        url = "$baseUrl/api/Series/$id",
        title = localizedName?.takeIf { it.isNotBlank() } ?: sortName?.takeIf { it.isNotBlank() } ?: name,
        description = "",
        status = SNNovel.UNKNOWN,
        thumbnail_url = "$baseUrl/api/Image/SeriesCover?seriesId=$id",
    )
}

@Serializable
data class KavitaSeriesDetailPlusDto(
    val seriesId: Int? = null,
    val libraryName: String? = "",
    val libraryId: Int? = null,
    val summary: String? = null,
    val genres: List<KavitaMetadataGenre> = emptyList(),
    val tags: List<KavitaMetadataTag> = emptyList(),
    val writers: List<KavitaMetadataPeople> = emptyList(),
    val coverArtists: List<KavitaMetadataPeople> = emptyList(),
    val publicationStatus: Int? = null,
) {
    fun toSManga(baseUrl: String): SManga = SManga.create().apply {
        url = seriesId?.let { "$baseUrl/api/Series/$it" } ?: ""
        title = ""
        author = writers.joinToString { it.name }.takeIf { it.isNotBlank() }
        artist = coverArtists.joinToString { it.name }.takeIf { it.isNotBlank() }
        description = summary
        genre = (genres.map { it.title } + tags.map { it.title }).joinToString(", ").takeIf { it.isNotBlank() }
        status = publicationStatus?.let { mapPublicationStatus(it) } ?: SManga.UNKNOWN
        thumbnail_url = seriesId?.let { "$baseUrl/api/Image/SeriesCover?seriesId=$it" }
        initialized = true
    }

    fun toSNNovel(baseUrl: String): SNNovel = SNNovel(
        url = seriesId?.let { "$baseUrl/api/Series/$it" } ?: "",
        title = "",
        author = writers.joinToString { it.name }.takeIf { it.isNotBlank() },
        artist = coverArtists.joinToString { it.name }.takeIf { it.isNotBlank() },
        description = summary ?: "",
        genre = (genres.map { it.title } + tags.map { it.title }).joinToString(", ").takeIf { it.isNotBlank() },
        status = publicationStatus?.let { mapPublicationStatus(it) } ?: SNNovel.UNKNOWN,
        thumbnail_url = seriesId?.let { "$baseUrl/api/Image/SeriesCover?seriesId=$it" },
    )
}

@Serializable
data class KavitaMetadataGenre(val id: Int, val title: String)

@Serializable
data class KavitaMetadataTag(val id: Int, val title: String)

@Serializable
data class KavitaMetadataPeople(val id: Int, val name: String)

@Serializable
data class KavitaVolumeDto(
    val id: Int,
    val minNumber: Double,
    val maxNumber: Double,
    val name: String = "",
    val pages: Int = 0,
    val pagesRead: Int = 0,
    val lastModified: String = "",
    val created: String = "",
    val seriesId: Int = 0,
    val coverImage: String = "",
    val chapters: List<KavitaChapterDto> = emptyList(),
)

@Serializable
data class KavitaChapterDto(
    val id: Int,
    val range: String = "",
    val number: String = "",
    val minNumber: Double = 0.0,
    val maxNumber: Double = 0.0,
    val pages: Int = 0,
    val isSpecial: Boolean = false,
    val title: String = "",
    val titleName: String? = null,
    val pagesRead: Int = 0,
    val coverImageLocked: Boolean = true,
    val coverImage: String = "",
    val volumeId: Int = 0,
    val created: String = "",
    val lastModifiedUtc: String = "",
    val releaseDate: String = "",
)

@Serializable
data class KavitaPageDto(
    val number: Int,
    val fileName: String? = null,
)

// ===== Filter DTOs =====

@Serializable
data class KavitaFilterV2Dto(
    val sortOptions: KavitaSortOptions,
    val statements: MutableList<KavitaFilterStatementDto>,
)

@Serializable
data class KavitaSortOptions(
    var sortField: Int = 1,
    var isAscending: Boolean = true,
)

@Serializable
data class KavitaFilterStatementDto(
    val comparison: Int,
    val field: Int,
    val value: String,
)

fun mapPublicationStatus(status: Int): Int = when (status) {
    1 -> SNNovel.ONGOING
    2 -> SNNovel.COMPLETED
    3 -> SNNovel.CANCELLED
    4 -> SNNovel.ON_HIATUS
    5 -> SNNovel.PUBLISHING_FINISHED
    else -> SNNovel.UNKNOWN
}

// ===== Library type constants =====
object KavitaLibraryType {
    const val MANGA = 0
    const val COMIC = 1
    const val BOOK = 2
    const val MANGA_V2 = 3
    const val LIGHT_NOVEL = 4

    fun isNovelType(type: Int): Boolean = type == BOOK || type == LIGHT_NOVEL
    fun isMangaType(type: Int): Boolean = type == MANGA || type == COMIC || type == MANGA_V2
}
