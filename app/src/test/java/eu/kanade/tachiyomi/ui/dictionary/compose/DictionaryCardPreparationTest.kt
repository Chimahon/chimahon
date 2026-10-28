package eu.kanade.tachiyomi.ui.dictionary.compose

import chimahon.LookupResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DictionaryCardPreparationTest {
    @Test fun preparationRequiresTheSameResultsAndGroupingInputs() {
        val results = ArrayList<LookupResult>()
        val task = CompletableDeferred<Result<List<TermCard>>>()
        val preparation = DictionaryCardPreparation(results, true, listOf("first", "second"), task)
        assertTrue(preparation.matches(results, true, listOf("first", "second")))
        assertFalse(preparation.matches(ArrayList(), true, listOf("first", "second")))
        assertFalse(preparation.matches(results, false, listOf("first", "second")))
        assertFalse(preparation.matches(results, true, listOf("second", "first")))
        preparation.cancel()
        assertTrue(task.isCancelled)
        assertFalse(preparation.matches(results, true, listOf("first", "second")))
    }

    @Test fun awaitingReturnsThePreparedCardsWithoutCopying() = runBlocking {
        val cards = ArrayList<TermCard>()
        val task = CompletableDeferred<Result<List<TermCard>>>(Result.success(cards))
        val preparation = DictionaryCardPreparation(emptyList(), true, emptyList(), task)
        assertSame(cards, preparation.await())
    }

    @Test fun parserFailuresReachTheConsumerWithoutBecomingEmptyContent() {
        val error = IllegalArgumentException("invalid glossary")
        val task = CompletableDeferred<Result<List<TermCard>>>(Result.failure(error))
        val preparation = DictionaryCardPreparation(emptyList(), true, emptyList(), task)
        assertSame(error, assertThrows(IllegalArgumentException::class.java) {
            runBlocking { preparation.await() }
        })
    }
}
