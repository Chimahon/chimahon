package chimahon.novel.download

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.util.storage.DiskUtil
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelChapter
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException

data class StoredDownload(
    val content: String,
    val isHtml: Boolean,
)

class NovelDownloadProvider(
    private val context: Context,
    private val storageManager: StorageManager = Injekt.get(),
) {
    private val downloadsDir: UniFile?
        get() = storageManager.getDownloadsDirectory()?.createDirectory("novels")
            ?: UniFile.fromFile(File(context.filesDir, "novel_downloads").apply { mkdirs() })

    internal fun getNovelDir(novelTitle: String, source: NovelSource): UniFile {
        val downloadsDir = downloadsDir
        if (downloadsDir == null) {
            logcat(LogPriority.ERROR) { "Failed to create novel download directory" }
            throw IOException(context.stringResource(MR.strings.storage_failed_to_create_download_directory))
        }

        val sourceDirName = getSourceDirName(source)
        val sourceDir = downloadsDir.createDirectory(sourceDirName)
            ?: throw IOException("Failed to create source download directory")

        val novelDirName = getNovelDirName(novelTitle)
        return sourceDir.createDirectory(novelDirName)
            ?: throw IOException("Failed to create novel download directory")
    }

    fun findSourceDir(source: NovelSource): UniFile? {
        return downloadsDir?.findFile(getSourceDirName(source))
    }

    fun findNovelDir(novelTitle: String, source: NovelSource): UniFile? {
        val sourceDir = findSourceDir(source)
        return sourceDir?.findFile(getNovelDirName(novelTitle))
    }

    fun findChapterDir(chapterName: String, novelTitle: String, source: NovelSource): UniFile? {
        val novelDir = findNovelDir(novelTitle, source)
        return getValidChapterDirNames(chapterName).asSequence()
            .mapNotNull { novelDir?.findFile(it) }
            .firstOrNull()
    }

    fun getChapterDir(chapterName: String, novelTitle: String, source: NovelSource): UniFile {
        val novelDir = getNovelDir(novelTitle, source)
        val chapterDirName = getChapterDirName(chapterName)
        return novelDir.createDirectory(chapterDirName)
            ?: throw IOException("Failed to create chapter download directory")
    }

    /**
     * Single atomic writer for chapter downloads (manga tmp+rename parity).
     * Both download paths funnel here, so a crash never leaves torn content
     * and the type sidecar always matches what [readDownloadedContent] sniffs.
     * False instead of throwing; callers map to their own error states.
     */
    fun writeDownloadContent(
        chapterDir: UniFile,
        fileName: String,
        rawContent: String,
        isText: Boolean,
    ): Boolean {
        return try {
            chapterDir.findFile("$fileName.tmp")?.delete()
            val tmp = chapterDir.createFile("$fileName.tmp") ?: return false
            (tmp.openOutputStream() ?: return false).use { out ->
                out.write(rawContent.toByteArray(Charsets.UTF_8))
            }
            chapterDir.findFile(fileName)?.delete()
            if (!tmp.renameTo(fileName)) return false
            try {
                chapterDir.findFile(CONTENT_TYPE_FILE)?.delete()
                chapterDir.createFile(CONTENT_TYPE_FILE)?.openOutputStream()?.use { out ->
                    out.write((if (isText) "text" else "html").toByteArray(Charsets.UTF_8))
                }
            } catch (_: Exception) {}
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reads a previously downloaded chapter back (content + whether it is HTML).
     * Sidecar `content.type` written by the downloader decides; legacy downloads
     * without it fall back to extension (.txt = text) and `<` sniffing (.html).
     * Null when nothing usable is stored.
     */
    fun readDownloadedContent(
        chapterName: String,
        novelTitle: String,
        source: NovelSource,
    ): StoredDownload? {
        return try {
            val chapterDir = findChapterDir(chapterName, novelTitle, source) ?: return null
            val files = try {
                chapterDir.listFiles()?.toList().orEmpty()
            } catch (_: Exception) {
                return null
            }
            val htmlFile = files.firstOrNull { it.name == "content.html" }
            val txtFile = files.firstOrNull { it.name == "content.txt" }
            val contentFile = htmlFile ?: txtFile ?: return null
            val raw = try {
                contentFile.openInputStream()?.bufferedReader()?.readText()
            } catch (_: Exception) {
                null
            }?.takeIf { it.isNotBlank() } ?: return null
            val type = try {
                files.firstOrNull { it.name == CONTENT_TYPE_FILE }
                    ?.openInputStream()?.bufferedReader()?.readText()?.trim()?.lowercase()
            } catch (_: Exception) {
                null
            }
            val isHtml = when (type) {
                "text" -> false
                "html" -> true
                else -> htmlFile != null && (txtFile == null || raw.contains('<'))
            }
            StoredDownload(raw, isHtml)
        } catch (_: Exception) {
            null
        }
    }

    private fun getSourceDirName(source: NovelSource): String {
        return DiskUtil.buildValidFilename(source.toString())
    }

    private fun getNovelDirName(novelTitle: String): String {
        return DiskUtil.buildValidFilename(novelTitle)
    }

    private fun getChapterDirName(chapterName: String): String {
        return DiskUtil.buildValidFilename(chapterName)
    }

    private fun getValidChapterDirNames(chapterName: String): List<String> {
        return listOf(
            getChapterDirName(chapterName),
            DiskUtil.buildValidFilename(chapterName),
        ).distinct()
    }

    companion object {
        const val TMP_DIR_SUFFIX = "_tmp"
        const val CONTENT_TYPE_FILE = "content.type"
    }
}
