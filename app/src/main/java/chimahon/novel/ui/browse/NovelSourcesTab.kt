package chimahon.novel.ui.browse

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import chimahon.novel.ui.browse.globalsearch.GlobalNovelSearchScreen
import eu.kanade.presentation.browse.novel.NovelSourceOptionsDialog
import eu.kanade.presentation.browse.novel.NovelSourcesScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.novelSourcesTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = rememberScreenModel { NovelSourcesScreenModel() }
    val state by screenModel.state.collectAsState()

    return TabContent(
        titleRes = MR.strings.label_sources,
        actions = persistentListOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = Icons.Outlined.TravelExplore,
                onClick = { navigator.push(GlobalNovelSearchScreen()) },
            ),
            AppBar.Action(
                title = stringResource(MR.strings.action_filter),
                icon = Icons.Outlined.FilterList,
                onClick = { navigator.push(NovelSourcesFilterScreen()) },
            ),
        ),
        content = { contentPadding, _ ->
            NovelSourcesScreen(
                state = state,
                contentPadding = contentPadding,
                onClickSource = { source ->
                    navigator.push(BrowseNovelSourceScreen(null, source.id))
                },
                onClickLatest = { source ->
                    navigator.push(
                        BrowseNovelSourceScreen(
                            null,
                            source.id,
                            BrowseNovelSourceScreenModel.Listing.Latest,
                        ),
                    )
                },
                onClickPin = screenModel::togglePin,
                onLongClickSource = screenModel::showSourceDialog,
            )

            state.dialog?.let { dialog ->
                NovelSourceOptionsDialog(
                    source = dialog.source,
                    isPinned = state.items.values.flatten()
                        .firstOrNull { it.source.id == dialog.source.id }
                        ?.isPinned == true,
                    onClickPin = {
                        screenModel.togglePin(dialog.source)
                        screenModel.closeDialog()
                    },
                    onClickDisable = {
                        screenModel.toggleSource(dialog.source)
                        screenModel.closeDialog()
                    },
                    onDismiss = screenModel::closeDialog,
                )
            }
        },
    )
}
