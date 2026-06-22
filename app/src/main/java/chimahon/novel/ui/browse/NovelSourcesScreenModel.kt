package chimahon.novel.ui.browse

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import chimahon.novel.manager.NovelSourceManager
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
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
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val preferences: SourcePreferences = Injekt.get(),
) : StateScreenModel<NovelSourcesScreenModel.State>(State()) {

    init {
        screenModelScope.launchIO {
            combine(
                novelSourceManager.catalogueSources,
                preferences.enabledLanguages().changes(),
                preferences.pinnedNovelSources().changes(),
                preferences.disabledNovelSources().changes(),
                extensionManager.installedNovelExtensionsFlow,
            ) { sources, enabledLanguages, pinnedSources, disabledSources, extensions ->
                val extensionBySourceId = extensions
                    .flatMap { extension -> extension.novelSources.map { source -> source.id to extension } }
                    .toMap()

                sources
                    .filter { it.id.toString() !in disabledSources }
                    .map { source ->
                        NovelSourceUiModel(
                            source = source,
                            extension = extensionBySourceId[source.id],
                            isPinned = source.id.toString() in pinnedSources,
                        )
                    }
                    .sortedWith(
                        compareBy<NovelSourceUiModel> { !it.isPinned }
                            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.source.name },
                    )
                    .groupBy { it.source.lang }
                    .toSortedMap(LocaleHelper.comparator)
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
        val sourceId = source.id.toString()
        preferences.pinnedNovelSources().getAndSet { pinned ->
            if (sourceId in pinned) pinned - sourceId else pinned + sourceId
        }
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
        val extension: Extension.Installed?,
        val isPinned: Boolean,
    )
}
