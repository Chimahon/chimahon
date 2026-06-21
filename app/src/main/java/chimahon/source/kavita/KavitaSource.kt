package chimahon.source.kavita

import chimahon.novel.model.NovelServer
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.PreferenceScreen
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.security.MessageDigest

class KavitaSource(
    private val server: NovelServer,
) : HttpSource(), ConfigurableSource {

    override val name: String get() = server.name
    override val lang: String = "all"
    override val baseUrl: String get() = server.baseUrl.trimEnd('/')
    override val supportsLatest: Boolean = true

    override val id: Long by lazy { generateKavitaId() }

    private val json = Json { ignoreUnknownKeys = true }
    private var authToken: String? = null

    override val client: OkHttpClient = network.client.newBuilder()
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

    private suspend fun getMangaLibraries(): List<KavitaLibraryDto> {
        val response = client.newCall(GET("$baseUrl/api/Library")).awaitSuccess()
        return json.decodeFromString(response.body.string())
    }

    override fun popularMangaRequest(page: Int): Request {
        return searchMangaRequest(page, "", FilterList())
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return searchMangaParse(response)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return searchMangaRequest(page, "", FilterList())
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return searchMangaParse(response)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val libraries = kotlinx.coroutines.runBlocking { getMangaLibraries() }
        val filter = KavitaFilterV2Dto(
            sortOptions = KavitaSortOptions(
                sortField = if (query.isNotBlank()) 1 else 8,
                isAscending = query.isBlank(),
            ),
            statements = mutableListOf<KavitaFilterStatementDto>().apply {
                if (query.isNotBlank()) {
                    add(KavitaFilterStatementDto(comparison = 7, field = 1, value = query))
                }
                libraries.forEach { lib ->
                    add(KavitaFilterStatementDto(comparison = 5, field = 19, value = lib.id.toString()))
                }
            },
        )
        val payload = json.encodeToJsonElement(filter).toString()
        return POST(
            "$baseUrl/api/Series/all-v2?pageNumber=$page&pageSize=20",
            body = payload.toRequestBody("application/json".toMediaType()),
        )
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val seriesList = json.decodeFromString<List<KavitaSeriesDto>>(response.body.string())
        return MangasPage(seriesList.map { it.toSManga(baseUrl) }, seriesList.size >= 20)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val seriesId = manga.url.substringAfterLast("/")
        return GET("$baseUrl/api/series/metadata?seriesId=$seriesId")
    }

    override suspend fun getMangaDetails(manga: SManga): SManga {
        return super.getMangaDetails(manga).apply {
            if (title.isBlank() && manga.title.isNotBlank()) {
                title = manga.title
            }
        }
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val detail = json.decodeFromString<KavitaSeriesDetailPlusDto>(response.body.string())
        return detail.toSManga(baseUrl)
    }

    override fun chapterListRequest(manga: SManga): Request {
        val seriesId = manga.url.substringAfterLast("/")
        return GET("$baseUrl/api/Series/$seriesId/Volumes")
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val volumes = json.decodeFromString<List<KavitaVolumeDto>>(response.body.string())
        return volumes.flatMap { volume ->
            volume.chapters.map { chapter ->
                SChapter.create().apply {
                    url = "$baseUrl/api/Chapter/${chapter.id}"
                    name = chapter.titleName?.takeIf { it.isNotBlank() }
                        ?: chapter.range.takeIf { it.isNotBlank() }
                        ?: "Chapter ${chapter.minNumber.toInt()}"
                    chapter_number = chapter.minNumber.toFloat()
                    date_upload = chapter.created.takeIf { it.isNotBlank() }?.let { parseDateTime(it) } ?: 0L
                }
            }
        }.sortedByDescending { it.chapter_number }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringAfterLast("/")
        val response = client.newCall(GET("$baseUrl/api/Chapter/$chapterId/Pages")).awaitSuccess()
        val pages = json.decodeFromString<List<KavitaPageDto>>(response.body.string())
        return pages.map { page ->
            Page(
                index = page.number - 1,
                imageUrl = "$baseUrl/api/Reader/Extract?chapterId=$chapterId&page=${page.number}",
            )
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        throw UnsupportedOperationException("Not used")
    }

    override fun imageUrlParse(response: Response): String {
        return response.request.url.toString()
    }

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!)
    }

    override fun getFilterList() = FilterList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
    }
}
