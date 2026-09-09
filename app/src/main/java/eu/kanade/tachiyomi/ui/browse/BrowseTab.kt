package eu.kanade.tachiyomi.ui.browse

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab as M3Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import cafe.adriel.voyager.core.model.rememberScreenModel
import dev.icerock.moko.resources.StringResource
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.core.preference.asState
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.BulkSelectionToolbar
import eu.kanade.presentation.components.SearchToolbar
import tachiyomi.domain.history.model.SearchHistory
import eu.kanade.presentation.components.TabContent
import tachiyomi.presentation.core.components.material.TabText
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.DiscordScreen
import eu.kanade.tachiyomi.ui.browse.animeextension.AnimeExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.animeextension.animeExtensionsTab
import eu.kanade.tachiyomi.ui.browse.animemigration.sources.migrateAnimeSourceTab
import eu.kanade.tachiyomi.ui.browse.animesource.animeSourcesTab
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.extension.extensionsTab
import eu.kanade.tachiyomi.ui.browse.novelextension.NovelExtensionsScreenModel
import eu.kanade.tachiyomi.ui.browse.novelextension.novelExtensionsTab
import eu.kanade.tachiyomi.ui.browse.feed.FeedScreenModel
import eu.kanade.tachiyomi.ui.browse.feed.feedTab
import eu.kanade.tachiyomi.ui.browse.migration.sources.migrateSourceTab
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchScreen
import eu.kanade.tachiyomi.ui.browse.source.sourcesTab
import chimahon.novel.ui.browse.novelSourcesTab
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

enum class BrowseViewMode(val labelRes: StringResource) {
    Sources(MR.strings.manga_singular),
    Anime(MR.strings.label_anime),
    Novels(MR.strings.novel_singular),
}

data object BrowseTab : Tab {
    private fun readResolve(): Any = BrowseTab

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_browse_enter)
            return TabOptions(
                index = 3u,
                title = stringResource(MR.strings.browse),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(GlobalSearchScreen())
    }

    private val switchToExtensionTabChannel = Channel<BrowseViewMode>(1, BufferOverflow.DROP_OLDEST)

    private val modeSelectionEvent = Channel<BrowseViewMode>(Channel.BUFFERED)

    suspend fun selectBrowseMode(mode: BrowseViewMode) {
        modeSelectionEvent.send(mode)
    }

    fun showExtension() {
        switchToExtensionTabChannel.trySend(BrowseViewMode.Sources)
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val hideFeedTab by remember { Injekt.get<UiPreferences>().hideFeedTab().asState(scope) }
        val feedTabInFront by remember { Injekt.get<UiPreferences>().feedTabInFront().asState(scope) }

        val extensionsScreenModel = rememberScreenModel { ExtensionsScreenModel() }
        val extensionsState by extensionsScreenModel.state.collectAsState()

        val animeExtensionsScreenModel = rememberScreenModel { AnimeExtensionsScreenModel() }
        val animeExtensionsState by animeExtensionsScreenModel.state.collectAsState()

        val feedScreenModel = rememberScreenModel { FeedScreenModel() }
        val bulkFavoriteScreenModel = rememberScreenModel { BulkFavoriteScreenModel() }
        val novelExtensionsScreenModel = rememberScreenModel { NovelExtensionsScreenModel() }
        val novelExtensionsState by novelExtensionsScreenModel.state.collectAsState()

        val feedState by feedScreenModel.state.collectAsState()
        val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()

        val snackbarHostState = remember { SnackbarHostState() }

        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        var browseMode by remember {
            mutableStateOf(BrowseViewMode.entries.getOrElse(uiPreferences.lastUsedBrowseMode().get()) { BrowseViewMode.Sources })
        }
        var showModeDropdown by remember { mutableStateOf(false) }

        val currentTabs: ImmutableList<TabContent> = when (browseMode) {
            BrowseViewMode.Sources -> {
                when {
                    hideFeedTab ->
                        persistentListOf(
                            sourcesTab(),
                            extensionsTab(extensionsScreenModel),
                            migrateSourceTab(),
                        )
                    feedTabInFront ->
                        persistentListOf(
                            feedTab(feedScreenModel, bulkFavoriteScreenModel),
                            sourcesTab(),
                            extensionsTab(extensionsScreenModel),
                            migrateSourceTab(),
                        )
                    else ->
                        persistentListOf(
                            sourcesTab(),
                            feedTab(feedScreenModel, bulkFavoriteScreenModel),
                            extensionsTab(extensionsScreenModel),
                            migrateSourceTab(),
                        )
                }
            }
            BrowseViewMode.Anime -> persistentListOf(
                animeSourcesTab(),
                animeExtensionsTab(animeExtensionsScreenModel),
                migrateAnimeSourceTab(),
            )
            BrowseViewMode.Novels -> persistentListOf(
                novelSourcesTab(),
                novelExtensionsTab(novelExtensionsScreenModel),
            )
        }
        val pagerState = rememberPagerState { currentTabs.size }

        val currentTabIndex = pagerState.currentPage.coerceAtMost(currentTabs.lastIndex)
        val currentTab = currentTabs.getOrNull(currentTabIndex)
        val searchEnabled = currentTab?.searchEnabled ?: false

        val searchQuery: String? = when {
            browseMode == BrowseViewMode.Anime && currentTab?.titleRes == MR.strings.label_extensions -> animeExtensionsState.searchQuery
            browseMode == BrowseViewMode.Sources && currentTab?.titleRes == MR.strings.label_extensions -> extensionsState.searchQuery
            browseMode == BrowseViewMode.Novels && currentTab?.titleRes == MR.strings.label_extensions -> novelExtensionsState.searchQuery
            else -> null
        }
        val onChangeSearchQuery: (String?) -> Unit = when {
            browseMode == BrowseViewMode.Anime && currentTab?.titleRes == MR.strings.label_extensions -> animeExtensionsScreenModel::search
            browseMode == BrowseViewMode.Sources && currentTab?.titleRes == MR.strings.label_extensions -> extensionsScreenModel::search
            browseMode == BrowseViewMode.Novels && currentTab?.titleRes == MR.strings.label_extensions -> novelExtensionsScreenModel::search
            else -> { _ -> }
        }

        Scaffold(
            topBar = {
                if (bulkFavoriteState.selectionMode) {
                    BulkSelectionToolbar(
                        selectedCount = bulkFavoriteState.selection.size,
                        isRunning = bulkFavoriteState.isRunning,
                        onClickClearSelection = bulkFavoriteScreenModel::toggleSelectionMode,
                        onChangeCategoryClick = bulkFavoriteScreenModel::addFavorite,
                        onSelectAll = {
                            feedState.items?.let { result ->
                                result.mapNotNull { it.results }
                                    .flatten()
                                    .forEach { bulkFavoriteScreenModel.select(it) }
                            }
                        },
                        onReverseSelection = {
                            feedState.items?.let { result ->
                                result.mapNotNull { it.results }
                                    .flatten()
                                    .let { bulkFavoriteScreenModel.reverseSelection(it) }
                            }
                        },
                    )
                } else {
                    SearchToolbar(
                        titleContent = {
                            Row(
                                modifier = Modifier.clickable { showModeDropdown = true },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(browseMode.labelRes),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onBackground,
                                )
                                DropdownMenu(
                                    expanded = showModeDropdown,
                                    onDismissRequest = { showModeDropdown = false },
                                ) {
                                    BrowseViewMode.entries.forEach { mode ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = stringResource(mode.labelRes),
                                                    fontWeight = if (mode == browseMode) FontWeight.Bold else FontWeight.Normal,
                                                )
                                            },
                                            onClick = {
                                                showModeDropdown = false
                                                browseMode = mode
                                                uiPreferences.lastUsedBrowseMode().set(mode.ordinal)
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        searchEnabled = searchEnabled,
                        searchQuery = if (searchEnabled) searchQuery else null,
                        onChangeSearchQuery = onChangeSearchQuery,
                        searchHistoryScope = if (currentTab?.titleRes == MR.strings.label_extensions || currentTab?.titleRes == MR.strings.label_migration) SearchHistory.SCOPE_EXTENSION_MIGRATE else SearchHistory.SCOPE_ANIME_MANGA,
                        actions = {
                            AppBarActions(currentTab?.actions ?: persistentListOf())
                        },
                    )
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            Column(
                modifier = Modifier.padding(
                    top = contentPadding.calculateTopPadding(),
                    start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
                    end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
                ),
            ) {
                PrimaryTabRow(
                    selectedTabIndex = currentTabIndex,
                    modifier = Modifier.zIndex(1f),
                ) {
                    currentTabs.forEachIndexed { index, tab ->
                        M3Tab(
                            selected = currentTabIndex == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { TabText(text = stringResource(tab.titleRes), badgeCount = tab.badgeNumber) },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                HorizontalPager(
                    modifier = Modifier.fillMaxSize(),
                    state = pagerState,
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    val tab = currentTabs.getOrNull(page) ?: return@HorizontalPager
                    tab.content(
                        PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                        snackbarHostState,
                    )
                }
            }
        }

        LaunchedEffect(Unit) {
            switchToExtensionTabChannel.receiveAsFlow()
                .collectLatest { mode ->
                    browseMode = mode
                    val extensionsIndex = currentTabs.indexOfFirst { tab ->
                        tab.titleRes == MR.strings.label_extensions
                    }
                    if (extensionsIndex >= 0) {
                        pagerState.scrollToPage(extensionsIndex)
                    }
                }
        }

        LaunchedEffect(Unit) {
            modeSelectionEvent.receiveAsFlow().collectLatest { mode ->
                browseMode = mode
                uiPreferences.lastUsedBrowseMode().set(mode.ordinal)
            }
        }

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
            with(DiscordRPCService) {
                discordScope.launchIO { setScreen(context, DiscordScreen.BROWSE) }
            }
        }
    }
}
