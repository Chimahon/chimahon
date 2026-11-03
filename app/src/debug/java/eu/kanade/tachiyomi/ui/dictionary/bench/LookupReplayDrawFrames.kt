package eu.kanade.tachiyomi.ui.dictionary.bench

/** Frame identity, not a clock: a late callback from a blank frame cannot claim a content commit. */
internal class LookupReplayDrawFrames {
    private var current = 0L
    var contentFrame: Long? = null
        private set

    fun beginFrame(): Long = ++current

    fun contentDrawn() {
        if (current > 0 && contentFrame == null) contentFrame = current
    }

    fun containsFirstContent(frame: Long): Boolean = frame > 0 && contentFrame == frame
}
