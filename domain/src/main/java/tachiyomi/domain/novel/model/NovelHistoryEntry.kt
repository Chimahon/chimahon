package tachiyomi.domain.novel.model

/**
 * One history entry per novel (manga history-tab parity): the latest-read
 * chapter carries when/where, the counts carry how much. The tab lists
 * entries, never one row per chapter visit.
 */
data class NovelHistoryEntry(
    val novelId: Long,
    val title: String,
    val thumbnailUrl: String?,
    val source: Long,
    val favorite: Boolean,
    val latest: NovelHistoryWithRelations,
    val readCount: Int,
    val totalCount: Int,
    /** 0-based position of the latest-read chapter in the novel's spine. */
    val latestIndex: Int = -1,
) {
    val lastRead: Long get() = latest.lastRead

    val progress: Float get() = if (totalCount == 0) 0f else readCount.toFloat() / totalCount

    val hasUnread: Boolean get() = readCount < totalCount
}
