package eu.kanade.presentation.entries.anime.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.manga.components.OutlinedButtonWithArrow
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun AnimeInfoButtons(
    showRecommendsButton: Boolean,
    onRecommendClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (showRecommendsButton) {
        Column(modifier.fillMaxWidth()) {
            OutlinedButtonWithArrow(
                text = stringResource(SYMR.strings.az_recommends),
                onClick = onRecommendClicked,
            )
        }
    }
}
