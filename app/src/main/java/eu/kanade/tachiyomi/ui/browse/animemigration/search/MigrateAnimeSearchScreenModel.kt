package eu.kanade.tachiyomi.ui.browse.animemigration.search

import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.ui.browse.animesource.globalsearch.AnimeSearchItemResult
import eu.kanade.tachiyomi.ui.browse.animesource.globalsearch.AnimeSearchScreenModel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MigrateAnimeSearchScreenModel(
    val animeId: Long,
    initialExtensionFilter: String = "",
    getAnime: GetAnime = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val sourceManager: AnimeSourceManager = Injekt.get(),
) : AnimeSearchScreenModel() {

    private val migrationSources by lazy { sourcePreferences.migrationAnimeSources().get() }

    init {
        extensionFilter = initialExtensionFilter
        screenModelScope.launch {
            val anime = getAnime.await(animeId) ?: return@launch
            mutableState.update {
                it.copy(
                    fromSourceId = anime.source,
                    searchQuery = anime.title,
                )
            }

            search()
        }
    }

    /**
     * Only the sources the user configured for anime migration, in the order they configured them.
     * Mirrors the manga migration search, including having no fallback: if nothing is configured
     * the list is simply empty rather than silently searching every source.
     */
    override fun getEnabledSources(): List<AnimeCatalogueSource> {
        return migrationSources.mapNotNull { sourceId ->
            sourceManager.get(sourceId) as? AnimeCatalogueSource
        }
    }

    override val sortComparator = { map: Map<AnimeCatalogueSource, AnimeSearchItemResult> ->
        compareBy<AnimeCatalogueSource>(
            { (map[it] as? AnimeSearchItemResult.Success)?.isEmpty ?: true },
            { migrationSources.indexOf(it.id) },
        )
    }
}
