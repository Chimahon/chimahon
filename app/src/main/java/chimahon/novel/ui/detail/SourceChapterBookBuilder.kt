package chimahon.novel.ui.detail

import android.content.Context
import chimahon.novel.data.BookStorage
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.ContentItem
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.serialization.Serializable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest

object SourceChapterBookBuilder {

    /**
     * Ensures the cache dir for a book exists and hands it back. Chapter
     * content, images and resume state all resolve on demand (DB + loader);
     * nothing is pre-written here anymore.
     */
    fun ensureBookDir(context: Context, bookId: String): File {
        return BookStorage.getBookDirectory(context, bookId).apply { mkdirs() }
    }

    private val SAFELIST = Safelist.relaxed()
        .addTags("ruby", "rt", "rp", "sup", "sub")
        .addAttributes("img", "src", "alt", "width", "height", "style")
        .addAttributes("a", "href", "title", "rel")
        .addAttributes(":all", "style", "class", "id", "lang", "dir", "title")
        .addProtocols("img", "src", "http", "https", "data")
        .addProtocols("a", "href", "http", "https", "mailto")

    private fun String.escapeXml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun String.escapeUrl(): String = this
        .replace("\\", "\\\\")
        .replace("&", "&amp;")
        .replace("<", "%3C")
        .replace(">", "%3E")
        .replace("\"", "%22")

    fun bookId(source: NovelSource, novel: SNNovel): String {
        val key = "${source.id}:${novel.url.ifBlank { novel.title }}"
        return "src_${source.id}_${key.sha256().take(16)}"
    }

    internal fun chapterContentToXhtml(content: ChapterContent): String = when (content) {
        is ChapterContent.Text -> buildChapterXhtml(content.text)
        is ChapterContent.Html -> buildHtmlChapterXhtml(content.html)
        is ChapterContent.Images -> buildImageChapterXhtml(content.urls)
        is ChapterContent.Mixed -> buildMixedChapterXhtml(content.items)
    }

    internal fun buildChapterXhtml(text: String): String = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1.0"/></head>
<body>
${text.escapeXml().replace("\n\n", "</p><p>").let { "<p>$it</p>" }}
</body>
</html>"""

    internal fun buildHtmlChapterXhtml(html: String): String {
        val sanitized = Jsoup.clean(html, SAFELIST)
        return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1.0"/></head>
<body>
$sanitized
</body>
</html>"""
    }

    private fun buildImageChapterXhtml(imageUrls: List<String>): String {
        val images = imageUrls.joinToString("\n") { url ->
            """<div style="text-align:center;margin:0;page-break-after:always;"><img src="${url.escapeUrl()}" style="max-width:100%;height:auto;object-fit:contain;"/></div>"""
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1.0"/></head>
<body>
$images
</body>
</html>"""
    }

    private fun buildMixedChapterXhtml(items: List<ContentItem>): String {
        val body = items.joinToString("\n") { item ->
            when (item) {
                is ContentItem.Text -> item.text.escapeXml()
                is ContentItem.Image -> """<div style="text-align:center;"><img src="${item.url.escapeUrl()}" style="max-width:100%;height:auto;"/></div>"""
                is ContentItem.Html -> Jsoup.clean(item.html, SAFELIST)
                is ContentItem.Images -> item.urls.joinToString("") { url ->
                    """<div style="text-align:center;"><img src="${url.escapeUrl()}" style="max-width:100%;height:auto;"/></div>"""
                }
                is ContentItem.Mixed -> buildMixedChapterXhtml(item.items)
            }
        }
        return """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1.0"/></head>
<body>
$body
</body>
</html>"""
    }

    private fun String.sha256(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    internal const val CONTENT_DIR = "OEBPS"
    private const val MIRRORED_IMAGE_PREFIX = "img_"

    private const val CHAPTER_LIST_FILE = "source_chapters.json"

    @Serializable
    data class StoredChapter(
        val url: String,
        val name: String,
        val number: Float,
    )

    fun readChapterList(bookDir: File): List<StoredChapter>? {
        return try {
            BookStorage.load<List<StoredChapter>>(bookDir, CHAPTER_LIST_FILE)
        } catch (_: Exception) {
            null
        }
    }

    private const val MAX_MIRRORED_IMAGE_BYTES = 8 * 1024 * 1024

    /**
     * Mirrors remote chapter images into [contentDir] for offline reading,
     * rewriting their srcs to local filenames. Anything that fails keeps its
     * remote URL. Operates on full xhtml documents; returns the (possibly
     * re-serialized) document.
     */
    internal suspend fun mirrorChapterImages(xhtml: String, contentDir: java.io.File): String {
        if (!xhtml.contains("<img", ignoreCase = true)) return xhtml
        return try {
            val client = Injekt.get<eu.kanade.tachiyomi.network.NetworkHelper>().client
            val doc = Jsoup.parse(xhtml)
            var changed = false
            for (img in doc.select("img[src]")) {
                val src = img.attr("src").trim()
                if (src.isBlank() || !(src.startsWith("http://") || src.startsWith("https://"))) continue
                try {
                    val fileName = "img_" + src.sha256().take(16) + src.guessImageExtension()
                    val target = java.io.File(contentDir, fileName)
                    if (!target.isFile || target.length() <= 0L) {
                        val request = okhttp3.Request.Builder()
                            .url(src)
                            .header("User-Agent", "Mozilla/5.0")
                            .build()
                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) return@use
                            val bytes = response.body.bytes()
                            if (bytes.isEmpty() || bytes.size > MAX_MIRRORED_IMAGE_BYTES) return@use
                            target.writeBytes(bytes)
                        }
                    }
                    if (target.isFile && target.length() > 0L) {
                        img.attr("src", fileName)
                        changed = true
                    }
                } catch (_: Exception) {
                }
            }
            if (!changed) xhtml else doc.html()
        } catch (_: Exception) {
            xhtml
        }
    }

    private fun String.guessImageExtension(): String {
        val ext = substringBefore("?").substringAfterLast(".", "").lowercase()
        return if (ext.length in 3..4 && ext.all { it.isLetterOrDigit() }) ".$ext" else ".jpg"
    }
}
