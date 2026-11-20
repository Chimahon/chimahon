package chimahon.novel.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object FontManager {
    val defaultFonts = listOf("System Serif", "System Sans-Serif")

    const val KANJI_STROKE_FONT_NAME = "KanjiStrokeOrders"
    const val KANJI_STROKE_FONT_FAMILY = "KanjiStrokeOrders"
    const val KANJI_STROKE_FONT_URL = "https://drive.google.com/uc?export=download&id=1TELymEhF0YMK0Ma-fQlpHNmZLg9Xw3zx"

    fun isCustomFont(context: Context, fontName: String): Boolean {
        return !defaultFonts.contains(fontName) && getFontFile(context, fontName) != null
    }

    fun getFontUri(context: Context, fontName: String): String? {
        val file = getFontFile(context, fontName) ?: return null
        return Uri.fromFile(file).toString()
    }

    fun getFontsDir(context: Context): File {
        val dir = File(context.filesDir, "fonts")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    suspend fun importFont(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        var temporary: File? = null
        try {
            val fileName = getFileName(context, uri)?.let { File(it).name }
                ?.takeUnless { it.isBlank() || it == "." || it == ".." }
                ?: "imported_font_${System.currentTimeMillis()}.ttf"
            val directory = getFontsDir(context)
            val pending = File.createTempFile("font_", ".tmp", directory)
            temporary = pending
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext false
            input.use {
                pending.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (pending.length() == 0L) return@withContext false
            var target = File(directory, fileName)
            var suffix = 2
            while (target.exists()) {
                val name = File(fileName)
                target = File(directory, "${name.nameWithoutExtension} (${suffix++}).${name.extension}")
            }
            pending.renameTo(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            temporary?.delete()
        }
    }

    fun getImportedFonts(context: Context): List<String> {
        val dir = getFontsDir(context)
        return dir.listFiles()?.filter { it.isFile && it.extension != "tmp" }
            ?.map { it.nameWithoutExtension } ?: emptyList()
    }

    fun getFontFile(context: Context, fontName: String): File? {
        val dir = getFontsDir(context)
        return dir.listFiles()?.find { it.isFile && it.extension != "tmp" && it.nameWithoutExtension == fontName }
    }

    fun deleteFont(context: Context, fontName: String) {
        getFontFile(context, fontName)?.delete()
    }

    fun getKanjiStrokeFontFile(context: Context): File {
        return File(getFontsDir(context), "$KANJI_STROKE_FONT_NAME.ttf")
    }

    fun hasKanjiStrokeFont(context: Context): Boolean {
        return getKanjiStrokeFontFile(context).exists()
    }

    fun getKanjiStrokeFontUri(context: Context): String? {
        val file = getKanjiStrokeFontFile(context)
        return if (file.exists()) Uri.fromFile(file).toString() else null
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex("_display_name")
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path?.let { File(it).name }
        }
        return result
    }
}
