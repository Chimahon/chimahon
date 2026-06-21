package chimahon.source.kavita

import chimahon.novel.model.NovelServer
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.NovelPage
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest

class KavitaNovelSource(
    private val server: NovelServer,
) : NovelsPageSource {

    override val id: Long by lazy { generateKavitaId() }
    override val name: String get() = server.name
    override val lang: String = "all"
    override val supportsLatest: Boolean = true

    private val json = Json { ignoreUnknownKeys = true }
    private val network: NetworkHelper = Injekt.get()
    private val baseUrl: String get() = server.baseUrl.trimEnd('/')
    private var authToken: String? = null

    private val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val req = chain.request()
            val builder = req.newBuilder()
            if (!server.apiKey.isNullOrBlank()) {
                builder.header("Authorization", "Bearer ${server.apiKey}")
            } else if (authToken != null) {
                builder.header("Authorization", "Bearer $authToken")
            } else if (!server.username.isNullOrBlank()) {
                kotlinx.coroutines.runBlocking {
                    builder.header("Authorization", "Bearer ${login()}")
                }
            }
            chain.proceed(builder.build())
        }
        .build()

    private suspend fun login(): String {
        val body = buildJsonObject {
            put("username", server.username ?: "")
            put("password", server.password ?: "")
        }.toString()
        val response = network.client.newCall(
            POST(
                "$baseUrl/api/Account/login",
                body = body.toRequestBody("application/json".toMediaType()),
            ),
        ).awaitSuccess()
        val token = json.decodeFromString<KavitaLoginResponse>(response.body.string()).token
        authToken = token
        return token
    }

    private fun generateKavitaId(): Long {
        val key = "kavita:${server.baseUrl}:${server.name}"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    private suspend fun getNovelLibraries(): List<KavitaLibraryDto> {
        val response = client.newCall(GET("$baseUrl/api/Library")).awaitSuccess()
        return json.decodeFromString<List<KavitaLibraryDto>>(response.body.string())
            .filter { KavitaLibraryType.isNovelType(it.type) }
    }

    override suspend fun getNovelDetails(novel: SNNovel): SNNovel {
        val seriesId = novel.url.substringAfterLast("/")
        val response = client.newCall(GET("$baseUrl/api/series/metadata?seriesId=$seriesId")).awaitSuccess()
        val detail = json.decodeFromString<KavitaSeriesDetailPlusDto>(response.body.string())
        return detail.toSNNovel(baseUrl).let { details ->
            if (details.title.isBlank() && novel.title.isNotBlank()) {
                SNNovel(
                    url = details.url,
                    title = novel.title,
                    author = details.author,
                    artist = details.artist,
                    description = details.description,
                    genre = details.genre,
                    status = details.status,
                    thumbnail_url = details.thumbnail_url,
                )
            } else {
                details
            }
        }
    }

    override suspend fun getChapterList(novel: SNNovel): List<SNChapter> {
        val seriesId = novel.url.substringAfterLast("/")
        val response = client.newCall(GET("$baseUrl/api/Series/$seriesId/Volumes")).awaitSuccess()
        val volumes = json.decodeFromString<List<KavitaVolumeDto>>(response.body.string())
        return volumes.flatMap { volume ->
            volume.chapters.map { chapter ->
                SNChapter(
                    name = chapter.titleName?.takeIf { it.isNotBlank() }
                        ?: chapter.range.takeIf { it.isNotBlank() }
                        ?: "Chapter ${chapter.minNumber.toInt()}",
                    url = "$baseUrl/api/Chapter/${chapter.id}",
                    chapter_number = chapter.minNumber.toFloat(),
                    date_upload = chapter.created.takeIf { it.isNotBlank() }?.let { parseDateTime(it) } ?: 0L,
                )
            }
        }.sortedByDescending { it.chapter_number }
    }

    override suspend fun getChapterContent(chapter: SNChapter): ChapterContent {
        val chapterId = chapter.url.substringAfterLast("/")
        val textResponse = client.newCall(GET("$baseUrl/api/Chapter/$chapterId/ExtractText")).awaitSuccess()
        val text = textResponse.body.string()
        if (text.isNotBlank()) {
            return ChapterContent.text(text)
        }
        val pagesResponse = client.newCall(GET("$baseUrl/api/Chapter/$chapterId/Pages")).awaitSuccess()
        val pages = json.decodeFromString<List<KavitaPageDto>>(pagesResponse.body.string())
        if (pages.isNotEmpty()) {
            val imageUrls = pages.map { "$baseUrl/api/Reader/Extract?chapterId=$chapterId&page=${it.number}" }
            return ChapterContent.images(imageUrls)
        }
        return ChapterContent.text("No content available")
    }

    override suspend fun getPopularNovels(page: Int): NovelPage {
        val libraries = getNovelLibraries()
        if (libraries.isEmpty()) return NovelPage(emptyList(), false)
        val filter = KavitaFilterV2Dto(
            sortOptions = KavitaSortOptions(sortField = 8, isAscending = false),
            statements = libraries.map { lib ->
                KavitaFilterStatementDto(comparison = 5, field = 19, value = lib.id.toString())
            }.toMutableList(),
        )
        return executeSeriesSearch(filter, page)
    }

    override suspend fun getSearchNovels(page: Int, query: String, filters: FilterList): NovelPage {
        val libraries = getNovelLibraries()
        val filter = KavitaFilterV2Dto(
            sortOptions = KavitaSortOptions(sortField = 1, isAscending = true),
            statements = mutableListOf<KavitaFilterStatementDto>().apply {
                if (query.isNotBlank()) {
                    add(KavitaFilterStatementDto(comparison = 7, field = 1, value = query))
                }
                libraries.forEach { lib ->
                    add(KavitaFilterStatementDto(comparison = 5, field = 19, value = lib.id.toString()))
                }
            },
        )
        return executeSeriesSearch(filter, page)
    }

    override suspend fun getLatestUpdates(page: Int): NovelPage {
        val libraries = getNovelLibraries()
        if (libraries.isEmpty()) return NovelPage(emptyList(), false)
        val filter = KavitaFilterV2Dto(
            sortOptions = KavitaSortOptions(sortField = 4, isAscending = false),
            statements = libraries.map { lib ->
                KavitaFilterStatementDto(comparison = 5, field = 19, value = lib.id.toString())
            }.toMutableList(),
        )
        return executeSeriesSearch(filter, page)
    }

    override fun getFilterList() = FilterList()

    private suspend fun executeSeriesSearch(filter: KavitaFilterV2Dto, page: Int): NovelPage {
        val payload = json.encodeToJsonElement(filter).toString()
        val response = client.newCall(
            POST(
                "$baseUrl/api/Series/all-v2?pageNumber=$page&pageSize=20",
                body = payload.toRequestBody("application/json".toMediaType()),
            ),
        ).awaitSuccess()
        val seriesList = json.decodeFromString<List<KavitaSeriesDto>>(response.body.string())
        return NovelPage(seriesList.map { it.toSNNovel(baseUrl) }, seriesList.size >= 20)
    }
}
