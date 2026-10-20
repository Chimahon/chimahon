package chimahon.dictionary

import android.util.Log
import chimahon.HoshiDicts
import chimahon.ImportResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The kinds of entries a dictionary folder can answer for, in a fixed order.
 *
 * A dictionary is one folder and may hold several kinds at once (a term dictionary that also has
 * kanji entries, say). The engine answers a kind only from the lists a folder was registered in,
 * so the kinds a folder serves have to be known to the app; they are recorded inside the folder
 * ([DICTIONARY_TYPES_FILE]) instead of being spelled out by copying the folder per kind.
 */
enum class DictionaryType(val dirName: String) {
    TERM("term"),
    FREQUENCY("frequency"),
    PITCH("pitch"),
    KANJI("kanji"),
    ;

    companion object {
        /** Every type, in the order used to choose a folder's physical home: term > frequency > pitch > kanji. */
        val all: List<DictionaryType> = listOf(TERM, FREQUENCY, PITCH, KANJI)

        fun fromDirName(dirName: String): DictionaryType? = all.firstOrNull { it.dirName == dirName }

        /**
         * The type directory a folder serving [types] physically lives in: the first of [all] it
         * contains, or null when it serves none.
         */
        fun primaryOf(types: Collection<DictionaryType>): DictionaryType? = all.firstOrNull { it in types }
    }
}

/** The file a dictionary folder uses to record the [DictionaryType]s it serves. */
const val DICTIONARY_TYPES_FILE = "types.json"

/** Marker written into the dictionaries directory once the one-time type pass has completed. */
const val DICTIONARY_TYPES_MARKER = ".dict-types-v1"

private const val TAG = "DictTypes"

@Serializable
private data class TypeRecord(
    val version: Int = 1,
    val types: List<String> = emptyList(),
)

private val typeRecordJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** The types a fresh import reported, in primary order. */
fun ImportResult.detectedTypes(): List<DictionaryType> = DictionaryType.all.filter { type ->
    when (type) {
        DictionaryType.TERM -> termCount > 0
        DictionaryType.FREQUENCY -> freqCount > 0
        DictionaryType.PITCH -> pitchCount > 0
        DictionaryType.KANJI -> kanjiCount > 0
    }
}

/**
 * The types an existing folder serves, counted from its hash table and blobs through [probe] (the
 * native engine by default). Empty when the folder cannot be read or holds no recognizable entries.
 *
 * This is a full scan of the folder's data, so it is only run when a folder's types are not already
 * recorded, by the committer and the one-time type pass.
 */
fun probeDictionaryTypes(
    dictDir: File,
    probe: (String) -> LongArray = HoshiDicts::probeEntryTypes,
): List<DictionaryType> {
    val counts = try {
        probe(dictDir.absolutePath)
    } catch (e: Throwable) {
        // Deliberately broad: a folder written by an older format can also make the native probe
        // fail outright, and that must not abort a pass over the other dictionaries.
        Log.w(TAG, "Could not probe '${dictDir.name}': ${e.message}")
        return emptyList()
    }
    return DictionaryType.all.filterIndexed { index, _ -> index < counts.size && counts[index] > 0 }
}

/**
 * The types recorded inside [dictDir], or null when it has no usable record — a folder written
 * before records existed, or one whose record is unreadable. Callers then fall back to the
 * directory the folder physically lives in.
 */
fun readDictionaryTypes(dictDir: File): List<DictionaryType>? {
    val recordFile = File(dictDir, DICTIONARY_TYPES_FILE)
    if (!recordFile.isFile) return null
    return try {
        val names = typeRecordJson.decodeFromString<TypeRecord>(recordFile.readText()).types
        val recorded = names.mapNotNull { DictionaryType.fromDirName(it) }
        DictionaryType.all.filter { it in recorded }.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        Log.w(TAG, "Ignoring unreadable $DICTIONARY_TYPES_FILE in '${dictDir.name}': ${e.message}")
        null
    }
}

/** Records [types] inside [dictDir], replacing any previous record. */
fun writeDictionaryTypes(dictDir: File, types: Collection<DictionaryType>) {
    val ordered = DictionaryType.all.filter { it in types }
    val recordFile = File(dictDir, DICTIONARY_TYPES_FILE)
    val tempFile = File(dictDir, "$DICTIONARY_TYPES_FILE.tmp")
    tempFile.writeText(typeRecordJson.encodeToString(TypeRecord(types = ordered.map { it.dirName })))
    if (!tempFile.renameTo(recordFile)) {
        tempFile.copyTo(recordFile, overwrite = true)
        tempFile.delete()
    }
}

/** A dictionary folder found under [dictionariesDir], with the types it serves. */
data class DictionaryFolder(
    /** Folder name: the identity of a dictionary throughout the app. */
    val name: String,
    /** The folder itself, in whichever type directory it lives. */
    val dir: File,
    /** The types it serves, in primary order. */
    val types: List<DictionaryType>,
)

/**
 * Every dictionary folder under [dictionariesDir], each with the types it serves.
 *
 * A folder that carries a record is read from it, so a mixed dictionary sitting once in `term/`
 * reports both `term` and `kanji` and both kinds use that one folder. A folder without a record is
 * read as serving only the directory it sits in, which is exactly how installs written before
 * records existed behaved. A name found in more than one type directory is reported once, with the
 * union of its types, so a dictionary still serves everything it did before the copies were cleaned
 * up.
 *
 * Hidden entries are skipped: the committer stages a folder under a dot-prefixed name while it
 * replaces one, and a leftover from a commit that was killed must never read as a dictionary.
 */
fun scanDictionaryFolders(dictionariesDir: File): List<DictionaryFolder> {
    val copiesByName = LinkedHashMap<String, MutableMap<DictionaryType, File>>()
    for (type in DictionaryType.all) {
        val typeDir = File(dictionariesDir, type.dirName)
        for (folder in typeDir.listFiles().orEmpty()) {
            if (!folder.isDirectory || folder.name.startsWith(".")) continue
            val served = readDictionaryTypes(folder) ?: listOf(type)
            val byType = copiesByName.getOrPut(folder.name) { mutableMapOf() }
            for (servedType in served) {
                byType.putIfAbsent(servedType, folder)
            }
        }
    }
    return copiesByName.map { (name, byType) ->
        val types = DictionaryType.all.filter { it in byType }
        DictionaryFolder(name, byType.getValue(types.first()), types)
    }
}

/** Every dictionary name under [dictionariesDir], in [scanDictionaryFolders] order. */
fun scanDictionaryNames(dictionariesDir: File): List<String> =
    scanDictionaryFolders(dictionariesDir).map { it.name }

/** The folder of the dictionary called [name], wherever it lives under [dictionariesDir]. */
fun findDictionaryFolder(dictionariesDir: File, name: String): File? = DictionaryType.all
    .map { File(File(dictionariesDir, it.dirName), name) }
    .firstOrNull { it.isDirectory }

/** The engine format marker (`.hoshidicts_N`) a folder was written with, or null when it has none. */
fun dictionaryMarkerName(dictDir: File): String? = dictDir.listFiles()
    ?.firstOrNull { it.isFile && it.name.startsWith(".hoshidicts_") }
    ?.name

/**
 * Refreshes the modification time of [dictionariesDir] itself.
 *
 * The app caches lookup paths and dictionary listings on the modification time of that directory,
 * but writing inside one of its type directories does not change it, so anything that adds,
 * replaces or removes a dictionary calls this to make the change visible.
 */
fun touchDictionariesDir(dictionariesDir: File) {
    val now = System.currentTimeMillis()
    val lastModified = dictionariesDir.lastModified()
    dictionariesDir.setLastModified(if (now > lastModified) now else lastModified + 1000)
}
