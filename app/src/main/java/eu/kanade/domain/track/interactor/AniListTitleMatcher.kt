package eu.kanade.domain.track.interactor

internal object AniListTitleMatcher {

    private val nonAlphanumeric = Regex("[\\W_]+")

    fun normalize(title: String): String = title.lowercase().replace(nonAlphanumeric, "")

    fun matches(a: String, b: String): Boolean {
        val normalized = normalize(a)
        return normalized.isNotEmpty() && normalized == normalize(b)
    }
}
