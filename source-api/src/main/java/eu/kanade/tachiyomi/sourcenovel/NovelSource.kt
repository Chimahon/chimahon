package eu.kanade.tachiyomi.sourcenovel

import eu.kanade.tachiyomi.sourcenovel.model.ChapterContent
import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel

interface NovelSource {
    val id: Long
    val name: String
    val lang: String

    suspend fun getNovelDetails(novel: SNNovel): SNNovel
    suspend fun getChapterList(novel: SNNovel): List<SNChapter>
    suspend fun getChapterContent(chapter: SNChapter): ChapterContent
}
