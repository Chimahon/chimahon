package eu.kanade.tachiyomi.ui.player.utils

import tachiyomi.domain.episode.model.Episode

/** Source lists can arrive in either direction; numbered playback always advances chronologically. */
internal fun List<Episode>.inPlaybackOrder(useEpisodeNumbers: Boolean): List<Episode> =
    if (useEpisodeNumbers && all { it.isRecognizedNumber && it.episodeNumber.isFinite() }) {
        sortedBy { it.episodeNumber }
    } else {
        this
    }
