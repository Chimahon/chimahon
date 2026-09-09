package chimahon.novel.interactor

import android.app.Application
import android.content.Context
import chimahon.novel.source.LocalNovelFiles
import chimahon.novel.source.NovelLocalSource
import chimahon.novel.data.BookMetadata
import chimahon.novel.data.BookStorage
import chimahon.novel.data.NovelCategory
import chimahon.novel.data.NovelCategoryStorage
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import tachiyomi.domain.library.service.NovelLibraryPreferences
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelCategory.Companion.SYSTEM_CATEGORY_ID
import tachiyomi.domain.novel.model.NovelChapter
import tachiyomi.domain.novel.model.NovelChapterUpdate
import tachiyomi.domain.novel.model.NovelUpdate
import tachiyomi.domain.novel.model.isNovelReadComplete
import tachiyomi.domain.novel.repository.NovelCategoryRepository
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelHistoryRepository
import tachiyomi.domain.novel.repository.NovelReadingStatsRepository
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * One-shot migration of chimareader's JSON files into the database.
 *
 * - Copies only; never deletes or rewrites any JSON file.
 * - Idempotent: guarded by a preference marker.
 * - Categories: JSON string ids ("default" + uuids) map to integer ids assigned
 *   in insertion order; memberships on books are translated through that map.
 * - Imported books become real rows (source = LOCAL_SOURCE_ID, is_local = 1,
 *   local_folder = book dir) with a single synthetic chapter carrying progress.
 * - Extension-built books get their local_folder link backfilled.
 * - statistics.json daily entries are copied into novel_reading_stats.
 */
class MigrateNovelJsonData(
    private val novelRepository: NovelRepository = Injekt.get(),
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
    private val novelHistoryRepository: NovelHistoryRepository = Injekt.get(),
    private val novelCategoryRepository: NovelCategoryRepository = Injekt.get(),
    private val novelReadingStatsRepository: NovelReadingStatsRepository = Injekt.get(),
    private val novelLibraryPreferences: NovelLibraryPreferences = Injekt.get(),
    private val categoryStorage: NovelCategoryStorage = Injekt.get(),
    private val syncNovelChapters: SyncNovelChapters = Injekt.get(),
    private val registerLocalHome: RegisterLocalNovelHome = Injekt.get(),
    private val app: Application = Injekt.get(),
) {

    private val categoryIdMap = HashMap<String, Long>()

    suspend fun await() {
        val marker = novelLibraryPreferences.jsonMigrated()
        if (!marker.get()) {
            migrateCategories()
            migrateBooks()

            marker.set(true)
        }
        migrateLocalHome()
        foldSidecarsToDb()
    }

    /**
     * Final fold (one-shot, idempotent, retry-safe): copy any remaining
     * sidecar state into the DB, then the JSON layer can die. Rows are live
     * truth — sidecars only fill gaps (missing history/dateKeys/fields).
     * Files themselves are left alone (deleted in a later release).
     */
    private suspend fun foldSidecarsToDb() {
        val marker = novelLibraryPreferences.sidecarsFolded()
        if (marker.get()) return
        var failed = false
        try {
            val roots = listOfNotNull(
                runCatching { LocalNovelFiles.publicRoot(app) }.getOrNull(),
                BookStorage.getBooksDirectory(app),
            ).distinctBy { it.absolutePath }
            for (root in roots) {
                root.listFiles()
                    ?.filter { it.isDirectory }
                    ?.filterNot { it.name.endsWith(".tmp") || it.name.endsWith(".bak") }
                    .orEmpty()
                    .forEach { dir ->
                        runCatching { foldBookDir(dir) }.onFailure { failed = true }
                    }
            }
        } catch (_: Exception) {
            failed = true
        }
        if (!failed) marker.set(true)
    }

    private suspend fun foldBookDir(dir: File) {
        val metadata = BookStorage.loadMetadata(dir)
        val bookmark = BookStorage.loadBookmark(dir)
        val stats = BookStorage.loadStatistics(dir)
        if (metadata == null && bookmark == null && stats.isNullOrEmpty()) return

        // Resolve row: sidecar link, then folder, then register local content.
        var novel = metadata?.let { meta ->
            val sourceId = meta.novelSourceId
            val novelUrl = meta.novelUrl
            if (sourceId != null && novelUrl != null) {
                runCatching { novelRepository.getNovelByUrlAndSourceId(novelUrl, sourceId) }.getOrNull()
            } else {
                runCatching { novelRepository.getNovelByLocalFolder(dir.name) }.getOrNull()
            }
        } ?: runCatching { novelRepository.getNovelByLocalFolder(dir.name) }.getOrNull()
        if (novel == null && BookStorage.hasImportedBookContent(dir) && !dir.name.startsWith("src_")) {
            val id = registerLocalHome.register(dir.name)
            novel = id?.let { runCatching { novelRepository.getNovelById(it) }.getOrNull() }
        }
        val row = novel ?: return
        val novelId = row.id

        // Metadata: sidecar wins only where rows never learned the value.
        if (metadata != null) {
            val title = metadata.title?.takeIf { it.isNotBlank() }
            val author = metadata.author?.takeIf { it.isNotBlank() }
            val lang = metadata.lang?.takeIf { it.isNotBlank() }
            val useTitle = row.isLocal && title != null && title != row.title
            val useAuthor = row.isLocal && author != null && author != row.author
            val useLang = lang != null && lang != row.lang
            if (useTitle || useAuthor || useLang) {
                novelRepository.update(
                    NovelUpdate(
                        id = novelId,
                        title = title?.takeIf { useTitle },
                        author = author?.takeIf { useAuthor },
                        lang = lang?.takeIf { useLang },
                    ),
                )
            }
            val categoryIds = metadata.categoryIds
                .filter { it.isNotBlank() && it != NovelCategory.UNCATEGORIZED_ID }
                .mapNotNull { it.toLongOrNull() }
            if (categoryIds.isNotEmpty()) {
                val current = novelCategoryRepository.getByNovelId(novelId).map { it.id }.toSet()
                val merged = (current + categoryIds).toList()
                if (merged.size != current.size) {
                    novelCategoryRepository.setNovelCategories(novelId, merged)
                }
            }
        }

        // Bookmark: newer-wins against the chapter's history row.
        if (bookmark != null) {
            val chapters = novelChapterRepository.getChaptersByNovelId(novelId)
                .sortedBy { it.chapterNumber }
            val target = chapters.getOrNull(bookmark.chapterIndex)
            if (target != null) {
                val history = novelHistoryRepository.getHistoryByChapterId(target.id)
                val bookmarkTime = bookmark.lastModified ?: 0L
                if (history == null || bookmarkTime > history.lastRead) {
                    val completed = (bookmark.progress).isNovelReadComplete()
                    novelChapterRepository.update(
                        NovelChapterUpdate(
                            id = target.id,
                            read = target.read || completed,
                            lastPageRead = maxOf(target.lastPageRead, bookmark.characterCount.toLong().coerceAtLeast(0)),
                            progress = bookmark.progress.coerceIn(0.0, 1.0),
                        ),
                    )
                    novelHistoryRepository.upsertHistory(
                        chapterId = target.id,
                        lastRead = maxOf(bookmarkTime, System.currentTimeMillis()),
                        timeRead = 0L,
                    )
                }
            }
        }

        // Statistics: backfill dateKeys the DB lacks; live rows always win.
        if (!stats.isNullOrEmpty()) {
            val present = novelReadingStatsRepository.getByNovelId(novelId)
                .map { it.dateKey }.toSet()
            stats.filter { it.dateKey !in present }.forEach { entry ->
                runCatching {
                    novelReadingStatsRepository.upsert(
                        novelId = novelId,
                        dateKey = entry.dateKey,
                        charactersRead = entry.charactersRead,
                        readingTime = entry.readingTime,
                        minReadingSpeed = entry.minReadingSpeed,
                        altMinReadingSpeed = entry.altMinReadingSpeed,
                        lastReadingSpeed = entry.lastReadingSpeed,
                        maxReadingSpeed = entry.maxReadingSpeed,
                        completedBook = entry.completedBook,
                    )
                }
            }
        }
    }

    /**
     * One versioned step (v5): real spine chapters, public folders, swept
     * legacy files. The workers are idempotent, so a failed run simply retries
     * everything next boot; the flag lands only on a fully clean pass.
     */
    private suspend fun migrateLocalHome() {
        val prefs = app.getSharedPreferences("novel_sync_migration", Context.MODE_PRIVATE)
        if (prefs.getBoolean("novel_migration_v5_local_home_done", false)) return
        val spinesClean = migrateLocalSpines()
        val movedClean = moveLocalBooksPublic()
        val sweptClean = sweepLegacyPackageFiles()
        if (spinesClean && movedClean && sweptClean) {
            prefs.edit().putBoolean("novel_migration_v5_local_home_done", true).apply()
        }
    }

    private suspend fun migrateCategories() {
        var nextId = 1L
        for (category in categoryStorage.loadAllCategories()) {
            if (category.isSystemCategory) continue
            val dbId = nextId++
            novelCategoryRepository.insertRawCategory(
                id = dbId,
                name = category.name,
                order = category.order,
                flags = category.flags,
                hidden = category.hidden,
            )
            categoryIdMap[category.id] = dbId
        }
    }

    private suspend fun migrateBooks() {
        for (metadata in BookStorage.loadAllBooks(app)) {
            val bookDir = BookStorage.getBookDirectory(app, metadata.id)
            if (!bookDir.exists()) continue

            val novelId = ensureNovelRow(metadata, metadata.id)
            migrateBookmark(metadata.id, metadata.title, novelId)
            migrateCategoriesForBook(metadata, novelId)
            migrateStatistics(metadata.id, novelId)
        }
    }

    private suspend fun ensureNovelRow(metadata: BookMetadata, folderName: String): Long {
        val sourceId = metadata.novelSourceId
        val novelUrl = metadata.novelUrl
        val existing = if (sourceId != null && novelUrl != null) {
            novelRepository.getNovelByUrlAndSourceId(novelUrl, sourceId)
        } else {
            novelRepository.getNovelByUrlAndSourceId(NovelLocalSource.novelUrl(folderName), Novel.LOCAL_SOURCE_ID)
        }
        if (existing == null) {
            return novelRepository.insert(
                Novel.create().copy(
                    source = sourceId ?: Novel.LOCAL_SOURCE_ID,
                    url = novelUrl ?: NovelLocalSource.novelUrl(folderName),
                    title = metadata.title ?: "Unknown",
                    author = metadata.author,
                    favorite = true,
                    dateAdded = metadata.dateAdded,
                    thumbnailUrl = metadata.cover,
                    initialized = true,
                    notes = "",
                    isLocal = sourceId == null,
                    localFolder = folderName,
                ),
            )
        }
        if (existing.localFolder != folderName) {
            novelRepository.update(NovelUpdate(id = existing.id, localFolder = folderName))
        }
        return existing.id
    }

    private suspend fun migrateBookmark(bookId: String, title: String?, novelId: Long) {
        val bookmark = BookStorage.loadBookmark(BookStorage.getBookDirectory(app, bookId))
        val chapters = novelChapterRepository.getChaptersByNovelId(novelId)
        val chapter = chapters.firstOrNull() ?: run {
            novelChapterRepository.insertAll(
                listOf(
                    NovelChapter.create().copy(
                        novelId = novelId,
                        url = SYNTHETIC_CHAPTER_URL,
                        name = title ?: "Book",
                        sourceOrder = 0L,
                        chapterNumber = 1f,
                        dateFetch = System.currentTimeMillis(),
                        dateUpload = 0L,
                        read = (bookmark?.progress ?: 0.0).isNovelReadComplete(),
                        lastPageRead = bookmark?.characterCount?.toLong()?.coerceAtLeast(0) ?: 0L,
                    ),
                ),
            )
            novelChapterRepository.getChaptersByNovelId(novelId).first()
        }

        if (bookmark != null && chapter != null) {
            novelHistoryRepository.upsertHistory(
                chapterId = chapter.id,
                lastRead = bookmark.lastModified ?: System.currentTimeMillis(),
                timeRead = 0L,
            )
        }
    }

    private suspend fun migrateCategoriesForBook(metadata: BookMetadata, novelId: Long) {
        val dbIds = metadata.categoryIds.mapNotNull { jsonId ->
            if (jsonId == NovelCategory.UNCATEGORIZED_ID) null else categoryIdMap[jsonId]
        }
        if (dbIds.isNotEmpty()) {
            novelCategoryRepository.setNovelCategories(novelId, dbIds)
        }
    }

    private suspend fun migrateStatistics(bookId: String, novelId: Long) {
        BookStorage.loadStatistics(BookStorage.getBookDirectory(app, bookId))?.forEach { entry ->
            novelReadingStatsRepository.upsert(
                novelId = novelId,
                dateKey = entry.dateKey,
                charactersRead = entry.charactersRead,
                readingTime = entry.readingTime,
                minReadingSpeed = entry.minReadingSpeed,
                altMinReadingSpeed = entry.altMinReadingSpeed,
                lastReadingSpeed = entry.lastReadingSpeed,
                maxReadingSpeed = entry.maxReadingSpeed,
                completedBook = entry.completedBook,
            )
        }
    }

    /**
     * Removes what Phase 2c deleted from the builder — package files, chapter
     * files (shells and stale copies; the loader serves downloads/cache/network),
     * manifests, stale temp dirs — from `src_*` cache books, plus crash leftovers
     * (`.tmp` always; `.bak` only when superseded, restored otherwise).
     * Metadata, bookmarks, statistics, sidecars and mirrored images are untouched.
     */
    private suspend fun sweepLegacyPackageFiles(): Boolean {
        var failed = false
        try {
            val roots = listOfNotNull(
                BookStorage.getBooksDirectory(app).takeIf { it.isDirectory },
                BookStorage.localBooksRoot?.takeIf { it.isDirectory },
            ).distinct()
            roots.flatMap { it.listFiles()?.toList().orEmpty() }.forEach { entry ->
                runCatching {
                    when {
                        entry.isDirectory && entry.name.endsWith(".tmp") -> entry.deleteRecursively()
                        entry.isDirectory && entry.name.endsWith(".bak") -> {
                            val live = File(entry.parentFile, entry.name.removeSuffix(".bak"))
                            if (live.isDirectory) entry.deleteRecursively() else entry.renameTo(live)
                        }
                        entry.isDirectory && entry.name.startsWith("src_") -> sweepBookDir(entry)
                        entry.isDirectory -> sweepOrphanDir(entry)
                    }
                }.onFailure {
                    failed = true
                    logcat(LogPriority.WARN, it) { "Novel migration sweep failed for ${entry.name}" }
                }
            }
        } catch (_: Exception) {
            failed = true
        }
        return !failed
    }

    /**
     * True orphans: no EPUB content and no novel row referencing the folder.
     * Dirs with rows stay as empty entries the user can refill (no ghost flags).
     */
    private suspend fun sweepOrphanDir(dir: File) {
        if (BookStorage.hasImportedBookContent(dir)) return
        val row = runCatching {
            novelRepository.getNovelByUrlAndSourceId(
                NovelLocalSource.novelUrl(dir.name),
                Novel.LOCAL_SOURCE_ID,
            )
        }.getOrNull()
        if (row == null) {
            dir.deleteRecursively()
        }
    }

    private suspend fun sweepBookDir(dir: File) {
        foldStaleBookmark(dir)
        File(dir, "source_chapter_cache.json").takeIf { it.isFile }?.delete()
        File(dir, "mimetype").takeIf { it.isFile }?.delete()
        File(dir, "META-INF").takeIf { it.isDirectory }?.deleteRecursively()
        val contentDir = File(dir, "OEBPS")
        File(contentDir, "content.opf").takeIf { it.isFile }?.delete()
        File(contentDir, "nav.xhtml").takeIf { it.isFile }?.delete()
        contentDir.listFiles()
            ?.filter { it.isFile && CHAPTER_SHELL_REGEX.matches(it.name) }
            .orEmpty()
            .forEach { runCatching { it.delete() } }
    }

    /**
     * Legacy `src_-1501_*` skeleton caches for local books (pre-direct-open):
     * fold any stranded sidecar bookmark into history, resolved against the
     * real home rows. Never throws; ordering vs v5/v6 does not matter.
     */
    private suspend fun foldStaleBookmark(dir: File) {
        val metadata = BookStorage.loadMetadata(dir) ?: return
        if (metadata.novelSourceId != Novel.LOCAL_SOURCE_ID) return
        val bookmark = BookStorage.loadBookmark(dir) ?: return
        val novelUrl = metadata.novelUrl ?: return
        val novel = runCatching {
            novelRepository.getNovelByUrlAndSourceId(novelUrl, Novel.LOCAL_SOURCE_ID)
        }.getOrNull() ?: return
        val chapters = runCatching {
            novelChapterRepository.getChaptersByNovelId(novel.id).sortedBy { it.chapterNumber }
        }.getOrDefault(emptyList())
        val target = chapters.getOrNull(bookmark.chapterIndex) ?: return
        runCatching {
            val current = novelHistoryRepository.getHistoryByChapterId(target.id)
            if (current == null || bookmark.lastModified ?: 0L > current.lastRead) {
                novelHistoryRepository.upsertHistory(
                    chapterId = target.id,
                    lastRead = bookmark.lastModified ?: System.currentTimeMillis(),
                    timeRead = 0L,
                )
            }
        }
    }

    /**
     * Moves imported books from the private dir to the public local-novels
     * folder (`<base>/localnovel/<Title>/`, manga `<base>/local/` mirror) and
     * re-keys identity folder-wide (dir, metadata, novel row, chapter urls).
     * History/bookmarks survive (chapter ids and indices are untouched).
     * False while the public root is unresolvable so the step retries later.
     */
    private suspend fun moveLocalBooksPublic(): Boolean {
        val publicRoot = LocalNovelFiles.publicRoot(app) ?: return false
        var failed = false
        try {
            val privateRoot = BookStorage.getBooksDirectory(app)
            privateRoot.listFiles()
                ?.filter { it.isDirectory }
                .orEmpty()
                .filterNot { it.name.startsWith("src_") || it.name.endsWith(".bak") || it.name.endsWith(".tmp") }
                .forEach { dir ->
                    runCatching { moveLocalBookPublic(dir, publicRoot) }
                        .onFailure {
                            failed = true
                            logcat(LogPriority.WARN, it) { "Novel migration move failed for ${dir.name}" }
                        }
                }
        } catch (_: Exception) {
            failed = true
        }
        return !failed
    }

    private suspend fun moveLocalBookPublic(dir: File, publicRoot: File) {
        val metadata = BookStorage.loadMetadata(dir) ?: return
        if (metadata.novelSourceId != null) return
        if (!BookStorage.hasImportedBookContent(dir)) return
        val title = metadata.title?.takeIf { it.isNotBlank() } ?: return
        val newFolder = BookStorage.uniqueTitleDir(publicRoot, title, metadata.author.orEmpty()).name
        if (newFolder == dir.name && dir.parentFile?.canonicalPath == publicRoot.canonicalPath) return
        val target = File(publicRoot, newFolder)
        dir.copyRecursively(target, overwrite = false)
        if (BookStorage.loadMetadata(target) == null) return
        // Paranoia before deleting the source: every file must have arrived.
        val sourceCount = dir.walkTopDown().count { it.isFile }
        val targetCount = target.walkTopDown().count { it.isFile }
        if (targetCount < sourceCount) return

        val moved = BookStorage.loadMetadata(target) ?: return
        // Absolute cover paths break on move; rebase them to the new dir.
        val oldDirPrefix = dir.absolutePath + File.separator
        val newDirPrefix = target.absolutePath + File.separator
        val fixed = moved.copy(
            id = newFolder,
            folder = newFolder,
            hash = newFolder,
            cover = moved.cover?.takeIf { it.startsWith(oldDirPrefix) }
                ?.let { newDirPrefix + it.removePrefix(oldDirPrefix) }
                ?: moved.cover,
        )
        BookStorage.saveMetadata(fixed, target)

        val oldUrl = NovelLocalSource.novelUrl(dir.name)
        val newUrl = NovelLocalSource.novelUrl(newFolder)
        val novel = novelRepository.getNovelByUrlAndSourceId(oldUrl, Novel.LOCAL_SOURCE_ID)
        if (novel == null) {
            registerLocalHome.register(newFolder)
            dir.deleteRecursively()
            return
        }
        novelRepository.update(
            NovelUpdate(
                id = novel.id,
                url = newUrl,
                title = fixed.title,
                author = fixed.author,
                thumbnailUrl = fixed.cover,
                localFolder = newFolder,
            ),
        )
        val oldPrefix = "$oldUrl/"
        val newPrefix = "$newUrl/"
        val chapters = novelChapterRepository.getChaptersByNovelId(novel.id)
        chapters
            .filter { it.url.startsWith(oldPrefix) }
            .forEach { chapter ->
                runCatching {
                    novelChapterRepository.update(
                        NovelChapterUpdate(
                            id = chapter.id,
                            url = newPrefix + chapter.url.removePrefix(oldPrefix),
                        ),
                    )
                }
            }
        novelRepository.update(NovelUpdate(id = novel.id, totalChapters = chapters.size))
        // Source deleted last: a crash before this line retries into the merge
        // path above (same author reuses the folder) instead of stranding data.
        dir.deleteRecursively()
    }

    /**
     * Replaces the synthetic single chapter on local novels with real spine
     * chapters, moving read state + history onto the bookmark's indexed chapter.
     * Idempotent: re-runs find no synthetic left.
     */
    private suspend fun migrateLocalSpines(): Boolean {
        var failed = false
        try {
            val source = NovelLocalSource(app)
            val locals = novelRepository.getNovelsBySourceId(Novel.LOCAL_SOURCE_ID)
            for (novel in locals) {
                runCatching { upgradeLocalSpine(source, novel) }
                    .onFailure {
                        failed = true
                        logcat(LogPriority.WARN, it) { "Novel migration spine upgrade failed for novel ${novel.id}" }
                    }
            }
        } catch (_: Exception) {
            failed = true
        }
        return !failed
    }

    private suspend fun upgradeLocalSpine(source: NovelLocalSource, novel: Novel) {
        val stableId = novel.localFolder
            ?.takeIf { it.isNotBlank() }
            ?: novel.url.removePrefix(NovelLocalSource.LOCAL_URL_PREFIX).takeIf { it.isNotBlank() }
            ?: return
        val spine = try {
            source.getChapterList(SNNovel(url = NovelLocalSource.novelUrl(stableId)))
        } catch (_: Exception) {
            return
        }
        if (spine.isEmpty()) return
        val merged = syncNovelChapters.await(novel.id, spine).chapters
        val synthetic = merged.firstOrNull { it.url == SYNTHETIC_CHAPTER_URL }
            ?: run {
                novelRepository.update(NovelUpdate(id = novel.id, totalChapters = merged.size))
                return
            }
        val ordered = merged.filterNot { it.id == synthetic.id }.sortedBy { it.chapterNumber }
        if (ordered.isEmpty()) return
        val bookmark = BookStorage.loadBookmark(BookStorage.getBookDirectory(app, stableId))
        val target = ordered.getOrNull(bookmark?.chapterIndex ?: 0) ?: ordered.first()
        val completed = (bookmark?.progress ?: 0.0).isNovelReadComplete() || synthetic.read
        novelChapterRepository.update(
            NovelChapterUpdate(
                id = target.id,
                read = completed || target.read,
                lastPageRead = maxOf(
                    target.lastPageRead,
                    synthetic.lastPageRead,
                    bookmark?.characterCount?.toLong()?.coerceAtLeast(0) ?: 0L,
                ),
            ),
        )
        val history = novelHistoryRepository.getHistoryByChapterId(synthetic.id)
        if (history != null) {
            novelHistoryRepository.upsertHistory(
                chapterId = target.id,
                lastRead = history.lastRead,
                timeRead = history.timeRead,
            )
            novelHistoryRepository.resetHistoryByChapterIds(listOf(synthetic.id))
        } else if (bookmark != null) {
            novelHistoryRepository.upsertHistory(
                chapterId = target.id,
                lastRead = bookmark.lastModified ?: System.currentTimeMillis(),
                timeRead = 0L,
            )
        }
        novelChapterRepository.deleteChapterById(synthetic.id)
        novelRepository.update(NovelUpdate(id = novel.id, totalChapters = ordered.size))
    }

    private companion object {
        const val SYNTHETIC_CHAPTER_URL = "chimahon-novel://local/book"
        val CHAPTER_SHELL_REGEX = Regex("""chapter_\d+\.xhtml""")
    }
}
