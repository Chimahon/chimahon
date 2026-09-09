package chimahon.novel.data.epub

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

class TocParser {

    fun parse(tocContent: String, manifest: EpubManifest): List<TocEntry> {
        val doc = Jsoup.parse(tocContent, "", Parser.xmlParser())
        val elements = doc.select("*")

        return if (elements.any { it.tagName().endsWith("navMap", ignoreCase = true) }) {
            parseNcx(elements)
        } else {
            parseXhtmlNav(elements)
        }
    }

    private fun parseNcx(elements: org.jsoup.select.Elements): List<TocEntry> {
        val navMap = elements.firstOrNull { it.tagName().endsWith("navMap", ignoreCase = true) } ?: return emptyList()
        return parseNavPoints(navMap)
    }

    private fun parseNavPoints(parent: org.jsoup.nodes.Element): List<TocEntry> {
        val entries = mutableListOf<TocEntry>()

        parent.children().filter { it.tagName().endsWith("navPoint", ignoreCase = true) }.forEach { point ->
            val id = point.attr("id").ifBlank { java.util.UUID.randomUUID().toString() }
            val labelNode = point.children().firstOrNull { it.tagName().endsWith("navLabel", ignoreCase = true) }
            val textNode = labelNode?.children()?.firstOrNull { it.tagName().endsWith("text", ignoreCase = true) }
            val label = textNode?.text() ?: labelNode?.text() ?: "Unknown"
            
            val contentNode = point.children().firstOrNull { it.tagName().endsWith("content", ignoreCase = true) }
            val href = contentNode?.attr("src") ?: ""
            val decodedHref = java.net.URLDecoder.decode(href, "UTF-8")
            val children = parseNavPoints(point)

            entries.add(
                TocEntry(
                    id = id,
                    label = label,
                    href = decodedHref,
                    children = children
                )
            )
        }

        return entries
    }

    private fun parseXhtmlNav(elements: org.jsoup.select.Elements): List<TocEntry> {
        val nav = findTocNav(elements) ?: return emptyList()
        val ol = nav.children().firstOrNull { it.tagName().endsWith("ol", ignoreCase = true) } ?: return emptyList()
        return parseOl(ol)
    }

    private fun findTocNav(elements: org.jsoup.select.Elements): org.jsoup.nodes.Element? {
        return elements.firstOrNull { nav ->
            nav.tagName().endsWith("nav", ignoreCase = true) && 
            (nav.attr("epub:type").contains("toc") || nav.attr("type").contains("toc"))
        }
    }

    private fun parseOl(ol: org.jsoup.nodes.Element): List<TocEntry> {
        val entries = mutableListOf<TocEntry>()

        ol.children().filter { it.tagName().endsWith("li", ignoreCase = true) }.forEach { li ->
            val a = li.children().firstOrNull { it.tagName().endsWith("a", ignoreCase = true) }
            if (a != null) {
                val label = a.text().ifBlank { "Unknown" }
                val href = a.attr("href")
                val decodedHref = java.net.URLDecoder.decode(href, "UTF-8")
                val nestedOl = li.children().firstOrNull { it.tagName().endsWith("ol", ignoreCase = true) }
                val children = if (nestedOl != null) parseOl(nestedOl) else emptyList()

                entries.add(
                    TocEntry(
                        id = java.util.UUID.randomUUID().toString(),
                        label = label,
                        href = decodedHref,
                        children = children
                    )
                )
            }
        }

        return entries
    }

    fun parseToc(tocId: String?, manifest: EpubManifest, extractor: EpubExtractorBase, contentDir: String): List<TocEntry> {
        val resolvedTocId = tocId ?: manifest.items.entries.find { it.value.properties?.contains("nav") == true }?.key
            ?: return emptyList()

        val tocItem = manifest.items[resolvedTocId] ?: return emptyList()
        val tocPath = if (contentDir.isNotEmpty()) "$contentDir${tocItem.href}" else tocItem.href

        val tocContent = extractor.getFileContent(tocPath) ?: return emptyList()

        return parse(tocContent, manifest)
    }
}
