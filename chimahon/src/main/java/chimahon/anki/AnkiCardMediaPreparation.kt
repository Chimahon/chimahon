package chimahon.anki

import kotlinx.coroutines.CancellationException

internal const val TRANSPARENT_IMAGE_DATA_URI =
    "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=="

internal data class PreparedAnkiMediaPayload(
    val filename: String,
    val data: ByteArray,
)

internal data class PreparedDictionaryMedia(
    val placeholder: String,
    val payload: PreparedAnkiMediaPayload?,
)

internal data class PreparedAnkiCardMedia(
    val screenshot: PreparedAnkiMediaPayload?,
    val cover: PreparedAnkiMediaPayload? = null,
    val wordAudio: PreparedAnkiMediaPayload?,
    val dictionaryMedia: List<PreparedDictionaryMedia>,
    val sentenceAudio: AnkiSentenceAudioPreparation?,
)

internal data class CommittedAnkiCardMedia(
    val screenshotFilename: String?,
    val coverFilename: String? = null,
    val wordAudioFilename: String?,
    val dictionaryReplacementByPlaceholder: Map<String, String>,
    val sentenceAudio: StoredSentenceAudio,
)

/**
 * The only media write boundary used by the final duplicate-gate callback.
 * Ordinary optional-media failures preserve the legacy omission/fallback behavior.
 */
internal suspend fun commitPreparedAnkiCardMedia(
    prepared: PreparedAnkiCardMedia,
    storeBytes: suspend (filename: String, data: ByteArray) -> String,
    storeSentenceAudio: suspend (AnkiSentenceAudioSource) -> String,
    storeCover: suspend (filename: String, data: ByteArray) -> String = storeBytes,
): CommittedAnkiCardMedia {
    suspend fun storeOptional(
        payload: PreparedAnkiMediaPayload?,
        writer: suspend (String, ByteArray) -> String = storeBytes,
    ): String? {
        if (payload == null) return null
        return try {
            writer(payload.filename, payload.data)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    val dictionaryReplacements = prepared.dictionaryMedia.associate { dictionary ->
        val filename = storeOptional(dictionary.payload)
        dictionary.placeholder to (filename ?: TRANSPARENT_IMAGE_DATA_URI)
    }
    return CommittedAnkiCardMedia(
        screenshotFilename = storeOptional(prepared.screenshot),
        coverFilename = storeOptional(prepared.cover, storeCover),
        wordAudioFilename = storeOptional(prepared.wordAudio),
        dictionaryReplacementByPlaceholder = dictionaryReplacements,
        sentenceAudio = AnkiSentenceAudioCommitter(storeSentenceAudio).store(prepared.sentenceAudio),
    )
}
