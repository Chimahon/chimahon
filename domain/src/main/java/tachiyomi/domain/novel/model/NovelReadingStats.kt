package tachiyomi.domain.novel.model

data class NovelReadingStats(
    val id: Long,
    val novelId: Long,
    val dateKey: String,
    val charactersRead: Int,
    val readingTime: Double,
    val minReadingSpeed: Int,
    val altMinReadingSpeed: Int,
    val lastReadingSpeed: Int,
    val maxReadingSpeed: Int,
    val completedBook: Int?,
)
