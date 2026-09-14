package exh.util

import tachiyomi.domain.entries.anime.model.Anime

/**
 * Tag-based lewd check for anime entries, mirroring [Manga.isLewd] minus the
 * manga-specific source heuristics (EH-based / nHentai / hentai extension id
 * ranges), which do not apply to anime sources.
 */
fun Anime.isLewd(): Boolean {
    return genre.orEmpty().any { tag -> isHentaiTag(tag) }
}

private fun isHentaiTag(tag: String): Boolean {
    return tag.contains("hentai", true) ||
        tag.contains("adult", true) ||
        tag.contains("smut", true) ||
        tag.contains("lewd", true) ||
        tag.contains("nsfw", true) ||
        tag.contains("erotica", true) ||
        tag.contains("pornographic", true) ||
        tag.contains("mature", true) ||
        tag.contains("18+", true)
}
