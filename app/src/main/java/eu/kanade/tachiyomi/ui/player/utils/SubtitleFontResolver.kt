package eu.kanade.tachiyomi.ui.player.utils

import android.content.Context
import android.net.Uri
import chimahon.novel.data.FontManager
import com.yubyf.truetypeparser.TTFFile
import eu.kanade.tachiyomi.ui.player.getFileName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resolves font files for the subtitle font picker from the app and system font directories.
 */
object SubtitleFontResolver {

    private val systemFontDirs = listOf("/system/fonts/", "/product/fonts/")

    @Volatile
    private var cachedFamilies: Triple<Boolean, Long, Map<String, File>>? = null

    suspend fun importFont(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val name = uri.getFileName(context) ?: return@withContext false
            if (!name.lowercase().matches(FONT_EXTENSION_REGEX)) return@withContext false
            val valid = context.contentResolver.openInputStream(uri)?.use {
                TTFFile.open(it).families.isNotEmpty()
            } == true
            if (!valid) return@withContext false
            FontManager.importFont(context, uri).also { if (it) invalidate() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Family name to font file, user fonts first so they win on duplicates.
     */
    suspend fun fontFamilies(context: Context, includeSystemFonts: Boolean): Map<String, File> =
        withContext(Dispatchers.IO) {
            val userFonts = FontManager.getFontsDir(context)
            val modified = userFonts.lastModified()
            cachedFamilies?.takeIf { it.first == includeSystemFonts && it.second == modified }
                ?.let { return@withContext it.third }
            val dirs = listOf(userFonts) +
                systemFontDirs.map(::File).takeIf { includeSystemFonts }.orEmpty()
            val families = dirs
                .filter { it.isDirectory }
                .flatMap { dir ->
                    dir.listFiles()?.filter { file ->
                        file.isFile && file.name.lowercase().matches(FONT_EXTENSION_REGEX)
                    }.orEmpty()
                }
                .mapNotNull { file ->
                    runCatching {
                        file.inputStream().use { TTFFile.open(it).families.values.first() } to file
                    }.getOrNull()
                }
                .distinctBy { it.first }
                .toMap()
            cachedFamilies = Triple(includeSystemFonts, modified, families)
            families
        }

    fun invalidate() {
        cachedFamilies = null
    }

    private val FONT_EXTENSION_REGEX = Regex(""".*\.[ot]tf$""")
}
