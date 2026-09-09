package tachiyomi.domain.novel.model

data class NovelHistory(
    val id: Long,
    val chapterId: Long,
    val lastRead: Long,
    val timeRead: Long,
)
