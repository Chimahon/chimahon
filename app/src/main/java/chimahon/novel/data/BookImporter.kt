package chimahon.novel.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import chimahon.novel.data.epub.EpubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

data class ImportResult(
    val metadata: BookMetadata? = null,
    val error: String? = null,
)

object BookImporter {

    private const val TAG = "BookImporter"
    private const val MAX_DIM = 2048
    private const val MAX_PIXELS = 4_000_000L

    suspend fun importEpub(
        context: Context,
        uri: Uri,
        categoryIds: List<String>? = null,
        targetRoot: File? = null,
        targetRootUni: com.hippo.unifile.UniFile? = null,
    ): ImportResult = withContext(Dispatchers.IO) {
        // Temps are always cache-resident; success paths consume them
        // (rename/delete), so anything left in `finally` is failed-import
        // debris — never a book.
        var tempFile: File? = null
        var tempExtractDir: File? = null
        try {
            Log.d(TAG, "Starting import from URI: $uri")

            tempFile = File(context.cacheDir, "import_${System.currentTimeMillis()}.epub")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile!!.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext ImportResult(error = "Could not read file")

            Log.d(TAG, "Copied to temp: ${tempFile!!.absolutePath}, size: ${tempFile.length()}")

            try {
                ZipFile(tempFile).use { zip ->
                    val entries = zip.entries().asSequence().toList()
                    Log.d(TAG, "ZIP contains ${entries.size} entries")
                    if (entries.isEmpty()) {
                        return@withContext ImportResult(error = "File is empty")
                    }
                }
            } catch (e: Exception) {
                return@withContext ImportResult(error = "Not a valid EPUB file: ${e.message}")
            }

            // Extraction happens in cache first; the target is picked below
            // (UniFile-first, File fallback).
            tempExtractDir = File(context.cacheDir, "temp_extract_${System.currentTimeMillis()}").canonicalFile
            tempExtractDir!!.mkdirs()

            extractZip(tempFile!!, tempExtractDir!!)

            // Non-null from here on; `finally` still guards the failure paths above.
            val workFile = tempFile!!
            val workDir = tempExtractDir!!

            val extractedBook = EpubParser.parse(workDir)
            val title = extractedBook.title ?: "Unknown"
            val author = extractedBook.author ?: ""

            // Normalize large images before the tree lands anywhere (both
            // targets below). Reader layout is applied at render time;
            // imported markup stays untouched.
            normaliseTree(workDir)

            // UniFile-first: one copy path for file and tree schemes alike.
            // Public holds ONLY the .epub (extraction always lives in the
            // private cache); cover stays null here and resolves from the
            // EPUB at read time.
            if (targetRootUni != null) {
                val uniDir = chimahon.novel.source.LocalNovelFiles.uniqueTitleDirUni(
                    targetRootUni, title, author,
                ) ?: return@withContext ImportResult(error = "Could not create book folder")
                // Reimports replace files (DB carries resume/history/stats).
                runCatching { uniDir.listFiles() }.getOrNull()
                    ?.forEach { runCatching { it.delete() } }
                val epubName = "${uniDir.name ?: title}.epub"
                val epubOk = runCatching {
                    val epubOut = uniDir.createFile(epubName)
                        ?: error("createFile returned null")
                    workFile.inputStream().use { input ->
                        epubOut.openOutputStream()?.use { output -> input.copyTo(output) }
                            ?: error("openOutputStream returned null")
                    }
                    uniDir.findFile(epubName) != null
                }.getOrDefault(false)
                workFile.delete()
                workDir.deleteRecursively()
                tempFile = null
                tempExtractDir = null
                // Never report success on an empty dir — that phantom is
                // exactly the "nothing happened" confusion.
                if (!epubOk) {
                    runCatching { uniDir.delete() }
                    return@withContext ImportResult(error = "Could not write EPUB file")
                }
                val dirName = uniDir.name ?: title
                return@withContext ImportResult(
                    metadata = BookMetadata(
                        id = dirName,
                        title = title,
                        author = author,
                        cover = null,
                        folder = dirName,
                        lastAccess = System.currentTimeMillis(),
                        dateAdded = System.currentTimeMillis(),
                        hash = dirName,
                        lang = extractedBook.language,
                        categoryIds = categoryIds.orEmpty(),
                    ),
                )
            }

            // File fallback: explicit root wins, else the private dir.
            // Books live in title folders (manga local mirror): the folder is
            // the identity.
            val booksDir = BookStorage.getBooksDirectory(context).apply { mkdirs() }
            Log.d(TAG, "Books directory: ${booksDir.absolutePath}")
            val root = targetRoot?.takeIf { it.isDirectory || it.mkdirs() } ?: booksDir
            val bookDir = BookStorage.uniqueTitleDir(root, title, author)

            Log.d(TAG, "Import folder: ${bookDir.absolutePath} ($title | $author)")

            // Reimports replace files; resume/history/stats live in the DB,
            // never in sidecars — nothing to preserve here.
            if (bookDir.exists()) {
                bookDir.deleteRecursively()
            }
            workDir.renameTo(bookDir)

            // Keep the original EPUB next to the extraction, named after the novel.
            runCatching {
                workFile.copyTo(File(bookDir, "${bookDir.name}.epub"), overwrite = true)
            }

            workFile.delete()
            // workDir moved above; null the vars so `finally` skips them.
            tempFile = null
            tempExtractDir = null

            Log.d(TAG, "Extracted to: ${bookDir.absolutePath}")
            val extractedFiles = bookDir.walkTopDown().take(20).map { it.relativeTo(bookDir).path }.toList()
            Log.d(TAG, "Extracted files (first 20): $extractedFiles")

            Log.d(TAG, "Finalized to: ${bookDir.absolutePath}")

            val coverAbsPath = extractedBook.coverPath?.let { File(bookDir, it).absolutePath }

            Log.d(TAG, "Parsed EPUB: title=$title, contentDir=${extractedBook.contentDirectory}, chapters=${extractedBook.spine.items.size}")

            // Transient descriptor for the caller (registration builds the DB
            // row; nothing is written to disk as JSON anymore).
            val metadata = BookMetadata(
                id = bookDir.name,
                title = title,
                author = author,
                cover = coverAbsPath,
                folder = bookDir.name,
                lastAccess = System.currentTimeMillis(),
                dateAdded = System.currentTimeMillis(),
                hash = bookDir.name,
                lang = extractedBook.language,
                categoryIds = categoryIds.orEmpty(),
            )

            return@withContext ImportResult(metadata = metadata)
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            return@withContext ImportResult(error = "Import failed: ${e.message}")
        } finally {
            // Success paths consume their temps (rename/delete + nulled vars);
            // whatever remains is failed-import debris in cache — drop it.
            tempFile?.takeIf { it.exists() }?.delete()
            tempExtractDir?.takeIf { it.exists() }?.deleteRecursively()
        }
    }

    /**
     * Slip-safe EPUB extraction, shared by import and the extraction cache
     * (public books keep only the `.epub`; the cache is the readable copy).
     */
    fun extractZip(epubFile: File, destDir: File) {
        val canonicalDest = destDir.canonicalFile
        ZipFile(epubFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val file = File(canonicalDest, entry.name).canonicalFile
                if (!file.path.startsWith(canonicalDest.path + File.separator)) {
                    throw SecurityException("Unsafe zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    /**
     * Downsample oversized images in place (shared by import and the
     * extraction cache — served images must never OOM the reader).
     */
    fun normaliseTree(root: File) {
        root.walkTopDown().forEach { file ->
            val ext = file.extension.lowercase()
            if (ext == "jpg" || ext == "jpeg" || ext == "png" || ext == "webp" || ext == "gif") {
                normaliseImageInPlace(file)
            }
        }
    }

    private fun normaliseImageInPlace(file: File) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)

            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) {
                Log.w(TAG, "normalise: could not read bounds for ${file.name} — skipping")
                return
            }

            val needsDownsample = w > MAX_DIM || h > MAX_DIM || (w.toLong() * h) > MAX_PIXELS
            if (!needsDownsample) return // small enough — leave it alone

            val sampleSize = calculateInSampleSize(w, h)
            Log.w(TAG, "normalise: ${file.name} ${w}x$h → 1:$sampleSize sample")

            val mimeType = bounds.outMimeType ?: ""
            val mightHaveAlpha = mimeType.contains("png", ignoreCase = true) ||
                mimeType.contains("gif", ignoreCase = true) ||
                mimeType.contains("webp", ignoreCase = true)

            val decodeOpts = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inSampleSize = sampleSize
                inPreferredConfig = if (mightHaveAlpha) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            }
            val bitmap: Bitmap? = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            if (bitmap == null) {
                Log.e(TAG, "normalise: decode returned null for ${file.name} — skipping")
                return
            }

            try {
                val hasAlpha = bitmap.hasAlpha() && mightHaveAlpha

                @Suppress("DEPRECATION")
                val format: Bitmap.CompressFormat
                val quality: Int
                if (hasAlpha) {
                    // Lossless WebP to preserve transparency
                    format = if (android.os.Build.VERSION.SDK_INT >= 30) {
                        Bitmap.CompressFormat.WEBP_LOSSLESS
                    } else {
                        Bitmap.CompressFormat.WEBP
                    }
                    quality = 100
                } else {
                    // Lossy WebP — smallest file, no transparency needed
                    format = if (android.os.Build.VERSION.SDK_INT >= 30) {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    } else {
                        Bitmap.CompressFormat.WEBP
                    }
                    quality = 82
                }

                val tmp = File(file.parent, "${file.name}.norm.tmp")
                val ok = try {
                    tmp.outputStream().use { out -> bitmap.compress(format, quality, out) }
                } catch (e: Exception) {
                    Log.e(TAG, "normalise: compress failed for ${file.name}", e)
                    tmp.delete()
                    false
                }

                if (ok) {
                    if (!tmp.renameTo(file)) {
                        // renameTo can fail cross-device; fall back to copy + delete
                        tmp.inputStream().use { src -> file.outputStream().use { dst -> src.copyTo(dst) } }
                        tmp.delete()
                    }
                    Log.d(TAG, "normalise: ${file.name} compressed OK (alpha=$hasAlpha)")
                } else {
                    Log.e(TAG, "normalise: compress returned false for ${file.name} — keeping original")
                    tmp.delete()
                }
            } finally {
                bitmap.recycle()
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "normalise: OOM on ${file.name} — keeping original", e)
        } catch (e: Exception) {
            Log.e(TAG, "normalise: unexpected error for ${file.name} — keeping original", e)
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var inSampleSize = 1
        var w = width
        var h = height
        while (w > MAX_DIM || h > MAX_DIM || (w.toLong() * h) > MAX_PIXELS) {
            inSampleSize *= 2
            w = width / inSampleSize
            h = height / inSampleSize
        }
        return inSampleSize
    }
}
