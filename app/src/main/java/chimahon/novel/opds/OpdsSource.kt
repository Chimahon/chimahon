package chimahon.novel.opds

import android.content.Context
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.sourcenovel.NovelConfigurableSource
import eu.kanade.tachiyomi.sourcenovel.NovelFileSource
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.NovelPage
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class OpdsSource(
    private val context: Context,
    val store: OpdsStore = OpdsStore(),
    private val network: NetworkHelper = Injekt.get(),
    private val preferences: NetworkPreferences = Injekt.get(),
    private val client: okhttp3.OkHttpClient = OpdsNetwork.client,
) : NovelsPageSource, NovelConfigurableSource, NovelFileSource {
    override val id = ID
    override val name = "OPDS"
    override val lang = "all"
    override val supportsLatest = false
    private val nextPages = ConcurrentHashMap<String, String>()

    override fun setupPreferenceScreen(screen: PreferenceScreen) = OpdsSettings.populate(screen, store)
    override fun getFilterList() = FilterList()
    override suspend fun hasDownloadSupport() = true
    override suspend fun getChapterList(novel: SNNovel) = emptyList<SNChapter>()
    override suspend fun getChapterContent(chapter: SNChapter): ChapterContent = error("Download this EPUB to read it")

    fun headers(catalog: OpdsCatalog, url: String): Map<String, String> {
        val target = url.toHttpUrl()
        val origin = catalog.url.toHttpUrl()
        return buildMap {
            put("User-Agent", preferences.defaultUserAgent().get())
            if (catalog.username.isNotBlank() && target.scheme == origin.scheme &&
                target.host == origin.host && target.port == origin.port
            ) {
                put("Authorization", Credentials.basic(catalog.username, catalog.password))
            }
        }
    }

    suspend fun loadFeed(catalog: OpdsCatalog, url: String): OpdsFeed = withContext(Dispatchers.IO) {
        val (text, responseUrl) = fetchText(catalog, url)
        OpdsParser.parse(text, responseUrl, catalog.id).also { feed -> feed.books.forEach(store::saveBook) }
    }

    suspend fun searchUrl(catalog: OpdsCatalog, feed: OpdsFeed, query: String): String {
        val advertised = feed.search ?: loadFeed(catalog, catalog.url).search
            ?: error(context.getString(eu.kanade.tachiyomi.R.string.opds_search_unavailable))
        val template = if (feed.searchDescription || !advertised.contains('{')) {
            val (text, responseUrl) = fetchText(catalog, advertised)
            OpdsParser.searchTemplate(text, responseUrl)
        } else advertised
        return OpdsParser.expandSearch(template, query)
    }

    private suspend fun fetchText(catalog: OpdsCatalog, url: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/atom+xml, application/opensearchdescription+xml")
        headers(catalog, url).forEach { (name, value) -> request.header(name, value) }
        OpdsNetwork.read(client.newCall(request.build())) { response ->
            val body = response.body
            require(body.contentLength() <= MAX_FEED_BYTES) { "OPDS catalog is too large" }
            val bytes = body.byteStream().use { OpdsNetwork.readLimited(it, MAX_FEED_BYTES) }
            bytes.toString(Charsets.UTF_8) to response.request.url.toString()
        }
    }

    override suspend fun getPopularNovels(page: Int): NovelPage {
        val catalog = store.active() ?: return NovelPage(emptyList(), false)
        return paged(catalog, catalog.url, page)
    }

    override suspend fun getLatestUpdates(page: Int) = getPopularNovels(page)

    override suspend fun getSearchNovels(page: Int, query: String, filters: FilterList): NovelPage {
        val catalog = store.active() ?: return NovelPage(emptyList(), false)
        val root = loadFeed(catalog, catalog.url)
        return paged(catalog, searchUrl(catalog, root, query), page)
    }

    private suspend fun paged(catalog: OpdsCatalog, initial: String, page: Int): NovelPage {
        val key = opdsHash("${catalog.id}\n$initial")
        val url = if (page == 1) initial else nextPages["$key:$page"] ?: return NovelPage(emptyList(), false)
        val feed = loadFeed(catalog, url)
        feed.next?.let { nextPages["$key:${page + 1}"] = it }
        return NovelPage(feed.books.map { it.toNovel().copy(thumbnail_url = cachedCover(it)) }, feed.next != null)
    }

    override suspend fun getNovelDetails(novel: SNNovel): SNNovel = withContext(Dispatchers.IO) {
        val book = resolveBook(novel.url)
        val image = cachedCover(book) ?: book.cover?.let { cover ->
            runCatching {
                val catalog = store.catalog(book.catalogId)
                val request = Request.Builder().url(cover)
                headers(catalog, cover).forEach { (name, value) -> request.header(name, value) }
                val target = coverFile(book)
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, "${book.key}.${java.util.UUID.randomUUID()}.tmp")
                try {
                    OpdsNetwork.read(client.newCall(request.build())) { response ->
                        require(response.body.contentLength() <= MAX_FEED_BYTES)
                        val bytes = response.body.byteStream().use { OpdsNetwork.readLimited(it, MAX_FEED_BYTES) }
                        temporary.writeBytes(bytes)
                    }
                    check(temporary.length() > 0 && temporary.renameTo(target))
                    target.toURI().toString()
                } finally {
                    temporary.delete()
                }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                null
            }
        }
        book.toNovel().copy(thumbnail_url = image)
    }

    private suspend fun resolveBook(path: String): OpdsBook {
        val book = store.book(path)
        if (book.acquisition != null || book.detail == null) return book
        val resolved = loadFeed(store.catalog(book.catalogId), book.detail).books
            .firstOrNull { it.entryId == book.entryId } ?: error("No downloadable EPUB in this entry")
        return resolved.copy(entryId = book.entryId).also(store::saveBook)
    }

    override suspend fun getDownloadUrl(path: String, format: String?): String? = resolveBook(path).acquisition
    override fun downloadHeaders(path: String): Map<String, String> {
        val book = store.book(path)
        return headers(store.catalog(book.catalogId), requireNotNull(book.acquisition))
    }
    override fun downloadFolderId(path: String) = store.book(path).folder
    override fun downloadClient() = client

    fun novel(book: OpdsBook) = book.toNovel().copy(thumbnail_url = cachedCover(book))
    private fun coverFile(book: OpdsBook) = File(context.cacheDir, "opds_covers/${book.key}.image")
    private fun cachedCover(book: OpdsBook) = coverFile(book).takeIf { it.isFile }?.toURI()?.toString()

    companion object {
        const val ID = 0x4f504453L
        const val PACKAGE_NAME = "builtin.chimahon.opds"
        private const val MAX_FEED_BYTES = 8 * 1024 * 1024
    }
}
