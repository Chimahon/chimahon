package eu.kanade.tachiyomi.ui.player.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.episode.model.Episode

class PlaybackEpisodeOrderTest {
    private fun episodes(vararg numbers: Double) = numbers.mapIndexed { index, number ->
        Episode.create().copy(id = index.toLong(), episodeNumber = number)
    }

    @Test
    fun `next advances to a higher episode for either source direction`() {
        for (playlist in listOf(episodes(300.0, 301.0, 302.0), episodes(302.0, 301.0, 300.0))) {
            val ordered = playlist.inPlaybackOrder(true)
            val current = ordered.indexOfFirst { it.episodeNumber == 301.0 }
            assertEquals(302.0, ordered[current + 1].episodeNumber)
            assertEquals(300.0, ordered[current - 1].episodeNumber)
        }
    }

    @Test
    fun `fractional episodes and duplicate releases keep their order`() {
        val playlist = episodes(302.0, 301.5, 301.0, 301.0)
        assertEquals(listOf(2L, 3L, 1L, 0L), playlist.inPlaybackOrder(true).map { it.id })
    }

    @Test
    fun `unknown numbers and explicit alternative sorting preserve the supplied order`() {
        for (playlist in listOf(episodes(2.0, -1.0, 1.0), episodes(2.0, Double.NaN, 1.0))) {
            assertEquals(playlist, playlist.inPlaybackOrder(true))
        }
        val playlist = episodes(2.0, 1.0)
        assertEquals(playlist, playlist.inPlaybackOrder(false))
        assertEquals(emptyList<Episode>(), emptyList<Episode>().inPlaybackOrder(true))
    }
}
