package chimahon.source.komga

import chimahon.novel.model.NovelServer
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class KomgaClient(val server: NovelServer) {

    val json: Json = Injekt.get()
    val network: NetworkHelper by lazy { Injekt.get<NetworkHelper>() }

    val baseUrl: String get() = server.baseUrl.trimEnd('/')

    val client: OkHttpClient = network.client.newBuilder()
        .authenticator { _, response ->
            if (server.apiKey.isNullOrBlank() && response.request.header("Authorization") == null) {
                response.request.newBuilder()
                    .addHeader("Authorization", Credentials.basic(server.username.orEmpty(), server.password.orEmpty()))
                    .build()
            } else null
        }
        .dns(Dns.SYSTEM)
        .build()

    val headers: Headers
        get() {
            val builder = Headers.Builder()
                .set("User-Agent", "Chimahon/1.0")
            if (!server.apiKey.isNullOrBlank()) {
                builder.set("X-API-Key", server.apiKey!!)
            }
            return builder.build()
        }

    fun generateId(): Long {
        val key = "komga:${server.baseUrl}:${server.name}"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    // ===== Filter options =====

    var libraries: List<LibraryDto> = emptyList()
    var collections: List<CollectionDto> = emptyList()
    var genres: Set<String> = emptySet()
    var tags: Set<String> = emptySet()
    var publishers: Set<String> = emptySet()
    var authors: Map<String, List<AuthorDto>> = emptyMap()
    var fetchFilterStatus = FetchFilterStatus.NOT_FETCHED
    var fetchFiltersAttempts = 0
    private val filterScope = CoroutineScope(Dispatchers.IO)
    private var filterFetchLaunched = false

    fun fetchFilterOptions() {
        if (baseUrl.isBlank() || fetchFilterStatus != FetchFilterStatus.NOT_FETCHED || fetchFiltersAttempts >= 3 || filterFetchLaunched) return
        filterFetchLaunched = true
        fetchFilterStatus = FetchFilterStatus.FETCHING
        fetchFiltersAttempts++
        filterScope.launch {
            try {
                libraries = client.newCall(GET("$baseUrl/api/v1/libraries", headers)).await().parseAs(json)
                collections = client.newCall(GET("$baseUrl/api/v1/collections?unpaged=true", headers)).await()
                    .parseAs<PageWrapperDto<CollectionDto>>(json).content
                genres = client.newCall(GET("$baseUrl/api/v1/genres", headers)).await().parseAs(json)
                tags = client.newCall(GET("$baseUrl/api/v1/tags", headers)).await().parseAs(json)
                publishers = client.newCall(GET("$baseUrl/api/v1/publishers", headers)).await().parseAs(json)
                authors = client.newCall(GET("$baseUrl/api/v1/authors", headers)).await()
                    .parseAs<List<AuthorDto>>(json).groupBy { it.role }
                fetchFilterStatus = FetchFilterStatus.FETCHED
            } catch (_: Exception) {
                fetchFilterStatus = FetchFilterStatus.NOT_FETCHED
                filterFetchLaunched = false
            }
        }
    }

    companion object {
        val SUPPORTED_IMAGE_TYPES = listOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/jxl", "image/heif", "image/avif")
    }
}

enum class FetchFilterStatus { NOT_FETCHED, FETCHING, FETCHED }

// ===== Date utilities =====

private val threadLocalDate = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
}
private val threadLocalDateTime = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
}

fun parseDate(dateStr: String): Long = runCatching { threadLocalDate.get()!!.parse(dateStr)?.time ?: 0L }.getOrDefault(0L)
fun parseDateTime(dateStr: String): Long = runCatching { threadLocalDateTime.get()!!.parse(dateStr)?.time ?: 0L }.getOrDefault(0L)

// ===== Response parsing =====

inline fun <reified T> Response.parseAs(json: Json): T = json.decodeFromString(body.string())

// ===== URL type detection =====

fun String.isFromReadList() = contains("/api/v1/readlists")
fun String.isFromBook() = contains("/api/v1/books")
fun Response.isFromReadList() = request.url.toString().isFromReadList()
fun Response.isFromBook() = request.url.toString().isFromBook()

// ===== DTOs =====

@Serializable
data class PageWrapperDto<T>(
    val content: List<T>,
    val empty: Boolean = false,
    val first: Boolean = false,
    val last: Boolean,
    val number: Long = 0,
    val numberOfElements: Long = 0,
    val size: Long = 0,
    val totalElements: Long = 0,
    val totalPages: Long = 0,
)

@Serializable
data class LibraryDto(val id: String, val name: String)

@Serializable
data class SeriesDto(
    val id: String,
    val libraryId: String,
    val name: String,
    val created: String?,
    val lastModified: String?,
    val fileLastModified: String,
    val booksCount: Int,
    val metadata: SeriesMetadataDto,
    val booksMetadata: BookMetadataAggregationDto,
)

@Serializable
data class SeriesMetadataDto(
    val status: String,
    val created: String?,
    val lastModified: String?,
    val title: String,
    val titleSort: String,
    val summary: String,
    val summaryLock: Boolean,
    val readingDirection: String,
    val readingDirectionLock: Boolean,
    val publisher: String,
    val publisherLock: Boolean,
    val ageRating: Int?,
    val ageRatingLock: Boolean,
    val language: String,
    val languageLock: Boolean,
    val genres: Set<String>,
    val genresLock: Boolean,
    val tags: Set<String>,
    val tagsLock: Boolean,
    val totalBookCount: Int? = null,
)

@Serializable
data class BookMetadataAggregationDto(
    val authors: List<AuthorDto> = emptyList(),
    val tags: Set<String> = emptySet(),
    val releaseDate: String?,
    val summary: String,
    val summaryNumber: String,
    val created: String,
    val lastModified: String,
)

@Serializable
data class BookDto(
    val id: String,
    val seriesId: String,
    val seriesTitle: String,
    val name: String,
    val number: Float,
    val created: String?,
    val lastModified: String?,
    val fileLastModified: String,
    val sizeBytes: Long,
    val size: String,
    val media: MediaDto,
    val metadata: BookMetadataDto,
)

@Serializable
data class MediaDto(
    val status: String,
    val mediaType: String,
    val pagesCount: Int,
    val mediaProfile: String = "DIVINA",
    val epubDivinaCompatible: Boolean = false,
)

@Serializable
data class BookMetadataDto(
    val title: String,
    val titleLock: Boolean,
    val summary: String,
    val summaryLock: Boolean,
    val number: String,
    val numberLock: Boolean,
    val numberSort: Float,
    val numberSortLock: Boolean,
    val releaseDate: String?,
    val releaseDateLock: Boolean,
    val authors: List<AuthorDto>,
    val authorsLock: Boolean,
    val tags: Set<String>,
    val tagsLock: Boolean,
)

@Serializable
data class AuthorDto(val name: String, val role: String)

@Serializable
data class CollectionDto(
    val id: String,
    val name: String,
    val ordered: Boolean,
    val seriesIds: List<String>,
    val createdDate: String,
    val lastModifiedDate: String,
    val filtered: Boolean,
)

@Serializable
data class ReadListDto(
    val id: String,
    val name: String,
    val summary: String,
    val bookIds: List<String>,
    val createdDate: String,
    val lastModifiedDate: String,
    val filtered: Boolean,
)

@Serializable
data class PageDto(
    val number: Int,
    val fileName: String,
    val mediaType: String,
)

// ===== DTO extension functions =====

fun SeriesDto.toSManga(baseUrl: String): SManga = SManga.create().apply {
    url = "$baseUrl/api/v1/series/$id"
    title = metadata.title.takeIf { it.isNotBlank() } ?: name
    author = booksMetadata.authors.filter { it.role == "writer" || it.role == "author" }.joinToString { it.name }
    artist = booksMetadata.authors.filter { it.role == "artist" || it.role == "penciller" }.joinToString { it.name }
    description = metadata.summary.takeIf { it.isNotBlank() } ?: booksMetadata.summary
    genre = metadata.genres.joinToString(", ")
    status = parseStatus(metadata.status)
    thumbnail_url = "$baseUrl/api/v1/series/$id/thumbnail"
    initialized = true
}

fun SeriesDto.toSNNovel(baseUrl: String): SNNovel = SNNovel(
    url = "$baseUrl/api/v1/series/$id",
    title = metadata.title.takeIf { it.isNotBlank() } ?: name,
    author = booksMetadata.authors.filter { it.role == "writer" || it.role == "author" }.joinToString { it.name }.takeIf { it.isNotBlank() },
    artist = booksMetadata.authors.filter { it.role == "artist" || it.role == "penciller" }.joinToString { it.name }.takeIf { it.isNotBlank() },
    description = metadata.summary.takeIf { it.isNotBlank() } ?: booksMetadata.summary,
    genre = metadata.genres.joinToString(", ").takeIf { it.isNotBlank() },
    status = parseStatus(metadata.status),
    thumbnail_url = "$baseUrl/api/v1/series/$id/thumbnail",
)

fun ReadListDto.toSManga(baseUrl: String): SManga = SManga.create().apply {
    url = "$baseUrl/api/v1/readlists/$id"
    title = name
    description = summary
    thumbnail_url = "$baseUrl/api/v1/readlists/$id/thumbnail"
    initialized = true
}

fun ReadListDto.toSNNovel(baseUrl: String): SNNovel = SNNovel(
    url = "$baseUrl/api/v1/readlists/$id",
    title = name,
    description = summary,
    thumbnail_url = "$baseUrl/api/v1/readlists/$id/thumbnail",
)

fun BookDto.toSManga(baseUrl: String): SManga = SManga.create().apply {
    url = "$baseUrl/api/v1/books/$id"
    title = metadata.title.takeIf { it.isNotBlank() } ?: name
    author = metadata.authors.filter { it.role == "writer" || it.role == "author" }.joinToString { it.name }
    artist = metadata.authors.filter { it.role == "artist" || it.role == "penciller" }.joinToString { it.name }
    description = metadata.summary
    thumbnail_url = "$baseUrl/api/v1/books/$id/thumbnail"
    initialized = true
}

fun BookDto.toSNNovel(baseUrl: String): SNNovel = SNNovel(
    url = "$baseUrl/api/v1/books/$id",
    title = metadata.title.takeIf { it.isNotBlank() } ?: name,
    author = metadata.authors.filter { it.role == "writer" || it.role == "author" }.joinToString { it.name }.takeIf { it.isNotBlank() },
    artist = metadata.authors.filter { it.role == "artist" || it.role == "penciller" }.joinToString { it.name }.takeIf { it.isNotBlank() },
    description = metadata.summary.takeIf { it.isNotBlank() },
    thumbnail_url = "$baseUrl/api/v1/books/$id/thumbnail",
)

fun BookDto.toSChapter(baseUrl: String, isFromReadList: Boolean = false, chapterIndex: Float? = null): SChapter =
    SChapter.create().apply {
        url = "$baseUrl/api/v1/books/$id"
        name = metadata.title.takeIf { it.isNotBlank() } ?: seriesTitle.takeIf { it.isNotBlank() } ?: name
        chapter_number = if (isFromReadList) chapterIndex ?: number else number
        date_upload = created?.let { parseDateTime(it) } ?: 0L
    }

fun BookDto.toSNChapter(isFromReadList: Boolean = false, chapterIndex: Float? = null, baseUrl: String, mediaProfile: String = ""): SNChapter =
    SNChapter(
        url = "$baseUrl/api/v1/books/$id?mediaProfile=$mediaProfile",
        name = metadata.title.takeIf { it.isNotBlank() } ?: seriesTitle.takeIf { it.isNotBlank() } ?: name,
        chapter_number = if (isFromReadList) chapterIndex ?: number else number,
        date_upload = created?.let { parseDateTime(it) } ?: 0L,
    )

private fun parseStatus(status: String): Int = when (status.lowercase()) {
    "ongoing" -> SManga.ONGOING
    "ended" -> SManga.COMPLETED
    "abandoned" -> SManga.CANCELLED
    "hiatus" -> SManga.ON_HIATUS
    else -> SManga.UNKNOWN
}
