package eu.kanade.tachiyomi.ui.dictionary.compose

import chimahon.renderer.DictionaryCore
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Flat host chrome commands. UTF-8 packing happens on the preparation worker. */
public class NativeCardSource internal constructor(
    private val commands: IntArray,
    private val values: List<String>,
    public val sourceWeight: Int,
    /** Sum of the text-value lengths only; tags and attributes excluded. */
    public val textUnits: Int,
    /** Native-built tape (headword chrome) skips the HTML parser. */
    private val prebuilt: ByteBuffer? = null,
) {
    public val tape: ByteBuffer by lazy { prebuilt ?: buildTape() }

    private fun buildTape(): ByteBuffer {
        val strings = ByteArrayOutputStream()
        val offsets = IntArray(values.size)
        val lengths = IntArray(values.size)
        values.forEachIndexed { i, value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            offsets[i] = strings.size()
            lengths[i] = bytes.size
            strings.write(bytes)
        }
        return ByteBuffer.allocateDirect(12 + commands.size * 8 + strings.size())
            .order(ByteOrder.nativeOrder()).apply {
                putInt(0x43415244); putInt(commands.size / 2); putInt(strings.size())
                var i = 0
                while (i < commands.size) {
                    val kind = commands[i++]
                    val value = commands[i++]
                    putInt(kind); putInt(0)
                    putInt(if (value < 0) 0 else offsets[value])
                    putInt(if (value < 0) 0 else lengths[value])
                }
                put(strings.toByteArray()); flip()
            }
    }
}

public class NativeCardBuilder internal constructor() {
    private var commands = IntArray(64)
    private var size = 0
    private val values = ArrayList<String>()
    private var units = 0
    private var textUnits = 0

    @PublishedApi
    internal fun emit(kind: Int, value: String? = null) {
        if (size + 2 > commands.size) commands = commands.copyOf(commands.size * 2)
        commands[size++] = kind
        commands[size++] = if (value == null) -1 else values.size.also {
            values.add(value)
            units += value.length
            if (kind == 3) textUnits += value.length
        }
    }

    public fun text(value: String) { emit(3, value) }

    public inline fun element(tag: String, classes: String? = null, content: NativeCardBuilder.() -> Unit) {
        emit(0, tag)
        if (classes != null) attribute("class", classes)
        content()
        emit(4)
    }

    public fun attribute(name: String, value: String) { emit(1, name); emit(5, value) }
    public fun property(name: String, value: String) { emit(2, name); emit(5, value) }

    internal fun finish(): NativeCardSource = NativeCardSource(commands.copyOf(size), values, units + size * 8, textUnits)
}

public fun nativeCardSource(content: NativeCardBuilder.() -> Unit): List<StructuredNode> {
    val source = NativeCardBuilder().apply(content).finish()
    return listOf(StructuredNode.Element(StructuredTag.Unknown,
        attributes = StructuredAttributes(nativeCard = source)))
}

internal fun nativeCardInput(nodes: List<StructuredNode>): NativeCardSource? =
    (nodes.singleOrNull() as? StructuredNode.Element)?.attributes?.nativeCard

internal fun nativeCardTape(nodes: List<StructuredNode>): ByteBuffer? = nativeCardInput(nodes)?.tape

/** Headword chrome built in C++ as a card tape: no HTML parse, no Kotlin split. */
public fun headwordCardSource(expression: String, reading: String, classes: String): List<StructuredNode> {
    val size = DictionaryCore.headwordTapeSize(expression, reading, classes)
    val buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
    DictionaryCore.headwordTapeInto(buffer, expression, reading, classes)
    val source = NativeCardSource(IntArray(0), emptyList(), size, expression.length + reading.length, buffer)
    return listOf(StructuredNode.Element(StructuredTag.Unknown,
        attributes = StructuredAttributes(nativeCard = source)))
}
