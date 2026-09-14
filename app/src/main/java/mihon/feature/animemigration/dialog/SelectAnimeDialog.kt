package mihon.feature.animemigration.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.tachiyomi.animesource.model.FetchType
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun SelectAnimeDialog(
    selected: Anime,
    onClickTitle: () -> Unit,
    onClickSeasons: () -> Unit,
    onClickSelect: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(MR.strings.migration_dialog_what_to_include))
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

                if (selected.fetchType == FetchType.Seasons) {
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

                TextButton(
                    onClick = {
                        onDismissRequest()
                        onClickSelect()
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_select))
                }
            }
        },
    )
}
