package eu.kanade.tachiyomi.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Remote version lookup for the downloadable OCR models.
 *
 * Both payloads ship in the same GitHub release: `models.zip` (Lens) and
 * `paddle-ocr.zip` (Paddle), produced by chimahon-local-models'
 * `release-models.yml`, which is dispatched manually with a `vX.Y` tag. One
 * version therefore covers both sets.
 *
 * The pinned constant in [ModelDownloader] is the floor so a fresh or offline
 * install still resolves to a real release. This endpoint is what lets a newly
 * published release reach an already-installed app: cut the release and the
 * next check moves forward without an app update.
 *
 * Best-effort by design — a failure resolves to null and the caller keeps the
 * pinned version, so a rate-limited or broken endpoint can never block a
 * download. Releases are created in dispatch order, not sorted, so the caller
 * compares versions and must never move backwards.
 */
class ModelVersionResolver(
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Latest release tag without the leading "v" (e.g. "2.6"), or null when the
     * endpoint is unreachable or answers with no usable tag.
     */
    suspend fun latestOcrVersion(): String? = runCatching {
        val request = Request.Builder()
            .url(LATEST_RELEASE)
            .header("Accept", "application/vnd.github+json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string() ?: return@use null
            json.parseToJsonElement(body).jsonObject["tag_name"]
                ?.jsonPrimitive?.content
                ?.removePrefix("v")
                ?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private companion object {
        const val LATEST_RELEASE =
            "https://api.github.com/repos/Chimahon/chimahon-local-models/releases/latest"
    }
}