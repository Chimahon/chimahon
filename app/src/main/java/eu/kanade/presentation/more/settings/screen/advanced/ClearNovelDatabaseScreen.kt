package eu.kanade.presentation.more.settings.screen.advanced

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastMap
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import chimahon.novel.manager.NovelSourceManager
import eu.kanade.presentation.browse.novel.components.NovelSourceIcon
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.domain.novel.repository.NovelRepository
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LazyColumnWithAction
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.selectedBackground
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ClearNovelDatabaseScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = rememberScreenModel { ClearNovelDatabaseScreenModel() }
        val state by model.state.collectAsState()
        val scope = rememberCoroutineScope()

        when (val s = state) {
            is ClearNovelDatabaseScreenModel.State.Loading -> LoadingScreen()
            is ClearNovelDatabaseScreenModel.State.Ready -> {
                if (s.showConfirmation) {
                    var keepReadNovels by remember { mutableStateOf(true) }
                    AlertDialog(
                        title = { Text(text = stringResource(MR.strings.are_you_sure)) },
                        text = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                            ) {
                                Text(text = stringResource(MR.strings.clear_novel_database_confirmation))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(MR.strings.clear_db_exclude_read),
                                        modifier = Modifier.weight(1f),
                                    )
                                    Switch(
                                        checked = keepReadNovels,
                                        onCheckedChange = { keepReadNovels = it },
                                    )
                                }
                                if (!keepReadNovels) {
                                    Text(
                                        text = stringResource(MR.strings.clear_database_history_warning),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                        onDismissRequest = model::hideConfirmation,
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    scope.launchUI {
                                        model.removeNovelsBySourceId(keepReadNovels)
                                        model.clearSelection()
                                        model.hideConfirmation()
                                        context.toast(MR.strings.clear_database_completed)
                                    }
                                },
                            ) {
                                Text(text = stringResource(MR.strings.action_ok))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = model::hideConfirmation) {
                                Text(text = stringResource(MR.strings.action_cancel))
                            }
                        },
                    )
                }

                Scaffold(
                    topBar = { scrollBehavior ->
                        AppBar(
                            title = stringResource(MR.strings.pref_clear_novel_database),
                            navigateUp = navigator::pop,
                            actions = {
                                if (s.items.isNotEmpty()) {
                                    AppBarActions(
                                        actions = persistentListOf(
                                            AppBar.Action(
                                                title = stringResource(MR.strings.action_select_all),
                                                icon = Icons.Outlined.SelectAll,
                                                onClick = model::selectAll,
                                            ),
                                            AppBar.Action(
                                                title = stringResource(MR.strings.action_select_inverse),
                                                icon = Icons.Outlined.FlipToBack,
                                                onClick = model::invertSelection,
                                            ),
                                        ),
                                    )
                                }
                            },
                            scrollBehavior = scrollBehavior,
                        )
                    },
                ) { contentPadding ->
                    if (s.items.isEmpty()) {
                        EmptyScreen(
                            message = stringResource(MR.strings.database_clean),
                            modifier = Modifier.padding(contentPadding),
                        )
                    } else {
                        LazyColumnWithAction(
                            contentPadding = contentPadding,
                            actionLabel = stringResource(MR.strings.action_delete),
                            actionEnabled = s.selection.isNotEmpty(),
                            onClickAction = model::showConfirmation,
                        ) {
                            items(s.items) { sourceWithNovels ->
                                ClearNovelDatabaseItem(
                                    name = sourceWithNovels.displayName,
                                    lang = sourceWithNovels.lang,
                                    isStub = sourceWithNovels.isStub,
                                    count = sourceWithNovels.ids.size.toLong(),
                                    isSelected = s.selection.contains(sourceWithNovels.id),
                                    onClickSelect = { model.toggleSelection(sourceWithNovels.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ClearNovelDatabaseItem(
        name: String,
        lang: String,
        isStub: Boolean,
        count: Long,
        isSelected: Boolean,
        onClickSelect: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .selectedBackground(isSelected)
                .clickable(onClick = onClickSelect)
                .padding(horizontal = 8.dp)
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NovelSourceIcon(iconUrl = null, isStub = isStub)
            Column(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
            ) {
                Text(
                    text = if (lang.isNotBlank()) "$name (${lang.uppercase()})" else name,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(text = stringResource(MR.strings.clear_database_source_item_count, count))
            }
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClickSelect() },
            )
        }
    }
}

private data class NovelSourceWithNovels(
    val id: Long,
    val displayName: String,
    val lang: String,
    val isStub: Boolean,
    val ids: List<Long>,
)

private class ClearNovelDatabaseScreenModel : StateScreenModel<ClearNovelDatabaseScreenModel.State>(
    State.Loading,
) {
    private val novelRepository: NovelRepository = Injekt.get()
    private val sourceManager: NovelSourceManager = Injekt.get()

    init {
        reload()
    }

    private fun reload() {
        screenModelScope.launchIO {
            val nonLibrary = runCatching { novelRepository.getAll() }
                .getOrNull().orEmpty()
                .filter { !it.favorite }
            val items = nonLibrary
                .groupBy { it.source }
                .map { (sourceId, novels) ->
                    val live = sourceManager.getNovelSource(sourceId)
                    val display = live ?: sourceManager.getOrStub(sourceId)
                    NovelSourceWithNovels(
                        id = sourceId,
                        displayName = display.name.ifBlank { sourceId.toString() },
                        lang = display.lang,
                        isStub = live == null,
                        ids = novels.map { it.id },
                    )
                }
                .sortedBy { it.displayName.lowercase() }
            mutableState.update { State.Ready(items) }
        }
    }

    suspend fun removeNovelsBySourceId(keepReadNovels: Boolean) = withNonCancellableContext {
        val state = state.value as? State.Ready ?: return@withNonCancellableContext
        val ids = state.items
            .filter { it.id in state.selection }
            .flatMap { it.ids }
        novelRepository.deleteNonLibraryNovelsByIds(ids, keepReadNovels)
        reload()
    }

    fun toggleSelection(sourceId: Long) = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        val mutableList = state.selection.toMutableList()
        if (mutableList.contains(sourceId)) {
            mutableList.remove(sourceId)
        } else {
            mutableList.add(sourceId)
        }
        state.copy(selection = mutableList)
    }

    fun clearSelection() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(selection = emptyList())
    }

    fun selectAll() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(selection = state.items.fastMap { it.id })
    }

    fun invertSelection() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(
            selection = state.items
                .fastMap { it.id }
                .filterNot { it in state.selection },
        )
    }

    fun showConfirmation() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(showConfirmation = true)
    }

    fun hideConfirmation() = mutableState.update { state ->
        if (state !is State.Ready) return@update state
        state.copy(showConfirmation = false)
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Ready(
            val items: List<NovelSourceWithNovels>,
            val selection: List<Long> = emptyList(),
            val showConfirmation: Boolean = false,
        ) : State
    }
}
