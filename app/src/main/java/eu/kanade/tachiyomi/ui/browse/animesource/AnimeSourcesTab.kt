package eu.kanade.tachiyomi.ui.browse.animesource

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
import eu.kanade.presentation.browse.anime.AnimeSourceOptionsDialog
import eu.kanade.presentation.browse.anime.AnimeSourcesScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.browse.animesource.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.animesource.feed.AnimeSourceFeedScreen
import eu.kanade.tachiyomi.ui.browse.animesource.globalsearch.GlobalAnimeSearchScreen
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.source.anime.interactor.GetRemoteAnime
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun Screen.animeSourcesTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = rememberScreenModel { AnimeSourcesScreenModel() }
    val state by screenModel.state.collectAsState()

    return TabContent(
        titleRes = MR.strings.label_sources,
        actions = persistentListOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = Icons.Outlined.TravelExplore,
                onClick = { navigator.push(GlobalAnimeSearchScreen()) },
            ),
            AppBar.Action(
                title = stringResource(MR.strings.action_filter),
                icon = Icons.Outlined.FilterList,
                onClick = { navigator.push(AnimeSourcesFilterScreen()) },
            ),
        ),
        content = { contentPadding, _ ->
            val stateValue = state
            AnimeSourcesScreen(
                state = stateValue,
                contentPadding = contentPadding,
                onClickSource = { source ->
                    navigator.push(source.createBrowseScreen(GetRemoteAnime.QUERY_POPULAR))
                },
                onClickLatest = { source ->
                    navigator.push(source.createBrowseScreen(GetRemoteAnime.QUERY_LATEST))
                },
                onClickPin = screenModel::togglePin,
                onLongClickSource = screenModel::showSourceDialog,
            )

            stateValue.dialog?.let { dialog ->
                AnimeSourceOptionsDialog(
                    source = dialog.source,
                    isPinned = stateValue.items.values.flatten()
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

private fun AnimeCatalogueSource.createBrowseScreen(listingQuery: String?) =
    (this as? AnimeSourceScreenProvider)?.createBrowseScreen(listingQuery, null)
        ?: if (listingQuery == GetRemoteAnime.QUERY_POPULAR &&
            Injekt.get<UiPreferences>().useNewSourceNavigation().get()
        ) {
            AnimeSourceFeedScreen(id)
        } else {
            BrowseAnimeSourceScreen(id, listingQuery)
        }
