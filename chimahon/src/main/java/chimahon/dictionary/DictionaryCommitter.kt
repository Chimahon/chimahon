package chimahon.dictionary

import android.util.Log
import java.io.File

/**
 * The one place a freshly imported dictionary folder is put into place.
 *
 * A dictionary is a single folder. The kinds of entries it can answer for are recorded inside it
 * ([DICTIONARY_TYPES_FILE]) and the folder is registered under all of them at lookup time, so a
 * dictionary is never copied into a second directory to make another kind work. Only the folder's
 * primary type directory receives it, and a copy left in another type directory by an earlier build
 * is removed.
 *
 * Committing replaces the previous folder by renaming, never by deleting it first: the engine keeps
 * a dictionary's blobs mapped by path, and a dictionary must never stop existing while a new one is
 * being written. If the commit fails, the folder that was there before is still there.
 */
object DictionaryCommitter {

    private const val TAG = "DictCommit"

    data class Report(
        val folderName: String,
        /** The types the committed folder serves, in primary order. */
        val types: List<DictionaryType>,
        /** Where the folder was written: `<dictionariesDir>/<primary type>/<folderName>`. */
        val writtenDir: File,
        /** Copies of the dictionary that were removed from other type directories. */
        val removedDuplicates: List<File> = emptyList(),
    )

    /**
     * Commits [importedDir] as the dictionary called [folderName].
     *
     * The folder is written into its primary type directory under [dictionariesDir], [types] is
     * recorded inside it, and any copy of the dictionary in another type directory is removed: the
     * fresh import is the dictionary's content, whatever the older copy held.
     *
     * @throws IllegalArgumentException when [folderName] is unusable or [types] is empty.
     * @throws java.io.IOException when the folder cannot be staged or moved into place; the
     *   previously committed folder is then left untouched.
     */
    fun commit(
        importedDir: File,
        dictionariesDir: File,
        folderName: String,
        types: List<DictionaryType>,
    ): Report {
        require(folderName.isNotBlank() && '/' !in folderName && '\\' !in folderName) {
            "Unusable dictionary folder name '$folderName'"
        }
        val orderedTypes = DictionaryType.all.filter { it in types }
        require(orderedTypes.isNotEmpty()) { "No known entry types for '$folderName'" }

        val primaryType = DictionaryType.primaryOf(orderedTypes)!!
        val targetDir = File(File(dictionariesDir, primaryType.dirName), folderName)
        replaceFolder(importedDir, targetDir, orderedTypes)

        val removedDuplicates = mutableListOf<File>()
        for (type in DictionaryType.all) {
            if (type == primaryType) continue
            val copy = File(File(dictionariesDir, type.dirName), folderName)
            if (!copy.isDirectory) continue
            if (copy.deleteRecursively()) {
                removedDuplicates += copy
                Log.i(TAG, "Removed duplicate copy of '$folderName' from ${type.dirName}/")
            } else {
                Log.w(TAG, "Could not remove duplicate copy at ${copy.absolutePath}")
            }
        }

        // Folders are added and replaced inside the type directories, which does not change the
        // modification time of the dictionaries directory the app caches listings on, so it is
        // refreshed here for every commit.
        touchDictionariesDir(dictionariesDir)
        Log.i(
            TAG,
            "Committed '$folderName' as ${orderedTypes.joinToString("+") { it.dirName }} " +
                "in ${primaryType.dirName}/",
        )
        return Report(folderName, orderedTypes, targetDir, removedDuplicates)
    }

    /**
     * Copies [sourceDir] to a staging folder beside [targetDir], records [types] in it, and then
     * swaps it in for [targetDir]. The previous folder only goes away after the new one is in
     * place, and comes back if the swap fails.
     */
    private fun replaceFolder(sourceDir: File, targetDir: File, types: List<DictionaryType>) {
        val parentDir = targetDir.parentFile ?: error("No parent directory for ${targetDir.absolutePath}")
        parentDir.mkdirs()
        val stamp = System.currentTimeMillis().toString()
        val stagingDir = File(parentDir, ".${targetDir.name}.new-$stamp")
        val backupDir = File(parentDir, ".${targetDir.name}.old-$stamp")

        stagingDir.deleteRecursively()
        try {
            if (!sourceDir.copyRecursively(stagingDir, overwrite = true)) {
                error("Could not copy ${sourceDir.absolutePath} to ${stagingDir.absolutePath}")
            }
            writeDictionaryTypes(stagingDir, types)
        } catch (e: Throwable) {
            stagingDir.deleteRecursively()
            throw e
        }

        val hadPrevious = targetDir.exists()
        try {
            if (hadPrevious && !targetDir.renameTo(backupDir)) {
                error("Could not move ${targetDir.absolutePath} aside")
            }
            if (!stagingDir.renameTo(targetDir)) {
                if (hadPrevious) backupDir.renameTo(targetDir)
                error("Could not move ${stagingDir.absolutePath} into place")
            }
        } finally {
            stagingDir.deleteRecursively()
            backupDir.deleteRecursively()
        }
    }
}
