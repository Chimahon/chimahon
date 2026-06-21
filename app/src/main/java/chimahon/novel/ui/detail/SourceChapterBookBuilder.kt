package chimahon.novel.ui.detail

import android.content.Context
import com.canopus.chimareader.data.Bookmark
import com.canopus.chimareader.data.BookMetadata
import com.canopus.chimareader.data.BookStorage
import com.canopus.chimareader.data.FileNames
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.ContentItem
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

object SourceChapterBookBuilder {

    private const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L // 24 hours
    private const val CACHE_MAX_ENTRIES = 50

    private val SAFELIST = Safelist.relaxed()
        .addTags("ruby", "rt", "rp", "sup", "sub")
        .addAttributes("img", "src", "alt", "width", "height", "style")
        .addAttributes("a", "href", "title", "rel")
        .addAttributes(":all", "style", "class", "id", "lang", "dir", "title")
        .addProtocols("img", "src", "http", "https", "data")
        .addProtocols("a", "href", "http", "https", "mailto")

    fun cleanUpOldCache(context: Context) {
        val cacheDir = BookStorage.getBooksDirectory(context)
        if (!cacheDir.exists()) return
        val entries = cacheDir.listFiles()?.filter { it.name.startsWith("src_") }?.toList() ?: return
        if (entries.size <= CACHE_MAX_ENTRIES) return
        val toDelete = entries
            .sortedBy { it.lastModified() }
            .take(entries.size - CACHE_MAX_ENTRIES)
        for (dir in toDelete) {
            dir.deleteRecursively()
        }
    }

    fun cleanUpExpiredEntries(context: Context) {
        val cacheDir = BookStorage.getBooksDirectory(context)
        if (!cacheDir.exists()) return
        val now = System.currentTimeMillis()
        cacheDir.listFiles()?.filter { it.name.startsWith("src_") }?.forEach { dir ->
            if (dir.isDirectory && now - dir.lastModified() > CACHE_MAX_AGE_MS) {
                dir.deleteRecursively()
            }
        }
    }

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

    suspend fun buildSingleChapter(
        context: Context,
        source: NovelSource,
        novel: SNNovel,
        chapter: SNChapter,
    ): File {
        val bookId = bookId(source, novel)
        val bookDir = prepareTempBookDirectory(context, bookId)
        val contentDir = File(bookDir, CONTENT_DIR).apply { mkdirs() }

        val content = source.getChapterContent(chapter)
        val xhtmlContent = chapterContentToXhtml(content)
        val generatedChapter = GeneratedChapter(
            id = "chapter_0",
            href = "chapter_0.xhtml",
            title = chapter.name,
        )

        File(contentDir, generatedChapter.href).writeText(xhtmlContent, Charsets.UTF_8)
        writeEpubPackage(bookDir, source, novel, listOf(generatedChapter))

        saveMetadata(bookDir, bookId, novel, source)
        return commitBookDirectory(context, bookId, bookDir)
    }

    suspend fun build(
        context: Context,
        source: NovelSource,
        novel: SNNovel,
        chapters: List<SNChapter>,
        startChapterIndex: Int = 0,
    ): File {
        val bookId = bookId(source, novel)
        val bookDir = prepareTempBookDirectory(context, bookId)
        val contentDir = File(bookDir, CONTENT_DIR).apply { mkdirs() }

        cleanUpOldCache(context)

        val semaphore = Semaphore(4)
        val fetchOrder = chapters.indices.sortedWith(
            compareBy<Int> { abs(it - startChapterIndex) }.thenBy { it },
        )

        val generatedByIndex = coroutineScope {
            val deferred = fetchOrder.map { index ->
                val chapter = chapters[index]
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        val id = "chapter_$index"
                        val href = "$id.xhtml"
                        val result = runCatching {
                            val content = source.getChapterContent(chapter)
                            chapterContentToXhtml(content)
                        }
                        index to result.fold(
                            onSuccess = { xhtml ->
                                File(contentDir, href).writeText(xhtml, Charsets.UTF_8)
                                GeneratedChapter(id = id, href = href, title = chapter.name)
                            },
                            onFailure = { error ->
                                if (index == startChapterIndex) throw error
                                File(contentDir, href).writeText(buildErrorChapterXhtml(chapter, error), Charsets.UTF_8)
                                GeneratedChapter(id = id, href = href, title = chapter.name)
                            },
                        )
                    } finally {
                        semaphore.release()
                    }
                }
            }
            deferred.awaitAll().toMap()
        }
        val generatedChapters = chapters.indices.mapNotNull { generatedByIndex[it] }

        writeEpubPackage(bookDir, source, novel, generatedChapters)
        saveMetadata(bookDir, bookId, novel, source)

        if (startChapterIndex in chapters.indices) {
            BookStorage.save(
                Bookmark(
                    chapterIndex = startChapterIndex,
                    progress = 0.0,
                    characterCount = 0,
                    lastModified = System.currentTimeMillis(),
                ),
                bookDir,
                FileNames.bookmark,
            )
        }

        return commitBookDirectory(context, bookId, bookDir)
    }

    private fun prepareTempBookDirectory(context: Context, bookId: String): File {
        val booksDir = BookStorage.getBooksDirectory(context).apply { mkdirs() }
        val tempDir = File(booksDir, "$bookId.tmp")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()
        return tempDir
    }

    private fun commitBookDirectory(context: Context, bookId: String, tempDir: File): File {
        val bookDir = BookStorage.getBookDirectory(context, bookId)
        val backupDir = File(bookDir.parentFile, "$bookId.bak")
        if (backupDir.exists()) backupDir.deleteRecursively()

        if (bookDir.exists() && !bookDir.renameTo(backupDir)) {
            bookDir.deleteRecursively()
        }

        return try {
            if (!tempDir.renameTo(bookDir)) {
                tempDir.copyRecursively(bookDir, overwrite = true)
                tempDir.deleteRecursively()
            }
            backupDir.deleteRecursively()
            bookDir
        } catch (e: Throwable) {
            bookDir.deleteRecursively()
            if (backupDir.exists()) {
                backupDir.renameTo(bookDir)
            }
            throw e
        }
    }

    private fun writeEpubPackage(
        bookDir: File,
        source: NovelSource,
        novel: SNNovel,
        chapters: List<GeneratedChapter>,
    ) {
        File(bookDir, "mimetype").writeText("application/epub+zip", Charsets.UTF_8)
        File(bookDir, "META-INF").apply { mkdirs() }
        File(bookDir, "META-INF/container.xml").writeText(buildContainerXml(), Charsets.UTF_8)
        val contentDir = File(bookDir, CONTENT_DIR).apply { mkdirs() }
        File(contentDir, "nav.xhtml").writeText(buildNavXhtml(chapters), Charsets.UTF_8)
        File(contentDir, "content.opf").writeText(buildOpf(source, novel, chapters), Charsets.UTF_8)
    }

    private fun saveMetadata(
        bookDir: File,
        bookId: String,
        novel: SNNovel,
        source: NovelSource,
    ) {
        BookStorage.save(
            BookMetadata(
                id = bookId,
                title = novel.title,
                author = novel.author,
                cover = novel.thumbnail_url,
                folder = bookId,
                hash = bookId,
                lang = source.lang.takeIf { it.isNotBlank() },
            ),
            bookDir,
            FileNames.metadata,
        )
    }

    private fun buildContainerXml() = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="$CONTENT_DIR/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    private fun buildOpf(
        source: NovelSource,
        novel: SNNovel,
        chapters: List<GeneratedChapter>,
    ): String {
        val language = source.lang.takeIf { it.isNotBlank() } ?: "und"
        val chapterManifest = chapters.joinToString("\n") {
            """    <item id="${it.id}" href="${it.href.escapeXml()}" media-type="application/xhtml+xml"/>"""
        }
        val spine = chapters.joinToString("\n") {
            """    <itemref idref="${it.id}"/>"""
        }
        val author = novel.author?.takeIf { it.isNotBlank() }?.let {
            "\n    <dc:creator>${it.escapeXml()}</dc:creator>"
        }.orEmpty()
        val description = novel.description?.takeIf { it.isNotBlank() }?.let {
            "\n    <dc:description>${it.escapeXml()}</dc:description>"
        }.orEmpty()

        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="book-id">urn:chimahon:${source.id}:${novel.url.escapeXml()}</dc:identifier>
    <dc:title>${novel.title.escapeXml()}</dc:title>$author
    <dc:language>${language.escapeXml()}</dc:language>$description
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
$chapterManifest
  </manifest>
  <spine>
$spine
  </spine>
</package>"""
    }

    private fun buildNavXhtml(chapters: List<GeneratedChapter>): String {
        val entries = chapters.joinToString("\n") {
            """      <li><a href="${it.href.escapeXml()}">${it.title.escapeXml()}</a></li>"""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Table of Contents</title></head>
<body>
  <nav epub:type="toc" id="toc">
    <ol>
$entries
    </ol>
  </nav>
</body>
</html>"""
    }

    private fun chapterContentToXhtml(content: ChapterContent): String = when (content) {
        is ChapterContent.Text -> buildChapterXhtml(content.fullText())
        is ChapterContent.Html -> buildHtmlChapterXhtml(content.html)
        is ChapterContent.Images -> buildImageChapterXhtml(content.urls)
        is ChapterContent.Mixed -> buildMixedChapterXhtml(content.items)
    }

    private fun buildChapterXhtml(text: String): String = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><meta charset="utf-8"/><meta name="viewport" content="width=device-width,initial-scale=1.0"/></head>
<body>
${text.escapeXml().replace("\n\n", "</p><p>").let { "<p>$it</p>" }}
</body>
</html>"""

    private fun buildHtmlChapterXhtml(html: String): String {
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

    private fun buildErrorChapterXhtml(chapter: SNChapter, error: Throwable): String {
        val message = error.message ?: "Could not fetch this chapter."
        return buildChapterXhtml("${chapter.name}\n\n$message")
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

    private data class GeneratedChapter(
        val id: String,
        val href: String,
        val title: String,
    )

    private const val CONTENT_DIR = "OEBPS"
}
