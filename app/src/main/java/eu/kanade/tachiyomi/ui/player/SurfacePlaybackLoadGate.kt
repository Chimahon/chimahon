package eu.kanade.tachiyomi.ui.player

internal class SurfacePlaybackLoadGate(
    private val loadNow: (url: String, options: String) -> Boolean,
) {
    private var isSurfaceReady = false
    private var isClosed = false
    private var pendingLoad: Pair<String, String>? = null

    fun load(url: String, options: String = "") {
        if (isClosed) return

        if (isSurfaceReady && loadNow(url, options)) {
            pendingLoad = null
        } else {
            pendingLoad = url to options
        }
    }

    fun onSurfaceCreated() {
        if (isClosed) return

        isSurfaceReady = true
        retryPending()
    }

    fun retryPending() {
        if (isClosed || !isSurfaceReady) return

        val (url, options) = pendingLoad ?: return
        if (loadNow(url, options)) {
            pendingLoad = null
        }
    }

    fun onSurfaceDestroyed() {
        isSurfaceReady = false
    }

    fun close() {
        isClosed = true
        isSurfaceReady = false
        pendingLoad = null
    }
}
