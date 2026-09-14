package mihon.feature.animemigration.list

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.ui.browse.animemigration.search.MigrateAnimeSearchScreen
import eu.kanade.tachiyomi.ui.browse.animemigration.season.MigrateSeasonSelectScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.util.system.toast
import mihon.feature.animemigration.list.models.MigratingAnime
import mihon.feature.migration.config.MigrationConfigScreen
import mihon.feature.migration.config.MigrationConfigScreenSheet
import mihon.feature.migration.list.components.MigrationExitDialog
import mihon.feature.migration.list.components.MigrationMangaDialog
import mihon.feature.migration.list.components.MigrationProgressDialog
import tachiyomi.i18n.MR

/**
 * Screen showing a list of pair of current-target anime entries being migrated.
 */
class AnimeMigrationListScreen(
    private val animeIds: Collection<Long>,
    private val extraSearchQuery: String?,
    private val isSmartSearchSingleEntry: Boolean = false,
) : Screen() {

    private var matchOverride: Pair<Long, Long>? = null

    fun addMatchOverride(current: Long, target: Long) {
        matchOverride = current to target
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val singleEntryNoSmartSearch = animeIds.size == 1 && !isSmartSearchSingleEntry
        val screenModel = rememberScreenModel {
            AnimeMigrationListScreenModel(animeIds, extraSearchQuery, singleEntryNoSmartSearch)
        }
        val state by screenModel.state.collectAsState()
        val context = LocalContext.current

        var hasPushedManual by rememberSaveable(animeIds) { mutableStateOf(false) }
        LaunchedEffect(animeIds) {
            if (singleEntryNoSmartSearch && !hasPushedManual) {
                hasPushedManual = true
                navigator.push(MigrateAnimeSearchScreen(animeIds.single()))
            }
        }

        LaunchedEffect(matchOverride) {
            val (current, target) = matchOverride ?: return@LaunchedEffect
            screenModel.useAnimeForMigration(
                current = current,
                target = target,
                onMissingEpisodes = {
                    val stringResource = when (it) {
                        FetchType.Seasons -> MR.strings.migrationListScreen_matchWithoutSeasonToast
                        FetchType.Episodes -> MR.strings.migrationListScreen_matchWithoutEpisodeToast
                    }
                    context.toast(stringResource, Toast.LENGTH_LONG)
                },
            )
            matchOverride = null
        }

        LaunchedEffect(screenModel) {
            screenModel.navigateBackEvent.collect {
                /* If this screen is called from single anime migration, replace the AnimeScreen in the backstack
                   with the newly migrated anime to reflect the changes properly.
                   Otherwise, just pop normally. */
                if (animeIds.size == 1 && navigator.items.any { it is AnimeScreen }) {
                    val animeId = (state.items.firstOrNull()?.searchResult?.value as? MigratingAnime.SearchResult.Success)?.anime?.id
                    if (animeId != null) {
                        val newStack = navigator.items.filter {
                            it !is AnimeScreen &&
                                it !is AnimeMigrationListScreen &&
                                it !is MigrationConfigScreen
                        } + AnimeScreen(animeId)
                        navigator replaceAll newStack.first()
                        navigator.push(newStack.drop(1))

                        // need to set the navigator in a pop state to dispose of everything properly
                        navigator.push(this@AnimeMigrationListScreen)
                        navigator.pop()
                    } else {
                        navigator.pop()
                    }
                } else {
                    navigator.pop()
                }
            }
        }
        AnimeMigrationListScreenContent(
            items = state.items,
            migrationComplete = state.migrationComplete,
            finishedCount = state.finishedCount,
            onItemClick = {
                navigator.push(AnimeScreen(it.id, true))
            },
            onSearchManually = { migrationItem ->
                navigator push MigrateAnimeSearchScreen(migrationItem.anime.id)
            },
            onSearchSeasons = { current, target ->
                navigator push MigrateSeasonSelectScreen(current, target, true)
            },
            onSkip = { screenModel.removeAnime(it) },
            onMigrate = { screenModel.migrateNow(animeId = it, replace = true) },
            onCopy = { screenModel.migrateNow(animeId = it, replace = false) },
            openMigrationDialog = screenModel::showMigrateDialog,
            onCancel = { screenModel.cancelAnime(it) },
            openOptionsDialog = screenModel::openOptionsDialog,
        )

        when (val dialog = state.dialog) {
            is AnimeMigrationListScreenModel.Dialog.Migrate -> {
                MigrationMangaDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    copy = dialog.copy,
                    totalCount = dialog.totalCount,
                    skippedCount = dialog.skippedCount,
                    onMigrate = {
                        if (dialog.copy) {
                            screenModel.copyAnimes()
                        } else {
                            screenModel.migrateAnimes()
                        }
                    },
                )
            }
            is AnimeMigrationListScreenModel.Dialog.Progress -> {
                MigrationProgressDialog(
                    progress = dialog.progress,
                    exitMigration = screenModel::cancelMigrate,
                )
            }
            AnimeMigrationListScreenModel.Dialog.Exit -> {
                MigrationExitDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    exitMigration = navigator::pop,
                )
            }
            AnimeMigrationListScreenModel.Dialog.Options -> {
                MigrationConfigScreenSheet(
                    preferences = screenModel.preferences,
                    onDismissRequest = screenModel::dismissDialog,
                    onStartMigration = { _ ->
                        screenModel.dismissDialog()
                        screenModel.updateOptions()
                    },
                    fullSettings = false,
                )
            }
            null -> Unit
        }

        BackHandler(true) {
            screenModel.showExitDialog()
        }
    }
}
