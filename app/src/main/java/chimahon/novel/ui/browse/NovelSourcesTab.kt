package chimahon.novel.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import chimahon.novel.ui.servers.NovelServerConfigScreen
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.icons.FlagEmoji
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.plus

@Composable
fun Screen.novelSourcesTab(): TabContent {
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = rememberScreenModel { NovelSourcesScreenModel() }
    val state by screenModel.state.collectAsState()

    return TabContent(
        titleRes = MR.strings.novel_singular,
        actions = persistentListOf(
            AppBar.Action(
                title = stringResource(MR.strings.novel_servers),
                icon = Icons.Outlined.Settings,
                onClick = { navigator.push(NovelServerConfigScreen()) },
            ),
        ),
        content = { contentPadding, _ ->
            when {
                state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
                state.isEmpty -> EmptyScreen(MR.strings.source_empty_screen, modifier = Modifier.padding(contentPadding))
                else -> {
                    NovelSourcesList(
                        items = state.items,
                        contentPadding = contentPadding,
                        onClickSource = { source ->
                            navigator.push(BrowseNovelSourceScreen(null, source.id))
                        },
                        onClickLatest = { source ->
                            navigator.push(BrowseNovelSourceScreen(null, source.id))
                        },
                        onClickPin = screenModel::togglePin,
                        onLongClickSource = screenModel::showSourceDialog,
                    )
                }
            }

            state.dialog?.let { dialog ->
                NovelSourceOptionsDialog(
                    source = dialog.source,
                    isPinned = state.items.values.flatten()
                        .firstOrNull { it.source.id == dialog.source.id }
                        ?.isPinned == true,
                    onClickPin = {
                        screenModel.togglePin(dialog.source)
                        screenModel.closeDialog()
                    },
                    onClickDisable = {
                        screenModel.toggleSource(dialog.source)
                        screenModel.closeDialog()
                    },
                    onDismiss = screenModel::closeDialog,
                )
            }
        },
    )
}

@Composable
private fun NovelSourcesList(
    items: Map<String, List<NovelSourcesScreenModel.NovelSourceUiModel>>,
    contentPadding: PaddingValues,
    onClickSource: (NovelsPageSource) -> Unit,
    onClickLatest: (NovelsPageSource) -> Unit,
    onClickPin: (NovelsPageSource) -> Unit,
    onLongClickSource: (NovelsPageSource) -> Unit,
) {
    val context = LocalContext.current

    FastScrollLazyColumn(
        contentPadding = contentPadding + topSmallPaddingValues,
    ) {
        items.forEach { (lang, sources) ->
            item(key = "novel-source-header-$lang") {
                Row(
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = LocaleHelper.getSourceDisplayName(lang, context) +
                            " ${FlagEmoji.getEmojiLangFlag(lang)}",
                        modifier = Modifier
                            .padding(vertical = MaterialTheme.padding.small)
                            .weight(1f),
                        style = MaterialTheme.typography.header,
                    )
                }
            }

            items(
                items = sources,
                key = { "novel-source-${it.source.id}" },
            ) { item ->
                NovelSourceItem(
                    item = item,
                    onClick = { onClickSource(item.source) },
                    onLongClick = { onLongClickSource(item.source) },
                    onClickLatest = { onClickLatest(item.source) },
                    onClickPin = { onClickPin(item.source) },
                )
            }
        }
    }
}

@Composable
private fun NovelSourceItem(
    item: NovelSourcesScreenModel.NovelSourceUiModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickLatest: () -> Unit,
    onClickPin: () -> Unit,
) {
    val context = LocalContext.current
    val source = item.source

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(
                Modifier.padding(
                    vertical = 12.dp,
                    horizontal = MaterialTheme.padding.medium,
                )
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = LocaleHelper.getSourceDisplayName(source.lang, context),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (source.supportsLatest) {
            TextButton(onClick = onClickLatest) {
                Text(
                    text = stringResource(MR.strings.latest),
                    style = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }

        NovelSourcePinButton(
            isPinned = item.isPinned,
            onClick = onClickPin,
        )
    }
}

@Composable
private fun NovelSourcePinButton(
    isPinned: Boolean,
    onClick: () -> Unit,
) {
    val icon = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin
    val tint = if (isPinned) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onBackground.copy(alpha = SECONDARY_ALPHA)
    }
    val description = if (isPinned) MR.strings.action_unpin else MR.strings.action_pin
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            tint = tint,
            contentDescription = stringResource(description),
        )
    }
}

@Composable
private fun NovelSourceOptionsDialog(
    source: NovelsPageSource,
    isPinned: Boolean,
    onClickPin: () -> Unit,
    onClickDisable: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        title = {
            Text(text = source.name)
        },
        text = {
            Column {
                Text(
                    text = stringResource(if (isPinned) MR.strings.action_unpin else MR.strings.action_pin),
                    modifier = Modifier
                        .clickable(onClick = onClickPin)
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                )
                Text(
                    text = stringResource(MR.strings.action_disable),
                    modifier = Modifier
                        .clickable(onClick = onClickDisable)
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                )
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {},
    )
}
