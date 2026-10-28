package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.runtime.mutableStateOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeFontValidityTest {
    @Test fun repeatedValidationSharesOneScanButFontChangesAndNewProfilesInvalidateIt() {
        val first = mutableStateOf(false)
        val fonts = mutableListOf(first)
        var scans = 0
        val validity = NativeFontValidity { scans++; fonts.any { it.value } }
        repeat(100) { assertFalse(validity.stale) }
        assertEquals(1, scans)
        first.value = true
        assertTrue(validity.stale)
        assertEquals(2, scans)
        first.value = false
        assertFalse(validity.stale)
        assertEquals(3, scans)

        val next = mutableStateOf(false)
        fonts.add(next)
        validity.fontAdded()
        assertFalse(validity.stale)
        assertEquals(4, scans)
        next.value = true
        repeat(100) { assertTrue(validity.stale) }
        assertEquals(5, scans)
    }
}
