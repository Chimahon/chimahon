package eu.kanade.tachiyomi.sourcenovel

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.sourcenovel.model.NovelPage
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel

interface NovelsPageSource : NovelSource {
    val supportsLatest: Boolean

    suspend fun getPopularNovels(page: Int): NovelPage
    suspend fun getSearchNovels(page: Int, query: String, filters: FilterList): NovelPage
    suspend fun getLatestUpdates(page: Int): NovelPage
    fun getFilterList(): FilterList
}
