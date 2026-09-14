package eu.kanade.presentation.browse.anime

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.browse.RadioSelectorSearchable
import eu.kanade.presentation.browse.anime.components.GlobalAnimeSearchCardRow
import eu.kanade.presentation.browse.components.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.components.GlobalSearchResultItem
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.presentation.more.settings.LocalPreferenceMinHeight
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.source.getNameForAnimeInfo
import eu.kanade.tachiyomi.ui.browse.animefeed.AnimeFeedScreenModel
import eu.kanade.tachiyomi.ui.browse.animefeed.AnimeFeedScreenState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.source.anime.model.AnimeFeedSavedSearch
import tachiyomi.domain.source.anime.model.AnimeSavedSearch
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus
import kotlin.time.Duration.Companion.seconds

data class AnimeFeedItemUI(
    val feed: AnimeFeedSavedSearch,
    val savedSearch: AnimeSavedSearch?,
    val source: AnimeSource?,
    val title: String,
    val subtitle: String,
    val results: List<Anime>?,
)

internal val AnimeFeedSavedSearch.key inline get() = "anime-feed-$id"

@Composable
fun AnimeFeedScreen(
    state: AnimeFeedScreenState,
    contentPadding: PaddingValues,
    onClickSavedSearch: (AnimeSavedSearch, AnimeSource) -> Unit,
    onClickSource: (AnimeSource) -> Unit,
    onLongClickFeed: (AnimeFeedItemUI) -> Unit,
    onClickAnime: (Anime) -> Unit,
    onLongClickAnime: (Anime) -> Unit,
    selection: List<Anime>,
    onRefresh: () -> Unit,
    getAnimeState: @Composable (Anime) -> State<Anime>,
) {
    when {
        state.isLoading -> LoadingScreen()
        state.isEmpty -> EmptyScreen(
            SYMR.strings.feed_tab_empty,
            modifier = Modifier.padding(contentPadding),
        )
        else -> {
            var refreshing by remember { mutableStateOf(false) }
            LaunchedEffect(refreshing) {
                if (refreshing) {
                    delay(1.seconds)
                    refreshing = false
                }
            }
            PullRefresh(
                refreshing = refreshing && state.isLoadingItems,
                onRefresh = {
                    refreshing = true
                    onRefresh()
                },
                enabled = !state.isLoadingItems,
            ) {
                ScrollbarLazyColumn(
                    contentPadding = contentPadding + topSmallPaddingValues,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    val feeds = state.items.orEmpty()
                    items(
                        items = feeds,
                        key = { it.feed.key },
                    ) { item ->
                        GlobalSearchResultItem(
                            title = item.title,
                            subtitle = item.subtitle,
                            onLongClick = {
                                onLongClickFeed(item)
                            },
                            onClick = {
                                if (item.savedSearch != null && item.source != null) {
                                    onClickSavedSearch(item.savedSearch, item.source)
                                } else if (item.source != null) {
                                    onClickSource(item.source)
                                }
                            },
                            modifier = Modifier.animateItem(),
                        ) {
                            AnimeFeedItem(
                                item = item,
                                getAnimeState = { getAnimeState(it) },
                                onClickAnime = onClickAnime,
                                onLongClickAnime = onLongClickAnime,
                                selection = selection,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AnimeFeedItem(
    item: AnimeFeedItemUI,
    getAnimeState: @Composable ((Anime) -> State<Anime>),
    onClickAnime: (Anime) -> Unit,
    onLongClickAnime: (Anime) -> Unit,
    selection: List<Anime>,
) {
    when {
        item.results == null -> {
            GlobalSearchLoadingResultItem()
        }
        item.results.isEmpty() -> {
            GlobalSearchErrorResultItem(message = stringResource(MR.strings.no_results_found))
        }
        else -> {
            GlobalAnimeSearchCardRow(
                titles = item.results,
                getAnime = getAnimeState,
                onClick = onClickAnime,
                onLongClick = onLongClickAnime,
            )
        }
    }
}

@Composable
fun AnimeFeedAddDialog(
    sources: ImmutableList<AnimeSource>,
    onDismiss: () -> Unit,
    onClickAdd: (AnimeSource?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val sourceList = sources
        .filter { source ->
            if (query.isBlank()) return@filter true
            query.split(",").any {
                val input = it.trim()
                if (input.isEmpty()) return@any false
                source.name.contains(input, ignoreCase = true) ||
                    source.id == input.toLongOrNull()
            }
        }
    val composeOptions: List<@Composable () -> Unit> = sourceList
        .map {
            {
                Text(text = it.getNameForAnimeInfo())
            }
        }
    var selected by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        title = {
            Text(text = stringResource(SYMR.strings.feed))
        },
        text = {
            RadioSelectorSearchable(
                options = composeOptions,
                queryString = query,
                onChangeSearchQuery = {
                    query = it ?: ""
                },
                selected = selected,
            ) {
                selected = it
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onClickAdd(selected?.let { sourceList[it] }) }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}

@Composable
fun AnimeFeedAddSearchDialog(
    source: AnimeSource,
    savedSearches: ImmutableList<AnimeSavedSearch?>,
    onDismiss: () -> Unit,
    onClickAdd: (AnimeSource, AnimeSavedSearch?) -> Unit,
) {
    var selected by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        title = {
            Text(text = source.name)
        },
        text = {
            val context = LocalContext.current
            val savedSearchStrings = remember {
                savedSearches.map {
                    it?.name
                        ?: if ((source as? eu.kanade.tachiyomi.animesource.AnimeCatalogueSource)?.supportsLatest == true) {
                            context.stringResource(MR.strings.latest)
                        } else {
                            context.stringResource(MR.strings.popular)
                        }
                }.toImmutableList()
            }
            RadioSelectorSearchable(
                options = savedSearches,
                optionStrings = savedSearchStrings,
                selected = selected,
            ) {
                selected = it
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = { onClickAdd(source, selected?.let { savedSearches[it] }) },
                enabled = selected != null,
            ) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}

@Composable
fun AnimeFeedActionsDialog(
    feed: AnimeFeedSavedSearch,
    title: String,
    onDismissRequest: () -> Unit,
    onClickDelete: (AnimeFeedSavedSearch) -> Unit,
    modifier: Modifier = Modifier,
) {
    val minHeight = LocalPreferenceMinHeight.current

    AdaptiveSheet(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .padding(
                    vertical = TabbedDialogPaddings.Vertical,
                    horizontal = TabbedDialogPaddings.Horizontal,
                )
                .fillMaxWidth(),
        ) {
            Text(
                modifier = Modifier.padding(TitlePadding),
                text = title,
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(Modifier.height(PaddingSize))

            TextPreferenceWidget(
                title = stringResource(MR.strings.action_delete),
                icon = Icons.Outlined.Delete,
                onPreferenceClick = {
                    onDismissRequest()
                    onClickDelete(feed)
                },
            )

            Row(
                modifier = Modifier
                    .sizeIn(minHeight = minHeight)
                    .clickable { onDismissRequest.invoke() }
                    .padding(ButtonPadding)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                OutlinedButton(onClick = onDismissRequest, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        modifier = Modifier
                            .padding(vertical = 8.dp),
                        text = stringResource(MR.strings.action_cancel),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleLarge,
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

private val PaddingSize = 16.dp

private val ButtonPadding = PaddingValues(top = 16.dp, bottom = 16.dp)

private val TitlePadding = PaddingValues(bottom = 16.dp, top = 8.dp)
