package eu.kanade.tachiyomi.animesource.online

import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import tachiyomi.core.common.util.lang.awaitSingle
import uy.kohesive.injekt.injectLazy
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

@Suppress("unused")
abstract class AnimeHttpSource : AnimeCatalogueSource {

    protected val network: NetworkHelper by injectLazy()

    abstract val baseUrl: String

    open val versionId = 1

    override val id by lazy { generateId(name, lang, versionId) }

    open val headers: Headers by lazy { headersBuilder().build() }

    open val client: OkHttpClient
        get() = network.client

    @Suppress("MemberVisibilityCanBePrivate")
    protected fun generateId(name: String, lang: String, versionId: Int): Long {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    protected open fun headersBuilder() = Headers.Builder().apply {
        add("User-Agent", network.defaultUserAgentProvider())
    }

    override fun toString() = "$name (${lang.uppercase()})"

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getPopularAnime"),
    )
    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> {
        return client.newCall(popularAnimeRequest(page))
            .asObservableSuccess()
            .map { response ->
                popularAnimeParse(response)
            }
    }

    protected abstract fun popularAnimeRequest(page: Int): Request

    protected abstract fun popularAnimeParse(response: Response): AnimesPage

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getSearchAnime"),
    )
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return Observable.defer {
            try {
                client.newCall(searchAnimeRequest(page, query, filters)).asObservableSuccess()
            } catch (e: NoClassDefFoundError) {
                throw RuntimeException(e)
            }
        }
            .map { response ->
                searchAnimeParse(response)
            }
    }

    protected abstract fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request

    protected abstract fun searchAnimeParse(response: Response): AnimesPage

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getLatestUpdates"),
    )
    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> {
        return client.newCall(latestUpdatesRequest(page))
            .asObservableSuccess()
            .map { response ->
                latestUpdatesParse(response)
            }
    }

    protected abstract fun latestUpdatesRequest(page: Int): Request

    protected abstract fun latestUpdatesParse(response: Response): AnimesPage

    @Suppress("DEPRECATION")
    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return fetchAnimeDetails(anime).awaitSingle()
    }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getAnimeDetails"))
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return client.newCall(animeDetailsRequest(anime))
            .asObservableSuccess()
            .map { response ->
                animeDetailsParse(response).apply { initialized = true }
            }
    }

    open fun animeDetailsRequest(anime: SAnime): Request {
        return GET(baseUrl + anime.url, headers)
    }

    protected abstract fun animeDetailsParse(response: Response): SAnime

    @Suppress("DEPRECATION")
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return fetchEpisodeList(anime).awaitSingle()
    }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getEpisodeList"))
    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        return client.newCall(episodeListRequest(anime))
            .asObservableSuccess()
            .map { response ->
                episodeListParse(response)
            }
    }

    protected open fun episodeListRequest(anime: SAnime): Request {
        return GET(baseUrl + anime.url, headers)
    }

    protected abstract fun episodeListParse(response: Response): List<SEpisode>

    protected open fun episodeVideoParse(response: Response): SEpisode = throw UnsupportedOperationException("Not used")

    /**
     * Get all the available seasons for an anime.
     * Normally it's not needed to override this method.
     *
     * @since extensions-lib 16
     * @param anime the anime to look for seasons.
     * @return the seasons for the anime.
     */
    override suspend fun getSeasonList(anime: SAnime): List<SAnime> {
        return client.newCall(seasonListRequest(anime))
            .awaitSuccess()
            .let { response ->
                seasonListParse(response)
            }
    }

    /**
     * Returns the request for updating the season list. Override only if it's needed to override
     * the url, send different headers or request method like POST.
     *
     * @since extensions-lib 16
     * @param anime the anime to look for seasons.
     * @return the request for getting the seasons.
     */
    protected open fun seasonListRequest(anime: SAnime): Request {
        return GET(baseUrl + anime.url, headers)
    }

    /**
     * Parses the response from the site and returns a list of seasons.
     *
     * @since extensions-lib 16
     * @param response the response from the site.
     * @return the list of seasons.
     */
    protected open fun seasonListParse(response: Response): List<SAnime> = emptyList()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        return client.newCall(hosterListRequest(episode))
            .awaitSuccess()
            .let { response ->
                hosterListParse(response).sortHosters()
            }
    }

    protected open fun hosterListRequest(episode: SEpisode): Request {
        return GET(baseUrl + episode.url, headers)
    }

    protected abstract fun hosterListParse(response: Response): List<Hoster>

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        return client.newCall(videoListRequest(hoster))
            .awaitSuccess()
            .let { response ->
                videoListParse(response, hoster).sortVideos()
            }
    }

    protected open fun videoListRequest(hoster: Hoster): Request {
        return GET(hoster.hosterUrl, headers)
    }

    protected abstract fun videoListParse(response: Response, hoster: Hoster): List<Video>

    open suspend fun resolveVideo(video: Video): Video? {
        return video
    }

    @Suppress("DEPRECATION")
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        return fetchVideoList(episode).awaitSingle()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getVideoList"))
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        return client.newCall(videoListRequest(episode))
            .asObservableSuccess()
            .map { response ->
                videoListParse(response).sort()
            }
    }

    protected open fun videoListRequest(episode: SEpisode): Request {
        return GET(baseUrl + episode.url, headers)
    }

    protected open fun videoListParse(response: Response): List<Video> =
        throw UnsupportedOperationException("Not used")

    open fun List<Hoster>.sortHosters(): List<Hoster> {
        return this
    }

    open fun List<Video>.sortVideos(): List<Video> {
        return this
    }

    @Deprecated("Use .sortVideos() instead", replaceWith = ReplaceWith("sortVideos"))
    protected open fun List<Video>.sort(): List<Video> {
        return this
    }

    @Suppress("DEPRECATION")
    open suspend fun getVideoUrl(video: Video): String {
        return fetchVideoUrl(video).awaitSingle()
    }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getVideoUrl"))
    open fun fetchVideoUrl(video: Video): Observable<String> {
        return client.newCall(videoUrlRequest(video))
            .asObservableSuccess()
            .map { videoUrlParse(it) }
    }

    protected open fun videoUrlRequest(video: Video): Request {
        return GET(video.videoPageUrl, headers)
    }

    protected abstract fun videoUrlParse(response: Response): String

    suspend fun getVideo(
        request: Request,
        listener: ProgressListener,
    ): Response {
        return client.newCachelessCallWithProgress(request, listener)
            .awaitSuccess()
    }

    fun getVideoSize(
        video: Video,
        tries: Int,
    ): Long {
        val rangeHeaders = Headers.Builder().addAll(video.headers ?: headers).add("Range", "bytes=0-1").build()
        val request = GET(video.videoUrl, rangeHeaders)
        repeat(tries + 1) {
            val size = client.newCall(request).execute().use { response ->
                response.header("Content-Range")
            }?.substringAfterLast('/')?.toLongOrNull()
            if (size != null && size >= 0) return size
        }
        return -1L
    }

    fun videoRequest(
        video: Video,
        start: Long,
        end: Long,
    ): Request {
        val headers = video.headers ?: headers
        val newHeaders =
            if (end - start > 0L) {
                Headers.Builder().addAll(headers).add("Range", "bytes=$start-$end").build()
            } else if (start >= 0L) {
                Headers.Builder().addAll(headers).add("Range", "bytes=$start-").build()
            } else {
                null
            }
        return GET(video.videoUrl, newHeaders ?: headers)
    }

    fun safeVideoRequest(
        video: Video,
    ): Request {
        return GET(video.videoUrl, video.headers ?: headers)
    }

    fun SEpisode.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    fun SAnime.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String {
        return try {
            val uri = URI(orig.replace(" ", "%20"))
            var out = uri.path
            if (uri.query != null) {
                out += "?" + uri.query
            }
            if (uri.fragment != null) {
                out += "#" + uri.fragment
            }
            out
        } catch (e: URISyntaxException) {
            orig
        }
    }

    open fun getAnimeUrl(anime: SAnime): String {
        return animeDetailsRequest(anime).url.toString()
    }

    open fun getEpisodeUrl(episode: SEpisode): String {
        return episode.url
    }

    open fun prepareNewEpisode(episode: SEpisode, anime: SAnime) {}

    override fun getFilterList() = AnimeFilterList()
}
