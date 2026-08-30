package eu.kanade.domain.track.interactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AniListTitleMatcherTest {

    @Test
    fun `normalize strips punctuation and case`() {
        assertEquals("rezerostartinglifeinanotherworld", AniListTitleMatcher.normalize("Re:Zero − Starting Life in Another World!"))
        assertEquals("onepiece", AniListTitleMatcher.normalize("One Piece"))
        assertEquals("ワンピース", AniListTitleMatcher.normalize("ワンピース"))
    }

    @Test
    fun `matches ignores casing and punctuation`() {
        assertTrue(AniListTitleMatcher.matches("One Piece", "one piece"))
        assertTrue(AniListTitleMatcher.matches("Frieren: Beyond Journey's End", "Frieren – Beyond Journeys End"))
    }

    @Test
    fun `matches unicode titles`() {
        assertTrue(AniListTitleMatcher.matches("葬送のフリーレン", "葬送のフリーレン"))
        assertFalse(AniListTitleMatcher.matches("葬送のフリーレン", "ワンピース"))
    }

    @Test
    fun `empty normalized titles never match`() {
        assertFalse(AniListTitleMatcher.matches(":", "!?"))
        assertFalse(AniListTitleMatcher.matches(":", "One Piece"))
    }
}
