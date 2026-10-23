package eu.kanade.tachiyomi.ui.dictionary.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NativeCssDrawingTest {
    @Test fun radialStopsKeepTheCenterPlateauAndNamedColors() {
        assertEquals(listOf(0xff800080.toInt() to 0.5f, 0xffffffff.toInt() to 1f),
            radialColorStops("radial-gradient(purple 50%, white 100%)"))
    }

    @Test fun functionalColorsDoNotSplitTheStopList() {
        assertEquals(listOf(0xff1f1f1f.toInt() to 0f, 0xffffffff.toInt() to 1f),
            radialColorStops("radial-gradient(rgb(31, 31, 31), white)"))
        assertNull(radialColorStops("radial-gradient(circle at left, red, white)"))
        assertNull(radialColorStops("radial-gradient(red 80%, white 40%)"))
    }
}
