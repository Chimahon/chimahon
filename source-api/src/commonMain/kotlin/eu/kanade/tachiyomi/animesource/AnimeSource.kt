package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.AnimeRelation
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeEpisodeUpdate
import eu.kanade.tachiyomi.animesource.model.SAnimeSeasonUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import rx.Observable
import tachiyomi.core.common.util.lang.awaitSingle

interface AnimeSource {

    val id: Long

    val name: String

    val lang: String
        get() = ""

    @Suppress("DEPRECATION")
    suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return fetchAnimeDetails(anime).awaitSingle()
    }

    @Suppress("DEPRECATION")
    suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return fetchEpisodeList(anime).awaitSingle()
    }

    suspend fun getSeasonList(anime: SAnime): List<SAnime> = emptyList()

    suspend fun getHosterList(episode: SEpisode): List<Hoster> = throw IllegalStateException("Not used")

    suspend fun getVideoList(hoster: Hoster): List<Video> = throw IllegalStateException("Not used")

    @Suppress("DEPRECATION")
    suspend fun getVideoList(episode: SEpisode): List<Video> {
        return fetchVideoList(episode).awaitSingle()
    }

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getAnimeDetails"),
    )
    fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getEpisodeList"),
    )
    fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getVideoList"),
    )
    fun fetchVideoList(episode: SEpisode): Observable<List<Video>> =
        throw IllegalStateException("Not used")

    // KMK -->
    suspend fun getRelatedAnimeList(
        anime: SAnime,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (relatedAnime: Pair<String, List<SAnime>>, completed: Boolean) -> Unit,
    ): Unit = getRelatedMangaList(anime, exceptionHandler, pushResults)

    suspend fun getRelatedMangaList(
        anime: SAnime,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (relatedAnime: Pair<String, List<SAnime>>, completed: Boolean) -> Unit,
    ): Unit = throw UnsupportedOperationException()
    // KMK <--

    // AY -->
    /**
     * Get anime related to [anime], grouped by relation label
     *
     * extensions-lib 17 shape. The three-argument [getRelatedAnimeList] above is the older API and
     * is kept so existing extensions keep working.
     *
     * @since extensions-lib 17
     */
    suspend fun getRelatedAnimeList(anime: SAnime): List<AnimeRelation> = emptyList()

    /**
     * Fetches updated information for an anime: metadata, episodes, or both. Unrequested values
     * may be returned as-is.
     *
     * @since extensions-lib 17
     */
    suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate = SAnimeEpisodeUpdate(anime, episodes)

    /**
     * Fetches updated information for an anime: metadata, seasons, or both. Unrequested values
     * may be returned as-is.
     *
     * @since extensions-lib 17
     */
    suspend fun getAnimeSeasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ): SAnimeSeasonUpdate = SAnimeSeasonUpdate(anime, seasons)
    // <-- AY
}
