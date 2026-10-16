package chimahon.novel.opds

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import chimahon.novel.manager.NovelSourceManager
import chimahon.novel.ui.browse.BrowseNovelSourceContent
import chimahon.novel.ui.browse.BrowseNovelSourceToolbar
import chimahon.novel.ui.detail.NovelDetailScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import eu.kanade.tachiyomi.ui.browse.novel.NovelSourcePreferencesScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class OpdsBrowseScreen(private val catalogId: String? = null, private val feedUrl: String? = null) : Screen() {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val source = remember { Injekt.get<NovelSourceManager>().getNovelSource(OpdsSource.ID) as OpdsSource }
        val model = rememberScreenModel { OpdsBrowseModel(source, catalogId, feedUrl) }
        val state by model.state.collectAsState()
        var search by remember { mutableStateOf<String?>(null) }
        var displayMode by remember { mutableStateOf<LibraryDisplayMode>(LibraryDisplayMode.default) }
        var catalogsExpanded by remember { mutableStateOf(false) }
        LaunchedEffect(source) {
            source.store.catalogChanges.collect { model.refresh() }
        }

        Scaffold(topBar = {
            BrowseNovelSourceToolbar(
                searchQuery = search,
                onSearchQueryChange = { search = it; if (it == null) model.refresh() },
                source = source,
                displayMode = displayMode,
                onDisplayModeChange = { displayMode = it },
                navigateUp = navigator::pop,
                onSearch = model::search,
                onSettingsClick = { navigator.push(NovelSourcePreferencesScreen(OpdsSource.ID)) },
            )
        }) { padding ->
            Column(Modifier.padding(padding)) {
                if (catalogId == null) {
                    Box {
                        TextButton(onClick = { catalogsExpanded = true }) {
                            Text(state.catalog?.name ?: stringResource(R.string.opds_catalogs))
                        }
                        DropdownMenu(expanded = catalogsExpanded, onDismissRequest = { catalogsExpanded = false }) {
                            source.store.catalogs().forEach { catalog ->
                                DropdownMenuItem(text = { Text(catalog.name) }, onClick = {
                                    catalogsExpanded = false
                                    search = null
                                    model.select(catalog)
                                })
                            }
                        }
                    }
                }
                state.feed?.title?.takeIf { it.isNotBlank() }?.let {
                    Text(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                val navigation = state.feed?.navigation.orEmpty()
                if (navigation.isNotEmpty()) {
                    LazyColumn(
                        modifier = if (state.books.isEmpty()) Modifier.weight(1f) else Modifier.heightIn(max = 200.dp),
                    ) {
                        items(navigation, key = { it.url }) { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    state.catalog?.let { navigator.push(OpdsBrowseScreen(it.id, entry.url)) }
                                }.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Icon(Icons.Outlined.Folder, contentDescription = null)
                                Text(entry.title)
                            }
                        }
                    }
                }
                if (state.books.isNotEmpty() || navigation.isEmpty()) {
                    Box(Modifier.weight(1f)) {
                        BrowseNovelSourceContent(
                            sourceId = source.id,
                            novels = state.books,
                            isLoading = state.loading,
                            hasNextPage = state.feed?.next != null,
                            error = state.error ?: if (state.catalog == null) stringResource(R.string.opds_empty) else null,
                            columns = GridCells.Adaptive(120.dp),
                            displayMode = displayMode,
                            contentPadding = PaddingValues(),
                            onNovelClick = { navigator.push(NovelDetailScreen(it, source.id)) },
                            onLoadMore = model::loadMore,
                            onRequestCover = model::requestCover,
                            onRetryClick = model::refresh,
                        )
                    }
                } else if (state.feed?.next != null) {
                    TextButton(onClick = model::loadMore) { Text(stringResource(R.string.opds_load_more)) }
                }
            }
        }
    }
}

private class OpdsBrowseModel(
    private val source: OpdsSource,
    private val catalogId: String?,
    private val feedUrl: String?,
) : StateScreenModel<OpdsBrowseModel.State>(State()) {
    private var job: Job? = null
    private val requestedCovers = mutableSetOf<String>()
    private val coverSemaphore = kotlinx.coroutines.sync.Semaphore(4)
    private var query: String? = null

    fun select(catalog: OpdsCatalog) {
        source.store.select(catalog.id)
        query = null
        refresh()
    }

    fun refresh() {
        val catalog = catalogId?.let { id -> source.store.catalogs().find { it.id == id } } ?: source.store.active()
        if (catalog == null) {
            job?.cancel()
            mutableState.value = State()
            return
        }
        load(catalog, feedUrl ?: catalog.url, append = false, searchQuery = query)
    }

    fun search(value: String) {
        query = value.takeIf { it.isNotBlank() }
        refresh()
    }

    fun loadMore() {
        val state = state.value
        if (state.loading) return
        val catalog = state.catalog ?: return
        load(catalog, state.feed?.next ?: return, append = true)
    }

    private fun load(catalog: OpdsCatalog, url: String, append: Boolean, searchQuery: String? = null) {
        job?.cancel()
        if (!append) requestedCovers.clear()
        job = screenModelScope.launch {
            mutableState.value = if (append) state.value.copy(loading = true, error = null) else State(catalog = catalog, loading = true)
            try {
                var feed = source.loadFeed(catalog, url)
                if (searchQuery != null) feed = source.loadFeed(catalog, source.searchUrl(catalog, feed, searchQuery))
                val previous = if (append) state.value.books else emptyList()
                val navigation = if (append) (state.value.feed?.navigation.orEmpty() + feed.navigation).distinctBy { it.url } else feed.navigation
                mutableState.value = State(
                    catalog = catalog,
                    feed = feed.copy(navigation = navigation),
                    books = (previous + feed.books.map(source::novel)).distinctBy { it.url },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Avoid displaying exceptions containing tokenized feed URLs.
                mutableState.value = state.value.copy(loading = false, error = "Could not load this OPDS catalog. Check its URL, login, and connection.")
            }
        }
    }

    fun requestCover(novel: SNNovel) {
        if (!requestedCovers.add(novel.url)) return
        screenModelScope.launch {
            try {
                val details = coverSemaphore.withPermit { source.getNovelDetails(novel) }
                mutableState.value = state.value.copy(books = state.value.books.map { if (it.url == novel.url) details else it })
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                requestedCovers.remove(novel.url)
            }
        }
    }

    data class State(
        val catalog: OpdsCatalog? = null,
        val feed: OpdsFeed? = null,
        val books: List<SNNovel> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )
}
