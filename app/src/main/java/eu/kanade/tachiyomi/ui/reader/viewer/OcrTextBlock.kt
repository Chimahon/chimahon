package eu.kanade.tachiyomi.ui.reader.viewer

import chimahon.ocr.extractOcrLookupText
import chimahon.ocr.extractWholeWord
import chimahon.ocr.isOcrLookupStartChar

/**
 * Represents an OCR-detected text block (normalized coordinates, pre-processed offline).
 *
 * Coordinates are normalized to 0.0–1.0 relative to image dimensions.
 * This format is produced by the Chrome Lens OCR pipeline and avoids
 * dependency on original image pixel dimensions at render time.
 *
 * @param xmin normalized X coordinate of left edge (0.0–1.0)
 * @param ymin normalized Y coordinate of top edge (0.0–1.0)
 * @param xmax normalized X coordinate of right edge (0.0–1.0)
 * @param ymax normalized Y coordinate of bottom edge (0.0–1.0)
 * @param lines list of text strings (one per line within the block)
 * @param vertical true for vertical (tategumi) text, false for horizontal
 */
data class OcrTextBlock(
    val xmin: Float,
    val ymin: Float,
    val xmax: Float,
    val ymax: Float,
    val lines: List<String>,
    val vertical: Boolean = false,
    val lineGeometries: List<OcrLineGeometry>? = null,
    val language: String = "",
)

/**
 * Geometry for an individual line within a block.
 */
data class OcrLineGeometry(
    val xmin: Float,
    val ymin: Float,
    val xmax: Float,
    val ymax: Float,
    val rotation: Float = 0f,
)

/**
 * Returns the full text of the block by joining all lines.
 * Lines are concatenated without separator (correct for Japanese, no spaces between lines).
 */
val OcrTextBlock.fullText: String
    get() = lines.joinToString("")

/**
 * Returns block text in reading order derived from the line geometry.
 *
 * OCR engines can return line text in detection order, which is not always the
 * same order the OCR box is read in. Geometry lets Anki sentence export use the
 * same block ordering as the visible text box.
 */
val OcrTextBlock.orderedFullText: String
    get() = orderedLineIndices().joinToString("") { index -> lines[index] }

/**
 * Returns the full text of the block with a space separator between lines for
 * horizontal text. Used for display in the selection panel and popup sentence
 * context where words at line boundaries would otherwise be concatenated.
 * Adjacent-line word overlap from OCR boundary duplication is trimmed.
 */
val OcrTextBlock.displayText: String
    get() {
        if (vertical) return lines.joinToString("")
        val out = mutableListOf(lines.firstOrNull() ?: return "")
        for (i in 1 until lines.size) {
            out.add(trimLineOverlap(out.last(), lines[i]))
        }
        return out.joinToString(" ")
    }

/**
 * Returns display text in reading order with a space separator between lines
 * for horizontal text.
 */
val OcrTextBlock.orderedDisplayText: String
    get() {
        val indices = orderedLineIndices()
        if (vertical) return indices.joinToString("") { lines[it] }
        val ordered = indices.map { lines[it] }
        val out = mutableListOf(ordered.firstOrNull() ?: return "")
        for (i in 1 until ordered.size) {
            out.add(trimLineOverlap(out.last(), ordered[i]))
        }
        return out.joinToString(" ")
    }

/**
 * Converts an offset based on [fullText] to the equivalent offset in
 * [orderedFullText].
 */
fun OcrTextBlock.toOrderedOffset(rawOffset: Int): Int {
    if (fullText.isEmpty()) return 0
    if (rawOffset >= fullText.length) return orderedFullText.length

    val safeRawOffset = rawOffset.coerceIn(0, fullText.length)
    var rawLineStart = 0
    var rawLineIndex = lines.lastIndex
    var offsetInLine = 0

    for (i in lines.indices) {
        val lineLength = lines[i].length
        val rawLineEnd = rawLineStart + lineLength
        if (safeRawOffset < rawLineEnd) {
            rawLineIndex = i
            offsetInLine = (safeRawOffset - rawLineStart).coerceIn(0, lineLength)
            break
        }
        rawLineStart = rawLineEnd
    }

    val orderedIndices = orderedLineIndices()
    val orderedLineStart = orderedIndices
        .takeWhile { it != rawLineIndex }
        .sumOf { lines[it].length }

    return (orderedLineStart + offsetInLine).coerceIn(0, orderedFullText.length)
}

internal fun OcrTextBlock.orderedLineIndices(): List<Int> {
    val geometries = lineGeometries
    if (lines.size <= 1 || geometries == null || geometries.size != lines.size) {
        return lines.indices.toList()
    }

    return if (vertical) {
        lines.indices.sortedWith(
            compareByDescending<Int> { geometries[it].centerX }
                .thenBy { geometries[it].ymin },
        )
    } else {
        lines.indices.sortedWith(
            compareBy<Int> { geometries[it].centerY }
                .thenBy { geometries[it].xmin },
        )
    }
}

private val OcrLineGeometry.centerX: Float
    get() = (xmin + xmax) / 2f

private val OcrLineGeometry.centerY: Float
    get() = (ymin + ymax) / 2f

internal fun isLookupStartChar(char: Char): Boolean {
    return isOcrLookupStartChar(char)
}

internal fun extractOcrLookupString(text: String, start: Int): String {
    return extractOcrLookupText(text, start)
}

/**
 * Lookup string for the tap at [global] offset of the block. When [wholeWord]
 * is true the tap position expands to the full surrounding word, clamped so it
 * never crosses into a neighboring OCR line (block text concatenates lines
 * without a separator).
 */
internal fun OcrTextBlock.extractLookupString(global: Int, wholeWord: Boolean): String {
    if (!wholeWord) return extractOcrLookupText(fullText, global)
    val (lineStart, lineEnd) = lineBoundariesFor(global)
    return extractWholeWord(fullText, global, lineStart, lineEnd)
}

/** [start, end) range of the line containing [offset] within [fullText]. */
internal fun OcrTextBlock.lineBoundariesFor(offset: Int): Pair<Int, Int> {
    if (lines.isEmpty()) return 0 to 0
    var lineStart = 0
    for (line in lines) {
        val lineEnd = lineStart + line.length
        if (offset < lineEnd) return lineStart to lineEnd
        lineStart = lineEnd
    }
    return lineStart to fullText.length
}

internal fun uniformCharOffset(
    block: OcrTextBlock,
    localX: Float,
    localY: Float,
    screenW: Float,
    screenH: Float,
): Int {
    if (block.vertical) {
        val columns = block.lines.ifEmpty { listOf(block.fullText) }
        if (columns.isEmpty()) return 0

        val columnWidth = (screenW / columns.size.coerceAtLeast(1)).coerceAtLeast(1f)
        val maxChars = columns.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
        val rowHeight = (screenH / maxChars).coerceAtLeast(1f)
        val contentTop = (screenH - rowHeight * maxChars) / 2f

        val fromRight = (screenW - localX).coerceIn(0f, screenW)
        val columnIndex = (fromRight / columnWidth)
            .toInt()
            .coerceIn(0, columns.lastIndex)

        val columnText = columns[columnIndex]
        val charsInColumn = columnText.length.coerceAtLeast(1)
        val yInColumn = (localY - contentTop)
            .coerceIn(0f, (rowHeight * charsInColumn - 0.001f).coerceAtLeast(0f))
        val charIndex = (yInColumn / rowHeight)
            .toInt()
            .coerceIn(0, charsInColumn - 1)

        return columns.take(columnIndex).sumOf { it.length } + charIndex
    }

    val lineCount = block.lines.size.coerceAtLeast(1)
    val lineIndex = (localY / (screenH / lineCount))
        .toInt().coerceIn(0, lineCount - 1)
    val line = block.lines[lineIndex]
    if (line.isEmpty()) return block.lines.take(lineIndex).sumOf { it.length }
    val charIndex = (localX / (screenW / line.length.coerceAtLeast(1)))
        .toInt().coerceIn(0, line.length - 1)
    return block.lines.take(lineIndex).sumOf { it.length } + charIndex
}

/**
 * Trims any leading text from [curr] that duplicates the trailing text of
 * [prev] at an OCR line boundary. Only triggers for ≥4-char matches that
 * are whole-word on both sides, avoiding false positives on short /
 * non-whitespace-delimited text (CJK).
 */
private fun trimLineOverlap(prev: String, curr: String): String {
    val a = prev.trimEnd()
    val b = curr.trimStart()
    if (a.length < 4 || b.length < 4) return curr.trimStart()
    val maxLen = minOf(a.length, b.length)
    for (len in maxLen downTo 4) {
        if (a.takeLast(len) != b.take(len)) continue
        val beforeWord = len == a.length || a[a.length - len - 1].isWhitespace()
        val afterWord = len == b.length || b[len].isWhitespace()
        if (beforeWord && afterWord) {
            return b.substring(len).trimStart()
        }
    }
    return curr.trimStart()
}
