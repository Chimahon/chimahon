package eu.kanade.presentation.updates.anime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.updates.UpdatesSettingsScreenModel
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.domain.updates.service.UpdatesPreferences
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * Anime counterpart of the manga updates filter sheet. "Seen" replaces "unread" and there is an
 * extra fillermark toggle, since fillermarked episodes are the anime equivalent of filler chapters.
 */
@Composable
fun AnimeUpdatesFilterDialog(
    onDismissRequest: () -> Unit,
    screenModel: UpdatesSettingsScreenModel,
) {
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = persistentListOf(
            stringResource(MR.strings.action_filter),
        ),
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            AnimeFilterSheet(screenModel = screenModel)
        }
    }
}

@Composable
private fun ColumnScope.AnimeFilterSheet(
    screenModel: UpdatesSettingsScreenModel,
) {
    val filterDownloaded by screenModel.updatesPreferences.filterDownloaded().collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.label_downloaded),
        state = filterDownloaded,
        onClick = { screenModel.toggleFilter(UpdatesPreferences::filterDownloaded) },
    )

    val filterSeen by screenModel.updatesPreferences.filterSeen().collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.action_mark_as_seen),
        state = filterSeen,
        onClick = { screenModel.toggleFilter(UpdatesPreferences::filterSeen) },
    )

    val filterStarted by screenModel.updatesPreferences.filterStartedAnime().collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.label_started),
        state = filterStarted,
        onClick = { screenModel.toggleFilter(UpdatesPreferences::filterStartedAnime) },
    )

    val filterBookmarked by screenModel.updatesPreferences.filterBookmarkedAnime().collectAsState()
    TriStateItem(
        label = stringResource(MR.strings.action_filter_bookmarked),
        state = filterBookmarked,
        onClick = { screenModel.toggleFilter(UpdatesPreferences::filterBookmarkedAnime) },
    )

    val filterFillermarked by screenModel.updatesPreferences.filterFillermarked().collectAsState()
    TriStateItem(
        label = stringResource(AMR.strings.action_filter_fillermarked),
        state = filterFillermarked,
        onClick = { screenModel.toggleFilter(UpdatesPreferences::filterFillermarked) },
    )
}
