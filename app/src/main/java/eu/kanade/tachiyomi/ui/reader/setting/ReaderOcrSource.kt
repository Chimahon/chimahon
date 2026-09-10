package eu.kanade.tachiyomi.ui.reader.setting

import eu.kanade.tachiyomi.data.ocr.OcrEngineType

enum class ReaderOcrSource(
    val usesMokuro: Boolean,
    val usesPersistentCache: Boolean,
    val persistentCacheVariant: String? = null,
    val recognitionEngine: OcrEngineType?,
) {
    AUTOMATIC(
        usesMokuro = true,
        usesPersistentCache = true,
        recognitionEngine = null,
    ),
    MOKURO(
        usesMokuro = true,
        usesPersistentCache = false,
        recognitionEngine = null,
    ),
    GOOGLE_LENS(
        usesMokuro = false,
        usesPersistentCache = false,
        persistentCacheVariant = "google_lens",
        recognitionEngine = OcrEngineType.CLOUD,
    ),
    LOCAL(
        usesMokuro = false,
        usesPersistentCache = false,
        recognitionEngine = OcrEngineType.LOCAL,
    ),
    PADDLE(
        usesMokuro = false,
        usesPersistentCache = false,
        persistentCacheVariant = "paddle",
        recognitionEngine = OcrEngineType.PADDLE,
    ),
    ;

    val persistsOcrResults: Boolean
        get() = usesPersistentCache || persistentCacheVariant != null

    companion object {
        fun availableSources(localOcrAvailable: Boolean, mokuroAvailable: Boolean): List<ReaderOcrSource> {
            return entries.filter { source ->
                when (source) {
                    LOCAL, PADDLE -> localOcrAvailable
                    MOKURO -> mokuroAvailable
                    else -> true
                }
            }
        }
    }
}
