package eu.kanade.tachiyomi.ui.history.novel

import android.app.Application
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import chimahon.novel.manager.NovelSourceManager
import chimahon.novel.ui.detail.SourceChapterBookBuilder
import chimahon.novel.data.BookStorage
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelHistoryEntry
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelHistoryRepository
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Reading history for novels, manga HistoryScreenModel parity (minus
 * selection/filters/migrate — v1 covers list, search, resume, delete).
 * Source and local novels share the same rows, so both show up uniformly.
 */
class NovelHistoryScreenModel(
    private val novelRepository: NovelRepository = Injekt.get(),
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
    private val novelHistoryRepository: NovelHistoryRepository = Injekt.get(),
    private val sourceManager: NovelSourceManager = Injekt.get(),
    private val app: Application = Injekt.get(),
) : StateScreenModel<NovelHistoryScreenModel.State>(State()) {

    init {
        // Manga parity: subscribe so reads land live instead of only on
        // init/restart. Search re-queries via flatMapLatest.
        screenModelScope.launchIO {
            state.map { it.searchQuery }
                .distinctUntilChanged()
                .flatMapLatest { query ->
                    novelHistoryRepository.subscribeToHistoryWithRelations(query.orEmpty())
                }
                .distinctUntilChanged()
                .catch { e -> logcat(LogPriority.ERROR, e) }
                .collect { rows ->
                    mutableState.update {
                        it.copy(list = buildEntries(rows), isLoading = false)
                    }
                }
        }
    }

    fun reload() {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isLoading = true) }
            // One entry per novel (latest read first — the query already
            // orders by last_read DESC, so the first row per novel wins).
            val rows = novelHistoryRepository
                .getHistoryWithRelations(mutableState.value.searchQuery.orEmpty())
            mutableState.update {
                it.copy(list = buildEntries(rows), isLoading = false)
            }
        }
    }

    private suspend fun buildEntries(
        rows: List<tachiyomi.domain.novel.model.NovelHistoryWithRelations>,
    ): ImmutableList<NovelHistoryEntry> {
        return rows.groupBy { it.novelId }.mapNotNull { (novelId, group) ->
            val latest = group.firstOrNull() ?: return@mapNotNull null
            val chapters = runCatching {
                novelChapterRepository.getChaptersByNovelId(novelId)
            }.getOrNull().orEmpty()
            NovelHistoryEntry(
                novelId = novelId,
                title = latest.novelTitle,
                thumbnailUrl = latest.novelThumbnailUrl,
                source = latest.novelSource,
                favorite = latest.novelFavorite,
                latest = latest,
                readCount = chapters.count { it.read },
                totalCount = chapters.size,
                latestIndex = chapters.sortedBy { it.chapterNumber }
                    .indexOfFirst { it.id == latest.chapterId },
            )
        }.toImmutableList()
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
        reload()
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    suspend fun getNovel(novelId: Long): Novel? {
        return withIOContext { novelRepository.getNovelById(novelId) }
    }

    /**
     * Manga history-tap parity: resume the latest-read chapter (with its
     * stored position), never null just because everything is read. Local
     * novels open their folder directly (main parity, no skeleton).
     */
    data class ResumeTarget(val dir: File, val novelId: Long, val chapterIndex: Int)

    suspend fun buildResumeBook(entry: NovelHistoryEntry): ResumeTarget? = withIOContext {
        val novel = novelRepository.getNovelById(entry.novelId) ?: return@withIOContext null
        val chapters = novelChapterRepository.getChaptersByNovelId(novel.id)
            .sortedBy { it.chapterNumber }
        if (chapters.isEmpty()) return@withIOContext null
        // Latest-read chapter; falls back to the first when the row is gone
        // (pruned chapters). The reader picks up the stored progress itself.
        val targetIndex = chapters.indexOfFirst { it.id == entry.latest.chapterId }
            .takeIf { it >= 0 } ?: 0
        if (novel.source == Novel.LOCAL_SOURCE_ID) {
            val folder = novel.localFolder?.takeIf { it.isNotBlank() } ?: return@withIOContext null
            val dir = chimahon.novel.source.LocalNovelFiles.ensureReadableDir(app, folder)
                ?.takeIf { BookStorage.hasImportedBookContent(it) }
                ?: return@withIOContext null
            return@withIOContext ResumeTarget(dir, novel.id, targetIndex)
        }
        val source = sourceManager.getNovelSource(novel.source) ?: return@withIOContext null
        val snNovel = SNNovel(url = novel.url, title = novel.title, author = novel.author)
        val dir = SourceChapterBookBuilder.ensureBookDir(
            app,
            SourceChapterBookBuilder.bookId(source, snNovel),
        )
        ResumeTarget(dir, novel.id, targetIndex)
    }

    fun removeFromHistory(entry: NovelHistoryEntry) {
        screenModelScope.launchIO {
            // Reset last_read on every chapter of the entry (rows stay, the
            // list filters them out). Takes the chapter ids, not the history
            // row id.
            val chapterIds = runCatching {
                novelChapterRepository.getChaptersByNovelId(entry.novelId).map { it.id }
            }.getOrNull().orEmpty()
            if (chapterIds.isNotEmpty()) {
                novelHistoryRepository.resetHistoryByChapterIds(chapterIds)
            }
            reload()
        }
    }

    fun removeAllHistory() {
        screenModelScope.launchIO {
            novelHistoryRepository.deleteAllHistory()
            reload()
        }
    }

    @Immutable
    data class State(
        val searchQuery: String? = null,
        val list: ImmutableList<NovelHistoryEntry> = persistentListOf(),
        val isLoading: Boolean = true,
        val dialog: Dialog? = null,
    )

    sealed interface Dialog {
        data class Delete(val entry: NovelHistoryEntry) : Dialog
        data object DeleteAll : Dialog
    }
}
