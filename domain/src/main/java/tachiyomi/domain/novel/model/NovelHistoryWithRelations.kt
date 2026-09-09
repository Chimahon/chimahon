package tachiyomi.domain.novel.model

/**
 * One history row with its chapter + novel attached (manga
 * `HistoryWithRelations` parity). Covers source and local novels alike —
 * local homes use the same rows.
 */
data class NovelHistoryWithRelations(
    val id: Long,
    val chapterId: Long,
    val lastRead: Long,
    val timeRead: Long,
    val novelId: Long,
    val chapterUrl: String,
    val chapterName: String,
    val chapterNumber: Float,
    val chapterRead: Boolean,
    val chapterProgress: Double,
    val novelTitle: String,
    val novelThumbnailUrl: String?,
    val novelSource: Long,
    val novelFavorite: Boolean,
)
