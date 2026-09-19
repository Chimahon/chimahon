package chimahon.novel.ui.reader

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class ReaderResourceSanitizerTest {

    private val koboHead = """
        <head>
        <meta charset="utf-8"/>
        <title>t</title>
        <link href="../Styles/book-style.css" type="text/css" rel="stylesheet"/>

        <!-- kobo-style -->
        <script xmlns="http://www.w3.org/1999/xhtml" type="text/javascript" src="../Misc/kobo.js"/>
        <style xmlns="http://www.w3.org/1999/xhtml" type="text/css" id="koboSpanStyle">.koboSpan { -webkit-text-combine: inherit; }</style>

        </head>
    """.trimIndent()

    @Test
    fun `self-closing script tag is removed`() {
        val html = "<html>$koboHead<body><p>text</p></body></html>"
        val result = sanitizeReaderHtml(html)

        result shouldNotContain "<script"
        result shouldContain "<style"
        result shouldContain "<p>text</p>"
    }

    @Test
    fun `classic script block is removed`() {
        val html = "<head><script type=\"text/javascript\">alert(1);</script></head><body><p>x</p></body>"

        sanitizeReaderHtml(html) shouldBe "<head></head><body><p>x</p></body>"
    }

    @Test
    fun `uppercase script tags are removed`() {
        val html = "<HEAD><SCRIPT SRC=\"a.js\"/></HEAD><BODY><P>x</P></BODY>"

        sanitizeReaderHtml(html) shouldBe "<HEAD></HEAD><BODY><P>x</P></BODY>"
    }

    @Test
    fun `html without scripts is untouched`() {
        val html = "<html><head><link href=\"a.css\"/></head><body><p>日本語テキスト</p></body></html>"

        sanitizeReaderHtml(html) shouldBe html
    }

    @Test
    fun `kobo span content survives`() {
        val html = "<body><p><span class=\"koboSpan\" id=\"kobo.1.1\">本文</span></p></body>"

        sanitizeReaderHtml(html) shouldBe html
    }
}
