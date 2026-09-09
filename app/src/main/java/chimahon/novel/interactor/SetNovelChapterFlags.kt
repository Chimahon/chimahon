package chimahon.novel.interactor

import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelUpdate
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * One home for chapter-view prefs (sort/flip, unread + bookmark filters,
 * full-flag writes for the set-as-default UI).
 */
class SetNovelChapterFlags(
    private val novelRepository: NovelRepository = Injekt.get(),
) {

    suspend fun awaitSetUnreadFilter(novelId: Long, unreadOnly: Boolean): Boolean {
        return novelRepository.update(
            NovelUpdate(id = novelId, chapterFilterUnread = unreadOnly),
        )
    }

    suspend fun awaitSetBookmarkFilter(novelId: Long, bookmarkedOnly: Boolean): Boolean {
        return novelRepository.update(
            NovelUpdate(id = novelId, chapterFilterBookmarked = bookmarkedOnly),
        )
    }

    suspend fun awaitSetSortingModeOrFlipOrder(novel: Novel, mode: Long): Boolean {
        val (newMode, newDescending) = if (novel.chapterSortMode == mode) {
            // Just flip the order.
            mode to !novel.chapterSortDescending
        } else {
            // Set new mode with ascending order.
            mode to false
        }
        return novelRepository.update(
            NovelUpdate(
                id = novel.id,
                chapterSortMode = newMode,
                chapterSortDescending = newDescending,
            ),
        )
    }

    suspend fun awaitSetAllFlags(
        novelId: Long,
        unreadOnly: Boolean,
        bookmarkedOnly: Boolean,
        sortingMode: Long,
        sortingDescending: Boolean,
    ): Boolean {
        return novelRepository.update(
            NovelUpdate(
                id = novelId,
                chapterFilterUnread = unreadOnly,
                chapterFilterBookmarked = bookmarkedOnly,
                chapterSortMode = sortingMode,
                chapterSortDescending = sortingDescending,
            ),
        )
    }
}
