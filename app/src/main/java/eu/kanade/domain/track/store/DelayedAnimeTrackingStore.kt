package eu.kanade.domain.track.store

import android.content.Context
import androidx.core.content.edit
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class DelayedAnimeTrackingStore(context: Context) {
    private val preferences = context.getSharedPreferences("anime_tracking_queue", Context.MODE_PRIVATE)

    fun add(trackId: Long, lastEpisodeSeen: Double, episodeUrl: String? = null, jellyfinAnimeId: Long? = null) {
        val key = key(trackId, episodeUrl, jellyfinAnimeId)
        val previous = preferences.getFloat(key, 0f)
        if (episodeUrl != null || lastEpisodeSeen > previous) {
            logcat(LogPriority.DEBUG) { "Queuing anime track item: $trackId, last episode seen: $lastEpisodeSeen" }
            preferences.edit { putFloat(key, lastEpisodeSeen.toFloat()) }
        }
    }

    fun remove(trackId: Long, episodeUrl: String? = null, jellyfinAnimeId: Long? = null) {
        preferences.edit { remove(key(trackId, episodeUrl, jellyfinAnimeId)) }
    }

    private fun key(trackId: Long, episodeUrl: String?, jellyfinAnimeId: Long?): String = when {
        // Track inserts replace the database row ID. The anime ID remains stable across refreshes.
        jellyfinAnimeId != null -> "jellyfin:$jellyfinAnimeId" + (episodeUrl?.let { ":$it" } ?: "")
        episodeUrl != null -> "$trackId:$episodeUrl"
        else -> trackId.toString()
    }

    fun getItems(): List<DelayedAnimeTrackingItem> = preferences.all.mapNotNull { (key, value) ->
        val progress = (value as? Number)?.toFloat() ?: return@mapNotNull null
        if (key.startsWith("jellyfin:")) {
            val parts = key.split(':', limit = 3)
            DelayedAnimeTrackingItem(
                trackId = -1,
                lastEpisodeSeen = progress,
                episodeUrl = parts.getOrNull(2),
                jellyfinAnimeId = parts[1].toLongOrNull() ?: return@mapNotNull null,
            )
        } else {
            // Preserve the existing scalar queue used by ordinary trackers and older builds.
            DelayedAnimeTrackingItem(
                trackId = key.substringBefore(':').toLongOrNull() ?: return@mapNotNull null,
                lastEpisodeSeen = progress,
                episodeUrl = key.substringAfter(':', "").takeIf { it.isNotEmpty() },
            )
        }
    }

    data class DelayedAnimeTrackingItem(
        val trackId: Long,
        val lastEpisodeSeen: Float,
        val episodeUrl: String? = null,
        val jellyfinAnimeId: Long? = null,
    )
}
