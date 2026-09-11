package eu.kanade.tachiyomi.ui.player.utils

import eu.kanade.tachiyomi.data.database.models.Episode
import eu.kanade.tachiyomi.data.database.models.EpisodeImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaybackEpisodeOrderTest {
    private fun episodes(vararg numbers: Float) = numbers.mapIndexed { index, number ->
        EpisodeImpl().apply { id = index.toLong(); episode_number = number }
    }

    @Test
    fun `next advances to a higher episode for either source direction`() {
        for (playlist in listOf(episodes(300f, 301f, 302f), episodes(302f, 301f, 300f))) {
            val ordered = playlist.inPlaybackOrder(true)
            val current = ordered.indexOfFirst { it.episode_number == 301f }
            assertEquals(302f, ordered[current + 1].episode_number)
            assertEquals(300f, ordered[current - 1].episode_number)
        }
    }

    @Test
    fun `fractional episodes and duplicate releases keep their order`() {
        val playlist = episodes(302f, 301.5f, 301f, 301f)
        assertEquals(listOf(2L, 3L, 1L, 0L), playlist.inPlaybackOrder(true).map { it.id })
    }

    @Test
    fun `unknown numbers and explicit alternative sorting preserve the supplied order`() {
        for (playlist in listOf(episodes(2f, -1f, 1f), episodes(2f, Float.NaN, 1f))) {
            assertEquals(playlist, playlist.inPlaybackOrder(true))
        }
        val playlist = episodes(2f, 1f)
        assertEquals(playlist, playlist.inPlaybackOrder(false))
        assertEquals(emptyList<Episode>(), emptyList<Episode>().inPlaybackOrder(true))
    }
}
