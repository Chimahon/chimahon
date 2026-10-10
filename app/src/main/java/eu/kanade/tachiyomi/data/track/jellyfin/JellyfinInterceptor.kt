package eu.kanade.tachiyomi.data.track.jellyfin

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.sourcePreferences
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.security.MessageDigest

class JellyfinInterceptor : Interceptor {
    private val sourceManager: AnimeSourceManager by injectLazy()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        val usersIndex = url.pathSegments.indexOfLast { it == "Users" }
        val userId = url.queryParameter("userId") ?: url.queryParameter("UserId")
            ?: (if (usersIndex >= 0) url.pathSegments.getOrNull(usersIndex + 1) else null)
            ?: throw IOException("Jellyfin request has no user ID")
        // Login can replace the extension token, so do not cache it in the tracker.
        val apiKey = getApiKey(url, userId) ?: throw IOException("Please log in through the Jellyfin extension")
        return chain.proceed(
            request.newBuilder()
                .header("User-Agent", "Aniyomi v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")
                .url(url.newBuilder().setQueryParameter("api_key", apiKey).build())
                .build(),
        )
    }

    private fun getApiKey(url: HttpUrl, userId: String): String? {
        for (suffix in 1..10) {
            val names = if (suffix == 1) listOf("jellyfin", "jellyfin (1)") else listOf("jellyfin ($suffix)")
            for (name in names) {
                for (version in 1..3) {
                    val preferences = (sourceManager.get(jellyfinSourceId(name, version)) as? ConfigurableAnimeSource)
                        ?.sourcePreferences() ?: continue
                    if (preferences.getString("user_id", "") != userId) continue
                    val server = preferences.getString("host_url", "")?.toHttpUrlOrNull() ?: continue
                    if (!matchesJellyfinServer(url, server)) continue
                    return preferences.getString("api_key", "")?.takeIf { it.isNotBlank() } ?: continue
                }
            }
        }
        return null
    }
}

internal fun jellyfinSourceId(name: String, version: Int): Long {
    val bytes = MessageDigest.getInstance("MD5").digest("$name/all/$version".toByteArray())
    return (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
}

internal fun matchesJellyfinServer(request: HttpUrl, server: HttpUrl): Boolean {
    val prefix = server.encodedPath.trimEnd('/')
    return request.scheme == server.scheme && request.host == server.host && request.port == server.port &&
        (prefix.isEmpty() || request.encodedPath == prefix || request.encodedPath.startsWith("$prefix/"))
}
