package eu.kanade.tachiyomi.data.ocr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class OcrRetryTest {

    @Test
    fun `persistent failures exhaust retries instead of looping`() = runTest {
        var attempts = 0
        val failure = IllegalStateException("Cannot save OCR")
        try {
            retryWithBackoff(times = 3, initialDelayMs = 1) {
                attempts++
                throw failure
            }
            fail<Unit>("Expected the final failure")
        } catch (e: IllegalStateException) {
            assertSame(failure, e)
        }
        assertEquals(3, attempts)
    }

    @Test
    fun `cancellation is never retried`() = runTest {
        var attempts = 0
        val cancellation = CancellationException("User paused OCR")
        try {
            retryWithBackoff(times = 3, initialDelayMs = 1) {
                attempts++
                throw cancellation
            }
            fail<Unit>("Expected cancellation")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
        assertEquals(1, attempts)
    }
}
