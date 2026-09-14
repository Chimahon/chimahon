package exh.recs

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifAnimeSourcesLoaded
import eu.kanade.presentation.browse.anime.components.AnimeBulkFavoriteDialogs
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteAnimeScreenModel
import eu.kanade.tachiyomi.ui.browse.animesource.globalsearch.GlobalAnimeSearchScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import exh.recs.components.AnimeRecommendsScreen
import exh.recs.sources.ANIME_RECOMMENDS_SOURCE
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import java.io.Serializable

class AnimeRecommendsScreen(private val args: Args) : Screen() {

    sealed interface Args : Serializable {
        data class SingleSourceAnime(val animeId: Long, val sourceId: Long) : Args
    }

    @Composable
    override fun Content() {
        if (!ifAnimeSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val screenModel = rememberScreenModel { AnimeRecommendsScreenModel(args) }
        val state by screenModel.state.collectAsState()

        val bulkFavoriteScreenModel = rememberScreenModel { BulkFavoriteAnimeScreenModel() }
        val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()

        val haptic = LocalHapticFeedback.current

        BackHandler(enabled = bulkFavoriteState.selectionMode) {
            bulkFavoriteScreenModel.backHandler()
        }

        val onClickItem = { anime: Anime ->
            navigator.push(
                when (anime.source) {
                    ANIME_RECOMMENDS_SOURCE -> GlobalAnimeSearchScreen(anime.title)
                    else -> AnimeScreen(anime.id, true)
                },
            )
        }

        val onLongClickItem = { anime: Anime ->
            when (anime.source) {
                ANIME_RECOMMENDS_SOURCE -> WebViewActivity.newIntent(context, anime.url, title = anime.title).let(context::startActivity)
                else -> {
                    // Add to favorite
                    bulkFavoriteScreenModel.addRemoveAnime(
                        anime,
                        haptic,
                    )
                }
            }
        }

        AnimeRecommendsScreen(
            title = if (args is Args.SingleSourceAnime) {
                stringResource(SYMR.strings.similar, state.title.orEmpty())
            } else {
                stringResource(SYMR.strings.rec_common_recommendations)
            },
            state = state,
            navigateUp = navigator::pop,
            getAnime = @Composable { anime: Anime -> screenModel.getAnime(anime) },
            onClickSource = {
                // TODO: picks up AnimeBrowseRecommendsScreen once per-source full browsing exists.
                // Tracker sources return a single page, so all results are already shown inline.
            },
            onClickItem = { onClickItem(it) },
            onLongClickItem = { onLongClickItem(it) },
        )

        AnimeBulkFavoriteDialogs(
            bulkFavoriteScreenModel = bulkFavoriteScreenModel,
            dialog = bulkFavoriteState.dialog,
        )
    }
}
