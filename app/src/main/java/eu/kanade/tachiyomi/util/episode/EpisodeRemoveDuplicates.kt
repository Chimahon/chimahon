package eu.kanade.tachiyomi.util.episode

import eu.kanade.tachiyomi.data.database.models.Episode as DbEpisode
import tachiyomi.domain.episode.model.Episode as DomainEpisode

/**
 * Returns a copy of the list with duplicate episodes removed
 */
fun List<DomainEpisode>.removeDuplicates(currentEpisode: DomainEpisode): List<DomainEpisode> {
    return groupBy { it.episodeNumber }
        .map { (_, episodes) ->
            episodes.find { it.id == currentEpisode.id }
                ?: episodes.find { it.scanlator == currentEpisode.scanlator }
                ?: episodes.first()
        }
}

/**
 * Returns a copy of the list with duplicate episodes removed
 */
fun List<DbEpisode>.removeDuplicates(currentEpisode: DbEpisode): List<DbEpisode> {
    return groupBy { it.episode_number }
        .map { (_, episodes) ->
            episodes.find { it.id == currentEpisode.id }
                ?: episodes.find { it.scanlator == currentEpisode.scanlator }
                ?: episodes.first()
        }
}
