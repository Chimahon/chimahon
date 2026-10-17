package eu.kanade.tachiyomi.ui.player.utils

import android.content.Context
import com.yubyf.truetypeparser.TTFFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resolves font files for the subtitle font picker from the app and system font directories.
 */
object SubtitleFontResolver {

    private val systemFontDirs = listOf("/system/fonts/", "/product/fonts/")

    @Volatile
    private var cachedFamilies: Pair<Boolean, Map<String, File>>? = null

    /**
     * Family name to font file, user fonts first so they win on duplicates.
     */
    suspend fun fontFamilies(context: Context, includeSystemFonts: Boolean): Map<String, File> =
        withContext(Dispatchers.IO) {
            cachedFamilies?.takeIf { it.first == includeSystemFonts }?.let { return@withContext it.second }
            val dirs = listOf(chimahon.novel.data.FontManager.getFontsDir(context)) +
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
            cachedFamilies = includeSystemFonts to families
            families
        }

    fun invalidate() {
        cachedFamilies = null
    }

    private val FONT_EXTENSION_REGEX = Regex(""".*\.[ot]tf$""")
}
