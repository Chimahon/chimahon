package eu.kanade.tachiyomi.data.track.jellyfin

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.sourcePreferences
import okhttp3.Interceptor
import okhttp3.Response
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.security.MessageDigest

class JellyfinInterceptor : Interceptor {

    private val sourceManager: AnimeSourceManager by injectLazy()

    private val apiKeys = mutableMapOf<String, String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Add the User-Agent header to the original request.
        val uaRequest = originalRequest.newBuilder()
            .header("User-Agent", "Aniyomi v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
            .build()

        // Check api keys
        if (originalRequest.url.queryParameter("api_key") != null) {
            return chain.proceed(uaRequest)
        }

        val userId = originalRequest.url.queryParameter("userId") ?: originalRequest.url.pathSegments[1]
        val apiKey = apiKeys[userId] ?: getApiKey(userId)?.also { apiKeys[userId] = it }
            ?: throw IOException("Please log in through the extension")

        val authUrl = originalRequest.url.newBuilder()
            .addQueryParameter("api_key", apiKey)
            .build()

        val authRequest = uaRequest.newBuilder().url(authUrl).build()
        return chain.proceed(authRequest)
    }

    private fun getId(name: String, versionId: Int): Long {
        val key = "$name/all/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }
            .reduce(Long::or) and Long.MAX_VALUE
    }

    private fun getApiKey(userId: String): String? {
        for (i in 1..MAX_JELLYFIN_SOURCES) {
            // The first Jellyfin source is named "Jellyfin", later ones are "Jellyfin (n)", and the
            // id is the md5 of the lowercased name, so both spellings have to be probed. The
            // extension has also shipped under several versionIds, so probe each of those too
            // instead of hardcoding one and silently resolving nothing when it changes.
            val names = if (i == 1) {
                listOf(JELLYFIN_SOURCE_NAME, "$JELLYFIN_SOURCE_NAME ($i)")
            } else {
                listOf("$JELLYFIN_SOURCE_NAME ($i)")
            }

            for (name in names) {
                for (versionId in JELLYFIN_VERSION_IDS) {
                    val preferences = (sourceManager.get(getId(name, versionId)) as? ConfigurableAnimeSource)
                        ?.sourcePreferences()
                        ?: continue

                    val sourceUserId = preferences.getString("user_id", "")
                    if (sourceUserId.isNullOrEmpty()) {
                        continue // Source not configured
                    }

                    if (sourceUserId == userId) {
                        // Returning null here keeps a blank key out of the apiKeys cache, otherwise
                        // it would be cached and replayed as an empty api_key on every later call.
                        return preferences.getString("api_key", "")?.takeIf { it.isNotBlank() }
                    }
                }
            }
        }

        return null
    }

    companion object {
        private const val JELLYFIN_SOURCE_NAME = "jellyfin"
        private val JELLYFIN_VERSION_IDS = listOf(1, 2, 3)
        private const val MAX_JELLYFIN_SOURCES = 10
    }
}
