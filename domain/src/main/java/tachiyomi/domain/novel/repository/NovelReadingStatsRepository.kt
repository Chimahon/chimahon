package tachiyomi.domain.novel.repository

import tachiyomi.domain.novel.model.NovelReadingStats

interface NovelReadingStatsRepository {

    suspend fun upsert(
        novelId: Long,
        dateKey: String,
        charactersRead: Int,
        readingTime: Double,
        minReadingSpeed: Int,
        altMinReadingSpeed: Int,
        lastReadingSpeed: Int,
        maxReadingSpeed: Int,
        completedBook: Int?,
    )

    suspend fun getByNovelId(novelId: Long): List<NovelReadingStats>

    suspend fun getAll(): List<NovelReadingStats>

    suspend fun getForDay(dateKey: String): List<NovelReadingStats>

    suspend fun deleteByNovelId(novelId: Long)
}
