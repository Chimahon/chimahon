package eu.kanade.tachiyomi.ui.dictionary.compose

/** Observes committed inputs only; it never changes plan reuse or Compose state. */
internal class DictionaryPlanDiagnostics {
    private var previous: Map<String, Any?>? = null
    private var previousRejection: List<String>? = null
    private var consumed: RenderPlan? = null

    fun committed(request: Long, inputs: Map<String, Any?>, supplied: DictionaryPlanPreparation?,
        rejection: List<String>, plan: RenderPlan?) {
        val old = previous
        val changed = inputs.filter { (key, value) -> old == null || old[key] != value }
        if (changed.isNotEmpty() || previousRejection != rejection) {
            val details = changed.entries.joinToString(",") { (key, value) ->
                "$key:${describe(old?.get(key))}->${describe(value)}"
            }
            NativeRenderDiagnostics.mark(request, plan?.profilingPlanId ?: supplied?.planId ?: 0L,
                "plan.inputs", "changed=$details suppliedPlan=${supplied?.planId ?: 0L} rejected=${rejection.joinToString(",").ifEmpty { "none" }}")
        }
        previous = inputs
        previousRejection = rejection
        if (plan != null && plan !== RenderPlan.Empty && consumed !== plan) {
            consumed = plan
            NativeRenderDiagnostics.mark(request, plan.profilingPlanId, "plan.compose.commit")
        }
    }

    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean, is Enum<*> -> value.toString()
        else -> "${value.javaClass.simpleName}@${System.identityHashCode(value)}"
    }
}
