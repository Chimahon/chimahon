package eu.kanade.presentation.browse.anime.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.entries.anime.DuplicateAnimeDialog
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteAnimeScreenModel
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteAnimeScreenModel.Dialog
import eu.kanade.tachiyomi.ui.browse.animemigration.season.MigrateSeasonSelectScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import mihon.feature.animemigration.dialog.MigrateAnimeDialog
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Compose to shows the anime bulk favorite dialogs.
 *
 * @param bulkFavoriteScreenModel the screen model.
 * @param dialog the dialog to show.
 */
@Composable
fun Screen.AnimeBulkFavoriteDialogs(
    bulkFavoriteScreenModel: BulkFavoriteAnimeScreenModel,
    dialog: Dialog?,
) {
    val navigator = LocalNavigator.current
    val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()

    when (dialog) {
        /* Bulk-favorite actions */
        is Dialog.ChangeAnimesCategory ->
            ChangeAnimeCategoryDialog(
                initialSelection = dialog.initialSelection,
                onDismissRequest = bulkFavoriteScreenModel::dismissDialog,
                onEditCategories = { navigator?.push(CategoryScreen(CategoryScreen.Tab.ANIME)) },
                onConfirm = { include, exclude ->
                    bulkFavoriteScreenModel.setAnimesCategories(dialog.animes, include, exclude)
                },
            )

        is Dialog.BulkAllowDuplicate ->
            BulkAllowDuplicateDialog(
                dialog = dialog,
                navigator = navigator,
                onDismiss = bulkFavoriteScreenModel::dismissDialog,
                stopRunning = bulkFavoriteScreenModel::stopRunning,
                addFavorite = bulkFavoriteScreenModel::addFavorite,
                showMigrateDialog = bulkFavoriteScreenModel::showMigrateDialog,
                addFavoriteDuplicate = bulkFavoriteScreenModel::addFavoriteDuplicate,
                removeDuplicateSelectedAnime = bulkFavoriteScreenModel::removeDuplicateSelectedAnime,
            )

        /* Single-favorite actions for screens originally don't have it */
        is Dialog.AddDuplicateAnime ->
            DuplicateAnimeDialog(
                onDismissRequest = bulkFavoriteScreenModel::dismissDialog,
                onConfirm = { bulkFavoriteScreenModel.addFavorite(dialog.anime) },
                onOpenAnime = { navigator?.push(AnimeScreen(dialog.anime.id)) },
                onMigrate = {
                    dialog.duplicates.firstOrNull()?.let {
                        bulkFavoriteScreenModel.showMigrateDialog(dialog.anime, it)
                    } ?: bulkFavoriteScreenModel.dismissDialog()
                },
            )

        is Dialog.RemoveAnime ->
            RemoveAnimeDialog(
                dialog = dialog,
                onDismiss = bulkFavoriteScreenModel::dismissDialog,
                changeAnimeFavorite = bulkFavoriteScreenModel::changeAnimeFavorite,
            )

        is Dialog.Migrate ->
            ShowMigrateDialog(
                dialog = dialog,
                navigator = navigator,
                state = bulkFavoriteState,
                onDismiss = bulkFavoriteScreenModel::dismissDialog,
                stopRunning = bulkFavoriteScreenModel::stopRunning,
                toggleSelection = bulkFavoriteScreenModel::toggleSelection,
                addFavorite = bulkFavoriteScreenModel::addFavorite,
            )

        else -> {}
    }
}

@Composable
private fun Screen.ShowMigrateDialog(
    dialog: Dialog.Migrate,
    navigator: Navigator?,
    state: BulkFavoriteAnimeScreenModel.State,
    onDismiss: () -> Unit,
    stopRunning: () -> Unit,
    toggleSelection: (Anime, toSelectedState: Boolean) -> Unit,
    addFavorite: () -> Unit,
) {
    stopRunning()

    MigrateAnimeDialog(
        current = dialog.current,
        target = dialog.target,
        // Initiated from the context of [dialog.target] so we show [dialog.current].
        onClickTitle = { navigator?.push(AnimeScreen(dialog.current.id)) },
        onClickSeasons = { navigator?.push(MigrateSeasonSelectScreen(dialog.current, dialog.target)) },
        onDismissRequest = onDismiss,
        onComplete = {
            toggleSelection(dialog.target, false)
            onDismiss()
            // `selection.size` is at current value before calling above `toggleSelection`
            if (state.selection.size > 1) {
                // Continue adding favorites
                addFavorite()
            }
        },
    )
}

@Composable
private fun RemoveAnimeDialog(
    dialog: Dialog.RemoveAnime,
    onDismiss: () -> Unit,
    changeAnimeFavorite: (Anime) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    changeAnimeFavorite(dialog.anime)
                },
            ) {
                Text(text = stringResource(MR.strings.action_remove))
            }
        },
        title = {
            Text(text = stringResource(MR.strings.are_you_sure))
        },
        text = {
            Text(text = stringResource(MR.strings.remove_anime, dialog.anime.title))
        },
    )
}

/**
 * Shows dialog to bulk allow/skip or migrate multiple anime to library when there are duplicates.
 */
@Composable
private fun BulkAllowDuplicateDialog(
    dialog: Dialog.BulkAllowDuplicate,
    navigator: Navigator?,
    onDismiss: () -> Unit,
    stopRunning: () -> Unit,
    addFavorite: (startIdx: Int) -> Unit,
    showMigrateDialog: (anime: Anime, duplicate: Anime) -> Unit,
    addFavoriteDuplicate: (skipAllDuplicates: Boolean) -> Unit,
    removeDuplicateSelectedAnime: (index: Int) -> Unit,
) {
    stopRunning()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(MR.strings.possible_duplicates_title))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                Text(
                    text = dialog.anime.title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(MR.strings.possible_duplicates_summary),
                    style = MaterialTheme.typography.bodyMedium,
                )
                dialog.duplicates.forEach { duplicate ->
                    Text(
                        text = duplicate.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                navigator?.push(AnimeScreen(duplicate.id))
                            }
                            .padding(vertical = MaterialTheme.padding.extraSmall),
                    )
                }
            }
        },
        confirmButton = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                TextButton(
                    onClick = {
                        onDismiss()
                        addFavorite(dialog.currentIdx + 1)
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_add_anyway))
                }
                TextButton(
                    onClick = {
                        onDismiss()
                        addFavoriteDuplicate(false)
                    },
                ) {
                    Text(text = stringResource(KMR.strings.action_allow_all_duplicate_anime))
                }
                TextButton(
                    onClick = {
                        removeDuplicateSelectedAnime(dialog.currentIdx)
                        addFavorite(dialog.currentIdx)
                    },
                ) {
                    Text(text = stringResource(KMR.strings.action_skip_duplicate_anime))
                }
                TextButton(
                    onClick = {
                        onDismiss()
                        addFavoriteDuplicate(true)
                    },
                ) {
                    Text(text = stringResource(KMR.strings.action_skip_all_duplicate_anime))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        onDismiss()
                        showMigrateDialog(dialog.anime, dialog.duplicates.first())
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_show_anime))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
