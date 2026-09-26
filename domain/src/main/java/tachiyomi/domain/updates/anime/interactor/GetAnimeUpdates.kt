package tachiyomi.domain.updates.anime.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.updates.anime.model.AnimeUpdatesWithRelations
import tachiyomi.domain.updates.anime.repository.AnimeUpdatesRepository
import java.time.Instant

class GetAnimeUpdates(
    private val repository: AnimeUpdatesRepository,
) {

    suspend fun await(seen: Boolean, after: Long): List<AnimeUpdatesWithRelations> {
        return repository.awaitWithSeen(seen, after, limit = 500)
    }

    fun subscribe(
        instant: Instant,
        seen: Boolean? = null,
        started: Boolean? = null,
        bookmarked: Boolean? = null,
        fillermarked: Boolean? = null,
    ): Flow<List<AnimeUpdatesWithRelations>> {
        return repository.subscribeAllAnimeUpdates(
            after = instant.toEpochMilli(),
            limit = 500,
            seen = seen,
            started = started,
            bookmarked = bookmarked,
            fillermarked = fillermarked,
        )
    }

    fun subscribe(seen: Boolean, after: Long): Flow<List<AnimeUpdatesWithRelations>> {
        return repository.subscribeWithSeen(seen, after, limit = 500)
    }
}
