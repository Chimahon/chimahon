package eu.kanade.tachiyomi.ui.dictionary.compose

import chimahon.DictionaryStyle
import chimahon.LookupResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DictionaryPlanPreparationTest {
    @Test fun reuseRequiresTheSameLookupAndCompatibleEnvironment() {
        val builder = mockk<DictionaryPlanBuilder>()
        val other = mockk<DictionaryPlanBuilder>()
        every { builder.compatible(builder) } returns true
        every { builder.compatible(other) } returns false
        val results = ArrayList<LookupResult>()
        val styles = emptyList<DictionaryStyle>()
        val task = CompletableDeferred<Result<RenderPlan>>()
        val preparation = DictionaryPlanPreparation(builder, results, styles, null, task)
        assertTrue(preparation.matches(builder, results, styles, null))
        assertFalse(preparation.matches(builder, ArrayList(), styles, null))
        assertFalse(preparation.matches(other, results, styles, null))
        assertFalse(preparation.matches(builder, results, styles, listOf("kanji")))
        preparation.cancel()
        assertTrue(task.isCancelled)
        assertFalse(preparation.matches(builder, results, styles, null))
    }

    @Test fun mountingAwaitsTheOriginalPlanWithoutRebuildingOrCopying() = runBlocking {
        val plan = RenderPlan.Empty
        val task = CompletableDeferred(Result.success(plan))
        val preparation = DictionaryPlanPreparation(mockk(), emptyList(), emptyList(), null, task)
        assertSame(plan, preparation.await())
        assertSame(plan, preparation.await())
    }
}
