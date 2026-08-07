package eu.kanade.tachiyomi.ui.dictionary.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlossaryJsonParserTest {

    @Test
    fun `parses structured-content with sense group and tags`() {
        val json = """
            [{"type":"structured-content","content":[
              {"tag":"div","data":{"content":"sense-group"},"content":[
                {"tag":"span","title":"noun","data":{"class":"tag","code":"n","content":"part-of-speech-info"},"content":"noun"},
                {"tag":"div","data":{"content":"sense"},"content":[
                  {"tag":"ul","data":{"content":"glossary"},"content":{"tag":"li","content":"ditto mark"}}
                ]}
              ]}
            ]}]
        """.trimIndent()

        val nodes = parse(json)
        assertEquals(1, nodes.size)
        val group = nodes[0] as StructuredNode.Element
        assertEquals(StructuredTag.Div, group.tag)
        assertEquals("sense-group", group.attributes.data["content"])

        val tag = group.children[0] as StructuredNode.Element
        assertEquals(StructuredTag.Span, tag.tag)
        assertEquals("part-of-speech-info", tag.attributes.data["content"])
        assertEquals("noun", tag.collect())

        val sense = group.children[1] as StructuredNode.Element
        val ul = sense.children[0] as StructuredNode.Element
        val li = ul.children[0] as StructuredNode.Element
        assertEquals(StructuredTag.ListItem, li.tag)
        assertEquals("ditto mark", li.collect())
    }

    @Test
    fun `preserves link href and ruby structure`() {
        val json = """
            [{"tag":"a","href":"?query=%E4%B8%80&wildcards=off","content":[
              {"tag":"ruby","content":["一",{"tag":"rt","content":"いち"}]}
            ]}]
        """.trimIndent()

        val link = parse(json).single() as StructuredNode.Element
        assertEquals(StructuredTag.Link, link.tag)
        assertEquals("?query=%E4%B8%80&wildcards=off", link.attributes.properties["href"])

        val ruby = link.children.single() as StructuredNode.Element
        assertEquals(StructuredTag.Ruby, ruby.tag)
        assertEquals("一", ruby.children[0].collect())
        val rt = ruby.children[1] as StructuredNode.Element
        assertEquals(StructuredTag.Rt, rt.tag)
        assertEquals("いち", rt.children.single().collect())
    }

    @Test
    fun `preserves details open and table rows`() {
        val json = """
            [{"tag":"details","open":true,"content":[
              {"tag":"summary","content":"Forms"},
              {"tag":"table","content":[
                {"tag":"tr","content":[
                  {"tag":"th","content":""},
                  {"tag":"th","content":"一"}
                ]},
                {"tag":"tr","content":[
                  {"tag":"th","content":"口"},
                  {"tag":"td","data":{"class":"form-valid"},"content":{"tag":"span","content":"◇"}}
                ]}
              ]}
            ]}]
        """.trimIndent()

        val details = parse(json).single() as StructuredNode.Element
        assertEquals(StructuredTag.Details, details.tag)
        assertEquals("true", details.attributes.properties["open"])

        val summary = details.children[0] as StructuredNode.Element
        assertEquals(StructuredTag.Summary, summary.tag)
        val table = details.children[1] as StructuredNode.Element
        assertEquals(StructuredTag.Table, table.tag)
        val rows = table.children.filterIsInstance<StructuredNode.Element>().filter { it.tag == StructuredTag.Tr }
        assertEquals(2, rows.size)
    }

    @Test
    fun `plain text returns PlainText entry`() {
        assertTrue(parseStructuredGlossary("a simple gloss") is StructuredEntry.PlainText)
        assertTrue(parseStructuredGlossary("") is StructuredEntry.PlainText)
    }

    @Test
    fun `string array becomes multiple text nodes`() {
        val nodes = parse("""["first", "second"]""")
        assertEquals(2, nodes.size)
        assertTrue(nodes[0] is StructuredNode.Text)
    }

    @Test
    fun `image node captures path`() {
        val nodes = parse("""[{"type":"image","path":"media/a.png","title":"pic"}]""")
        val img = nodes.single() as StructuredNode.Element
        assertEquals(StructuredTag.Image, img.tag)
        assertEquals("media/a.png", img.attributes.properties["path"])
    }

    @Test
    fun `pixiv tag img form captures path and sizing properties`() {
        val json = """
            [{"tag":"div","content":"Pixiv条目","style":{"fontWeight":"bold","fontSize":"1.3em","color":"#e5007f"},"data":{"pixiv":"series"}},
             {"tag":"ul","content":[{"tag":"li","content":"概要"}],"data":{"pixiv":"summary"}},
             {"tag":"div","data":{"pixiv":"footer"},"content":[{"tag":"span","content":[
               {"tag":"img","path":"assets/pixiv-logo.png","alt":"pixiv","collapsed":false,"collapsible":false,"height":1,"width":1,"sizeUnits":"em","verticalAlign":"middle"},
               " ",
               {"tag":"a","href":"https://dic.pixiv.net/a/条目","content":"pixiv大百科"}
             ]}]}]
        """.trimIndent()

        val nodes = parse(json)
        val header = nodes[0] as StructuredNode.Element
        assertEquals(StructuredTag.Div, header.tag)
        assertEquals("bold", header.attributes.style["fontWeight"])
        assertEquals("#e5007f", header.attributes.style["color"])
        assertEquals("series", header.attributes.data["pixiv"])

        val ul = nodes[1] as StructuredNode.Element
        assertEquals(StructuredTag.UnorderedList, ul.tag)
        assertEquals("summary", ul.attributes.data["pixiv"])

        val footer = nodes[2] as StructuredNode.Element
        val span = footer.children[0] as StructuredNode.Element
        val img = span.children[0] as StructuredNode.Element
        assertEquals(StructuredTag.Image, img.tag)
        assertEquals("assets/pixiv-logo.png", img.attributes.properties["path"])
        assertEquals("em", img.attributes.properties["sizeUnits"])
        assertEquals("1", img.attributes.properties["width"])
        assertEquals("middle", img.attributes.properties["verticalAlign"])

        val link = span.children[2] as StructuredNode.Element
        assertEquals(StructuredTag.Link, link.tag)
        assertEquals("https://dic.pixiv.net/a/条目", link.attributes.properties["href"])
        assertEquals("pixiv大百科", link.collect())
    }

    private fun parse(json: String): List<StructuredNode> =
        (parseStructuredGlossary(json) as StructuredEntry.Tree).nodes

    private fun StructuredNode.collect(): String = when (this) {
        is StructuredNode.Text -> text
        is StructuredNode.Element -> children.joinToString("") { it.collect() }
        StructuredNode.TextBreak -> "\n"
    }
}