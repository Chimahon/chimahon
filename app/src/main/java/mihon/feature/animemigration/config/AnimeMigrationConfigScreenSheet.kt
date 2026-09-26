package mihon.feature.animemigration.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.components.AdaptiveSheet
import mihon.domain.animemigration.models.AnimeMigrationFlag
import mihon.feature.common.utils.getLabel
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.toggle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.collectAsState

/**
 * Anime counterpart of the manga [mihon.feature.migration.config.MigrationConfigScreenSheet].
 *
 * Deliberately a separate composable rather than extra parameters on the manga one: the two keep
 * different flag sets with different bit layouts (`anime_migrate_flags` / [AnimeMigrationFlag] vs
 * `migration_flags` / `MigrationFlag`), and the manga sheet must not change for this.
 *
 * The non-flag options (hide unmatched, hide without updates, deep search, prioritize by chapters)
 * are intentionally shared with manga through [SourcePreferences], matching what
 * [mihon.feature.animemigration.list.AnimeMigrationListScreenModel.updateOptions] reads back.
 */
@Composable
fun AnimeMigrationConfigScreenSheet(
    preferences: SourcePreferences,
    flags: Set<AnimeMigrationFlag>,
    onToggleFlag: (AnimeMigrationFlag) -> Unit,
    onDismissRequest: () -> Unit,
    onStartMigration: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = MaterialTheme.padding.medium),
            ) {
                Text(
                    text = stringResource(MR.strings.migrationConfigScreen_dataToMigrateHeader),
                    style = MaterialTheme.typography.header,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MaterialTheme.padding.extraSmall)
                        .padding(horizontal = MaterialTheme.padding.medium),
                )
                Spacer(Modifier.height(MaterialTheme.padding.extraSmall))
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.padding.medium)
                        .padding(bottom = MaterialTheme.padding.extraSmall),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    AnimeMigrationFlag.entries
                        .filterNot { it == AnimeMigrationFlag.REMOVE_DOWNLOAD }
                        .forEach { flag ->
                            val selected = flag in flags
                            FilterChip(
                                selected = selected,
                                onClick = { onToggleFlag(flag) },
                                label = { Text(stringResource(flag.getLabel())) },
                                leadingIcon = {
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Outlined.Check,
                                            contentDescription = null,
                                        )
                                    }
                                },
                            )
                        }
                }
                val removeDownloads = AnimeMigrationFlag.REMOVE_DOWNLOAD in flags
                AnimeMigrationSheetSwitchItem(
                    title = stringResource(MR.strings.migrationConfigScreen_removeDownloadsTitle),
                    subtitle = null,
                    checked = removeDownloads,
                    onClick = { onToggleFlag(AnimeMigrationFlag.REMOVE_DOWNLOAD) },
                )
                AnimeMigrationSheetDividerItem()
                AnimeMigrationSheetSwitchItem(
                    title = stringResource(MR.strings.migrationConfigScreen_hideUnmatchedTitle),
                    subtitle = null,
                    preference = preferences.migrationHideUnmatched(),
                )
                AnimeMigrationSheetSwitchItem(
                    title = stringResource(MR.strings.migrationConfigScreen_hideWithoutUpdatesTitle),
                    subtitle = stringResource(MR.strings.migrationConfigScreen_hideWithoutUpdatesSubtitle),
                    preference = preferences.migrationHideWithoutUpdates(),
                )
                AnimeMigrationSheetDividerItem()
                AnimeMigrationSheetSwitchItem(
                    title = stringResource(MR.strings.migrationConfigScreen_deepSearchModeTitle),
                    subtitle = stringResource(MR.strings.migrationConfigScreen_deepSearchModeSubtitle),
                    preference = preferences.migrationDeepSearchMode(),
                )
                AnimeMigrationSheetSwitchItem(
                    title = stringResource(MR.strings.migrationConfigScreen_prioritizeByChaptersTitle),
                    subtitle = stringResource(MR.strings.migrationConfigScreen_prioritizeByChaptersSubtitle),
                    preference = preferences.migrationPrioritizeByChapters(),
                )
            }
            HorizontalDivider()
            Button(
                onClick = onStartMigration,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = MaterialTheme.padding.medium,
                        vertical = MaterialTheme.padding.small,
                    ),
            ) {
                Text(text = stringResource(MR.strings.action_save))
            }
        }
    }
}

@Composable
private fun AnimeMigrationSheetSwitchItem(
    title: String,
    subtitle: String?,
    preference: Preference<Boolean>,
) {
    AnimeMigrationSheetSwitchItem(
        title = title,
        subtitle = subtitle,
        checked = preference.collectAsState().value,
        onClick = { preference.toggle() },
    )
}

@Composable
private fun AnimeMigrationSheetSwitchItem(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(text = title) },
        supportingContent = subtitle?.let { { Text(text = subtitle) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun AnimeMigrationSheetDividerItem() {
    HorizontalDivider(modifier = Modifier.padding(vertical = MaterialTheme.padding.extraSmall))
}
