package tachiyomi.data.novel

import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.novel.model.NovelReadingStats
import tachiyomi.domain.novel.repository.NovelReadingStatsRepository

fun mapNovelReadingStats(
    _id: Long,
    novel_id: Long,
    date_key: String,
    characters_read: Long,
    reading_time: Double,
    min_reading_speed: Long,
    alt_min_reading_speed: Long,
    last_reading_speed: Long,
    max_reading_speed: Long,
    completed_book: Long?,
): NovelReadingStats = NovelReadingStats(
    id = _id,
    novelId = novel_id,
    dateKey = date_key,
    charactersRead = characters_read.toInt(),
    readingTime = reading_time,
    minReadingSpeed = min_reading_speed.toInt(),
    altMinReadingSpeed = alt_min_reading_speed.toInt(),
    lastReadingSpeed = last_reading_speed.toInt(),
    maxReadingSpeed = max_reading_speed.toInt(),
    completedBook = completed_book?.toInt(),
)

class NovelReadingStatsRepositoryImpl(
    private val handler: DatabaseHandler,
) : NovelReadingStatsRepository {

    override suspend fun upsert(
        novelId: Long,
        dateKey: String,
        charactersRead: Int,
        readingTime: Double,
        minReadingSpeed: Int,
        altMinReadingSpeed: Int,
        lastReadingSpeed: Int,
        maxReadingSpeed: Int,
        completedBook: Int?,
    ) {
        handler.await {
            novel_reading_statsQueries.upsertNovelReadingStats(
                novelId = novelId,
                dateKey = dateKey,
                charactersRead = charactersRead.toLong(),
                readingTime = readingTime,
                minReadingSpeed = minReadingSpeed.toLong(),
                altMinReadingSpeed = altMinReadingSpeed.toLong(),
                lastReadingSpeed = lastReadingSpeed.toLong(),
                maxReadingSpeed = maxReadingSpeed.toLong(),
                completedBook = completedBook?.toLong(),
            )
        }
    }

    override suspend fun getByNovelId(novelId: Long): List<NovelReadingStats> {
        return handler.awaitList {
            novel_reading_statsQueries.getNovelStatsByNovelId(novelId, ::mapNovelReadingStats)
        }
    }

    override suspend fun getAll(): List<NovelReadingStats> {
        return handler.awaitList { novel_reading_statsQueries.getAllNovelStats(::mapNovelReadingStats) }
    }

    override suspend fun getForDay(dateKey: String): List<NovelReadingStats> {
        return handler.awaitList {
            novel_reading_statsQueries.getNovelStatsForDay(dateKey, ::mapNovelReadingStats)
        }
    }

    override suspend fun deleteByNovelId(novelId: Long) {
        handler.await { novel_reading_statsQueries.deleteNovelStatsByNovelId(novelId) }
    }
}
