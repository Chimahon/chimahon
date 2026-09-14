package mihon.feature.animemigration.config

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import androidx.compose.ui.util.fastForEachIndexed
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.components.AnimatedFloatingSearchBox
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.SOURCE_SEARCH_BOX_HEIGHT
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.update
import mihon.feature.animemigration.list.AnimeMigrationListScreen
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.anime.model.AnimeSource
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.FlagEmoji
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.shouldExpandFAB
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Screen that allows the user to configure which sources should be used when migrating anime.
 *
 * It displays available sources, supports searching and bulk selection, and opens the
 * anime mass-migration list for the selected sources.
 *
 * @param animeIds IDs of the anime that will be migrated using the sources configured on this screen.
 */
class AnimeMigrationConfigScreen(private val animeIds: Collection<Long>) : Screen() {

    constructor(animeId: Long) : this(listOf(animeId))

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        val screenModel = rememberScreenModel { ScreenModel() }
        val state by screenModel.state.collectAsState()

        var searchQuery by remember { mutableStateOf("") }
        BackHandler(enabled = searchQuery.isNotBlank()) {
            searchQuery = ""
        }

        fun continueMigration() {
            navigator.replace(
                AnimeMigrationListScreen(animeIds, null, screenModel.sourcePreferences.migrationSmartSearchSingleEntry().get()),
            )
        }

        if (state.isLoading) {
            LoadingScreen()
            return
        }

        val (selectedSources, availableSources) = state.sources
            .filter { sources ->
                if (searchQuery.isBlank()) return@filter true
                val source = sources.source
                searchQuery.split(",").any {
                    val input = it.trim()
                    if (input.isEmpty()) return@any false
                    source.name.contains(input, ignoreCase = true) ||
                        source.id == input.toLongOrNull()
                }
            }
            .partition { it.isSelected }
        val showLanguage by remember(state) {
            derivedStateOf {
                state.sources.distinctBy { it.source.lang }.size > 1
            }
        }

        val lazyListState = rememberLazyListState()
        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(SYMR.strings.select_sources),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectPinnedLabel),
                                    icon = Icons.Outlined.PushPin,
                                    onClick = { screenModel.toggleSelection(ScreenModel.SelectionConfig.Pinned) },
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectNoneLabel),
                                    icon = Icons.Outlined.Deselect,
                                    onClick = { screenModel.toggleSelection(ScreenModel.SelectionConfig.None) },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectEnabledLabel),
                                    onClick = { screenModel.toggleSelection(ScreenModel.SelectionConfig.Enabled) },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.migrationConfigScreen_selectAllLabel),
                                    onClick = { screenModel.toggleSelection(ScreenModel.SelectionConfig.All) },
                                ),
                            ),
                        )
                    },
                )
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = selectedSources.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                    content = {
                        SmallExtendedFloatingActionButton(
                            text = { Text(text = stringResource(MR.strings.migrationConfigScreen_continueButtonText)) },
                            icon = { Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null) },
                            onClick = {
                                screenModel.saveSources()
                                continueMigration()
                            },
                            expanded = lazyListState.shouldExpandFAB(),
                        )
                    },
                )
            },
        ) { contentPadding ->
            val reorderableState = rememberReorderableLazyListState(lazyListState, contentPadding) { from, to ->
                val fromIndex = selectedSources.indexOfFirst { it.id == from.key }
                val toIndex = selectedSources.indexOfFirst { it.id == to.key }
                if (fromIndex == -1 || toIndex == -1) return@rememberReorderableLazyListState
                screenModel.orderSource(fromIndex, toIndex)
            }
            Box(
                modifier = Modifier.padding(contentPadding),
            ) {
                val density = LocalDensity.current
                var searchBoxHeight by remember { mutableStateOf(SOURCE_SEARCH_BOX_HEIGHT) }

                FastScrollLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = lazyListState,
                    contentPadding = PaddingValues(top = searchBoxHeight),
                ) {
                    listOf(selectedSources, availableSources).fastForEachIndexed { listIndex, sources ->
                        val selectedSourceList = listIndex == 0
                        if (sources.isNotEmpty()) {
                            val headerPrefix = if (selectedSourceList) "selected" else "available"
                            item("$headerPrefix-header") {
                                Text(
                                    text = stringResource(
                                        resource = if (selectedSourceList) {
                                            MR.strings.migrationConfigScreen_selectedHeader
                                        } else {
                                            MR.strings.migrationConfigScreen_availableHeader
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .padding(MaterialTheme.padding.medium)
                                        .animateItem(),
                                )
                            }
                        }
                        itemsIndexed(
                            items = sources,
                            key = { _, item -> item.id },
                        ) { index, item ->
                            SourceItemContainer(
                                firstItem = index == 0,
                                lastItem = index == (sources.size - 1),
                                source = item,
                                showLanguage = showLanguage,
                                dragEnabled = selectedSourceList && sources.size > 1,
                                state = reorderableState,
                                key = { if (selectedSourceList) it.id else "available-${it.id}" },
                                onClick = { screenModel.toggleSelection(item.id) },
                            )
                        }
                    }
                }

                AnimatedFloatingSearchBox(
                    listState = lazyListState,
                    searchQuery = searchQuery,
                    onChangeSearchQuery = { searchQuery = it ?: "" },
                    placeholderText = stringResource(KMR.strings.action_search_for_source),
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .padding(
                            horizontal = MaterialTheme.padding.medium,
                            vertical = MaterialTheme.padding.small,
                        )
                        .align(Alignment.TopCenter),
                    onGloballyPositioned = { layoutCoordinates ->
                        searchBoxHeight = with(density) { layoutCoordinates.size.height.toDp() + 2 * MaterialTheme.padding.small }
                    },
                )
            }
        }
    }

    @Composable
    private fun LazyItemScope.SourceItemContainer(
        firstItem: Boolean,
        lastItem: Boolean,
        source: MigrationSource,
        showLanguage: Boolean,
        dragEnabled: Boolean,
        state: ReorderableLazyListState,
        key: (MigrationSource) -> Any,
        onClick: () -> Unit,
    ) {
        val shape = remember(firstItem, lastItem) {
            val top = if (firstItem) 12.dp else 0.dp
            val bottom = if (lastItem) 12.dp else 0.dp
            RoundedCornerShape(top, top, bottom, bottom)
        }

        ReorderableItem(
            state = state,
            key = key(source),
            enabled = dragEnabled,
        ) { _ ->
            ElevatedCard(
                shape = shape,
                modifier = Modifier
                    .padding(horizontal = MaterialTheme.padding.medium)
                    .animateItem(),
            ) {
                SourceItem(
                    source = source,
                    showLanguage = showLanguage,
                    dragEnabled = dragEnabled,
                    scope = this@ReorderableItem,
                    onClick = onClick,
                )
            }
        }

        if (!lastItem) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium))
        }
    }

    @Composable
    private fun SourceItem(
        source: MigrationSource,
        showLanguage: Boolean,
        dragEnabled: Boolean,
        scope: ReorderableCollectionItemScope,
        onClick: () -> Unit,
    ) {
        ListItem(
            headlineContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = source.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (showLanguage) {
                        Pill(
                            text = FlagEmoji.getEmojiLangFlag(source.shortLanguage) + " (${source.upperShortLanguage})",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            trailingContent = if (dragEnabled) {
                {
                    Icon(
                        imageVector = Icons.Outlined.DragHandle,
                        contentDescription = null,
                        modifier = with(scope) {
                            Modifier.draggableHandle()
                        },
                    )
                }
            } else {
                null
            },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
            ),
            modifier = Modifier.clickable(onClick = onClick),
        )
    }

    private class ScreenModel(
        val sourcePreferences: SourcePreferences = Injekt.get(),
        private val sourceManager: AnimeSourceManager = Injekt.get(),
    ) : StateScreenModel<ScreenModel.State>(State()) {

        private val pinnedSources by lazy { sourcePreferences.pinnedAnimeSources().get().mapNotNull { it.toLongOrNull() } }
        private val disabledSources by lazy { sourcePreferences.disabledAnimeSources().get().mapNotNull { it.toLongOrNull() } }

        private val sourcesComparator = { includedSources: Map<Long, Int> ->
            compareBy<MigrationSource>(
                { includedSources[it.source.id] ?: Int.MAX_VALUE },
                { with(it) { "$name ($shortLanguage)" } },
            )
        }

        init {
            screenModelScope.launchIO {
                initSources()
                mutableState.update { it.copy(isLoading = false) }
            }
        }

        private fun updateSources(action: (List<MigrationSource>) -> List<MigrationSource>) {
            mutableState.update { state ->
                val updatedSources = action(state.sources)
                val includedSources = updatedSources.mapNotNull { if (!it.isSelected) null else it.id }
                    .mapIndexed { index, id -> id to index }.toMap()
                state.copy(sources = updatedSources.sortedWith(sourcesComparator(includedSources)))
            }
            saveSources()
        }

        private fun initSources() {
            val languages = sourcePreferences.enabledAnimeLanguages().get()
            val includedSources = sourcePreferences.migrationAnimeSources().get()
                .mapIndexed { index, id -> id to index }.toMap()
            val sources = sourceManager
                .getCatalogueSources()
                .asSequence()
                .filterIsInstance<AnimeHttpSource>()
                .filter { it.lang in languages }
                .map {
                    val source = AnimeSource(
                        id = it.id,
                        lang = it.lang,
                        name = it.name,
                        supportsLatest = it.supportsLatest,
                        isStub = false,
                    )
                    MigrationSource(
                        source = source,
                        isSelected = when {
                            includedSources.isNotEmpty() -> source.id in includedSources
                            pinnedSources.isNotEmpty() -> source.id in pinnedSources
                            else -> source.id !in disabledSources
                        },
                    )
                }
                .toList()

            mutableState.update { state ->
                state.copy(sources = sources.sortedWith(sourcesComparator(includedSources)))
            }
        }

        fun toggleSelection(id: Long) {
            updateSources { sources ->
                sources.map { source ->
                    source.copy(isSelected = if (source.source.id == id) !source.isSelected else source.isSelected)
                }
            }
        }

        fun toggleSelection(config: SelectionConfig) {
            val isSelected: (Long) -> Boolean = {
                when (config) {
                    SelectionConfig.All -> true
                    SelectionConfig.None -> false
                    SelectionConfig.Pinned -> it in pinnedSources
                    SelectionConfig.Enabled -> it !in disabledSources
                }
            }
            updateSources { sources ->
                sources.map { source ->
                    source.copy(isSelected = isSelected(source.source.id))
                }
            }
        }

        fun orderSource(from: Int, to: Int) {
            updateSources {
                it.toMutableList()
                    .apply {
                        add(to, removeAt(from))
                    }
                    .toList()
            }
        }

        fun saveSources() {
            state.value.sources
                .filter { source -> source.isSelected }
                .map { source -> source.source.id }
                .let { sources -> sourcePreferences.migrationAnimeSources().set(sources) }
        }

        data class State(
            val isLoading: Boolean = true,
            val sources: List<MigrationSource> = emptyList(),
        )

        enum class SelectionConfig {
            All,
            None,
            Pinned,
            Enabled,
        }
    }

    data class MigrationSource(
        val source: AnimeSource,
        val isSelected: Boolean,
        val shortLanguage: String = LocaleHelper.getShortDisplayName(source.lang),
        val upperShortLanguage: String = shortLanguage.uppercase(),
    ) {
        val id: Long
            inline get() = source.id

        val name: String
            inline get() = source.name
    }
}
