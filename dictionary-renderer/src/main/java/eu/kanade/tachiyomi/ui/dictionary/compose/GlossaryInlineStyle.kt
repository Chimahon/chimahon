package eu.kanade.tachiyomi.ui.dictionary.compose

public fun StructuredNode.appendCollectedTo(target: StringBuilder) {
    when (this) {
        is StructuredNode.Text -> target.append(text)
        is StructuredNode.Element -> children.forEach { it.appendCollectedTo(target) }
        StructuredNode.TextBreak -> target.append('\n')
    }
}
