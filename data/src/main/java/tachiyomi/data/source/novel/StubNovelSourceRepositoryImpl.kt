package tachiyomi.data.source.novel

import kotlinx.coroutines.flow.Flow
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.source.novel.model.StubNovelSource
import tachiyomi.domain.source.novel.repository.StubNovelSourceRepository

class StubNovelSourceRepositoryImpl(
    private val handler: DatabaseHandler,
) : StubNovelSourceRepository {

    override fun subscribeAll(): Flow<List<StubNovelSource>> {
        return handler.subscribeToList { sourcesQueries.findAll(::mapStubNovelSource) }
    }

    override suspend fun getStubSource(id: Long): StubNovelSource? {
        return handler.awaitOneOrNull { sourcesQueries.findOne(id, ::mapStubNovelSource) }
    }

    override suspend fun upsertStubSource(id: Long, lang: String, name: String) {
        handler.await { sourcesQueries.upsert(id, lang, name) }
    }

    private fun mapStubNovelSource(
        id: Long,
        lang: String,
        name: String,
    ): StubNovelSource = StubNovelSource(id = id, lang = lang, name = name)
}
