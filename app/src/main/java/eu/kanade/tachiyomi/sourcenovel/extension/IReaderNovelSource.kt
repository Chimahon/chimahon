package eu.kanade.tachiyomi.sourcenovel.extension

import eu.kanade.tachiyomi.extension.ireader.IReaderRuntime
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.sourcenovel.HttpNovelSource
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.ContentItem
import eu.kanade.tachiyomi.sourcenovel.model.NovelPage
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import ireader.core.source.CatalogSource
import ireader.core.source.HttpSource
import ireader.core.source.model.Command
import ireader.core.source.model.Filter
import ireader.core.source.model.ImageBase64
import ireader.core.source.model.ImageUrl
import ireader.core.source.model.PageUrl
import ireader.core.source.model.Listing
import ireader.core.source.model.MangaInfo
import ireader.core.source.model.MangasPageInfo
import ireader.core.source.model.MovieUrl
import ireader.core.source.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class IReaderNovelSource(
    val catalogSource: CatalogSource,
    private val runtime: IReaderRuntime,
) : NovelsPageSource, HttpNovelSource {

    private val baseUrl: String?
        get() = (catalogSource as? HttpSource)?.baseUrl

    override val id: Long get() = catalogSource.id
    override val name: String get() = catalogSource.name
    override val lang: String get() = catalogSource.lang
    override val supportsLatest: Boolean get() = catalogSource.supportsLatest()

    // -- HttpNovelSource --

    override fun getNovelUrl(novel: SNNovel): String? {
        return runtime.resolveUrl(novel.url, baseUrl)
    }

    override fun getChapterUrl(chapter: SNChapter): String? {
        return runtime.resolveUrl(chapter.url, baseUrl)
    }

    // -- NovelsPageSource --

    override suspend fun getPopularNovels(page: Int): NovelPage {
        val listings = catalogSource.getListings()
        val listing = findListing(listings, "Popular")
            ?: listings.firstOrNull()
            ?: return NovelPage(emptyList(), false)
        return withContext(Dispatchers.IO) {
            catalogSource.getMangaList(listing, page).toNovelPage()
        }
    }

    override suspend fun getLatestUpdates(page: Int): NovelPage {
        val listings = catalogSource.getListings()
        val listing = findListing(listings, "Latest")
        if (listing == null) {
            val pop = findListing(listings, "Popular") ?: listings.firstOrNull()
            if (pop != null) {
                return withContext(Dispatchers.IO) {
                    catalogSource.getMangaList(pop, page).toNovelPage()
                }
            }
            return NovelPage(emptyList(), false)
        }
        return withContext(Dispatchers.IO) {
            catalogSource.getMangaList(listing, page).toNovelPage()
        }
    }

    override suspend fun getSearchNovels(
        page: Int,
        query: String,
        filters: FilterList,
    ): NovelPage {
        if (query.isBlank()) return NovelPage(emptyList(), false)
        val titleFilter = Filter.Title().apply { value = query }
        return withContext(Dispatchers.IO) {
            catalogSource.getMangaList(listOf(titleFilter), page).toNovelPage()
        }
    }

    override fun getFilterList(): FilterList = FilterList()

    // -- NovelSource --

    override suspend fun getNovelDetails(novel: SNNovel): SNNovel {
        val input = novel.toMangaInfo()
        val nativeResult = runCatching {
            withContext(Dispatchers.IO) {
                catalogSource.getMangaDetails(input, emptyList())
            }
        }
        val nativeDetails = nativeResult.getOrNull()
        val details = if (nativeDetails != null && nativeDetails.hasDetailsBeyond(input)) {
            nativeDetails
        } else {
            val fetchCommands = runCatching { detailFetchCommands(novel.url) }.getOrDefault(emptyList())
            if (fetchCommands.isNotEmpty()) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        catalogSource.getMangaDetails(input, fetchCommands)
                    }
                }
                    .getOrNull()
                    ?.takeIf { it.hasDetailsBeyond(input) }
                    ?: nativeDetails
                    ?: nativeResult.getOrThrow()
            } else {
                nativeDetails ?: nativeResult.getOrThrow()
            }
        }
        return details.toSNNovel(fallback = novel)
    }

    override suspend fun getChapterList(novel: SNNovel): List<SNChapter> {
        val input = novel.toMangaInfo()
        val nativeResult = runCatching {
            withContext(Dispatchers.IO) {
                catalogSource.getChapterList(input, emptyList())
            }
        }
        val nativeChapters = nativeResult.getOrNull()
        val chapters = if (!nativeChapters.isNullOrEmpty()) {
            nativeChapters
        } else {
            val fetchCommands = runCatching { chapterFetchCommands(novel.url) }.getOrDefault(emptyList())
            if (fetchCommands.isNotEmpty()) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        catalogSource.getChapterList(input, fetchCommands)
                    }
                }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?: nativeChapters
                    ?: nativeResult.getOrThrow()
            } else {
                nativeChapters ?: nativeResult.getOrThrow()
            }
        }
        return chapters.map { it.toSNChapter() }
    }

    override suspend fun getChapterContent(chapter: SNChapter): ChapterContent {
        val input = chapter.toChapterInfo()
        val nativeResult = runCatching {
            withContext(Dispatchers.IO) {
                catalogSource.getPageList(input, emptyList()).toChapterContent()
            }
        }
        val nativeContent = nativeResult.getOrNull()
        if (nativeContent?.hasContent() == true) return nativeContent

        val fetchCommands = runCatching { contentFetchCommands(chapter.url) }.getOrDefault(emptyList())
        if (fetchCommands.isNotEmpty()) {
            val fetchedContent = runCatching {
                withContext(Dispatchers.IO) {
                    catalogSource.getPageList(input, fetchCommands).toChapterContent()
                }
            }.getOrNull()
            if (fetchedContent?.hasContent() == true) return fetchedContent
        }
        nativeResult.exceptionOrNull()?.let { throw it }
        throw IllegalStateException("Source returned no chapter content")
    }

    private suspend fun detailFetchCommands(url: String): List<Command<*>> {
        if (catalogSource.getCommands().none { it is Command.Detail.Fetch }) return emptyList()
        val fetched = runtime.fetch(url, baseUrl) ?: return emptyList()
        return listOf(Command.Detail.Fetch(fetched.url, fetched.html))
    }

    private suspend fun chapterFetchCommands(url: String): List<Command<*>> {
        if (catalogSource.getCommands().none { it is Command.Chapter.Fetch }) return emptyList()
        val fetched = runtime.fetch(url, baseUrl) ?: return emptyList()
        return listOf(Command.Chapter.Fetch(fetched.url, fetched.html))
    }

    private suspend fun contentFetchCommands(url: String): List<Command<*>> {
        if (catalogSource.getCommands().none { it is Command.Content.Fetch }) return emptyList()
        val fetched = runtime.fetch(url, baseUrl) ?: return emptyList()
        return listOf(Command.Content.Fetch(fetched.url, fetched.html))
    }

    // -- Model mapping --

    private fun MangaInfo.toSNNovel(fallback: SNNovel? = null): SNNovel = SNNovel(
        url = fallback?.url?.takeIf { it.isNotBlank() } ?: key,
        title = title.ifBlank { fallback?.title.orEmpty() },
        author = author.takeIf { it.isNotBlank() } ?: fallback?.author,
        artist = artist.takeIf { it.isNotBlank() } ?: fallback?.artist,
        description = description.takeIf { it.isNotBlank() } ?: fallback?.description,
        genre = genres.joinToString(", ").takeIf { it.isNotBlank() } ?: fallback?.genre,
        status = status.toInt().takeUnless { it == SNNovel.UNKNOWN } ?: fallback?.status ?: SNNovel.UNKNOWN,
        thumbnail_url = (cover.takeIf { it.isNotBlank() } ?: fallback?.thumbnail_url)
            ?.let { runtime.resolveUrl(it, baseUrl) ?: it },
    )

    private fun SNNovel.toMangaInfo(): MangaInfo = MangaInfo(
        key = url,
        title = title,
        author = author ?: "",
        artist = artist ?: "",
        description = description ?: "",
        genres = genre?.split(", ").orEmpty().filter { it.isNotBlank() },
        status = status.toLong(),
        cover = thumbnail_url ?: "",
    )

    private fun ireader.core.source.model.ChapterInfo.toSNChapter(): SNChapter = SNChapter(
        name = name,
        url = key,
        chapter_number = number,
        date_upload = dateUpload,
        scanlator = scanlator.ifBlank { null },
    )

    private fun SNChapter.toChapterInfo(): ireader.core.source.model.ChapterInfo =
        ireader.core.source.model.ChapterInfo(
            key = url,
            name = name,
            dateUpload = date_upload,
            number = chapter_number,
            scanlator = scanlator ?: "",
        )

    private suspend fun List<ireader.core.source.model.Page>.toChapterContent(): ChapterContent {
        val textParts = mutableListOf<String>()
        val imageUrls = mutableListOf<String>()
        val items = mutableListOf<ContentItem>()

        for (page in this) {
            val resolvedPage = if (page is PageUrl) {
                val httpSource = catalogSource as? HttpSource ?: continue
                runCatching { httpSource.getPage(page) }.getOrNull() ?: continue
            } else {
                page
            }
            when (resolvedPage) {
                is ireader.core.source.model.Text -> {
                    textParts.add(resolvedPage.text)
                    items.add(ContentItem.Text(resolvedPage.text))
                }
                is ImageUrl -> {
                    val url = runtime.resolveUrl(resolvedPage.url, baseUrl) ?: resolvedPage.url
                    imageUrls.add(url)
                    items.add(ContentItem.Image(url))
                }
                is ImageBase64 -> {
                    val dataUrl = resolvedPage.data
                        .takeIf { it.startsWith("data:image/", ignoreCase = true) }
                        ?: "data:image/png;base64,${resolvedPage.data}"
                    imageUrls.add(dataUrl)
                    items.add(ContentItem.Image(dataUrl))
                }
                is MovieUrl,
                is PageUrl,
                is Subtitle,
                -> Unit
            }
        }

        return when {
            textParts.isNotEmpty() && imageUrls.isEmpty() ->
                ChapterContent.text(textParts.joinToString("\n\n"))
            imageUrls.isNotEmpty() && textParts.isEmpty() ->
                ChapterContent.images(imageUrls)
            items.isNotEmpty() -> ChapterContent.mixed(items)
            else -> ChapterContent.text("")
        }
    }

    private fun MangasPageInfo.toNovelPage(): NovelPage = NovelPage(
        novels = mangas.map { it.toSNNovel() },
        hasNextPage = hasNextPage,
    )

    private fun findListing(listings: List<Listing>, name: String): Listing? {
        return listings.find { it.name.equals(name, ignoreCase = true) }
    }

    private fun MangaInfo.hasDetailsBeyond(input: MangaInfo): Boolean {
        return title.isNotBlank() && (
            title != input.title ||
                author.isNotBlank() ||
                artist.isNotBlank() ||
                description.isNotBlank() ||
                genres.isNotEmpty() ||
                cover.isNotBlank()
            )
    }

    private fun ChapterContent.hasContent(): Boolean = when (this) {
        is ChapterContent.Text -> text.isNotBlank()
        is ChapterContent.Html -> html.isNotBlank()
        is ChapterContent.Images -> urls.any { it.isNotBlank() }
        is ChapterContent.Mixed -> items.isNotEmpty()
    }
}
