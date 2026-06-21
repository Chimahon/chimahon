package eu.kanade.tachiyomi.sourcenovel.extension

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
import ireader.core.source.model.Filter
import ireader.core.source.model.ImageBase64
import ireader.core.source.model.ImageUrl
import ireader.core.source.model.PageUrl
import ireader.core.source.model.Listing
import ireader.core.source.model.MangaInfo
import ireader.core.source.model.MangasPageInfo

class IReaderNovelSource(
    val catalogSource: CatalogSource,
) : NovelsPageSource, HttpNovelSource {

    override val id: Long get() = catalogSource.id
    override val name: String get() = catalogSource.name
    override val lang: String get() = catalogSource.lang
    override val supportsLatest: Boolean get() = catalogSource.supportsLatest()

    // -- HttpNovelSource --

    override fun getNovelUrl(novel: SNNovel): String? {
        return novel.url.takeIf { it.startsWith("http") }
    }

    override fun getChapterUrl(chapter: SNChapter): String? {
        return chapter.url.takeIf { it.startsWith("http") }
    }

    // -- NovelsPageSource --

    override suspend fun getPopularNovels(page: Int): NovelPage {
        val listing = findListing(catalogSource.getListings(), "Popular")
            ?: return NovelPage(emptyList(), false)
        return catalogSource.getMangaList(listing, page).toNovelPage()
    }

    override suspend fun getLatestUpdates(page: Int): NovelPage {
        val listing = findListing(catalogSource.getListings(), "Latest")
        if (listing == null) {
            val pop = findListing(catalogSource.getListings(), "Popular")
            if (pop != null) return catalogSource.getMangaList(pop, page).toNovelPage()
            return NovelPage(emptyList(), false)
        }
        return catalogSource.getMangaList(listing, page).toNovelPage()
    }

    override suspend fun getSearchNovels(
        page: Int,
        query: String,
        filters: FilterList,
    ): NovelPage {
        if (query.isBlank()) return NovelPage(emptyList(), false)
        val titleFilter = Filter.Title().apply { value = query }
        return catalogSource.getMangaList(listOf(titleFilter), page).toNovelPage()
    }

    override fun getFilterList(): FilterList = FilterList()

    // -- NovelSource --

    override suspend fun getNovelDetails(novel: SNNovel): SNNovel {
        return catalogSource.getMangaDetails(
            novel.toMangaInfo(),
            emptyList(),
        ).toSNNovel()
    }

    override suspend fun getChapterList(novel: SNNovel): List<SNChapter> {
        return catalogSource.getChapterList(
            novel.toMangaInfo(),
            emptyList(),
        ).map { it.toSNChapter() }
    }

    override suspend fun getChapterContent(chapter: SNChapter): ChapterContent {
        val pages = catalogSource.getPageList(
            chapter.toChapterInfo(),
            emptyList(),
        )
        return pages.toChapterContent()
    }

    // -- Model mapping --

    private fun MangaInfo.toSNNovel(): SNNovel = SNNovel(
        url = key,
        title = title,
        author = author.ifBlank { null },
        artist = artist.ifBlank { null },
        description = description.ifBlank { null },
        genre = genres.joinToString(", ").ifBlank { null },
        status = status.toInt(),
        thumbnail_url = cover.ifBlank { null },
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

    private fun List<ireader.core.source.model.Page>.toChapterContent(): ChapterContent {
        val textParts = mutableListOf<String>()
        val imageUrls = mutableListOf<String>()
        val items = mutableListOf<ContentItem>()

        for (page in this) {
            when (page) {
                is ireader.core.source.model.Text -> {
                    textParts.add(page.text)
                    items.add(ContentItem.Text(page.text))
                }
                is ImageUrl -> {
                    imageUrls.add(page.url)
                    items.add(ContentItem.Image(page.url))
                }
                is ImageBase64 -> { /* skip — can't render inline */ }
                is PageUrl -> {
                    imageUrls.add(page.url)
                    items.add(ContentItem.Image(page.url))
                }
                // ponytail: MovieUrl/Subtitle irrelevant for novels
                else -> {}
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
}
