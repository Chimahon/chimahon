package chimahon.novel.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import chimahon.novel.manager.NovelSourceManager
import chimahon.novel.plugin.normalizeNovelLanguage
import eu.kanade.domain.source.interactor.ToggleNovelLanguage
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.icons.FlagEmoji
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.SortedMap

class NovelSourcesFilterScreenModel(
    private val novelSourceManager: NovelSourceManager = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
    private val toggleLanguage: ToggleNovelLanguage = Injekt.get(),
) : StateScreenModel<NovelSourcesFilterScreenModel.State>(State.Loading) {

    init {
        screenModelScope.launchIO {
            combine(
                preferences.enabledNovelLanguages().changes(),
                preferences.disabledNovelSources().changes(),
                novelSourceManager.catalogueSources,
            ) { enabledLanguages, disabledSources, allSources ->
                val sortedSources = allSources
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

                val byLang = sortedSources
                    .groupBy { normalizeNovelLanguage(it.lang) }
                    .toSortedMap()

                State.Success(
                    items = byLang.mapValues { it.value.toImmutableList() }.toSortedMap(),
                    enabledLanguages = enabledLanguages.toImmutableSet(),
                    disabledSources = disabledSources.toImmutableSet(),
                )
            }.collectLatest { state ->
                mutableState.update { state }
            }
        }
    }

    fun toggleLanguage(language: String) {
        toggleLanguage.await(language)
    }

    fun toggleSource(source: NovelSource) {
        val isEnabled = source.id.toString() !in preferences.disabledNovelSources().get()
        preferences.disabledNovelSources().getAndSet { disabled ->
            if (isEnabled) disabled + source.id.toString() else disabled - source.id.toString()
        }
    }

    sealed interface State {
        @Immutable
        data object Loading : State

        @Immutable
        data class Success(
            val items: SortedMap<String, ImmutableList<NovelsPageSource>>,
            val enabledLanguages: ImmutableSet<String>,
            val disabledSources: ImmutableSet<String>,
        ) : State {
            val isEmpty: Boolean
                get() = items.isEmpty()
        }
    }
}

class NovelSourcesFilterScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelSourcesFilterScreenModel() }
        val state by screenModel.state.collectAsState()

        if (state is NovelSourcesFilterScreenModel.State.Loading) {
            LoadingScreen()
            return
        }

        val successState = state as NovelSourcesFilterScreenModel.State.Success

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.label_sources),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            if (successState.isEmpty) {
                EmptyScreen(
                    stringRes = MR.strings.source_filter_empty_screen,
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }
            FastScrollLazyColumn(
                contentPadding = contentPadding,
            ) {
                successState.items.forEach { (language, sources) ->
                    val enabled = language in successState.enabledLanguages
                    item(
                        key = language,
                        contentType = "source-filter-header",
                    ) {
                        SwitchPreferenceWidget(
                            title = LocaleHelper.getSourceDisplayName(language, LocalContext.current) +
                                (
                                    " (${LocaleHelper.getDisplayName(language)} ${FlagEmoji.getEmojiLangFlag(language)})"
                                        .takeIf { language !in listOf("all", "other") }
                                        ?: " (${FlagEmoji.getEmojiLangFlag(language)})"
                                    ),
                            checked = enabled,
                            onCheckedChanged = { screenModel.toggleLanguage(language) },
                        )
                    }
                    if (enabled) {
                        items(
                            items = sources,
                            key = { "source-filter-${it.id}" },
                            contentType = { "source-filter-item" },
                        ) { source ->
                            Row(
                                modifier = Modifier
                                    .clickable { screenModel.toggleSource(source) }
                                    .fillMaxWidth()
                                    .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = source.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Checkbox(
                                    checked = source.id.toString() !in successState.disabledSources,
                                    onCheckedChange = null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
