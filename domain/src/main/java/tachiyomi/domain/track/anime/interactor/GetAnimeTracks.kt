package tachiyomi.domain.track.anime.interactor

import kotlinx.coroutines.flow.Flow
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.anime.model.AnimeTrack
import tachiyomi.domain.track.anime.repository.AnimeTrackRepository

class GetAnimeTracks(
    private val animeTrackRepository: AnimeTrackRepository,
) {

    suspend fun awaitOne(id: Long): AnimeTrack? {
        return try {
            animeTrackRepository.getTrackByAnimeId(id)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            null
        }
    }

    suspend fun await(animeId: Long): List<AnimeTrack> {
        return try {
            animeTrackRepository.getTracksByAnimeId(animeId)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    suspend fun awaitAll(): List<AnimeTrack> {
        return try {
            animeTrackRepository.getAllAnime()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            emptyList()
        }
    }

    fun subscribe(): Flow<List<AnimeTrack>> {
        return animeTrackRepository.getAnimeTracksAsFlow()
    }

    fun subscribe(animeId: Long): Flow<List<AnimeTrack>> {
        return animeTrackRepository.getTracksByAnimeIdAsFlow(animeId)
    }
}
