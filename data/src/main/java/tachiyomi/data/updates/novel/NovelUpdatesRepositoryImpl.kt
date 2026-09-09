package tachiyomi.data.updates.novel

import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.novel.model.NovelUpdatesWithRelations
import tachiyomi.domain.updates.novel.repository.NovelUpdatesRepository

fun mapNovelUpdatesWithRelations(
    novelId: Long,
    novelTitle: String,
    novelUrl: String,
    chapterId: Long,
    chapterName: String,
    scanlator: String?,
    chapterUrl: String,
    read: Long,
    bookmark: Long,
    last_page_read: Long,
    sourceId: Long,
    favorite: Long,
    thumbnailUrl: String?,
    coverLastModified: Long,
    dateUpload: Long,
    datefetch: Long,
): NovelUpdatesWithRelations = NovelUpdatesWithRelations(
    novelId = novelId,
    novelTitle = novelTitle,
    novelUrl = novelUrl,
    chapterId = chapterId,
    chapterName = chapterName,
    scanlator = scanlator,
    read = read != 0L,
    bookmark = bookmark != 0L,
    sourceId = sourceId,
    dateFetch = datefetch,
    coverData = MangaCover(
        mangaId = novelId,
        sourceId = sourceId,
        isMangaFavorite = favorite != 0L,
        ogUrl = thumbnailUrl,
        lastModified = coverLastModified,
    ),
)

class NovelUpdatesRepositoryImpl(
    private val handler: DatabaseHandler,
) : NovelUpdatesRepository {

    override suspend fun awaitUpdates(after: Long, limit: Long): List<NovelUpdatesWithRelations> {
        return handler.awaitList {
            novelUpdatesViewQueries.getRecentNovelUpdates(
                after = after,
                limit = limit,
                mapper = ::mapNovelUpdatesWithRelations,
            )
        }
    }

    override suspend fun awaitWithRead(
        read: Boolean,
        after: Long,
        limit: Long,
    ): List<NovelUpdatesWithRelations> {
        return handler.awaitList {
            novelUpdatesViewQueries.getRecentNovelUpdatesWithReadFilter(
                read = if (read) 1L else 0L,
                after = after,
                limit = limit,
                mapper = ::mapNovelUpdatesWithRelations,
            )
        }
    }
}
