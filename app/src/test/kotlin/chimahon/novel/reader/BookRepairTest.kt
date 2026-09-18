package chimahon.novel.reader

import chimahon.novel.data.epub.EpubBook
import chimahon.novel.data.epub.EpubSpine
import chimahon.novel.data.epub.SpineItem
import chimahon.novel.data.epub.SpineItemType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class BookRepairTest {

    private fun book(vararg types: SpineItemType): EpubBook {
        return EpubBook(
            spine = EpubSpine(
                items = types.mapIndexed { index, type ->
                    SpineItem(idref = "ch$index", type = type)
                },
            ),
        )
    }

    private fun textBook(count: Int): EpubBook {
        return book(*Array(count) { SpineItemType.TEXT })
    }

    @Test
    fun `healthy book with text chapters is not damaged`() {
        val document = textBook(2)
        var calls = 0
        val damaged = assessTextDamage(document) { _, _ ->
            calls++
            "<html><body><p>content $calls</p></body></html>"
        }

        damaged shouldBe false
    }

    @Test
    fun `all blank chapters means damaged`() {
        val document = textBook(3)
        val damaged = assessTextDamage(document) { _, _ -> "" }

        damaged shouldBe true
    }

    @Test
    fun `missing chapter files count as blank`() {
        val document = textBook(3)
        val damaged = assessTextDamage(document) { _, _ -> null }

        damaged shouldBe true
    }

    @Test
    fun `single text chapter among blanks is healthy`() {
        val document = textBook(3)
        val chapters = listOf("", "<html><body><p>Real content</p></body></html>", "")
        val damaged = assessTextDamage(document) { _, index -> chapters[index] }

        damaged shouldBe false
    }

    @Test
    fun `divider pages with symbols are not blank`() {
        val document = textBook(1)
        val damaged = assessTextDamage(document) { _, _ ->
            "<html><body><p>❖ ❖ ❖</p></body></html>"
        }

        damaged shouldBe false
    }

    @Test
    fun `empty xhtml bodies count as blank`() {
        val document = textBook(2)
        val damaged = assessTextDamage(document) { _, _ ->
            "<html><body></body></html>"
        }

        damaged shouldBe true
    }

    @Test
    fun `xhtml image pages are not blank`() {
        val document = textBook(1)
        val damaged = assessTextDamage(document) { _, _ ->
            "<html><body><div><img src=\"image.jpg\"/></div></body></html>"
        }

        damaged shouldBe false
    }

    @Test
    fun `existing image-only pages are skipped`(@TempDir tempDir: File) {
        val image = File(tempDir, "insert.jpg")
        image.writeBytes(byteArrayOf(1, 2, 3))
        val document = EpubBook(
            spine = EpubSpine(
                items = listOf(
                    SpineItem(idref = "cover", type = SpineItemType.IMAGE_ONLY, imageUrl = image.toURI().toString()),
                    SpineItem(idref = "ch1"),
                ),
            ),
        )
        val damaged = assessTextDamage(document) { _, _ ->
            "<html><body><p>Text</p></body></html>"
        }

        damaged shouldBe false
    }

    @Test
    fun `empty spine is not damaged`() {
        val damaged = assessTextDamage(EpubBook()) { _, _ -> null }

        damaged shouldBe false
    }

    @Test
    fun `sampling stops at max samples`() {
        val document = textBook(7)
        var calls = 0
        assessTextDamage(document, maxSamples = 3) { _, _ ->
            calls++
            ""
        }

        calls shouldBe 3
    }
}
