package chimahon.novel.ui.browse

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun BrowseNovelSourceToolbar(
    searchQuery: String?,
    onSearchQueryChange: (String?) -> Unit,
    source: NovelSource?,
    displayMode: LibraryDisplayMode?,
    onDisplayModeChange: (LibraryDisplayMode) -> Unit,
    navigateUp: () -> Unit,
    onSearch: (String) -> Unit,
    onWebViewClick: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    onSettingsClick: (() -> Unit)? = null,
) {
    val title = source?.name
    var selectingDisplayMode by remember { mutableStateOf(false) }

    SearchToolbar(
        navigateUp = navigateUp,
        titleContent = { AppBarTitle(title) },
        searchQuery = searchQuery,
        onChangeSearchQuery = onSearchQueryChange,
        onSearch = onSearch,
        onClickCloseSearch = { onSearchQueryChange(null) },
        actions = {
            if (onSettingsClick != null) {
                IconButton(onClick = onSettingsClick) {
                    Icon(Icons.Outlined.Settings, contentDescription = androidx.compose.ui.res.stringResource(eu.kanade.tachiyomi.R.string.opds_settings))
                }
            }
            if (onWebViewClick != null) {
                IconButton(onClick = onWebViewClick) {
                    Icon(Icons.Outlined.Public, contentDescription = stringResource(MR.strings.action_open_in_web_view))
                }
            }
            AppBarActions(
                actions = persistentListOf<AppBar.AppBarAction>().builder()
                    .apply {
                        if (displayMode != null) {
                            add(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_display_mode),
                                    icon = if (displayMode == LibraryDisplayMode.List) {
                                        Icons.AutoMirrored.Filled.ViewList
                                    } else {
                                        Icons.Filled.ViewModule
                                    },
                                    onClick = { selectingDisplayMode = true },
                                ),
                            )
                        }
                    }
                    .build(),
            )

            DropdownMenu(
                expanded = selectingDisplayMode,
                onDismissRequest = { selectingDisplayMode = false },
            ) {
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_comfortable_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.ComfortableGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.ComfortableGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(KMR.strings.action_display_comfortable_grid_panorama)) },
                    isChecked = displayMode == LibraryDisplayMode.ComfortableGridPanorama,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.ComfortableGridPanorama)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.CompactGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.CompactGrid)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_list)) },
                    isChecked = displayMode == LibraryDisplayMode.List,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.List)
                }
                RadioMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_display_cover_only_grid)) },
                    isChecked = displayMode == LibraryDisplayMode.CoverOnlyGrid,
                ) {
                    selectingDisplayMode = false
                    onDisplayModeChange(LibraryDisplayMode.CoverOnlyGrid)
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}
