package eu.kanade.presentation.more.stats.data

sealed interface StatsData {
    data class HistoryPoint(
        val label: String,
        val value: Long, // Duration in ms
        val dateOffset: Int,
    )

    data class Overview(
        val libraryMangaCount: Int,
        val completedMangaCount: Int,
        val totalReadDuration: Long,
        val readingStreak: Int,
        val historyPoints: List<HistoryPoint>,
        val avgDurationPerDay: Long?,
        val ankiCardsAdded: Int,
        val charactersRead: Int,
        val charactersPerHour: Int?,
    ) : StatsData

    data class Titles(
        val startedMangaCount: Int,
        val localMangaCount: Int,
    ) : StatsData

    data class Chapters(
        val totalChapterCount: Int,
        val readChapterCount: Int,
        val downloadCount: Int,
    ) : StatsData

    data class Trackers(
        val trackedTitleCount: Int,
        val meanScore: Double,
        val trackerCount: Int,
    ) : StatsData

}


enum class StatsType {
    All, Manga, Novels, Anime
}
