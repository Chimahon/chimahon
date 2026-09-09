package chimahon.novel.data.epub

/**
 * In-memory book for source novels: full spine/TOC for navigation and counts,
 * no package files. Chapter hrefs keep the `chapter_N.xhtml` scheme so reader
 * identity URLs stay stable. Content arrives per chapter through the loader and
 * lands in [EpubBook.contentOverride]; counts follow lazily from real text.
 */
object VirtualNovelBook {

    data class ChapterRef(
        val title: String,
    )

    fun fromChapters(
        title: String?,
        author: String?,
        language: String?,
        chapters: List<ChapterRef>,
    ): EpubBook {
        // Hrefs carry the OEBPS prefix like parsed packages, so identity URLs,
        // link matching and image bases behave identically for both kinds.
        val items = chapters.mapIndexed { index, _ ->
            SpineItem(idref = "ch$index")
        }
        val manifest = chapters.mapIndexed { index, _ ->
            val href = "$CONTENT_DIR/${hrefFor(index)}"
            "ch$index" to ManifestItem(id = "ch$index", href = href, mediaType = EpubMediaType.XHTML)
        }.toMap()
        val toc = chapters.mapIndexed { index, chapter ->
            TocEntry(id = "ch$index", label = chapter.title, href = "$CONTENT_DIR/${hrefFor(index)}")
        }
        return EpubBook(
            title = title,
            author = author,
            language = language,
            metadata = EpubMetadata(title = title, language = language),
            manifest = EpubManifest(items = manifest),
            spine = EpubSpine(items = items),
            tableOfContents = toc,
            contentDirectory = "OEBPS",
        )
    }

    private const val CONTENT_DIR = "OEBPS"

    fun hrefFor(index: Int): String = "chapter_$index.xhtml"
}
