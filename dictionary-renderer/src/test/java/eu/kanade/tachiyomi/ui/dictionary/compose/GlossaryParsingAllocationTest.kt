package eu.kanade.tachiyomi.ui.dictionary.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class GlossaryParsingAllocationTest {
    @Test fun structuredDataUsesBrowserDatasetNames() {
        assertEquals("hsup", structuredDataName("Hsup"))
        assertEquals("meaning-g", structuredDataName("MeaningG"))
        assertEquals("p-o-s", structuredDataName("POS"))
        assertEquals("rank_num", structuredDataName("rank_num"))
        assertEquals("class", structuredDataName("class"))
        assertEquals(null, structuredDataName("rank-num"))
    }

    @Test fun normalizedKeysKeepTheirOriginalString() {
        val key = charArrayOf('h', 'e', 'a', 'd', 'w', 'o', 'r', 'd').concatToString()
        assertSame(key, toCamelCase(key))
        assertEquals("", toCamelCase(""))
        assertEquals("borderTopColor", toCamelCase("border-top-color"))
        assertEquals("FooBar", toCamelCase("-foo--bar-"))
    }

    @Test fun emptyAttributesAreSharedWithoutChangingNodeEquality() {
        val attributes = StructuredAttributes.create(emptyMap(), emptyMap(), emptyMap())
        assertSame(StructuredAttributes.Empty, attributes)
        assertSame(attributes, StructuredNode.Element(StructuredTag.Span).attributes)
        assertEquals(StructuredAttributes(), attributes)
    }

    @Test fun nonemptyAttributesRemainIndependentAndIntact() {
        val data = mapOf("headword" to "")
        val style = mapOf("color" to "red")
        val properties = mapOf("href" to "target")
        val attributes = StructuredAttributes.create(data, style, properties)
        assertNotSame(StructuredAttributes.Empty, attributes)
        assertEquals(StructuredAttributes(data, style, properties), attributes)
        assertEquals(data, attributes.data)
        assertEquals(style, attributes.style)
        assertEquals(properties, attributes.properties)
        assertEquals(emptyMap<String, String>(), StructuredAttributes.Empty.data)
    }
}
