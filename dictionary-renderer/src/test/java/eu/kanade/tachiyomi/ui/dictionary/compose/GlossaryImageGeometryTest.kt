package eu.kanade.tachiyomi.ui.dictionary.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GlossaryImageGeometryTest {
    @Test fun pixelImagesStillScaleWithTheirInheritedFont() {
        assertEquals(100f to 50f, glossaryImageSize(mapOf("width" to "100", "height" to "50"),
            emptyMap(), 14f, 14f, 420f))
        assertEquals(200f to 100f, glossaryImageSize(mapOf("width" to "100", "height" to "50"),
            emptyMap(), 28f, 14f, 420f))
    }

    @Test fun stylesheetGaijiWidthUsesTheReducedContainerFont() {
        assertEquals(10f to 10f, glossaryImageSize(emptyMap(), mapOf("width" to "10em"), 14f, 14f, 420f))
        assertEquals(20f to 20f, glossaryImageSize(emptyMap(), mapOf("width" to "10em"), 28f, 14f, 420f))
        assertEquals(8f to 8f, glossaryImageSize(emptyMap(), mapOf("fontSize" to "0.5em", "width" to "1em"), 16f, 14f, 420f))
    }

    @Test fun emImagesAndPercentageClampsPreserveAspectRatio() {
        assertEquals(28f to 14f, glossaryImageSize(mapOf("sizeUnits" to "em", "width" to "2", "height" to "1"),
            emptyMap(), 14f, 14f, 420f))
        assertEquals(210f to 105f, glossaryImageSize(mapOf("width" to "400", "height" to "200"),
            mapOf("maxWidth" to "50%"), 14f, 14f, 420f))
    }
}
