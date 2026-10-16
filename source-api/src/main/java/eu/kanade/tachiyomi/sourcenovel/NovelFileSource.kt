package eu.kanade.tachiyomi.sourcenovel

/** Whole-book acquisition, independent of the source's plugin implementation. */
interface NovelFileSource : NovelSource {
    suspend fun hasDownloadSupport(): Boolean
    suspend fun getDownloadUrl(path: String, format: String? = null): String?
    fun downloadHeaders(path: String): Map<String, String> = emptyMap()
    fun downloadClient(): okhttp3.OkHttpClient? = null
    fun downloadFolderId(path: String): String? = null
}
