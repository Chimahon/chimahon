package eu.kanade.tachiyomi.data.dictionary

import chimahon.DictionaryRepository
import chimahon.dictionary.DICTIONARY_TYPES_MARKER
import chimahon.dictionary.readDictionaryIndex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object DictionaryBackupManager {

    const val FORMAT_VERSION = 1
    const val MANIFEST_NAME = "manifest.json"

    val types = listOf("term", "frequency", "pitch", "kanji")

    @Serializable
    data class Manifest(
        val format: Int,
        val app: String,
        val exportedAt: Long,
        val dictionaries: List<DictionaryEntry>,
    )

    @Serializable
    data class DictionaryEntry(
        val name: String,
        val revision: String? = null,
    )

    enum class Action { IMPORT, UPDATE, SKIP, KEEP }

    data class ImportReport(
        val imported: List<String> = emptyList(),
        val updated: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
        val kept: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = imported.isEmpty() && updated.isEmpty() && skipped.isEmpty() && kept.isEmpty()
    }

    sealed interface ImportResult {
        data class Success(val report: ImportReport) : ImportResult
        data class Failure(val message: String) : ImportResult
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // ---- Export -----------------------------------------------------------

    /**
     * Compresses [dictionariesDir] (the `dictionaries` storage folder) into [output]
     * as a zip whose first entry is [MANIFEST_NAME] and whose remaining entries are
     * the folder tree with paths relative to [dictionariesDir] (e.g. `term/jmdict/...`).
     */
    fun export(
        dictionariesDir: File,
        output: OutputStream,
        appVersion: String = "",
    ) {
        val manifest = Manifest(
            format = FORMAT_VERSION,
            app = appVersion,
            exportedAt = System.currentTimeMillis(),
            dictionaries = scanDictionaryEntries(dictionariesDir),
        )

        ZipOutputStream(output).use { zip ->
            writeEntry(zip, MANIFEST_NAME, json.encodeToString(manifest).toByteArray())
            zipFolder(zip, dictionariesDir, basePath = null)
        }
    }

    // ---- Import -----------------------------------------------------------

    /**
     * Reads a backup zip from [input] (buffered into [tempZip] first so it can be
     * scanned twice), validates its format version and imports the dictionaries
     * into [dictionariesDir], resolving duplicates by revision.
     */
    fun import(
        input: InputStream,
        tempZip: File,
        dictionariesDir: File,
    ): ImportResult {
        try {
            input.use { it.copyTo(tempZip.outputStream()) }
            if (!tempZip.isFile || tempZip.length() == 0L) {
                return ImportResult.Failure("Backup file is empty or could not be read")
            }

            val manifest = readManifest(tempZip)
                ?: return ImportResult.Failure("No manifest.json found - not a dictionary backup")
            if (manifest.format != FORMAT_VERSION) {
                return ImportResult.Failure(
                    "Unsupported backup format (${manifest.format}). Expected $FORMAT_VERSION.",
                )
            }

            val decisions = manifest.dictionaries.associate { entry ->
                entry.name to decideAction(dictionariesDir, entry)
            }

            return ImportResult.Success(
                extractZip(tempZip, dictionariesDir, decisions).also { report ->
                    if (report.imported.isNotEmpty() || report.updated.isNotEmpty()) {
                        // A folder restored from a backup made before dictionaries recorded the
                        // types they serve carries no record, and the one-time pass has already run
                        // on this install, so it is allowed to look at the restored folders again.
                        File(dictionariesDir, DICTIONARY_TYPES_MARKER).delete()
                        DictionaryRepository.ensureDictionaryTypes(dictionariesDir)
                    }
                },
            )
        } catch (e: Exception) {
            return ImportResult.Failure(e.message ?: "Unknown error")
        } finally {
            if (tempZip.exists()) tempZip.delete()
        }
    }

    /**
     * Determines what to do with [entry] when importing over [dictionariesDir].
     */
    fun decideAction(
        dictionariesDir: File,
        entry: DictionaryEntry,
    ): Action {
        val existingDirs = types
            .map { File(File(dictionariesDir, it), entry.name) }
            .filter { it.isDirectory }
        if (existingDirs.isEmpty()) return Action.IMPORT

        val existingRevision = existingDirs
            .mapNotNull { readDictionaryIndex(it)?.revision }
            .distinct()
            .firstOrNull()

        return when {
            entry.revision == null -> Action.UPDATE
            existingRevision == null -> Action.UPDATE
            existingRevision == entry.revision -> Action.SKIP
            else -> Action.UPDATE
        }
    }

    // ---- Internals --------------------------------------------------------

    private fun scanDictionaryEntries(dictionariesDir: File): List<DictionaryEntry> {
        if (!dictionariesDir.isDirectory) return emptyList()
        return types.flatMap { type ->
            val typeDir = File(dictionariesDir, type)
            if (!typeDir.isDirectory) {
                emptyList()
            } else {
                typeDir.listFiles()
                    ?.filter { it.isDirectory }
                    ?.map { dir -> DictionaryEntry(name = dir.name) }
                    ?: emptyList()
            }
        }.groupBy { it.name }.map { (name, entries) ->
            val revision = types.mapNotNull { type ->
                readDictionaryIndex(File(dictionariesDir, "$type/$name"))?.revision
            }.distinct().firstOrNull()
            DictionaryEntry(name = name, revision = revision)
        }.sortedBy { it.name }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun zipFolder(zip: ZipOutputStream, folder: File, basePath: String?) {
        val children = folder.listFiles()?.sorted() ?: return
        children.forEach { child ->
            val name = if (basePath == null) child.name else "$basePath/${child.name}"
            if (child.isDirectory) {
                zipFolder(zip, child, name)
            } else {
                child.inputStream().use { input ->
                    zip.putNextEntry(ZipEntry(name))
                    input.copyTo(zip)
                    zip.closeEntry()
                }
            }
        }
    }

    private fun readManifest(zipFile: File): Manifest? {
        val bytes = ZipInputStream(zipFile.inputStream()).use { zip ->
            var bytes: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == MANIFEST_NAME) bytes = zip.readBytes()
                zip.closeEntry()
                if (bytes != null) break
            }
            bytes
        } ?: return null

        return try {
            json.decodeFromString<Manifest>(String(bytes))
        } catch (_: Exception) {
            null
        }
    }

    private fun extractZip(
        zipFile: File,
        dictionariesDir: File,
        decisions: Map<String, Action>,
    ): ImportReport {
        if (!dictionariesDir.exists()) dictionariesDir.mkdirs()
        val root = dictionariesDir.canonicalFile

        val imported = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val kept = mutableListOf<String>()

        fun record(name: String, action: Action) {
            val list = when (action) {
                Action.IMPORT -> imported
                Action.UPDATE -> updated
                Action.SKIP -> skipped
                Action.KEEP -> kept
            }
            if (name !in list) list += name
        }

        ZipInputStream(zipFile.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val entryName = entry.name
                val dictName = entryName.split('/').getOrNull(1)

                if (entryName == MANIFEST_NAME) {
                    zip.closeEntry()
                    continue
                }

                val target = File(root, entryName).canonicalFile
                check(
                    target.path == root.path ||
                        target.path.startsWith(root.path + File.separator),
                ) { "Unsafe zip entry: $entryName" }

                val action = dictName?.let(decisions::get) ?: Action.IMPORT
                if (action == Action.SKIP || action == Action.KEEP) {
                    dictName?.let { record(it, action) }
                    zip.closeEntry()
                    continue
                }

                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out -> zip.copyTo(out) }
                }
                zip.closeEntry()

                dictName?.let { record(it, action) }
            }
        }

        return ImportReport(
            imported = imported,
            updated = updated,
            skipped = skipped,
            kept = kept,
        )
    }
}
