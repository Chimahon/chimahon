package chimahon.novel.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.novel.repository.NovelCategoryRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Category writes for novels. Id-mapping stays at the call sites (payload
 * string ids differ per flow); the write itself lives here.
 */
class SetNovelCategories(
    private val novelCategoryRepository: NovelCategoryRepository = Injekt.get(),
) {

    suspend fun await(novelId: Long, categoryIds: List<Long>) {
        try {
            novelCategoryRepository.setNovelCategories(novelId, categoryIds)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }
}
