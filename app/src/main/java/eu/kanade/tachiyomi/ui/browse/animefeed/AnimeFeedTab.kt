package eu.kanade.tachiyomi.ui.browse.animefeed

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalHapticFeedback
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.StackEvent
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.anime.AnimeFeedAddDialog
import eu.kanade.presentation.browse.anime.AnimeFeedAddSearchDialog
import eu.kanade.presentation.browse.anime.AnimeFeedActionsDialog
import eu.kanade.presentation.browse.anime.AnimeFeedOrderScreen
import eu.kanade.presentation.browse.anime.AnimeFeedScreen
import eu.kanade.presentation.browse.anime.components.AnimeBulkFavoriteDialogs
import eu.kanade.presentation.browse.components.SourceFeedDeleteDialog
import eu.kanade.presentation.browse.components.bulkSelectionButton
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteAnimeScreenModel
import eu.kanade.tachiyomi.ui.browse.animesource.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import tachiyomi.domain.source.anime.interactor.GetRemoteAnime
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun Screen.animeFeedTab(
    screenModel: AnimeFeedScreenModel,
    bulkFavoriteScreenModel: BulkFavoriteAnimeScreenModel,
): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val state by screenModel.state.collectAsState()

    val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()
    var showingFeedOrderScreen by rememberSaveable { mutableStateOf(false) }

    val haptic = LocalHapticFeedback.current

    LaunchedEffect(bulkFavoriteState.selectionMode) {
        HomeScreen.showBottomNav(!bulkFavoriteState.selectionMode)
    }

    DisposableEffect(navigator.lastEvent) {
        if (navigator.lastEvent == StackEvent.Push) {
            screenModel.pushed = true
        } else if (!screenModel.pushed) {
            screenModel.init()
        }

        onDispose {
            if (navigator.lastEvent == StackEvent.Idle && screenModel.pushed) {
                screenModel.pushed = false
            }
        }
    }

    return TabContent(
        titleRes = SYMR.strings.feed,
        actions =
        if (showingFeedOrderScreen) {
            persistentListOf(
                AppBar.Action(
                    title = stringResource(KMR.strings.action_sort_feed),
                    icon = Icons.Outlined.Close,
                    iconTint = MaterialTheme.colorScheme.primary,
                    onClick = { showingFeedOrderScreen = false },
                ),
            )
        } else {
            persistentListOf(
                AppBar.Action(
                    title = stringResource(MR.strings.action_add),
                    icon = Icons.Outlined.Add,
                    onClick = {
                        screenModel.openAddDialog()
                    },
                ),
                AppBar.Action(
                    title = stringResource(KMR.strings.action_sort_feed),
                    icon = Icons.Outlined.SwapVert,
                    onClick = { showingFeedOrderScreen = true },
                ),
                bulkSelectionButton(
                    isRunning = bulkFavoriteState.isRunning,
                    toggleSelectionMode = bulkFavoriteScreenModel::toggleSelectionMode,
                ),
            )
        },
        content = { contentPadding, snackbarHostState ->
            BackHandler(enabled = bulkFavoriteState.selectionMode || showingFeedOrderScreen) {
                when {
                    bulkFavoriteState.selectionMode -> bulkFavoriteScreenModel.backHandler()
                    showingFeedOrderScreen -> showingFeedOrderScreen = false
                }
            }
            Crossfade(
                targetState = showingFeedOrderScreen,
                label = "feed_order_crossfade",
            ) { showingFeedOrder ->
                if (showingFeedOrder) {
                    AnimeFeedOrderScreen(
                        state = state,
                        onClickDelete = screenModel::openDeleteDialog,
                        onChangeOrder = screenModel::changeOrder,
                    )
                } else {
                    AnimeFeedScreen(
                        state = state,
                        contentPadding = contentPadding,
                        onClickSavedSearch = { savedSearch, source ->
                            screenModel.sourcePreferences.lastUsedAnimeSource().set(savedSearch.source)
                            navigator.push(
                                BrowseAnimeSourceScreen(
                                    source.id,
                                    listingQuery = null,
                                    savedSearchId = savedSearch.id,
                                ),
                            )
                        },
                        onClickSource = { source ->
                            screenModel.sourcePreferences.lastUsedAnimeSource().set(source.id)
                            val catalogueSource = source as? eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
                            navigator.push(
                                BrowseAnimeSourceScreen(
                                    source.id,
                                    listingQuery = if (catalogueSource?.supportsLatest == false) {
                                        GetRemoteAnime.QUERY_POPULAR
                                    } else {
                                        GetRemoteAnime.QUERY_LATEST
                                    },
                                ),
                            )
                        },
                        onLongClickFeed = screenModel::openActionsDialog,
                        onClickAnime = { anime ->
                            if (bulkFavoriteState.selectionMode) {
                                bulkFavoriteScreenModel.toggleSelection(anime)
                            } else {
                                navigator.push(AnimeScreen(anime.id, true))
                            }
                        },
                        onLongClickAnime = { anime ->
                            if (!bulkFavoriteState.selectionMode) {
                                bulkFavoriteScreenModel.addRemoveAnime(anime, haptic)
                            } else {
                                navigator.push(AnimeScreen(anime.id, true))
                            }
                        },
                        selection = bulkFavoriteState.selection,
                        onRefresh = screenModel::init,
                        getAnimeState = { anime -> screenModel.getAnime(initialAnime = anime) },
                    )
                }
            }

            state.dialog?.let { dialog ->
                val onDismissRequest = screenModel::dismissDialog
                when (dialog) {
                    is AnimeFeedScreenModel.Dialog.AddFeed -> {
                        AnimeFeedAddDialog(
                            sources = dialog.options,
                            onDismiss = onDismissRequest,
                            onClickAdd = {
                                if (it != null) {
                                    screenModel.openAddSearchDialog(it)
                                }
                                onDismissRequest()
                            },
                        )
                    }
                    is AnimeFeedScreenModel.Dialog.AddFeedSearch -> {
                        AnimeFeedAddSearchDialog(
                            source = dialog.source,
                            savedSearches = dialog.options,
                            onDismiss = onDismissRequest,
                            onClickAdd = { source, savedSearch ->
                                screenModel.createFeed(source, savedSearch)
                                onDismissRequest()
                            },
                        )
                    }
                    is AnimeFeedScreenModel.Dialog.DeleteFeed -> {
                        SourceFeedDeleteDialog(
                            onDismissRequest = onDismissRequest,
                            deleteFeed = {
                                screenModel.deleteFeed(dialog.feed)
                                onDismissRequest()
                            },
                        )
                    }
                    is AnimeFeedScreenModel.Dialog.FeedActions -> {
                        AnimeFeedActionsDialog(
                            feed = dialog.feedItem.feed,
                            title = dialog.feedItem.title,
                            onDismissRequest = onDismissRequest,
                            onClickDelete = { screenModel.openDeleteDialog(it) },
                        )
                    }
                }
            }

            AnimeBulkFavoriteDialogs(
                bulkFavoriteScreenModel = bulkFavoriteScreenModel,
                dialog = bulkFavoriteState.dialog,
            )

            val internalErrString = stringResource(MR.strings.internal_error)
            val tooManyFeedsString = stringResource(KMR.strings.too_many_in_feed)
            LaunchedEffect(Unit) {
                screenModel.events.collectLatest { event ->
                    when (event) {
                        AnimeFeedScreenModel.Event.FailedFetchingSources -> {
                            launch { snackbarHostState.showSnackbar(internalErrString) }
                        }
                        AnimeFeedScreenModel.Event.TooManyFeeds -> {
                            launch { snackbarHostState.showSnackbar(tooManyFeedsString) }
                        }
                    }
                }
            }
        },
    )
}
