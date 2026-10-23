package chimahon

data class ImportResult(
    val success: Boolean,
    val title: String,
    val termCount: Long,
    val metaCount: Long,
    val freqCount: Long,
    val pitchCount: Long,
    val kanjiCount: Long,
    val mediaCount: Long,
)

data class TransformGroup(
    val name: String,
    val description: String,
)

data class DictionaryStyle(
    val dictName: String,
    val styles: String,
)

data class Frequency(
    val value: Int,
    val displayValue: String,
)

data class GlossaryEntry(
    val dictName: String,
    val glossary: String,
    val definitionTags: String,
    val termTags: String,
)

data class FrequencyEntry(
    val dictName: String,
    val frequencies: Array<Frequency>,
)

data class PitchEntry(
    val dictName: String,
    val pitchPositions: IntArray,
)

data class Cloze(
    val sentence: String,
    val prefix: String,
    val body: String,
    val bodyKana: String,
    val suffix: String,
)

data class MediaInfo(
    val mangaTitle: String,
    val chapterName: String,
)

data class TermResult(
    val expression: String,
    val reading: String,
    val rules: String,
    val glossaries: Array<GlossaryEntry>,
    val frequencies: Array<FrequencyEntry>,
    val pitches: Array<PitchEntry>,
)

data class KanjiEntry(
    val dictName: String,
    val onyomi: String,
    val kunyomi: String,
    val tags: String,
    val definitions: Array<String>,
    val stats: Map<String, String>,
)

data class KanjiResult(
    val character: String,
    val entries: Array<KanjiEntry>,
)

data class LookupResult(
    val matched: String,
    val deinflected: String,
    val process: Array<TransformGroup>,
    val term: TermResult,
    val preprocessorSteps: Int,
)

object HoshiDicts {
    init {
        System.loadLibrary("hoshidicts_jni")
    }

    external fun importDictionary(zipPath: String, outputDir: String): ImportResult
    external fun probeEntryTypes(dictPath: String): LongArray
    external fun createLookupObject(): Long
    external fun destroyLookupObject(session: Long)
    external fun rebuildQuery(
        session: Long,
        termPaths: Array<String>,
        freqPaths: Array<String>,
        pitchPaths: Array<String>,
        kanjiPaths: Array<String>,
    )

    external fun lookup(session: Long, text: String, maxResults: Int, scanLength: Int): Array<LookupResult>
    // DICT_PROFILE: used only by explicitly enabled captures.
    external fun lookupProfiled(session: Long, text: String, maxResults: Int, scanLength: Int, requestId: Long): Array<LookupResult>
    external fun query(session: Long, text: String): Array<TermResult>
    external fun queryKanji(session: Long, character: String): KanjiResult
    external fun getStyles(session: Long): Array<DictionaryStyle>
    external fun getMediaFile(session: Long, dictName: String, mediaPath: String): ByteArray?

    fun query(session: Long, texts: List<String>, maxResults: Int): List<TermResult> {
        return texts.flatMap { query(session, it).toList() }
            .distinctBy { it.expression to it.reading }
            .take(maxResults)
    }
}
