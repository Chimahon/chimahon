package chimahon.source.komga

import chimahon.novel.model.NovelServer
import com.canopus.chimareader.data.epub.EpubParser
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.NovelPage
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest

class KomgaNovelSource(
    private val server: NovelServer,
) : NovelsPageSource {

    override val id: Long by lazy { generateKomgaId() }
    override val name: String get() = server.name
    override val lang: String = "all"
    override val supportsLatest: Boolean = true

    private val network: NetworkHelper = Injekt.get()
    private val baseUrl: String get() = server.baseUrl.trimEnd('/')
    private val komgaClient = KomgaClient(server)

    private val headers: Headers by lazy {
        Headers.Builder().apply {
            set("User-Agent", network.defaultUserAgentProvider())
            if (!server.apiKey.isNullOrBlank()) {
                set("X-API-Key", server.apiKey!!)
            }
        }.build()
    }

    private val client: OkHttpClient = network.client.newBuilder()
        .authenticator { _, response ->
            if (server.apiKey.isNullOrBlank() && response.request.header("Authorization") == null) {
                response.request.newBuilder()
                    .addHeader("Authorization", Credentials.basic(server.username.orEmpty(), server.password.orEmpty()))
                    .build()
            } else null
        }
        .build()

    private fun generateKomgaId(): Long {
        val key = "komga:${server.baseUrl}:${server.name}"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    override suspend fun getPopularNovels(page: Int): NovelPage {
        return getSearchNovels(page, "", FilterList(SeriesSort(Filter.Sort.Selection(1, true))))
    }

    override suspend fun getLatestUpdates(page: Int): NovelPage {
        return getSearchNovels(page, "", FilterList(SeriesSort(Filter.Sort.Selection(3, false))))
    }

    override suspend fun getSearchNovels(page: Int, query: String, filters: FilterList): NovelPage {
        val url = buildSearchUrl(page, query, filters)
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        return parseNovelPage(response)
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

    override suspend fun getNovelDetails(novel: SNNovel): SNNovel {
        val response = client.newCall(GET(novel.url, headers)).awaitSuccess()
        return if (response.isFromReadList()) {
            response.parseAs<ReadListDto>(komgaClient.json).toSNNovel(baseUrl)
        } else if (response.isFromBook()) {
            response.parseAs<BookDto>(komgaClient.json).toSNNovel(baseUrl)
        } else {
            response.parseAs<SeriesDto>(komgaClient.json).toSNNovel(baseUrl)
        }
    }

    override suspend fun getChapterList(novel: SNNovel): List<SNChapter> {
        val url = when {
            novel.url.isFromBook() -> "${novel.url}?unpaged=true&media_status=READY&deleted=false"
            else -> "${novel.url}/books?unpaged=true&media_status=READY&deleted=false"
        }
        val response = client.newCall(GET(url, headers)).awaitSuccess()
        return if (response.isFromBook()) {
            val book = response.parseAs<BookDto>(komgaClient.json)
            listOf(book.toSNChapter(baseUrl = baseUrl, mediaProfile = book.media.mediaProfile))
        } else {
            val page = response.parseAs<PageWrapperDto<BookDto>>(komgaClient.json).content
            val isFromReadList = response.isFromReadList()
            page
                .mapIndexed { index, book ->
                    book.toSNChapter(isFromReadList, index.toFloat(), baseUrl, book.media.mediaProfile)
                }
                .sortedByDescending { it.chapter_number }
        }
    }

    override suspend fun getChapterContent(chapter: SNChapter): ChapterContent {
        val cleanUrl = chapter.url.substringBefore("?")
        val bookId = cleanUrl.substringAfterLast("/")
        val isEpub = chapter.url.contains("mediaProfile=EPUB")
        if (isEpub) {
            return downloadAndParseEpub(bookId)
        }
        val pagesResponse = client.newCall(GET("${baseUrl}/api/v1/books/$bookId/pages", headers)).awaitSuccess()
        val pages = pagesResponse.parseAs<List<PageDto>>(komgaClient.json)
        val imageUrls = pages.map { "${baseUrl}/api/v1/books/$bookId/pages/${it.number}/media" }
        return if (imageUrls.isNotEmpty()) ChapterContent.images(imageUrls) else ChapterContent.text("No content")
    }

    private suspend fun downloadAndParseEpub(bookId: String): ChapterContent {
        val response = client.newCall(GET("${baseUrl}/api/v1/books/$bookId/file", headers)).awaitSuccess()
        val tempFile = File.createTempFile("komga_epub_", ".epub")
        try {
            tempFile.outputStream().use { output ->
                response.body.byteStream().use { input ->
                    input.copyTo(output)
                }
            }
            val epubBook = EpubParser.parse(tempFile)
            val texts = epubBook.linearSpineItems.indices.mapNotNull { index ->
                val spineItem = epubBook.linearSpineItems[index]
                if (spineItem.type == com.canopus.chimareader.data.epub.SpineItemType.IMAGE_ONLY) {
                    null
                } else {
                    EpubParser().parseChapter(epubBook, index)
                }
            }
            if (texts.isEmpty()) return ChapterContent.text("No text content")
            return ChapterContent.text(texts.joinToString("\n\n"))
        } finally {
            tempFile.delete()
        }
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

    private fun parseNovelPage(response: Response): NovelPage {
        return if (response.isFromReadList()) {
            val data = response.parseAs<PageWrapperDto<ReadListDto>>(komgaClient.json)
            NovelPage(data.content.map { it.toSNNovel(baseUrl) }, !data.last)
        } else if (response.isFromBook()) {
            val data = response.parseAs<PageWrapperDto<BookDto>>(komgaClient.json)
            NovelPage(data.content.map { it.toSNNovel(baseUrl) }, !data.last)
        } else {
            val data = response.parseAs<PageWrapperDto<SeriesDto>>(komgaClient.json)
            NovelPage(data.content.map { it.toSNNovel(baseUrl) }, !data.last)
        }
    }
}
