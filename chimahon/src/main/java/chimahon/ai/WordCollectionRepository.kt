package chimahon.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SavedWordItem(
    val id: String,
    val word: String,
    val reading: String,
    val sentence: String,
    val contextualMeaning: String,
    val entry: CustomAiDictionaryEntry?,
    val savedAtMillis: Long,
    val imageFileName: String? = null,
    val imageSource: String? = null,
)

data class WordCollection(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long = createdAtMillis,
    val items: List<SavedWordItem>,
)

data class WordCollectionMeta(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val wordCount: Int,
    val wordsLowercase: Set<String> = emptySet(),
)

data class BackupImportSummary(
    val collectionsAdded: Int,
    val collectionsMerged: Int,
    val wordsImported: Int,
    val wordsSkipped: Int,
    val collectionsSkippedDueToLimit: Int,
)

object WordCollectionRepository {

    private const val LEGACY_FILE_NAME = "chimahon_ai_collections.json"
    private const val INDEX_FILE_NAME = "collections_index.json"
    private const val IMAGES_DIR_NAME = "chimahon_ai_images"
    private const val DEFAULT_COLLECTION_NAME = "Favorites"
    const val MAX_COLLECTIONS = 15
    const val MAX_WORDS_PER_COLLECTION = 15
    private val fileLock = Any()

    private fun shardFileName(collectionId: String): String = "collection_$collectionId.json"

    fun deterministicImageFileName(itemId: String): String = "img_$itemId.jpg"

    fun getImagesDir(context: Context): File =
        File(context.filesDir, IMAGES_DIR_NAME).apply { if (!exists()) mkdirs() }

    fun getWordImageFile(context: Context, imageFileName: String?): File? {
        val clean = imageFileName?.trim().orEmpty()
        if (clean.isEmpty()) return null
        val file = File(getImagesDir(context), clean)
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    private fun writeWordImageAtomicLocked(
        context: Context,
        itemId: String,
        jpegBytes: ByteArray,
    ): String {
        val dir = getImagesDir(context)
        val fileName = deterministicImageFileName(itemId)
        val targetFile = File(dir, fileName)
        val tempFile = File(dir, "$fileName.tmp")
        tempFile.writeBytes(jpegBytes)
        if (!tempFile.renameTo(targetFile)) {
            targetFile.writeBytes(jpegBytes)
            tempFile.delete()
        }
        return fileName
    }

    private fun deleteWordImageFileLocked(context: Context, imageFileName: String?) {
        val clean = imageFileName?.trim().orEmpty()
        if (clean.isEmpty()) return
        val dir = File(context.filesDir, IMAGES_DIR_NAME)
        runCatching { File(dir, clean).delete() }
        runCatching { File(dir, "$clean.tmp").delete() }
    }

    suspend fun getCollectionIndex(context: Context): List<WordCollectionMeta> = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            ensureIndexInitializedLocked(context)
        }
    }

    suspend fun getCollection(context: Context, collectionId: String): WordCollection? = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            val index = ensureIndexInitializedLocked(context)
            val meta = index.firstOrNull { it.id == collectionId } ?: return@synchronized null
            readShardLocked(context, meta)
        }
    }

    suspend fun createCollection(
        context: Context,
        rawName: String,
    ): Result<WordCollection> = withContext(Dispatchers.IO) {
        val cleanName = rawName.trim()
        if (cleanName.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Collection name cannot be empty."))
        }

        synchronized(fileLock) {
            val currentIndex = ensureIndexInitializedLocked(context).toMutableList()
            if (currentIndex.size >= MAX_COLLECTIONS) {
                return@withContext Result.failure(
                    IllegalStateException("Collection limit reached ($MAX_COLLECTIONS collections max). Delete a collection first.")
                )
            }
            if (currentIndex.any { it.name.equals(cleanName, ignoreCase = true) }) {
                return@withContext Result.failure(IllegalArgumentException("A collection named \"$cleanName\" already exists."))
            }

            val now = System.currentTimeMillis()
            val created = WordCollection(
                id = UUID.randomUUID().toString(),
                name = cleanName,
                createdAtMillis = now,
                updatedAtMillis = now,
                items = emptyList(),
            )
            writeShardLocked(context, created)
            currentIndex.add(created.toMeta())
            writeIndexLocked(context, currentIndex)
            Result.success(created)
        }
    }

    suspend fun deleteCollection(
        context: Context,
        collectionId: String,
    ): List<WordCollectionMeta> = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            val existingIndex = ensureIndexInitializedLocked(context)
            existingIndex.firstOrNull { it.id == collectionId }?.let { meta ->
                val shard = readShardLocked(context, meta)
                shard.items.forEach { deleteWordImageFileLocked(context, it.imageFileName) }
            }
            val updatedIndex = existingIndex.filterNot { it.id == collectionId }
            val shardName = shardFileName(collectionId)
            File(context.filesDir, shardName).delete()
            File(context.filesDir, "$shardName.tmp").delete()
            writeIndexLocked(context, updatedIndex)
            updatedIndex
        }
    }

    suspend fun saveWordToCollection(
        context: Context,
        collectionId: String,
        word: String,
        sentence: String,
        entry: CustomAiDictionaryEntry?,
        stagedImageBytes: ByteArray? = null,
        stagedImageSource: String? = null,
    ): Result<WordCollection> = withContext(Dispatchers.IO) {
        val cleanWord = word.trim()
        if (cleanWord.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Cannot save an empty word."))
        }

        synchronized(fileLock) {
            val currentIndex = ensureIndexInitializedLocked(context).toMutableList()
            val metaIdx = currentIndex.indexOfFirst { it.id == collectionId }
            if (metaIdx < 0) {
                return@withContext Result.failure(IllegalStateException("Collection not found."))
            }

            val target = readShardLocked(context, currentIndex[metaIdx])
            val cleanSentence = sentence.trim()
            val now = System.currentTimeMillis()
            val existingMatch = target.items.firstOrNull { existing ->
                existing.word.equals(cleanWord, ignoreCase = true) &&
                    existing.sentence.equals(cleanSentence, ignoreCase = true)
            }
            val itemId = existingMatch?.id ?: UUID.randomUUID().toString()
            val resolvedImageFileName: String?
            val resolvedImageSource: String?
            if (stagedImageBytes != null && stagedImageBytes.isNotEmpty()) {
                resolvedImageFileName = writeWordImageAtomicLocked(context, itemId, stagedImageBytes)
                resolvedImageSource = stagedImageSource ?: StagedImageSource.WEB_IMAGE.storageValue
            } else {
                resolvedImageFileName = existingMatch?.imageFileName
                resolvedImageSource = existingMatch?.imageSource
            }

            val newItem = SavedWordItem(
                id = itemId,
                word = cleanWord,
                reading = entry?.reading?.trim().orEmpty(),
                sentence = cleanSentence,
                contextualMeaning = entry?.contextualMeaning?.trim().orEmpty(),
                entry = entry,
                savedAtMillis = now,
                imageFileName = resolvedImageFileName,
                imageSource = resolvedImageSource,
            )

            val filteredItems = target.items.filterNot { existing ->
                existing.word.equals(cleanWord, ignoreCase = true) &&
                    existing.sentence.equals(cleanSentence, ignoreCase = true)
            }

            if (filteredItems.size >= MAX_WORDS_PER_COLLECTION) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Collection limit reached ($MAX_WORDS_PER_COLLECTION words max). Export to Anki or remove words first."
                    )
                )
            }

            val updatedCollection = target.copy(
                updatedAtMillis = now,
                items = listOf(newItem) + filteredItems,
            )
            writeShardLocked(context, updatedCollection)
            currentIndex[metaIdx] = updatedCollection.toMeta()
            writeIndexLocked(context, currentIndex)
            Result.success(updatedCollection)
        }
    }

    suspend fun updateWordImage(
        context: Context,
        collectionId: String,
        itemId: String,
        jpegBytes: ByteArray,
        imageSource: String,
    ): WordCollection? = withContext(Dispatchers.IO) {
        if (jpegBytes.isEmpty()) return@withContext null
        synchronized(fileLock) {
            val currentIndex = ensureIndexInitializedLocked(context).toMutableList()
            val metaIdx = currentIndex.indexOfFirst { it.id == collectionId }
            if (metaIdx < 0) return@synchronized null

            val target = readShardLocked(context, currentIndex[metaIdx])
            if (target.items.none { it.id == itemId }) return@synchronized null

            val fileName = writeWordImageAtomicLocked(context, itemId, jpegBytes)
            val now = System.currentTimeMillis()
            val updatedItems = target.items.map { item ->
                if (item.id == itemId) {
                    item.copy(
                        imageFileName = fileName,
                        imageSource = imageSource,
                    )
                } else {
                    item
                }
            }
            val updated = target.copy(updatedAtMillis = now, items = updatedItems)
            writeShardLocked(context, updated)
            currentIndex[metaIdx] = updated.toMeta()
            writeIndexLocked(context, currentIndex)
            updated
        }
    }

    suspend fun clearWordImage(
        context: Context,
        collectionId: String,
        itemId: String,
    ): WordCollection? = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            val currentIndex = ensureIndexInitializedLocked(context).toMutableList()
            val metaIdx = currentIndex.indexOfFirst { it.id == collectionId }
            if (metaIdx < 0) return@synchronized null

            val target = readShardLocked(context, currentIndex[metaIdx])
            val existingItem = target.items.firstOrNull { it.id == itemId } ?: return@synchronized null
            deleteWordImageFileLocked(context, existingItem.imageFileName)

            val now = System.currentTimeMillis()
            val updatedItems = target.items.map { item ->
                if (item.id == itemId) {
                    item.copy(imageFileName = null, imageSource = null)
                } else {
                    item
                }
            }
            val updated = target.copy(updatedAtMillis = now, items = updatedItems)
            writeShardLocked(context, updated)
            currentIndex[metaIdx] = updated.toMeta()
            writeIndexLocked(context, currentIndex)
            updated
        }
    }

    suspend fun clearCollection(
        context: Context,
        collectionId: String,
    ): WordCollection? = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            val currentIndex = ensureIndexInitializedLocked(context).toMutableList()
            val metaIdx = currentIndex.indexOfFirst { it.id == collectionId }
            if (metaIdx < 0) return@synchronized null

            val target = readShardLocked(context, currentIndex[metaIdx])
            target.items.forEach { deleteWordImageFileLocked(context, it.imageFileName) }
            val updated = target.copy(
                updatedAtMillis = System.currentTimeMillis(),
                items = emptyList(),
            )
            writeShardLocked(context, updated)
            currentIndex[metaIdx] = updated.toMeta()
            writeIndexLocked(context, currentIndex)
            updated
        }
    }

    suspend fun removeWordFromCollection(
        context: Context,
        collectionId: String,
        itemId: String,
    ): WordCollection? = removeWordsFromCollection(context, collectionId, setOf(itemId))

    suspend fun removeWordsFromCollection(
        context: Context,
        collectionId: String,
        itemIds: Set<String>,
    ): WordCollection? = withContext(Dispatchers.IO) {
        synchronized(fileLock) {
            val currentIndex = ensureIndexInitializedLocked(context).toMutableList()
            val metaIdx = currentIndex.indexOfFirst { it.id == collectionId }
            if (metaIdx < 0) return@synchronized null

            val target = readShardLocked(context, currentIndex[metaIdx])
            if (itemIds.isEmpty()) return@synchronized target

            target.items.filter { it.id in itemIds }.forEach {
                deleteWordImageFileLocked(context, it.imageFileName)
            }
            val updated = target.copy(
                updatedAtMillis = System.currentTimeMillis(),
                items = target.items.filterNot { it.id in itemIds },
            )
            writeShardLocked(context, updated)
            currentIndex[metaIdx] = updated.toMeta()
            writeIndexLocked(context, currentIndex)
            updated
        }
    }

    suspend fun exportBackupJson(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            synchronized(fileLock) {
                val index = ensureIndexInitializedLocked(context)
                val collections = index.map { meta -> readShardLocked(context, meta) }
                val root = JSONObject().apply {
                    put("app", "Chimahon")
                    put("type", "ai_word_collections_backup")
                    put("version", 1)
                    put("exportedAtMillis", System.currentTimeMillis())
                    put(
                        "collections",
                        JSONArray().apply {
                            collections.forEach { col ->
                                put(serializeCollection(col))
                            }
                        }
                    )
                }
                root.toString(2)
            }
        }
    }

    suspend fun importBackupJson(
        context: Context,
        rawJson: String,
    ): Result<BackupImportSummary> = withContext(Dispatchers.IO) {
        if (rawJson.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Backup file is empty."))
        }

        val parsedCollections = runCatching {
            val root = JSONObject(rawJson)
            val colsArray = root.optJSONArray("collections")
                ?: throw IllegalArgumentException("Invalid backup file: missing 'collections' array.")
            buildList {
                for (i in 0 until colsArray.length()) {
                    val colObj = colsArray.optJSONObject(i) ?: continue
                    val col = parseCollectionObject(colObj)
                    if (col.name.isNotEmpty()) {
                        add(col)
                    }
                }
            }
        }.getOrElse { err ->
            return@withContext Result.failure(
                IllegalArgumentException(err.message ?: "Invalid JSON backup file.")
            )
        }

        if (parsedCollections.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Backup file contains no valid collections."))
        }

        runCatching {
            synchronized(fileLock) {
                val currentIndex = ensureIndexInitializedLocked(context).toMutableList()
                val now = System.currentTimeMillis()
                var collectionsAdded = 0
                var collectionsMerged = 0
                var wordsImported = 0
                var wordsSkipped = 0
                var collectionsSkippedDueToLimit = 0

                for (incomingCol in parsedCollections) {
                    val existingIdx = currentIndex.indexOfFirst {
                        it.name.equals(incomingCol.name, ignoreCase = true)
                    }

                    if (existingIdx >= 0) {
                        val existingShard = readShardLocked(context, currentIndex[existingIdx])
                        val seenKeys = existingShard.items
                            .mapTo(mutableSetOf()) { it.word.trim().lowercase() to it.sentence.trim().lowercase() }
                        val mergedItems = existingShard.items.toMutableList()
                        var addedCount = 0

                        for (item in incomingCol.items) {
                            val key = item.word.trim().lowercase() to item.sentence.trim().lowercase()
                            if (key in seenKeys || mergedItems.size >= MAX_WORDS_PER_COLLECTION) {
                                wordsSkipped++
                                continue
                            }
                            seenKeys.add(key)
                            mergedItems.add(
                                item.copy(
                                    id = UUID.randomUUID().toString(),
                                    savedAtMillis = item.savedAtMillis.takeIf { it > 0L } ?: now,
                                    imageFileName = null,
                                    imageSource = null,
                                )
                            )
                            wordsImported++
                            addedCount++
                        }

                        if (addedCount > 0) {
                            val updatedShard = existingShard.copy(
                                updatedAtMillis = now,
                                items = mergedItems,
                            )
                            writeShardLocked(context, updatedShard)
                            currentIndex[existingIdx] = updatedShard.toMeta()
                        }
                        collectionsMerged++
                    } else {
                        if (currentIndex.size >= MAX_COLLECTIONS) {
                            collectionsSkippedDueToLimit++
                            wordsSkipped += incomingCol.items.size
                            continue
                        }

                        val seenKeys = mutableSetOf<Pair<String, String>>()
                        val acceptedItems = mutableListOf<SavedWordItem>()

                        for (item in incomingCol.items) {
                            val key = item.word.trim().lowercase() to item.sentence.trim().lowercase()
                            if (key in seenKeys || acceptedItems.size >= MAX_WORDS_PER_COLLECTION) {
                                wordsSkipped++
                                continue
                            }
                            seenKeys.add(key)
                            acceptedItems.add(
                                item.copy(
                                    id = UUID.randomUUID().toString(),
                                    savedAtMillis = item.savedAtMillis.takeIf { it > 0L } ?: now,
                                    imageFileName = null,
                                    imageSource = null,
                                )
                            )
                            wordsImported++
                        }

                        val newShard = WordCollection(
                            id = UUID.randomUUID().toString(),
                            name = incomingCol.name,
                            createdAtMillis = incomingCol.createdAtMillis.takeIf { it > 0L } ?: now,
                            updatedAtMillis = now,
                            items = acceptedItems,
                        )
                        writeShardLocked(context, newShard)
                        currentIndex.add(newShard.toMeta())
                        collectionsAdded++
                    }
                }

                writeIndexLocked(context, currentIndex)
                BackupImportSummary(
                    collectionsAdded = collectionsAdded,
                    collectionsMerged = collectionsMerged,
                    wordsImported = wordsImported,
                    wordsSkipped = wordsSkipped,
                    collectionsSkippedDueToLimit = collectionsSkippedDueToLimit,
                )
            }
        }
    }

    private fun WordCollection.toMeta(): WordCollectionMeta = WordCollectionMeta(
        id = id,
        name = name,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        wordCount = items.size,
        wordsLowercase = items.map { it.word.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
    )

    private fun ensureIndexInitializedLocked(context: Context): List<WordCollectionMeta> {
        val indexFile = File(context.filesDir, INDEX_FILE_NAME)
        if (!indexFile.exists()) {
            val legacyFile = File(context.filesDir, LEGACY_FILE_NAME)
            val initialCollections = if (legacyFile.exists()) {
                val migrated = runCatching {
                    parseLegacyCollectionsJson(legacyFile.readText(Charsets.UTF_8))
                }.getOrDefault(emptyList()).take(MAX_COLLECTIONS)
                migrated.ifEmpty { listOf(createDefaultCollection()) }
            } else {
                listOf(createDefaultCollection())
            }

            initialCollections.forEach { col -> writeShardLocked(context, col) }
            val metas = initialCollections.map { it.toMeta() }
            writeIndexLocked(context, metas)
            if (legacyFile.exists()) {
                runCatching { legacyFile.delete() }
            }
            return metas
        }

        return runCatching {
            parseIndexJson(indexFile.readText(Charsets.UTF_8))
        }.getOrElse {
            emptyList()
        }
    }

    private fun createDefaultCollection(): WordCollection {
        val now = System.currentTimeMillis()
        return WordCollection(
            id = UUID.randomUUID().toString(),
            name = DEFAULT_COLLECTION_NAME,
            createdAtMillis = now,
            updatedAtMillis = now,
            items = emptyList(),
        )
    }

    private fun writeAtomicFile(context: Context, fileName: String, content: String) {
        val targetFile = File(context.filesDir, fileName)
        val tempFile = File(context.filesDir, "$fileName.tmp")
        tempFile.writeText(content, Charsets.UTF_8)
        if (!tempFile.renameTo(targetFile)) {
            targetFile.writeText(content, Charsets.UTF_8)
            tempFile.delete()
        }
    }

    private fun writeIndexLocked(context: Context, entries: List<WordCollectionMeta>) {
        val root = JSONObject().apply {
            put("version", 1)
            put(
                "collections",
                JSONArray().apply {
                    entries.take(MAX_COLLECTIONS).forEach { meta ->
                        put(
                            JSONObject().apply {
                                put("id", meta.id)
                                put("name", meta.name)
                                put("createdAtMillis", meta.createdAtMillis)
                                put("updatedAtMillis", meta.updatedAtMillis)
                                put("wordCount", meta.wordCount)
                                put(
                                    "words",
                                    JSONArray().apply {
                                        meta.wordsLowercase.forEach { put(it) }
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }
        writeAtomicFile(context, INDEX_FILE_NAME, root.toString())
    }

    private fun writeShardLocked(context: Context, collection: WordCollection) {
        writeAtomicFile(context, shardFileName(collection.id), serializeCollection(collection).toString())
    }

    private fun readShardLocked(context: Context, meta: WordCollectionMeta): WordCollection {
        val shardFile = File(context.filesDir, shardFileName(meta.id))
        if (!shardFile.exists()) {
            val empty = WordCollection(
                id = meta.id,
                name = meta.name,
                createdAtMillis = meta.createdAtMillis,
                updatedAtMillis = meta.updatedAtMillis,
                items = emptyList(),
            )
            writeShardLocked(context, empty)
            return empty
        }
        return runCatching {
            val obj = JSONObject(shardFile.readText(Charsets.UTF_8))
            parseCollectionObject(obj, fallbackMeta = meta)
        }.getOrElse {
            WordCollection(
                id = meta.id,
                name = meta.name,
                createdAtMillis = meta.createdAtMillis,
                updatedAtMillis = meta.updatedAtMillis,
                items = emptyList(),
            )
        }
    }

    private fun serializeCollection(collection: WordCollection): JSONObject {
        return JSONObject().apply {
            put("id", collection.id)
            put("name", collection.name)
            put("createdAtMillis", collection.createdAtMillis)
            put("updatedAtMillis", collection.updatedAtMillis)
            put(
                "items",
                JSONArray().apply {
                    collection.items.take(MAX_WORDS_PER_COLLECTION).forEach { item ->
                        put(serializeSavedItem(item))
                    }
                }
            )
        }
    }

    private fun serializeSavedItem(item: SavedWordItem): JSONObject {
        return JSONObject().apply {
            put("id", item.id)
            put("word", item.word)
            put("reading", item.reading)
            put("sentence", item.sentence)
            put("contextualMeaning", item.contextualMeaning)
            put("savedAtMillis", item.savedAtMillis)
            if (!item.imageFileName.isNullOrBlank()) {
                put("imageFileName", item.imageFileName)
            }
            if (!item.imageSource.isNullOrBlank()) {
                put("imageSource", item.imageSource)
            }
            if (item.entry != null) {
                put("entry", serializeDictionaryEntry(item.entry))
            }
        }
    }

    private fun serializeDictionaryEntry(entry: CustomAiDictionaryEntry): JSONObject {
        return JSONObject().apply {
            put("word", entry.word)
            put("reading", entry.reading)
            put("partOfSpeech", entry.partOfSpeech)
            put("contextualMeaning", entry.contextualMeaning)
            put("literalMeaning", entry.literalMeaning)
            put(
                "nuance",
                JSONObject().apply {
                    put("formality", entry.nuance.formality)
                    put("frequency", entry.nuance.frequency)
                    put("dailyConversation", entry.nuance.dailyConversation)
                }
            )
            put(
                "alternativeWords",
                JSONArray().apply {
                    entry.alternativeWords.forEach { put(it) }
                }
            )
            put(
                "sentenceBreakdown",
                JSONArray().apply {
                    entry.sentenceBreakdown.forEach { chunk ->
                        put(
                            JSONObject().apply {
                                put("segment", chunk.segment)
                                put("meaning", chunk.meaning)
                            }
                        )
                    }
                }
            )
            put(
                "grammarBreakdown",
                JSONArray().apply {
                    entry.grammarBreakdown.forEach { point ->
                        put(
                            JSONObject().apply {
                                put("pattern", point.pattern)
                                put("explanation", point.explanation)
                            }
                        )
                    }
                }
            )
            put(
                "exampleSentences",
                JSONArray().apply {
                    entry.exampleSentences.forEach { put(it) }
                }
            )
        }
    }

    private fun parseIndexJson(raw: String): List<WordCollectionMeta> {
        if (raw.isBlank()) return emptyList()
        val root = JSONObject(raw)
        val colsArray = root.optJSONArray("collections") ?: return emptyList()
        val result = mutableListOf<WordCollectionMeta>()

        for (i in 0 until colsArray.length()) {
            val colObj = colsArray.optJSONObject(i) ?: continue
            val id = colObj.optString("id").trim()
            val name = colObj.optString("name").trim()
            if (id.isEmpty() || name.isEmpty()) continue
            val createdAt = colObj.optLong("createdAtMillis", 0L)
            val updatedAt = colObj.optLong("updatedAtMillis", createdAt)
            val wordsArray = colObj.optJSONArray("words") ?: JSONArray()
            val wordsSet = buildSet {
                for (j in 0 until wordsArray.length()) {
                    val w = wordsArray.optString(j).trim().lowercase()
                    if (w.isNotEmpty()) add(w)
                }
            }
            result.add(
                WordCollectionMeta(
                    id = id,
                    name = name,
                    createdAtMillis = createdAt,
                    updatedAtMillis = updatedAt,
                    wordCount = colObj.optInt("wordCount", wordsSet.size).coerceIn(0, MAX_WORDS_PER_COLLECTION),
                    wordsLowercase = wordsSet,
                )
            )
        }
        return result.take(MAX_COLLECTIONS)
    }

    private fun parseLegacyCollectionsJson(raw: String): List<WordCollection> {
        if (raw.isBlank()) return emptyList()
        val root = JSONObject(raw)
        val colsArray = root.optJSONArray("collections") ?: return emptyList()
        val result = mutableListOf<WordCollection>()
        for (i in 0 until colsArray.length()) {
            val colObj = colsArray.optJSONObject(i) ?: continue
            val parsed = parseCollectionObject(colObj)
            if (parsed.name.isNotEmpty()) {
                result.add(parsed)
            }
        }
        return result.take(MAX_COLLECTIONS)
    }

    private fun parseCollectionObject(
        colObj: JSONObject,
        fallbackMeta: WordCollectionMeta? = null,
    ): WordCollection {
        val id = colObj.optString("id").ifBlank { fallbackMeta?.id ?: UUID.randomUUID().toString() }
        val name = colObj.optString("name").trim().ifEmpty { fallbackMeta?.name.orEmpty() }
        val createdAt = colObj.optLong("createdAtMillis", fallbackMeta?.createdAtMillis ?: 0L)
        val updatedAt = colObj.optLong("updatedAtMillis", fallbackMeta?.updatedAtMillis ?: createdAt)

        val itemsArray = colObj.optJSONArray("items") ?: JSONArray()
        val items = mutableListOf<SavedWordItem>()
        for (j in 0 until itemsArray.length()) {
            val itemObj = itemsArray.optJSONObject(j) ?: continue
            val word = itemObj.optString("word").trim()
            if (word.isEmpty()) continue
            val entryObj = itemObj.optJSONObject("entry")
            items.add(
                SavedWordItem(
                    id = itemObj.optString("id").ifBlank { UUID.randomUUID().toString() },
                    word = word,
                    reading = itemObj.optString("reading", "").trim(),
                    sentence = itemObj.optString("sentence", "").trim(),
                    contextualMeaning = itemObj.optString("contextualMeaning", "").trim(),
                    entry = entryObj?.let { CustomAiDictionaryEngine.parseEntryJson(word, it) },
                    savedAtMillis = itemObj.optLong("savedAtMillis", 0L),
                    imageFileName = itemObj.optString("imageFileName", "").trim().ifEmpty { null },
                    imageSource = itemObj.optString("imageSource", "").trim().ifEmpty { null },
                )
            )
        }

        return WordCollection(
            id = id,
            name = name,
            createdAtMillis = createdAt,
            updatedAtMillis = updatedAt,
            items = items.take(MAX_WORDS_PER_COLLECTION),
        )
    }
}