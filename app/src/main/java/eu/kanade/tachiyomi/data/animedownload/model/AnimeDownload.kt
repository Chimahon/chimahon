package eu.kanade.tachiyomi.data.animedownload.model

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.episode.interactor.GetEpisode
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class AnimeDownload(
    val source: AnimeHttpSource,
    val anime: Anime,
    val episode: Episode,
    @Volatile var video: Video? = null,
    /**
     * Hand this episode to an external downloader instead of fetching it ourselves. The queue
     * entry is dropped as soon as the intent is fired: the other app owns the file from there.
     */
    val useExternalDownloader: Boolean = false,
) {
    @Volatile
    var totalBytes: Long = -1L

    @Volatile
    var downloadedBytes: Long = 0L

    @Transient
    private val _statusFlow = MutableStateFlow(State.NOT_DOWNLOADED)

    @Transient
    val statusFlow = _statusFlow.asStateFlow()
    var status: State
        get() = _statusFlow.value
        set(status) {
            _statusFlow.value = status
        }

    @Transient
    private val _progressFlow = MutableStateFlow(0)

    @Transient
    val progressFlow = _progressFlow.asStateFlow()
    var progress: Int
        get() = _progressFlow.value
        set(value) {
            _progressFlow.value = value
        }

    enum class State(val value: Int) {
        NOT_DOWNLOADED(0),
        QUEUE(1),
        DOWNLOADING(2),
        DOWNLOADED(3),
        ERROR(4),
    }

    companion object {
        suspend fun fromEpisodeId(
            episodeId: Long,
            getEpisode: GetEpisode = Injekt.get(),
            getAnimeById: GetAnime = Injekt.get(),
            sourceManager: AnimeSourceManager = Injekt.get(),
        ): AnimeDownload? {
            val episode = getEpisode.await(episodeId) ?: return null
            val anime = getAnimeById.await(episode.animeId) ?: return null
            val source = sourceManager.get(anime.source) as? AnimeHttpSource ?: return null
            return AnimeDownload(source, anime, episode)
        }
    }
}
