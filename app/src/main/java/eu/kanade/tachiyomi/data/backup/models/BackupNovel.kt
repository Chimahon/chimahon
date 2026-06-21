package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelChapter

@Serializable
data class BackupNovel(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val title: String,
    @ProtoNumber(3) val author: String? = null,
    @ProtoNumber(4) val cover: String? = null,
    @ProtoNumber(5) val chapterIndex: Int = 0,
    @ProtoNumber(6) val progress: Double = 0.0,
    @ProtoNumber(7) val characterCount: Int = 0,
    @ProtoNumber(8) val lastModified: Long = 0L,
    @ProtoNumber(9) val stats: List<BackupStatEntry> = emptyList(),
    @ProtoNumber(10) val categoryIds: List<String> = emptyList(),
    @ProtoNumber(11) val lang: String? = null,
)

@Serializable
data class BackupNovelCategory(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val name: String,
    @ProtoNumber(3) val order: Long = 0,
    @ProtoNumber(4) val flags: Long = 0,
)

@Serializable
data class BackupStatEntry(
    @ProtoNumber(1) val dateKey: String,
    @ProtoNumber(2) val charactersRead: Int,
    @ProtoNumber(3) val readingTime: Double,
    @ProtoNumber(4) val minReadingSpeed: Int,
    @ProtoNumber(5) val altMinReadingSpeed: Int,
    @ProtoNumber(6) val lastReadingSpeed: Int,
    @ProtoNumber(7) val maxReadingSpeed: Int,
    @ProtoNumber(8) val lastStatisticModified: Long,
)

@Serializable
data class BackupSourceNovel(
    @ProtoNumber(1) var source: Long,
    @ProtoNumber(2) var url: String,
    @ProtoNumber(3) var title: String = "",
    @ProtoNumber(4) var artist: String? = null,
    @ProtoNumber(5) var author: String? = null,
    @ProtoNumber(6) var description: String? = null,
    @ProtoNumber(7) var genre: String? = null,
    @ProtoNumber(8) var status: Long = 0,
    @ProtoNumber(9) var thumbnailUrl: String? = null,
    @ProtoNumber(13) var dateAdded: Long = 0,
    @ProtoNumber(16) var chapters: List<BackupChapter> = emptyList(),
    @ProtoNumber(100) var favorite: Boolean = true,
    @ProtoNumber(101) var totalChapters: Int = 0,
    @ProtoNumber(102) var lastUpdate: Long = 0,
    @ProtoNumber(103) var nextUpdate: Long = 0,
    @ProtoNumber(104) var fetchInterval: Int = 0,
    @ProtoNumber(105) var coverLastModified: Long = 0,
    @ProtoNumber(106) var lastModifiedAt: Long = 0,
    @ProtoNumber(107) var favoriteModifiedAt: Long? = null,
    @ProtoNumber(108) var version: Long = 0,
    @ProtoNumber(109) var notes: String = "",
    @ProtoNumber(110) var initialized: Boolean = false,
) {
    fun getNovelImpl(): Novel {
        return Novel.create().copy(
            source = source,
            url = url,
            title = title,
            artist = artist,
            author = author,
            description = description,
            genre = genre,
            status = status,
            thumbnailUrl = thumbnailUrl,
            favorite = favorite,
            lastUpdate = lastUpdate,
            nextUpdate = nextUpdate,
            fetchInterval = fetchInterval,
            dateAdded = dateAdded,
            coverLastModified = coverLastModified,
            initialized = initialized,
            totalChapters = totalChapters,
            lastModifiedAt = lastModifiedAt,
            favoriteModifiedAt = favoriteModifiedAt,
            version = version,
            notes = notes,
        )
    }
}

fun NovelChapter.toBackupChapter(): BackupChapter {
    return BackupChapter(
        url = url,
        name = name,
        scanlator = scanlator,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        dateFetch = dateFetch,
        dateUpload = dateUpload,
        chapterNumber = chapterNumber,
        sourceOrder = sourceOrder,
        lastModifiedAt = lastModifiedAt,
        version = version,
    )
}

fun BackupChapter.toNovelChapter(novelId: Long): NovelChapter {
    return NovelChapter.create().copy(
        novelId = novelId,
        url = url,
        name = name,
        scanlator = scanlator,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        dateFetch = dateFetch,
        dateUpload = dateUpload,
        chapterNumber = chapterNumber,
        sourceOrder = sourceOrder,
        lastModifiedAt = lastModifiedAt,
        version = version,
    )
}
