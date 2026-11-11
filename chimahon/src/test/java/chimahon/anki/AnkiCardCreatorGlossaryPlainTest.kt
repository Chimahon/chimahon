package chimahon.anki

import android.content.ContextWrapper
import chimahon.GlossaryEntry
import chimahon.LookupResult
import chimahon.TermResult
import chimahon.TransformGroup
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class AnkiCardCreatorGlossaryPlainTest {

    @AfterEach
    fun tearDown() {
        AnkiCardCreator.resetBridgeFactoryForTests()
        AnkiCardCreator.resetFieldMapParserForTests()
    }

    private val jitendexJisshitsuJson = """
    {
      "type": "structured-content",
      "content": [
        {
          "tag": "div",
          "data": { "content": "part-of-speech" },
          "content": "noun"
        },
        {
          "tag": "ul",
          "data": { "content": "glossary" },
          "content": [
            { "tag": "li", "content": "substance" },
            { "tag": "li", "content": "essence" }
          ]
        },
        {
          "tag": "div",
          "data": { "content": "example-sentence" },
          "content": [
            {
              "tag": "ruby",
              "content": [
                "実",
                { "tag": "rt", "content": "じっ" },
                "質",
                { "tag": "rt", "content": "しつ" },
                "成",
                { "tag": "rt", "content": "せい" },
                "長",
                { "tag": "rt", "content": "ちょう" }
              ]
            },
            "と名目成長率の差は物価上昇を意味する。"
          ]
        },
        {
          "tag": "div",
          "data": { "content": "example-sentence-translation" },
          "content": "Price increases explain the difference between the real and nominal growth rates."
        },
        {
          "tag": "div",
          "data": { "content": "part-of-speech" },
          "content": "no-adj"
        },
        {
          "tag": "ul",
          "data": { "content": "glossary" },
          "content": [
            { "tag": "li", "content": "substantive" },
            { "tag": "li", "content": "substantial" },
            { "tag": "li", "content": "essential" },
            { "tag": "li", "content": "real (e.g. interest rate)" }
          ]
        },
        {
          "tag": "div",
          "data": { "content": "part-of-speech" },
          "content": "adverb"
        },
        {
          "tag": "ul",
          "data": { "content": "glossary" },
          "content": [
            { "tag": "li", "content": "in essence" },
            { "tag": "li", "content": "in effect" },
            { "tag": "li", "content": "essentially" },
            { "tag": "li", "content": "effectively" },
            { "tag": "li", "content": "practically" }
          ]
        },
        {
          "tag": "div",
          "data": { "content": "part-of-speech" },
          "content": "noun anatomy"
        },
        {
          "tag": "ul",
          "data": { "content": "glossary" },
          "content": [
            { "tag": "li", "content": "parenchyma" }
          ]
        },
        {
          "tag": "div",
          "data": { "content": "attribution" },
          "content": "JMdict, Tatoeba"
        }
      ]
    }
    """.trimIndent()

    @Test
    fun `jitendex structured content extracts only definitions matching Yomitan parity`() {
        val defs = AnkiCardCreator.extractGlossaryPlainDefinitions(jitendexJisshitsuJson)
        val expected = listOf(
            "substance",
            "essence",
            "substantive",
            "substantial",
            "essential",
            "real (e.g. interest rate)",
            "in essence",
            "in effect",
            "essentially",
            "effectively",
            "practically",
            "parenchyma",
        )
        assertEquals(expected, defs)
    }

    @Test
    fun `glossary-plain-no-dictionary marker outputs definitions separated by br without examples`() = runTest {
        val bridge = TestAnkiBridge()
        AnkiCardCreator.bridgeFactory = { bridge }
        AnkiCardCreator.fieldMapParser = { mapOf("Glossary" to "{glossary-plain-no-dictionary}") }

        val lookup = makeLookupResult(
            glossaries = arrayOf(
                GlossaryEntry(
                    dictName = "Jitendex",
                    glossary = jitendexJisshitsuJson,
                    definitionTags = "n, no-adj, adv",
                    termTags = "",
                )
            )
        )

        val result = addCard(lookup)

        val expectedOutput = listOf(
            "substance",
            "essence",
            "substantive",
            "substantial",
            "essential",
            "real (e.g. interest rate)",
            "in essence",
            "in effect",
            "essentially",
            "effectively",
            "practically",
            "parenchyma",
        ).joinToString("<br>\n")

        assertEquals(AnkiResult.Success(100), result)
        assertEquals(expectedOutput, bridge.lastAddedFields["Glossary"])
    }

    @Test
    fun `glossary-plain marker prepends dictionary name header`() = runTest {
        val bridge = TestAnkiBridge()
        AnkiCardCreator.bridgeFactory = { bridge }
        AnkiCardCreator.fieldMapParser = { mapOf("Glossary" to "{glossary-plain}") }

        val lookup = makeLookupResult(
            glossaries = arrayOf(
                GlossaryEntry(
                    dictName = "Jitendex",
                    glossary = jitendexJisshitsuJson,
                    definitionTags = "",
                    termTags = "",
                )
            )
        )

        addCard(lookup)

        val expectedDefs = listOf(
            "substance",
            "essence",
            "substantive",
            "substantial",
            "essential",
            "real (e.g. interest rate)",
            "in essence",
            "in effect",
            "essentially",
            "effectively",
            "practically",
            "parenchyma",
        ).joinToString("<br>\n")

        val expectedOutput = "(Jitendex)<br>\n$expectedDefs"
        assertEquals(expectedOutput, bridge.lastAddedFields["Glossary"])
    }

    @Test
    fun `json string array glossary parses cleanly`() {
        val json = """["substance", "essence", "<te-form>"]"""
        val defs = AnkiCardCreator.extractGlossaryPlainDefinitions(json)
        assertEquals(listOf("substance", "essence", "&lt;te-form&gt;"), defs)
    }

    @Test
    fun `plain text glossary normalizes newlines and escapes html`() {
        val text = "substance\nessence\n<markup>"
        val defs = AnkiCardCreator.extractGlossaryPlainDefinitions(text)
        assertEquals(listOf("substance", "essence", "&lt;markup&gt;"), defs)
    }

    @Test
    fun `fallback structured content without data content glossary skips example and attribution`() {
        val json = """
        {
          "type": "structured-content",
          "content": [
            { "tag": "p", "content": "Primary definition <test>" },
            { "tag": "div", "data": { "content": "example-sentence" }, "content": "Should be ignored" },
            { "tag": "div", "data": { "content": "attribution" }, "content": "Attribution text" },
            { "tag": "ul", "content": [
              { "tag": "li", "content": "Secondary definition" }
            ]}
          ]
        }
        """.trimIndent()

        val defs = AnkiCardCreator.extractGlossaryPlainDefinitions(json)
        assertEquals(listOf("Primary definition &lt;test&gt;", "Secondary definition"), defs)
    }

    @Test
    fun `out of bounds glossaryIndex returns empty string instead of crashing or returning first dictionary`() = runTest {
        val bridge = TestAnkiBridge()
        AnkiCardCreator.bridgeFactory = { bridge }
        AnkiCardCreator.fieldMapParser = { mapOf("Glossary" to "{glossary-plain-no-dictionary}") }

        val lookup = makeLookupResult(
            glossaries = arrayOf(
                GlossaryEntry("Dict1", "def1", "", ""),
                GlossaryEntry("Dict2", "def2", "", ""),
            )
        )

        addCard(lookup, glossaryIndex = 99)

        assertEquals("", bridge.lastAddedFields["Glossary"])
    }

    @Test
    fun `empty glossaries array returns empty string safely`() = runTest {
        val bridge = TestAnkiBridge()
        AnkiCardCreator.bridgeFactory = { bridge }
        AnkiCardCreator.fieldMapParser = { mapOf("Glossary" to "{glossary-plain}") }

        val lookup = makeLookupResult(glossaries = emptyArray())

        addCard(lookup)

        assertEquals("", bridge.lastAddedFields["Glossary"])
    }

    @Test
    fun `multiple dictionaries separate with double br and respect noDictTag`() = runTest {
        val bridge = TestAnkiBridge()
        AnkiCardCreator.bridgeFactory = { bridge }
        AnkiCardCreator.fieldMapParser = {
            mapOf(
                "WithDict" to "{glossary-plain}",
                "NoDict" to "{glossary-plain-no-dictionary}",
            )
        }

        val lookup = makeLookupResult(
            glossaries = arrayOf(
                GlossaryEntry("Dict A", """["def 1", "def 2"]""", "", ""),
                GlossaryEntry("Dict B", """["def 3"]""", "", ""),
            )
        )

        addCard(lookup)

        assertEquals("(Dict A)<br>\ndef 1<br>\ndef 2<br>\n<br>\n(Dict B)<br>\ndef 3", bridge.lastAddedFields["WithDict"])
        assertEquals("def 1<br>\ndef 2<br>\n<br>\ndef 3", bridge.lastAddedFields["NoDict"])
    }

    @Test
    fun `selectedDict filters dictionaries for glossary-plain`() = runTest {
        val bridge = TestAnkiBridge()
        AnkiCardCreator.bridgeFactory = { bridge }
        AnkiCardCreator.fieldMapParser = { mapOf("Glossary" to "{glossary-plain-no-dictionary}") }

        val lookup = makeLookupResult(
            glossaries = arrayOf(
                GlossaryEntry("JMdict", "jmdict def", "", ""),
                GlossaryEntry("Jitendex", "jitendex def", "", ""),
            )
        )

        addCard(lookup, selectedDict = "Jitendex")

        assertEquals("jitendex def", bridge.lastAddedFields["Glossary"])
    }

    private suspend fun addCard(
        result: LookupResult,
        glossaryIndex: Int? = null,
        selectedDict: String? = null,
    ): AnkiResult = AnkiCardCreator.addToAnki(
        context = TestContext,
        result = result,
        deck = "Default",
        model = "Basic",
        fieldMapJson = "{}",
        tags = "",
        dupCheck = false,
        dupScope = "collection",
        dupAction = "prevent",
        glossaryIndex = glossaryIndex,
        selectedDict = selectedDict,
    )

    private fun makeLookupResult(glossaries: Array<GlossaryEntry>): LookupResult = LookupResult(
        matched = "実質",
        deinflected = "実質",
        process = emptyArray<TransformGroup>(),
        term = TermResult(
            expression = "実質",
            reading = "じっしつ",
            rules = "",
            glossaries = glossaries,
            frequencies = emptyArray(),
            pitches = emptyArray(),
        ),
        preprocessorSteps = 0,
    )

    private class TestAnkiBridge : AnkiCardBridge {
        var lastAddedFields = emptyMap<String, String>()

        override fun hasPermission(): Boolean = true
        override suspend fun ensureDefaultDeckName(): String = "Default"
        override suspend fun ensureLapisModelName(): String = "Basic"
        override suspend fun getDeckId(deckName: String): Long = 1L
        override suspend fun findNotes(expression: String, modelName: String?, deckId: Long?): List<Long> = emptyList()
        override suspend fun storeMedia(filename: String, data: ByteArray): String = filename
        override suspend fun storeMedia(source: AnkiSentenceAudioSource): String = "audio.m4a"
        override suspend fun addNote(deckName: String, modelName: String, fields: Map<String, String>, tags: List<String>): Long {
            lastAddedFields = fields
            return 100L
        }
        override suspend fun updateNoteFields(noteId: Long, fields: Map<String, String>) {
            lastAddedFields = fields
        }
        override fun triggerSync() = Unit
    }

    private object TestContext : ContextWrapper(null) {
        private val files = Files.createTempDirectory("anki-card-creator-test").toFile()
        override fun getFilesDir(): File = files
    }
}
