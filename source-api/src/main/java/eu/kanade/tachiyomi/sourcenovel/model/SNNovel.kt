package eu.kanade.tachiyomi.sourcenovel.model

data class SNNovel(
    var url: String = "",
    var title: String = "",
    var author: String? = null,
    var artist: String? = null,
    var description: String? = null,
    var genre: String? = null,
    var status: Int = 0,
    var thumbnail_url: String? = null,
    var initialized: Boolean = false,
    var id: Long = -1,
    var source: Long = 0,
    var favorite: Boolean = false,
    var lastUpdate: Long = 0,
    var viewerFlags: Long = 0,
    var chapterFlags: Long = 0,
) {
    val thumbnailUrl get() = thumbnail_url
    val init get() = initialized

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create() = SNNovel()
    }
}
