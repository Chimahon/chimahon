package tachiyomi.domain.updates.novel.model

import tachiyomi.domain.manga.model.MangaCover

data class NovelUpdatesWithRelations(
    val novelId: Long,
    val novelTitle: String,
    val novelUrl: String,
    val chapterId: Long,
    val chapterName: String,
    val scanlator: String?,
    val read: Boolean,
    val bookmark: Boolean,
    val sourceId: Long,
    val dateFetch: Long,
    val coverData: MangaCover,
)
