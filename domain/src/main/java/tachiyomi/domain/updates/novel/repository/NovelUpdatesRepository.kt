package tachiyomi.domain.updates.novel.repository

import tachiyomi.domain.updates.novel.model.NovelUpdatesWithRelations

interface NovelUpdatesRepository {

    suspend fun awaitUpdates(after: Long, limit: Long): List<NovelUpdatesWithRelations>

    suspend fun awaitWithRead(read: Boolean, after: Long, limit: Long): List<NovelUpdatesWithRelations>
}
