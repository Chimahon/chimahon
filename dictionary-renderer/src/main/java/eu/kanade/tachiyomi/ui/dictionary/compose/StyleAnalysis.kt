package eu.kanade.tachiyomi.ui.dictionary.compose

/**
 * Pure style analysis. No Compose, no Android, no measurement, no drawing.
 *
 * Everything here is a total function from data in to data out, which is what makes the analysis
 * separable from the renderer: the cascade can be resolved, inspected and tested without a device,
 * a `TextMeasurer`, or a frame. [parseBoxStyle] in `DictionaryCssParser.kt` is now only the adapter
 * that turns these values into Compose's `BoxStyle`.
 *
 * Naming: `StyleAnalysis`, not `DictionaryCssParser` (that owns parsing and cascade order) and not
 * `NativeGlossaryCompiler` (that owns layout). This file is the pure middle.
 *
 * Values are stored resolved and typed rather than as `Map<String, String>`, so the three consumers
 * that used to each re-walk one declaration map (box metrics, typography, span styling) read fields.
 */

private val WHITESPACE = Regex("\\s+")
private val NAMED_COLOR = Regex("""[a-zA-Z#][a-zA-Z0-9#]*""")
private val BORDER_STYLE = setOf("none", "hidden", "dotted", "dashed", "solid", "double", "groove", "ridge", "inset", "outset")

/** CSS `text-align`, resolved. Kept free of Compose's `TextAlign` so this file stays pure. */
public enum class TextAlignValue { START, CENTER, END, JUSTIFY }

/**
 * Resolved box metrics for one element, in dp.
 *
 * A null float means the stylesheet did not declare it, which is distinct from zero: the two drive
 * different fallbacks downstream (e.g. `marginTop ?: fallbackTop` vs. a declared `0`).
 */
public class BoxProps(
    val hasBackground: Boolean = false,
    val hasBorder: Boolean = false,
    val leftAccent: Boolean = false,
    val borderRadius: Float? = null,
    val paddingStart: Float? = null,
    val paddingEnd: Float? = null,
    val paddingTop: Float? = null,
    val paddingBottom: Float? = null,
    val marginStart: Float? = null,
    val marginEnd: Float? = null,
    val marginTop: Float? = null,
    val marginBottom: Float? = null,
    val borderColor: String? = null,
    /** Declared `border-width` in dp, or null when the stylesheet does not set one. */
    val borderWidth: Float? = null,
    val backgroundColor: String? = null,
    val opacity: Float? = null,
    val textAlign: TextAlignValue? = null,
    val verticalAlign: String? = null,
    /** `word-break: keep-all` — a CJK cell that must not wrap. */
    val wordBreak: String? = null,
    /** `width: fit-content` — shrink-wrap instead of stretching to the parent. */
    val fitContent: Boolean = false,
) {
    val hasPadding: Boolean
        get() = paddingStart != null || paddingEnd != null || paddingTop != null || paddingBottom != null
}

/**
 * Interprets one combined declaration map into typed [BoxProps].
 *
 * `styleMap` is the cascade result for a single element (see [getCssStyles], [getNestedStyles],
 * [getSiblingStyles], merged in specificity order by the caller). `baseFontSizeSp` converts em/rem.
 *
 * The map is walked once, in whatever order it happens to iterate; every arm is order-independent
 * except the border shorthand rules, whose "first writer wins" semantics rely on the cascade having
 * already resolved conflicts upstream.
 */
public fun analyzeBoxProps(styleMap: Map<String, String>, baseFontSizeSp: Float): BoxProps {
    var hasBackground = false
    var hasBorder = false
    var leftAccent = false
    var borderRadius: Float? = null
    var paddingStart: Float? = null
    var paddingEnd: Float? = null
    var paddingTop: Float? = null
    var paddingBottom: Float? = null
    var marginStart: Float? = null
    var marginEnd: Float? = null
    var marginTop: Float? = null
    var marginBottom: Float? = null
    var borderColor: String? = null
    var borderWidth: Float? = null
    var backgroundColor: String? = null
    var opacity: Float? = null
    var textAlign: TextAlignValue? = null
    var verticalAlign: String? = null
    var wordBreak: String? = null
    var fitContent = false

    for ((key, value) in styleMap) {
        when (key) {
            "width" -> {
                if (value.trim().lowercase() == "fit-content") fitContent = true
            }
            "opacity" -> {
                value.trim().toFloatOrNull()?.let { parsed ->
                    if (parsed in 0f..1f) opacity = parsed
                    else if (parsed > 1f && parsed <= 100f) opacity = (parsed / 100f).coerceIn(0f, 1f)
                }
            }
            "textAlign" -> {
                textAlign = when (value.trim().lowercase()) {
                    "right", "end" -> TextAlignValue.END
                    "center" -> TextAlignValue.CENTER
                    "left", "start" -> TextAlignValue.START
                    "justify" -> TextAlignValue.JUSTIFY
                    else -> null
                }
            }
            "verticalAlign" -> {
                verticalAlign = value.trim().lowercase()
            }
            "wordBreak" -> {
                // "keep-all" has no Compose equivalent; ensure we don't break CJK incorrectly.
                wordBreak = value.trim().lowercase()
            }
            "backgroundColor", "background" -> {
                backgroundColor = extractBackgroundColor(value)
                if (backgroundColor != null &&
                    backgroundColor != "transparent" &&
                    backgroundColor != "inherit" &&
                    backgroundColor != "none"
                ) {
                    hasBackground = true
                }
            }
            "border", "borderColor", "borderWidth", "borderStyle", "borderLeftColor" -> {
                if (value.isNotBlank() && value != "none" && value != "0" && value != "0px") {
                    when (key) {
                        "borderWidth" -> borderWidth = parseDpValue(value, baseFontSizeSp)
                        "border" -> {
                            // `border: 2px solid var(--background-color)` - the first length is the
                            // width, then a style keyword, then the colour. Only a real colour token
                            // is taken: reading `solid`/`dashed`/etc. as the colour painted the whole
                            // grid in an unparseable value, because the first non-length token in a
                            // shorthand is the STYLE, not the colour.
                            val tokens = value.trim().split(WHITESPACE).filter { it.isNotBlank() }
                            val w = tokens.firstOrNull()?.let { parseDpValue(it, baseFontSizeSp) }
                            if (w != null) borderWidth = w
                            val colorTok = tokens.drop(1).firstOrNull {
                                it.lowercase() !in BORDER_STYLE && (it.startsWith("#") || NAMED_COLOR.matches(it))
                            }
                            if (colorTok != null && borderColor == null) {
                                borderColor = extractBackgroundColor(colorTok) ?: colorTok
                            }
                        }
                        "borderStyle" -> {
                            // `none none none solid` / `none solid` -> left accent bar only.
                            // A plain `solid` (all sides) is a full frame.
                            val tokens = value.split(WHITESPACE).filter { it.isNotBlank() }
                            if (tokens.isNotEmpty() &&
                                tokens.all { it == "none" || it == "solid" } &&
                                tokens.any { it == "solid" } &&
                                tokens.any { it == "none" }
                            ) {
                                leftAccent = true
                            }
                        }
                        "borderLeftColor" -> if (borderColor == null) {
                            borderColor = extractBackgroundColor(value) ?: value.takeIf { it.startsWith("#") }
                            leftAccent = true
                        }
                        "borderColor" -> if (borderColor == null) {
                            borderColor = extractBackgroundColor(value) ?: value.takeIf { it.startsWith("#") }
                            // `border-color` alone: CSS default style is none, but the reference
                            // renderer draws these as coloured accent boxes.
                            leftAccent = true
                        }
                    }
                    if (leftAccent) {
                        hasBorder = true
                    } else {
                        // `border-color` alone produces no visible box border in CSS (border-style
                        // defaults to none); treat it as an accent edge, not a full frame.
                        hasBorder = key == "border" || key == "borderWidth"
                        if (key == "borderWidth" && value != "0px" && value != "0") hasBorder = true
                        if (key == "borderStyle" && value.contains("solid")) hasBorder = true
                    }
                }
            }
            "borderRadius" -> borderRadius = parseDpValue(value, baseFontSizeSp)
            "padding" -> {
                val (top, right, bottom, left) = parseFourValue(value, baseFontSizeSp)
                if (top != null) paddingTop = top
                if (right != null) paddingEnd = right
                if (bottom != null) paddingBottom = bottom
                if (left != null) paddingStart = left
            }
            "paddingLeft", "paddingInlineStart" -> paddingStart = parseDpValue(value, baseFontSizeSp)
            "paddingRight", "paddingInlineEnd" -> paddingEnd = parseDpValue(value, baseFontSizeSp)
            "paddingTop", "paddingBlockStart" -> paddingTop = parseDpValue(value, baseFontSizeSp)
            "paddingBottom", "paddingBlockEnd" -> paddingBottom = parseDpValue(value, baseFontSizeSp)
            "paddingBlock" -> {
                val (top, bottom) = parseLogicalPair(value, baseFontSizeSp)
                if (top != null) paddingTop = top
                if (bottom != null) paddingBottom = bottom
            }
            "paddingInline" -> {
                val (start, end) = parseLogicalPair(value, baseFontSizeSp)
                if (start != null) paddingStart = start
                if (end != null) paddingEnd = end
            }
            "margin" -> {
                val (top, right, bottom, left) = parseFourValue(value, baseFontSizeSp)
                if (top != null) marginTop = top
                if (right != null) marginEnd = right
                if (bottom != null) marginBottom = bottom
                if (left != null) marginStart = left
            }
            "marginLeft", "marginInlineStart" -> marginStart = parseMarginValue(value, baseFontSizeSp)
            "marginRight", "marginInlineEnd" -> marginEnd = parseMarginValue(value, baseFontSizeSp)
            "marginTop", "marginBlockStart" -> marginTop = parseMarginValue(value, baseFontSizeSp)
            "marginBottom", "marginBlockEnd" -> marginBottom = parseMarginValue(value, baseFontSizeSp)
            "borderInlineStart", "borderLeft" -> if (value.isNotBlank() && value != "none" && value != "0") {
                leftAccent = true
                hasBorder = true
                // Shorthand components may appear in any order; preserve fractional widths.
                borderWidth = value.split(WHITESPACE).firstNotNullOfOrNull {
                    parseDpValue(it, baseFontSizeSp)
                } ?: borderWidth
                borderColor = extractBackgroundColor(value) ?: value.takeIf { it.startsWith("#") }
            }
            // Opacity / textAlign etc. handled above; remaining keys fall through safely.
        }
    }

    // A `border-style: none none none solid` + left border-width means a left accent border.
    // Ensure a visible border even when `borderColor` came only as a shorthand `border` etc.
    return BoxProps(hasBackground, hasBorder, leftAccent, borderRadius,
        paddingStart, paddingEnd, paddingTop, paddingBottom,
        marginStart, marginEnd, marginTop, marginBottom,
        borderColor, borderWidth, backgroundColor, opacity, textAlign, verticalAlign, wordBreak, fitContent)
}
