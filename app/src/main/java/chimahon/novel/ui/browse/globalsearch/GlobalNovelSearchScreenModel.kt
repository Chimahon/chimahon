package chimahon.novel.ui.browse.globalsearch

import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource

class GlobalNovelSearchScreenModel(
    initialQuery: String = "",
) : NovelSearchScreenModel(
    State(
        searchQuery = initialQuery,
    ),
) {

    init {
        if (initialQuery.isNotBlank()) {
            search()
        }
    }

    override fun getEnabledSources(): List<NovelsPageSource> {
        return super.getEnabledSources()
            .filter { state.value.sourceFilter != NovelSourceFilter.PinnedOnly || "${it.id}" in pinnedSources }
    }
}
