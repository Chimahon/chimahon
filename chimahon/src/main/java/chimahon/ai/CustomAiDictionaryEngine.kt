package chimahon.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class AiNuanceInfo(
    val formality: String,
    val frequency: String,
    val dailyConversation: String,
)

data class SentenceChunk(
    val segment: String,
    val meaning: String,
)

data class GrammarPoint(
    val pattern: String,
    val explanation: String,
)

data class CustomAiDictionaryEntry(
    val word: String,
    val reading: String,
    val partOfSpeech: String,
    val contextualMeaning: String,
    val literalMeaning: String,
    val nuance: AiNuanceInfo,
    val alternativeWords: List<String>,
    val sentenceBreakdown: List<SentenceChunk>,
    val grammarBreakdown: List<GrammarPoint>,
    val exampleSentences: List<String> = emptyList(),
)

object CustomAiDictionaryEngine {

    private const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val GROQ_MODELS_ENDPOINT = "https://api.groq.com/openai/v1/models"
    private const val DEFAULT_MODEL_NAME = AiPreferences.DEFAULT_MODEL

    private val NON_CHAT_MODEL_KEYWORDS = listOf(
        "whisper",
        "tts",
        "playai",
        "orpheus",
        "guard",
        "moderation",
        "embedding",
    )

    private val EDGE_PUNCTUATION_REGEX = Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$")
    private val CJK_CLAUSE_SPLIT_REGEX = Regex("[。、！？!?「」『』（）【】〔〕［］｛｝〈〉《》…\\r\\n]")

    private fun parseGroqErrorMessage(rawResponse: String, responseCode: Int): String = try {
        JSONObject(rawResponse).optJSONObject("error")?.optString("message")
            ?.takeIf { it.isNotBlank() }
            ?: "HTTP $responseCode"
    } catch (_: Exception) {
        "HTTP $responseCode"
    }  /**
     * Normalizes the target lookup term when readers (such as the EPUB/novel WebView)
     * pass an entire trailing substring from the tapped offset to the end of the sentence.
     */
    fun extractTargetWord(
        rawLookup: String,
        sentence: String,
        charOffset: Int = -1,
        matchedTerm: String? = null,
    ): String {
        val raw = rawLookup.trim()
        if (raw.isEmpty()) return ""

        val cleanMatched = matchedTerm?.trim().orEmpty()
        if (cleanMatched.isNotEmpty() && raw.startsWith(cleanMatched, ignoreCase = true)) {
            return cleanMatched
        }

        val cleanSentence = sentence.trim()

        if (!raw.any { it.isWhitespace() }) {
            if (AiTtsLocaleHelper.JA_REGEX.containsMatchIn(raw)) {
                return raw.split(CJK_CLAUSE_SPLIT_REGEX, limit = 2).first().trim().ifEmpty { raw }
            }
            return raw.replace(EDGE_PUNCTUATION_REGEX, "").ifEmpty { raw }
        }

        val matchesAtOffset = charOffset in cleanSentence.indices &&
            cleanSentence.substring(charOffset).startsWith(raw, ignoreCase = true)
        val reachesSentenceEnd = cleanSentence.isNotEmpty() &&
            cleanSentence.endsWith(raw, ignoreCase = true)
        val isLongTailSlice = raw.split(Regex("\\s+")).size >= 3 &&
            cleanSentence.contains(raw, ignoreCase = true)

        if (cleanSentence.isEmpty() || matchesAtOffset || reachesSentenceEnd || isLongTailSlice) {
            val firstToken = raw.split(Regex("\\s+"), limit = 2).first().trim()
            return firstToken.replace(EDGE_PUNCTUATION_REGEX, "").ifEmpty { firstToken }
        }

        return raw
    }

    private fun buildSystemPrompt(languageMode: String): String {
        val languageGuidance = if (languageMode == AiPreferences.LANG_GLOBAL) {
            """
            Target Language Mode: Global Language (Auto-detect any source language such as Korean, Chinese, Spanish, French, German, Arabic, Russian, etc.).
            - Provide clear English explanations and translations.
            - For "reading", provide standard romanization, pinyin, phonetic spelling, or IPA as appropriate for the source language (empty string if standard Latin script with obvious pronunciation).
            - Break down morphological inflections, affixes, particles, or idiomatic structures specific to the detected language.
            """.trimIndent()
        } else {
            """
            Target Language Mode: English & Japanese (Main).
            - Focus specifically on Japanese and English vocabulary, nuance, and grammar.
            - For Japanese words, always provide accurate Hiragana/Katakana reading in "reading" (and dictionary base form in "literalMeaning" if conjugated), and explain particles, conjugations (e.g., ~ておく, ~ばかり, ~わけではない), and register (Casual, Spoken, Keigo/Polite, Literary, Slang).
            - For English words, provide IPA or phonetic stress in "reading" only when helpful, and highlight phrasal verbs, idioms, register, and natural conversational usage.
            """.trimIndent()
        }

        return """
            You are an expert bilingual dictionary and language tutor.
            $languageGuidance
            Analyze the target word strictly inside the provided sentence context.
            Respond ONLY with a valid, RFC-8259 compliant JSON object matching this exact schema (no markdown fences, no extra commentary, and properly escape all double quotes inside string values as \"):
            {
              "reading": "phonetic reading / furigana / IPA (empty string if identical to word)",
              "partOfSpeech": "Single base category ONLY, no parentheses or subtypes (e.g., Noun, Verb, Adjective, Adverb, Expression, Idiom)",
              "contextualMeaning": "Clear, natural definition matching how the word is used in this specific sentence",
              "literalMeaning": "Core/dictionary root meaning if different from contextualMeaning, otherwise empty string",
              "nuance": {
                "formality": "e.g., Casual / Colloquial / Polite / Formal / Literary / Slang",
                "frequency": "Short label ONLY, no extra context clauses (choose one: Very Common, Common, Uncommon, Rare)",
                "dailyConversation": "Brief note on whether native speakers use this in everyday speech and any subtle tone"
              },
              "alternativeWords": ["synonym 1 (brief nuance)", "synonym 2 (brief nuance)", "synonym 3"],
              "sentenceBreakdown": [
                {"segment": "chunk 1 of sentence", "meaning": "meaning & role of chunk 1"},
                {"segment": "chunk 2 of sentence", "meaning": "meaning & role of chunk 2"}
              ],
              "grammarBreakdown": [
                {"pattern": "grammatical structure, particle, or conjugation in sentence", "explanation": "concise explanation of how it functions here"}
              ],
              "exampleSentences": [
                "Natural example sentence 1 using the target word",
                "Natural example sentence 2 using the target word",
                "Natural example sentence 3 using the target word"
              ]
            }
        """.trimIndent()
    }

    suspend fun fetchAvailableModels(apiKey: String, backupApiKey: String = ""): Result<List<String>> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanBackupKey = backupApiKey.trim()
        if (cleanKey.isEmpty() && cleanBackupKey.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Enter a Groq API key first."))
        }

        val keysToTry = listOfNotNull(cleanKey.takeIf { it.isNotEmpty() }, cleanBackupKey.takeIf { it.isNotEmpty() })
        var lastError: Throwable? = null

        for ((index, key) in keysToTry.withIndex()) {
            try {
                val url = URL(GROQ_MODELS_ENDPOINT)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12_000
                    readTimeout = 15_000
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                }

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val rawResponse = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }

                if (responseCode == 429 && index < keysToTry.size - 1) {
                    continue
                }

                if (responseCode !in 200..299) {
                    val ex = RuntimeException("Groq API error: ${parseGroqErrorMessage(rawResponse, responseCode)}")
                    if (responseCode == 429 && index < keysToTry.size - 1) {
                        lastError = ex
                        continue
                    }
                    return@withContext Result.failure(ex)
                }

                val dataArray = JSONObject(rawResponse).optJSONArray("data") ?: JSONArray()
                val models = mutableListOf<String>()
                for (i in 0 until dataArray.length()) {
                    val item = dataArray.optJSONObject(i) ?: continue
                    val id = item.optString("id").trim()
                    if (id.isEmpty()) continue
                    val lowerId = id.lowercase()
                    val isExcluded = NON_CHAT_MODEL_KEYWORDS.any { keyword -> lowerId.contains(keyword) }
                    if (!isExcluded) {
                        models.add(id)
                    }
                }

                val sortedModels = models
                    .distinct()
                    .sortedWith(
                        compareBy<String> { it != DEFAULT_MODEL_NAME }
                            .thenBy { it.lowercase() }
                    )

                if (sortedModels.isEmpty()) {
                    return@withContext Result.failure(IllegalStateException("No chat models returned by Groq."))
                } else {
                    return@withContext Result.success(sortedModels)
                }
            } catch (e: Exception) {
                lastError = e
                if (index == keysToTry.size - 1) {
                    return@withContext Result.failure(e)
                }
            }
        }
        Result.failure(lastError ?: RuntimeException("Failed to fetch Groq models."))
    }

    suspend fun lookup(
        context: Context,
        word: String,
        sentence: String,
    ): Result<CustomAiDictionaryEntry> = withContext(Dispatchers.IO) {
        try {
            val aiPrefs = AiPreferences(context)
            val primaryKey = aiPrefs.groqApiKey
            val backupKey = aiPrefs.groqBackupApiKey
            val selectedModel = aiPrefs.groqModel.ifBlank { DEFAULT_MODEL_NAME }
            val languageMode = aiPrefs.languageMode

            if (primaryKey.isEmpty() && backupKey.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Groq API key is missing. Set it in AI settings first.")
                )
            }

            val cleanWord = word.trim()
            val cleanSentence = sentence.trim()

            if (cleanWord.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("No target word selected.")
                )
            }

            val systemPrompt = buildSystemPrompt(languageMode)

            val userPrompt = buildString {
                appendLine("Target Word: $cleanWord")
                if (cleanSentence.isNotEmpty()) {
                    appendLine("Sentence Context: $cleanSentence")
                } else {
                    appendLine("Sentence Context: (No surrounding sentence provided; analyze the word generally)")
                }
            }

            fun buildRequestBody(enforceJsonFormat: Boolean): String = JSONObject().apply {
                put("model", selectedModel)
                put("temperature", 0.2)
                if (enforceJsonFormat) {
                    put("response_format", JSONObject().put("type", "json_object"))
                }
                put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", systemPrompt))
                        put(JSONObject().put("role", "user").put("content", userPrompt))
                    }
                )
            }.toString()

            fun executeRequest(apiKey: String, enforceJsonFormat: Boolean): Pair<Int, String> {
                val url = URL(GROQ_ENDPOINT)
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 25_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $apiKey")
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }

                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(buildRequestBody(enforceJsonFormat))
                    writer.flush()
                }

                val responseCode = connection.responseCode
                val stream = if (responseCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val rawResponse = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
                return responseCode to rawResponse
            }

            val keysToTry = listOfNotNull(
                primaryKey.takeIf { it.isNotEmpty() },
                backupKey.takeIf { it.isNotEmpty() }
            )

            var lastException: Throwable? = null

            for ((index, apiKey) in keysToTry.withIndex()) {
                try {
                    var (responseCode, rawResponse) = executeRequest(apiKey, enforceJsonFormat = true)

                    if (responseCode == 429 && index < keysToTry.size - 1) {
                        // Rate limit hit on primary key, failover to backup key automatically
                        continue
                    }

                    // Groq's strict server-side JSON validator occasionally throws HTTP 400 when
                    // the model emits unescaped dialogue quotes or markdown fences; retry once
                    // without response_format and extract the JSON object locally.
                    if (responseCode == 400 && rawResponse.contains("json", ignoreCase = true)) {
                        val retry = executeRequest(apiKey, enforceJsonFormat = false)
                        responseCode = retry.first
                        rawResponse = retry.second
                        if (responseCode == 429 && index < keysToTry.size - 1) {
                            continue
                        }
                    }

                    if (responseCode !in 200..299) {
                        val errorMsg = try {
                            JSONObject(rawResponse).optJSONObject("error")?.optString("message")
                                ?: "HTTP $responseCode"
                        } catch (_: Exception) {
                            "HTTP $responseCode"
                        }
                        val ex = RuntimeException("Groq API error: $errorMsg")
                        if (responseCode == 429 && index < keysToTry.size - 1) {
                            lastException = ex
                            continue
                        }
                        return@withContext Result.failure(ex)
                    }

                    val root = JSONObject(rawResponse)
                    val rawContent = root
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                        .trim()
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()

                    val jsonStart = rawContent.indexOf('{')
                    val jsonEnd = rawContent.lastIndexOf('}')
                    val content = if (jsonStart >= 0 && jsonEnd > jsonStart) {
                        rawContent.substring(jsonStart, jsonEnd + 1)
                    } else {
                        rawContent
                    }

                    val parsed = parseEntryJson(cleanWord, content)
                    return@withContext Result.success(parsed)
                } catch (e: Exception) {
                    lastException = e
                    if (index == keysToTry.size - 1) {
                        return@withContext Result.failure(e)
                    }
                }
            }
            Result.failure(lastException ?: RuntimeException("Failed to lookup word via Groq."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseEntryJson(word: String, jsonText: String): CustomAiDictionaryEntry =
        parseEntryJson(word, JSONObject(jsonText))

    internal fun parseEntryJson(word: String, obj: JSONObject): CustomAiDictionaryEntry {
        val nuanceObj = obj.optJSONObject("nuance") ?: JSONObject()
        val nuance = AiNuanceInfo(
            formality = nuanceObj.optString("formality", "Neutral").ifBlank { "Neutral" },
            frequency = nuanceObj.optString("frequency", "Common").ifBlank { "Common" },
            dailyConversation = nuanceObj.optString("dailyConversation", ""),
        )

        val alternatives = mutableListOf<String>()
        val altArray = obj.optJSONArray("alternativeWords") ?: JSONArray()
        for (i in 0 until altArray.length()) {
            val item = altArray.optString(i).trim()
            if (item.isNotEmpty()) alternatives.add(item)
        }

        val chunks = mutableListOf<SentenceChunk>()
        val chunkArray = obj.optJSONArray("sentenceBreakdown") ?: JSONArray()
        for (i in 0 until chunkArray.length()) {
            val item = chunkArray.optJSONObject(i) ?: continue
            val seg = item.optString("segment").trim()
            val mean = item.optString("meaning").trim()
            if (seg.isNotEmpty() || mean.isNotEmpty()) {
                chunks.add(SentenceChunk(segment = seg, meaning = mean))
            }
        }

        val grammar = mutableListOf<GrammarPoint>()
        val grammarArray = obj.optJSONArray("grammarBreakdown") ?: JSONArray()
        for (i in 0 until grammarArray.length()) {
            val item = grammarArray.optJSONObject(i) ?: continue
            val pattern = item.optString("pattern").trim()
            val exp = item.optString("explanation").trim()
            if (pattern.isNotEmpty() || exp.isNotEmpty()) {
                grammar.add(GrammarPoint(pattern = pattern, explanation = exp))
            }
        }

        val examples = mutableListOf<String>()
        val exampleArray = obj.optJSONArray("exampleSentences") ?: JSONArray()
        for (i in 0 until exampleArray.length()) {
            val item = exampleArray.optString(i).trim()
            if (item.isNotEmpty()) examples.add(item)
        }

        return CustomAiDictionaryEntry(
            word = word,
            reading = obj.optString("reading", "").trim(),
            partOfSpeech = obj.optString("partOfSpeech", "").trim(),
            contextualMeaning = obj.optString("contextualMeaning", "No definition returned.").trim(),
            literalMeaning = obj.optString("literalMeaning", "").trim(),
            nuance = nuance,
            alternativeWords = alternatives,
            sentenceBreakdown = chunks,
            grammarBreakdown = grammar,
            exampleSentences = examples,
        )
    }
}