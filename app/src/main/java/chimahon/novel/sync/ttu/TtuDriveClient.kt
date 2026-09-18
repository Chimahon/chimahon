package chimahon.novel.sync.ttu

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

class TtuDriveClient(
    context: Context,
    private val authManager: TtuOAuthManager,
) {
    private companion object {
        const val DRIVE_API = "https://www.googleapis.com/drive/v3"
        const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val ROOT_FOLDER_NAME = "ttu-reader-data"
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        const val CACHE_NAME = "google-drive-sync-cache"
        const val ROOT_FOLDER_ID_KEY = "rootFolderId"
        const val TITLE_FOLDER_IDS_KEY = "titleFolderIds"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cachePreferences = context.applicationContext.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE)
    private var rootFolderId: String? = cachePreferences.getString(ROOT_FOLDER_ID_KEY, null)
    private var titleToFolderId: MutableMap<String, String> = cachePreferences
        .getStringSet(TITLE_FOLDER_IDS_KEY, emptySet())
        ?.mapNotNull { encoded ->
            val separator = encoded.indexOf('=')
            if (separator <= 0) null
            else encoded.substring(0, separator).urlQueryDecoded() to
                encoded.substring(separator + 1).urlQueryDecoded()
        }
        ?.toMap()
        ?.toMutableMap()
        ?: mutableMapOf()

    fun findOrCreateRootFolder(): String {
        rootFolderId?.let { return it }
        val existing = findFolder(parentId = "root", ROOT_FOLDER_NAME)
        if (existing != null) {
            rootFolderId = existing
            cachePreferences.edit().putString(ROOT_FOLDER_ID_KEY, existing).apply()
            return existing
        }
        return createFolder("root", ROOT_FOLDER_NAME).also {
            rootFolderId = it
            cachePreferences.edit().putString(ROOT_FOLDER_ID_KEY, it).apply()
        }
    }

    fun findOrCreateBookFolder(rootId: String, folderName: String, coverDataProvider: (() -> ByteArray?)? = null): String {
        titleToFolderId[folderName]?.let { return it }
        val existing = findFolder(parentId = rootId, folderName)
        if (existing != null) {
            cacheBookFolder(folderName, existing)
            return existing
        }
        val folderId = createFolder(parentId = rootId, folderName).also {
            cacheBookFolder(folderName, it)
        }
        val coverData = coverDataProvider?.invoke()
        if (coverData != null) {
            try {
                uploadCoverImage(folderId, coverData)
            } catch (_: Exception) {
            }
        }
        return folderId
    }

    fun listSyncFiles(folderId: String): DriveSyncFiles {
        val query = "trashed=false and '${folderId.driveQueryLiteral()}' in parents and mimeType != '$FOLDER_MIME_TYPE'"
        val files = listFiles(query)
        return DriveSyncFiles(
            progress = files.latestTtuFile("progress_", TtuSyncRules::parseProgressTimestampMillis),
            statistics = files.latestTtuFile("statistics_", TtuSyncRules::parseStatisticsTimestampMillis),
        )
    }

    fun downloadFile(fileId: String): String {
        val data = performRequest(
            url = "$DRIVE_API/files/${fileId.urlPathSegment()}?alt=media",
            method = "GET",
        )
        return data.decodeToString()
    }

    fun uploadFile(parentId: String, fileName: String, content: String, mimeType: String = "application/json") {
        uploadMultipartFile(
            parentId = parentId,
            fileId = null,
            name = fileName,
            content = content.toByteArray(StandardCharsets.UTF_8),
            contentType = mimeType,
        )
    }

    fun updateFile(fileId: String, fileName: String, content: String, mimeType: String = "application/json") {
        uploadMultipartFile(
            parentId = null,
            fileId = fileId,
            name = fileName,
            content = content.toByteArray(StandardCharsets.UTF_8),
            contentType = mimeType,
        )
    }

    fun deleteFile(fileId: String) {
        performRequest(url = "$DRIVE_API/files/${fileId.urlPathSegment()}", method = "DELETE")
    }

    fun clearCache() {
        rootFolderId = null
        titleToFolderId.clear()
        cachePreferences.edit()
            .remove(ROOT_FOLDER_ID_KEY)
            .remove(TITLE_FOLDER_IDS_KEY)
            .apply()
    }

    private fun findFolder(parentId: String, name: String): String? {
        val escapedName = name.replace("'", "\\'")
        val query = "trashed=false and '$parentId' in parents and mimeType='$FOLDER_MIME_TYPE' and name='$escapedName'"
        val files = listFiles(query)
        return files.firstOrNull()?.id
    }

    private fun createFolder(parentId: String, name: String): String {
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER_MIME_TYPE)
            put("parents", JsonArray(listOf(JsonPrimitive(parentId))))
        }
        val url = "$DRIVE_API/files?fields=id"
        val data = performRequest(url = url, method = "POST", body = metadata.toString().toByteArray(), contentType = "application/json")
        return json.parseToJsonElement(data.decodeToString()).jsonObject["id"]?.jsonPrimitive?.content
            ?: throw Exception("Failed to create folder: no id in response")
    }

    private fun listFiles(query: String): List<DriveFile> {
        val files = mutableListOf<DriveFile>()
        val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        var pageToken: String? = null
        do {
            val tokenQuery = pageToken?.let {
                "&pageToken=${URLEncoder.encode(it, StandardCharsets.UTF_8.name())}"
            }.orEmpty()
            val url = "$DRIVE_API/files?q=$encodedQuery&fields=nextPageToken,files(id,name)&pageSize=100$tokenQuery"
            val data = performRequest(url = url, method = "GET")
            val obj = json.parseToJsonElement(data.decodeToString()).jsonObject
            files += obj["files"]?.jsonArray?.map { element ->
                val fileObj = element.jsonObject
                DriveFile(
                    id = fileObj["id"]?.jsonPrimitive?.content ?: "",
                    name = fileObj["name"]?.jsonPrimitive?.content ?: "",
                )
            }.orEmpty()
            pageToken = obj["nextPageToken"]?.jsonPrimitive?.content
        } while (!pageToken.isNullOrBlank())
        return files
    }

    private fun uploadMultipartFile(
        parentId: String?,
        fileId: String?,
        name: String?,
        content: ByteArray,
        contentType: String,
    ) {
        val metadata = buildJsonObject {
            name?.let { put("name", it) }
            if (fileId == null && parentId != null) {
                put("parents", JsonArray(listOf(JsonPrimitive(parentId))))
            }
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val boundary = UUID.randomUUID().toString()
        val url = if (fileId == null) {
            "$DRIVE_UPLOAD/files?uploadType=multipart"
        } else {
            "$DRIVE_UPLOAD/files/${fileId.urlPathSegment()}?uploadType=multipart"
        }
        val body = ByteArrayOutputStream().apply {
            writeUtf8("--$boundary\r\n")
            writeUtf8("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            write(metadata)
            writeUtf8("\r\n--$boundary\r\n")
            writeUtf8("Content-Type: $contentType\r\n\r\n")
            write(content)
            writeUtf8("\r\n--$boundary--\r\n")
        }.toByteArray()
        performRequest(
            url = url,
            method = if (fileId == null) "POST" else "PATCH",
            body = body,
            contentType = "multipart/related; boundary=$boundary",
        )
    }

    private fun uploadCoverImage(folderId: String, imageBytes: ByteArray) {
        val metadata = TtuSyncRules.coverMetadata(imageBytes)
        uploadMultipartFile(
            parentId = folderId,
            fileId = null,
            name = "cover_1_6.${metadata.extension}",
            content = imageBytes,
            contentType = metadata.mimeType,
        )
    }

    private fun performRequest(
        url: String,
        method: String,
        body: ByteArray? = null,
        contentType: String? = null,
        retry: Boolean = true,
    ): ByteArray {
        val token = authManager.getValidAccessToken()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer $token")
            contentType?.let { setRequestProperty("Content-Type", it) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body) }
            }
            connectTimeout = 30000
            readTimeout = 30000
        }
        val statusCode = connection.responseCode
        if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED && retry) {
            connection.disconnect()
            authManager.clearAccessToken()
            return performRequest(url, method, body, contentType, retry = false)
        }
        val responseBytes = try {
            if (statusCode >= 400) {
                connection.errorStream?.use { it.readBytes() } ?: ByteArray(0)
            } else {
                connection.inputStream.use { it.readBytes() }
            }
        } finally {
            connection.disconnect()
        }
        if (statusCode >= 400) {
            val message = responseBytes.driveErrorMessage() ?: "Request failed with status $statusCode"
            if (statusCode == 404) throw DriveFileNotFoundException()
            throw Exception("Drive API error $statusCode: $message")
        }
        return responseBytes
    }

    private fun cacheBookFolder(sanitizedTitle: String, folderId: String) {
        titleToFolderId[sanitizedTitle] = folderId
        cachePreferences.edit()
            .putStringSet(
                TITLE_FOLDER_IDS_KEY,
                titleToFolderId.mapTo(mutableSetOf()) {
                    "${it.key.urlQueryComponent()}=${it.value.urlQueryComponent()}"
                },
            )
            .apply()
    }

    private fun ByteArray.driveErrorMessage(): String? =
        runCatching {
            json.parseToJsonElement(decodeToString()).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull()
}

private fun String.urlQueryComponent(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name())

private fun String.urlQueryDecoded(): String =
    URLDecoder.decode(this, StandardCharsets.UTF_8.name())

private fun String.urlPathSegment(): String =
    split("/").joinToString("/") { it.urlQueryComponent() }

private fun String.driveQueryLiteral(): String =
    replace("'", "\\'")

private fun ByteArrayOutputStream.writeUtf8(text: String) {
    write(text.toByteArray(StandardCharsets.UTF_8))
}

class DriveFileNotFoundException : Exception("File not found")

private fun List<DriveFile>.latestTtuFile(prefix: String, timestampMillis: (DriveFile) -> Long?): DriveFile? =
    filter { it.name.startsWith(prefix) }
        .maxByOrNull { timestampMillis(it) ?: Long.MIN_VALUE }
