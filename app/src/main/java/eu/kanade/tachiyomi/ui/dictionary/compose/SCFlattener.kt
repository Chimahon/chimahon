package eu.kanade.tachiyomi.ui.dictionary.compose

/**
 * Port of JL flatten logic for structured-content glossary nodes.
 * Uses StringBuilder to avoid O(n²) joinToString allocations on large glosses.
 */
fun StructuredNode.flatten(): String = buildString { appendFlatten(this@flatten) }

private fun StructuredNode.isAttribution(): Boolean =
    this is StructuredNode.Element && attributes.data["content"] == "attribution"

private fun StringBuilder.appendFlatten(node: StructuredNode) {
    if (node.isAttribution()) return // credits/links row (JMdict/Tatoeba + raw URLs) is noise in reading mode
    when (node) {
        is StructuredNode.Text -> append(node.text)
        StructuredNode.TextBreak -> append('\n')
        is StructuredNode.Element -> when (node.tag) {
            StructuredTag.Break -> append('\n')
            StructuredTag.Rt, StructuredTag.Rp -> Unit
            StructuredTag.Ruby -> {
                val rt = node.children.filterIsInstance<StructuredNode.Element>()
                    .firstOrNull { it.tag == StructuredTag.Rt }
                node.children.forEach { c ->
                    if (c is StructuredNode.Element && (c.tag == StructuredTag.Rt || c.tag == StructuredTag.Rp)) return@forEach
                    appendFlatten(c)
                }
                if (rt != null) {
                    val rtText = buildString { rt.children.forEach { appendFlatten(it) } }
                    if (rtText.isNotBlank()) { append('['); append(rtText); append(']') }
                }
            }
            StructuredTag.ListItem -> { append("\n• "); node.children.forEach { appendFlatten(it) } }
            StructuredTag.UnorderedList, StructuredTag.OrderedList -> { append('\n'); node.children.forEach { appendFlatten(it) }; append('\n') }
            StructuredTag.Td, StructuredTag.Th -> { append(" | "); node.children.forEach { appendFlatten(it) } }
            StructuredTag.Tr -> { node.children.forEach { appendFlatten(it) }; append('\n') }
            StructuredTag.Image -> Unit
            StructuredTag.Link -> {
                // Plain reading mode: link text only, hrefs are unreadable noise here.
                node.children.forEach { appendFlatten(it) }
            }
            StructuredTag.Div -> { append('\n'); node.children.forEach { appendFlatten(it) }; append('\n') }
            else -> node.children.forEach { appendFlatten(it) }
        }
    }
}

fun StructuredNode.collectText(): String = buildString { appendCollect(this@collectText) }

private fun StringBuilder.appendCollect(node: StructuredNode) {
    if (node.isAttribution()) return
    when (node) {
        is StructuredNode.Text -> append(node.text)
        StructuredNode.TextBreak -> append('\n')
        is StructuredNode.Element -> when (node.tag) {
            StructuredTag.Break -> append('\n')
            StructuredTag.Rt, StructuredTag.Rp -> Unit
            StructuredTag.Ruby -> {
                val rt = node.children.filterIsInstance<StructuredNode.Element>()
                    .firstOrNull { it.tag == StructuredTag.Rt }
                node.children.forEach { c ->
                    if (c is StructuredNode.Element && (c.tag == StructuredTag.Rt || c.tag == StructuredTag.Rp)) return@forEach
                    appendCollect(c)
                }
                if (rt != null) {
                    val rtText = buildString { rt.children.forEach { appendCollect(it) } }
                    if (rtText.isNotBlank()) { append('['); append(rtText); append(']') }
                }
            }
            StructuredTag.ListItem -> { append("\n• "); node.children.forEach { appendCollect(it) } }
            StructuredTag.UnorderedList, StructuredTag.OrderedList -> { append('\n'); node.children.forEach { appendCollect(it) }; append('\n') }
            StructuredTag.Td, StructuredTag.Th -> { append(" | "); node.children.forEach { appendCollect(it) } }
            StructuredTag.Tr -> { node.children.forEach { appendCollect(it) }; append('\n') }
            StructuredTag.Image -> Unit
            StructuredTag.Div -> { append('\n'); node.children.forEach { appendCollect(it) }; append('\n') }
            else -> node.children.forEach { appendCollect(it) }
        }
    }
}
