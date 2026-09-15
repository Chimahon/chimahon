package eu.kanade.tachiyomi.ui.player.utils

import android.content.Context
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import com.yubyf.truetypeparser.TTFFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resolves subtitle font families to files and Compose [FontFamily]s.
 *
 * Single source of truth for both the font picker and the subtitle overlay:
 * the shared app fonts directory plus Android system font directories.
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

    suspend fun resolveFontFamily(
        context: Context,
        family: String,
        includeSystemFonts: Boolean,
    ): FontFamily? {
        return runCatching {
            val file = fontFamilies(context, includeSystemFonts)[family] ?: return null
            FontFamily(Typeface.createFromFile(file))
        }.getOrNull()
    }

    private val FONT_EXTENSION_REGEX = Regex(""".*\.[ot]tf$""")
}
