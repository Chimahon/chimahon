package eu.kanade.tachiyomi.ui.reader.viewer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OcrPopupClipboardTest {

    @Test
    fun `copies complete OCR text instead of the lookup term`() {
        var label = ""
        var copiedText = ""

        copyOcrPopupFullText("A full OCR text block") { copiedLabel, content ->
            label = copiedLabel
            copiedText = content
        }

        assertEquals("OCR text", label)
        assertEquals("A full OCR text block", copiedText)
    }
}
