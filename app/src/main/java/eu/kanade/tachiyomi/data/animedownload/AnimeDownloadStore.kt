package eu.kanade.tachiyomi.data.animedownload

import android.content.Context
import androidx.core.content.edit
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.animedownload.model.AnimeDownload
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.episode.interactor.GetEpisode
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeDownloadStore(
    context: Context,
    private val animeSourceManager: AnimeSourceManager = Injekt.get(),
    private val json: Json = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val getEpisode: GetEpisode = Injekt.get(),
) {

    private val preferences = context.getSharedPreferences("active_anime_downloads", Context.MODE_PRIVATE)

    private var counter = 0

    fun addAll(downloads: List<AnimeDownload>) {
        preferences.edit {
            downloads.forEach { putString(getKey(it), serialize(it)) }
        }
    }

    fun remove(download: AnimeDownload) {
        preferences.edit {
            remove(getKey(download))
        }
    }

    fun removeAll(downloads: List<AnimeDownload>) {
        preferences.edit {
            downloads.forEach { remove(getKey(it)) }
        }
    }

    fun clear() {
        preferences.edit {
            clear()
        }
    }

    fun hasItems(): Boolean {
        return preferences.all.isNotEmpty()
    }

    private fun getKey(download: AnimeDownload): String {
        return download.episode.id.toString()
    }

    suspend fun restore(): List<AnimeDownload> {
        val objs = preferences.all
            .mapNotNull { it.value as? String }
            .mapNotNull { deserialize(it) }
            .sortedBy { it.order }

        val downloads = mutableListOf<AnimeDownload>()
        if (objs.isNotEmpty()) {
            val cachedAnime = mutableMapOf<Long, Anime?>()
            for (obj in objs) {
                val anime = cachedAnime.getOrPut(obj.animeId) {
                    getAnime.await(obj.animeId)
                } ?: continue
                val source = animeSourceManager.get(anime.source) as? AnimeHttpSource ?: continue
                val episode = getEpisode.await(obj.episodeId) ?: continue
                downloads.add(
                    AnimeDownload(
                        source,
                        anime,
                        episode,
                        useExternalDownloader = obj.useExternalDownloader,
                    ),
                )
            }
        }

        clear()
        return downloads
    }

    private fun serialize(download: AnimeDownload): String {
        val obj = AnimeDownloadObject(
            download.anime.id,
            download.episode.id,
            counter++,
            download.useExternalDownloader,
        )
        return json.encodeToString(obj)
    }

    private fun deserialize(string: String): AnimeDownloadObject? {
        return try {
            json.decodeFromString<AnimeDownloadObject>(string)
        } catch (e: Exception) {
            null
        }
    }
}

@Serializable
private data class AnimeDownloadObject(
    val animeId: Long,
    val episodeId: Long,
    val order: Int,
    // Absent in queues persisted before the external downloader handoff existed.
    val useExternalDownloader: Boolean = false,
)
