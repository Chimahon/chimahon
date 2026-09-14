package mihon.feature.animemigration.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.domain.entries.anime.model.hasCustomBackground
import eu.kanade.domain.entries.anime.model.hasCustomCover
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.data.animedownload.AnimeDownloadManager
import eu.kanade.tachiyomi.data.cache.AnimeBackgroundCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import kotlinx.coroutines.flow.update
import mihon.domain.animemigration.models.AnimeMigrationFlag
import mihon.domain.animemigration.usecases.MigrateAnimeUseCase
import mihon.feature.common.utils.getLabel
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun Screen.MigrateAnimeDialog(
    current: Anime,
    target: Anime,
    onClickTitle: () -> Unit,
    onClickSeasons: () -> Unit = {},
    onDismissRequest: () -> Unit,
    onComplete: () -> Unit = onDismissRequest,
) {
    val scope = rememberCoroutineScope()
    val canMigrate = current.fetchType == target.fetchType

    val screenModel = rememberScreenModel { MigrateAnimeDialogScreenModel() }
    LaunchedEffect(current, target) {
        screenModel.init(current, target)
    }
    val state by screenModel.state.collectAsState()

    if (state.isMigrated) return

    if (state.isMigrating) {
        LoadingScreen(
            modifier = Modifier.background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f)),
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(MR.strings.migration_dialog_what_to_include))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (canMigrate) {
                    state.applicableFlags.forEach { flag ->
                        LabeledCheckbox(
                            label = stringResource(flag.getLabel()),
                            checked = flag in state.selectedFlags,
                            onCheckedChange = { screenModel.toggleSelection(flag) },
                        )
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(
                                if (current.fetchType == FetchType.Seasons) {
                                    MR.strings.label_cant_migrate_anime_season
                                } else {
                                    MR.strings.label_cant_migrate_anime_episode
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (state.migrationFailed) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(MR.strings.internal_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                TextButton(
                    onClick = {
                        onDismissRequest()
                        onClickTitle()
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_show_anime))
                }

                if (target.fetchType != FetchType.Episodes) {
                    TextButton(
                        onClick = {
                            onDismissRequest()
                            onClickSeasons()
                        },
                    ) {
                        Text(text = stringResource(MR.strings.label_show_seasons))
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                if (canMigrate) {
                    TextButton(
                        onClick = {
                            scope.launchIO {
                                if (screenModel.migrateAnime(replace = false)) {
                                    withUIContext { onComplete() }
                                }
                            }
                        },
                    ) {
                        Text(text = stringResource(MR.strings.copy))
                    }
                    TextButton(
                        onClick = {
                            scope.launchIO {
                                if (screenModel.migrateAnime(replace = true)) {
                                    withUIContext { onComplete() }
                                }
                            }
                        },
                    ) {
                        Text(text = stringResource(MR.strings.migrate))
                    }
                }
            }
        },
    )
}

private class MigrateAnimeDialogScreenModel(
    private val preferenceStore: PreferenceStore = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val backgroundCache: AnimeBackgroundCache = Injekt.get(),
    private val downloadManager: AnimeDownloadManager = Injekt.get(),
    private val migrateAnime: MigrateAnimeUseCase = Injekt.get(),
) : StateScreenModel<MigrateAnimeDialogScreenModel.State>(State()) {

    private val migrationFlags = preferenceStore.getInt("anime_migrate_flags", Int.MAX_VALUE)

    fun init(current: Anime, target: Anime) {
        val applicableFlags = buildList {
            AnimeMigrationFlag.entries.forEach {
                val applicable = when (it) {
                    AnimeMigrationFlag.EPISODE -> true
                    AnimeMigrationFlag.CATEGORY -> true
                    AnimeMigrationFlag.TRACK -> true
                    AnimeMigrationFlag.CUSTOM_BACKGROUND -> current.hasCustomBackground(backgroundCache)
                    AnimeMigrationFlag.CUSTOM_COVER -> current.hasCustomCover(coverCache)
                    AnimeMigrationFlag.REMOVE_DOWNLOAD -> downloadManager.getDownloadCount(current) > 0
                    AnimeMigrationFlag.EXTRA -> true
                }
                if (applicable) add(it)
            }
        }
        val selectedFlags = AnimeMigrationFlag.fromBit(migrationFlags.get())
        mutableState.update {
            it.copy(
                current = current,
                target = target,
                applicableFlags = applicableFlags,
                selectedFlags = selectedFlags,
                isMigrated = false,
                migrationFailed = false,
            )
        }
    }

    fun toggleSelection(flag: AnimeMigrationFlag) {
        mutableState.update {
            val selectedFlags = it.selectedFlags.toMutableSet()
                .apply { if (contains(flag)) remove(flag) else add(flag) }
                .toSet()
            it.copy(selectedFlags = selectedFlags)
        }
    }

    suspend fun migrateAnime(replace: Boolean): Boolean {
        val state = state.value
        val current = state.current ?: return false
        val target = state.target ?: return false
        migrationFlags.set(AnimeMigrationFlag.toBit(state.selectedFlags))
        mutableState.update { it.copy(isMigrating = true, migrationFailed = false) }
        val migrated = migrateAnime(current, target, replace, state.selectedFlags)
        mutableState.update { it.copy(isMigrating = false, isMigrated = migrated, migrationFailed = !migrated) }
        return migrated
    }

    data class State(
        val current: Anime? = null,
        val target: Anime? = null,
        val applicableFlags: List<AnimeMigrationFlag> = emptyList(),
        val selectedFlags: Set<AnimeMigrationFlag> = emptySet(),
        val isMigrating: Boolean = false,
        val isMigrated: Boolean = false,
        val migrationFailed: Boolean = false,
    )
}
