package chimahon.anki

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

interface AnkiCardBridge {
    fun hasPermission(): Boolean
    suspend fun ensureDefaultDeckName(): String
    suspend fun ensureLapisModelName(): String
    suspend fun getDeckId(deckName: String): Long
    suspend fun findNotes(expression: String, modelName: String? = null, deckId: Long? = null): List<Long>
    suspend fun addNote(deckName: String, modelName: String, fields: Map<String, String>, tags: List<String>): Long
    suspend fun updateNoteFields(noteId: Long, fields: Map<String, String>): Any?
    suspend fun storeMedia(filename: String, data: ByteArray): String
    suspend fun storeMedia(source: AnkiSentenceAudioSource): String
    fun triggerSync()
}

class AnkiDroidBridge(
    private val context: Context,
    private val packageName: String = AnkiAppSelection.packageName,
) : AnkiCardBridge {

    private val readWritePermission: String = "$packageName.permission.READ_WRITE_DATABASE"
    private val baseUri: Uri by lazy { Uri.parse("content://$packageName.flashcards") }
    private val notesUri by lazy { Uri.withAppendedPath(baseUri, "notes") }
    private val notesV2Uri by lazy { Uri.withAppendedPath(baseUri, "notes_v2") }
    private val modelsUri by lazy { Uri.withAppendedPath(baseUri, "models") }
    private val decksUri by lazy { Uri.withAppendedPath(baseUri, "decks") }
    private val mediaUri by lazy { Uri.withAppendedPath(baseUri, "media") }

    companion object {
        private const val TAG = "AnkiDroidBridge"
        private const val SYNC_COOLDOWN_MS = 120_000L
        private var lastSyncTimeMs = 0L

        const val PERMISSION_REQUEST_CODE = 2001

        private const val NOTE_ID = "_id"
        private const val NOTE_MID = "mid"
        private const val NOTE_FLDS = "flds"
        private const val NOTE_TAGS = "tags"
        private const val NOTE_CSUM = "csum"

        private const val MODEL_ID = "_id"
        private const val MODEL_NAME = "name"
        private const val MODEL_FIELD_NAMES = "field_names"
        private const val MODEL_NUM_CARDS = "num_cards"
        private const val MODEL_CSS = "css"
        private const val MODEL_DECK_ID = "deck_id"
        private const val MODEL_SORT_FIELD_INDEX = "sort_field_index"

        private const val DECK_ID = "deck_id"
        private const val DECK_NAME = "deck_name"

        private const val CARD_TEMPLATE_NAME = "card_template_name"
        private const val CARD_TEMPLATE_QUESTION_FORMAT = "question_format"
        private const val CARD_TEMPLATE_ANSWER_FORMAT = "answer_format"

        private const val MEDIA_FILE_URI = "file_uri"
        private const val MEDIA_PREFERRED_NAME = "preferred_name"

        private const val FIELD_SEPARATOR = "\u001f"
    }

    private data class ModelInfo(
        val name: String,
        val fields: List<String>,
    )

    // ==========================================================================
    // Public API
    // ==========================================================================

    suspend fun isAnkiDroidInstalled(): Boolean = withContext(Dispatchers.IO) {
        AnkiAppSelection.isInstalled(context, packageName)
    }

    override fun hasPermission(): Boolean {
        return context.checkSelfPermission(readWritePermission) == PackageManager.PERMISSION_GRANTED
    }

    fun requestPermission(activity: android.app.Activity) {
        activity.requestPermissions(arrayOf(readWritePermission), PERMISSION_REQUEST_CODE)
    }

    suspend fun deckNames(): List<String> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val names = mutableListOf<String>()
        try {
            context.contentResolver.query(
                decksUri,
                arrayOf(DECK_NAME),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    c.getString(0)?.let(names::add)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission lost during deckNames query", e)
        } catch (e: Exception) {
            Log.e(TAG, "deckNames", e)
        }
        names
    }

    suspend fun modelNames(): List<String> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val names = mutableListOf<String>()
        try {
            context.contentResolver.query(
                modelsUri,
                arrayOf(MODEL_NAME),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    c.getString(0)?.let(names::add)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission lost during modelNames query", e)
        } catch (e: Exception) {
            Log.e(TAG, "modelNames", e)
        }
        names
    }

    suspend fun modelFieldNames(modelName: String): List<String> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        val result = mutableListOf<String>()
        try {
            context.contentResolver.query(
                modelsUri,
                arrayOf(MODEL_NAME, MODEL_FIELD_NAMES),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val dbModelName = c.getString(0) ?: continue
                    if (dbModelName != modelName) continue

                    val rawData = c.getString(1) ?: continue
                    val parsed = parseFieldNames(rawData)
                    if (parsed.size > result.size) {
                        result.clear()
                        result.addAll(parsed)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission lost during modelFieldNames query", e)
        } catch (e: Exception) {
            Log.e(TAG, "modelFieldNames", e)
        }
        result
    }

    override suspend fun ensureDefaultDeckName(): String = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext ""
        val defaultName = LapisPreset.DEFAULT_DECK_NAME
        findDeckIdOrNull(defaultName) ?: createDeck(defaultName)
        defaultName
    }

    override suspend fun ensureLapisModelName(): String = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext ""

        val models = readModelInfos()
        if (models.any { it.name == LapisPreset.MODEL_NAME }) {
            LapisPreset.MODEL_NAME
        } else {
            createLapisModel(LapisPreset.MODEL_NAME)
            LapisPreset.MODEL_NAME
        }
    }

    override suspend fun getDeckId(deckName: String): Long = withContext(Dispatchers.IO) {
        findDeckId(deckName)
    }

    override suspend fun findNotes(expression: String, modelName: String?, deckId: Long?): List<Long> =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext emptyList()
            val ids = mutableListOf<Long>()
            try {
                val csum = fieldChecksum(expression)
                context.contentResolver.query(
                    notesV2Uri,
                    arrayOf(NOTE_ID, NOTE_MID),
                    "$NOTE_CSUM=?",
                    arrayOf(csum.toString()),
                    null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val nid = c.getLong(0)

                        if (deckId != null) {
                            if (!isNoteInDeck(nid, deckId)) continue
                        }

                        ids.add(nid)
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Permission lost during findNotes", e)
            } catch (e: Exception) {
                Log.e(TAG, "findNotes", e)
            }
            ids
        }

    override suspend fun addNote(
        deckName: String,
        modelName: String,
        fields: Map<String, String>,
        tags: List<String>,
    ): Long = withContext(Dispatchers.IO) {
        if (!hasPermission()) throw Exception("AnkiDroid permission not granted")

        val deckId = findDeckId(deckName)
        val modelId = findModelId(modelName)
        val fieldNames = getModelFields(modelId)

        val values = Array(fieldNames.size) { i ->
            fields[fieldNames[i]] ?: ""
        }

        val tagSet = tags.toMutableSet()

        val cv = ContentValues().apply {
            put(NOTE_MID, modelId)
            put(NOTE_FLDS, values.joinToString(FIELD_SEPARATOR))
            if (tagSet.isNotEmpty()) put(NOTE_TAGS, tagSet.joinToString(" "))
        }

        val result = try {
            context.contentResolver.insert(notesUri, cv)
        } catch (e: Exception) {
            null
        } ?: throw Exception("AnkiDroid insert failed")

        val newNoteId = result.lastPathSegment?.toLongOrNull()
            ?: throw Exception("Failed to parse note ID from insert result")

        moveCardsToDeck(newNoteId, deckId)
        newNoteId
    }

    override suspend fun updateNoteFields(noteId: Long, fields: Map<String, String>) =
        withContext(Dispatchers.IO) {
            val uri = Uri.withAppendedPath(notesUri, noteId.toString())
            context.contentResolver.query(
                uri,
                arrayOf(NOTE_MID, NOTE_FLDS),
                null,
                null,
                null,
            )?.use { c ->
                if (!c.moveToFirst()) return@withContext

                val modelId = c.getLong(0)
                val oldFields = splitFields(c.getString(1))
                val fieldNames = getModelFields(modelId)
                val newFields = Array(fieldNames.size) { i ->
                    when {
                        fields.containsKey(fieldNames[i]) -> fields[fieldNames[i]]!!
                        i < oldFields.size -> oldFields[i]
                        else -> ""
                    }
                }

                val cv = ContentValues().apply {
                    put(NOTE_FLDS, newFields.joinToString(FIELD_SEPARATOR))
                }
                context.contentResolver.update(uri, cv, null, null)
            }
        }

    fun guiBrowse(query: String) {
        try {
            val uri = Uri.parse("anki://x-callback-url/browser?search=${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(packageName)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "guiBrowse", e)
        }
    }

    fun guiEditNote(noteId: Long) {
        // AnkiDroid's NoteEditor has no public intent filter for external apps.
        // The only supported external navigation is the Card Browser filtered by note ID.
        guiBrowse("nid:$noteId")
    }

    fun guiBrowseNotes(noteIds: List<Long>) {
        val ids = noteIds.filter { it > 0 }.distinct()
        if (ids.isNotEmpty()) guiBrowse(ids.joinToString(" or ") { "nid:$it" })
    }

    override suspend fun storeMedia(filename: String, data: ByteArray): String =
        withContext(Dispatchers.IO) {
            saveMediaBytes(filename, data)
        }

    override suspend fun storeMedia(source: AnkiSentenceAudioSource): String =
        withContext(Dispatchers.IO) {
            val baseName = sanitizePreferredBaseName(source.preferredBaseName)
            val extension = AnkiSentenceAudioSource.safeExtension(source.extension, "m4a")
            saveMediaBytes("$baseName.$extension", source.data)
        }

    suspend fun storeMediaFromBase64(filename: String, base64: String): String =
        storeMedia(filename, Base64.decode(base64, Base64.NO_WRAP))

    suspend fun storeMediaFromUrl(filename: String, urlString: String): String =
        withContext(Dispatchers.IO) {
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("HTTP ${conn.responseCode}")
                }
                val buffer = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    var r: Int
                    while (input.read(buf).also { r = it } != -1) {
                        buffer.write(buf, 0, r)
                    }
                }
                saveMediaBytes(filename, buffer.toByteArray())
            } finally {
                conn.disconnect()
            }
        }

    suspend fun storeMediaFromFile(filename: String, filePath: String): String =
        withContext(Dispatchers.IO) {
            val file = File(filePath)
            if (!file.exists()) throw Exception("File not found: $filePath")
            saveMediaBytes(filename, file.readBytes())
        }

    override fun triggerSync() {
        try {
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - lastSyncTimeMs
            if (elapsed < SYNC_COOLDOWN_MS) {
                Log.d(TAG, "triggerSync: skipped (cooldown ${(SYNC_COOLDOWN_MS - elapsed) / 1000}s remaining)")
                return
            }

            context.packageManager.getPackageInfo(packageName, 0)

            val intent = Intent("com.ichi2.anki.DO_SYNC").apply {
                setPackage(packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
            lastSyncTimeMs = now
            Log.d(TAG, "triggerSync: sent")
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "triggerSync: AnkiDroid not installed")
        } catch (e: SecurityException) {
            Log.w(TAG, "triggerSync: permission denied: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "triggerSync: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ==========================================================================
    // Internal helpers
    // ==========================================================================

    private fun findDeckId(deckName: String): Long {
        findDeckIdOrNull(deckName)?.let { return it }
        throw Exception("Deck '$deckName' not found")
    }

    private fun findDeckIdOrNull(deckName: String): Long? {
        try {
            context.contentResolver.query(
                decksUri,
                arrayOf(DECK_ID, DECK_NAME),
                null,
                null,
                null,
            )?.use { c ->
                val idIdx = c.getColumnIndex(DECK_ID)
                val nameIdx = c.getColumnIndex(DECK_NAME)
                if (idIdx == -1 || nameIdx == -1) throw Exception("Missing deck columns")
                while (c.moveToNext()) {
                    if (c.getString(nameIdx) == deckName) return c.getLong(idIdx)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "findDeckId failed", e)
        }
        return null
    }

    private fun createDeck(deckName: String): Long {
        val cv = ContentValues().apply {
            put(DECK_NAME, deckName)
        }
        val uri = context.contentResolver.insert(decksUri, cv)
            ?: throw Exception("Failed to create deck '$deckName'")
        return uri.lastPathSegment?.toLongOrNull()
            ?: throw Exception("Failed to parse deck ID for '$deckName'")
    }

    private fun findModelId(modelName: String): Long {
        try {
            context.contentResolver.query(
                modelsUri,
                arrayOf(MODEL_ID, MODEL_NAME),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == modelName) return c.getLong(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "findModelId failed", e)
        }
        throw Exception("Model '$modelName' not found")
    }


    private fun getModelFields(modelId: Long): List<String> {
        try {
            context.contentResolver.query(
                modelsUri,
                arrayOf(MODEL_ID, MODEL_FIELD_NAMES),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getLong(0) == modelId) {
                        val rawData = c.getString(1) ?: continue
                        return parseFieldNames(rawData)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getModelFields failed", e)
        }
        throw Exception("Model fields not found for ID: $modelId")
    }

    private fun readModelInfos(): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()
        try {
            context.contentResolver.query(
                modelsUri,
                arrayOf(MODEL_NAME, MODEL_FIELD_NAMES),
                null,
                null,
                null,
            )?.use { c ->
                val nameIdx = c.getColumnIndex(MODEL_NAME)
                val fieldsIdx = c.getColumnIndex(MODEL_FIELD_NAMES)
                if (nameIdx == -1 || fieldsIdx == -1) return@use
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx) ?: continue
                    val fields = parseFieldNames(c.getString(fieldsIdx) ?: "")
                    models += ModelInfo(name, fields)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "readModelInfos failed", e)
        }
        return models
    }

    private fun createLapisModel(modelName: String): Long {
        val css = readLapisAsset("styling.css")
        val front = readLapisAsset("front.html")
        val back = readLapisAsset("back.html")
        val deckId = findDeckIdOrNull(LapisPreset.DEFAULT_DECK_NAME)

        val modelValues = ContentValues().apply {
            put(MODEL_NAME, modelName)
            put(MODEL_FIELD_NAMES, LapisPreset.fields.joinToString(FIELD_SEPARATOR))
            put(MODEL_NUM_CARDS, 1)
            put(MODEL_CSS, css)
            put(MODEL_SORT_FIELD_INDEX, 0)
            if (deckId != null) put(MODEL_DECK_ID, deckId)
        }

        val modelUri = context.contentResolver.insert(modelsUri, modelValues)
            ?: throw Exception("Failed to create Anki model '$modelName'")
        val modelId = modelUri.lastPathSegment?.toLongOrNull()
            ?: throw Exception("Failed to parse Anki model ID for '$modelName'")

        val templatesUri = Uri.withAppendedPath(modelUri, "templates")
        val firstTemplateUri = Uri.withAppendedPath(templatesUri, "0")
        val templateValues = ContentValues().apply {
            put(CARD_TEMPLATE_NAME, "Card 1")
            put(CARD_TEMPLATE_QUESTION_FORMAT, front)
            put(CARD_TEMPLATE_ANSWER_FORMAT, back)
        }
        context.contentResolver.update(firstTemplateUri, templateValues, null, null)

        return modelId
    }

    private fun readLapisAsset(filename: String): String =
        context.assets.open("lapis/$filename").use { input ->
            input.bufferedReader(StandardCharsets.UTF_8).readText()
        }

    private fun parseFieldNames(rawData: String): List<String> {
        val trimmed = rawData.trim()
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            try {
                val json = org.json.JSONArray(trimmed)
                return (0 until json.length()).map { i ->
                    val item = json.get(i)
                    if (item is org.json.JSONObject) item.optString("name") else item.toString()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Field parse error", e)
            }
        }
        return splitFields(rawData)
    }

    private fun moveCardsToDeck(noteId: Long, targetDeckId: Long) {
        val cardsUri = Uri.withAppendedPath(notesUri, "$noteId/cards")
        try {
            context.contentResolver.query(
                cardsUri,
                arrayOf("ord", "deck_id"),
                null,
                null,
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val ord = c.getInt(0)
                    val currentDeckId = c.getLong(1)
                    if (currentDeckId != targetDeckId) {
                        val cardUri = Uri.withAppendedPath(cardsUri, ord.toString())
                        val cv = ContentValues().apply {
                            put("deck_id", targetDeckId)
                        }
                        context.contentResolver.update(cardUri, cv, null, null)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "moveCardsToDeck", e)
        }
    }

    private fun saveMediaBytes(name: String, data: ByteArray): String {
        val mediaDir = File(context.getExternalFilesDir(null), "anki_media")
        mediaDir.mkdirs()

        val file = File(mediaDir, name)
        file.writeBytes(data)

        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file,
        )

        context.grantUriPermission(
            packageName,
            contentUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )

        val mediaCv = ContentValues().apply {
            put(MEDIA_FILE_URI, contentUri.toString())
            put(MEDIA_PREFERRED_NAME, name.replace(Regex("\\.[^.]*$"), ""))
        }

        val res = context.contentResolver.insert(mediaUri, mediaCv)
        file.delete()

        if (res != null) {
            return File(res.path!!).name
        }

        throw Exception("AnkiDroid failed to copy the media")
    }

    private fun sanitizePreferredBaseName(value: String): String =
        value
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .trim('_')
            .take(96)
            .ifBlank { "chimahon_media" }

    private fun isNoteInDeck(noteId: Long, deckId: Long): Boolean {
        val noteUri = Uri.withAppendedPath(notesUri, noteId.toString())
        val cardsUri = Uri.withAppendedPath(noteUri, "cards")
        var inDeck = false
        try {
            context.contentResolver.query(
                cardsUri,
                arrayOf("deck_id"),
                null,
                null,
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getLong(0) == deckId) {
                        inDeck = true
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "isNoteInDeck", e)
        }
        return inDeck
    }

    private val STYLE_PATTERN = Regex("(?s)<style.*?>.*?</style>")
    private val SCRIPT_PATTERN = Regex("(?s)<script.*?>.*?</script>")
    private val TAG_PATTERN = Regex("<.*?>")
    private val IMG_PATTERN = Regex("<img src=[\"']?([^\"'>]+)[\"']? ?/?>")
    private val HTML_ENTITIES_PATTERN = Regex("&#?\\w+;")

    private fun entsToTxt(htmlText: String): String {
        val htmlReplaced = htmlText.replace("&nbsp;", " ")
        val sb = StringBuffer()
        val matcher = java.util.regex.Pattern.compile("&#?\\w+;").matcher(htmlReplaced)
        while (matcher.find()) {
            val entity = matcher.group()
            val decoded = android.text.Html.fromHtml(entity, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            matcher.appendReplacement(sb, decoded)
        }
        matcher.appendTail(sb)
        return sb.toString()
    }

    private fun stripHTML(s: String): String {
        var strRep = STYLE_PATTERN.replace(s, "")
        strRep = SCRIPT_PATTERN.replace(strRep, "")
        strRep = TAG_PATTERN.replace(strRep, "")
        return entsToTxt(strRep)
    }

    private fun stripHTMLMedia(s: String): String {
        val replacedImg = IMG_PATTERN.replace(s) { matchResult ->
            " ${matchResult.groupValues[1]} "
        }
        return stripHTML(replacedImg)
    }

    private fun fieldChecksum(data: String): Long {
        val SHA1_ZEROES = "0000000000000000000000000000000000000000"
        val strippedData = stripHTMLMedia(data)

        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val digest = md.digest(strippedData.toByteArray(StandardCharsets.UTF_8))
            val bigInteger = BigInteger(1, digest)
            var result = bigInteger.toString(16)

            if (result.length < 40) {
                result = SHA1_ZEROES.substring(0, SHA1_ZEROES.length - result.length) + result
            }
            result.substring(0, 8).toLong(16)
        } catch (e: Exception) {
            Log.e(TAG, "Error making field checksum", e)
            0L
        }
    }

    private fun splitFields(str: String): List<String> =
        str.split(FIELD_SEPARATOR)

}
