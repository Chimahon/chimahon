package chimahon.novel.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import chimahon.novel.model.NovelServer
import chimahon.novel.ui.detail.NovelDetailScreen
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.util.Screen
import java.security.MessageDigest
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus

data class BrowseNovelSourceScreen(
    private val server: NovelServer?,
    private val source: NovelSource,
) : Screen() {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { BrowseNovelSourceScreenModel(source) }
        val state by screenModel.state.collectAsState()

        val navigator = LocalNavigator.currentOrThrow
        val snackbarHostState = remember { SnackbarHostState() }

        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {},
                ) {
                    BrowseNovelSourceToolbar(
                        searchQuery = null,
                        onSearchQueryChange = {},
                        source = source,
                        displayMode = screenModel.displayMode,
                        onDisplayModeChange = { screenModel.displayMode = it },
                        navigateUp = navigator::pop,
                        onSearch = screenModel::search,
                    )

                    if (source is NovelsPageSource) {
                        Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = MaterialTheme.padding.small),
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        ) {
                            FilterChip(
                                selected = state.listing is BrowseNovelSourceScreenModel.Listing.Popular,
                                onClick = { screenModel.loadListing(BrowseNovelSourceScreenModel.Listing.Popular, reset = true) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Favorite,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = { Text(stringResource(MR.strings.popular)) },
                            )
                            if (source.supportsLatest) {
                                FilterChip(
                                    selected = state.listing is BrowseNovelSourceScreenModel.Listing.Latest,
                                    onClick = { screenModel.loadListing(BrowseNovelSourceScreenModel.Listing.Latest, reset = true) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.NewReleases,
                                            contentDescription = null,
                                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                                        )
                                    },
                                    label = { Text(stringResource(MR.strings.latest)) },
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { paddingValues ->
            BrowseNovelSourceContent(
                sourceId = source.id,
                novels = state.novels,
                isLoading = state.isLoading,
                hasNextPage = state.hasNextPage,
                error = state.error,
                columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
                displayMode = screenModel.displayMode,
                contentPadding = paddingValues,
                onNovelClick = { navigator.push(NovelDetailScreen(it, source)) },
                onLoadMore = screenModel::loadNextPage,
            )
        }
    }
}

@Composable
private fun BrowseNovelSourceContent(
    sourceId: Long,
    novels: List<SNNovel>,
    isLoading: Boolean,
    hasNextPage: Boolean,
    error: String?,
    columns: GridCells,
    displayMode: LibraryDisplayMode,
    contentPadding: PaddingValues,
    onNovelClick: (SNNovel) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (novels.isEmpty() && isLoading) {
        LoadingScreen(Modifier.padding(contentPadding))
        return
    }

    if (novels.isEmpty()) {
        EmptyScreen(
            modifier = Modifier.padding(contentPadding),
            message = error ?: stringResource(MR.strings.no_results_found),
        )
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (lastVisible >= novels.size - 3 && hasNextPage && !isLoading) {
            onLoadMore()
        }
    }

    when (displayMode) {
        LibraryDisplayMode.ComfortableGrid -> {
            BrowseNovelSourceComfortableGrid(
                novels = novels,
                sourceId = sourceId,
                columns = columns,
                contentPadding = contentPadding,
                isLoading = isLoading,
                onNovelClick = onNovelClick,
            )
        }
        else -> {
            BrowseNovelSourceCompactGrid(
                novels = novels,
                sourceId = sourceId,
                columns = columns,
                contentPadding = contentPadding,
                isLoading = isLoading,
                onNovelClick = onNovelClick,
            )
        }
    }
}

@Composable
private fun BrowseNovelSourceCompactGrid(
    novels: List<SNNovel>,
    sourceId: Long,
    columns: GridCells,
    contentPadding: PaddingValues,
    isLoading: Boolean,
    onNovelClick: (SNNovel) -> Unit,
) {
    LazyVerticalGrid(
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        items(novels, key = { it.url }) { novel ->
            MangaCompactGridItem(
                title = novel.title,
                coverData = MangaCover(
                    mangaId = stableStringHash(novel.url),
                    sourceId = sourceId,
                    isMangaFavorite = false,
                    ogUrl = novel.thumbnail_url,
                    lastModified = 0L,
                ),
                onClick = { onNovelClick(novel) },
                onLongClick = {},
            )
        }
        if (isLoading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun BrowseNovelSourceComfortableGrid(
    novels: List<SNNovel>,
    sourceId: Long,
    columns: GridCells,
    contentPadding: PaddingValues,
    isLoading: Boolean,
    onNovelClick: (SNNovel) -> Unit,
) {
    LazyVerticalGrid(
        columns = columns,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
        horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
    ) {
        items(novels, key = { it.url }) { novel ->
            MangaComfortableGridItem(
                title = novel.title,
                coverData = MangaCover(
                    mangaId = stableStringHash(novel.url),
                    sourceId = sourceId,
                    isMangaFavorite = false,
                    ogUrl = novel.thumbnail_url,
                    lastModified = 0L,
                ),
                usePanoramaCover = false,
                onClick = { onNovelClick(novel) },
                onLongClick = {},
            )
        }
        if (isLoading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

private fun stableStringHash(input: String): Long {
    val hash = MessageDigest.getInstance("MD5").digest(input.toByteArray())
    return (0..7).fold(0L) { acc, i ->
        acc or ((hash[i].toLong() and 0xff) shl (8 * (7 - i)))
    } and Long.MAX_VALUE
}
