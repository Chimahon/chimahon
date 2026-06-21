package tachiyomi.domain.novel.model

data class NovelUpdate(
    val id: Long,
    val source: Long? = null,
    val favorite: Boolean? = null,
    val lastUpdate: Long? = null,
    val nextUpdate: Long? = null,
    val fetchInterval: Int? = null,
    val dateAdded: Long? = null,
    val coverLastModified: Long? = null,
    val url: String? = null,
    val title: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genre: String? = null,
    val status: Long? = null,
    val thumbnailUrl: String? = null,
    val initialized: Boolean? = null,
    val totalChapters: Int? = null,
    val version: Long? = null,
    val notes: String? = null,
)

fun Novel.toNovelUpdate(): NovelUpdate {
    return NovelUpdate(
        id = id,
        source = source,
        favorite = favorite,
        lastUpdate = lastUpdate,
        nextUpdate = nextUpdate,
        fetchInterval = fetchInterval,
        dateAdded = dateAdded,
        coverLastModified = coverLastModified,
        url = url,
        title = title,
        author = author,
        artist = artist,
        description = description,
        genre = genre,
        status = status,
        thumbnailUrl = thumbnailUrl,
        initialized = initialized,
        totalChapters = totalChapters,
        version = version,
        notes = notes,
    )
}
