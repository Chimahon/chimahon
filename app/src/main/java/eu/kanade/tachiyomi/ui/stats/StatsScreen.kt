package eu.kanade.tachiyomi.ui.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.stats.StatsScreenContent
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.util.Screen
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import eu.kanade.presentation.more.stats.data.StatsType
import tachiyomi.presentation.core.screens.LoadingScreen

class StatsScreen(
    private val titleId: String? = null,
    private val isNovel: Boolean = false,
    private val isAnime: Boolean = false,
    private val titleName: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        val screenModel = rememberScreenModel {
            StatsScreenModel(titleId = titleId, isNovel = isNovel, isAnime = isAnime, titleName = titleName)
        }
        val state by screenModel.state.collectAsState()
        val allRead by screenModel.allRead.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = titleName ?: stringResource(MR.strings.label_stats),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                    // SY -->
                    actions = {
                        if (titleId == null) {
                            AppBarActions(
                                persistentListOf(
                                    AppBar.OverflowAction(
                                        title = if (allRead) {
                                            stringResource(SYMR.strings.ignore_non_library_entries)
                                        } else {
                                            stringResource(SYMR.strings.include_all_read_entries)
                                        },
                                        onClick = screenModel::toggleReadManga,
                                    ),
                                ),
                            )
                        }
                    },
                    // SY <--
                )
            },
        ) { paddingValues ->
            if (state is StatsScreenState.Loading) {
                LoadingScreen()
                return@Scaffold
            }

            StatsScreenContent(
                state = state as? StatsScreenState.Success ?: return@Scaffold,
                paddingValues = paddingValues,
                onDateScaleSelect = screenModel::setDateScale,
                onDateOffsetChange = screenModel::setDateOffset,
                onStatsTypeSelect = screenModel::setStatsType,
                onProfileSelect = screenModel::setProfileFilter,
                allRead = allRead,
                isSingleTitle = titleId != null,
                titleName = titleName,
                onLibraryCardClick = if (titleId == null) {
                    {
                        val successState = state as? StatsScreenState.Success ?: return@StatsScreenContent
                        navigator.push(
                            StatsTitlesScreen(
                                activeProfileId = successState.activeProfileId,
                                allRead = allRead,
                                statsType = successState.statsType
                            )
                        )
                    }
                } else null,
            )
        }
    }
}
