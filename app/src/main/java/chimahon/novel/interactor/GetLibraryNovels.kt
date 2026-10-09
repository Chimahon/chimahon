package chimahon.novel.interactor

import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Library and read-but-removed novels straight out of novel_libraryView, the
 * novel counterpart of GetLibraryManga / GetReadMangaNotInLibraryView. The
 * counts live in the view so call sites never recompute them.
 */
class GetLibraryNovels(
    private val novelRepository: NovelRepository = Injekt.get(),
) {

    suspend fun await(): List<Novel> {
        return novelRepository.getLibraryNovels()
    }
}

class GetReadNovelNotInLibraryView(
    private val novelRepository: NovelRepository = Injekt.get(),
) {

    suspend fun await(): List<Novel> {
        return novelRepository.getReadNovelsNotInLibraryView()
    }
}
