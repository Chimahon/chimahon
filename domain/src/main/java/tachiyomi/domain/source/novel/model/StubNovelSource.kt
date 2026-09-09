package tachiyomi.domain.source.novel.model

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.NovelPage
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel

class StubNovelSource(
    override val id: Long,
    override val lang: String,
    override val name: String,
) : NovelsPageSource {

    private val isInvalid: Boolean = name.isBlank() || lang.isBlank()

    override val supportsLatest: Boolean = false

    override suspend fun getNovelDetails(novel: SNNovel): SNNovel =
        throw NovelSourceNotInstalledException()

    override suspend fun getChapterList(novel: SNNovel): List<SNChapter> =
        throw NovelSourceNotInstalledException()

    override suspend fun getChapterContent(chapter: SNChapter): ChapterContent =
        throw NovelSourceNotInstalledException()

    override suspend fun getPopularNovels(page: Int): NovelPage =
        throw NovelSourceNotInstalledException()

    override suspend fun getSearchNovels(page: Int, query: String, filters: FilterList): NovelPage =
        throw NovelSourceNotInstalledException()

    override suspend fun getLatestUpdates(page: Int): NovelPage =
        throw NovelSourceNotInstalledException()

    override fun getFilterList(): FilterList = FilterList()

    override fun toString(): String =
        if (!isInvalid) "$name (${lang.uppercase()})" else id.toString()

    companion object {
        fun from(source: NovelsPageSource): StubNovelSource {
            return StubNovelSource(id = source.id, lang = source.lang, name = source.name)
        }
    }
}

class NovelSourceNotInstalledException : Exception()
