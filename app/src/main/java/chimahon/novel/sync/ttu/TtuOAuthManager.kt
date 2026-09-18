package chimahon.novel.sync.ttu

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class TtuOAuthManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "ttu_sync_auth"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_CLIENT_SECRET = "client_secret"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
        private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val TOKEN_REFRESH_MARGIN_MS = 60000L
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var clientId: String
        get() = prefs.getString(KEY_CLIENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CLIENT_ID, value).apply()

    var clientSecret: String
        get() = prefs.getString(KEY_CLIENT_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CLIENT_SECRET, value).apply()

    val isConfigured: Boolean get() = clientId.isNotBlank()

    val isConnected: Boolean get() = accessToken.isNotBlank() || refreshToken.isNotBlank()

    private val accessToken: String
        get() = prefs.getString(KEY_ACCESS_TOKEN, "") ?: ""

    private val refreshToken: String
        get() = prefs.getString(KEY_REFRESH_TOKEN, "") ?: ""

    private val expiresAt: Long
        get() = prefs.getLong(KEY_EXPIRES_AT, 0L)

    fun configuredClient(): Pair<String, String>? {
        val cid = clientId.trim()
        val secret = clientSecret.trim()
        return if (cid.isBlank() || secret.isBlank()) null else cid to secret
    }

    fun saveClient(clientId: String, clientSecret: String) {
        this.clientId = clientId.trim()
        this.clientSecret = clientSecret.trim()
    }

    fun requestDeviceCode(): DeviceCodePrompt {
        val (cid) = configuredClient() ?: throw DriveAuthException.Unknown("OAuth client not configured")
        val body = "client_id=${java.net.URLEncoder.encode(cid, "UTF-8")}" +
            "&scope=${java.net.URLEncoder.encode(SCOPE, "UTF-8")}"
        val response = postForm(DEVICE_CODE_URL, body)
        val obj = json.parseToJsonElement(response).jsonObject
        obj["error"]?.jsonPrimitive?.content?.let { error ->
            val description = obj["error_description"]?.jsonPrimitive?.content
            Log.w("TtuSyncAuth", "Device code request returned OAuth error: $error")
            throw DriveAuthException.Unknown(description ?: error)
        }
        return DeviceCodePrompt(
            deviceCode = obj["device_code"]?.jsonPrimitive?.content ?: throw DriveAuthException.Unknown("Missing device_code"),
            userCode = obj["user_code"]?.jsonPrimitive?.content ?: throw DriveAuthException.Unknown("Missing user_code"),
            verificationUrl = obj["verification_url"]?.jsonPrimitive?.content
                ?: obj["verification_uri"]?.jsonPrimitive?.content
                ?: "https://www.google.com/device",
            expiresInSeconds = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 1800L,
            intervalSeconds = obj["interval"]?.jsonPrimitive?.content?.toLongOrNull() ?: 5L,
        )
    }

    fun pollAuthorization(prompt: DeviceCodePrompt): DriveAuthorizationPollResult {
        val client = configuredClient() ?: return DriveAuthorizationPollResult.Failed("OAuth client not configured")
        val response = try {
            val body = "client_id=${java.net.URLEncoder.encode(client.first, "UTF-8")}" +
                "&client_secret=${java.net.URLEncoder.encode(client.second, "UTF-8")}" +
                "&device_code=${java.net.URLEncoder.encode(prompt.deviceCode, "UTF-8")}" +
                "&grant_type=urn:ietf:params:oauth:grant-type:device_code"
            postForm(TOKEN_URL, body)
        } catch (e: IOException) {
            return if (e.isTransientDeviceCodePollingFailure()) {
                DriveAuthorizationPollResult.TransientNetworkFailure
            } else {
                DriveAuthorizationPollResult.Failed(e.message ?: "Authorization failed")
            }
        }
        val obj = json.parseToJsonElement(response).jsonObject
        val error = obj["error"]?.jsonPrimitive?.content
        if (error != null && error != "authorization_pending") {
            Log.w("TtuSyncAuth", "Authorization poll returned OAuth error: $error")
        }
        return when (error) {
            null -> {
                val access = obj["access_token"]?.jsonPrimitive?.content ?: return DriveAuthorizationPollResult.Failed("No access token")
                val refresh = obj["refresh_token"]?.jsonPrimitive?.content ?: ""
                val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
                prefs.edit()
                    .putString(KEY_ACCESS_TOKEN, access)
                    .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000)
                    .apply {
                        if (refresh.isNotBlank()) {
                            putString(KEY_REFRESH_TOKEN, refresh)
                        }
                    }
                    .apply()
                DriveAuthorizationPollResult.Authorized(access)
            }
            "authorization_pending" -> DriveAuthorizationPollResult.Pending
            "slow_down" -> DriveAuthorizationPollResult.SlowDown
            "access_denied" -> DriveAuthorizationPollResult.Failed("Access denied by user")
            "expired_token" -> DriveAuthorizationPollResult.Failed("Device code expired")
            "invalid_client" -> DriveAuthorizationPollResult.Failed(
                "Google OAuth client is invalid. Use a TVs and Limited Input devices client."
            )
            else -> DriveAuthorizationPollResult.Failed(error)
        }
    }

    fun getValidAccessToken(): String {
        if (accessToken.isBlank()) throw Exception("Not authenticated")
        if (System.currentTimeMillis() + TOKEN_REFRESH_MARGIN_MS < expiresAt) {
            return accessToken
        }
        return refreshAccessToken()
    }

    private fun refreshAccessToken(): String {
        val client = configuredClient() ?: throw Exception("OAuth client not configured")
        if (refreshToken.isBlank()) throw Exception("No refresh token available")
        val body = "client_id=${java.net.URLEncoder.encode(client.first, "UTF-8")}" +
            "&client_secret=${java.net.URLEncoder.encode(client.second, "UTF-8")}" +
            "&refresh_token=${java.net.URLEncoder.encode(refreshToken, "UTF-8")}" +
            "&grant_type=refresh_token"
        val response = postForm(TOKEN_URL, body)
        val obj = json.parseToJsonElement(response).jsonObject
        val newAccess = obj["access_token"]?.jsonPrimitive?.content
        if (newAccess.isNullOrBlank()) {
            val error = obj["error"]?.jsonPrimitive?.content
            val description = obj["error_description"]?.jsonPrimitive?.content
            if (error == "invalid_grant") {
                clearTokens()
            }
            throw DriveAuthException.Unknown(description ?: error ?: "Failed to refresh token")
        }
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, newAccess)
            .putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresIn * 1000)
            .apply()
        return newAccess
    }

    fun revokeAccess() {
        try {
            if (accessToken.isNotBlank()) {
                val url = URL("https://oauth2.googleapis.com/revoke?token=${java.net.URLEncoder.encode(accessToken, "UTF-8")}")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.outputStream.write("".toByteArray())
                conn.responseCode
                conn.disconnect()
            }
        } catch (_: Exception) {
        }
        clearTokens()
    }

    fun clearAuth() {
        clearTokens()
    }

    fun clearAccessToken() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    private fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    private fun postForm(urlString: String, body: String): String {
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Accept", "application/json")
            OutputStreamWriter(conn.outputStream).use { it.write(body) }
            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream
            }
            return BufferedReader(InputStreamReader(stream)).readText()
        } finally {
            conn.disconnect()
        }
    }
}

private fun Throwable.isTransientDeviceCodePollingFailure(): Boolean = this is IOException

fun nextDeviceCodePollIntervalSeconds(
    currentIntervalSeconds: Long,
    result: DriveAuthorizationPollResult,
): Long = when (result) {
    is DriveAuthorizationPollResult.SlowDown -> currentIntervalSeconds + 5L
    DriveAuthorizationPollResult.TransientNetworkFailure ->
        (currentIntervalSeconds * 2).coerceAtMost(60L)
    else -> currentIntervalSeconds
}
