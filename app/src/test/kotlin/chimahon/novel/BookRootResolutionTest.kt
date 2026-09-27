package chimahon.novel

import chimahon.novel.data.BookStorage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BookRootResolutionTest {

    @TempDir
    lateinit var tempDir: File

    private fun dir(name: String): File = File(tempDir, name).apply { mkdirs() }

    private fun extractedBook(name: String): File = dir(name).apply {
        File(this, "OEBPS").mkdirs()
        File(this, "OEBPS/content.opf").writeText("<package/>")
    }

    private fun epubOnlyBook(name: String): File = dir(name).apply {
        File(this, "$name.epub").writeText("not really a zip")
    }

    @Test
    fun `extracted cache wins over an epub-only public folder`() {
        val public = epubOnlyBook("public")
        val cache = extractedBook("cache")

        BookStorage.firstReadableDir(public, cache) shouldBe cache
    }

    @Test
    fun `a readable public folder wins over the cache`() {
        val public = extractedBook("public")
        val cache = extractedBook("cache")

        BookStorage.firstReadableDir(public, cache) shouldBe public
    }

    @Test
    fun `an epub-only folder is never readable`() {
        BookStorage.firstReadableDir(epubOnlyBook("public"), dir("empty-cache")) shouldBe null
    }

    @Test
    fun `an epub alone is not importable content`() {
        BookStorage.hasImportedBookContent(epubOnlyBook("public")) shouldBe false
    }

    @Test
    fun `a loose ncx counts as importable content`() {
        val book = dir("book")
        File(book, "toc.ncx").writeText("<ncx/>")

        BookStorage.hasImportedBookContent(book) shouldBe true
    }
}
