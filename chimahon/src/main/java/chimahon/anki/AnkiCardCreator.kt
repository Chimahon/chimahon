package chimahon.anki

import android.content.Context
import chimahon.Cloze
import chimahon.DictionaryRepository
import chimahon.HoshiDicts
import chimahon.DictionaryStyle
import chimahon.GlossaryEntry
import chimahon.LookupResult
import chimahon.MediaInfo
import chimahon.PitchEntry
import chimahon.audio.WordAudioResult
import chimahon.audio.WordAudioService
import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

// =============================================================================
// Legacy FieldType enum for UI backwards compatibility
// TODO: Migrate UI to use Marker strings
// =============================================================================

enum class FieldType {
    NONE,
    TARGET_WORD,
    READING,
    FURIGANA,
    DEFINITION,
    SENTENCE,
    FREQUENCY,
    PITCH_ACCENT,
    ;

    companion object {
        val DISPLAY_VALUES = entries.filter { it != NONE }

        fun fromKey(key: String): FieldType =
            entries.find { it.name == key } ?: NONE
    }
}

// =============================================================================
// Marker constants — mirrors Yomitan's getStandardFieldMarkers("term")
// =============================================================================

object Marker {
    const val EXPRESSION = "expression"
    const val READING = "reading"
    const val FURIGANA = "furigana"
    const val FURIGANA_PLAIN = "furigana-plain"
    const val AUDIO = "audio"
    const val GLOSSARY = "glossary"
    const val GLOSSARY_BRIEF = "glossary-brief"
    const val GLOSSARY_PLAIN = "glossary-plain"
    const val GLOSSARY_PLAIN_NO_DICT = "glossary-plain-no-dictionary"
    const val GLOSSARY_NO_DICT = "glossary-no-dictionary"
    const val GLOSSARY_FIRST = "glossary-first"
    const val GLOSSARY_FIRST_NO_DICT = "glossary-first-no-dictionary"
    const val GLOSSARY_FIRST_BRIEF = "glossary-first-brief"
    const val SENTENCE = "sentence"
    const val SENTENCE_BOLD = "sentence-bold"
    const val CLOZE_PREFIX = "cloze-prefix"
    const val CLOZE_BODY = "cloze-body"
    const val CLOZE_BODY_KANA = "cloze-body-kana"
    const val CLOZE_SUFFIX = "cloze-suffix"
    const val TAGS = "tags"
    const val PART_OF_SPEECH = "part-of-speech"
    const val CONJUGATION = "conjugation"
    const val DICTIONARY = "dictionary"
    const val DICTIONARY_ALIAS = "dictionary-alias"
    const val FREQUENCIES = "frequencies"
    const val FREQUENCY_LOWEST = "frequency-lowest"
    const val FREQUENCY_HARMONIC_RANK = "frequency-harmonic-rank"
    const val FREQUENCY_AVERAGE_RANK = "frequency-average-rank"
    const val SINGLE_FREQUENCY = "single-frequency"
    const val SINGLE_FREQUENCY_NUMBER = "single-frequency-number"
    const val PITCH_ACCENTS = "pitch-accents"
    const val PITCH_ACCENT_POSITIONS = "pitch-accent-positions"
    const val PITCH_ACCENT_CATEGORIES = "pitch-accent-categories"
    const val SCREENSHOT = "screenshot"
    const val SEARCH_QUERY = "search-query"
    const val URL = "url"
    const val CHAPTER = "chapter"
    const val MEDIA = "media"
    const val SINGLE_GLOSSARY = "single-glossary"
    const val MORAE = "morae"
    const val PITCH_ACCENT_GRAPHS = "pitch-accent-graphs"
    const val PITCH_ACCENT_GRAPHS_JJ = "pitch-accent-graphs-jj"
    const val PITCH_ACCENT_COMPOSITE = "pitch-accent-composite"
    const val SENTENCE_FURIGANA = "sentence-furigana"
    const val SENTENCE_FURIGANA_PLAIN = "sentence-furigana-plain"
    const val SENTENCE_TRANSLATION = "sentence-translation"
    const val SELECTION_TEXT = "selection-text"
    const val MISC_INFO = "misc-info"
    const val POPUP_SELECTION_TEXT = "popup-selection-text"
    const val SELECTED_GLOSSARY = "selected-glossary"
    const val SELECTED_GLOSSARY_NO_FALLBACK = "selected-glossary-no-fallback"
    const val MEDIA_NAME = "media-name"
    const val WORD_AUDIO = "word-audio"
    const val SENTENCE_AUDIO = "sentence-audio"

    val ALL: List<String> = listOf(
        // Core/Common
        EXPRESSION, READING, GLOSSARY, SENTENCE, SCREENSHOT, WORD_AUDIO, AUDIO,
        SELECTED_GLOSSARY, SELECTED_GLOSSARY_NO_FALLBACK, SINGLE_GLOSSARY,

        // Furigana
        FURIGANA, FURIGANA_PLAIN,

        // Glossary variants
        GLOSSARY_BRIEF, GLOSSARY_PLAIN, GLOSSARY_PLAIN_NO_DICT, GLOSSARY_NO_DICT,
        GLOSSARY_FIRST, GLOSSARY_FIRST_NO_DICT, GLOSSARY_FIRST_BRIEF,

        // Sentence/Cloze
        SENTENCE_BOLD, CLOZE_PREFIX, CLOZE_BODY, CLOZE_BODY_KANA, CLOZE_SUFFIX,
        SENTENCE_FURIGANA, SENTENCE_FURIGANA_PLAIN,

        // Pitch Accent
        PITCH_ACCENTS, PITCH_ACCENT_POSITIONS, PITCH_ACCENT_CATEGORIES,
        PITCH_ACCENT_GRAPHS, PITCH_ACCENT_GRAPHS_JJ, PITCH_ACCENT_COMPOSITE, MORAE,

        // Frequencies
        FREQUENCIES, FREQUENCY_LOWEST, FREQUENCY_HARMONIC_RANK, FREQUENCY_AVERAGE_RANK,

        // Meta
        TAGS, PART_OF_SPEECH, CONJUGATION, DICTIONARY, DICTIONARY_ALIAS,

        // Source/Context
        URL, CHAPTER, MEDIA, MEDIA_NAME,

        // Other
        SENTENCE_AUDIO, POPUP_SELECTION_TEXT,
    )

    val ALL_WITH_TODO: List<String> = ALL

    val TODO_MARKERS = setOf<String>()

    val AUTO_DETECT_ALIASES: Map<String, List<String>> = mapOf(
        EXPRESSION to listOf("expression", "phrase", "term", "word"),
        READING to listOf("reading", "expression-reading", "term-reading", "word-reading"),
        FURIGANA to listOf("furigana", "expression-furigana", "term-furigana", "word-furigana"),
        GLOSSARY to listOf("glossary", "definition", "meaning"),
        WORD_AUDIO to listOf("audio", "sound", "word-audio", "term-audio", "expression-audio", "wordaudio"),
        DICTIONARY to listOf("dictionary", "dict"),
        PITCH_ACCENTS to listOf("pitch-accents", "pitch", "pitch-accent", "pitchaccent", "pitchaccents", "accent", "pitch-pattern"),
        PITCH_ACCENT_POSITIONS to listOf("pitch-accent-positions", "pitch-position", "pitch-positions", "pitchpositions", "positions", "pitchaccentpositions"),
        PITCH_ACCENT_CATEGORIES to listOf("pitch-accent-categories", "pitch-categories", "pitchcategories", "categories", "pitchaccentcategories"),
        PITCH_ACCENT_GRAPHS to listOf("pitch-accent-graphs", "pitch-graphs", "graphs", "pitchaccentgraphs"),
        SENTENCE to listOf("sentence", "example-sentence"),
        SENTENCE_FURIGANA to listOf("sentence-furigana", "sentencefurigana", "sentence_furigana"),
        SENTENCE_FURIGANA_PLAIN to listOf("sentence-furigana-plain", "sentencefuriganaplain", "sentence_furigana_plain"),
        CLOZE_BODY to listOf("cloze-body", "cloze"),
        CLOZE_PREFIX to listOf("cloze-prefix"),
        CLOZE_SUFFIX to listOf("cloze-suffix"),
        FREQUENCIES to listOf("frequencies", "frequency-list"),
        FREQUENCY_HARMONIC_RANK to listOf("frequency-harmonic-rank", "freq", "frequency", "freq-rank", "frequency-rank", "freq-sort", "freqency-sort", "freqsort"),
        FREQUENCY_AVERAGE_RANK to listOf("freq-avg", "frequency-average"),
        SENTENCE_TRANSLATION to listOf("sentence-translation", "sentencetranslation", "meaning-eng"),
        POPUP_SELECTION_TEXT to listOf("selection", "selection-text", "selectiontext", "popupselectiontext", "popup-selection-text"),
        MEDIA_NAME to listOf("media-name", "medianame", "book", "manga", "series", "title", "miscinfo", "document-title", "documenttitle"),
        SEARCH_QUERY to listOf("search-query", "query"),
        SCREENSHOT to listOf("screenshot"),
        TAGS to listOf("tags", "tag"),
        PART_OF_SPEECH to listOf("part-of-speech", "pos", "part"),
        CONJUGATION to listOf("conjugation", "inflection"),
        CHAPTER to listOf("chapter", "episode"),
        MEDIA to listOf("media", "source", "context"),
        SENTENCE_AUDIO to listOf("sentence-audio", "sentenceaudio", "sentence-sound", "sentence_sound"),
    )

    fun autoDetect(fieldName: String, fieldIndex: Int, entryType: String = "term"): String? {
        if (fieldIndex == 0) {
            return if (entryType == "kanji") "character" else EXPRESSION
        }
        val lower = fieldName.trim().lowercase()
        val normalized = normalizeAutoDetectName(fieldName)
        for ((marker, aliases) in AUTO_DETECT_ALIASES) {
            if (aliases.any { alias -> lower == alias || normalized == normalizeAutoDetectName(alias) }) return marker
        }
        return null
    }

    private fun normalizeAutoDetectName(name: String): String =
        name.trim()
            .lowercase()
            .replace(Regex("""[\s_\-]+"""), "")
}

// =============================================================================
// AnkiResult
// =============================================================================

sealed class AnkiResult {
    data class Success(
        val noteId: Long,
        val mediaWarnings: List<AnkiMediaWarning> = emptyList(),
    ) : AnkiResult()
    data class CardExists(val noteId: Long) : AnkiResult()
    data class OpenCard(val noteId: Long) : AnkiResult()
    data class Error(val message: String) : AnkiResult()
    data object PermissionDenied : AnkiResult()
    data object NotConfigured : AnkiResult()
}

// =============================================================================
// Field map parsing
// =============================================================================

object AnkiCardCreator {

    private const val TAG = "AnkiCardCreator"
    private const val SCREENSHOT_MEDIA_PLACEHOLDER = "chimahon_screenshot_pending.webp"
    private const val WORD_AUDIO_MEDIA_PLACEHOLDER = "chimahon_word_audio_pending.mp3"
    private const val SENTENCE_AUDIO_MEDIA_PLACEHOLDER = "chimahon_sentence_audio_pending.m4a"
    private val duplicateGate = AnkiDuplicateGate()
    @Volatile
    internal var bridgeFactory: (Context) -> AnkiCardBridge = ::AnkiDroidBridge
    @Volatile
    internal var fieldMapParser: (String) -> Map<String, String> = ::parseFieldMap

    internal fun resetBridgeFactoryForTests() {
        bridgeFactory = ::AnkiDroidBridge
    }

    internal fun resetFieldMapParserForTests() {
        fieldMapParser = ::parseFieldMap
    }

    private data class DictionaryMediaReference(
        val dictionary: String,
        val path: String,
        val placeholder: String,
    )

    private class ExportMediaContext {
        private val items = linkedMapOf<Pair<String, String>, DictionaryMediaReference>()

        val references: List<DictionaryMediaReference>
            get() = items.values.toList()

        fun placeholderFor(dictionary: String, path: String): String {
            val key = dictionary to path
            return items.getOrPut(key) {
                val extension = path.substringBefore('?')
                    .substringAfterLast('.', "png")
                    .replace(Regex("[^A-Za-z0-9]"), "")
                    .ifBlank { "png" }
                    .lowercase()
                DictionaryMediaReference(
                    dictionary = dictionary,
                    path = path,
                    placeholder = "chimahon_dict_${items.size}.$extension",
                )
            }.placeholder
        }
    }

    suspend fun addToAnki(
        context: Context,
        result: LookupResult,
        deck: String,
        model: String,
        fieldMapJson: String,
        tags: String,
        dupCheck: Boolean,
        dupScope: String,
        dupAction: String,
        sentence: String = "",
        offset: Int = -1,
        media: MediaInfo? = null,
        screenshotBytes: ByteArray? = null,
        sentenceAudioBytes: ByteArray? = null,
        sentenceAudioExtension: String = "m4a",
        glossaryIndex: Int? = null,
        selection: String? = null,
        selectedDict: String? = null,
        popupSelection: String? = null,
        styles: List<DictionaryStyle> = emptyList(),
        forceOpen: Boolean = false,
        type: String? = null,
        syncOnCreate: Boolean = false,
        profileId: String = "",
        titleId: String? = null,
        mediaRequest: AnkiMediaRequest? = null,
    ): AnkiResult {
        android.util.Log.d(TAG, "addToAnki: deck=$deck, model=$model, forceOpen=$forceOpen, glossaryIndex=$glossaryIndex")

        val bridge = bridgeFactory(context)
        if (!bridge.hasPermission()) {
            return AnkiResult.PermissionDenied
        }
        return try {
            val effectiveDeck = deck.ifBlank { bridge.ensureDefaultDeckName() }
            val effectiveModel = if (model.isBlank()) {
                bridge.ensureLapisModelName()
            } else {
                model
            }
            val effectiveFieldMapJson = if (
                LapisPreset.isBundledModelName(effectiveModel) &&
                LapisPreset.isBlankFieldMap(fieldMapJson)
            ) {
                LapisPreset.defaultFieldMapJson
            } else {
                fieldMapJson
            }

            if (effectiveDeck.isBlank() || effectiveModel.isBlank()) {
                android.util.Log.w(TAG, "addToAnki: NotConfigured - deck or model is blank")
                return AnkiResult.NotConfigured
            }

            val fieldMap = fieldMapParser(effectiveFieldMapJson)
            val cloze = if (sentence.isNotEmpty() && offset >= 0) {
                // Use result.matched (the exact surface form the dictionary engine consumed)
                // so the bold window is precisely the word that was looked up, not the base form.
                // If the caller provided a manual selection override, use that instead.
                val boldTarget = selection?.takeIf { it.isNotEmpty() } ?: result.matched
                buildCloze(sentence, offset, result.term.expression, result.term.reading, boldTarget)
            } else {
                null
            }

            val hasWordAudioMarker = fieldMap.values.any {
                it.contains("{${Marker.WORD_AUDIO}}") || it.contains("{${Marker.AUDIO}}")
            }
            val hasSentenceAudioMarker = fieldMap.values.any { it.contains("{${Marker.SENTENCE_AUDIO}}") }
            val hasScreenshotMarker = fieldMap.values.any { it.contains("{${Marker.SCREENSHOT}}") }
            val lockKey = "${dupScope.lowercase()}|${if (dupScope == "deck") effectiveDeck else ""}|${result.term.expression}"
            suspend fun duplicateDecision(): AnkiDuplicateDecision {
                if (!dupCheck && !forceOpen) return AnkiDuplicateDecision.Insert
                val targetDeckId = if (dupScope == "deck" && effectiveDeck.isNotBlank()) {
                    try {
                        bridge.getDeckId(effectiveDeck)
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
                val existing = bridge.findNotes(result.term.expression, null, targetDeckId)
                if (existing.isEmpty()) return AnkiDuplicateDecision.Insert
                val noteId = existing.first()
                return when {
                    forceOpen -> AnkiDuplicateDecision.Return(AnkiResult.OpenCard(noteId))
                    dupAction == "prevent" -> AnkiDuplicateDecision.Return(AnkiResult.CardExists(noteId))
                    dupAction == "open" -> AnkiDuplicateDecision.Return(AnkiResult.OpenCard(noteId))
                    dupAction == "overwrite" -> AnkiDuplicateDecision.Overwrite(noteId)
                    else -> AnkiDuplicateDecision.Insert
                }
            }

            val firstDecision = duplicateGate.preflight(lockKey, ::duplicateDecision)
            if (firstDecision is AnkiDuplicateDecision.Return) return firstDecision.result

            val sentenceAudioPreparation = prepareSentenceAudioForMarker(
                hasSentenceAudioMarker,
                mediaRequest?.sentenceAudioProvider,
            ) ?: when {
                hasSentenceAudioMarker && sentenceAudioBytes != null -> {
                    AnkiSentenceAudioPreparation.Ready(
                        AnkiSentenceAudioSource.fromBytes(sentenceAudioBytes, sentenceAudioExtension),
                    )
                }
                else -> null
            }
            val exportMedia = ExportMediaContext()
            val fieldsWithPlaceholders = withContext(Dispatchers.Default) {
                buildFields(
                    result,
                    fieldMap,
                    cloze,
                    media,
                    SCREENSHOT_MEDIA_PLACEHOLDER.takeIf { hasScreenshotMarker },
                    WORD_AUDIO_MEDIA_PLACEHOLDER.takeIf { hasWordAudioMarker },
                    SENTENCE_AUDIO_MEDIA_PLACEHOLDER.takeIf { hasSentenceAudioMarker },
                    selectedDict,
                    popupSelection,
                    glossaryIndex,
                    styles,
                    exportMedia,
                )
            }
            val tagsWithPlaceholders = withContext(Dispatchers.Default) {
                // Split configured tags before rendering markers so commas from a
                // resolved value (for example a title) stay inside one tag.
                tags.split(",")
                    .map { template ->
                        formatField(
                            template,
                            result,
                            cloze,
                            media,
                            SCREENSHOT_MEDIA_PLACEHOLDER.takeIf { hasScreenshotMarker },
                            WORD_AUDIO_MEDIA_PLACEHOLDER.takeIf { hasWordAudioMarker },
                            SENTENCE_AUDIO_MEDIA_PLACEHOLDER.takeIf { hasSentenceAudioMarker },
                            selectedDict,
                            popupSelection,
                            glossaryIndex,
                            styles, exportMedia,
                        )
                    }
            }
            val preparedMedia = PreparedAnkiCardMedia(
                screenshot = screenshotBytes
                    ?.let { PreparedAnkiMediaPayload(generateScreenshotFilename(it), it) },
                wordAudio = if (hasWordAudioMarker) prepareWordAudioPayload(result) else null,
                dictionaryMedia = prepareDictionaryMedia(exportMedia),
                sentenceAudio = sentenceAudioPreparation,
            )

            duplicateGate.commit(lockKey, ::duplicateDecision) { finalDecision ->
                if (finalDecision is AnkiDuplicateDecision.Return) {
                    return@commit finalDecision.result
                }
                val committedMedia = commitPreparedAnkiCardMedia(
                    prepared = preparedMedia,
                    storeBytes = bridge::storeMedia,
                    storeSentenceAudio = bridge::storeMedia,
                )
                val fields = resolveMediaPlaceholders(
                    resolveDictionaryMediaPlaceholders(fieldsWithPlaceholders, committedMedia.dictionaryReplacementByPlaceholder),
                    committedMedia,
                )
                val tagList = tagsWithPlaceholders
                    .map { value -> resolveMediaPlaceholders(value, committedMedia) }
                    .mapNotNull(::normalizeAnkiTag)

                val noteId = when (finalDecision) {
                    is AnkiDuplicateDecision.Overwrite -> {
                        bridge.updateNoteFields(finalDecision.noteId, fields)
                        finalDecision.noteId
                    }
                    AnkiDuplicateDecision.Insert -> bridge.addNote(
                        deckName = effectiveDeck,
                        modelName = effectiveModel,
                        fields = fields,
                        tags = tagList,
                    )
                    is AnkiDuplicateDecision.Return -> error("handled above")
                }
                com.canopus.chimareader.data.AnkiStatsStorage.addCard(
                    context,
                    type,
                    profileId = profileId,
                    titleId = titleId,
                )
                if (syncOnCreate) bridge.triggerSync()
                AnkiResult.Success(noteId, committedMedia.sentenceAudio.warnings)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            AnkiResult.PermissionDenied
        } catch (e: Exception) {
            AnkiResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun filterToSingleGlossary(result: LookupResult, glossaryIndex: Int): LookupResult {
        val glossary = result.term.glossaries.getOrNull(glossaryIndex) ?: return result
        val filteredGlossaries = arrayOf(glossary)
        return result.copy(
            term = result.term.copy(
                glossaries = filteredGlossaries,
            ),
        )
    }

    suspend fun checkExistingCards(
        context: Context,
        expressions: List<String>,
        deckName: String = "",
        dupScope: String = "collection",
    ): Set<String> = checkExistingCardIds(
        context = context,
        expressions = expressions,
        deckName = deckName,
        dupScope = dupScope,
    ).keys

    suspend fun checkExistingCardIds(
        context: Context,
        expressions: List<String>,
        deckName: String = "",
        dupScope: String = "collection",
    ): Map<String, Long> {
        val existing = linkedMapOf<String, Long>()

        val bridge = bridgeFactory(context)
        if (!bridge.hasPermission()) return existing

        val targetDeckId = if (dupScope == "deck" && deckName.isNotBlank()) {
            try {
                bridge.getDeckId(deckName)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        for (expr in expressions.distinct()) {
            try {
                val notes = bridge.findNotes(expr, null, targetDeckId)
                notes.firstOrNull()?.let { noteId -> existing[expr] = noteId }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "checkExistingCards failed for expr=$expr", e)
            }
        }
        return existing
    }

    suspend fun findExistingCardId(
        context: Context,
        expression: String,
        deckName: String = "",
        dupScope: String = "collection",
    ): Long? {
        val bridge = bridgeFactory(context)
        if (!bridge.hasPermission()) return null

        val targetDeckId = if (dupScope == "deck" && deckName.isNotBlank()) {
            try {
                bridge.getDeckId(deckName)
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        return try {
            bridge.findNotes(expression, null, targetDeckId).firstOrNull()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "findExistingCardId failed for expr=$expression", e)
            null
        }
    }

    fun parseFieldMap(json: String): Map<String, String> {
        if (json.isBlank() || json == "{}") return emptyMap()
        return try {
            val obj = JSONObject(json)
            buildMap {
                obj.keys().forEach { key ->
                    val raw = obj.getString(key).trim()
                    if (raw.isNotEmpty()) put(key, normalizeFieldValue(raw))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun normalizeFieldValue(raw: String): String {
        // If it looks like a template already (contains markers or HTML), preserve it.
        if (raw.contains("{") || raw.contains("<") || raw.contains(">")) return raw

        val legacyMap = mapOf(
            "TARGET_WORD" to Marker.EXPRESSION,
            "READING" to Marker.READING,
            "FURIGANA" to Marker.FURIGANA,
            "DEFINITION" to Marker.GLOSSARY,
            "SENTENCE" to Marker.SENTENCE,
            "FREQUENCY" to Marker.FREQUENCY_HARMONIC_RANK,
            "PITCH_ACCENT" to Marker.PITCH_ACCENTS,
            "NONE" to "",
        )
        val parts = raw.split(",")
        if (parts.all { it.trim() in legacyMap }) {
            return parts.mapNotNull { part ->
                val marker = legacyMap[part.trim()]
                if (marker.isNullOrBlank()) null else "{$marker}"
            }.joinToString("<br>")
        }
        return raw
    }

    // =============================================================================
    // Field rendering
    // =============================================================================

    private val MARKER_PATTERN = Regex("""\{([\w\W]+?)\}""")

    private fun buildFields(
        result: LookupResult,
        fieldMap: Map<String, String>,
        cloze: Cloze? = null,
        media: MediaInfo? = null,
        screenshotFilename: String? = null,
        wordAudioFilename: String? = null,
        sentenceAudioFilename: String? = null,
        selectedDict: String? = null,
        popupSelection: String? = null,
        glossaryIndex: Int? = null,
        styles: List<DictionaryStyle> = emptyList(),
        exportMedia: ExportMediaContext? = null,
    ): Map<String, String> = fieldMap.mapValues { (_, template) ->
        formatField(template, result, cloze, media, screenshotFilename, wordAudioFilename, sentenceAudioFilename, selectedDict, popupSelection, glossaryIndex, styles, exportMedia)
    }

    private fun formatField(
        template: String,
        result: LookupResult,
        cloze: Cloze?,
        media: MediaInfo?,
        screenshotFilename: String?,
        wordAudioFilename: String?,
        sentenceAudioFilename: String?,
        selectedDict: String?,
        popupSelection: String?,
        glossaryIndex: Int?,
        styles: List<DictionaryStyle>,
        exportMedia: ExportMediaContext?,
    ): String {
        if (template.isBlank()) return ""
        return MARKER_PATTERN.replace(template) { match ->
            renderMarker(
                marker = match.groupValues[1],
                result = result,
                cloze = cloze,
                media = media,
                screenshotFilename = screenshotFilename,
                wordAudioFilename = wordAudioFilename,
                sentenceAudioFilename = sentenceAudioFilename,
                selectedDict = selectedDict,
                popupSelection = popupSelection,
                glossaryIndex = glossaryIndex,
                styles = styles,
                exportMedia = exportMedia,
            )
        }
    }

    private suspend fun prepareDictionaryMedia(
        exportMedia: ExportMediaContext,
    ): List<PreparedDictionaryMedia> = withContext(Dispatchers.IO) {
        val references = exportMedia.references
        if (references.isEmpty()) return@withContext emptyList()
        val session = Injekt.get<DictionaryRepository>().lookupSession
        references.map { reference ->
            val payload = try {
                session?.let {
                    HoshiDicts.getMediaFile(it, reference.dictionary, reference.path)
                }?.let { bytes ->
                    PreparedAnkiMediaPayload(dictionaryMediaFilename(reference.path, bytes), bytes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to prepare dictionary media ${reference.path}", e)
                null
            }
            PreparedDictionaryMedia(reference.placeholder, payload)
        }
    }

    private suspend fun prepareWordAudioPayload(result: LookupResult): PreparedAnkiMediaPayload? = try {
        val service = Injekt.get<WordAudioService>()
        val bestAudio = service.findWordAudio(result.term.expression, result.term.reading).firstOrNull()
            ?: return null
        val data = if (bestAudio.url.startsWith("chimahon-local://")) {
            val uri = android.net.Uri.parse(bestAudio.url)
            service.getAudioData(uri.path?.substring(1).orEmpty(), uri.host.orEmpty())
        } else {
            service.fetchRemoteAudioData(bestAudio.url)
        } ?: return null
        val extension = android.net.Uri.parse(bestAudio.url).lastPathSegment
            ?.substringAfterLast('.', "mp3")
            ?.let { AnkiSentenceAudioSource.safeExtension(it, "mp3") }
            ?: "mp3"
        PreparedAnkiMediaPayload(
            "chimahon_audio_${result.term.expression}_${result.term.reading}_${System.currentTimeMillis()}.$extension",
            data,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w(TAG, "addToAnki: failed to prepare word audio media", e)
        null
    }

    private fun resolveDictionaryMediaPlaceholders(
        fields: Map<String, String>,
        replacements: Map<String, String>,
    ): Map<String, String> = fields.mapValues { (_, value) ->
        replacements.entries.fold(value) { current, (placeholder, replacement) ->
            current.replace(placeholder, replacement)
        }
    }

    private fun resolveMediaPlaceholders(
        value: String,
        media: CommittedAnkiCardMedia,
    ): String =
        value
            .resolveImagePlaceholder(SCREENSHOT_MEDIA_PLACEHOLDER, media.screenshotFilename)
            .resolveSoundPlaceholder(WORD_AUDIO_MEDIA_PLACEHOLDER, media.wordAudioFilename)
            .resolveSoundPlaceholder(SENTENCE_AUDIO_MEDIA_PLACEHOLDER, media.sentenceAudio.filename)

    private fun resolveMediaPlaceholders(
        fields: Map<String, String>,
        media: CommittedAnkiCardMedia,
    ): Map<String, String> = fields.mapValues { (_, value) ->
        resolveMediaPlaceholders(value, media)
    }

    private fun String.resolveImagePlaceholder(placeholder: String, filename: String?): String =
        if (filename == null) replace("<img src=\"$placeholder\">", "").replace(placeholder, "") else replace(placeholder, filename)

    private fun String.resolveSoundPlaceholder(placeholder: String, filename: String?): String =
        if (filename == null) replace("[sound:$placeholder]", "").replace(placeholder, "") else replace(placeholder, filename)

    private fun dictionaryMediaFilename(path: String, bytes: ByteArray): String {
        val extension = path.substringBefore('?')
            .substringAfterLast('.', "png")
            .replace(Regex("[^A-Za-z0-9]"), "")
            .ifBlank { "png" }
            .lowercase()
        val hash = try {
            val digest = java.security.MessageDigest.getInstance("SHA-1").digest(bytes)
            digest.joinToString("") { "%02x".format(it) }.take(12)
        } catch (_: Exception) {
            System.currentTimeMillis().toString()
        }
        return "chimahon_dict_$hash.$extension"
    }

    // =============================================================================
    // Cloze
    // =============================================================================

    fun buildCloze(
        sentence: String,
        offset: Int,
        expression: String,
        reading: String,
        // matched: the exact surface form the dictionary engine consumed (e.g. "走った" not "走る").
        // Pass result.matched here for precise bolding. A user manual highlight takes priority at the call site.
        matched: String? = null,
    ): Cloze {
        if (sentence.isEmpty() || offset < 0 || expression.isEmpty()) {
            return Cloze(
                sentence = sentence,
                prefix = "",
                body = expression,
                bodyKana = reading,
                suffix = "",
            )
        }

        val safeOffset = offset.coerceIn(0, sentence.length)
        val prefix = sentence.substring(0, safeOffset)

        // Bold exactly as many characters as the dictionary matched (surface form length).
        // Fall back to expression length if matched is unavailable.
        val boldLen = (matched?.length ?: expression.length).coerceAtLeast(1)
        val bodyEnd = (safeOffset + boldLen).coerceAtMost(sentence.length)
        val body = sentence.substring(safeOffset, bodyEnd)

        val suffix = sentence.substring(bodyEnd)

        val bodyKana = calculateBodyKana(expression, reading)

        return Cloze(
            sentence = sentence,
            prefix = prefix,
            body = body,
            bodyKana = bodyKana,
            suffix = suffix,
        )
    }

    private fun calculateBodyKana(expression: String, reading: String): String {
        if (reading.isEmpty()) return expression
        if (expression == reading) return expression

        val segments = distributeFurigana(expression, reading)
        return segments.joinToString("") { (_, furigana) ->
            if (furigana.isNotEmpty()) furigana else ""
        }
    }

    // =============================================================================
    // Marker rendering
    // =============================================================================

    private fun renderMarker(
        marker: String,
        result: LookupResult,
        cloze: Cloze? = null,
        media: MediaInfo? = null,
        screenshotFilename: String? = null,
        wordAudioFilename: String? = null,
        sentenceAudioFilename: String? = null,
        selectedDict: String? = null,
        popupSelection: String? = null,
        glossaryIndex: Int? = null,
        styles: List<DictionaryStyle> = emptyList(),
        exportMedia: ExportMediaContext? = null,
    ): String = when (marker) {
        Marker.EXPRESSION -> escapeHtml(result.term.expression)
        Marker.READING -> escapeHtml(result.term.reading)
        Marker.FURIGANA -> buildFuriganaHtml(result.term.expression, result.term.reading)
        Marker.FURIGANA_PLAIN -> buildFuriganaPlain(result.term.expression, result.term.reading)
        Marker.AUDIO -> wordAudioFilename?.let { "[sound:$it]" } ?: ""
        Marker.GLOSSARY -> buildGlossary(
            if (glossaryIndex != null && glossaryIndex >= 0) arrayOf(result.term.glossaries.getOrElse(glossaryIndex) { result.term.glossaries.first() }) else result.term.glossaries,
            brief = false,
            noDictTag = false,
            firstOnly = false,
            styles = styles,
            exportMedia = exportMedia,
        )
        Marker.GLOSSARY_BRIEF -> buildGlossary(
            if (glossaryIndex != null && glossaryIndex >= 0) arrayOf(result.term.glossaries.getOrElse(glossaryIndex) { result.term.glossaries.first() }) else result.term.glossaries,
            brief = true,
            noDictTag = false,
            firstOnly = false,
            styles = styles,
            exportMedia = exportMedia,
        )
        Marker.GLOSSARY_NO_DICT -> buildGlossary(
            if (glossaryIndex != null && glossaryIndex >= 0) arrayOf(result.term.glossaries.getOrElse(glossaryIndex) { result.term.glossaries.first() }) else result.term.glossaries,
            brief = false,
            noDictTag = true,
            firstOnly = false,
            styles = styles,
            exportMedia = exportMedia,
        )
        Marker.GLOSSARY_FIRST -> buildGlossary(
            if (glossaryIndex != null && glossaryIndex >= 0) arrayOf(result.term.glossaries.getOrElse(glossaryIndex) { result.term.glossaries.first() }) else result.term.glossaries,
            brief = false,
            noDictTag = false,
            firstOnly = true,
            styles = styles,
            exportMedia = exportMedia,
        )
        Marker.GLOSSARY_FIRST_NO_DICT -> buildGlossary(
            if (glossaryIndex != null && glossaryIndex >= 0) arrayOf(result.term.glossaries.getOrElse(glossaryIndex) { result.term.glossaries.first() }) else result.term.glossaries,
            brief = false,
            noDictTag = true,
            firstOnly = true,
            styles = styles,
            exportMedia = exportMedia,
        )
        Marker.GLOSSARY_FIRST_BRIEF -> buildGlossary(
            if (glossaryIndex != null && glossaryIndex >= 0) arrayOf(result.term.glossaries.getOrElse(glossaryIndex) { result.term.glossaries.first() }) else result.term.glossaries,
            brief = true,
            noDictTag = false,
            firstOnly = true,
            styles = styles,
            exportMedia = exportMedia,
        )
        Marker.GLOSSARY_PLAIN -> buildGlossaryPlain(
            if (glossaryIndex != null && glossaryIndex >= 0) arrayOf(result.term.glossaries.getOrElse(glossaryIndex) { result.term.glossaries.first() }) else result.term.glossaries,
            noDictTag = false,
        )
        Marker.GLOSSARY_PLAIN_NO_DICT -> buildGlossaryPlain(
            if (glossaryIndex != null && glossaryIndex >= 0) arrayOf(result.term.glossaries.getOrElse(glossaryIndex) { result.term.glossaries.first() }) else result.term.glossaries,
            noDictTag = true,
        )
        Marker.SENTENCE -> cloze?.let { escapeHtml(it.sentence) } ?: ""
        Marker.SENTENCE_BOLD -> cloze?.let { "${escapeHtml(it.prefix)}<b>${escapeHtml(it.body)}</b>${escapeHtml(it.suffix)}" } ?: ""
        Marker.SENTENCE_FURIGANA -> {
            val sentence = cloze?.sentence ?: ""
            val session = Injekt.get<DictionaryRepository>().lookupSession
            if (sentence.isNotEmpty() && session != null) {
                buildSentenceFuriganaHtml(sentence, session)
            } else {
                escapeHtml(sentence)
            }
        }
        Marker.SENTENCE_FURIGANA_PLAIN -> {
            val sentence = cloze?.sentence ?: ""
            val session = Injekt.get<DictionaryRepository>().lookupSession
            if (sentence.isNotEmpty() && session != null) {
                buildSentenceFuriganaPlain(sentence, session)
            } else {
                sentence
            }
        }
        Marker.CLOZE_PREFIX -> cloze?.let { escapeHtml(it.prefix) } ?: ""
        Marker.CLOZE_BODY -> cloze?.let { escapeHtml(it.body) } ?: ""
        Marker.CLOZE_BODY_KANA -> cloze?.let { escapeHtml(it.bodyKana) } ?: ""
        Marker.CLOZE_SUFFIX -> cloze?.let { escapeHtml(it.suffix) } ?: ""
        Marker.TAGS -> buildTags(result)
        Marker.PART_OF_SPEECH -> buildPartOfSpeech(result)
        Marker.CONJUGATION -> buildConjugation(result)
        Marker.DICTIONARY -> result.term.glossaries.firstOrNull()?.let { escapeHtml(it.dictName) } ?: ""
        Marker.DICTIONARY_ALIAS -> result.term.glossaries.firstOrNull()?.let { escapeHtml(it.dictName) } ?: ""
        Marker.FREQUENCIES -> buildFrequenciesList(result)
        Marker.FREQUENCY_LOWEST -> selectLowestFrequencyValue(result) ?: ""
        Marker.FREQUENCY_HARMONIC_RANK -> buildFrequencyHarmonicRank(result)
        Marker.FREQUENCY_AVERAGE_RANK -> buildFrequencyAverageRank(result)
        Marker.PITCH_ACCENTS -> buildPitchAccents(result.term.reading, result.term.pitches, format = PitchFormat.OVERLINE)
        Marker.PITCH_ACCENT_POSITIONS -> buildPitchAccentPositions(result.term.pitches)
        Marker.PITCH_ACCENT_CATEGORIES -> buildPitchCategories(result.term.reading, result.term.rules, result.term.pitches)
        Marker.PITCH_ACCENT_COMPOSITE -> buildPitchAccents(result.term.reading, result.term.pitches, format = PitchFormat.COMPOSITE)
        Marker.WORD_AUDIO -> wordAudioFilename?.let { "[sound:$it]" } ?: ""
        Marker.SENTENCE_AUDIO -> sentenceAudioFilename?.let { "[sound:$it]" } ?: ""
        Marker.MORAE -> buildMorae(result.term.reading)
        Marker.PITCH_ACCENT_GRAPHS, Marker.PITCH_ACCENT_GRAPHS_JJ -> buildPitchAccentGraphs(result.term.reading, result.term.pitches)
        Marker.SCREENSHOT -> screenshotFilename?.let { "<img src=\"$it\">" } ?: ""
        Marker.SEARCH_QUERY -> escapeHtml(result.term.expression)
        Marker.URL -> ""
        Marker.MEDIA_NAME -> media?.mangaTitle?.let { escapeHtml(it) } ?: ""
        Marker.CHAPTER -> media?.chapterName?.let { escapeHtml(it) } ?: ""
        Marker.MEDIA -> {
            if (media != null && media.mangaTitle.isNotBlank()) {
                if (media.chapterName.isNotBlank()) {
                    "${escapeHtml(media.mangaTitle)}-${escapeHtml(media.chapterName)}"
                } else {
                    escapeHtml(media.mangaTitle)
                }
            } else {
                ""
            }
        }
        Marker.POPUP_SELECTION_TEXT -> popupSelection?.let { escapeHtmlWithLineBreaks(it) } ?: ""
        Marker.SELECTED_GLOSSARY -> {
            val selected = selectedDict?.takeIf { it.isNotBlank() }
            if (selected != null) {
                buildGlossary(
                    result.term.glossaries,
                    brief = false,
                    noDictTag = false,
                    firstOnly = false,
                    dictionaryFilter = selected,
                    styles = styles,
                    exportMedia = exportMedia,
                )
            } else {
                renderMarker(Marker.GLOSSARY_FIRST, result, glossaryIndex = glossaryIndex, styles = styles, exportMedia = exportMedia)
            }
        }
        Marker.SELECTED_GLOSSARY_NO_FALLBACK -> {
            val selected = selectedDict?.takeIf { it.isNotBlank() }
            if (selected != null) {
                buildGlossary(
                    result.term.glossaries,
                    brief = false,
                    noDictTag = false,
                    firstOnly = false,
                    dictionaryFilter = selected,
                    styles = styles,
                    exportMedia = exportMedia,
                )
            } else {
                ""
            }
        }
        else -> parseDynamicMarker(marker, result, styles, exportMedia)
    }

    // =============================================================================
    // Dynamic marker parsing (Yomitan-style)
    // =============================================================================

    private fun parseDynamicMarker(
        marker: String,
        result: LookupResult,
        styles: List<DictionaryStyle>,
        exportMedia: ExportMediaContext?,
    ): String {
        return parseSingleGlossaryMarker(marker, result, styles, exportMedia)
            ?: parseSingleFrequencyMarker(marker, result)
            ?: ""
    }

    private fun parseSingleGlossaryMarker(
        marker: String,
        result: LookupResult,
        styles: List<DictionaryStyle>,
        exportMedia: ExportMediaContext?,
    ): String? {
        val prefix = "single-glossary-"
        if (!marker.startsWith(prefix)) return null

        val rest = marker.substring(prefix.length)
        if (rest.isBlank()) return ""

        val tokens = rest.split("-").toMutableList()
        var hasBrief = false
        var hasFirst = false
        var hasPlain = false
        var noDictTag = false
        while (tokens.isNotEmpty()) {
            val suffix = tokens.last().lowercase()
            when {
                suffix == "brief" -> {
                    hasBrief = true
                    tokens.removeAt(tokens.lastIndex)
                }
                suffix == "first" -> {
                    hasFirst = true
                    tokens.removeAt(tokens.lastIndex)
                }
                suffix == "plain" -> {
                    hasPlain = true
                    tokens.removeAt(tokens.lastIndex)
                }
                tokens.size >= 2 &&
                    tokens[tokens.lastIndex - 1].equals("no", ignoreCase = true) &&
                    suffix == "dictionary" -> {
                    noDictTag = true
                    tokens.removeAt(tokens.lastIndex)
                    tokens.removeAt(tokens.lastIndex)
                }
                else -> break
            }
        }

        val dictName = tokens.joinToString("-").trim()
        if (dictName.isEmpty()) return ""
        val dictionaryFilter = dictName.takeUnless { it.equals("all", ignoreCase = true) }

        if (hasPlain) {
            val filtered = if (dictionaryFilter == null) {
                result.term.glossaries
            } else {
                result.term.glossaries.filter { it.dictName.contains(dictionaryFilter, ignoreCase = true) }.toTypedArray()
            }
            val entries = if (hasFirst) firstDictionaryGlossaries(filtered) else filtered
            return buildGlossaryPlain(entries, noDictTag = noDictTag)
        }

        return buildGlossary(
            result.term.glossaries,
            brief = hasBrief,
            noDictTag = noDictTag,
            firstOnly = hasFirst,
            dictionaryFilter = dictionaryFilter,
            styles = styles,
            exportMedia = exportMedia,
        )
    }

    private fun parseSingleFrequencyMarker(marker: String, result: LookupResult): String? {
        return when {
            marker.startsWith("single-frequency-number-") -> {
                val dictionaryKey = marker.removePrefix("single-frequency-number-")
                buildSingleFrequencyNumber(result, dictionaryKey)
            }
            marker.startsWith("single-frequency-") -> {
                val dictionaryKey = marker.removePrefix("single-frequency-")
                buildFrequenciesList(result, dictionaryKey)
            }
            else -> null
        }
    }

    // =============================================================================
    // Furigana
    // =============================================================================

    fun buildFuriganaHtml(expression: String, reading: String): String {
        if (expression.isBlank()) return ""
        if (reading.isBlank() || expression == reading) return escapeHtml(expression)
        val segments = distributeFurigana(expression, reading)
        return segments.joinToString("") { (text, furigana) ->
            if (furigana.isNotEmpty()) {
                "<ruby>${escapeHtml(text)}<rt>${escapeHtml(furigana)}</rt></ruby>"
            } else {
                escapeHtml(text)
            }
        }
    }

    fun buildFuriganaPlain(expression: String, reading: String): String {
        if (expression.isBlank()) return ""
        if (reading.isBlank() || expression == reading) return expression
        val segments = distributeFurigana(expression, reading)
        return buildString {
            for ((text, furigana) in segments) {
                if (furigana.isNotEmpty()) append(text).append('[').append(furigana).append(']')
                else append(text).append(' ')
            }
        }
    }

    private data class FuriganaGroup(val isKanji: Boolean, val text: String, val normalized: String?)

    private fun distributeFurigana(expression: String, reading: String): List<Pair<String, String>> {
        if (reading == expression) return listOf(expression to "")
        val groups = mutableListOf<FuriganaGroup>()
        for (char in expression) {
            val kanji = isKanji(char)
            if (groups.lastOrNull()?.isKanji == kanji) {
                val previous = groups.removeAt(groups.lastIndex)
                val text = previous.text + char
                groups += FuriganaGroup(kanji, text, if (!kanji) toHiragana(text) else null)
            } else {
                groups += FuriganaGroup(kanji, char.toString(), if (!kanji) toHiragana(char.toString()) else null)
            }
        }
        return segmentizeFurigana(reading, toHiragana(reading), groups, 0)
            ?: listOf(expression to reading)
    }

    private fun segmentizeFurigana(
        reading: String,
        readingNormalized: String,
        groups: List<FuriganaGroup>,
        start: Int,
    ): List<Pair<String, String>>? {
        if (start >= groups.size) return if (reading.isEmpty()) emptyList() else null
        val group = groups[start]
        if (!group.isKanji) {
            if (group.normalized == null || !readingNormalized.startsWith(group.normalized)) return null
            val tail = segmentizeFurigana(
                reading.substring(group.text.length),
                readingNormalized.substring(group.text.length),
                groups,
                start + 1,
            )?.toMutableList() ?: return null
            if (reading.startsWith(group.text)) {
                tail.add(0, group.text to "")
            } else {
                tail.addAll(0, getFuriganaKanaSegments(group.text, reading))
            }
            return tail
        }

        var result: List<Pair<String, String>>? = null
        for (i in reading.length downTo group.text.length) {
            val tail = segmentizeFurigana(reading.substring(i), readingNormalized.substring(i), groups, start + 1)
                ?: continue
            if (result != null) return null
            result = tail.toMutableList().also { it.add(0, group.text to reading.substring(0, i)) }
            if (groups.size - start == 1) break
        }
        return result
    }

    private fun getFuriganaKanaSegments(text: String, reading: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var start = 0
        var state = reading.firstOrNull() == text.firstOrNull()
        for (i in 1 until text.length) {
            val nextState = reading.getOrNull(i) == text[i]
            if (nextState != state) {
                result += text.substring(start, i) to if (state) "" else reading.substring(start, i.coerceAtMost(reading.length))
                start = i
                state = nextState
            }
        }
        result += text.substring(start) to if (state) "" else reading.substring(start.coerceAtMost(reading.length))
        return result
    }

    private fun isKanji(ch: Char) =
        ch.code in 0x4E00..0x9FFF ||
            ch.code in 0x3400..0x4DBF ||
            ch.code in 0xF900..0xFAFF ||
            ch == '\u3005'

    private fun toHiragana(str: String) = str.map { ch ->
        if (ch.code in 0x30A1..0x30F6) (ch.code - 0x60).toChar() else ch
    }.joinToString("")

    private fun normalizeAnkiTag(value: String): String? = value.trim()
        .replace(Regex("\\s+"), "_")
        .takeIf { it.isNotBlank() }

    // =============================================================================
    // Sentence Furigana
    // =============================================================================

    private fun buildSentenceFuriganaHtml(sentence: String, session: Long): String {
        if (sentence.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < sentence.length) {
            val suffix = sentence.substring(i)
            val results = HoshiDicts.lookup(session, suffix, 20, 25).toList()
            val best = results
                .filter { suffix.startsWith(it.matched) }
                .maxByOrNull { it.matched.length }
            if (best != null) {
                sb.append(buildFuriganaHtml(best.matched, best.term.reading))
                i += best.matched.length
            } else {
                sb.append(escapeHtml(sentence[i].toString()))
                i++
            }
        }
        return sb.toString()
    }

    private fun buildSentenceFuriganaPlain(sentence: String, session: Long): String {
        if (sentence.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < sentence.length) {
            val suffix = sentence.substring(i)
            val results = HoshiDicts.lookup(session, suffix, 20, 25).toList()
            val best = results
                .filter { suffix.startsWith(it.matched) }
                .maxByOrNull { it.matched.length }
            if (best != null) {
                sb.append(buildFuriganaPlain(best.matched, best.term.reading))
                i += best.matched.length
            } else {
                sb.append(sentence[i])
                i++
            }
        }
        return sb.toString()
    }

    // =============================================================================
    // Glossary
    // =============================================================================

    private fun buildGlossary(
        glossaries: Array<GlossaryEntry>,
        brief: Boolean,
        noDictTag: Boolean,
        firstOnly: Boolean,
        dictionaryFilter: String? = null,
        styles: List<DictionaryStyle> = emptyList(),
        exportMedia: ExportMediaContext? = null,
    ): String {
        if (glossaries.isEmpty()) return ""

        val filteredGlossaries = if (dictionaryFilter != null) {
            glossaries.filter { it.dictName.contains(dictionaryFilter, ignoreCase = true) }.toTypedArray()
        } else {
            glossaries
        }

        if (filteredGlossaries.isEmpty()) return ""
        val entries = if (firstOnly) firstDictionaryGlossaries(filteredGlossaries) else filteredGlossaries
        val scopedStyles = buildScopedDictionaryStyles(entries.map { it.dictName }.toSet(), styles)

        val sb = StringBuilder()
        sb.append("""<div style="text-align: left;" class="yomitan-glossary">""")
        sb.append("<ol>")
        var previousDictName = ""
        for (entry in entries) {
            val dictAttr = attrEscape(entry.dictName)
            sb.append("""<li data-dictionary="$dictAttr">""")
            sb.append(renderGlossarySingle(entry, brief, noDictTag, previousDictName, exportMedia))
            sb.append("</li>")
            previousDictName = entry.dictName
        }
        sb.append("</ol>")
        sb.append("</div>")
        sb.append("<style>")
        val lists = ".yomitan-glossary .glossary-list, .yomitan-glossary ul[data-sc-content=\"glossary\"]"
        sb.append("$lists { display: inline; list-style: none; padding-left: 0; }")
        val items = ".yomitan-glossary .glossary-list > li, .yomitan-glossary ul[data-sc-content=\"glossary\"] > li"
        sb.append("$items { display: inline; }")
        sb.append(".yomitan-glossary .glossary-list > li:not(:first-child)::before, ")
        sb.append(".yomitan-glossary ul[data-sc-content=\"glossary\"] > li:not(:first-child)::before ")
        sb.append("{ content: \" | \"; white-space: pre-wrap; color: #777; }")
        sb.append(scopedStyles)
        sb.append("</style>")
        return sb.toString()
    }

    // A dictionary can supply several senses for the same term (for example JMnedict names).
    private fun firstDictionaryGlossaries(entries: Array<GlossaryEntry>): Array<GlossaryEntry> =
        entries.firstOrNull()?.dictName?.let { dictionary -> entries.filter { it.dictName == dictionary }.toTypedArray() }
            ?: emptyArray()

    private fun buildScopedDictionaryStyles(
        dictionaryNames: Set<String>,
        styles: List<DictionaryStyle>,
    ): String {
        if (dictionaryNames.isEmpty() || styles.isEmpty()) return ""
        return styles
            .asSequence()
            .filter { it.dictName in dictionaryNames && it.styles.isNotBlank() }
            .joinToString("\n") { style ->
                scopeDictionaryCss(
                    css = sanitizeDictionaryCss(style.styles),
                    scopeSelector = """[data-dictionary="${cssDoubleQuotedContent(style.dictName)}"]""",
                )
            }
            .trim()
    }

    private fun sanitizeDictionaryCss(css: String): String = css
        .replace(Regex("(?is)<\\s*/?\\s*style[^>]*>"), "")
        .replace(Regex("(?is)@import\\s+[^;]+;"), "")
        .replace(Regex("(?is)url\\s*\\(\\s*javascript:[^)]+\\)"), "")
        .trim()

    private fun scopeDictionaryCss(
        css: String,
        scopeSelector: String,
        parentSelectors: List<String> = emptyList(),
    ): String {
        if (css.isBlank()) return ""
        if (!css.contains("{")) return "$scopeSelector { $css }"

        val out = StringBuilder()
        var i = 0
        while (i < css.length) {
            while (i < css.length && css[i].isWhitespace()) i++
            if (i >= css.length) break

            if (css.startsWith("/*", i)) {
                val end = css.indexOf("*/", i + 2)
                i = if (end >= 0) end + 2 else css.length
                continue
            }

            val preludeStart = i
            while (i < css.length && css[i] != '{' && css[i] != ';') i++
            if (i >= css.length) break

            if (css[i] == ';') {
                i++
                continue
            }

            val prelude = css.substring(preludeStart, i).trim()
            val bodyStart = i + 1
            val bodyEnd = findCssBlockEnd(css, i)
            if (bodyEnd <= bodyStart) break
            val body = css.substring(bodyStart, bodyEnd).trim()

            when {
                prelude.startsWith("@media", ignoreCase = true) ||
                    prelude.startsWith("@supports", ignoreCase = true) ||
                    prelude.startsWith("@layer", ignoreCase = true) -> {
                    val scopedBody = scopeDictionaryCss(body, scopeSelector, parentSelectors)
                    if (scopedBody.isNotBlank()) {
                        out.append(prelude).append(" {\n").append(scopedBody).append("\n}\n")
                    }
                }
                prelude.startsWith("@font-face", ignoreCase = true) ||
                    prelude.startsWith("@keyframes", ignoreCase = true) ||
                    prelude.startsWith("@-", ignoreCase = true) -> {
                    out.append(prelude).append(" { ").append(body).append(" }\n")
                }
                prelude.startsWith("@", ignoreCase = true) -> {
                    out.append(prelude).append(" { ").append(body).append(" }\n")
                }
                else -> {
                    val selectors = if (parentSelectors.isNotEmpty()) {
                        splitCssSelectorList(prelude).flatMap { selector ->
                            parentSelectors.map { parent -> combineNestedCssSelector(selector.trim(), parent) }
                        }
                    } else {
                        splitCssSelectorList(prelude).map { prefixCssSelector(it.trim(), scopeSelector) }
                    }
                        .filter { it.isNotBlank() }
                    if (selectors.isNotEmpty()) {
                        val (declarations, nestedRules) = splitCssDeclarationsAndNestedRules(body)
                        if (declarations.isNotBlank()) {
                            out.append(selectors.joinToString(", "))
                                .append(" { ")
                                .append(declarations)
                                .append(" }\n")
                        }
                        if (nestedRules.isNotBlank()) {
                            val scopedNestedRules = scopeDictionaryCss(nestedRules, scopeSelector, selectors)
                            if (scopedNestedRules.isNotBlank()) {
                                out.append(scopedNestedRules).append("\n")
                            }
                        }
                    }
                }
            }

            i = bodyEnd + 1
        }
        return out.toString().trim()
    }

    private fun findCssBlockEnd(css: String, openBraceIndex: Int): Int {
        var depth = 0
        var i = openBraceIndex
        while (i < css.length) {
            when (css[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return css.length
    }

    private fun splitCssSelectorList(selectorText: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var parenDepth = 0
        var bracketDepth = 0
        var quote: Char? = null

        selectorLoop@ for (i in selectorText.indices) {
            val ch = selectorText[i]
            val prev = selectorText.getOrNull(i - 1)

            if (quote != null) {
                current.append(ch)
                if (ch == quote && prev != '\\') quote = null
                continue
            }

            if (ch == '"' || ch == '\'') {
                quote = ch
                current.append(ch)
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                ',' -> if (parenDepth == 0 && bracketDepth == 0) {
                    current.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }
                    current.clear()
                    continue@selectorLoop
                }
            }

            current.append(ch)
        }

        current.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    private fun findTopLevelCssChar(text: String, charToFind: Char, startIndex: Int): Int {
        var quote: Char? = null
        var parenDepth = 0
        var bracketDepth = 0

        var i = startIndex
        while (i < text.length) {
            val ch = text[i]
            val prev = text.getOrNull(i - 1)

            if (quote != null) {
                if (ch == quote && prev != '\\') quote = null
                i++
                continue
            }

            if (ch == '"' || ch == '\'') {
                quote = ch
                i++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> parenDepth = (parenDepth - 1).coerceAtLeast(0)
                '[' -> bracketDepth++
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
            }

            if (ch == charToFind && parenDepth == 0 && bracketDepth == 0) return i
            i++
        }

        return -1
    }

    private fun splitCssDeclarationsAndNestedRules(blockContent: String): Pair<String, String> {
        val declarations = StringBuilder()
        val nestedRules = StringBuilder()
        var i = 0

        while (i < blockContent.length) {
            while (i < blockContent.length && blockContent[i].isWhitespace()) i++
            if (i >= blockContent.length) break

            val nextSemi = findTopLevelCssChar(blockContent, ';', i)
            val nextBrace = findTopLevelCssChar(blockContent, '{', i)

            if (nextBrace >= 0 && (nextSemi < 0 || nextBrace < nextSemi)) {
                val closeBrace = findCssBlockEnd(blockContent, nextBrace)
                if (closeBrace <= nextBrace) {
                    declarations.append(blockContent.substring(i))
                    break
                }
                nestedRules.append(blockContent.substring(i, closeBrace + 1)).append('\n')
                i = closeBrace + 1
                continue
            }

            if (nextSemi >= 0) {
                declarations.append(blockContent.substring(i, nextSemi + 1)).append('\n')
                i = nextSemi + 1
            } else {
                declarations.append(blockContent.substring(i))
                break
            }
        }

        return declarations.toString().trim() to nestedRules.toString().trim()
    }

    private fun combineNestedCssSelector(selector: String, parentSelector: String): String {
        if (selector.isBlank()) return ""
        return if (selector.contains("&")) {
            selector.replace("&", parentSelector)
        } else {
            "$parentSelector $selector"
        }
    }

    private fun prefixCssSelector(selector: String, scopeSelector: String): String {
        if (selector.isBlank()) return ""
        if (selector.contains("&")) {
            return selector.replace("&", scopeSelector)
        }
        if (selector.equals(":root", ignoreCase = true) ||
            selector.equals("html", ignoreCase = true) ||
            selector.equals("body", ignoreCase = true)
        ) {
            return scopeSelector
        }
        val rootWithAttr = Regex("""(?i)^(:root|html|body)(\[[^\]]+\])([\s\S]*)$""").find(selector)
        if (rootWithAttr != null) {
            val head = rootWithAttr.groupValues[1]
            val attr = rootWithAttr.groupValues[2]
            val tail = rootWithAttr.groupValues[3]
            return if (tail.isBlank()) {
                "$head$attr $scopeSelector"
            } else {
                "$head$attr $scopeSelector$tail"
            }
        }
        val rootDesc = Regex("""(?i)^(:root|html|body)\s+(.+)$""").find(selector)
        if (rootDesc != null) {
            return "$scopeSelector ${rootDesc.groupValues[2]}"
        }
        return "$scopeSelector $selector"
    }

    private fun renderGlossarySingle(
        entry: GlossaryEntry,
        brief: Boolean,
        noDictTag: Boolean,
        previousDictName: String = "",
        exportMedia: ExportMediaContext? = null,
    ): String {
        val sb = StringBuilder()

        if (!brief) {
            val tagParts = mutableListOf<String>()
            if (entry.definitionTags.isNotBlank()) {
                tagParts += entry.definitionTags.split(" ").filter { it.isNotBlank() }
            }
            if (!noDictTag && entry.dictName.isNotBlank() && entry.dictName != previousDictName) {
                tagParts += entry.dictName
            }
            if (tagParts.isNotEmpty()) {
                sb.append("<i>(${tagParts.joinToString(", ")})</i> ")
            }
        }

        val content = glossaryToHtml(entry.glossary, entry.dictName, exportMedia)
        if (content.isNotEmpty()) sb.append(content)

        return sb.toString()
    }

    private fun buildGlossaryPlain(glossaries: Array<GlossaryEntry>, noDictTag: Boolean): String {
        if (glossaries.isEmpty()) return ""
        return glossaries.joinToString("<br>") { entry ->
            buildString {
                if (!noDictTag && entry.dictName.isNotBlank()) {
                    append("(${escapeHtml(entry.dictName)})<br>")
                }
                append(glossaryToPlainText(entry.glossary))
            }
        }
    }

    // =============================================================================
    // Structured-content → HTML
    // =============================================================================

    private fun normalizePlainText(text: String): String = text
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\\n", "\n")

    private fun escapeHtmlWithLineBreaks(text: String): String = escapeHtml(normalizePlainText(text))
        .replace("\n", "<br>")

    private fun glossaryToHtml(raw: String, dictionary: String = "", exportMedia: ExportMediaContext? = null): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return escapeHtmlWithLineBreaks(trimmed)
        }
        return try {
            when {
                trimmed.startsWith("[") -> arrayToHtml(JSONArray(trimmed), dictionary, "div", exportMedia)
                else -> objectToHtml(JSONObject(trimmed), dictionary, exportMedia)
            }
        } catch (_: Exception) {
            escapeHtmlWithLineBreaks(trimmed)
        }
    }

    private fun glossaryToPlainText(raw: String): String {
        val html = glossaryToHtml(raw)
        val text = html
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|li|div|tr|h[1-6])>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
        .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&amp;", "&").replace("&quot;", "\"")
        return text
            .split('\n')
            .joinToString("\n") { line -> line.replace(Regex("[ \\t\\u000B\\u000C]+"), " ").trim() }
            .trim()
    }

    private fun arrayToHtml(arr: JSONArray, dictionary: String, parentTag: String, exportMedia: ExportMediaContext?): String {
        val allStrings = (0 until arr.length()).all { arr.get(it) is String }
        if (allStrings) {
            if (arr.length() == 1) return contentValueToHtml(arr.getString(0), dictionary, parentTag, exportMedia)
            
            // Only auto-wrap in UL if we are NOT already in a list-like tag
            val isListParent = parentTag == "ul" || parentTag == "ol" || parentTag == "li" || parentTag == "span" || parentTag == "td" || parentTag == "th"
            if (!isListParent) {
                return """<ul class="glossary-list">""" + (0 until arr.length()).joinToString("") {
                    "<li>${contentValueToHtml(arr.getString(it), dictionary, "li", exportMedia)}</li>"
                } + "</ul>"
            }
        }

        return (0 until arr.length()).joinToString("") { i ->
            contentValueToHtml(arr.get(i), dictionary, parentTag, exportMedia)
        }
    }

    private fun objectToHtml(node: JSONObject, dictionary: String, exportMedia: ExportMediaContext?): String {
        val type = node.optString("type", "")
        if (type == "ruby") {
            val expression = node.optString("headword", "")
            val reading = node.optString("reading", "")
            return if (reading.isNotEmpty() && reading != expression) {
                "<ruby>$expression<rt>$reading</rt></ruby>"
            } else {
                expression
            }
        }

        if (type == "structured-content") {
            val content = node.opt("content") ?: return ""
            return """<span class="structured-content">${contentValueToHtml(content, dictionary, "span", exportMedia)}</span>"""
        }

        if (type == "image") {
            return imageNodeToHtml(node, dictionary, exportMedia)
        }

        val tag = node.optString("tag", "").trim().lowercase()

        if (tag.isEmpty()) {
            val content = node.opt("content") ?: return ""
            return contentValueToHtml(content, dictionary, "div", exportMedia)
        }

        val dataObj = node.optJSONObject("data")
        if (dataObj != null && dataObj.optString("content") == "attribution") return ""

        if (tag.lowercase() == "img") return imageNodeToHtml(node, dictionary, exportMedia)

        if (tag == "br") return "<br>"
        if (tag == "hr") return "<hr>"

        val sb = StringBuilder("<$tag")
        val classParts = mutableListOf("gloss-sc-$tag")
        val dataAttributes = StringBuilder()

        node.optString("href", "").takeIf { it.isNotEmpty() }?.let { sb.append(""" href="${attrEscape(it)}"""") }
        node.optString("title", "").takeIf { it.isNotEmpty() }?.let { sb.append(""" title="${attrEscape(it)}"""") }
        node.optString("lang", "").takeIf { it.isNotEmpty() }?.let { sb.append(""" lang="${attrEscape(it)}"""") }
        node.optString("id", "").takeIf { it.isNotEmpty() }?.let { sb.append(""" id="${attrEscape(it)}"""") }
        if (tag == "td" || tag == "th") {
            node.optInt("colSpan", 0).takeIf { it > 0 }?.let { sb.append(""" colspan="$it"""") }
            node.optInt("rowSpan", 0).takeIf { it > 0 }?.let { sb.append(""" rowspan="$it"""") }
        }
        if (tag == "details" && node.optBoolean("open", false)) {
            sb.append(" open")
        }

        if (dataObj != null) {
            for (key in dataObj.keys()) {
                val rawValue = dataObj.get(key)
                if (rawValue == JSONObject.NULL) continue
                val value = rawValue.toString()
                if (key == "class") {
                    classParts += value.split(" ").filter { it.isNotBlank() }
                    dataAttributes.append(""" data-sc-class="${attrEscape(value)}"""")
                } else {
                    dataAttributes.append(""" ${structuredDataAttributeName(key)}="${attrEscape(value)}"""")
                }
            }
        }
        sb.append(""" class="${attrEscape(classParts.joinToString(" "))}"""")
        sb.append(dataAttributes)

        val styleObj = node.optJSONObject("style")
        if (styleObj != null && styleObj.length() > 0) {
            val cssStr = styleObj.keys().asSequence()
                .mapNotNull { prop ->
                    val rawValue = styleObj.get(prop)
                    if (rawValue == JSONObject.NULL) return@mapNotNull null
                    val value = structuredStyleValue(prop, rawValue)
                    if (value.isBlank()) null else "${camelToKebab(prop)}: ${attrEscape(value)}"
                }
                .joinToString("; ")
            if (cssStr.isNotBlank()) sb.append(""" style="$cssStr"""")
        } else {
            // Apply compact default styles for common block tags in Anki.
            // Avoid inline defaults for table elements — dictionary CSS
            // (included as scoped <style>) would be overridden by inline styles.
            when (tag) {
                "ul", "ol" -> sb.append(""" style="margin: 0.2em 0; padding-left: 1.2em;"""")
                "p", "div" -> sb.append(""" style="margin: 0.1em 0;"""")
            }
        }

        sb.append(">")

        val content = node.opt("content")
        if (content != null) {
            if (tag == "table") {
                sb.append(tableContentToHtml(content, dictionary, exportMedia))
            } else {
                sb.append(contentValueToHtml(content, dictionary, tag, exportMedia))
            }
        }

        sb.append("</$tag>")

        return if (tag == "table") {
            """<div class="gloss-sc-table-container">$sb</div>"""
        } else {
            sb.toString()
        }
    }

    private fun contentValueToHtml(value: Any?, dictionary: String, parentTag: String, exportMedia: ExportMediaContext?): String = when (value) {
        null -> ""
        is String -> escapeHtmlWithLineBreaks(value)
        is Number -> value.toString()
        is Boolean -> value.toString()
        is JSONArray -> arrayToHtml(value, dictionary, parentTag, exportMedia)
        is JSONObject -> objectToHtml(value, dictionary, exportMedia)
        else -> value.toString()
    }

    private fun imageNodeToHtml(node: JSONObject, dictionary: String, exportMedia: ExportMediaContext?): String {
        val path = node.optString("path", "").ifEmpty { node.optString("src", "") }
        if (path.isEmpty()) return ""
        val renderPath = if (exportMedia != null && !isResolvableImagePath(path)) {
            exportMedia.placeholderFor(dictionary, path)
        } else {
            path
        }

        val sb = StringBuilder("<img")
        sb.append(""" src="${attrEscape(renderPath)}"""")
        
        node.optString("alt", "").takeIf { it.isNotEmpty() }?.let { sb.append(""" alt="${attrEscape(it)}"""") }
        node.optString("title", "").takeIf { it.isNotEmpty() }?.let { sb.append(""" title="${attrEscape(it)}"""") }

        val width = node.optInt("width", 0)
        val height = node.optInt("height", 0)
        val sizeUnits = node.optString("sizeUnits", "px")
        val appearance = node.optString("appearance", "")
        
        val styleParts = mutableListOf<String>()
        if (width > 0 || height > 0) {
            val cssWidth = if (width > 0) "${width}$sizeUnits" else "auto"
            val cssHeight = if (height > 0) "${height}$sizeUnits" else "auto"
            styleParts.add("width: $cssWidth")
            styleParts.add("height: $cssHeight")
        }
        
        styleParts.add("vertical-align: middle")
        
        if (appearance == "monochrome") {
            sb.append(""" class="gloss-image-monochrome"""")
            // Yomitan style masking: allows the image to inherit text color
            styleParts.add("background-color: currentColor")
            styleParts.add("-webkit-mask-image: url('${attrEscape(renderPath)}')")
            styleParts.add("-webkit-mask-repeat: no-repeat")
            styleParts.add("-webkit-mask-size: contain")
            styleParts.add("mask-image: url('${attrEscape(renderPath)}')")
            styleParts.add("mask-repeat: no-repeat")
            styleParts.add("mask-size: contain")
        }
        
        if (styleParts.isNotEmpty()) {
            sb.append(""" style="${styleParts.joinToString("; ")}"""")
        }

        sb.append(" />")
        return sb.toString()
    }

    private fun isResolvableImagePath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.startsWith("data:") ||
            lower.startsWith("http://") ||
            lower.startsWith("https://")
    }

    private fun tableContentToHtml(content: Any?, dictionary: String, exportMedia: ExportMediaContext?): String {
        val items: List<Any> = when (content) {
            is JSONArray -> (0 until content.length()).map { content.get(it) }
            else -> listOf(content ?: return "")
        }
        val hasSection = items.any {
            it is JSONObject && it.optString("tag") in listOf("tbody", "thead", "tfoot")
        }
        val hasBareRows = items.any { it is JSONObject && it.optString("tag") == "tr" }
        return if (hasBareRows && !hasSection) {
            val tbody = JSONObject().apply {
                put("tag", "tbody")
                put("content", JSONArray(items))
            }
            objectToHtml(tbody, dictionary, exportMedia)
        } else {
            contentValueToHtml(content, dictionary, "table", exportMedia)
        }
    }

    // =============================================================================
    // Tags, Part of Speech, Conjugation
    // =============================================================================

    private fun buildTags(result: LookupResult): String {
        val seen = linkedSetOf<String>()
        for (g in result.term.glossaries) {
            g.definitionTags.split(" ").filter { it.isNotBlank() }.forEach { seen.add(it) }
            g.termTags.split(" ").filter { it.isNotBlank() }.forEach { seen.add(it) }
        }
        return seen.joinToString(", ") { escapeHtml(it) }
    }

    private val POS_PRETTY = mapOf(
        "v1" to "Ichidan verb",
        "v5" to "Godan verb",
        "vk" to "Kuru verb",
        "vs" to "Suru verb",
        "vz" to "Zuru verb",
        "adj-i" to "I-adjective",
        "adj-na" to "Na-adjective",
        "n" to "Noun",
        "adv" to "Adverb",
        "prt" to "Particle",
        "exp" to "Expression",
        "conj" to "Conjunction",
        "pref" to "Prefix",
        "suf" to "Suffix",
        "aux-v" to "Auxiliary verb",
    )

    private fun buildPartOfSpeech(result: LookupResult): String {
        val seen = linkedSetOf<String>()
        result.term.rules
            .split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { rule -> seen.add(POS_PRETTY[rule.lowercase()] ?: rule) }
        return if (seen.isEmpty()) "Unknown" else seen.joinToString(", ") { escapeHtml(it) }
    }

    private fun buildConjugation(result: LookupResult): String {
        val rules = result.term.rules
        if (rules.isNullOrEmpty()) return ""
        return rules.split(" « ").joinToString(" « ") { escapeHtml(it) }
    }

    // =============================================================================
    // Frequencies
    // =============================================================================

    private fun buildFrequenciesList(result: LookupResult, dictionaryKey: String? = null): String {
        if (result.term.frequencies.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("<ul data-content=\"frequencies\">")
        var appended = false
        for (group in result.term.frequencies) {
            if (dictionaryKey != null && !matchesDynamicDictionaryName(group.dictName, dictionaryKey)) continue
            val dictAttr = attrEscape(group.dictName)
            for (freq in group.frequencies) {
                if (freq.value <= 0) continue
                appended = true
                val display = freq.displayValue.takeIf { it.isNotBlank() } ?: freq.value.toString()
                sb.append("<li data-dictionary=\"$dictAttr\">")
                if (dictionaryKey == null && group.dictName.isNotBlank()) {
                    sb.append("${escapeHtml(group.dictName)}: ")
                }
                sb.append(escapeHtml(display))
                sb.append("</li>")
            }
        }
        sb.append("</ul>")
        return if (appended) sb.toString() else ""
    }

    private fun buildSingleFrequencyNumber(result: LookupResult, dictionaryKey: String): String {
        if (dictionaryKey.isBlank()) return ""
        return result.term.frequencies
            .asSequence()
            .filter { group -> matchesDynamicDictionaryName(group.dictName, dictionaryKey) }
            .flatMap { group -> group.frequencies.asSequence() }
            .filter { frequency -> frequency.value > 0 }
            .map { frequency -> frequency.displayValue.takeIf { it.isNotBlank() } ?: frequency.value.toString() }
            .joinToString("<br>") { escapeHtml(it) }
    }

    private fun selectLowestFrequencyValue(result: LookupResult): String? {
        var bestValue: Int? = null
        var bestDisplayValue: String? = null

        for (group in result.term.frequencies) {
            for (frequency in group.frequencies) {
                if (frequency.value <= 0) continue
                if (bestValue == null || frequency.value < bestValue) {
                    bestValue = frequency.value
                    bestDisplayValue = frequency.displayValue.takeIf { it.isNotBlank() }
                }
            }
        }

        return bestDisplayValue ?: bestValue?.toString()
    }

    private fun buildFrequencyHarmonicRank(result: LookupResult): String {
        val numbers = collectFrequencyNumbers(result)
        if (numbers.isEmpty()) return "9999999"
        val total = numbers.sumOf { 1.0 / it }
        return Math.floor(numbers.size / total).toInt().toString()
    }

    private fun buildFrequencyAverageRank(result: LookupResult): String {
        val numbers = collectFrequencyNumbers(result)
        if (numbers.isEmpty()) return "9999999"
        return (numbers.sum() / numbers.size).toString()
    }

    private fun collectFrequencyNumbers(result: LookupResult): List<Double> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<Double>()
        for (group in result.term.frequencies) {
            if (!seen.add(group.dictName)) continue
            for (f in group.frequencies) {
                val v = f.value
                if (v > 0) {
                    out.add(v.toDouble())
                    break
                }
            }
        }
        return out
    }

    private fun matchesDynamicDictionaryName(dictionaryName: String, markerDictionaryKey: String): Boolean {
        val normalizedKey = yomitanKebabCase(markerDictionaryKey)
        return normalizedKey.isNotBlank() &&
            (yomitanKebabCase(dictionaryName) == normalizedKey || dictionaryName.equals(markerDictionaryKey, ignoreCase = true))
    }

    // =============================================================================
    // Pitch accent
    // =============================================================================

    private enum class PitchFormat { TEXT, POSITION, SVG, OVERLINE, COMPOSITE }

    private fun buildPitchAccentPositions(pitches: Array<PitchEntry>): String {
        val positions = pitches.flatMap { group -> group.pitchPositions.toList() }
        if (positions.isEmpty()) return ""
        return if (positions.size == 1) {
            renderPitchPosition(positions.first())
        } else {
            positions.joinToString(prefix = "<ol>", postfix = "</ol>", separator = "") { position ->
                "<li>${renderPitchPosition(position)}</li>"
            }
        }
    }

    private fun renderPitchPosition(position: Int): String {
        val escapedPosition = escapeHtml(position.toString())
        val attrPosition = attrEscape(position.toString())
        return """<span class="pronunciation-downstep-notation" data-downstep-position="$attrPosition" style="display:inline;"><span class="pronunciation-downstep-notation-prefix">[</span><span class="pronunciation-downstep-notation-number">$escapedPosition</span><span class="pronunciation-downstep-notation-suffix">]</span></span>"""
    }

    private fun buildPitchAccents(reading: String, pitches: Array<PitchEntry>, format: PitchFormat): String {
        if (pitches.isEmpty()) return ""

        val allPitches = pitches.flatMap { group ->
            group.pitchPositions.map { pos -> Triple(group.dictName, pos, reading) }
        }
        if (allPitches.isEmpty()) return ""

        return if (allPitches.size == 1) {
            val (dict, pos, read) = allPitches[0]
            renderPitchItem(dict, pos, read, format)
        } else {
            val sb = StringBuilder()
            sb.append("<ol>")
            for ((dict, pos, read) in allPitches) {
                sb.append("<li>")
                sb.append(renderPitchItem(dict, pos, read, format))
                sb.append("</li>")
            }
            sb.append("</ol>")
            sb.toString()
        }
    }

    private fun renderPitchItem(dictName: String, position: Int, reading: String, format: PitchFormat): String {
        val prefix = if (dictName.isNotBlank()) "${escapeHtml(dictName)}: " else ""
        return when (format) {
            PitchFormat.POSITION -> "$prefix$position"
            PitchFormat.TEXT -> {
                val label = when (position) {
                    0 -> "平板 (heiban)"
                    1 -> "頭高 (atamadaka)"
                    else -> "中高/尾高 ($position)"
                }
                "$prefix$label"
            }
            PitchFormat.SVG -> {
                val morae = getMorae(reading)
                createPitchSvg(morae, position)
            }
            PitchFormat.OVERLINE -> {
                val morae = getMorae(reading)
                renderOverlineText(morae, position)
            }
            PitchFormat.COMPOSITE -> {
                val morae = getMorae(reading)
                val sb = StringBuilder()
                sb.append("<div class=\"pitch-accent-composite\" style=\"display: flex; align-items: center; gap: 0.5em; flex-wrap: wrap;\">")

                // 1. SVG (Graph)
                sb.append(createPitchSvg(morae, position))

                // 2. Overline Text
                sb.append(renderOverlineText(morae, position))

                // 3. Number [0]
                sb.append("<span class=\"pitch-number\" style=\"font-weight: bold;\">[$position]</span>")

                sb.append("</div>")
                "$prefix$sb"
            }
        }
    }

    private fun renderOverlineText(morae: List<String>, p: Int): String {
        val sb = StringBuilder()
        sb.append("""<span class="pronunciation" data-pronunciation-type="pitch-accent" data-pitch-accent-downstep-position="${attrEscape(p.toString())}">""")
        sb.append("""<span class="pronunciation-text" style="display:inline;">""")
        for (i in morae.indices) {
            val mora = morae[i]
            val highPitch = isMoraPitchHigh(i, p)
            val highPitchNext = isMoraPitchHigh(i + 1, p)
            val moraStyle = mutableListOf("display:inline-block", "position:relative")
            if (highPitch && !highPitchNext) {
                moraStyle.add("padding-right:0.1em")
                moraStyle.add("margin-right:0.1em")
            }
            sb.append(
                """<span class="pronunciation-mora" data-position="$i" data-pitch="${if (highPitch) "high" else "low"}" data-pitch-next="${if (highPitchNext) "high" else "low"}" style="${moraStyle.joinToString(";")}">""",
            )
            for (character in mora) {
                sb.append("""<span class="pronunciation-character" style="display:inline;">""")
                sb.append(escapeHtml(character.toString()))
                sb.append("</span>")
            }

            val lineStyle = mutableListOf("border-color:currentColor")
            if (highPitch) {
                lineStyle.add("display:block")
                lineStyle.add("user-select:none")
                lineStyle.add("pointer-events:none")
                lineStyle.add("position:absolute")
                lineStyle.add("top:0.1em")
                lineStyle.add("left:0")
                lineStyle.add("right:0")
                lineStyle.add("height:0")
                lineStyle.add("border-top-width:0.1em")
                lineStyle.add("border-top-style:solid")
            } else {
                lineStyle.add("display:none")
            }
            if (highPitch && !highPitchNext) {
                lineStyle.add("right:-0.1em")
                lineStyle.add("height:0.4em")
                lineStyle.add("border-right-width:0.1em")
                lineStyle.add("border-right-style:solid")
            }
            sb.append("""<span style="${lineStyle.joinToString(";")}"></span>""")
            sb.append("</span>")
        }
        sb.append("</span>")
        sb.append("</span>")
        return sb.toString()
    }

    private fun isMoraPitchHigh(moraIndex: Int, pitchAccentValue: Int): Boolean = when (pitchAccentValue) {
        0 -> moraIndex > 0
        1 -> moraIndex < 1
        else -> moraIndex > 0 && moraIndex < pitchAccentValue
    }

    private fun buildPitchCategories(reading: String, rules: String, pitches: Array<PitchEntry>): String {
        val categories = linkedSetOf<String>()
        val moraCount = getMorae(reading).size
        if (moraCount == 0) return ""
        val verbOrAdjective = isVerbOrAdjective(rules)

        for (group in pitches) {
            for (pos in group.pitchPositions) {
                val cat = pitchPositionToCategory(pos, moraCount, verbOrAdjective)
                if (cat.isNotEmpty()) categories.add(cat)
            }
        }
        return categories.joinToString(",")
    }

    private fun pitchPositionToCategory(position: Int, moraCount: Int, verbOrAdjective: Boolean): String = when {
        position == 0 -> "heiban"
        verbOrAdjective && position > 0 -> "kifuku"
        position == 1 -> "atamadaka"
        position == moraCount -> "odaka"
        position > 1 -> "nakadaka"
        else -> ""
    }

    private fun isVerbOrAdjective(rules: String): Boolean {
        return rules.split(Regex("""\s+"""))
            .map { it.trim().lowercase() }
            .any { rule ->
                rule.startsWith("v") ||
                    rule.startsWith("adj") ||
                    rule == "aux-v"
            }
    }

    // =============================================================================
    // HTML utilities
    // =============================================================================

    internal fun escapeHtml(text: String): String = text
        .replace("\u001F", "")
        .replace("\u0000", "")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun attrEscape(text: String): String = text
        .replace("\u001F", "")
        .replace("\u0000", "")
        .replace("&", "&amp;")
        .replace("\"", "&quot;")

    private fun cssDoubleQuotedContent(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\A ")
        .replace("\r", "")

    private fun structuredDataAttributeName(key: String): String {
        val kebab = key.replace(Regex("[A-Z]")) { match ->
            val lower = match.value.lowercase()
            if (match.range.first == 0) lower else "-$lower"
        }
        val prefix = if (kebab.firstOrNull()?.isStructuredDataCjkStart() == true) {
            "data-sc"
        } else {
            "data-sc-"
        }
        return "$prefix$kebab"
    }

    private fun Char.isStructuredDataCjkStart(): Boolean =
        this in '\u3000'..'\u9FFF' ||
            this in '\uF900'..'\uFAFF'

    private fun structuredStyleValue(prop: String, value: Any): String {
        if (value is JSONArray) {
            return (0 until value.length()).joinToString(" ") { index ->
                value.get(index).toString()
            }
        }
        if (value is Number) {
            return when (prop) {
                "marginTop", "marginLeft", "marginRight", "marginBottom" -> "${value}em"
                else -> value.toString()
            }
        }
        return value.toString()
    }

    private fun camelToKebab(name: String): String =
        name.replace(Regex("([A-Z])")) { "-${it.value.lowercase()}" }

    private fun yomitanKebabCase(name: String): String =
        name.trim()
            .replace(Regex("""[\s_\u3000]+"""), "-")
            .replace(Regex("""[^\p{L}\p{N}-]+"""), "")
            .replace(Regex("""--+"""), "-")
            .trim('-')
            .lowercase()

    private fun generateScreenshotFilename(bytes: ByteArray): String {
        val hash = try {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            val digest = md.digest(bytes)
            digest.joinToString("") { "%02x".format(it) }.take(12)
        } catch (e: Exception) {
            "screenshot_${System.currentTimeMillis()}"
        }
        val extension = if (isAvif(bytes)) "avif" else "webp"
        return "chimahon_$hash.$extension"
    }

    private fun isAvif(bytes: ByteArray): Boolean {
        // ftyp box: 4-byte size + "ftyp" at offset 4
        return bytes.size >= 12 &&
            bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte() &&
            bytes[8] == 'a'.code.toByte() &&
            bytes[9] == 'v'.code.toByte() &&
            bytes[10] == 'i'.code.toByte() &&
            bytes[11] == 's'.code.toByte()
    }

    private fun generateSentenceAudioFilename(bytes: ByteArray, extension: String): String {
        val hash = try {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            val digest = md.digest(bytes)
            digest.joinToString("") { "%02x".format(it) }.take(12)
        } catch (e: Exception) {
            "audio_${System.currentTimeMillis()}"
        }
        val safeExtension = extension
            .substringBefore('?')
            .substringAfterLast('.', extension)
            .replace(Regex("[^A-Za-z0-9]"), "")
            .ifBlank { "m4a" }
            .lowercase()
        return "chimahon_sentence_$hash.$safeExtension"
    }

    // =============================================================================
    // Pitch Accent / Morae
    // =============================================================================

    private fun buildMorae(reading: String): String {
        return getMorae(reading).joinToString(" ")
    }

    private fun getMorae(text: String): List<String> {
        val morae = mutableListOf<String>()
        val smallKana = setOf(
            'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', 'ゃ', 'ゅ', 'ょ', 'ゎ',
            'ァ', 'ィ', 'ゥ', 'ェ', 'ォ', 'ャ', 'ュ', 'ョ', 'ヮ', 'ヵ', 'ヶ',
        )
        for (char in text) {
            if (morae.isNotEmpty() && char in smallKana) {
                morae[morae.size - 1] = morae.last() + char
            } else {
                morae.add(char.toString())
            }
        }
        return morae
    }

    private fun buildPitchAccentGraphs(reading: String, pitches: Array<PitchEntry>): String {
        if (pitches.isEmpty() || reading.isEmpty()) return ""
        val morae = getMorae(reading)
        if (morae.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("""<div class="pitch-accent-graphs">""")
        for (group in pitches) {
            for (pos in group.pitchPositions) {
                sb.append(createPitchSvg(morae, pos))
            }
        }
        sb.append("</div>")
        return sb.toString()
    }

    private fun createPitchSvg(morae: List<String>, p: Int): String {
        val n = morae.size
        // Values based on ref/pronunciation-generator.js JJ mode
        val stepWidth = 35
        val marginLr = 16
        val height = 45 // Reduced height since text is removed
        val svgWidth = Math.max(0, ((n) * stepWidth) + (marginLr * 2))

        val points = mutableListOf<Point>()
        for (i in 0..n) {
            val isHigh = when {
                p == 1 -> i == 0
                p == 0 -> i > 0
                p >= 2 -> {
                    if (i == 0) {
                        false
                    } else if (i < p) {
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
            points.add(Point(marginLr + (i * stepWidth), if (isHigh) 10 else 35))
        }

        val svg = StringBuilder()
        val displayWidth = (svgWidth * 0.8).toInt() // Increased from 0.6 to 0.8
        svg.append("""<svg xmlns="http://www.w3.org/2000/svg" width="${displayWidth}px" height="40px" viewBox="0 0 $svgWidth $height" style="display: inline-block; vertical-align: middle;">""")

        val strokeColor = "currentColor"

        // 2. Draw Paths (Step 1 was text, now removed)
        if (points.size > 1) {
            var d = "M ${points[0].x} ${points[0].y}"
            for (i in 1 until points.size) {
                // JJ style uses simple lines, but let's stick to the path model
                d += " L ${points[i].x} ${points[i].y}"
            }
            svg.append("""<path d="$d" fill="none" stroke="$strokeColor" stroke-width="2" />""")
        }

        // 3. Draw Dots/Circles
        for (i in points.indices) {
            val pt = points[i]
            val isTail = i >= n
            if (isTail) {
                // Open circle for the tail (phonetic continuation)
                svg.append("""<circle cx="${pt.x}" cy="${pt.y}" r="4" fill="none" stroke="$strokeColor" stroke-width="2" />""")
            } else {
                svg.append("""<circle cx="${pt.x}" cy="${pt.y}" r="5" fill="$strokeColor" />""")
            }
        }

        svg.append("</svg>")
        return svg.toString()
    }

    private data class Point(val x: Int, val y: Int)
}
