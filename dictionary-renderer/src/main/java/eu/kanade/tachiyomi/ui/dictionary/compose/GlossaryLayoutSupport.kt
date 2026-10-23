package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/** `body { line-height: var(--line-height) }` in base.css (`--line-height: 1.4`). */
public const val DEFAULT_LINE_HEIGHT_FACTOR = 1.4f

/** CSS 2.1 10.8.1: negative leading is split across the font's ascent and descent. */
internal fun negativeHalfLeading(lineHeight: Int, fontHeight: Int): Int =
    Math.floorDiv(minOf(lineHeight - fontHeight, 0), 2)

/** CSS 2.2 10.8.1: lengths use this element's font; percentages use its line-height. */
internal fun inlineBaselineDistance(value: String, fontSize: Float, lineHeight: Float): Float? {
    val distance = if (value.endsWith('%')) value.dropLast(1).toFloatOrNull()?.let { it * lineHeight / 100f }
        else parseDpValue(value, fontSize)
    return distance?.takeIf { it.isFinite() }
}

// List geometry defaults from base.css. The rules are keyed on `.gloss-sc-ol` / `.gloss-sc-ul`, classes
// only the reference renderer puts in the DOM, so the Compose side cannot match them from a node's own
// attributes and has to carry the values. Shared by document layout and margin collapsing.
public const val LIST_PADDING_START_EM = 1.2f // base.css:638
/** `table { margin: 6px 0 }` - base.css:652. In px, not em, as the declaration is. */
public const val TABLE_MARGIN_PX = 6f
/**
 * `.structured-content div, p, ul, ol { margin: 4px 0 }` - base.css:622. In px, not em, as the
 * declaration is. Only the div arm is needed here: lists keep their own rule above.
 */
public const val BLOCK_MARGIN_PX = 4f
/**
 * `th, td { padding: 3px 6px; border: 1px solid var(--border) }` - base.css:646-650, mirrored here
 * because base.css is not part of the parsed dictionary stylesheet.
 *
 * The horizontal value is 6px, not 3px: a single constant used to pad all four sides, which made
 * every cell 6px narrower than the browser and changed where its text wrapped. The border is 1px
 * here too, not the 0.5dp that was hardcoded at the draw site.
 */
public const val CELL_PADDING_BLOCK_PX = 3f
public const val CELL_PADDING_INLINE_PX = 6f
public const val CELL_BORDER_PX = 1f
public const val LIST_MARGIN_EM = 0.3f // base.css:638
public const val LIST_ITEM_MARGIN_EM = 0.15f // base.css:643

/** Fast multi-map merge that avoids intermediate map allocations when operands are empty. */
public fun mergeCssStyles(
    a: Map<String, String>,
    b: Map<String, String>,
): Map<String, String> = when {
    b.isEmpty() -> a
    a.isEmpty() -> b
    else -> HashMap<String, String>(a.size + b.size).apply { putAll(a); putAll(b) }
}

public fun mergeCssStyles(
    a: Map<String, String>,
    b: Map<String, String>,
    c: Map<String, String>,
): Map<String, String> = when {
    b.isEmpty() && c.isEmpty() -> a
    a.isEmpty() && c.isEmpty() -> b
    a.isEmpty() && b.isEmpty() -> c
    a.isEmpty() -> mergeCssStyles(b, c)
    b.isEmpty() -> mergeCssStyles(a, c)
    c.isEmpty() -> mergeCssStyles(a, b)
    else -> HashMap<String, String>(a.size + b.size + c.size).apply { putAll(a); putAll(b); putAll(c) }
}

public fun mergeCssStyles(
    a: Map<String, String>,
    b: Map<String, String>,
    c: Map<String, String>,
    d: Map<String, String>,
): Map<String, String> = when {
    b.isEmpty() && c.isEmpty() && d.isEmpty() -> a
    a.isEmpty() && c.isEmpty() && d.isEmpty() -> b
    a.isEmpty() && b.isEmpty() && d.isEmpty() -> c
    a.isEmpty() && b.isEmpty() && c.isEmpty() -> d
    a.isEmpty() && b.isEmpty() -> mergeCssStyles(c, d)
    a.isEmpty() && c.isEmpty() -> mergeCssStyles(b, d)
    a.isEmpty() && d.isEmpty() -> mergeCssStyles(b, c)
    b.isEmpty() && c.isEmpty() -> mergeCssStyles(a, d)
    b.isEmpty() && d.isEmpty() -> mergeCssStyles(a, c)
    c.isEmpty() && d.isEmpty() -> mergeCssStyles(a, b)
    a.isEmpty() -> mergeCssStyles(b, c, d)
    b.isEmpty() -> mergeCssStyles(a, c, d)
    c.isEmpty() -> mergeCssStyles(a, b, d)
    d.isEmpty() -> mergeCssStyles(a, b, c)
    else -> HashMap<String, String>(a.size + b.size + c.size + d.size).apply {
        putAll(a); putAll(b); putAll(c); putAll(d)
    }
}

/**
 * Prepared ruby geometry. NativeRubySpan reserves the width; NativeTextFlow draws the retained
 * base and annotation at these offsets relative to the paragraph baseline.
 */
public class RubyMetrics(
    val widthPx: Int,
    val heightPx: Int,
    /** Reading's top relative to the prepared ruby box. */
    val readingOffsetPx: Float,
    /** Base's top relative to the prepared ruby box. */
    val baseOffsetPx: Float,
    val baseText: String,
    val readingText: String,
    val baseLayout: PreparedRubyText,
    val readingLayout: PreparedRubyText,
    val readingStyle: TextStyle? = null,
)

public fun measureRubyInline(
    renderer: RubyTextRenderer,
    base: String,
    reading: String,
    style: TextStyle,
    readingStyle: TextStyle = rubyReadingStyle(style),
): RubyMetrics {
    return renderer.column(base, reading, style, readingStyle)
}




/** True for nodes that participate in inline text flow (HTML inline-level content). */
public fun isInlineLevel(node: StructuredNode): Boolean = when (node) {
    is StructuredNode.Text, is StructuredNode.TextBreak -> true
    is StructuredNode.Element -> when (node.tag) {
        StructuredTag.Span, StructuredTag.Ruby, StructuredTag.Link,
        StructuredTag.Bold, StructuredTag.Italic, StructuredTag.Underline,
        StructuredTag.Strike, StructuredTag.Superscript, StructuredTag.Subscript,
        StructuredTag.Small, StructuredTag.Mark,
        StructuredTag.Image -> true
        else -> false
    }
    else -> false
}

/**
 * Per-dictionary decoded media URIs, provided by [DictionaryEntryCompose] once per popup.
 *
 * Consumed by image leaves in the prepared document surface, never by its layout compiler.
 * Media arrival changes paint without invalidating text, ruby or table geometry.
 */
public val LocalMediaDataUris = compositionLocalOf<Map<String, String>> { emptyMap() }

/** Shared CSS line-box style for document preparation and the host. */
public fun dictionaryBaseStyle(color: Color, fontSize: Int, fontFamily: FontFamily?): TextStyle =
    TextStyle(
        color = color,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * DEFAULT_LINE_HEIGHT_FACTOR).sp,
        fontFamily = fontFamily ?: FontFamily.Default,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )

/** A text style's font size in sp, or 16 when unspecified (the em base the CSS parser assumes). */
public fun fontSizeSp(style: TextStyle): Float = style.fontSize.let { if (it.isSp) it.value else 16f }

/**
 * Line-box metrics for one text style, per CSS Inline Layout 3 §5.3:
 *
 *     L = line-height - (A + D),   A' = A + L/2,   D' = D + L/2
 *
 * A and D are the first available font's ascent and descent; leading may be negative.
 */
public class LineBoxMetrics(
    val ascentPx: Float,
    val descentPx: Float,
    val halfLeadingPx: Float,
    val firstBaselinePx: Float,
)

public fun tableCellCss(cell: StructuredTableCell, parsedCss: ParsedCss, rowScope: List<CssAncestor>): Map<String, String> {
    val tag = if (cell.isHeader) "th" else "td"
    val data = cell.element?.attributes?.data ?: emptyMap()
    return getCssStyles(cell.rowData, parsedCss, "tr").filterKeys { it in inheritedTableKeys } +
        getCssStyles(data, parsedCss, tag) + getNestedStyles(tag, data, rowScope, parsedCss,
            siblingIndex = cell.indexInSourceRow + 1,
            isLastSibling = cell.indexInSourceRow == cell.sourceRowSize - 1) +
        getHasStyles(tag, data, cell.element, rowScope, parsedCss,
            siblingIndex = cell.indexInSourceRow + 1,
            isLastSibling = cell.indexInSourceRow == cell.sourceRowSize - 1) +
        (cell.element?.attributes?.style ?: emptyMap())
}

public fun tableCellStyle(style: TextStyle, css: Map<String, String>, header: Boolean, box: BoxStyle): TextStyle {
    var result = style
    result = when {
        isBoldWeight(css["fontWeight"]) -> result.copy(fontWeight = FontWeight.Bold)
        isNormalWeight(css["fontWeight"]) -> result.copy(fontWeight = FontWeight.Normal)
        header -> result.copy(fontWeight = FontWeight.Bold)
        else -> result
    }
    if (css["lineHeight"] == null) result = result.copy(lineHeight = (fontSizeSp(result) * 1.2f).sp)
    val align = box.textAlign ?: if (header) TextAlign.Center else null
    return if (align != null) result.copy(textAlign = align) else result
}

/** Inheritable text properties a `tr` passes to its cells (CSS inheritance). */
private val inheritedTableKeys = setOf(
    "fontSize", "color", "textAlign", "fontStyle", "fontWeight", "lineHeight",
)

private fun isBoldWeight(v: String?): Boolean =
    v == "bold" || v == "bolder" || (v?.toIntOrNull() ?: 0) >= 600

private fun isNormalWeight(v: String?): Boolean =
    v == "normal" || v == "lighter" || v == "initial"

/** base.css grids every cell unless the cascade explicitly removes the border. */
public fun defaultCellBorder(combined: Map<String, String>): Boolean {
    if (combined["borderStyle"]?.trim()?.lowercase() == "none") return false
    val w = combined["borderWidth"]?.trim()?.lowercase()
    if (w == "0" || w == "0px") return false
    if (combined["border"]?.trim()?.lowercase() in setOf("none", "0", "0px")) return false
    return true
}

public data class StructuredTableCell(
    val nodes: List<StructuredNode>,
    val isHeader: Boolean,
    val element: StructuredNode.Element? = null,
    val rowData: Map<String, String> = emptyMap(),
    /**
     * 0-based position within the cell's ORIGINAL `tr`, and how many cells that `tr` held.
     *
     * `:first-child` / `:last-child` / `:nth-child(n)` are DOM-tree selectors, not box-tree ones,
     * so they keep referring to the element's own parent even when that parent is
     * `display: contents` and generates no box. Splicing such rows into one anonymous row
     * therefore cannot renumber them: 小学館 使い分け paints cells 1, 3 and 5 tan because each is
     * the first `td` of its own `tr`, not because of any position in the flattened row. Using the
     * flattened index here left only the first cell matching, and the rest fell back to the base
     * cell colour.
     */
    val indexInSourceRow: Int = 0,
    val sourceRowSize: Int = 1,
)

/**
 * Number of column tracks when a table lays itself out as a CSS grid, else null.
 *
 * Only the forms that decide an equal split are modelled: `repeat(N, ...)` and a plain
 * track list. Returns null - i.e. "use the container width" - for a non-grid `display`, a missing
 * or unresolvable template, or any explicit sizing that is not a bare fraction, because a wrong
 * track count would silently shrink every column.
 */
public fun gridTrackCountOf(styles: Map<String, String>): Int? {
    val display = styles["display"]?.trim()?.lowercase() ?: return null
    if (display != "grid" && display != "inline-grid") return null
    val template = styles["gridTemplateColumns"]?.trim()
        ?: styles["grid-template-columns"]?.trim()
        ?: return null
    if (template.isEmpty()) return null
    // `repeat(3, 1fr)` / `repeat(3, minmax(0, 1fr))`: the count is explicit.
    REPEAT_TEMPLATE_REGEX.find(template)?.let { m ->
        val n = m.groupValues[1].toIntOrNull() ?: return null
        if (n <= 0 || m.groupValues[2].contains("auto") || m.groupValues[2].contains("px")) return null
        return n
    }
    val tracks = template.split(TRACK_SPLIT_REGEX).filter { it.isNotBlank() }
    if (tracks.isEmpty()) return null
    // Every track must be a bare fraction: anything with intrinsic sizing or a length makes the
    // split uneven, which this model does not represent.
    if (tracks.any { !FRACTION_TRACK_REGEX.matches(it) }) return null
    return tracks.size
}

private val TRACK_SPLIT_REGEX = Regex("\\s+")

private val REPEAT_TEMPLATE_REGEX = Regex("""repeat\(\s*(\d+)\s*,\s*([^)]*)\s*\)""", RegexOption.IGNORE_CASE)
private val FRACTION_TRACK_REGEX = Regex("""\d*\.?\d+fr""")

/**
 * The grid template's track sizes, as fractions of the table's width.
 *
 * A table that declares `display: grid` with a fractional `grid-template-columns` gets its
 * columns from the TEMPLATE, not from its content. 小学館's 使い分け table is `width: 100%;
 * display: grid` with equal tracks, so every column is the same width and a narrow one - which
 * is why the reference wraps each glyph onto its own line and the cell content fits the box.
 * Sizing the columns from the content instead made them wide, the text stopped wrapping, and the
 * content no longer fitted the cell.
 */
public fun gridTrackFractions(styles: Map<String, String>): FloatArray? {
    val display = styles["display"]?.trim()?.lowercase() ?: return null
    if (display != "grid" && display != "inline-grid") return null
    val template = styles["gridTemplateColumns"]?.trim()
        ?: styles["grid-template-columns"]?.trim()
        ?: return null
    if (template.isEmpty()) return null
    REPEAT_TEMPLATE_REGEX.find(template)?.let { m ->
        val n = m.groupValues[1].toIntOrNull() ?: return null
        if (n <= 0 || m.groupValues[2].contains("auto") || m.groupValues[2].contains("px")) return null
        val unit = m.groupValues[2].trim()
        if (!FRACTION_TRACK_REGEX.matches(unit)) return null
        val value = unit.removeSuffix("fr").toFloatOrNull() ?: return null
        if (value <= 0f) return null
        return FloatArray(n) { value }
    }
    val tracks = template.split(TRACK_SPLIT_REGEX).filter { it.isNotBlank() }
    if (tracks.isEmpty()) return null
    if (tracks.any { !FRACTION_TRACK_REGEX.matches(it) }) return null
    val fractions = FloatArray(tracks.size)
    for ((index, track) in tracks.withIndex()) {
        val value = track.removeSuffix("fr").toFloatOrNull() ?: return null
        if (value <= 0f) return null
        fractions[index] = value
    }
    return fractions
}

/** True when a `tr` generates no box, so its cells belong to the surrounding cell run. */
private fun trDisplaysContents(
    tr: StructuredNode.Element,
    parsedCss: ParsedCss,
    tableScope: List<CssAncestor>,
): Boolean {
    // Same merge order cells use (direct + descendant rules, inline last): `table[data-sc-X] tr`
    // arrives via the nested arm, a bare `[data-sc-Y]` or inline `style` via the others.
    val styles = getCssStyles(tr.attributes.data, parsedCss, "tr") +
        getNestedStyles("tr", tr.attributes.data, tableScope, parsedCss) +
        tr.attributes.style
    return styles["display"]?.trim()?.lowercase() == "contents"
}

/**
 * Row collection with anonymous-table grouping, or source grid-cell positions.
 *
 * Outside a grid, a `tr` with `display: contents` generates no box: its cells splice
 * into the surrounding run instead of forming a row, and a run of cells with no row gets
 * wrapped in an anonymous one. Grid items instead retain their source row positions.
 * Stray `td`/`th` outside any `tr` join the run the same way (previously they were dropped).
 *
 * Provably neutral without those constructs: the run is always empty when a normal `tr`
 * flushes it, so output equals the old one-row-per-`tr` walk exactly. Returns true when any
 * `display: contents` row was spliced, so the caller can pick the anonymous-row column split.
 */
public fun collectTableRowsFlat(
    node: StructuredNode.Element,
    parsedCss: ParsedCss,
    tableScope: List<CssAncestor>,
    out: MutableList<List<StructuredTableCell>>,
    run: MutableList<StructuredTableCell>,
    gridItems: Boolean = false,
): Boolean {
    if (node.tag == StructuredTag.Tr) {
        if (!gridItems && trDisplaysContents(node, parsedCss, tableScope)) {
            val own = node.children.filterIsInstance<StructuredNode.Element>()
                .filter { it.tag == StructuredTag.Td || it.tag == StructuredTag.Th }
            own.forEachIndexed { i, child ->
                run.add(
                    StructuredTableCell(
                        child.children,
                        child.tag == StructuredTag.Th,
                        child,
                        node.attributes.data,
                        indexInSourceRow = i,
                        sourceRowSize = own.size,
                    ),
                )
            }
            return true
        } else {
            if (run.isNotEmpty()) {
                out.add(run.toList())
                run.clear()
            }
            val rowData = node.attributes.data
            val own = node.children
                .filterIsInstance<StructuredNode.Element>()
                .filter { it.tag == StructuredTag.Td || it.tag == StructuredTag.Th }
            val cells = own.mapIndexed { i, it ->
                StructuredTableCell(
                    it.children, it.tag == StructuredTag.Th, it, rowData,
                    indexInSourceRow = i, sourceRowSize = own.size,
                )
            }
            if (cells.isNotEmpty()) out.add(cells)
            return false
        }
    }
    if (node.tag == StructuredTag.Td || node.tag == StructuredTag.Th) {
        run.add(
            StructuredTableCell(
                node.children, node.tag == StructuredTag.Th, node, emptyMap(),
                indexInSourceRow = run.size, sourceRowSize = run.size + 1,
            ),
        )
        return false
    }
    var saw = false
    node.children.forEach { child ->
        if (child is StructuredNode.Element && collectTableRowsFlat(child, parsedCss, tableScope, out, run, gridItems)) {
            saw = true
        }
    }
    return saw
}

// ---------------------------------------------------------------------------
// Text / helpers
// ---------------------------------------------------------------------------

/** Longest forward slice taken from a CJK tap, matching the WebView renderer's cap. */
public const val LOOKUP_SCAN_MAX_CHARS = 24

// Whitespace (incl. U+3000) plus the CJK/ASCII punctuation that terminates a scan.
// Raw string so `\[` and `\]` are regex escapes for literal brackets; writing `[\\]` instead
// escapes the closing bracket and produces an unclosed character class.
private val SCAN_BOUNDARY_REGEX =
    Regex("""[\s　。、！？…‥「」『』（）()【】〈〉《》〔〕｛｝{}\[\]・：；:;，,.─]""")

// \w plus Latin-1 Supplement/Extended-A, Arabic, and Hangul - the set the WebView accepts.
private val WORD_CHAR_REGEX = Regex("""[\wÀ-ɏ؀-ۿᄀ-ᇿ㄰-㆏㐀-䶿가-힯]""")


/**
 * CJK ideographs and kana, plus Hangul - Korean recursive lookup is a supported feature here, so
 * the reference renderer deliberately treats Hangul as CJK for tap-targeting purposes.
 */
public fun Char.isLookupCjk(): Boolean = when (this) {
    in '぀'..'ヿ' -> true // hiragana + katakana
    in '　'..'鿿' -> true // CJK radicals through CJK unified ideographs (incl. U+3000)
    in '豈'..'﫿' -> true // CJK compatibility ideographs
    in '＀'..'￯' -> true // halfwidth and fullwidth forms
    in 'ᄀ'..'ᇿ' -> true // Hangul jamo
    in '㄰'..'㆏' -> true // Hangul compatibility jamo
    in '㐀'..'䶿' -> true // Hangul extended-A
    in '가'..'힯' -> true // Hangul syllables
    else -> false
}

private fun Char.isLookupWordChar(): Boolean = isLookupCjk() || WORD_CHAR_REGEX.matches(this.toString())

private fun Char.isScanBoundary(): Boolean = SCAN_BOUNDARY_REGEX.matches(this.toString())

/**
 * The word a tap at [offset] should look up.
 *
 * Mirrors the reference renderer's `extractTextAtPoint`, which uses `caretRangeFromPoint` to turn
 * a tap into a character offset and then expands it. Without this the whole rendered run is the
 * lookup target, so tapping one character in a sentence searched for the entire sentence.
 *
 * CJK expands FORWARD only (up to [LOOKUP_SCAN_MAX_CHARS]) because there are no spaces to expand
 * within and the backend handles deinflection of the resulting prefix; other scripts expand in both
 * directions to word characters.
 *
 * Returns null when the offset is not on a word character, so a tap on punctuation does nothing.
 */
public fun lookupWordAt(text: String, offset: Int): String? {
    if (text.isEmpty()) return null
    val i = offset.coerceIn(0, text.length - 1)
    val startChar = text[i]
    if (startChar.isScanBoundary() || !startChar.isLookupWordChar()) return null

    if (startChar.isLookupCjk()) {
        var end = i
        val limit = minOf(text.length, i + LOOKUP_SCAN_MAX_CHARS)
        while (end < limit && !text[end].isScanBoundary()) end++
        return text.substring(i, end).takeIf { it.isNotEmpty() }
    }

    var start = i
    while (start > 0 && !text[start - 1].isScanBoundary() && text[start - 1].isLookupWordChar()) start--
    var end = i + 1
    while (end < text.length && !text[end].isScanBoundary() && text[end].isLookupWordChar()) end++
    return text.substring(start, end).takeIf { it.isNotEmpty() }
}

public fun StructuredNode.collect(): String = if (this is StructuredNode.Text) text
    else buildString { this@collect.appendCollectedTo(this) }




// Color resolution lives in CssColors.kt (parseCssColor2 / blendColorMix shared with parser).

private val QUERY_PARAM_REGEX = Regex("[?&]([^=]+)=([^&]+)")

public fun extractQuery(href: String): String? {
    val params = QUERY_PARAM_REGEX.findAll(href)
    return params.asSequence().mapNotNull { m ->
        if (m.groupValues[1] == "query") m.groupValues[2].replace("+", " ") else null
    }.firstOrNull()?.let { raw ->
        // WebView's URLSearchParams.get() percent-decodes; match it so kanji
        // queries (`?query=%E4%B8%80…`) look up instead of missing.
        runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }
}

public fun resolveMediaUri2(dictName: String, path: String, map: Map<String, String>): String? {
    if (map.isEmpty()) return null
    val cleaned = path.removePrefix("media://").removePrefix("media:").trimStart('/')
    for (candidate in listOf("$dictName\u0000$path", "$dictName\u0000$cleaned")) {
        map[candidate]?.let { return it }
    }
    return null
}
