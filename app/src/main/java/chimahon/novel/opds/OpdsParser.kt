package chimahon.novel.opds

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** Namespace-prefix independent Atom parsing; feed links remain separate from publications. */
internal object OpdsParser {
    fun parse(xml: String, responseUrl: String, catalogId: String): OpdsFeed {
        val document = Jsoup.parse(xml, responseUrl, Parser.xmlParser())
        val root = document.children().firstOrNull { it.localName() in setOf("feed", "entry") }
            ?: error("This URL did not return an OPDS 1.2 catalog")
        val base = base(root, responseUrl)
        val links = root.childrenNamed("link")
        val search = links.firstOrNull { it.attr("rel") == "search" }
        val navigation = mutableListOf<OpdsNavigation>()
        val books = mutableListOf<OpdsBook>()
        val entries = if (root.localName() == "entry") listOf(root) else root.childrenNamed("entry")
        entries.forEach { entry ->
            val entryBase = if (entry === root) base else base(entry, base)
            val entryLinks = entry.childrenNamed("link")
            val acquisition = entryLinks.firstOrNull {
                it.attr("rel") in setOf("http://opds-spec.org/acquisition", "http://opds-spec.org/acquisition/open-access") &&
                    it.attr("type").substringBefore(';').trim().equals("application/epub+zip", true)
            }
            val detail = entryLinks.firstOrNull {
                it.attr("rel") in setOf("alternate", "self") && it.attr("type").startsWith("application/atom+xml")
            }
            val publication = acquisition != null || entryLinks.any { it.attr("rel").startsWith("http://opds-spec.org/acquisition") } ||
                detail?.attr("type")?.contains("type=entry") == true
            val title = entry.childText("title").ifBlank { "Untitled" }
            if (publication) {
                val identity = entry.childText("id").ifBlank {
                    detail?.let { resolve(entryBase, it.attr("href")) }
                        ?: acquisition?.let { resolve(entryBase, it.attr("href")) }
                        ?: return@forEach
                }
                val cover = entryLinks.firstOrNull { it.attr("rel") == "http://opds-spec.org/image/thumbnail" }
                    ?: entryLinks.firstOrNull { it.attr("rel") in setOf("http://opds-spec.org/image", "x-stanza-cover-image") }
                books += OpdsBook(
                    catalogId = catalogId,
                    entryId = identity,
                    title = title,
                    author = entry.childrenNamed("author").map { it.childText("name") }.filter { it.isNotBlank() }.joinToString(", ").ifBlank { null },
                    description = entry.childText("summary").ifBlank { entry.childText("content") }.let { Jsoup.parse(it).text() }.ifBlank { null },
                    language = entry.childText("language").ifBlank { null },
                    cover = cover?.let { resolve(entryBase, it.attr("href")) },
                    acquisition = acquisition?.let { resolve(entryBase, it.attr("href")) },
                    detail = detail?.let { resolve(entryBase, it.attr("href")) },
                )
            } else {
                entryLinks.firstOrNull { it.attr("type").startsWith("application/atom+xml") }?.let {
                    navigation += OpdsNavigation(title, resolve(entryBase, it.attr("href")))
                }
            }
        }
        return OpdsFeed(
            title = root.childText("title"),
            navigation = navigation,
            books = books,
            next = links.firstOrNull { it.attr("rel") == "next" }?.let { resolve(base, it.attr("href")) },
            search = search?.let { resolve(base, it.attr("href")) },
            searchDescription = search?.attr("type")?.startsWith("application/opensearchdescription+xml") == true,
        )
    }

    fun searchTemplate(xml: String, responseUrl: String): String {
        val doc = Jsoup.parse(xml, responseUrl, Parser.xmlParser())
        val template = doc.allElements.firstOrNull {
            it.localName() == "url" && it.attr("type").startsWith("application/atom+xml")
        }?.attr("template")?.takeIf { it.isNotBlank() } ?: error("Catalog does not advertise OPDS search")
        return resolve(responseUrl, template)
    }

    fun expandSearch(template: String, query: String): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return Regex("\\{([^}]+)}").replace(template) { match ->
            when (match.groupValues[1].removeSuffix("?")) {
                "searchTerms" -> encoded
                "startIndex", "startPage" -> "1"
                "count" -> "50"
                "language" -> "*"
                "inputEncoding", "outputEncoding" -> "UTF-8"
                else -> if (match.groupValues[1].endsWith('?')) "" else error("Unsupported catalog search parameter")
            }
        }
    }

    private fun Element.localName() = tagName().substringAfter(':').lowercase()
    private fun Element.childrenNamed(name: String) = children().filter { it.localName() == name }
    private fun Element.childText(name: String) = childrenNamed(name).firstOrNull()?.text().orEmpty()
    private fun base(element: Element, parent: String): String = element.attr("xml:base").takeIf { it.isNotBlank() }
        ?.let { resolve(parent, it) } ?: parent

    private fun resolve(base: String, href: String): String {
        require(href.isNotBlank()) { "Empty catalog link" }
        // HttpUrl escapes OpenSearch braces; retain them until expansion.
        return base.toHttpUrl().resolve(href)?.toString()?.replace("%7B", "{")?.replace("%7D", "}")
            ?: error("Invalid catalog link")
    }
}
