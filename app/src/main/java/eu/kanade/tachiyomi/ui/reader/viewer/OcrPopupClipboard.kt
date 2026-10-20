package eu.kanade.tachiyomi.ui.reader.viewer

internal const val OCR_POPUP_CLIPBOARD_LABEL = "OCR text"

internal fun copyOcrPopupFullText(
    fullText: String,
    copy: (label: String, content: String) -> Unit,
) {
    copy(OCR_POPUP_CLIPBOARD_LABEL, fullText)
}
