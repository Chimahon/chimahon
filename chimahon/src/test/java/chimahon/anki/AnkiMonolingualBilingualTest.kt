package chimahon.anki

import android.content.ContextWrapper
import chimahon.GlossaryEntry
import chimahon.LookupResult
import chimahon.TermResult
import chimahon.TransformGroup
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class AnkiMonolingualBilingualTest {

    @AfterEach
    fun tearDown() {
        AnkiCardCreator.resetBridgeFactoryForTests()
        AnkiCardCreator.resetFieldMapParserForTests()
    }

    @Test
    fun `profile resolves dictionary categories and defaults uncategorized entries to none`() {
        val emptyProfile = AnkiProfile(id = "p1", name = "Default")
        assertEquals(emptyMap<String, String>(), emptyProfile.dictionaryCategories)
        assertEquals(DictionaryCategory.NONE, emptyProfile.dictionaryCategory("JMdict"))
        assertEquals(emptyMap<String, DictionaryCategory>(), emptyProfile.dictionaryCategoryMap())

        val profile = AnkiProfile(
            id = "p2",
            name = "JA",
            dictionaryCategories = mapOf(
                "大辞林" to DictionaryCategory.MONOLINGUAL.storageValue,
                "JMdict" to DictionaryCategory.BILINGUAL.storageValue,
                "JMnedict" to DictionaryCategory.EXCLUDE.storageValue,
                "Other" to DictionaryCategory.NONE.storageValue,
            ),
        )
        assertEquals(DictionaryCategory.MONOLINGUAL, profile.dictionaryCategory("大辞林"))
        assertEquals(DictionaryCategory.BILINGUAL, profile.dictionaryCategory("JMdict"))
        assertEquals(DictionaryCategory.EXCLUDE, profile.dictionaryCategory("JMnedict"))
        assertEquals(DictionaryCategory.NONE, profile.dictionaryCategory("Other"))
        assertEquals(DictionaryCategory.NONE, DictionaryCategory.fromStorageValue("unknown"))
        assertEquals(
            mapOf(
                "大辞林" to DictionaryCategory.MONOLINGUAL,
                "JMdict" to DictionaryCategory.BILINGUAL,
                "JMnedict" to DictionaryCategory.EXCLUDE,
                "Other" to DictionaryCategory.NONE,
            ),
            profile.dictionaryCategoryMap(),
        )
    }

    @Test
    fun `autoDetect recognizes monolingual and bilingual field names`() {
        assertEquals(Marker.MONOLINGUAL_DEFINITION, Marker.autoDetect("Monolingual", 1))
        assertEquals(Marker.MONOLINGUAL_DEFINITION, Marker.autoDetect("MonolingualDefinition", 1))
        assertEquals(Marker.BILINGUAL_DEFINITION, Marker.autoDetect("Bilingual", 1))
        assertEquals(Marker.BILINGUAL_DEFINITION, Marker.autoDetect("BilingualDefinition", 1))
    }

    @Test
    fun `monolingual and bilingual markers respect dictionary order categories and variants`() {
        val result = lookupResult(
            GlossaryEntry(dictName = "JMdict", glossary = "translation", definitionTags = "", termTags = ""),
            GlossaryEntry(dictName = "Excluded", glossary = "excluded", definitionTags = "", termTags = ""),
            GlossaryEntry(dictName = "General", glossary = "general", definitionTags = "", termTags = ""),
            GlossaryEntry(dictName = "国語辞典", glossary = "単語の意味", definitionTags = "n", termTags = ""),
        )
        val order = listOf("Excluded", "General", "国語辞典 [2026-08-23]", "JMdict [2026-08-23]")
        val categories = mapOf(
            "Excluded" to DictionaryCategory.EXCLUDE,
            "General" to DictionaryCategory.NONE,
            "国語辞典 [2026-08-23]" to DictionaryCategory.MONOLINGUAL,
            "JMdict [2026-08-23]" to DictionaryCategory.BILINGUAL,
        )

        val mono = AnkiCardCreator.renderTemplateForTests(
            template = "{${Marker.MONOLINGUAL_DEFINITION}}",
            result = result,
            dictionaryOrder = order,
            dictionaryCategories = categories,
        )
        val monoBrief = AnkiCardCreator.renderTemplateForTests(
            template = "{${Marker.MONOLINGUAL_DEFINITION_BRIEF}}",
            result = result,
            dictionaryOrder = order,
            dictionaryCategories = categories,
        )
        val monoNoDict = AnkiCardCreator.renderTemplateForTests(
            template = "{${Marker.MONOLINGUAL_DEFINITION_NO_DICT}}",
            result = result,
            dictionaryOrder = order,
            dictionaryCategories = categories,
        )
        val bi = AnkiCardCreator.renderTemplateForTests(
            template = "{${Marker.BILINGUAL_DEFINITION}}",
            result = result,
            dictionaryOrder = order,
            dictionaryCategories = categories,
        )
        val biBrief = AnkiCardCreator.renderTemplateForTests(
            template = "{${Marker.BILINGUAL_DEFINITION_BRIEF}}",
            result = result,
            dictionaryOrder = order,
            dictionaryCategories = categories,
        )
        val biNoDict = AnkiCardCreator.renderTemplateForTests(
            template = "{${Marker.BILINGUAL_DEFINITION_NO_DICT}}",
            result = result,
            dictionaryOrder = order,
            dictionaryCategories = categories,
        )
        val glossaryFirst = AnkiCardCreator.renderTemplateForTests(
            template = "{${Marker.GLOSSARY_FIRST}}",
            result = result,
            dictionaryOrder = order,
            dictionaryCategories = categories,
        )

        assertTrue(mono.contains("<i>(n, 国語辞典)</i> 単語の意味"))
        assertTrue(!monoBrief.contains("<i>(") && monoBrief.contains("単語の意味"))
        assertTrue(monoNoDict.contains("<i>(n)</i> 単語の意味"))

        assertTrue(bi.contains("<i>(JMdict)</i> translation"))
        assertTrue(!biBrief.contains("<i>(") && biBrief.contains("translation"))
        assertTrue(!biNoDict.contains("<i>(") && biNoDict.contains("translation"))

        assertTrue(glossaryFirst.contains("<i>(General)</i> general"))
        assertTrue(!glossaryFirst.contains("excluded"))
    }

    @Test
    fun `category fallback only falls back to the opposite category and skips none or excluded`() {
        val resultOnlyBilingualAndGeneral = lookupResult(
            GlossaryEntry(dictName = "General", glossary = "general", definitionTags = "", termTags = ""),
            GlossaryEntry(dictName = "Excluded", glossary = "excluded", definitionTags = "", termTags = ""),
            GlossaryEntry(dictName = "JMdict", glossary = "translation", definitionTags = "", termTags = ""),
        )
        val categories = mapOf(
            "General" to DictionaryCategory.NONE,
            "Excluded" to DictionaryCategory.EXCLUDE,
            "国語辞典" to DictionaryCategory.MONOLINGUAL,
            "JMdict" to DictionaryCategory.BILINGUAL,
        )

        val monoStrict = AnkiCardCreator.renderTemplateForTests(
            template = "{${Marker.MONOLINGUAL_DEFINITION}}",
            result = resultOnlyBilingualAndGeneral,
            dictionaryCategories = categories,
        )
        val monoFallback = AnkiCardCreator.renderTemplateForTests(
            template = "{${Marker.MONOLINGUAL_DEFINITION_FALLBACK}}",
            result = resultOnlyBilingualAndGeneral,
            dictionaryCategories = categories,
        )
        assertEquals("", monoStrict)
        assertTrue(monoFallback.contains("translation"))

        val resultOnlyGeneral = lookupResult(
            GlossaryEntry(dictName = "General", glossary = "general", definitionTags = "", termTags = ""),
        )
        val biFallback = AnkiCardCreator.renderTemplateForTests(
            template = "{${Marker.BILINGUAL_DEFINITION_FALLBACK}}",
            result = resultOnlyGeneral,
            dictionaryCategories = categories,
        )
        assertEquals("", biFallback)
    }

    @Test
    fun `category selection skips missing dictionaries and picks next configured dictionary in priority order`() {
        val result = lookupResult(
            GlossaryEntry(dictName = "ThirdMono", glossary = "third", definitionTags = "", termTags = ""),
            GlossaryEntry(dictName = "SecondMono", glossary = "second", definitionTags = "", termTags = ""),
        )
        val rendered = AnkiCardCreator.renderTemplateForTests(
            template = "{${Marker.MONOLINGUAL_DEFINITION_BRIEF}}",
            result = result,
            dictionaryOrder = listOf("FirstMissingMono", "SecondMono", "ThirdMono"),
            dictionaryCategories = mapOf(
                "FirstMissingMono" to DictionaryCategory.MONOLINGUAL,
                "SecondMono" to DictionaryCategory.MONOLINGUAL,
                "ThirdMono" to DictionaryCategory.MONOLINGUAL,
            ),
        )
        assertTrue(rendered.contains("second"))
        assertTrue(!rendered.contains("third"))
    }

    @Test
    fun `excluded dictionary stays available to selected-glossary and single-glossary`() {
        val result = lookupResult(
            GlossaryEntry(dictName = "Excluded", glossary = "excluded def", definitionTags = "", termTags = ""),
        )
        val categories = mapOf("Excluded" to DictionaryCategory.EXCLUDE)

        val general = AnkiCardCreator.renderTemplateForTests(
            template = "{${Marker.GLOSSARY}}|{${Marker.GLOSSARY_FIRST}}|{${Marker.DICTIONARY}}",
            result = result,
            dictionaryCategories = categories,
        )
        val explicit = AnkiCardCreator.renderTemplateForTests(
            template = "{${Marker.SELECTED_GLOSSARY}}|{${Marker.SINGLE_GLOSSARY}-Excluded}",
            result = result,
            selectedDict = "Excluded",
            dictionaryCategories = categories,
        )
        assertEquals("||", general)
        val parts = explicit.split("|")
        assertEquals(2, parts.size)
        assertTrue(parts[0].contains("excluded def"))
        assertTrue(parts[1].contains("excluded def"))
    }

    @Test
    fun `addToAnki renders monolingual and bilingual markers using folder to index title mapping`() = runTest {
        val bridge = CapturingBridge()
        AnkiCardCreator.bridgeFactory = { bridge }
        AnkiCardCreator.fieldMapParser = {
            mapOf(
                "Front" to "{expression}",
                "Mono" to "{monolingual-definition-no-dictionary}",
                "Bi" to "{bilingual-definition-brief}",
                "Glossary" to "{glossary-brief}",
            )
        }

        val tempExternalDir = Files.createTempDirectory("chimahon-ext-test").toFile()
        val monoDir = File(tempExternalDir, "dictionaries/term/daijirin-folder").apply { mkdirs() }
        File(monoDir, "index.json").writeText("""{"title":"大辞林","revision":"1"}""")
        val biDir = File(tempExternalDir, "dictionaries/term/jmdict-folder").apply { mkdirs() }
        File(biDir, "index.json").writeText("""{"title":"JMdict","revision":"1"}""")

        val context = object : ContextWrapper(null) {
            private val files = Files.createTempDirectory("chimahon-files-test").toFile()
            override fun getFilesDir(): File = files
            override fun getExternalFilesDir(type: String?): File = tempExternalDir
        }

        val result = AnkiCardCreator.addToAnki(
            context = context,
            result = lookupResult(
                GlossaryEntry(dictName = "JMdict", glossary = "word", definitionTags = "", termTags = ""),
                GlossaryEntry(dictName = "大辞林", glossary = "ことば", definitionTags = "名", termTags = ""),
                GlossaryEntry(dictName = "ExcludedDict", glossary = "skip", definitionTags = "", termTags = ""),
            ),
            deck = "deck",
            model = "model",
            fieldMapJson = "{}",
            tags = "",
            dupCheck = false,
            dupScope = "collection",
            dupAction = "prevent",
            dictionaryOrder = listOf("daijirin-folder", "jmdict-folder", "ExcludedDict"),
            dictionaryCategories = mapOf(
                "daijirin-folder" to DictionaryCategory.MONOLINGUAL,
                "jmdict-folder" to DictionaryCategory.BILINGUAL,
                "ExcludedDict" to DictionaryCategory.EXCLUDE,
            ),
        )

        assertEquals(AnkiResult.Success(1L), result)
        assertEquals("言葉", bridge.lastFields["Front"])
        assertTrue(bridge.lastFields.getValue("Mono").contains("<i>(名)</i> ことば"))
        assertTrue(bridge.lastFields.getValue("Bi").contains("word"))
        assertTrue(bridge.lastFields.getValue("Glossary").contains("ことば"))
        assertTrue(bridge.lastFields.getValue("Glossary").contains("word"))
        assertTrue(!bridge.lastFields.getValue("Glossary").contains("skip"))
    }

    private fun lookupResult(vararg glossaries: GlossaryEntry): LookupResult = LookupResult(
        matched = "言葉",
        deinflected = "言葉",
        process = emptyArray<TransformGroup>(),
        term = TermResult(
            expression = "言葉",
            reading = "ことば",
            rules = "",
            glossaries = arrayOf(*glossaries),
            frequencies = emptyArray(),
            pitches = emptyArray(),
        ),
        preprocessorSteps = 0,
    )

    private class CapturingBridge : AnkiCardBridge {
        var lastFields: Map<String, String> = emptyMap()
        override fun hasPermission(): Boolean = true
        override suspend fun ensureDefaultDeckName(): String = "deck"
        override suspend fun ensureLapisModelName(): String = "model"
        override suspend fun getDeckId(deckName: String): Long = 1L
        override suspend fun findNotes(expression: String, modelName: String?, deckId: Long?): List<Long> = emptyList()
        override suspend fun storeMedia(filename: String, data: ByteArray): String = filename
        override suspend fun storeMedia(source: AnkiSentenceAudioSource): String = "audio.m4a"
        override suspend fun addNote(deckName: String, modelName: String, fields: Map<String, String>, tags: List<String>): Long {
            lastFields = fields
            return 1L
        }
        override suspend fun updateNoteFields(noteId: Long, fields: Map<String, String>) = Unit
        override fun triggerSync() = Unit
    }
}
