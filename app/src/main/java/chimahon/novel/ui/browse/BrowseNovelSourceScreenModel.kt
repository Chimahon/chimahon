package chimahon.novel.ui.browse

import android.content.res.Configuration
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import chimahon.novel.manager.NovelSourceManager
import eu.kanade.core.preference.asState
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Serializable

class BrowseNovelSourceScreenModel(
    val sourceId: Long,
    private val initialListing: Listing? = null,
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    sourcePreferences: SourcePreferences = Injekt.get(),
) : StateScreenModel<BrowseNovelSourceScreenModel.State>(State()) {

    private var currentPage = 1
    private var listingJob: Job? = null
    private var listingGeneration = 0

    private val sourceManager: NovelSourceManager = Injekt.get()
    val source: NovelSource? get() = sourceManager.getNovelSource(sourceId)

    var displayMode by sourcePreferences.sourceDisplayMode().asState(screenModelScope)

    private val pageSource: NovelsPageSource?
        get() = source as? NovelsPageSource

    private val coverSemaphore = Semaphore(4)
    private val coverRequested = mutableSetOf<String>()
    // Covers that resolved blank (syosetu has none) — never refetch within a
    // listing lifetime; scrolling normally retriggers LaunchedEffect per item.
    private val coverFailed = mutableSetOf<String>()

    var currentFilters by mutableStateOf<eu.kanade.tachiyomi.source.model.FilterList>(eu.kanade.tachiyomi.source.model.FilterList())
        private set

    init {
        loadFilters()
        when (val listing = initialListing) {
            is Listing.Search -> if (listing.query.isNotBlank()) {
                loadListing(listing, reset = true)
            } else {
                loadListing(Listing.Popular, reset = true)
            }
            Listing.Latest -> loadListing(Listing.Latest, reset = true)
            else -> loadListing(Listing.Popular, reset = true)
        }
    }

    private fun loadFilters() {
        val src = source as? eu.kanade.tachiyomi.sourcenovel.NovelsPageSource ?: return
        currentFilters = try { src.getFilterList() } catch (_: Exception) { eu.kanade.tachiyomi.source.model.FilterList() }
    }

    fun setFilters(newFilters: eu.kanade.tachiyomi.source.model.FilterList) {
        currentFilters = newFilters
    }

    fun resetFilters() {
        loadFilters()
    }

    fun requestCover(novel: SNNovel) {
        if (!novel.thumbnail_url.isNullOrBlank()) return
        if (novel.url in coverFailed) return
        val src = source ?: return
        if (!coverRequested.add(novel.url)) return
        val generation = listingGeneration
        screenModelScope.launch {
            try {
                val cover = coverSemaphore.withPermit {
                    withContext(Dispatchers.IO) { src.getNovelDetails(novel).thumbnail_url }
                }
                if (generation != listingGeneration) return@launch
                if (cover.isNullOrBlank()) {
                    coverFailed.add(novel.url)
                    return@launch
                }
                val current = mutableState.value.novels
                val index = current.indexOfFirst { it.url == novel.url }
                if (index < 0 || !current[index].thumbnail_url.isNullOrBlank()) return@launch
                mutableState.value = mutableState.value.copy(
                    novels = current.toMutableList()
                        .apply { this[index] = this[index].copy(thumbnail_url = cover) }
                        .toImmutableList(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == listingGeneration) coverRequested.remove(novel.url)
            }
        }
    }

    fun loadListing(listing: Listing, reset: Boolean = false) {
        val ps = pageSource ?: return
        if (!reset && mutableState.value.isLoading) return
        if (reset) {
            listingJob?.cancel()
            listingGeneration++
            currentPage = 1
            coverRequested.clear()
            coverFailed.clear()
        }
        val generation = listingGeneration
        val pageNumber = currentPage
        val filters = currentFilters
        mutableState.value = mutableState.value.copy(
            novels = if (reset) persistentListOf() else mutableState.value.novels,
            listing = listing,
            isLoading = true,
            loadedPage = if (reset) 0 else mutableState.value.loadedPage,
            error = null,
            hasNextPage = if (reset) true else mutableState.value.hasNextPage,
        )
        listingJob = screenModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    when (listing) {
                        Listing.Popular -> ps.getPopularNovels(pageNumber)
                        Listing.Latest -> if (ps.supportsLatest) {
                            ps.getLatestUpdates(pageNumber)
                        } else {
                            ps.getPopularNovels(pageNumber)
                        }
                        is Listing.Search -> ps.getSearchNovels(pageNumber, listing.query, filters)
                    }
                }
                if (generation != listingGeneration) return@launch
                mutableState.value = mutableState.value.copy(
                    novels = (mutableState.value.novels + page.novels).distinctBy { it.url }.toImmutableList(),
                    isLoading = false,
                    hasNextPage = page.hasNextPage,
                    loadedPage = pageNumber,
                )
                currentPage = pageNumber + 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == listingGeneration) {
                    mutableState.value = mutableState.value.copy(isLoading = false, error = e.message ?: e.toString())
                }
            }
        }
    }

    fun retry() {
        loadListing(mutableState.value.listing)
    }

    fun loadNextPage() {
        val s = mutableState.value
        if (!s.hasNextPage || s.isLoading || s.error != null) return
        loadListing(s.listing)
    }

    fun search(query: String) {
        loadListing(Listing.Search(query), reset = true)
    }

    fun searchWithFilters(query: String, filters: eu.kanade.tachiyomi.source.model.FilterList) {
        setFilters(filters)
        loadListing(Listing.Search(query), reset = true)
    }

    fun applyFilters(filters: eu.kanade.tachiyomi.source.model.FilterList) {
        setFilters(filters)
        val query = (mutableState.value.listing as? Listing.Search)?.query ?: ""
        loadListing(Listing.Search(query), reset = true)
    }

    fun getColumnsPreference(orientation: Int): GridCells {
        val columns = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            libraryPreferences.novelLandscapeColumns().get()
        } else {
            libraryPreferences.novelPortraitColumns().get()
        }
        return if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
    }

    sealed class Listing(open val query: String?) : Serializable {
        data object Popular : Listing(null)
        data object Latest : Listing(null)
        data class Search(override val query: String) : Listing(query)
    }

    @Immutable
    data class State(
        val novels: ImmutableList<SNNovel> = persistentListOf(),
        val isLoading: Boolean = false,
        val hasNextPage: Boolean = true,
        val loadedPage: Int = 0,
        val listing: Listing = Listing.Popular,
        val error: String? = null,
    )
}
