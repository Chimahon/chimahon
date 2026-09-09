package chimahon.novel.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.novel.model.NovelChapter
import tachiyomi.domain.novel.model.NovelChapterUpdate
import tachiyomi.domain.novel.repository.NovelChapterRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Mark chapters read/unread through one bulk update. Unread resets the
 * halfway position (`lastPageRead` + `progress`); read keeps it. Chapters
 * already in the target state are skipped so big selections stay cheap.
 */
class SetNovelReadStatus(
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
) {

    private val mapper = { chapter: NovelChapter, read: Boolean ->
        NovelChapterUpdate(
            id = chapter.id,
            read = read,
            lastPageRead = if (!read) 0L else null,
            progress = if (!read) 0.0 else null,
        )
    }

    suspend fun await(read: Boolean, chapters: List<NovelChapter>): Result = withNonCancellableContext {
        val chaptersToUpdate = chapters.filter {
            when (read) {
                true -> !it.read
                false -> it.read || it.lastPageRead > 0 || it.progress > 0.0
            }
        }
        if (chaptersToUpdate.isEmpty()) {
            return@withNonCancellableContext Result.NoChapters
        }

        try {
            novelChapterRepository.updateAll(
                chaptersToUpdate.map { mapper(it, read) },
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            return@withNonCancellableContext Result.InternalError(e)
        }

        Result.Success
    }

    suspend fun await(novelId: Long, read: Boolean): Result = withNonCancellableContext {
        await(
            read = read,
            chapters = novelChapterRepository.getChaptersByNovelId(novelId),
        )
    }

    sealed interface Result {
        data object Success : Result
        data object NoChapters : Result
        data class InternalError(val error: Throwable) : Result
    }
}
