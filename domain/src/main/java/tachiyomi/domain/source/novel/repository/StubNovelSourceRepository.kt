package tachiyomi.domain.source.novel.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.source.novel.model.StubNovelSource

interface StubNovelSourceRepository {
    fun subscribeAll(): Flow<List<StubNovelSource>>

    suspend fun getStubSource(id: Long): StubNovelSource?

    suspend fun upsertStubSource(id: Long, lang: String, name: String)
}
