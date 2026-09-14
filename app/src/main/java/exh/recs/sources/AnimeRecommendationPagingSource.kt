package exh.recs.sources

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.source.NoResultsException
import tachiyomi.data.source.anime.AnimeSourcePagingSource
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.i18n.sy.SYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/**
 * General class for anime recommendation sources.
 */
abstract class AnimeRecommendationPagingSource(
    protected val anime: Anime,
    source: AnimeRecommendationSource = AnimeRecommendationSource(),
) : AnimeSourcePagingSource(source) {
    // Display name
    abstract val name: String

    // Localized category name
    open val category: StringResource = SYMR.strings.similar_titles

    /**
     * Recommendation sources that display results from a source extension,
     * can override this property to associate results with a specific source.
     * This is used to redirect the user directly to the corresponding AnimeScreen.
     * If null, the user will be prompted to choose a source via global search when clicking on a recommendation.
     */
    open val associatedSourceId: Long? = null

    companion object {
        internal fun createSources(
            anime: Anime,
            recommendationSource: AnimeRecommendationSource,
        ): List<AnimeRecommendationPagingSource> {
            return buildList {
                add(AnimeAniListPagingSource(anime))
                add(AnimeMyAnimeListPagingSource(anime))
            }.sortedWith(compareBy({ it.name }, { it.category.resourceId }))
        }
    }
}

/**
 * General class for anime recommendation sources backed by trackers.
 */
abstract class AnimeTrackerRecommendationPagingSource(
    protected val endpoint: String,
    anime: Anime,
) : AnimeRecommendationPagingSource(anime) {
    private val getTracks: GetAnimeTracks by injectLazy()

    protected val trackerManager: TrackerManager by injectLazy()
    protected val client by lazy { Injekt.get<NetworkHelper>().client }
    protected val json by injectLazy<Json>()

    /**
     * Tracker id associated with the recommendation source.
     *
     * If not null and the tracker is attached to the source anime,
     * the remote id will be used to directly identify the anime on the tracker.
     * Otherwise, a search will be performed using the anime title.
     */
    abstract val associatedTrackerId: Long?

    abstract suspend fun getRecsBySearch(search: String): List<SAnime>
    abstract suspend fun getRecsById(id: String): List<SAnime>

    override suspend fun requestNextPage(currentPage: Int): AnimesPage {
        val tracks = getTracks.await(anime.id)

        val recs = try {
            val id = tracks.find { it.trackerId == associatedTrackerId }?.remoteId
            val results = if (id != null) {
                getRecsById(id.toString())
            } else {
                getRecsBySearch(anime.ogTitle)
            }
            logcat { name + " > Results: " + results.size }

            results.ifEmpty { throw NoResultsException() }
        } catch (e: Exception) {
            // 'No results' should not be logged as it happens frequently and is expected
            if (e !is NoResultsException) {
                logcat(LogPriority.ERROR, e) { name }
            }
            throw e
        }

        return AnimesPage(recs, false)
    }
}

class AnimeRecommendationSource(
    override val id: Long = ANIME_RECOMMENDS_SOURCE,
    sourceManager: AnimeSourceManager = Injekt.get(),
) : AnimeCatalogueSource {
    private val delegate by lazy {
        sourceManager.get(id) as? AnimeCatalogueSource
    }

    override val name: String by lazy { delegate?.name ?: "Recommends Source" }
    override val lang: String by lazy { delegate?.lang ?: "all" }
    override val supportsLatest: Boolean by lazy { delegate?.supportsLatest ?: false }

    override suspend fun getAnimeDetails(anime: SAnime) =
        delegate?.getAnimeDetails(anime)
            ?: throw UnsupportedOperationException()
    override suspend fun getEpisodeList(anime: SAnime) =
        delegate?.getEpisodeList(anime)
            ?: throw UnsupportedOperationException()
    override suspend fun getSeasonList(anime: SAnime) =
        delegate?.getSeasonList(anime)
            ?: throw UnsupportedOperationException()

    override suspend fun getPopularAnime(page: Int) =
        delegate?.getPopularAnime(page)
            ?: throw UnsupportedOperationException()
    override suspend fun getLatestUpdates(page: Int) =
        delegate?.getLatestUpdates(page)
            ?: throw UnsupportedOperationException()
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList) =
        delegate?.getSearchAnime(page, query, filters)
            ?: throw UnsupportedOperationException()
    override fun getFilterList() =
        delegate?.getFilterList()
            ?: throw UnsupportedOperationException()

    @Suppress("DEPRECATION")
    override fun fetchPopularAnime(page: Int) =
        (delegate ?: throw UnsupportedOperationException()).fetchPopularAnime(page)
    @Suppress("DEPRECATION")
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList) =
        (delegate ?: throw UnsupportedOperationException()).fetchSearchAnime(page, query, filters)
    @Suppress("DEPRECATION")
    override fun fetchLatestUpdates(page: Int) =
        (delegate ?: throw UnsupportedOperationException()).fetchLatestUpdates(page)
}

const val ANIME_RECOMMENDS_SOURCE = -1L
