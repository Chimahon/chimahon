package eu.kanade.tachiyomi.torrentutils

import eu.kanade.tachiyomi.torrentServer.TorrentServerApi
import eu.kanade.tachiyomi.torrentutils.model.DeadTorrentException
import eu.kanade.tachiyomi.torrentutils.model.TorrentFile
import eu.kanade.tachiyomi.torrentutils.model.TorrentInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException

object TorrentUtils {
    /**
     * extensions-lib 17 made this `suspend`, so its descriptor now carries a Continuation
     * parameter. Both descriptors have to exist: extensions built against 17 call the suspend
     * one, older ones the blocking one.
     */
    suspend fun getTorrentInfo(
        url: String,
        title: String,
    ): TorrentInfo = withContext(Dispatchers.IO) {
        loadTorrentInfo(url, title)
    }

    // Compatibility for older non-blocking calls
    @Deprecated(
        message = "This method exists only for bytecode compatibility with older extensions. Do not use.",
        level = DeprecationLevel.HIDDEN,
    )
    @JvmName("getTorrentInfo")
    fun blockingShimForGetTorrentInfo(
        url: String,
        title: String,
    ): TorrentInfo {
        return runBlocking(Dispatchers.IO) {
            loadTorrentInfo(url, title)
        }
    }

    private fun loadTorrentInfo(url: String, title: String): TorrentInfo {
        try {
            val torrent = TorrentServerApi.addTorrent(url, title, "", "", false)
            return TorrentInfo(
                torrent.title,
                torrent.file_stats?.map { file ->
                    TorrentFile(file.path, file.id ?: 0, file.length, torrent.hash!!, torrent.trackers ?: emptyList())
                } ?: emptyList(),
                torrent.hash!!,
                torrent.torrent_size!!,
                torrent.trackers ?: emptyList(),
            )
        } catch (e: SocketTimeoutException) {
            throw DeadTorrentException()
        }
    }
}
