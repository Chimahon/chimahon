package chimahon.novel.source.opds

import chimahon.novel.model.NovelServer
import com.canopus.chimareader.data.epub.EpubParser
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.NovelPage
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.URI
import java.security.MessageDigest

class OpdsSource(
    private val server: NovelServer,
) : NovelsPageSource {

    private val network: NetworkHelper by lazy { Injekt.get<NetworkHelper>() }
    private val json = Json { ignoreUnknownKeys = true }

    override val id: Long by lazy { generateId(server) }
    override val name: String get() = server.name
    override val lang: String get() = "all"
    override val supportsLatest: Boolean get() = true

    private val baseUrl: String get() = server.baseUrl.trimEnd('/')

    private val client: OkHttpClient
        get() {
            val builder = network.client.newBuilder()
            if (server.requiresAuth()) {
                if (!server.apiKey.isNullOrBlank()) {
                    builder.addInterceptor { chain ->
                        chain.proceed(chain.request().newBuilder().header("X-API-Key", server.apiKey!!).build())
                    }
                } else if (!server.username.isNullOrBlank()) {
                    builder.addInterceptor { chain ->
                        val credential = Credentials.basic(server.username!!, server.password.orEmpty())
                        chain.proceed(chain.request().newBuilder().header("Authorization", credential).build())
                    }
                }
            }
            return builder.build()
        }

    // ===== Feed discovery =====

    private var catalogRoot: String? = null

    private suspend fun getCatalogRoot(): String {
        catalogRoot?.let { return it }
        // Try common OPDS catalog paths
        val candidates = listOf(
            "$baseUrl/opds-catalog/",
            "$baseUrl/opds",
            "$baseUrl/",
        )
        for (path in candidates) {
            try {
                val response = client.newCall(GET(path)).awaitSuccess()
                val body = response.body.string()
                val doc = Jsoup.parse(body, "UTF-8")
                if (doc.select("entry").isNotEmpty() || doc.select("feed").isNotEmpty() || body.trimStart().startsWith("{")) {
                    catalogRoot = path
                    return path
                }
            } catch (_: Exception) {}
        }
        catalogRoot = "$baseUrl/opds-catalog"
        return catalogRoot!!
    }

    private suspend fun findFeedUrl(rel: String): String? {
        val root = getCatalogRoot()
        val response = client.newCall(GET(root)).awaitSuccess()
        val body = response.body.string()
        if (body.trimStart().startsWith("{")) return null // OPDS 2.0 doesn't use navigation links
        val doc = Jsoup.parse(body, "UTF-8")
        return doc.select("link[rel=$rel]").firstOrNull()?.attr("href")?.let { resolveUrl(it) }
    }

    private suspend fun getPopularFeed(): String = findFeedUrl("http://opds-spec.org/sort/popular") ?: "$baseUrl/opds-catalog?orderby=popularity"
    private suspend fun getLatestFeed(): String = findFeedUrl("http://opds-spec.org/sort/new") ?: "$baseUrl/opds-catalog?orderby=new"
    private fun getSearchFeed(query: String) = "$baseUrl/opds-catalog?search=$query"

    // ===== Novel browsing =====

    override suspend fun getPopularNovels(page: Int): NovelPage {
        return fetchFeedPage(getPopularFeed(), page)
    }

    override suspend fun getSearchNovels(page: Int, query: String, filters: eu.kanade.tachiyomi.source.model.FilterList): NovelPage {
        return fetchFeedPage(getSearchFeed(query), page)
    }

    override suspend fun getLatestUpdates(page: Int): NovelPage {
        return fetchFeedPage(getLatestFeed(), page)
    }

    override fun getFilterList() = eu.kanade.tachiyomi.source.model.FilterList()

    private suspend fun fetchFeedPage(feedUrl: String, page: Int): NovelPage {
        val url = if (page > 1) {
            // Fetch feed and find next link
            val initial = client.newCall(GET(feedUrl)).awaitSuccess()
            val body = initial.body.string()
            val nextUrl = if (body.trimStart().startsWith("{")) {
                findOpds2NextLink(body, page)
            } else {
                findOpds1NextLink(Jsoup.parse(body, "UTF-8"), page)
            }
            nextUrl ?: feedUrl
        } else {
            feedUrl
        }
        val response = client.newCall(GET(url)).awaitSuccess()
        val body = response.body.string()
        return if (body.trimStart().startsWith("{")) {
            parseOpds2List(body)
        } else {
            parseOpds1List(body, url)
        }
    }

    private fun findOpds1NextLink(doc: Document, targetPage: Int): String? {
        var currentUrl: String? = null
        var pageCount = 1
        val links = doc.select("link[rel=next]")
        if (links.isEmpty()) return null
        // For OPDS 1.2, the feed itself contains the next link
        return links.first()?.attr("href")?.let { resolveUrl(it) }
    }

    private fun findOpds2NextLink(body: String, targetPage: Int): String? {
        val root = json.parseToJsonElement(body).jsonObject
        val feed = root["feed"] ?: root
        val links = feed.jsonObject["links"]?.jsonArray ?: return null
        return links.firstOrNull {
            it.jsonObject["rel"]?.jsonPrimitive?.content == "next"
        }?.jsonObject?.get("href")?.jsonPrimitive?.content
    }

    // ===== Novel details =====

    override suspend fun getNovelDetails(novel: SNNovel): SNNovel {
        val response = client.newCall(GET(novel.url)).awaitSuccess()
        return parseNovelDetails(response, novel)
    }

    override suspend fun getChapterList(novel: SNNovel): List<SNChapter> {
        val response = client.newCall(GET(novel.url)).awaitSuccess()
        return parseChapterList(response)
    }

    // ===== Chapter content (EPUB + HTML acquisition) =====

    override suspend fun getChapterContent(chapter: SNChapter): ChapterContent {
        // Check if URL points to an EPUB file
        if (chapter.url.endsWith(".epub", ignoreCase = true) || chapter.url.contains("epub", ignoreCase = true)) {
            return downloadAndParseEpub(chapter.url)
        }
        // Try HTML/XML acquisition
        val response = client.newCall(GET(chapter.url)).awaitSuccess()
        val body = response.body.string()
        val contentType = (response.header("Content-Type") ?: "").lowercase()
        if (contentType.contains("epub") || contentType.contains("octet-stream")) {
            return downloadAndParseEpub(chapter.url)
        }
        // Extract text from HTML
        val doc = Jsoup.parse(body)
        val content = doc.selectFirst("div.entry-content, div.content, article, body")
        return ChapterContent.text(content?.wholeText() ?: body)
    }

    private suspend fun downloadAndParseEpub(url: String): ChapterContent {
        val response = client.newCall(GET(url)).awaitSuccess()
        val tempFile = File.createTempFile("opds_epub_", ".epub")
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

    // ===== OPDS 1.2 (Atom XML) parser =====

    private fun parseOpds1List(body: String, feedUrl: String): NovelPage {
        val novels = mutableListOf<SNNovel>()
        val doc = Jsoup.parse(body, "UTF-8")

        doc.select("entry").forEach { entry ->
            val title = entry.selectFirst("title")?.text() ?: return@forEach
            val id = entry.selectFirst("id")?.text() ?: return@forEach
            val cover = entry.select("link[rel=http\\:opds-spec.org\\/image], link[rel=http\\:opds-spec.org\\/image-thumbnail], link[type^=image]")
                .firstOrNull()?.attr("href")
            val author = entry.selectFirst("author name")?.text()
            val summary = entry.selectFirst("content, summary")?.text()

            val href = entry.select("link[rel=http\\:opds-spec.org\\/acquisition], link[rel=alternate]")
                .firstOrNull()?.attr("href") ?: id

            novels.add(
                SNNovel(
                    url = resolveUrl(href),
                    title = title,
                    author = author,
                    description = summary,
                    thumbnail_url = cover?.let { resolveUrl(it) },
                ),
            )
        }

        val hasNext = doc.select("link[rel=next]").isNotEmpty()
        return NovelPage(novels, hasNext)
    }

    // ===== OPDS 2.0 (JSON) parser =====

    private fun parseOpds2List(body: String): NovelPage {
        val novels = mutableListOf<SNNovel>()
        val root = json.parseToJsonElement(body).jsonObject
        val feed = root["feed"] ?: root
        val publications = feed.jsonObject["publications"]?.jsonArray
            ?: feed.jsonObject["books"]?.jsonArray
            ?: emptyList()

        for (publication in publications) {
            val obj = publication.jsonObject
            val metadata = obj["metadata"]?.jsonObject ?: obj
            val title = metadata["title"]?.jsonPrimitive?.content ?: "Unknown"

            val href = obj["links"]?.jsonArray?.firstOrNull {
                it.jsonObject["rel"]?.jsonPrimitive?.content == "self" ||
                    it.jsonObject["type"]?.jsonPrimitive?.content?.contains("opds-publication") == true
            }?.jsonObject?.get("href")?.jsonPrimitive?.content ?: continue

            val cover = obj["links"]?.jsonArray?.firstOrNull {
                it.jsonObject["rel"]?.jsonPrimitive?.content == "http://opds-spec.org/image" ||
                    it.jsonObject["type"]?.jsonPrimitive?.content?.startsWith("image/") == true
            }?.jsonObject?.get("href")?.jsonPrimitive?.content

            val author = metadata["author"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                ?: metadata["authors"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("name")?.jsonPrimitive?.content

            novels.add(
                SNNovel(
                    url = resolveUrl(href),
                    title = title,
                    author = author,
                    thumbnail_url = cover?.let { resolveUrl(it) },
                ),
            )
        }

        val hasNext = feed.jsonObject["links"]?.jsonArray?.any {
            it.jsonObject["rel"]?.jsonPrimitive?.content == "next"
        } == true

        return NovelPage(novels, hasNext)
    }

    // ===== Detail / Chapter parsers =====

    private fun parseNovelDetails(response: Response, novel: SNNovel): SNNovel {
        val body = response.body.string()
        val updated = SNNovel.create()
        if (body.trimStart().startsWith("{")) {
            val root = json.parseToJsonElement(body).jsonObject
            val meta = root["metadata"]?.jsonObject ?: root
            updated.title = meta["title"]?.jsonPrimitive?.content ?: novel.title
            updated.author = meta["author"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                ?: meta["authors"]?.jsonArray?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content
                ?: novel.author
            updated.description = meta["description"]?.jsonPrimitive?.content ?: novel.description
            updated.thumbnail_url = novel.thumbnail_url
            updated.initialized = true
        } else {
            val doc = Jsoup.parse(body, "UTF-8")
            updated.title = doc.selectFirst("title")?.text() ?: novel.title
            updated.author = doc.selectFirst("author name")?.text() ?: novel.author
            updated.description = doc.selectFirst("content, summary")?.text() ?: novel.description
            updated.thumbnail_url = novel.thumbnail_url
            updated.initialized = true
        }
        return updated
    }

    private fun parseChapterList(response: Response): List<SNChapter> {
        val body = response.body.string()
        val chapters = mutableListOf<SNChapter>()

        if (body.trimStart().startsWith("{")) {
            val root = json.parseToJsonElement(body).jsonObject
            val publications = root["publications"]?.jsonArray
                ?: root["feed"]?.jsonObject?.get("publications")?.jsonArray
                ?: root["feed"]?.jsonObject?.get("books")?.jsonArray
                ?: emptyList()

            publications.forEachIndexed { index, entry ->
                val obj = entry.jsonObject
                val meta = obj["metadata"]?.jsonObject ?: obj
                val title = meta["title"]?.jsonPrimitive?.content ?: "Unknown"
                val acquisitionLink = findAcquisitionLink(obj)
                val url = acquisitionLink ?: obj["links"]?.jsonArray?.firstOrNull {
                    it.jsonObject["rel"]?.jsonPrimitive?.content == "self"
                }?.jsonObject?.get("href")?.jsonPrimitive?.content ?: return@forEachIndexed
                chapters.add(
                    SNChapter(
                        name = title,
                        url = resolveUrl(url),
                        chapter_number = (index + 1).toFloat(),
                    ),
                )
            }
        } else {
            val doc = Jsoup.parse(body, "UTF-8")
            doc.select("entry").forEachIndexed { index, entry ->
                val title = entry.selectFirst("title")?.text() ?: return@forEachIndexed
                val date = entry.selectFirst("updated, published")?.text()
                val acquisitionLink = entry.select("link[rel=http\\:opds-spec.org\\/acquisition], link[rel=http\\:opds-spec.org\\/acquisition\\/open-access]")
                    .firstOrNull()?.attr("href")
                val url = acquisitionLink ?: entry.selectFirst("id")?.text() ?: return@forEachIndexed

                chapters.add(
                    SNChapter(
                        name = title,
                        url = resolveUrl(url),
                        chapter_number = (index + 1).toFloat(),
                        date_upload = date?.let { parseDate(it) } ?: 0L,
                    ),
                )
            }
        }

        return chapters
    }

    private fun findAcquisitionLink(obj: kotlinx.serialization.json.JsonObject): String? {
        val links = obj["links"]?.jsonArray ?: return null
        // Prefer direct acquisition links
        for (link in links) {
            val rel = link.jsonObject["rel"]?.jsonPrimitive?.content ?: continue
            if (rel == "http://opds-spec.org/acquisition" ||
                rel == "http://opds-spec.org/acquisition/open-access"
            ) {
                val href = link.jsonObject["href"]?.jsonPrimitive?.content ?: continue
                return href
            }
        }
        // Fallback: any link with EPUB media type
        for (link in links) {
            val type = link.jsonObject["type"]?.jsonPrimitive?.content ?: continue
            if (type.contains("epub") || type.contains("html")) {
                return link.jsonObject["href"]?.jsonPrimitive?.content
            }
        }
        return null
    }

    // ===== URL utilities =====

    private fun resolveUrl(url: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return try {
            URI(baseUrl).resolve(url).toString()
        } catch (_: Exception) {
            "$baseUrl$url"
        }
    }

    private fun parseDate(dateStr: String): Long =
        runCatching { java.time.Instant.parse(dateStr).toEpochMilli() }.getOrDefault(0L)

    companion object {
        fun generateId(server: NovelServer): Long {
            val key = "opds:${server.baseUrl}:${server.name}"
            val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
            return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
        }
    }
}
