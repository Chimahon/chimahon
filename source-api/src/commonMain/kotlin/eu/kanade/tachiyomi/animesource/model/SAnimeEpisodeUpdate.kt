package eu.kanade.tachiyomi.animesource.model

/**
 * The result of an episode update request, so a source can return the refreshed entry together
 * with the episode list in one call.
 *
 * @since extensions-lib 17
 */
@Suppress("UNUSED")
class SAnimeEpisodeUpdate(val anime: SAnime, val episodes: List<SEpisode>)
