package eu.kanade.tachiyomi.ui.dictionary.compose

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Host-generated chrome, passed as a typed tree rather than serialized JSON. */
public class NativeCardElement(public val tag: String, public val content: Any,
    public val attributes: Map<String, String> = emptyMap(), public val properties: Map<String, String> = emptyMap())

public class NativeCardSource internal constructor(content: Any) {
    public val tape: ByteBuffer = CardTape().apply { node(listOf(content)) }.finish()
}

public fun nativeCardSource(content: Any): List<StructuredNode> = listOf(StructuredNode.Element(
    StructuredTag.Unknown, attributes = StructuredAttributes(nativeCard = NativeCardSource(content))))

internal fun nativeCardTape(nodes: List<StructuredNode>): ByteBuffer? =
    (nodes.singleOrNull() as? StructuredNode.Element)?.attributes?.nativeCard?.tape

private class CardTape {
    private var tokens = IntArray(128)
    private var count = 0
    private val strings = ByteArrayOutputStream()
    private fun token(kind: Int, value: String = ""): Int {
        if ((count + 1) * 4 > tokens.size) tokens = tokens.copyOf(tokens.size * 2)
        val index = count++
        val bytes = value.toByteArray(Charsets.UTF_8)
        val at = index * 4
        tokens[at] = kind
        tokens[at + 1] = count
        tokens[at + 2] = strings.size()
        tokens[at + 3] = bytes.size
        strings.write(bytes)
        return index
    }
    private fun end(index: Int) { tokens[index * 4 + 1] = count }
    private fun key(name: String) { token(2, name) }
    fun node(value: Any) {
        when (value) {
            is String -> token(3, value)
            is List<*> -> {
                val start = token(1)
                value.forEach { node(requireNotNull(it)) }
                end(start)
            }
            is NativeCardElement -> {
                val start = token(0)
                key("tag"); token(3, value.tag)
                if (value.attributes.isNotEmpty()) {
                    key("htmlAttributes")
                    val attrs = token(0)
                    value.attributes.forEach { (name, text) -> key(name); token(3, text) }
                    end(attrs)
                }
                value.properties.forEach { (name, text) -> key(name); token(3, text) }
                key("content"); node(value.content)
                end(start)
            }
            else -> error("Unsupported card node: ${value.javaClass.name}")
        }
    }
    fun finish(): ByteBuffer = ByteBuffer.allocateDirect(8 + count * 16 + strings.size())
        .order(ByteOrder.nativeOrder()).apply {
            putInt(count); putInt(strings.size())
            for (i in 0 until count * 4) putInt(tokens[i])
            put(strings.toByteArray()); flip()
        }
}
