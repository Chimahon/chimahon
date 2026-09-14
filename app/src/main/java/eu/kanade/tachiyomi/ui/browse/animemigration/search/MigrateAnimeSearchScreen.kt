package eu.kanade.tachiyomi.ui.browse.animemigration.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.anime.MigrateAnimeSearchScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.animesource.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import mihon.feature.animemigration.dialog.MigrateAnimeDialog
import eu.kanade.tachiyomi.ui.browse.animemigration.season.MigrateSeasonSelectScreen
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MigrateAnimeSearchScreen(private val animeId: Long) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { MigrateAnimeSearchScreenModel(animeId = animeId) }
        val state by screenModel.state.collectAsState()
        val currentAnime by produceState<Anime?>(initialValue = null, animeId) {
            value = Injekt.get<GetAnime>().await(animeId)
        }
        var targetAnime by remember { mutableStateOf<Anime?>(null) }

        MigrateAnimeSearchScreen(
            state = state,
            fromSourceId = state.fromSourceId,
            navigateUp = navigator::pop,
            onChangeSearchQuery = screenModel::updateSearchQuery,
            onSearch = { screenModel.search() },
            getAnime = { screenModel.getAnime(it) },
            onChangeSearchFilter = screenModel::setSourceFilter,
            onToggleResults = screenModel::toggleFilterResults,
            onClickSource = {
                currentAnime?.let { current ->
                    navigator.push(BrowseAnimeSourceScreen(it.id, state.searchQuery, current.id))
                }
            },
            onClickItem = { targetAnime = it },
            onLongClickItem = { navigator.push(AnimeScreen(it.id, true)) },
        )

        val current = currentAnime
        val target = targetAnime
        if (current != null && target != null) {
            MigrateAnimeDialog(
                current = current,
                target = target,
                onClickTitle = { navigator.push(AnimeScreen(target.id, true)) },
                onClickSeasons = { navigator.push(MigrateSeasonSelectScreen(current, target)) },
                onDismissRequest = { targetAnime = null },
                onComplete = {
                    targetAnime = null
                    navigator.replace(AnimeScreen(target.id))
                },
            )
        }
    }
}
