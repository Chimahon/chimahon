package chimahon.novel.interactor

import android.app.Application
import android.content.Context
import chimahon.novel.data.BookStorage
import chimahon.novel.source.LocalNovelFiles
import chimahon.novel.source.NovelLocalSource
import com.hippo.unifile.UniFile
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.library.service.NovelLibraryPreferences
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelChapterUpdate
import tachiyomi.domain.novel.model.NovelUpdate
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * One-shot consolidation (v6): every local novel owns one user-visible
 * folder, `<public>/localnovel/<Title>/`, holding its `.epub`. Matching is
 * by novel name alone; backup/sync wire keys are untouched.
 *
 * Public tree + bundled epub -> pruned to epub-only. Private dir + bundled
 * epub -> moved to the public slot, row re-pointed, source deleted after
 * verification. Extracted tree without epub -> left in place, logged.
 * Row without files -> empty public slot for manual top-up. Chapters are
 * only URL re-prefixed on rename. Marker lands on a fully clean pass.
 */
class ConsolidateNovelHomes(
    private val novelRepository: NovelRepository = Injekt.get(),
    private val novelChapterRepository: NovelChapterRepository = Injekt.get(),
    private val registerLocalHome: RegisterLocalNovelHome = Injekt.get(),
    private val novelLibraryPreferences: NovelLibraryPreferences = Injekt.get(),
    private val app: Application = Injekt.get(),
) {

    suspend fun await(): Boolean {
        val prefs = app.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(MARKER_DONE, false)) return true
        // Sidecars are redundant only after the fold; never prune trees before that.
        if (!novelLibraryPreferences.sidecarsFolded().get()) return false
        val clean = runCatching { consolidate() }.getOrDefault(false)
        if (clean) prefs.edit().putBoolean(MARKER_DONE, true).apply()
        return clean
    }

    private suspend fun consolidate(): Boolean {
        val publicUni = LocalNovelFiles.publicRootUni(app) ?: return false
        val publicFileRoot = LocalNovelFiles.publicRoot(app)
        var failed = false
        // Slots claimed this run: two rows sharing a sanitized title must
        // not overwrite each other's epub; the loser takes a suffix.
        val claimedSlots = mutableSetOf<String>()
        val locals = runCatching {
            novelRepository.getNovelsBySourceId(Novel.LOCAL_SOURCE_ID)
        }.getOrNull().orEmpty()
        for (novel in locals) {
            runCatching { consolidateNovel(novel, publicUni, publicFileRoot, claimedSlots) }
                .onFailure {
                    failed = true
                    logcat(LogPriority.WARN, it) { "Novel consolidation failed for novel ${novel.id}" }
                }
        }
        return !failed
    }

    private fun claimSlot(base: String, claimed: MutableSet<String>): String {
        if (claimed.add(base)) return base
        var n = 1
        while (!claimed.add("$base ($n)")) n++
        return "$base ($n)"
    }

    private suspend fun consolidateNovel(
        novel: Novel,
        publicUni: UniFile,
        publicFileRoot: File?,
        claimedSlots: MutableSet<String>,
    ) {
        val title = novel.title.takeIf { it.isNotBlank() } ?: return
        val slot = claimSlot(BookStorage.sanitizeFileName(title), claimedSlots)
        if (slot.startsWith("src_")) return
        val folder = novel.localFolder?.takeIf { it.isNotBlank() }

        val publicDir = publicUni.findFile(slot)?.takeIf { it.isDirectory }
        val publicHasEpub = publicDir != null && uniDirHasEpub(publicDir)

        if (publicHasEpub) {
            // Home, possibly untidy: prune an extracted tree down to the epub.
            pruneToEpubOnly(publicDir!!, publicFileRoot, slot)
            repointRow(novel, folder, slot)
            refreshCover(novel, slot)
            deletePrivateTree(folder, slot)
            return
        }

        // No usable public home yet: look for a bundled epub in the private dir.
        val privateDir = folder?.let { File(BookStorage.getBooksDirectory(app), it) }
            ?.takeIf { it.isDirectory }
        val bundledEpub = privateDir?.let { findBundledEpub(it) }

        if (bundledEpub != null) {
            val targetUni = publicDir
                ?: publicUni.createDirectory(slot)
                ?: return logSkip(slot, "cannot create public slot")
            if (!copyEpubToUni(bundledEpub, targetUni)) {
                return logSkip(slot, "epub copy failed, source kept")
            }
            repointRow(novel, folder, slot)
            refreshCover(novel, slot)
            deletePrivateTree(folder, slot)
            return
        }

        if (privateDir != null && BookStorage.hasImportedBookContent(privateDir)) {
            // Extracted tree, no epub to promote: leave working as-is.
            logcat(LogPriority.INFO) { "Novel consolidation keeps private tree without epub: $slot" }
            return
        }

        // Nothing anywhere: ensure the top-up slot so the user (or the
        // MISSING card's picker) can drop the file in.
        val slotUni = publicDir
            ?: publicUni.createDirectory(slot)
            ?: return logSkip(slot, "cannot create top-up slot")
        if (!slotUni.isDirectory) return logSkip(slot, "slot is not a directory")
        repointRow(novel, folder, slot)
        if (folder != null && folder != slot) deletePrivateTree(folder, slot)
    }

    private fun uniDirHasEpub(dir: UniFile): Boolean =
        runCatching { dir.listFiles() }.getOrNull()
            ?.any { it.isFile && it.name?.endsWith(".epub", ignoreCase = true) == true } == true

    private fun pruneToEpubOnly(publicDir: UniFile, publicFileRoot: File?, slot: String) {
        // File scheme: plain deletes, keep top-level epubs.
        if (publicFileRoot != null) {
            val fileDir = File(publicFileRoot, slot)
            if (fileDir.isDirectory) {
                fileDir.listFiles()
                    ?.filterNot { it.isFile && it.extension.equals("epub", ignoreCase = true) }
                    ?.forEach { runCatching { if (it.isDirectory) it.deleteRecursively() else it.delete() } }
                return
            }
        }
        runCatching { publicDir.listFiles() }.getOrNull()
            ?.filterNot { it.isFile && it.name?.endsWith(".epub", ignoreCase = true) == true }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun findBundledEpub(privateDir: File): File? =
        privateDir.listFiles()
            ?.firstOrNull { it.isFile && it.extension.equals("epub", ignoreCase = true) }

    private fun copyEpubToUni(src: File, uniDir: UniFile): Boolean = runCatching {
        val name = "${uniDir.name ?: src.nameWithoutExtension}.epub"
        uniDir.findFile(name)?.delete()
        val dest = uniDir.createFile(name) ?: return false
        val out = dest.openOutputStream() ?: return false
        src.inputStream().use { input -> out.use { output -> input.copyTo(output) } }
        dest.length() == src.length() && dest.length() > 0
    }.getOrDefault(false)

    private suspend fun repointRow(novel: Novel, folder: String?, slot: String) {
        val newUrl = NovelLocalSource.novelUrl(slot)
        if (folder != slot || novel.url != newUrl) {
            novelRepository.update(
                NovelUpdate(
                    id = novel.id,
                    url = newUrl.takeIf { it != novel.url },
                    localFolder = slot.takeIf { it != folder },
                ),
            )
        }
        // Chapters may carry any historical prefix (row url or an older
        // folder); normalize every non-conforming one, skip conforming.
        val newPrefix = "$newUrl/"
        val legacyPrefixes = listOfNotNull(
            "${novel.url}/".takeIf { it != newPrefix },
            folder?.let { "${NovelLocalSource.novelUrl(it)}/" }?.takeIf { it != newPrefix },
        )
        if (legacyPrefixes.isEmpty()) return
        novelChapterRepository.getChaptersByNovelId(novel.id)
            .forEach { chapter ->
                if (chapter.url.startsWith(newPrefix)) return@forEach
                val legacy = legacyPrefixes.firstOrNull { chapter.url.startsWith(it) } ?: return@forEach
                runCatching {
                    novelChapterRepository.update(
                        NovelChapterUpdate(
                            id = chapter.id,
                            url = newPrefix + chapter.url.removePrefix(legacy),
                        ),
                    )
                }
            }
    }

    /**
     * Re-derives the cover from the public epub (moved/pruned trees leave
     * dead file:// thumbnails behind, and null means "keep" in updates so it
     * cannot be cleared). Favorite state is preserved.
     */
    private suspend fun refreshCover(novel: Novel, slot: String) {
        val wasFavorite = novel.favorite
        val id = runCatching { registerLocalHome.register(slot) }.getOrNull()
        if (id == null) {
            logcat(LogPriority.WARN) { "Novel consolidation cover refresh failed for novel ${novel.id}" }
            return
        }
        if (!wasFavorite) {
            runCatching { UpdateNovel(novelRepository).awaitUpdateFavorite(novel.id, false) }
        }
    }

    private fun deletePrivateTree(folder: String?, slot: String) {
        // Only delete a dir this row demonstrably owned (its recorded
        // folder). Same-name leftovers without linkage are left alone.
        if (folder == null) return
        if (folder == slot) {
            val priv = File(BookStorage.getBooksDirectory(app), slot)
            if (priv.isDirectory) runCatching { priv.deleteRecursively() }
            return
        }
        val priv = File(BookStorage.getBooksDirectory(app), folder)
        if (priv.isDirectory) runCatching { priv.deleteRecursively() }
    }

    private fun logSkip(slot: String, reason: String) {
        logcat(LogPriority.WARN) { "Novel consolidation skipped $slot: $reason" }
    }

    companion object {
        private const val MIGRATION_PREFS = "novel_sync_migration"
        private const val MARKER_DONE = "novel_migration_v6_public_consolidation_done"
    }
}
