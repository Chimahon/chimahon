package eu.kanade.tachiyomi.ui.dictionary.compose

/** The image container emitted by renderer.js uses reduced-font em units, not raw pixels. */
internal fun glossaryImageSize(properties: Map<String, String>, css: Map<String, String>, fontSize: Float,
    rootFontSize: Float, availableWidth: Float): Pair<Float, Float> {
    fun number(key: String) = properties[key]?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }
    val naturalWidth = number("preferredWidth") ?: number("width") ?: 100f
    val naturalHeight = number("preferredHeight") ?: number("height") ?: 100f
    val ratio = maxOf(0.01f, naturalHeight / maxOf(1f, naturalWidth))
    val defaultEm = if (properties["sizeUnits"] == "em") fontSize else fontSize / rootFontSize
    val em = css["fontSize"]?.let { parseMarginValue(it, fontSize) } ?: defaultEm
    fun length(value: String?): Float? {
        val raw = value?.trim() ?: return null
        val result = if (raw.endsWith('%')) raw.dropLast(1).toFloatOrNull()?.let { availableWidth * it / 100f }
            else parseDpValue(raw, em)
        return result?.takeIf { it.isFinite() && it >= 0f }
    }
    val wantedWidth = length(css["width"]) ?: naturalWidth * em
    val width = minOf(availableWidth, length(css["maxWidth"]) ?: availableWidth, wantedWidth)
    return width to (length(css["height"]) ?: width * ratio)
}
