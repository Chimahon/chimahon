package chimahon.novel.interactor

import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.NovelLibraryPreferences
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.repository.NovelCategoryRepository
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Category writes for novels. Id-mapping stays at the call sites (payload
 * string ids differ per flow); the write itself lives here.
 */
class SetNovelCategories(
    private val novelCategoryRepository: NovelCategoryRepository = Injekt.get(),
    private val novelRepository: NovelRepository = Injekt.get(),
    private val preferences: NovelLibraryPreferences = Injekt.get(),
) {

    suspend fun await(novelId: Long, categoryIds: List<Long>) {
        try {
            novelCategoryRepository.setNovelCategories(novelId, categoryIds)
            val folder = preferences.downloadedBookFolder(novelId).get()
            if (folder.isNotBlank()) {
                val localId = novelRepository.getNovelByUrlAndSourceId("local://$folder", Novel.LOCAL_SOURCE_ID)?.id
                if (localId != null && localId != novelId) {
                    novelCategoryRepository.setNovelCategories(localId, categoryIds)
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }
}
