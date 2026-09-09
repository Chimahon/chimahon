package chimahon.novel.ui.browse.globalsearch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import chimahon.novel.manager.NovelSourceManager
import chimahon.novel.ui.browse.BrowseNovelSourceScreen
import chimahon.novel.ui.detail.NovelDetailScreen
import eu.kanade.presentation.browse.novel.GlobalNovelSearchScreen
import eu.kanade.presentation.util.Screen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class GlobalNovelSearchScreen(
    val searchQuery: String = "",
) : Screen() {

    @Composable
    override fun Content() {
        val sourcesReady by remember { Injekt.get<NovelSourceManager>().isInitialized }.collectAsState()
        if (!sourcesReady) {
            LoadingScreen()
            return
        }

        val navigator = LocalNavigator.currentOrThrow

        val screenModel = rememberScreenModel {
            GlobalNovelSearchScreenModel(
                initialQuery = searchQuery,
            )
        }
        val state by screenModel.state.collectAsState()

        GlobalNovelSearchScreen(
            state = state,
            navigateUp = navigator::pop,
            onChangeSearchQuery = screenModel::updateSearchQuery,
            onSearch = { screenModel.search() },
            onChangeSearchFilter = screenModel::setSourceFilter,
            onToggleResults = screenModel::toggleFilterResults,
            onClickSource = {
                navigator.push(BrowseNovelSourceScreen(null, it.id))
            },
            onClickItem = { novel, sourceId -> navigator.push(NovelDetailScreen(novel, sourceId)) },
            onLongClickItem = { novel, sourceId -> navigator.push(NovelDetailScreen(novel, sourceId)) },
        )
    }
}
