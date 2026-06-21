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
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class BrowseNovelSourceScreenModel(
    val source: NovelSource,
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) : StateScreenModel<BrowseNovelSourceScreenModel.State>(State()) {

    private var currentPage = 1

    var displayMode: LibraryDisplayMode by mutableStateOf(LibraryDisplayMode.CompactGrid)

    private val pageSource: NovelsPageSource?
        get() = source as? NovelsPageSource

    init {
        loadListing(Listing.Popular, reset = true)
    }

    fun loadListing(listing: Listing, reset: Boolean = false) {
        if (reset) currentPage = 1
        val ps = pageSource ?: return
        screenModelScope.launch {
            mutableState.value = mutableState.value.copy(isLoading = true, error = null)
            try {
                val page = when (listing) {
                    Listing.Popular -> ps.getPopularNovels(currentPage)
                    Listing.Latest -> if (ps.supportsLatest) ps.getLatestUpdates(currentPage) else ps.getPopularNovels(currentPage)
                    is Listing.Search -> ps.getSearchNovels(currentPage, listing.query, eu.kanade.tachiyomi.source.model.FilterList())
                }
                val existingIds = mutableState.value.novels.map { it.url }.toSet()
                val newNovels = if (reset) page.novels else mutableState.value.novels + page.novels.filter { it.url !in existingIds }
                mutableState.value = mutableState.value.copy(
                    novels = newNovels.toImmutableList(),
                    isLoading = false,
                    hasNextPage = page.hasNextPage,
                    listing = listing,
                )
                currentPage++
            } catch (e: Exception) {
                mutableState.value = mutableState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun loadNextPage() {
        val s = mutableState.value
        if (!s.hasNextPage || s.isLoading) return
        loadListing(s.listing)
    }

    fun search(query: String) {
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

    sealed class Listing(open val query: String?) {
        data object Popular : Listing(null)
        data object Latest : Listing(null)
        data class Search(override val query: String) : Listing(query)
    }

    @Immutable
    data class State(
        val novels: ImmutableList<SNNovel> = persistentListOf(),
        val isLoading: Boolean = false,
        val hasNextPage: Boolean = true,
        val listing: Listing = Listing.Popular,
        val error: String? = null,
    )
}
