package tachiyomi.domain.novel.model

data class NovelChapter(
    val id: Long,
    val novelId: Long,
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Long,
    val dateFetch: Long,
    val sourceOrder: Long,
    val url: String,
    val name: String,
    val dateUpload: Long,
    val chapterNumber: Float,
    val scanlator: String?,
    val lastModifiedAt: Long,
    val version: Long,
) {
    val isRecognizedNumber: Boolean
        get() = chapterNumber >= 0f

    fun copyFrom(other: NovelChapter): NovelChapter {
        return copy(
            name = other.name,
            url = other.url,
            dateUpload = other.dateUpload,
            chapterNumber = other.chapterNumber,
            scanlator = other.scanlator?.ifBlank { null },
        )
    }

    companion object {
        fun create() = NovelChapter(
            id = -1,
            novelId = -1,
            read = false,
            bookmark = false,
            lastPageRead = 0,
            dateFetch = 0,
            sourceOrder = 0,
            url = "",
            name = "",
            dateUpload = -1,
            chapterNumber = -1f,
            scanlator = null,
            lastModifiedAt = 0,
            version = 1,
        )
    }
}
