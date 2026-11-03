package chimahon.audio

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.hippo.unifile.UniFile
import java.io.File

class WordAudioDatabase(private val context: Context) {

    private var handle: Long = 0L
    private var legacyDb: SQLiteDatabase? = null
    private var currentUri: String? = null

    var lastError: String? = null
    var fallbackUsed: Boolean = false

    private val defaultSources = listOf(
        "nhk16", "daijisen", "shinmeikai8", "jpod", "jpod_alternate",
        "taas", "ozk5", "forvo", "forvo_ext", "forvo_ext2",
    )

    private fun katakanaToHiragana(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            val code = c.code
            sb.append(if (code in 0x30A1..0x30F6) (code - 0x60).toChar() else c)
        }
        return sb.toString()
    }

    /** Opens a database by local file path — bypasses ContentResolver entirely. */
    fun updatePath(path: String?): Boolean {
        if (path.isNullOrBlank()) { close(); return false }
        close()
        lastError = null
        fallbackUsed = false
        val file = File(path)
        val pfd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            lastError = "Cannot open file: ${e.message}"
            return false
        }
        val size = pfd.statSize
        if (size <= 0) {
            lastError = "File is empty"
            pfd.close()
            return false
        }
        val fd = pfd.detachFd()
        val h = nativeOpen(fd, size)
        if (h != 0L) {
            handle = h
            currentUri = file.toURI().toString()
            Log.i(TAG, "Opened audio database: $path")
            return true
        }

        // Native mmap rejected the file; try legacy SQLiteDatabase.
        try {
            legacyDb = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            if (legacyDb != null) {
                currentUri = file.toURI().toString()
                fallbackUsed = true
                lastError = null
                Log.i(TAG, "Opened audio database (legacy fallback): $path")
                return true
            }
        } catch (_: Exception) { }

        lastError = "File is not a valid audio database"
        return false
    }

    /** Opens any SAF Uri using a direct file descriptor, then a streamed private-file fallback. */
    fun updateUri(uri: Uri): Boolean {
        return chimahon.diagnostics.LookupProfiler.operation("storage.audio.database.open") {
            val key = uri.toString()
            if (key == currentUri && (handle != 0L || legacyDb != null) && testConnection()) return true
            close()
            lastError = null
            fallbackUsed = false

            // ── Attempt 1: ContentResolver fd + statSize ──────────────
            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    var size = pfd.statSize

                    // ── Attempt 2: statSize was 0, try UniFile.length() ──
                    if (size <= 0) {
                        size = UniFile.fromUri(context, uri)?.length() ?: 0L
                    }

                    if (size > 0) {
                        val fd = pfd.detachFd()
                        val h = nativeOpen(fd, size)
                        if (h != 0L) {
                            // A cloud/document provider may expose a partial
                            // SQLite image that still produces a native handle.
                            // Probe the schema before accepting that handle so a
                            // stable private copy can be used instead.
                            if (nativeTestConnection(h)) {
                                handle = h
                                currentUri = key
                                Log.i(TAG, "Opened audio database: $uri")
                                return true
                            }
                            Log.w(TAG, "SAF descriptor opened but failed the entries probe; trying private copy")
                            nativeClose(h)
                        }
                        // nativeOpen failed — fd already handled by JNI (close/fd leak fix)
                    } else {
                        pfd.close()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Attempt 1/2 failed", e)
            }

            // The provider may have handed us a stale descriptor. Reopen it once
            // before resorting to the private-copy fallback.
            try {
                val retryPfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (retryPfd != null) {
                    var retrySize = retryPfd.statSize
                    if (retrySize <= 0) {
                        retrySize = UniFile.fromUri(context, uri)?.length() ?: 0L
                    }
                    if (retrySize > 0) {
                        val retryFd = retryPfd.detachFd()
                        val retryHandle = nativeOpen(retryFd, retrySize)
                        if (retryHandle != 0L) {
                            if (nativeTestConnection(retryHandle)) {
                                handle = retryHandle
                                currentUri = key
                                Log.i(TAG, "Reopened audio database with a fresh SAF descriptor: $uri")
                                return true
                            }
                            nativeClose(retryHandle)
                        }
                    } else {
                        retryPfd.close()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fresh native audio database reopen failed", e)
            }

            // ── Attempt 3 (LAST RESORT): copy to private + old SQLiteDatabase ──
            val privateDir = context.getExternalFilesDir(null) ?: run {
                if (lastError == null) lastError = "No accessible storage directory"
                return false
            }
            val targetFile = File(privateDir, "word_audio.db")
            try {
                val source = UniFile.fromUri(context, uri) ?: run {
                    if (lastError == null) lastError = "Cannot access file"
                    return false
                }

                source.openInputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                }

                if (targetFile.exists() && targetFile.length() > 0) {
                    legacyDb = SQLiteDatabase.openDatabase(
                        targetFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READONLY,
                    )
                    if (legacyDb != null) {
                        currentUri = key
                        fallbackUsed = true
                        lastError = null
                        Log.i(TAG, "Opened audio database (legacy fallback): ${targetFile.absolutePath}")
                        return true
                    }
                }
                targetFile.delete()
                if (lastError == null) lastError = "File is not a valid audio database"
            } catch (e: Exception) {
                Log.e(TAG, "Attempt 3 failed", e)
                targetFile.delete()
                if (lastError == null) lastError = "Could not read file: ${e.message}"
            }

            // ── Attempt 4 (FALLBACK FOR CUSTOM ROMS): resolve real path ────────
            try {
                val path = getPathFromUri(context, uri)
                if (path != null) {
                    if (updatePath(path)) {
                        Log.i(TAG, "Opened audio database via fallback path: $path")
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Attempt 4 failed", e)
            }

            return false
        }
    }

    fun isOpenFor(uriStr: String): Boolean {
        if (handle != 0L) return currentUri == uriStr
        if (legacyDb != null) return currentUri == uriStr
        return false
    }

    fun testConnection(): Boolean {
        if (fallbackUsed) {
            val db = legacyDb ?: run { lastError = "No database open"; return false }
            try {
                db.rawQuery("SELECT count(*) FROM entries LIMIT 1", null).use { cursor ->
                    if (cursor.moveToFirst()) return true
                }
            } catch (e: Exception) {
                lastError = "Database corrupted: ${e.message}"
                return false
            }
            lastError = "Database is missing the required 'entries' table"
            return false
        }
        if (handle == 0L) { lastError = "No database open"; return false }
        if (!nativeTestConnection(handle)) {
            lastError = "Database is missing the required 'entries' table"
            return false
        }
        return true
    }

    fun findEntries(term: String, reading: String): List<LocalEntry> {
        if (fallbackUsed) return queryLegacy(term, reading)
        if (handle == 0L) return emptyList()
        val normalizedReading = katakanaToHiragana(reading)
        val rows = nativeFindEntries(
            handle, term, normalizedReading,
            defaultSources.joinToString(","),
        )
        return rows?.toList() ?: emptyList()
    }

    fun getAudioData(filePath: String, sourceId: String): ByteArray? {
        if (fallbackUsed) return getLegacyAudioData(filePath, sourceId)
        if (handle == 0L) return null
        return nativeGetAudioData(handle, filePath, sourceId)
    }

    fun close() {
        if (fallbackUsed) {
            legacyDb?.close()
            legacyDb = null
            fallbackUsed = false
        } else if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
        currentUri = null
        lastError = null
    }

    private fun queryLegacy(term: String, reading: String): List<LocalEntry> {
        val database = legacyDb ?: return emptyList()
        val results = mutableListOf<LocalEntry>()
        val normalizedReading = katakanaToHiragana(reading)

        val sourceOrder = StringBuilder("CASE source ")
        defaultSources.forEachIndexed { index, source ->
            sourceOrder.append("WHEN '$source' THEN $index ")
        }
        sourceOrder.append("ELSE 999 END")

        val query = if (normalizedReading.isEmpty()) {
            """
            SELECT file, source, speaker, display, reading, expression FROM entries
            WHERE expression = ?
            ORDER BY $sourceOrder
            LIMIT 1
            """.trimIndent()
        } else {
            """
            SELECT file, source, speaker, display, reading, expression FROM entries
            WHERE (expression = ? OR reading = ?)
            ORDER BY CASE WHEN reading = ? THEN 0 ELSE 1 END, $sourceOrder
            LIMIT 1
            """.trimIndent()
        }

        val args = if (normalizedReading.isEmpty()) {
            arrayOf(term)
        } else {
            arrayOf(term, normalizedReading, normalizedReading)
        }

        try {
            database.rawQuery(query, args).use { cursor ->
                while (cursor.moveToNext()) {
                    results.add(
                        LocalEntry(
                            file = cursor.getString(0) ?: "",
                            sourceId = cursor.getString(1) ?: "",
                            speaker = cursor.getString(2),
                            display = cursor.getString(3),
                            reading = cursor.getString(4) ?: "",
                            expression = cursor.getString(5) ?: "",
                        ),
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying entries (legacy)", e)
        }

        return results
    }

    private fun getLegacyAudioData(filePath: String, sourceId: String): ByteArray? {
        val database = legacyDb ?: return null
        val query = "SELECT data FROM android WHERE file = ? AND source = ?"
        return try {
            database.rawQuery(query, arrayOf(filePath, sourceId)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getBlob(0) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving audio data (legacy)", e)
            null
        }
    }

    data class LocalEntry(
        val file: String,
        val sourceId: String,
        val speaker: String?,
        val display: String?,
        val reading: String,
        val expression: String,
    )

    companion object {
        private const val TAG = "WordAudioDatabase"
        init { System.loadLibrary("word_audio_jni") }
    }

    private external fun nativeOpen(fd: Int, size: Long): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeTestConnection(handle: Long): Boolean
    private external fun nativeFindEntries(
        handle: Long, term: String, reading: String, sourceOrder: String,
    ): Array<LocalEntry>?
    private external fun nativeGetAudioData(
        handle: Long, filePath: String, sourceId: String,
    ): ByteArray?

    private fun getPathFromUri(context: Context, uri: Uri): String? {
        val scheme = uri.scheme
        if ("file".equals(scheme, ignoreCase = true)) {
            return validatePath(context, uri.path)
        }
        if ("content".equals(scheme, ignoreCase = true)) {
            val authority = uri.authority
            if ("com.android.externalstorage.documents" == authority) {
                val docId = android.provider.DocumentsContract.getDocumentId(uri)
                val split = docId.split(":")
                if (split.size >= 2) {
                    val type = split[0]
                    val relativePath = split[1]
                    val rawPath = if ("primary".equals(type, ignoreCase = true)) {
                        android.os.Environment.getExternalStorageDirectory().toString() + "/" + relativePath
                    } else {
                        "/storage/" + type + "/" + relativePath
                    }
                    return validatePath(context, rawPath)
                }
            } else if ("com.android.providers.downloads.documents" == authority) {
                val id = android.provider.DocumentsContract.getDocumentId(uri)
                if (id.startsWith("raw:")) {
                    return validatePath(context, id.substring(4))
                }
            }
            
            try {
                context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex("_data")
                        if (idx != -1) {
                            return validatePath(context, cursor.getString(idx))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query _data column for $uri", e)
            }
        }
        return null
    }

    private fun validatePath(context: Context, path: String?): String? {
        if (path.isNullOrBlank()) return null
        try {
            val file = java.io.File(path)
            val canonicalPath = file.canonicalPath
            
            // Path Traversal Mitigation: Ensure the path is not pointing to the app's internal sandbox
            val internalDir = context.filesDir.parentFile?.canonicalPath
            if (internalDir != null && canonicalPath.startsWith(internalDir)) {
                Log.e(TAG, "Security Exception: Blocked access to internal app sandbox directory: $canonicalPath")
                return null
            }
            return canonicalPath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate path: $path", e)
            return null
        }
    }
}
