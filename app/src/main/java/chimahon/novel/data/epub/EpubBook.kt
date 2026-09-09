package chimahon.novel.data.epub

import android.util.Log
import org.jsoup.Jsoup
import java.io.File

data class EpubBook(
    val title: String? = null,
    val author: String? = null,
    val language: String? = null,
    val coverPath: String? = null,
    val metadata: EpubMetadata = EpubMetadata(),
    val manifest: EpubManifest = EpubManifest(),
    val spine: EpubSpine = EpubSpine(),
    val tableOfContents: List<TocEntry> = emptyList(),
    val contentDirectory: String = "",
    val zipPath: String = "",
    val extractedDir: File? = null,
) {
    val coverHref: String?
        get() {
            val coverId = metadata.coverId ?: return null
            val manifestItem = manifest.items[coverId] ?: return null
            // Prepend content directory if present
            return if (contentDirectory.isNotEmpty()) {
                "$contentDirectory${manifestItem.href}"
            } else {
                manifestItem.href
            }
        }

    val linearSpineItems: List<SpineItem>
        get() = spine.items.filter { it.linear }

    fun getChapterHref(index: Int): String? {
        val spineItem = linearSpineItems.getOrNull(index) ?: return null
        val manifestItem = manifest.items[spineItem.idref] ?: return null
        // Prepend content directory if present (e.g., "item/xhtml/p-001.xhtml")
        return if (contentDirectory.isNotEmpty()) {
            "$contentDirectory${manifestItem.href}"
        } else {
            manifestItem.href
        }
    }

    // Hoshi Shims
    fun title(): String? = title
    fun spine(): EpubSpine = spine
    fun chapterAbsolutePath(index: UInt): String? {
        val href = getChapterHref(index.toInt()) ?: return null
        val baseDir = extractedDir ?: return null
        return File(baseDir, href).absolutePath
    }

    /**
     * Returns the cached image URL for an image-only spine item.
     * The URL was resolved once during book parsing and stored in [SpineItem.imageUrl].
     */
    fun getImageUrl(index: Int): String? =
        linearSpineItems.getOrNull(index)
            ?.takeIf { it.type == SpineItemType.IMAGE_ONLY }
            ?.imageUrl

    // Cache to prevent recalculating Chapter Character length. Synchronized:
    // fills run on IO while progress ticks read on Main.
    private val chapterLengthCache =
        java.util.Collections.synchronizedMap(mutableMapOf<Int, Int>())

    /** In-memory chapter HTML by spine index (virtual books built without files). */
    val contentOverride = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /**
     * Calculates the exploredCharCount of a specific chapter matching TTSU logic
     */
    fun getChapterCharacters(index: Int): Int {
        if (chapterLengthCache.containsKey(index)) return chapterLengthCache[index]!!

        contentOverride[index]?.let {
            val charCount = countCharacters(it)
            chapterLengthCache[index] = charCount
            return charCount
        }

        val spineType = linearSpineItems.getOrNull(index)?.type
        if (spineType == SpineItemType.IMAGE_ONLY) {
            chapterLengthCache[index] = 0
            return 0
        }

        return try {
            val content = EpubParser().parseChapter(this, index) ?: return 0
            val charCount = countCharacters(content)
            chapterLengthCache[index] = charCount
            charCount
        } catch(e: Exception) {
            0
        }
    }

    /**
     * Same Hoshi-reader counting pipeline over an in-memory document:
     * body extract, tag strips, entity decode, TTSU character filter.
     */
    fun countCharacters(html: String): Int {
        return try {
            // Use the same approach as Hoshi Reader's characterCount():
            // 1. Extract <body>...</body>
            // 2. Strip <rt> (furigana), <script>, <style>, then ALL tags
            // 3. Decode HTML entities
            // 4. Apply TTSU character filter regex
            var text = html
            val bodyMatch = Regex("(?s)<body.*?</body>").find(text)
            if (bodyMatch != null) {
                text = bodyMatch.value
            }
            // Strip <rt>...</rt> (furigana annotations)
            text = text.replace(Regex("(?s)<rt>.*?</rt>"), "")
            // Strip <script>...</script> and <style>...</style>
            text = text.replace(Regex("(?s)<(script|style)[^>]*>.*?</\\1>"), "")
            // Strip all HTML tags
            text = text.replace(Regex("<[^>]+>"), "")
            // Decode common HTML entities
            text = text.replace("&nbsp;", " ")
            text = text.replace("&amp;", "&")
            text = text.replace("&lt;", "<")
            text = text.replace("&gt;", ">")

            // TTSU character filter (matching Hoshi's regex with \p{Unified_Ideograph} and \p{IsHangul})
            val ttsuRegex = Regex("[^0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\\p{IsHan}\\p{IsHangul}]")
            val filteredText = text.replace(ttsuRegex, "")

            filteredText.codePointCount(0, filteredText.length)
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Converts an absolute character count back into a 
     * specific chapter index and decimal progress (0.0 to 1.0) for that chapter.
     */
    fun convertCharsToProgress(totalExploredChars: Int): Pair<Int, Double> {
        var unallocatedChars = totalExploredChars
        for (i in 0 until linearSpineItems.size) {
            val chapterChars = getChapterCharacters(i)
            if (unallocatedChars <= chapterChars) {
                // If chapter has 0 chars (e.g. image), prevent NaN/Infinity
                val progress = if (chapterChars == 0) 0.0 else unallocatedChars.toDouble() / chapterChars.toDouble()
                return Pair(i, progress.coerceIn(0.0, 1.0))
            }
            unallocatedChars -= chapterChars
        }
        // If chars exceed total book size, clamp to the last chapter at 100%
        return Pair(maxOf(0, linearSpineItems.size - 1), 1.0)
    }
}