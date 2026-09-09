package eu.kanade.presentation.library.components

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.SearchToolbar
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.history.model.SearchHistory
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.active

@Composable
fun LibraryToolbar(
    hasActiveFilters: Boolean,
    selectedCount: Int,
    title: LibraryToolbarTitle,
    onClickUnselectAll: () -> Unit,
    onClickSelectAll: () -> Unit,
    onClickInvertSelection: () -> Unit,
    onClickFilter: () -> Unit,
    onClickRefresh: () -> Unit,
    onClickGlobalUpdate: (() -> Unit)?,
    onClickOpenRandomManga: (() -> Unit)?,
    onClickSyncNow: (() -> Unit)?,
    onClickSyncTtu: (() -> Unit)? = null,
    // SY -->
    onClickSyncExh: (() -> Unit)?,
    isSyncEnabled: Boolean,
    // SY <--
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?,
    onInvalidateDownloadCache: ((Context) -> Unit)?,
    titleContent: (@Composable () -> Unit)? = null,
    onClickEditCategories: (() -> Unit)? = null,
    editCategoriesTitle: String? = null,
    updateCategoryTitle: String? = null,
    updateLibraryTitle: String? = null,
    onClickNovelDefaultCategory: (() -> Unit)? = null,
    novelDefaultCategoryTitle: String? = null,
    onClickBrowseSources: (() -> Unit)? = null,
    browseSourcesTitle: String? = null,
) = when {
    selectedCount > 0 -> LibrarySelectionToolbar(
        selectedCount = selectedCount,
        onClickUnselectAll = onClickUnselectAll,
        onClickSelectAll = onClickSelectAll,
        onClickInvertSelection = onClickInvertSelection,
    )
    else -> LibraryRegularToolbar(
        title = title,
        hasFilters = hasActiveFilters,
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        onClickFilter = onClickFilter,
        onClickRefresh = onClickRefresh,
        onClickGlobalUpdate = onClickGlobalUpdate,
        onClickOpenRandomManga = onClickOpenRandomManga,
        onClickSyncNow = onClickSyncNow,
        onClickSyncTtu = onClickSyncTtu,
        // SY -->
        onClickSyncExh = onClickSyncExh,
        isSyncEnabled = isSyncEnabled,
        // SY <--
        scrollBehavior = scrollBehavior,
        onInvalidateDownloadCache = onInvalidateDownloadCache,
        titleContent = titleContent,
        onClickEditCategories = onClickEditCategories,
        editCategoriesTitle = editCategoriesTitle,
        updateCategoryTitle = updateCategoryTitle,
        updateLibraryTitle = updateLibraryTitle,
        onClickBrowseSources = onClickBrowseSources,
        browseSourcesTitle = browseSourcesTitle,
    )
}

@Composable
private fun LibraryRegularToolbar(
    title: LibraryToolbarTitle,
    hasFilters: Boolean,
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    onClickFilter: () -> Unit,
    onClickRefresh: () -> Unit,
    onClickGlobalUpdate: (() -> Unit)?,
    onClickOpenRandomManga: (() -> Unit)?,
    onClickSyncNow: (() -> Unit)?,
    onClickSyncTtu: (() -> Unit)? = null,
    // SY -->
    onClickSyncExh: (() -> Unit)?,
    isSyncEnabled: Boolean,
    // SY <--
    scrollBehavior: TopAppBarScrollBehavior?,
    onInvalidateDownloadCache: ((Context) -> Unit)?,
    titleContent: (@Composable () -> Unit)? = null,
    onClickEditCategories: (() -> Unit)? = null,
    editCategoriesTitle: String? = null,
    updateCategoryTitle: String? = null,
    updateLibraryTitle: String? = null,
    onClickNovelDefaultCategory: (() -> Unit)? = null,
    novelDefaultCategoryTitle: String? = null,
    onClickBrowseSources: (() -> Unit)? = null,
    browseSourcesTitle: String? = null,
) {
    val context = LocalContext.current
    val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
    SearchToolbar(
        titleContent = titleContent ?: {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title.text,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, false),
                    overflow = TextOverflow.Ellipsis,
                )
                if (title.numberOfManga != null) {
                    Pill(
                        text = "${title.numberOfManga}",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = pillAlpha),
                        fontSize = 14.sp,
                    )
                }
            }
        },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        searchHistoryScope = SearchHistory.SCOPE_ANIME_MANGA,
        actions = {
            val filterTint = if (hasFilters) MaterialTheme.colorScheme.active else LocalContentColor.current
            AppBarActions(
                persistentListOf(
                    AppBar.Action(
                        title = stringResource(MR.strings.action_filter),
                        icon = Icons.Outlined.FilterList,
                        iconTint = filterTint,
                        onClick = onClickFilter,
                    ),
                    AppBar.OverflowAction(
                        title = updateCategoryTitle ?: stringResource(MR.strings.action_update_category),
                        onClick = onClickRefresh,
                    ),
                ).builder().apply {
                    if (isSyncEnabled && onClickSyncNow != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(SYMR.strings.sync_library),
                                onClick = onClickSyncNow,
                            ),
                        )
                    }
                    if (onClickSyncTtu != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(SYMR.strings.sync_ttsu),
                                onClick = onClickSyncTtu,
                            ),
                        )
                    }
                    if (onClickGlobalUpdate != null) {
                        add(
                            AppBar.OverflowAction(
                                title = updateLibraryTitle ?: stringResource(MR.strings.action_update_library),
                                onClick = onClickGlobalUpdate,
                            ),
                        )
                    }
                    if (onClickOpenRandomManga != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.action_open_random_manga),
                                onClick = onClickOpenRandomManga,
                            ),
                        )
                    }
                    if (onInvalidateDownloadCache != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.pref_invalidate_download_cache),
                                onClick = {
                                    onInvalidateDownloadCache(context)
                                },
                            ),
                        )
                    }
                    if (onClickEditCategories != null) {
                        add(
                            AppBar.OverflowAction(
                                title = editCategoriesTitle ?: stringResource(MR.strings.action_edit_categories),
                                onClick = onClickEditCategories,
                            ),
                        )
                    }
                    if (onClickNovelDefaultCategory != null) {
                        add(
                            AppBar.OverflowAction(
                                title = novelDefaultCategoryTitle ?: stringResource(MR.strings.default_category),
                                onClick = onClickNovelDefaultCategory,
                            ),
                        )
                    }
                    if (onClickBrowseSources != null) {
                        add(
                            AppBar.OverflowAction(
                                title = browseSourcesTitle ?: "Browse Sources",
                                onClick = onClickBrowseSources,
                            ),
                        )
                    }
                    // SY -->
                    if (onClickSyncExh != null) {
                        add(
                            AppBar.OverflowAction(
                                title = stringResource(SYMR.strings.sync_favorites),
                                onClick = onClickSyncExh,
                            ),
                        )
                    }
                    // SY <--
                }.build(),
            )
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun LibrarySelectionToolbar(
    selectedCount: Int,
    onClickUnselectAll: () -> Unit,
    onClickSelectAll: () -> Unit,
    onClickInvertSelection: () -> Unit,
) {
    AppBar(
        titleContent = { Text(text = "$selectedCount") },
        actions = {
            AppBarActions(
                persistentListOf(
                    AppBar.Action(
                        title = stringResource(MR.strings.action_select_all),
                        icon = Icons.Outlined.SelectAll,
                        onClick = onClickSelectAll,
                    ),
                    AppBar.Action(
                        title = stringResource(MR.strings.action_select_inverse),
                        icon = Icons.Outlined.FlipToBack,
                        onClick = onClickInvertSelection,
                    ),
                ),
            )
        },
        isActionMode = true,
        onCancelActionMode = onClickUnselectAll,
    )
}

@Immutable
data class LibraryToolbarTitle(
    val text: String,
    val numberOfManga: Int? = null,
)
