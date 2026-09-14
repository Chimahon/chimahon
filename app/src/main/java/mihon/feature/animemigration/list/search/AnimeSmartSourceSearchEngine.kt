package mihon.feature.animemigration.list.search

import eu.kanade.domain.entries.anime.model.titleOrUrl
import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import mihon.feature.migration.list.search.BaseSmartSearchEngine
import mihon.feature.migration.list.search.SearchAction
import tachiyomi.domain.entries.anime.model.Anime

class AnimeSmartSourceSearchEngine(extraSearchParams: String?) : BaseSmartSearchEngine<SAnime>(extraSearchParams) {

    override fun getTitle(result: SAnime) = result.titleOrUrl()

    suspend fun regularSearch(source: AnimeCatalogueSource, title: String): Anime? {
        return regularSearch(makeSearchAction(source), title)?.toDomainAnime(source.id)
    }

    suspend fun deepSearch(source: AnimeCatalogueSource, title: String): Anime? {
        return deepSearch(makeSearchAction(source), title)?.toDomainAnime(source.id)
    }

    private fun makeSearchAction(source: AnimeCatalogueSource): SearchAction<SAnime> = { query ->
        source.getSearchAnime(1, query, source.getFilterList()).animes
    }
}
