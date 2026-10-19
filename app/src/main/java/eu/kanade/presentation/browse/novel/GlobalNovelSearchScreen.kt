package eu.kanade.presentation.browse.novel

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import chimahon.novel.ui.browse.globalsearch.NovelSearchItemResult
import chimahon.novel.ui.browse.globalsearch.NovelSearchScreenModel
import chimahon.novel.ui.browse.globalsearch.NovelSourceFilter
import eu.kanade.presentation.browse.GlobalSearchErrorResultItem
import eu.kanade.presentation.browse.GlobalSearchLoadingResultItem
import eu.kanade.presentation.browse.GlobalSearchResultItem
import eu.kanade.presentation.browse.novel.components.GlobalNovelSearchCardRow
import eu.kanade.presentation.browse.novel.components.GlobalNovelSearchToolbar
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

@Composable
fun GlobalNovelSearchScreen(
    state: NovelSearchScreenModel.State,
    navigateUp: () -> Unit,
    onChangeSearchQuery: (String?) -> Unit,
    onSearch: (String) -> Unit,
    onRetry: () -> Unit,
    onChangeSearchFilter: (NovelSourceFilter) -> Unit,
    onToggleResults: () -> Unit,
    onClickSource: (NovelsPageSource) -> Unit,
    onClickItem: (SNNovel, Long) -> Unit,
    onLongClickItem: (SNNovel, Long) -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            GlobalNovelSearchToolbar(
                searchQuery = state.searchQuery,
                progress = state.progress,
                total = state.total,
                navigateUp = navigateUp,
                onChangeSearchQuery = onChangeSearchQuery,
                onSearch = onSearch,
                sourceFilter = state.sourceFilter,
                onChangeSearchFilter = onChangeSearchFilter,
                onlyShowHasResults = state.onlyShowHasResults,
                onToggleResults = onToggleResults,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        if (state.filteredItems.isEmpty()) {
            EmptyScreen(stringRes = MR.strings.no_results_found, modifier = Modifier.padding(paddingValues))
        } else {
            GlobalNovelSearchContent(
                items = state.filteredItems,
                contentPadding = paddingValues,
                onClickSource = onClickSource,
                onClickItem = onClickItem,
                onLongClickItem = onLongClickItem,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun GlobalNovelSearchContent(
    items: Map<NovelsPageSource, NovelSearchItemResult>,
    contentPadding: PaddingValues,
    onRetry: () -> Unit,
    onClickSource: (NovelsPageSource) -> Unit,
    onClickItem: (SNNovel, Long) -> Unit,
    onLongClickItem: (SNNovel, Long) -> Unit,
) {
    LazyColumn(
        contentPadding = contentPadding,
    ) {
        items.forEach { (source, result) ->
            item(key = source.id) {
                GlobalSearchResultItem(
                    title = source.name,
                    subtitle = LocaleHelper.getLocalizedDisplayName(source.lang),
                    onClick = { onClickSource(source) },
                    modifier = Modifier.animateItem(),
                ) {
                    when (result) {
                        NovelSearchItemResult.Loading -> {
                            GlobalSearchLoadingResultItem()
                        }
                        is NovelSearchItemResult.Success -> {
                            GlobalNovelSearchCardRow(
                                titles = result.result,
                                sourceId = source.id,
                                onClick = { onClickItem(it, source.id) },
                                onLongClick = { onLongClickItem(it, source.id) },
                            )
                        }
                        is NovelSearchItemResult.Error -> {
                            GlobalSearchErrorResultItem(message = result.throwable.message)
                            TextButton(onClick = onRetry) {
                                Text(stringResource(MR.strings.action_retry))
                            }
                        }
                    }
                }
            }
        }
    }
}
