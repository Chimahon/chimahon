package chimahon.novel.ui.browse

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import chimahon.novel.manager.NovelSourceManager
import chimahon.novel.plugin.NovelPluginManager
import chimahon.novel.plugin.SimpleLNReaderSource
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.preference.getAndSet
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelSourcesScreenModel(
    private val novelSourceManager: NovelSourceManager = Injekt.get(),
    private val pluginManager: NovelPluginManager = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
) : StateScreenModel<NovelSourcesScreenModel.State>(State()) {

    init {
        screenModelScope.launchIO {
            combine(
                novelSourceManager.catalogueSources,
                preferences.enabledNovelLanguages().changes(),
                preferences.pinnedNovelSources().changes(),
                preferences.disabledNovelSources().changes(),
                pluginManager.catalog,
            ) { sources, enabledLanguages, pinnedSources, disabledSources, catalog ->
                val iconByPluginId = (
                    catalog.available.map { it.id to it.iconUrl } +
                        catalog.installed.map { it.descriptor.id to it.descriptor.iconUrl }
                    ).toMap()

                sources
                    .filter { it.id.toString() !in disabledSources }
                    .filter { it.lang == "all" || it.lang in enabledLanguages || "all" in enabledLanguages || enabledLanguages.isEmpty() }
                    .map { source ->
                        NovelSourceUiModel(
                            source = source,
                            isPinned = source.id.toString() in pinnedSources,
                            iconUrl = (source as? SimpleLNReaderSource)?.pluginId?.let { iconByPluginId[it] },
                        )
                    }
                    .sortedWith(
                        compareBy(String.CASE_INSENSITIVE_ORDER) { it.source.name },
                    )
                    .groupBy { if (it.isPinned) PINNED_KEY else it.source.lang }
                    .toSortedMap { first, second ->
                        when {
                            first == PINNED_KEY && second != PINNED_KEY -> -1
                            second == PINNED_KEY && first != PINNED_KEY -> 1
                            else -> LocaleHelper.comparator(first, second)
                        }
                    }
            }
                .collectLatest { grouped ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            items = grouped,
                        )
                    }
                }
        }
    }

    fun togglePin(source: NovelsPageSource) {
        Injekt.get<chimahon.novel.interactor.ToggleNovelSourcePin>().await(source)
    }

    fun toggleSource(source: NovelsPageSource) {
        val sourceId = source.id.toString()
        preferences.disabledNovelSources().getAndSet { disabled ->
            if (sourceId in disabled) disabled - sourceId else disabled + sourceId
        }
    }

    fun showSourceDialog(source: NovelsPageSource) {
        mutableState.update { it.copy(dialog = Dialog(source)) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    data class Dialog(val source: NovelsPageSource)

    @Immutable
    data class State(
        val dialog: Dialog? = null,
        val isLoading: Boolean = true,
        val items: Map<String, List<NovelSourceUiModel>> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = items.isEmpty()
    }

    @Immutable
    data class NovelSourceUiModel(
        val source: NovelsPageSource,
        val isPinned: Boolean,
        val iconUrl: String? = null,
    )

    companion object {
        const val PINNED_KEY = "pinned"
    }
}
