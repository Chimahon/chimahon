package eu.kanade.tachiyomi.animesource.model

import fi.iki.elonen.NanoHTTPD
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Local HTTP server an extension can hand to the app to serve video/track data itself.
 *
 * Extensions create one in [eu.kanade.tachiyomi.animesource.online.AnimeHttpSource.createHttpServer]
 * and return URLs built with [PLACEHOLDER_URL]. The app starts the server and swaps the
 * placeholder for the real port before playback.
 *
 * @since extensions-lib 17
 */
open class HttpServer : NanoHTTPD(0) {
    val url: String
        get() = "http://localhost:$listeningPort"

    fun isRunning(): Boolean {
        return isRunning
    }

    @Volatile
    private var isRunning = false

    override fun start() {
        try {
            super.start()
            isRunning = true
        } catch (e: Exception) {
            logcat(LogPriority.DEBUG, e) { "Failed to start http server" }
        }
    }

    override fun stop() {
        super.stop()
        isRunning = false
    }

    companion object {
        const val PLACEHOLDER_URL = "http://localhost:1"
    }
}
