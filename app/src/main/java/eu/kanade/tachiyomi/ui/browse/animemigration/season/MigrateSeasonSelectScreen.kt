package eu.kanade.tachiyomi.ui.browse.animemigration.season

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifAnimeSourcesLoaded
import eu.kanade.presentation.browse.anime.BrowseAnimeSourceContent
import eu.kanade.presentation.browse.anime.MissingSourceScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.browse.animesource.AnimeSourceScreenProvider
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import mihon.feature.animemigration.dialog.MigrateAnimeDialog
import mihon.feature.animemigration.dialog.SelectAnimeDialog
import mihon.feature.animemigration.list.AnimeMigrationListScreen
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.core.common.Constants
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.source.anime.model.StubAnimeSource
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.entries.anime.LocalAnimeSource

data class MigrateSeasonSelectScreen(
    private val oldAnime: tachiyomi.domain.entries.anime.model.Anime,
    private val anime: tachiyomi.domain.entries.anime.model.Anime,
    private val isFromList: Boolean = false,
) : Screen() {
    @Composable
    override fun Content() {
        if (!ifAnimeSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val uriHandler = LocalUriHandler.current
        val navigator = LocalNavigator.currentOrThrow

        val screenModel = rememberScreenModel { MigrateSeasonSelectScreenModel(anime) }
        val state by screenModel.state.collectAsState()

        if (screenModel.source is StubAnimeSource) {
            MissingSourceScreen(
                source = screenModel.source,
                navigateUp = navigator::pop,
            )
            return
        }

        val snackbarHostState = remember { SnackbarHostState() }
        val onHelpClick = { uriHandler.openUri(LocalAnimeSource.HELP_URL) }
        val onWebViewClick = f@{
            val source = screenModel.source

            val animeHttpSource = source as? AnimeHttpSource
            val animeSourceScreenProvider = source as? AnimeSourceScreenProvider
            if (animeHttpSource == null && animeSourceScreenProvider == null) {
                return@f
            }

            navigator.push(
                if (animeSourceScreenProvider != null) {
                    animeSourceScreenProvider.createBrowseScreen(null, null)
                } else {
                    WebViewScreen(
                        url = animeHttpSource!!.baseUrl,
                        initialTitle = animeHttpSource.name,
                        sourceId = animeHttpSource.id,
                    )
                },
            )
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = anime.title,
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            val openDialog: (tachiyomi.domain.entries.anime.model.Anime) -> Unit = {
                val dialog = if (isFromList) {
                    MigrateSeasonSelectScreenModel.Dialog.Select(anime = it)
                } else {
                    MigrateSeasonSelectScreenModel.Dialog.Migrate(newAnime = it, oldAnime = oldAnime)
                }
                screenModel.setDialog(dialog)
            }
            BrowseAnimeSourceContent(
                source = screenModel.source,
                animeList = screenModel.seasonPagerFlowFlow.collectAsLazyPagingItems(),
                columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
                entries = screenModel.getColumnsPreferenceForCurrentOrientation(LocalConfiguration.current.orientation),
                displayMode = screenModel.displayMode ?: LibraryDisplayMode.default,
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                onWebViewClick = onWebViewClick,
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalAnimeSourceHelpClick = onHelpClick,
                onAnimeClick = { openDialog(it) },
                onAnimeLongClick = { navigator.push(AnimeScreen(it.id, true)) },
            )
        }

        val onDismissRequest = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is MigrateSeasonSelectScreenModel.Dialog.Migrate -> {
                MigrateAnimeDialog(
                    current = dialog.oldAnime,
                    target = dialog.newAnime,
                    onClickTitle = { navigator.push(AnimeScreen(dialog.newAnime.id)) },
                    onClickSeasons = { navigator.push(MigrateSeasonSelectScreen(oldAnime, dialog.newAnime)) },
                    onDismissRequest = onDismissRequest,
                    onComplete = {
                        val animeScreen = navigator.items
                            .filterIsInstance<AnimeScreen>()
                            .lastOrNull()

                        if (animeScreen != null) {
                            navigator.popUntil { it is AnimeScreen }
                            navigator.push(AnimeScreen(dialog.newAnime.id))
                        }
                    },
                )
            }
            is MigrateSeasonSelectScreenModel.Dialog.Select -> {
                SelectAnimeDialog(
                    selected = dialog.anime,
                    onDismissRequest = onDismissRequest,
                    onClickTitle = { navigator.push(AnimeScreen(dialog.anime.id)) },
                    onClickSeasons = { navigator.push(MigrateSeasonSelectScreen(oldAnime, dialog.anime, true)) },
                    onClickSelect = {
                        val migrationListScreen = navigator.items
                            .filterIsInstance<AnimeMigrationListScreen>()
                            .last()
                        migrationListScreen.addMatchOverride(current = oldAnime.id, target = dialog.anime.id)
                        navigator.popUntil { screen -> screen is AnimeMigrationListScreen }
                    },
                )
            }
            null -> {}
        }
    }
}
