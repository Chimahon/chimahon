package chimahon.novel.data.epub

import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

class OpfParser {

    fun parseOpf(opfContent: String, contentDir: String): OpfParseResult {
        val doc = Parser.xmlParser().parseInput(opfContent, "")

        val metadata = parseMetadata(doc)
        val manifest = parseManifest(doc)
        val spine = parseSpine(doc, manifest.items)

        return OpfParseResult(
            metadata = metadata,
            manifest = manifest,
            spine = spine,
            contentDir = contentDir,
        )
    }

    private fun parseMetadata(doc: Document): EpubMetadata {
        val dc = "dc"

        fun selectElement(selector: String): org.jsoup.nodes.Element? {
            val localName = selector.substringAfter("|")
            return doc.select(selector).first()
                ?: doc.select("*|$localName").first()
                ?: doc.select(localName).first()
                ?: doc.select("*").find { it.tagName().endsWith(":$localName") }
        }

        fun selectText(selector: String): String? {
            return selectElement(selector)?.text()
        }

        fun selectCreator(): EpubCreator? {
            val creatorEl = selectElement("$dc|creator") ?: return null
            val name = creatorEl.text()
            if (name.isNullOrBlank()) return null
            return EpubCreator(
                name = name,
                role = creatorEl.attr("opf:role").takeIf { it.isNotBlank() },
                fileAs = creatorEl.attr("opf:file-as").takeIf { it.isNotBlank() },
            )
        }

        fun selectContributor(): EpubCreator? {
            val contribEl = selectElement("$dc|contributor") ?: return null
            val name = contribEl.text()
            if (name.isNullOrBlank()) return null
            return EpubCreator(
                name = name,
                role = contribEl.attr("opf:role").takeIf { it.isNotBlank() },
                fileAs = contribEl.attr("opf:file-as").takeIf { it.isNotBlank() },
            )
        }

        val coverId = doc.select("meta[name=cover]").first()?.attr("content")
            ?: doc.select("manifest > item[properties~=cover-image]").first()?.attr("id")

        return EpubMetadata(
            title = selectText("$dc|title"),
            identifier = selectText("$dc|identifier"),
            language = selectText("$dc|language"),
            creator = selectCreator(),
            contributor = selectContributor(),
            publisher = selectText("$dc|publisher"),
            date = selectText("$dc|date"),
            description = selectText("$dc|description"),
            rights = selectText("$dc|rights"),
            subject = selectText("$dc|subject"),
            coverage = selectText("$dc|coverage"),
            format = selectText("$dc|format"),
            relation = selectText("$dc|relation"),
            source = selectText("$dc|source"),
            type = selectText("$dc|type"),
            coverId = coverId,
        )
    }

    private fun parseManifest(doc: Document): EpubManifest {
        val items = mutableMapOf<String, ManifestItem>()

        doc.select("manifest > item").forEach { item ->
            val id = item.attr("id")
            val href = item.attr("href")
            val mediaType = item.attr("media-type")
            val properties = item.attr("properties").takeIf { it.isNotBlank() }

            if (id.isNotBlank() && href.isNotBlank()) {
                items[id] = ManifestItem(
                    id = id,
                    href = href,
                    mediaType = EpubMediaType.fromString(mediaType),
                    properties = properties,
                )
            }
        }

        val manifestId = doc.select("manifest").first()?.attr("id")

        return EpubManifest(
            id = manifestId,
            items = items,
        )
    }

    private fun parseSpine(doc: Document, manifestItems: Map<String, ManifestItem>): EpubSpine {
        val items = mutableListOf<SpineItem>()

        doc.select("spine > itemref").forEach { itemref ->
            val idref = itemref.attr("idref")
            if (idref.isNotBlank()) {
                val linear = itemref.attr("linear")?.lowercase() != "no"
                items.add(
                    SpineItem(
                        idref = idref,
                        id = itemref.attr("id").takeIf { it.isNotBlank() },
                        linear = linear,
                    ),
                )
            }
        }

        var tocId: String? = null
        val spineEl = doc.select("spine").first()
        if (spineEl != null) {
            tocId = spineEl.attr("toc").takeIf { it.isNotBlank() }
        }

        if (tocId == null) {
            tocId = manifestItems.values.find { it.properties?.contains("nav") == true }?.id
        }

        val direction = spineEl?.attr("page-progression-direction")

        return EpubSpine(
            id = spineEl?.attr("id"),
            toc = tocId,
            pageProgressionDirection = PageProgressionDirection.fromString(direction),
            items = items,
        )
    }

    data class OpfParseResult(
        val metadata: EpubMetadata,
        val manifest: EpubManifest,
        val spine: EpubSpine,
        val contentDir: String,
    )
}
