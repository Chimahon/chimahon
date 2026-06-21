package chimahon.source.komga

import chimahon.novel.model.NovelServer
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.PreferenceScreen
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.security.MessageDigest

class KomgaSource(
    private val server: NovelServer,
) : HttpSource(), ConfigurableSource {

    override val name: String get() = server.name
    override val lang: String = "all"
    override val baseUrl: String get() = server.baseUrl.trimEnd('/')
    override val supportsLatest: Boolean = true

    override val id: Long by lazy { generateKomgaId() }

    override val headers: Headers by lazy {
        Headers.Builder().apply {
            set("User-Agent", network.defaultUserAgentProvider())
            if (!server.apiKey.isNullOrBlank()) {
                set("X-API-Key", server.apiKey!!)
            }
        }.build()
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .authenticator { _, response ->
            if (server.apiKey.isNullOrBlank() && response.request.header("Authorization") == null) {
                response.request.newBuilder()
                    .addHeader("Authorization", Credentials.basic(server.username.orEmpty(), server.password.orEmpty()))
                    .build()
            } else null
        }
        .build()

    private val komgaClient = KomgaClient(server)

    private fun generateKomgaId(): Long {
        val key = "komga:${server.baseUrl}:${server.name}"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    override fun popularMangaRequest(page: Int): Request {
        return searchMangaRequest(page, "", FilterList(SeriesSort(Filter.Sort.Selection(1, true))))
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return searchMangaParse(response)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return searchMangaRequest(page, "", FilterList(SeriesSort(Filter.Sort.Selection(3, false))))
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return searchMangaParse(response)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = buildSearchUrl(page, query, filters)
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return parseMangaResponse(response)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(manga.url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        return if (response.isFromReadList()) {
            response.parseAs<ReadListDto>(komgaClient.json).toSManga(baseUrl)
        } else if (response.isFromBook()) {
            response.parseAs<BookDto>(komgaClient.json).toSManga(baseUrl)
        } else {
            response.parseAs<SeriesDto>(komgaClient.json).toSManga(baseUrl)
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        val url = when {
            manga.url.isFromBook() -> "${manga.url}?unpaged=true&media_status=READY&deleted=false"
            else -> "${manga.url}/books?unpaged=true&media_status=READY&deleted=false"
        }
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        return if (response.isFromBook()) {
            val book = response.parseAs<BookDto>(komgaClient.json)
            listOf(book.toSChapter(baseUrl))
        } else {
            val page = response.parseAs<PageWrapperDto<BookDto>>(komgaClient.json).content
            val isFromReadList = response.isFromReadList()
            page
                .filter { it.media.mediaProfile != "EPUB" || it.media.epubDivinaCompatible }
                .mapIndexed { index, book ->
                    book.toSChapter(baseUrl, isFromReadList, index.toFloat())
                }
                .sortedByDescending { it.chapter_number }
        }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.newCall(GET("${chapter.url}/pages", headers)).awaitSuccess()
        val pages = response.parseAs<List<PageDto>>(komgaClient.json)
        return pages.map { page ->
            val url = "${chapter.url}/pages/${page.number}" +
                if (!KomgaClient.SUPPORTED_IMAGE_TYPES.contains(page.mediaType)) {
                    "?convert=png"
                } else ""
            Page(page.number, imageUrl = url)
        }
    }

    override fun imageUrlParse(response: Response): String {
        return response.request.url.toString()
    }

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!)
    }

    override fun getFilterList(): FilterList {
        komgaClient.fetchFilterOptions()
        val filters = mutableListOf<Filter<*>>(
            UnreadFilter(),
            InProgressFilter(),
            ReadFilter(),
            TypeSelect(),
            CollectionSelect(
                buildList {
                    add(CollectionFilterEntry("None"))
                    komgaClient.collections.forEach {
                        add(CollectionFilterEntry(it.name, it.id))
                    }
                },
            ),
            LibraryFilter(komgaClient.libraries, emptySet()),
            UriMultiSelectFilter(
                "Status",
                "status",
                listOf("Ongoing", "Ended", "Abandoned", "Hiatus").map {
                    UriMultiSelectOption(it, it.uppercase())
                },
            ),
            UriMultiSelectFilter(
                "Genres",
                "genre",
                komgaClient.genres.map { UriMultiSelectOption(it) },
            ),
            UriMultiSelectFilter(
                "Tags",
                "tag",
                komgaClient.tags.map { UriMultiSelectOption(it) },
            ),
            UriMultiSelectFilter(
                "Publishers",
                "publisher",
                komgaClient.publishers.map { UriMultiSelectOption(it) },
            ),
        ).apply {
            if (komgaClient.fetchFilterStatus != FetchFilterStatus.FETCHED) {
                val message = if (komgaClient.fetchFilterStatus == FetchFilterStatus.NOT_FETCHED && komgaClient.fetchFiltersAttempts >= 3) {
                    "Failed to fetch filtering options from the server"
                } else {
                    "Press 'Reset' to show filtering options"
                }
                add(0, Filter.Header(message))
                add(1, Filter.Separator())
            }
            addAll(komgaClient.authors.map { (role, authors) -> AuthorGroup(role, authors.map { AuthorFilter(it) }) })
            add(SeriesSort())
        }
        return FilterList(filters)
    }

    override fun pageListParse(response: Response): List<Page> {
        throw UnsupportedOperationException("Not used")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
    }

    private fun buildSearchUrl(page: Int, query: String, filters: FilterList): String {
        val collectionId = (filters.find { it is CollectionSelect } as? CollectionSelect)?.let {
            it.collections[it.state].id
        }
        val type = when {
            collectionId != null -> "collections/$collectionId/series"
            filters.find { it is TypeSelect }?.state == 1 -> "readlists"
            filters.find { it is TypeSelect }?.state == 2 -> "books"
            else -> "series"
        }

        val url = "${baseUrl}/api/v1".toHttpUrl().newBuilder()
            .addPathSegments(type)
            .addQueryParameter("search", query)
            .addQueryParameter("page", (page - 1).toString())
            .addQueryParameter("deleted", "false")

        val filterList = filters.ifEmpty { getFilterList() }
        filterList.forEach { filter ->
            when (filter) {
                is UriFilter -> filter.addToUri(url)
                is Filter.Sort -> {
                    val state = filter.state ?: return@forEach
                    val sortCriteria = when (state.index) {
                        0 -> "relevance"
                        1 -> if (type == "series") "metadata.titleSort" else "name"
                        2 -> "createdDate"
                        3 -> "lastModifiedDate"
                        4 -> "random"
                        else -> return@forEach
                    } + "," + if (state.ascending) "asc" else "desc"
                    url.addQueryParameter("sort", sortCriteria)
                }
                else -> {}
            }
        }
        return url.build().toString()
    }

    private fun parseMangaResponse(response: Response): MangasPage {
        return if (response.isFromReadList()) {
            val data = response.parseAs<PageWrapperDto<ReadListDto>>(komgaClient.json)
            MangasPage(data.content.map { it.toSManga(baseUrl) }, !data.last)
        } else if (response.isFromBook()) {
            val data = response.parseAs<PageWrapperDto<BookDto>>(komgaClient.json)
            MangasPage(data.content.map { it.toSManga(baseUrl) }, !data.last)
        } else {
            val data = response.parseAs<PageWrapperDto<SeriesDto>>(komgaClient.json)
            MangasPage(data.content.map { it.toSManga(baseUrl) }, !data.last)
        }
    }

}
