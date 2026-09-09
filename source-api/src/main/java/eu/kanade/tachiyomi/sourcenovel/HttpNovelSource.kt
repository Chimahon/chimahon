package eu.kanade.tachiyomi.sourcenovel

import eu.kanade.tachiyomi.sourcenovel.model.SNChapter
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel

interface HttpNovelSource : NovelSource {
    fun getNovelUrl(novel: SNNovel): String?
    fun getChapterUrl(chapter: SNChapter): String?
}
