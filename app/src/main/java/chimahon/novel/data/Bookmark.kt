package chimahon.novel.data

import kotlinx.serialization.Serializable

@Serializable
data class Bookmark(
    val chapterIndex: Int,
    val progress: Double,
    val characterCount: Int,
    val lastModified: Long? = null,
)
