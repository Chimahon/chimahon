package eu.kanade.tachiyomi.ui.dictionary.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StyleAnalysisTest {
    @Test fun physicalLeftBorderIsOneEdgeWithItsRequestedWidthAndColor() {
        for (declaration in listOf("1px solid #666666", "solid #666666 1px")) {
            val box = analyzeBoxProps(mapOf("borderLeft" to declaration), 14f)
            assertEquals(true, box.hasBorder)
            assertEquals(true, box.leftAccent)
            assertEquals(1f, box.borderWidth)
            assertEquals("#666666", box.borderColor)
            assertEquals(null, box.paddingStart)
        }
    }

    @Test fun logicalStartBorderRetainsWidthRegardlessOfShorthandOrder() {
        for (declaration in listOf("solid 0.5px black", "black solid 0.5px", "0.5px solid black")) {
            val box = analyzeBoxProps(mapOf("borderInlineStart" to declaration), 14f)
            assertEquals(0.5f, box.borderWidth)
            assertEquals("black", box.borderColor)
            assertEquals(null, box.paddingStart)
        }
    }

    @Test fun resizedCellPaddingUsesTheComputedFontSize() {
        val box = analyzeBoxProps(mapOf("padding" to "0.25em"), 21f)
        assertEquals(5.25f, box.paddingStart)
        assertEquals(5.25f, box.paddingTop)
    }

    @Test fun borderShorthandSkipsTheStyleKeywordBeforeAHexColor() {
        for (style in listOf("solid", "dashed", "dotted", "double")) {
            val box = analyzeBoxProps(mapOf("border" to "2px $style #ffffff"), 14f)
            assertEquals("#ffffff", box.borderColor)
            assertEquals(2f, box.borderWidth)
        }
    }

    @Test fun borderShorthandPreservesNamedColors() {
        for (color in listOf("white", "blue", "transparent")) {
            val box = analyzeBoxProps(mapOf("border" to "1px solid $color"), 14f)
            assertEquals(color, box.borderColor)
        }
    }

    @Test fun explicitBorderColorKeepsItsExistingPriority() {
        val box = analyzeBoxProps(linkedMapOf("borderColor" to "blue", "border" to "2px solid white"), 14f)
        assertEquals("blue", box.borderColor)
    }
}
