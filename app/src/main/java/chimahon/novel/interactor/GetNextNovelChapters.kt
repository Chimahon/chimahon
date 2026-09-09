package chimahon.novel.interactor

import tachiyomi.domain.novel.model.NovelChapter
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.max

/**
 * Reading-order resume candidates with the novel's persisted view filters
 * applied. Display sort never applies here — the oldest unread chapter wins
 * regardless of how the list is displayed.
 */
class GetNextNovelChapters(
    private val novelRepository: NovelRepository = Injekt.get(),
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
) {

    suspend fun await(novelId: Long, onlyUnread: Boolean = true): List<NovelChapter> {
        val novel = runCatching { novelRepository.getNovelById(novelId) }.getOrNull()
        val chapters = runCatching { novelChapterRepository.getChaptersByNovelId(novelId) }
            .getOrNull().orEmpty()
            .sortedBy { it.chapterNumber }
            .let { list ->
                // Persisted view filters (detail checkboxes); bookmark scope
                // only narrows when the user actually set it.
                if (novel?.chapterFilterBookmarked == true) {
                    list.filter { it.bookmark }
                } else {
                    list
                }
            }
        return if (onlyUnread || novel?.chapterFilterUnread == true) {
            chapters.filterNot { it.read }
        } else {
            chapters
        }
    }

    suspend fun await(
        novelId: Long,
        fromChapterId: Long,
        onlyUnread: Boolean = true,
    ): List<NovelChapter> {
        val chapters = await(novelId, onlyUnread)
        val currChapterIndex = chapters.indexOfFirst { it.id == fromChapterId }
        val nextChapters = chapters.subList(max(0, currChapterIndex), chapters.size)

        if (onlyUnread) {
            return nextChapters
        }

        // The "next chapter" is either:
        // - The current chapter if it isn't completely read
        // - The chapters after the current chapter if the current one is completely read
        val fromChapter = chapters.getOrNull(currChapterIndex)
        return if (fromChapter != null && !fromChapter.read) {
            nextChapters
        } else {
            nextChapters.drop(1)
        }
    }
}
