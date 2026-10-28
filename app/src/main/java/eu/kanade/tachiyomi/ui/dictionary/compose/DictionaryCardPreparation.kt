package eu.kanade.tachiyomi.ui.dictionary.compose

import chimahon.LookupResult
import chimahon.diagnostics.LookupProfiler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One pending lookup's card preparation, independent of viewport, theme and UI mounting. */
class DictionaryCardPreparation internal constructor(
    private val results: List<LookupResult>,
    private val groupTerms: Boolean,
    private val dictionaryOrder: List<String>,
    private val task: Deferred<Result<List<TermCard>>>,
) {
    internal fun matches(results: List<LookupResult>, groupTerms: Boolean, dictionaryOrder: List<String>): Boolean =
        this.results === results && this.groupTerms == groupTerms && this.dictionaryOrder == dictionaryOrder && !task.isCancelled

    internal suspend fun await(): List<TermCard> = task.await().getOrThrow()

    internal fun cancel() = task.cancel()
}

internal fun CoroutineScope.prepareDictionaryCards(
    results: List<LookupResult>,
    groupTerms: Boolean,
    dictionaryOrder: List<String>,
    profilingId: Long,
    resolveTitle: (String) -> String,
): DictionaryCardPreparation {
    val order = dictionaryOrder.toList()
    val task = async(Dispatchers.Default) {
        val caller = currentCoroutineContext()
        try {
            Result.success(LookupProfiler.elapsedSection(profilingId, "plan.cards_parse") {
                val priority = order.map(resolveTitle).withIndex().associate { it.value to it.index }
                buildCards(results, groupTerms, resolveTitle, priority) { caller.ensureActive() }
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Deliver a parser failure to the consuming plan, not unrelated popup jobs.
            Result.failure(e)
        }
    }
    return DictionaryCardPreparation(results, groupTerms, order, task)
}
