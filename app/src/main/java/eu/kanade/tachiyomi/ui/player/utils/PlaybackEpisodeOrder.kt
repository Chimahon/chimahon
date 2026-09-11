package eu.kanade.tachiyomi.ui.player.utils

import eu.kanade.tachiyomi.data.database.models.Episode

/** Source lists can arrive in either direction; numbered playback always advances chronologically. */
internal fun List<Episode>.inPlaybackOrder(useEpisodeNumbers: Boolean): List<Episode> =
    if (useEpisodeNumbers && all { it.episode_number >= 0f && it.episode_number.isFinite() }) {
        sortedBy { it.episode_number }
    } else {
        this
    }
