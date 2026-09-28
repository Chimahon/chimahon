package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import coil3.compose.AsyncImage
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density

/**
 * Renders a parsed [StructuredEntry.Tree] with dictionary CSS (backgrounds, borders, tag chips,
 * lists, forms tables, links, ruby) applied — semantically matching the WebView renderer for
 * structured dictionaries like Jitendex. Fidelity is "good-enough": gradients / color-mix are
 * approximated with theme-aware solid colors.
 */
@Composable
internal fun StructuredGlossaryContent(
    nodes: List<StructuredNode>,
    parsedCss: ParsedCss,
    dictName: String,
    mediaDataUris: Map<String, String>,
    fontSize: Int,
    onBg: Color,
    secondary: Color,
    border: Color,
    onRecursiveLookup: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    ancestors: List<CssAncestor> = emptyList(),
    // The WebView injects a user-chosen dictionary font as an `@font-face` named `HoshiCustomFont`
    // (see getDictionaryBootstrapHtml), so the Compose renderer needs a way to be told the same
    // family - otherwise a custom font silently does nothing here, and any comparison against the
    // WebView would be measuring two different typefaces. Null keeps the platform default.
    fontFamily: FontFamily? = null,
    onLineMetrics: ((TextLayoutResult) -> Unit)? = null,
) {
    val baseStyle = TextStyle(
        color = onBg,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * DEFAULT_LINE_HEIGHT_FACTOR).sp,
        fontFamily = fontFamily ?: FontFamily.Default,
        // CSS Inline 3 5.3: L = line-height - (A + D), with the surplus split as A' = A + L/2 above
        // and D' = D + L/2 below, so a line box is `line-height` tall even when it holds one short
        // line. Compose by default leaves the first line at the font's own height and adds the surplus
        // after it, which shortens every single-line block by the whole half-leading. `Center` with
        // `Trim.None` is the equivalent of half-leading.
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )
    val runs = remember(nodes, parsedCss) { partitionInlineRuns(nodes, parsedCss) }
    Column(modifier = modifier) {
        FlowRuns(runs, parsedCss, baseStyle, dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors, onLineMetrics = onLineMetrics)
    }
}

@Composable
private fun StructuredNodeView(
    node: StructuredNode,
    parsedCss: ParsedCss,
    style: TextStyle,
    dictName: String,
    mediaDataUris: Map<String, String>,
    secondary: Color,
    border: Color,
    onRecursiveLookup: ((String) -> Unit)?,
    ancestors: List<CssAncestor> = emptyList(),
    onLineMetrics: ((TextLayoutResult) -> Unit)? = null,
) {
    when (node) {
        is StructuredNode.Text -> spanText(node.text, style, onRecursiveLookup)
        StructuredNode.TextBreak -> Spacer(Modifier.height(2.dp))
        is StructuredNode.Element -> StructuredElementView(
            node, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors,
            onLineMetrics = onLineMetrics,
        )
    }
}

/** Tags that participate in inline text flow and must never become block containers. */
private val INLINE_LEVEL_TAGS = setOf(
    StructuredTag.Span,
    StructuredTag.Bold, StructuredTag.Italic, StructuredTag.Underline,
    StructuredTag.Strike, StructuredTag.Superscript, StructuredTag.Subscript,
    StructuredTag.Small, StructuredTag.Mark,
)

/** `body { line-height: var(--line-height) }` in base.css (`--line-height: 1.4`). */
internal const val DEFAULT_LINE_HEIGHT_FACTOR = 1.4f

// List geometry defaults from base.css. The rules are keyed on `.gloss-sc-ol` / `.gloss-sc-ul`, classes
// only the reference renderer puts in the DOM, so the Compose side cannot match them from a node's own
// attributes and has to carry the values. They are declared once here because TWO owners need them:
// `StructuredList` renders with them, and `FlowRuns` needs them to collapse the margins correctly.
private const val LIST_PADDING_START_EM = 1.2f // base.css:638
/** `table { margin: 6px 0 }` - base.css:652. In px, not em, as the declaration is. */
private const val TABLE_MARGIN_PX = 6f
/** Padding the reference computes on `th` / `td`. */
private const val CELL_PADDING_PX = 3f
private const val LIST_MARGIN_EM = 0.3f // base.css:638
private const val LIST_ITEM_MARGIN_EM = 0.15f // base.css:643

// ---------------------------------------------------------------------------
// Inline runs as paragraphs
//
// An inline run is ONE `Text`, so the engine owns line breaking: one shared baseline per line, breaks
// between words rather than at node boundaries, and ruby runs stay atomic. Rendering each node
// separately would give every node its own line box, so leading would vary per line.
//
// Ruby is an `InlineTextContent` placeholder. `AboveBaseline` pins the rect's BOTTOM to the line
// baseline and the engine grows the line box to `placeholderHeight + descent`, so the base's baseline
// lands on the line's baseline with no line-height fudge. See [RubyMetrics].
// ---------------------------------------------------------------------------


/** A flattened inline run: the paragraph text plus the composables its placeholders draw. */
private class InlineParagraph(
    val text: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
    /**
     * Line height for this paragraph, in sp: the largest any span in the run asks for.
     *
     * CSS inherits `line-height: 1.4` as a *number*, so it scales with each element's own font size.
     * Compose puts `lineHeight` on the paragraph rather than the span, so a run with an enlarged span
     * would otherwise keep the base line box.
     */
    val lineHeightSp: Float,
    /** CSS half-leading in px, from [measureLineBox]; applied as the paragraph's top inset. */
    val halfLeadingPx: Float,
)

/** Thrown by the paragraph builder for constructs it does not model, so the caller can fall back. */
private class UnsupportedInline : RuntimeException(null, null, false, false)

/**
 * Pixel geometry of one ruby run inside its placeholder rect.
 *
 * Let `BB` be the base text's first baseline, `BH` its height and `RH` the reading's height. The
 * rect's bottom edge sits on the line baseline and the base is drawn `baseY` below the rect's top, so
 * the base's baseline lands at `baseline - H + baseY`. Requiring that to equal the line baseline
 * gives `H = baseY + BB`. The base's descender then paints `BH - BB` below the baseline, which is
 * correct and is exactly the extra the engine adds to the line box.
 *
 * `baseY` is less than `RH` on purpose: the reading's line box is ~0.55em, and stacking the base
 * fully below it left a ~0.24em hole that read as stacked text. See [RUBY_BASE_OVERLAP_EM].
 */
internal class RubyMetrics(
    val widthPx: Int,
    val heightPx: Int,
    val baseOffsetPx: Int,
    val baseText: String,
    val readingText: String,
)

internal fun measureRubyInline(
    measurer: TextMeasurer,
    base: String,
    reading: String,
    style: TextStyle,
    density: Density,
): RubyMetrics {
    val readingStyle = rubyReadingStyle(style)
    val baseLayout = measurer.measure(AnnotatedString(base), style)
    val readingLayout = measurer.measure(AnnotatedString(reading), readingStyle)
    // The strut comes from the same [measureLineBox] the paragraph and marker use, so all three share
    // one definition. It used to read `style.fontSize.value` - the sp NUMBER - which is only the
    // pixel size at density 1, so on a real device the reading and base were 2.75x too far apart.
    val strut = measureLineBox(measurer, density, style)
    val fontBoxPx = strut.ascentPx + strut.descentPx
    val baseY = (readingLayout.size.height - fontBoxPx * RUBY_BASE_OVERLAP_EM)
        .toInt()
        .coerceAtLeast(1)
    return RubyMetrics(
        widthPx = maxOf(baseLayout.size.width, readingLayout.size.width),
        heightPx = baseY + baseLayout.firstBaseline.toInt(),
        baseOffsetPx = baseY,
        baseText = base,
        readingText = reading,
    )
}

/** Draws one ruby run inside its placeholder rect, in exact pixels. */
@Composable
private fun RubyInlineContent(m: RubyMetrics, style: TextStyle) {
    val readingStyle = remember(style) { rubyReadingStyle(style) }
    Layout(
        content = {
            Text(
                text = m.readingText,
                style = readingStyle,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
            Text(
                text = m.baseText,
                style = style,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
            )
        },
    ) { measurables, _ ->
        val reading = measurables[0].measure(Constraints())
        val base = measurables[1].measure(Constraints())
        val w = maxOf(reading.width, base.width)
        layout(w, m.heightPx) {
            reading.placeRelative((w - reading.width) / 2, 0)
            base.placeRelative((w - base.width) / 2, m.baseOffsetPx)
        }
    }
}

/**
 * The subset of a computed [TextStyle] a [SpanStyle] can carry.
 *
 * Reuses [applyTypography] rather than re-parsing CSS, so inline runs and block boxes cannot drift
 * apart on colour/weight/decoration handling. `lineHeight` and `textAlign` are deliberately dropped:
 * they are paragraph-level in CSS and meaningless on a span.
 */
/**
 * Baseline offset for `vertical-align: super` / `sub`, in CSS px, from a parent font size.
 *
 * The specification fixes the ratio but not a formula:
 *  - CSS Inline 3 defines `super` as raising the box by "the appropriate offset from the font
 *    metrics", and permits a font-size-based fallback.
 *  - The CSS WG resolved the fallback to 1/3 em for `super` and 0.2 em for `sub`
 *    (w3c/csswg-drafts#5225, "vertical-align: super and font metrics").
 *
 * Blink and WebKit implement that ratio and then add one more pixel:
 *  - Blink, `ng_inline_box_state.cc` `ApplyBaselineShift`: `super = fontSize / 3 + 1`,
 *    `sub = fontSize / 5 + 1`.
 *  - WebKit, `InlineLineBoxVerticalAligner.cpp`: the same two expressions.
 *
 * The extra pixel predates the WebKit fork - it entered KHTML in 2001 (kdelibs 2ca6dbfc, changing
 * `1em/5` to `1em/5 + 1px`) with no stated rationale - so it is an implementation detail rather than
 * part of the standard. It is reproduced here because the renderer under comparison IS a Chromium
 * WebView, and omitting it would put every superscript 1px off the reference.
 */
private fun superBaselineShiftPx(parentFontSizePx: Float): Float = parentFontSizePx / 3f + 1f

/** `sub` lowers rather than raises, and uses the 1/5 ratio rather than 1/3. */
private fun subBaselineShiftPx(parentFontSizePx: Float): Float = parentFontSizePx / 5f + 1f

private fun spanStyleFor(base: TextStyle, css: Map<String, String>, density: Float = 1f): SpanStyle {
    val t = applyTypography(base, css)
    val background = css["backgroundColor"]?.let { raw ->
        val hex = if (raw.contains("color-mix", ignoreCase = true)) blendColorMix(raw) ?: raw else raw
        parseCssColor2(hex)
    }
    return SpanStyle(
        // Color.Unspecified is Compose's "leave alone" value; only emit what actually changed.
        color = t.color.takeIf { it != base.color } ?: Color.Unspecified,
        background = background ?: Color.Unspecified,
        fontSize = t.fontSize.takeIf { it != base.fontSize } ?: TextUnit.Unspecified,
        fontWeight = t.fontWeight.takeIf { it != base.fontWeight },
        fontStyle = t.fontStyle.takeIf { it != base.fontStyle },
        textDecoration = t.textDecoration.takeIf { it != base.textDecoration },
        baselineShift = t.baselineShift.takeIf { it != base.baselineShift },
    )
}

/**
 * Flattens an inline run into a single paragraph.
 *
 * Returns null for anything it does not model (a block element, or a padded/bordered box inside the
 * run) so the caller can fall back to the per-node path rather than silently drop content.
 */
private class InlineParagraphBuilder(
    private val style: TextStyle,
    private val parsedCss: ParsedCss,
    private val measurer: TextMeasurer,
    private val density: Density,
    private val lookup: ((String) -> Unit)?,
    private val ancestors: List<CssAncestor>,
) {
    private val builder = AnnotatedString.Builder()
    private val content = mutableMapOf<String, InlineTextContent>()
    private var nextId = 0

    /**
     * Largest line height any span in this run asks for, in sp. `lineHeight` is a paragraph property
     * in Compose, so the run has to hand the paragraph the biggest value its spans resolved to -
     * see [InlineParagraph.lineHeightSp].
     */
    private var maxLineHeightSp = 0f

    /** Set when the run contains something this builder cannot represent. */
    var unsupported = false
        private set

    private fun cssFor(node: StructuredNode.Element): Map<String, String> =
        getCssStyles(node.attributes.data, parsedCss) +
            getNestedStyles(node.tag.tagName, node.attributes.data, ancestors, parsedCss) +
            node.attributes.style

    private fun isHidden(css: Map<String, String>): Boolean {
        val display = css["display"]?.trim()?.lowercase()
        val visibility = css["visibility"]?.trim()?.lowercase()
        return display == "none" || visibility == "hidden" || visibility == "collapse"
    }

    fun build(nodes: List<StructuredNode>): InlineParagraph? {
        maxLineHeightSp = style.lineHeight.let { if (it.isSp) it.value else 0f }
        walk(nodes)
        if (unsupported || builder.length == 0) return null
        val box = measureLineBox(measurer, density, style)
        return InlineParagraph(builder.toAnnotatedString(), content, maxLineHeightSp, box.halfLeadingPx)
    }

    /** Records the line height a span's resolved style asks for, scaled to that span's font size. */
    private fun noteLineHeight(css: Map<String, String>) {
        val resolved = applyTypography(style, css)
        val lh = resolved.lineHeight
        if (lh.isSp) maxLineHeightSp = maxOf(maxLineHeightSp, lh.value)
    }

    private fun walk(list: List<StructuredNode>) {
        for (node in list) {
            when (node) {
                is StructuredNode.Text -> builder.append(node.text)
                is StructuredNode.TextBreak -> builder.append('\n')
                is StructuredNode.Element -> walkElement(node)
            }
        }
    }

    private fun walkElement(node: StructuredNode.Element) {
        val css = cssFor(node)
        if (isHidden(css)) return
        noteLineHeight(css)
        when (node.tag) {
            StructuredTag.Ruby -> walkRuby(node)
            StructuredTag.Link -> walkLink(node, css)
            StructuredTag.Break -> builder.append('\n')
            else -> {
                if (!isInlineLevel(node)) {
                    unsupported = true
                    return
                }
                // Padding/border need a real box, which a SpanStyle cannot express; leave those to
                // the per-node path rather than flattening them to bare text.
                val baseFontSp = fontSizeSp(style)
                val box = parseBoxStyle(css, baseFontSp)
                if (box.hasPadding || box.hasBorder || box.leftAccent) {
                    unsupported = true
                    return
                }
                builder.pushStyle(spanStyleFor(style, css, density.density))
                appendInlineMargins(css, before = true)
                walk(node.children)
                appendInlineMargins(css, before = false)
                builder.pop()
            }
        }
    }

    /**
     * Horizontal margins on an inline box occupy line space (CSS 2.1 10.3.2, 8.3.1). A
     * [androidx.compose.ui.text.SpanStyle] cannot express a margin, so the space is added as one
     * space character whose `letterSpacing` is the difference between the margin and that space's
     * natural advance. Measuring the space keeps the added advance exactly equal to the margin, so
     * following wrap points stay where the browser puts them.
     */
    private fun appendInlineMargins(css: Map<String, String>, before: Boolean) {
        val key = if (before) "marginLeft" else "marginRight"
        val raw = css[key]?.trim() ?: return
        val em = raw.removeSuffix("em").toFloatOrNull()?.takeIf { it != 0f } ?: return
        val spanSp = fontSizeSp(applyTypography(style, css, density.density))
        val pxPerSp = density.density
        val targetPx = em * spanSp * pxPerSp
        val spacePx = measurer
            .measure(AnnotatedString(" "), applyTypography(style, css))
            .size
            .width
            .toFloat()
        val extra = targetPx - spacePx
        // A negative target cannot be represented by a space, and a zero-width margin needs no filler.
        if (targetPx <= 0f || extra == 0f) return
        builder.pushStyle(SpanStyle(letterSpacing = (extra / pxPerSp).sp))
        builder.append(' ')
        builder.pop()
    }

    private fun walkRuby(node: StructuredNode.Element) {
        val base = node.children
            .filterNot {
                it is StructuredNode.Element &&
                    (it.tag == StructuredTag.Rt || it.tag == StructuredTag.Rp)
            }
            .joinToString("") { it.collect() }
        val reading = node.children
            .filterIsInstance<StructuredNode.Element>()
            .filter { it.tag == StructuredTag.Rt }
            .joinToString("") { rt -> rt.children.joinToString("") { it.collect() } }
        if (reading.isBlank() || base.isBlank()) {
            builder.append(base)
            return
        }
        val m = measureRubyInline(measurer, base, reading, style, density)
        val id = "r${nextId++}"
        // The rect is computed in px; the engine wants sp, hence the density round-trip.
        val wSp = with(density) { m.widthPx.toDp().toSp() }
        val hSp = with(density) { m.heightPx.toDp().toSp() }
        content[id] = InlineTextContent(
            Placeholder(
                width = wSp,
                height = hSp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.AboveBaseline,
            ),
        ) {
            RubyInlineContent(m, style)
        }
        // alternateText keeps the base characters in the string, so tap-to-lookup offsets still
        // resolve onto a ruby base, while the engine treats the whole range as one atomic inline.
        builder.appendInlineContent(id, base)
    }

    private fun walkLink(node: StructuredNode.Element, css: Map<String, String>) {
        val href = node.attributes.properties["href"]
        val span = spanStyleFor(style, css)
        // A gloss link is NOT underlined. renderer.js turns structured-content `<a>` into
        // `<span class="gloss-link" data-href=...>`, and base.css 657 gives `.gloss-link` only
        // `color: inherit`. A span never matches the UA stylesheet's `a:link { text-decoration:
        // underline }`, so the browser draws none.
        // Decoration therefore comes only from the source, via [spanStyleFor].
        val linkSpan = span.copy(
            color = if (span.color == Color.Unspecified) style.color else span.color,
        )
        val start = builder.length
        builder.pushStyle(linkSpan)
        walk(node.children)
        builder.pop()
        val plain = node.collect()
        val target = href?.let { extractQuery(it) ?: plain } ?: plain
        if (href != null && target.isNotEmpty() && start < builder.length) {
            val cb = lookup
            builder.addLink(
                // TextLinkStyles is not optional here: with `styles = null` the platform text style
                // paints the link range in its own accent colour, which overrode the run's SpanStyle
                // and rendered 新明解's `塞翁が馬` magenta instead of the dictionary's colour.
                LinkAnnotation.Clickable(
                    tag = target,
                    styles = TextLinkStyles(
                        style = linkSpan,
                        focusedStyle = linkSpan,
                        hoveredStyle = linkSpan,
                        pressedStyle = linkSpan,
                    ),
                ) { cb?.invoke(target) },
                start,
                builder.length,
            )
        }
    }
}


/** A run of children flowing inline (one wrapped line-run) vs a block-level node. */
private sealed interface FlowRun {
    data class Inline(val nodes: List<StructuredNode>) : FlowRun
    data class Block(val node: StructuredNode) : FlowRun
}

/**
 * A block's (top, bottom) margins as its PARENT's collapsing sees them (CSS 2.1 8.3.1).
 *
 * A first child's top margin collapses through a parent that has no border, padding or preceding
 * in-flow content, and a last child's bottom margin likewise. When that happens the child's margin
 * becomes the parent's own, so it has to be hoisted here rather than applied as inner padding - which
 * is also the only way a margin survives when the escape happens several boxes deep.
 *
 * Pure, so [FlowRuns] can compute every sibling's contribution before composing any of them.
 */
private fun effectiveVMargins(
    node: StructuredNode.Element,
    parsedCss: ParsedCss,
    ancestors: List<CssAncestor>,
    style: TextStyle,
    baseFontSizeSp: Float,
): Pair<Float, Float> {
    val nested = getNestedStyles(node.tag.tagName, node.attributes.data, ancestors, parsedCss)
    val map = getCssStyles(node.attributes.data, parsedCss) + nested + node.attributes.style
    val box = parseBoxStyle(map, baseFontSizeSp)
    val isList = node.tag == StructuredTag.UnorderedList || node.tag == StructuredTag.OrderedList
    val isTable = node.tag == StructuredTag.Table
    // Fallbacks for values base.css declares with a bare type selector, which [getCssStyles] cannot
    // reach: the WebView loads base.css but the Compose side only parses dictionary CSS, so the
    // values the reference relies on are carried here instead. `table { margin: 6px 0 }` is
    // base.css:652 - without it a table sits flush against the block above it.
    val fallbackTop = when {
        isList -> LIST_MARGIN_EM * baseFontSizeSp
        isTable -> TABLE_MARGIN_PX
        else -> 0f
    }
    var top = box.marginTop ?: fallbackTop
    var bottom = box.marginBottom ?: fallbackTop

    // Only a box that establishes no barrier lets its child's margin through.
    val transparent = !box.hasBorder && box.paddingTop == null && box.paddingBottom == null
    if (transparent) {
        val childRuns = partitionInlineRuns(node.children, parsedCss)
        val first = childRuns.firstOrNull { it.producesBox(parsedCss) }
        val last = childRuns.lastOrNull { it.producesBox(parsedCss) }
        if (first is FlowRun.Block && first.node is StructuredNode.Element) {
            top = maxOf(top, effectiveVMargins(first.node, parsedCss, ancestors, style, baseFontSizeSp).first)
        }
        if (last is FlowRun.Block && last.node is StructuredNode.Element) {
            bottom = maxOf(bottom, effectiveVMargins(last.node, parsedCss, ancestors, style, baseFontSizeSp).second)
        }
    }
    return top to bottom
}

/** True when this run generates a box: a block does, an inline run does only if it renders anything. */
private fun FlowRun.producesBox(parsedCss: ParsedCss): Boolean = when (this) {
    is FlowRun.Block -> true
    is FlowRun.Inline -> nodes.fold("") { acc, n -> acc + n.collectVisible(parsedCss) }.isNotBlank()
}

/** True for nodes that participate in inline text flow (HTML inline-level content). */
private fun isInlineLevel(node: StructuredNode): Boolean = when (node) {
    is StructuredNode.Text, is StructuredNode.TextBreak -> true
    is StructuredNode.Element -> when (node.tag) {
        StructuredTag.Span, StructuredTag.Ruby, StructuredTag.Link,
        StructuredTag.Bold, StructuredTag.Italic, StructuredTag.Underline,
        StructuredTag.Strike, StructuredTag.Superscript, StructuredTag.Subscript,
        StructuredTag.Small, StructuredTag.Mark -> true
        else -> false
    }
    else -> false
}

/**
 * True when the dictionary CSS promotes this otherwise-inline node to a block box.
 *
 * Dictionaries routinely restyle `span`s that Yomitan would render inline, e.g. Oxford's
 * `span[data-sc-content="semb x_xd1 hasSn"] { display: block }`. Without this every sense collapsed
 * sideways onto one line: three Oxford entries rendered as `① transfer ② investigate ③ ask for`
 * where the browser put each on its own line. `display` is a unitless keyword, so this needs no
 * base font size.
 */
private fun isDisplayBlock(node: StructuredNode, parsedCss: ParsedCss): Boolean {
    if (node !is StructuredNode.Element) return false
    return getCssStyles(node.attributes.data, parsedCss)["display"]?.trim()?.lowercase() == "block"
}

/**
 * True while [FlowRuns] is laying out a block, meaning the flow - not the block itself - owns the
 * vertical margins so it can collapse them.
 *
 * CSS collapses adjacent siblings' vertical margins to the largest of the two and lets a child's
 * outer margin escape its parent when the parent has no border/padding, so `margin: 1em 0` on
 * three sibling senses gives 1em between them, not 6em. Compose's `padding` simply adds, which
 * made three Oxford senses 84px taller than the browser's 169px. Under this local the block drops
 * its own top/bottom margin and [FlowRuns] inserts the collapsed gap instead.
 */
private val LocalCollapsesOwnVMargin = compositionLocalOf { false }

/**
 * The `data-*` maps of the siblings preceding this node, nearest first, for resolving `A + B` / `A ~ B`
 * rules. Jitendex spaces its senses with
 * `li[data-sc-content="sense-group"] + li[data-sc-content="sense-group"] { margin-top: .5em }`.
 */
private val LocalPrecedingSiblings = compositionLocalOf { emptyList<Map<String, String>>() }

/** True when this subtree holds a `<ruby>` (directly or nested in spans/links). */
private fun StructuredNode.containsRuby(): Boolean = when (this) {
    is StructuredNode.Text, StructuredNode.TextBreak -> false
    is StructuredNode.Element -> tag == StructuredTag.Ruby || children.any { it.containsRuby() }
}

/**
 * Partition children into inline runs (each rendered in one FlowRow so sentences wrap
 * like the WebView instead of stacking one node per line) and block nodes (full-width).
 * Adjacent plain Texts are merged so per-character splits don't explode the composition.
 * Break nodes split runs (explicit line break).
 */
private fun partitionInlineRuns(nodes: List<StructuredNode>, parsedCss: ParsedCss): List<FlowRun> {
    val runs = mutableListOf<FlowRun>()
    val current = mutableListOf<StructuredNode>()
    fun flushInline() {
        if (current.isEmpty()) return
        val merged = mutableListOf<StructuredNode>()
        val buf = StringBuilder()
        fun flushBuf() {
            if (buf.isNotEmpty()) {
                merged.add(StructuredNode.Text(buf.toString()))
                buf.clear()
            }
        }
        for (n in current) {
            if (n is StructuredNode.Text) buf.append(n.text)
            else {
                flushBuf()
                merged.add(n)
            }
        }
        flushBuf()
        runs.add(FlowRun.Inline(merged))
        current.clear()
    }
    for (n in nodes) {
        // A line break is INLINE, not a block boundary. Splitting the run on `<br>` gave every
        // line its own `Text` plus a 2dp spacer, so a 3-line gloss measured 73px against the
        // browser's 59px (3 x 14 x 1.4) and the lines drifted apart. `buildInlineParagraph` turns
        // these into '\n' in one paragraph, which is what CSS means by <br>.
        if (n is StructuredNode.TextBreak ||
            (n is StructuredNode.Element && n.tag == StructuredTag.Break)
        ) {
            current.add(n)
        } else if (isInlineLevel(n) && !isDisplayBlock(n, parsedCss)) {
            current.add(n)
        } else {
            flushInline()
            runs.add(FlowRun.Block(n))
        }
    }
    flushInline()
    return runs
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRuns(
    runs: List<FlowRun>,
    parsedCss: ParsedCss,
    style: TextStyle,
    dictName: String,
    mediaDataUris: Map<String, String>,
    secondary: Color,
    border: Color,
    onRecursiveLookup: ((String) -> Unit)?,
    ancestors: List<CssAncestor> = emptyList(),
    // Shrink-wrap flows (tables, `width: fit-content` boxes) so intrinsic
    // measurement sees content width — a forced fillMaxWidth inflates every
    // column / box to the parent width.
    fillMaxWidth: Boolean = true,
    arrangement: Arrangement.Horizontal = Arrangement.Start,
    /** Reports the first laid-out paragraph's line boxes; the oracle uses it to explain offsets. */
    onLineMetrics: ((TextLayoutResult) -> Unit)? = null,
) {
    // CSS collapses adjacent siblings' vertical margins to the largest of the two, and a child's
    // outer margin escapes a parent that has no border/padding. Compose adds `padding`, so the gap
    // has to be computed here and the blocks must not add their own - see
    // [LocalCollapsesOwnVMargin].
    val baseFontSizeSp = fontSizeSp(style)
    val vMargins = runs.map { run ->
        if (run is FlowRun.Block && run.node is StructuredNode.Element) {
            effectiveVMargins(run.node, parsedCss, ancestors, style, baseFontSizeSp)
        } else {
            0f to 0f
        }
    }
    runs.forEachIndexed { index, run ->
        // Adjacent siblings' margins collapse to the larger of the two (CSS 2.1 8.3.1). A margin that
        // collapses OUT of a block is already folded into that block's own margin by
        // [effectiveVMargins], so no run is special-cased here.
        if (index > 0) {
            val gap = maxOf(vMargins[index - 1].second, vMargins[index].first)
            if (gap > 0f) Spacer(Modifier.height(gap.dp))
        }
        when (run) {
            is FlowRun.Inline -> {
                val paragraph = rememberInlineParagraph(
                    nodes = run.nodes,
                    style = style,
                    parsedCss = parsedCss,
                    dictName = dictName,
                    mediaDataUris = mediaDataUris,
                    secondary = secondary,
                    onRecursiveLookup = onRecursiveLookup,
                    ancestors = ancestors,
                )
                if (paragraph != null) {
                    // ONE Text for the whole run: the engine breaks lines, keeps every line on a
                    // single shared baseline, and treats each ruby run as an atomic inline.
                    // Every paragraph is reported, not just the first: list items are separate
                    // paragraphs and their wrap points are where most of the browser's width goes.
                    val metrics = onLineMetrics
                    InlineParagraphText(paragraph, style, onRecursiveLookup, metrics)
                } else {
                    // Fallback for runs containing something the paragraph builder does not model
                    // (a padded/bordered chip, a nested block). Correct, just not as tidy.
                    FlowRow(
                        modifier = if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier,
                        horizontalArrangement = arrangement,
                    ) {
                        run.nodes.forEach { child ->
                            StructuredNodeView(child, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors, onLineMetrics = onLineMetrics)
                        }
                    }
                }
            }
            is FlowRun.Block -> {
                val thisNode = run.node
                // Siblings already laid out, nearest first, for the `+` / `~` combinators.
                val prevData = runs.asSequence()
                    .take(index)
                    .filterIsInstance<FlowRun.Block>()
                    .mapNotNull { (it.node as? StructuredNode.Element)?.attributes?.data }
                    .toList()
                    .asReversed()
                CompositionLocalProvider(
                    LocalCollapsesOwnVMargin provides true,
                    LocalPrecedingSiblings provides prevData,
                ) {
                    StructuredNodeView(thisNode, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors)
                }
            }
        }
    }
}

/**
 * Builds (and caches) the paragraph for an inline run.
 *
 * `remember` keys on the node list and the inputs the flattening depends on, so a recomposition
 * does not re-measure every ruby run.
 */
@Composable
private fun rememberInlineParagraph(
    nodes: List<StructuredNode>,
    style: TextStyle,
    parsedCss: ParsedCss,
    dictName: String,
    mediaDataUris: Map<String, String>,
    secondary: Color,
    onRecursiveLookup: ((String) -> Unit)?,
    ancestors: List<CssAncestor>,
): InlineParagraph? {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val density = LocalDensity.current
    return remember(nodes, style, parsedCss, dictName, mediaDataUris, secondary, density, ancestors) {
        InlineParagraphBuilder(
            style = style,
            parsedCss = parsedCss,
            measurer = measurer,
            density = density,
            lookup = onRecursiveLookup,
            ancestors = ancestors,
        ).build(nodes)
    }
}

/**
 * Renders a flattened inline run, keeping tap-to-lookup word resolution across the whole run.
 *
 * Offsets now span the run rather than a single node, so a tap between two styled spans still
 * resolves a word - closer to the WebView's `caretRangeFromPoint` behaviour than before.
 */
@Composable
private fun InlineParagraphText(
    paragraph: InlineParagraph,
    style: TextStyle,
    onRecursiveLookup: ((String) -> Unit)?,
    onLineMetrics: ((TextLayoutResult) -> Unit)? = null,
) {
    val text = paragraph.text
    if (text.isEmpty()) return
    // The run's largest scaled line height wins, so a run containing a larger headword gets the
    // taller line box CSS would give it rather than the base one. A non-positive value means the
    // base style had no `lineHeight` to scale, so leave the style alone rather than collapsing every
    // line box to zero.
    val paraStyle =
        if (paragraph.lineHeightSp > 0f) style.copy(lineHeight = paragraph.lineHeightSp.sp) else style
    val textNode: @Composable () -> Unit = {
        if (onRecursiveLookup == null) {
            if (onLineMetrics != null) {
                var lm: TextLayoutResult? = null
                Text(
                    text = text,
                    style = paraStyle,
                    inlineContent = paragraph.inlineContent,
                    onTextLayout = { lm = it; onLineMetrics(it) },
                )
                lm?.let(onLineMetrics)
            } else {
                Text(text = text, style = paraStyle, inlineContent = paragraph.inlineContent)
            }
        } else {
            val lookup by rememberUpdatedState(onRecursiveLookup)
            var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
            val plain = text.text
            Text(
                text = text,
                style = paraStyle,
                inlineContent = paragraph.inlineContent,
                onTextLayout = { layout = it },
                modifier = Modifier.pointerInput(plain) {
                    detectTapGestures { pos ->
                        val result = layout ?: return@detectTapGestures
                        val offset = result.getOffsetForPosition(Offset(pos.x, pos.y))
                        lookupWordAt(plain, offset)?.let { lookup(it) }
                    }
                },
            )
        }
    }
    // No manual half-leading padding here. CSS Inline 3 5.3 puts the surplus leading half above and
    // half below every line, and `LineHeightStyle(Center, Trim.None)` on the base style already does
    // exactly that, so the first line's box is `line-height` tall and the ink sits where the browser
    // puts it. Adding [InlineParagraph.halfLeadingPx] as top padding on top of that applied the
    // leading twice and pushed the first line down by a further 2.8px.
    textNode()
}

@Composable
private fun StructuredElementView(
    node: StructuredNode.Element,
    parsedCss: ParsedCss,
    style: TextStyle,
    dictName: String,
    mediaDataUris: Map<String, String>,
    secondary: Color,
    border: Color,
    onRecursiveLookup: ((String) -> Unit)?,
    ancestors: List<CssAncestor> = emptyList(),
    onLineMetrics: ((TextLayoutResult) -> Unit)? = null,
) {
    when (node.tag) {
        StructuredTag.Link -> StructuredLink(
            node, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors,
        )
        StructuredTag.Ruby -> StructuredRuby(node, style)
        StructuredTag.Image -> StructuredImage(node, style, dictName, mediaDataUris, secondary)
        StructuredTag.Table -> StructuredTable(
            node, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors,
        )
        StructuredTag.Details -> StructuredDetails(
            node, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors,
        )
        StructuredTag.Summary -> StructuredBox(
            node, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors,
        )
        StructuredTag.UnorderedList, StructuredTag.OrderedList, StructuredTag.ListItem -> StructuredList(
            node, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors,
            onLineMetrics = onLineMetrics,
        )
        StructuredTag.Div, StructuredTag.Span -> StructuredBox(
            node, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors,
        )
        StructuredTag.Break -> Spacer(Modifier.height(2.dp))
        StructuredTag.HorizontalRule -> androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
            color = border,
        )
        // Semantic inline tags: browser-default styling the WebView gets for free.
        StructuredTag.Bold -> semanticChildren(node, parsedCss, style.copy(fontWeight = FontWeight.Bold), dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors)
        StructuredTag.Italic -> semanticChildren(node, parsedCss, style.copy(fontStyle = FontStyle.Italic), dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors)
        StructuredTag.Underline -> semanticChildren(node, parsedCss, style.copy(textDecoration = TextDecoration.Underline), dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors)
        StructuredTag.Strike -> semanticChildren(node, parsedCss, style.copy(textDecoration = TextDecoration.LineThrough), dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors)
        StructuredTag.Superscript -> semanticChildren(node, parsedCss, style.copy(baselineShift = BaselineShift.Superscript, fontSize = style.fontSize * 0.75f), dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors)
        StructuredTag.Subscript -> semanticChildren(node, parsedCss, style.copy(baselineShift = BaselineShift.Subscript, fontSize = style.fontSize * 0.75f), dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors)
        StructuredTag.Small -> semanticChildren(node, parsedCss, style.copy(fontSize = style.fontSize * 0.8f), dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors)
        StructuredTag.Mark -> semanticChildren(
            node, parsedCss,
            style.copy(background = Color(0xFFFFF176)),
            dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors,
        )
        // Passthrough: every other tag renders its children with no extra styling. Real
        // dictionaries use tags outside the structured-content spec (p, blockquote, pre, code,
        // h1-h6, caption), and those frequently carry `display: none` in their stylesheet - the
        // Oxford sheet hides whole blocks that way. Honouring it here stops hidden text leaking
        // into the rendered output.
        else -> {
            val hidden = getCssStyles(node.attributes.data, parsedCss) + node.attributes.style
            val display = hidden["display"]?.trim()?.lowercase()
            val visibility = hidden["visibility"]?.trim()?.lowercase()
            if (display == "none" || visibility == "hidden" || visibility == "collapse") {
                return
            }
            node.children.forEach { child ->
                StructuredNodeView(child, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors, onLineMetrics = onLineMetrics)
            }
        }
    }
}

@Composable
private fun semanticChildren(
    node: StructuredNode.Element,
    parsedCss: ParsedCss,
    style: TextStyle,
    dictName: String,
    mediaDataUris: Map<String, String>,
    secondary: Color,
    border: Color,
    onRecursiveLookup: ((String) -> Unit)?,
    ancestors: List<CssAncestor> = emptyList(),
) {
    node.children.forEach { child ->
        StructuredNodeView(child, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors)
    }
}

@Composable
private fun StructuredBox(
    node: StructuredNode.Element,
    parsedCss: ParsedCss,
    style: TextStyle,
    dictName: String,
    mediaDataUris: Map<String, String>,
    secondary: Color,
    border: Color,
    onRecursiveLookup: ((String) -> Unit)?,
    ancestors: List<CssAncestor> = emptyList(),
) {
    if (node.attributes.data["content"] == "attribution") return

    val tagName = node.tag.tagName
    val nested = remember(tagName, node.attributes.data, parsedCss, ancestors) {
        getNestedStyles(tagName, node.attributes.data, ancestors, parsedCss)
    }
    val prevSiblings = LocalPrecedingSiblings.current
    val combined = remember(node.attributes.data, parsedCss, node.attributes.style, nested, prevSiblings) {
        getCssStyles(node.attributes.data, parsedCss) +
            getSiblingStyles(node.attributes.data, parsedCss, prevSiblings) +
            nested + node.attributes.style
    }
    val styled = remember(style, combined) { applyTypography(style, combined) }
    // Descendant scope for children: this element's lookup keys + tag.
    val childScope = remember(ancestors, node.attributes.data, tagName) {
        ancestors + CssAncestor(dataValueKeys(node.attributes.data), tagName)
    }

    // `display: none` / `visibility: hidden` rules hide the element entirely (e.g. Oxford
    // hides `sn`/`ps`/`hw`/`pr` spans). The WebView applies these via injected dictionary CSS.
    val display = remember(combined) { combined["display"]?.trim()?.lowercase() }
    val visibility = remember(combined) { combined["visibility"]?.trim()?.lowercase() }
    if (display == "none" || visibility == "hidden") return

    // Tag chip spans: `span[data-sc-class="tag"]` — token equality, not substring
    // (substring matched "stage"/"vintage" etc).
    if (node.tag == StructuredTag.Span &&
        node.attributes.data["class"]?.split(CLASS_SPLIT_REGEX)?.any { it == "tag" } == true
    ) {
        StructuredTagChip(node, combined, style)
        return
    }

    val baseFontSizeSp = fontSizeSp(style)
    val box = remember(combined, baseFontSizeSp) { parseBoxStyle(combined, baseFontSizeSp) }

    // Merge box-level typography (textAlign / verticalAlign / opacity) into the style for children.
    var effectiveStyled = styled
    if (box.textAlign != null) effectiveStyled = effectiveStyled.copy(textAlign = box.textAlign)
    if (box.verticalAlign == "super") {
        effectiveStyled = effectiveStyled.copy(baselineShift = BaselineShift.Superscript)
    } else if (box.verticalAlign == "sub") {
        effectiveStyled = effectiveStyled.copy(baselineShift = BaselineShift.Subscript)
    }
    // Fallback: if BoxStyle didn't capture verticalAlign but css map has it (e.g. inline super notes 0.7em)
    if (box.verticalAlign == null && combined["verticalAlign"]?.trim()?.lowercase() == "super") {
        effectiveStyled = effectiveStyled.copy(baselineShift = BaselineShift.Superscript)
    }
    // `vertical-align: top` on small inline notes (Jitendex attribution footnotes):
    // CSS pins them to the top of the line box — Superscript is the Compose equivalent.
    // (On block boxes vertical-align is meaningless in CSS; table cells never flow here.)
    if (combined["verticalAlign"]?.trim()?.lowercase() in setOf("top", "text-top")) {
        effectiveStyled = effectiveStyled.copy(baselineShift = BaselineShift.Superscript)
    }

    // Inline-level tags (span/b/i/em/...) must NEVER become block containers: even with box styles they
    // stay in the text flow. Spans without bg/border/padding emit children directly so they join the
    // parent flow; a wrapper Box would give the nested flow unbounded width and break wrapping.
    //
    // A span the dictionary CSS promotes with `display: block` is the exception - it is a block box in
    // CSS, so it takes the block path below and honours its vertical margins.
    val blockByCss = isDisplayBlock(node, parsedCss)
    if (node.tag in INLINE_LEVEL_TAGS && !blockByCss) {
        val needsBox = box.hasBackground || box.hasBorder || box.hasPadding || box.opacity != null
        // Raising small notes (`vertical-align: super/top`, e.g. footnote `[1]`):
        // a bare BaselineShift does nothing across the FlowRow's independent Text
        // layouts (no shared baseline), so shift the whole inline box visually.
        // Factor mirrors CSS: super/top lifts ~40% of the font size.
        val raiseEm = when (combined["verticalAlign"]?.trim()?.lowercase()) {
            "super", "top", "text-top" -> -0.4f
            "sub" -> 0.25f
            else -> 0f
        }
        if (!needsBox) {
            // Honor a left margin as a small gap (e.g. footnote margin-left), then flow inline.
            if ((box.marginStart ?: 0f) > 0f) {
                Spacer(Modifier.width(box.marginStart!!.dp))
            }
            // A raised box must not ALSO carry BaselineShift (double lift where a
            // shared baseline does exist); flat boxes keep theirs (harmless no-op).
            val childStyle = if (raiseEm != 0f) {
                effectiveStyled.copy(baselineShift = BaselineShift.None)
            } else effectiveStyled
            val raiseDp = if (raiseEm != 0f) {
                val fs = childStyle.fontSize.let { if (it.isSp) it.value else 14f }
                (fs * raiseEm).dp
            } else 0.dp
            val runs = remember(node.children) { partitionInlineRuns(node.children, parsedCss) }
            if (runs.size == 1 && runs[0] is FlowRun.Inline) {
                if (raiseEm != 0f) {
                    Box(Modifier.offset(y = raiseDp)) {
                        (runs[0] as FlowRun.Inline).nodes.forEach { child ->
                            StructuredNodeView(child, parsedCss, childStyle, dictName, mediaDataUris, secondary, border, onRecursiveLookup, childScope)
                        }
                    }
                } else {
                    (runs[0] as FlowRun.Inline).nodes.forEach { child ->
                        StructuredNodeView(child, parsedCss, childStyle, dictName, mediaDataUris, secondary, border, onRecursiveLookup, childScope)
                    }
                }
            } else {
                // Rare: block-level child inside an inline tag — stack VERTICALLY in a
                // Column. (A Box would overlay all children at the same position.)
                Column {
                    val runs = remember(node.children) { partitionInlineRuns(node.children, parsedCss) }
                    FlowRuns(runs, parsedCss, effectiveStyled, dictName, mediaDataUris, secondary, border, onRecursiveLookup, childScope)
                }
            }
            // Trailing margin (e.g. `margin-right` on reference-label spans like
            // "See also") — without it the next inline sibling glues on.
            if ((box.marginEnd ?: 0f) > 0f) {
                Spacer(Modifier.width(box.marginEnd!!.dp))
            }
            return
        }
        var inlineModifier: Modifier = Modifier
        if ((box.marginEnd ?: 0f) > 0f) inlineModifier = inlineModifier.padding(end = box.marginEnd!!.dp)
        if (box.opacity != null) inlineModifier = inlineModifier.alpha(box.opacity)
        if (box.hasBackground || box.hasBorder) {
            inlineModifier = inlineModifier.clip(RoundedCornerShape(box.borderRadius?.dp ?: 2.dp))
        }
        if (box.hasBackground) {
            // Remembered: hex parsing runs once per color, not per recomposition.
            val bgc = remember(box.backgroundColor, secondary) {
                box.backgroundColor?.let { parseCssColor2(it) } ?: secondary.copy(alpha = 0.08f)
            }
            inlineModifier = inlineModifier.background(bgc)
        }
        if (box.hasBorder && !box.leftAccent) {
            val bc = remember(box.borderColor, border) {
                box.borderColor?.let { parseCssColor2(it) } ?: border
            }
            inlineModifier = inlineModifier.border(1.dp, bc, RoundedCornerShape(box.borderRadius?.dp ?: 2.dp))
        }
        inlineModifier = inlineModifier.padding(
            start = (box.paddingStart ?: 0f).dp,
            end = (box.paddingEnd ?: 0f).dp,
            top = (box.paddingTop ?: 0f).dp,
            bottom = (box.paddingBottom ?: 0f).dp,
        )
        Box(modifier = inlineModifier) {
            val runs = remember(node.children) { partitionInlineRuns(node.children, parsedCss) }
            // Shrink-wrap: this box is an INLINE box, so it must be exactly as wide as its content.
            // Letting the flow fill max width made the background span the whole line - 新明解's
            // sub-entry chips (`子`, `句`) rendered as full-width bars instead of small pills.
            FlowRuns(runs, parsedCss, effectiveStyled, dictName, mediaDataUris, secondary, border, onRecursiveLookup, childScope, fillMaxWidth = false)
        }
        return
    }

    val isInline = node.children.all {
        // Only plain text and UNSTYLED spans count as inline. Styled spans, tag chips,
        // ruby (base+rt), links and images must fall through to per-node rendering or
        // collectVisible flattens them into one glued string (readings look duplicated,
        // images vanish).
        (it is StructuredNode.Text) ||
            (
                it is StructuredNode.Element && it.tag == StructuredTag.Span &&
                    it.attributes.style.isEmpty() && it.attributes.data.isEmpty() &&
                    it.children.all { c -> c is StructuredNode.Text }
                )
    }

    if (!box.hasAnyStyle && isInline) {
        // Plain inline span/div → emit inline text only (fast path). Hidden subtrees are skipped.
        // Use effectiveStyled which carries verticalAlign / textAlign / opacity.
        // Remembered: the subtree walk + per-node display/visibility lookups run once.
        val visibleText = remember(node, parsedCss) { node.collectVisible(parsedCss) }
        if (box.textAlign != null) {
            // Block alignment needs width; emit as full-width Text rather than inline
            if (visibleText.isNotBlank()) {
                Text(
                    text = visibleText,
                    style = effectiveStyled,
                    textAlign = box.textAlign,
                    modifier = Modifier.fillMaxWidth().let { m ->
                        if (box.opacity != null) m.alpha(box.opacity) else m
                    },
                )
            }
        } else {
            // Opacity for Text: apply alpha via Modifier if needed, or color alpha is already in effectiveStyled
            val alphaMod = if (box.opacity != null) Modifier.alpha(box.opacity) else Modifier
            if (alphaMod != Modifier) {
                // Wrap inline text with alpha
                Box(modifier = alphaMod) {
                    spanText(visibleText, effectiveStyled, onRecursiveLookup)
                }
            } else {
                spanText(visibleText, effectiveStyled, onRecursiveLookup)
            }
        }
        return
    }

    val shape = RoundedCornerShape(box.borderRadius?.dp ?: 6.dp)
    // Remembered: hex parsing runs once per color, not per recomposition.
    val bgColor = remember(box.backgroundColor, box.hasBackground, secondary) {
        box.backgroundColor?.let { parseCssColor2(it) }
            ?: if (box.hasBackground) secondary.copy(alpha = 0.08f) else Color.Transparent
    }
    val borderColor = remember(box.borderColor, border) {
        box.borderColor?.let { parseCssColor2(it) } ?: border
    }

    // Opacity handling: wrap box content with alpha. For Text opacity, also apply via TextStyle color alpha
    // (handled in applyTypography + effectiveStyled). Box opacity uses Modifier.alpha.
    val outerAlphaModifier = if (box.opacity != null) Modifier.alpha(box.opacity) else Modifier
    val fillWidthModifier = if (box.textAlign != null) Modifier.fillMaxWidth() else Modifier

    val runs = remember(node.children) { partitionInlineRuns(node.children, parsedCss) }
    val contentPadding = Modifier
        .padding(
            start = box.paddingStart?.dp ?: 0.dp,
            end = box.paddingEnd?.dp ?: 0.dp,
            top = box.paddingTop?.dp ?: 0.dp,
            bottom = box.paddingBottom?.dp ?: if (box.hasBackground || box.hasBorder) 3.dp else 0.dp,
        )
    // `width: fit-content` boxes (Jitendex extra-box) shrink-wrap their content;
    // inner flows must not force full width or the box can never shrink.
    val innerFillMaxWidth = !box.fitContent

    // Left-accent boxes (`border-style: none none none solid`): a colored edge bar with the
    // content beside it, exactly like the WebView's accent border. Drawn behind the row —
    // a standalone 3dp Box in a wrap-content Row measured 4dp tall (invisible stub).
    if (box.leftAccent) {
        Row(
            modifier = Modifier
                .then(outerAlphaModifier)
                .then(fillWidthModifier)
                // Margins live OUTSIDE the bar: CSS border spans the border box
                // (content + padding) only. Drawing behind margin padding made the
                // bar taller than the WebView's.
                .padding(
                    start = box.marginStart?.dp ?: 0.dp,
                    end = box.marginEnd?.dp ?: 0.dp,
                    top = box.marginTop?.dp ?: 0.dp,
                    bottom = box.marginBottom?.dp ?: 0.dp,
                )
                .drawWithCache {
                    val barWidth = 3.dp.toPx()
                    val barColor = borderColor
                    onDrawBehind {
                        drawRect(
                            color = barColor,
                            topLeft = Offset.Zero,
                            size = Size(barWidth, size.height),
                        )
                    }
                }
                .padding(start = 3.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier.then(
                    Modifier.clip(shape).let { m ->
                        if (box.hasBackground) m.background(bgColor) else m
                    },
                ),
            ) {
                // `content` carries the CSS padding; give the text breathing room from the edge bar.
                Box(Modifier.padding(start = if (box.paddingStart == null) 6.dp else 0.dp)) {
                    BoxContentColumn(
                        fitContent = box.fitContent,
                        chromeModifier = Modifier.then(
                            Modifier.clip(shape).let { m ->
                                if (box.hasBackground) m.background(bgColor) else m
                            },
                        ),
                        contentPadding = contentPadding,
                    ) {
                        FlowRuns(runs, parsedCss, effectiveStyled, dictName, mediaDataUris, secondary, border, onRecursiveLookup, childScope, innerFillMaxWidth)
                    }
                }
            }
        }
        return
    }

    var modifier = Modifier
        .then(outerAlphaModifier)
        .then(fillWidthModifier)
        .padding(
            start = box.marginStart?.dp ?: 0.dp,
            end = box.marginEnd?.dp ?: 0.dp,
            top = if (LocalCollapsesOwnVMargin.current) 0.dp else box.marginTop?.dp ?: 0.dp,
            bottom = if (LocalCollapsesOwnVMargin.current) 0.dp else box.marginBottom?.dp ?: 0.dp,
        )
    // Only clip when there's a background/border to contain: an unconditional clip
    // cuts off furigana that draws slightly above its layout bounds, and adds
    // useless clipping layers (perf) on transparent boxes.
    if (box.hasBackground || box.hasBorder) modifier = modifier.clip(shape)
    if (box.hasBackground) modifier = modifier.background(bgColor)
    if (box.hasBorder) modifier = modifier.border(1.dp, borderColor, shape)
    BoxContentColumn(
        fitContent = box.fitContent,
        chromeModifier = modifier,
        contentPadding = contentPadding,
    ) {
        FlowRuns(runs, parsedCss, effectiveStyled, dictName, mediaDataUris, secondary, border, onRecursiveLookup, childScope, innerFillMaxWidth)
    }
}

/**
 * Content column for a styled box.
 *
 * Plain boxes fill the parent width like CSS block boxes. A `width: fit-content` box is
 * `min(max-content, available)`, which needs two passes: measure the children with UNBOUNDED width
 * to learn the single-line max-content width, then re-measure the same [Placeable]s at the clamped
 * width so long text wraps there. Placeables are re-measurable, so this subcomposes only once.
 *
 * The previous implementation delegated to a hand-rolled greedy line breaker whose items were
 * TOP-aligned within each line rather than baseline-aligned - so plain text and ruby in
 * the same line sat at different baselines. That is exactly the "text is not on the same line"
 * symptom, and the Jitendex example-sentence box (the only `fit-content` rule across the 36 shipped
 * dictionaries, `div[data-sc-class="extra-box"]`) is the one box that took that path.
 */
@Composable
private fun BoxContentColumn(
    fitContent: Boolean,
    chromeModifier: Modifier,
    contentPadding: Modifier,
    content: @Composable () -> Unit,
) {
    if (!fitContent) {
        Column(chromeModifier.then(contentPadding)) { content() }
        return
    }
    SubcomposeLayout(modifier = chromeModifier.then(contentPadding)) { constraints ->
        // Pass 1: unbounded, to learn the max-content (single-line) width.
        val natural = subcompose(FIT_CONTENT_NATURAL) { content() }
            .map { it.measure(Constraints()) }
            .maxOfOrNull { it.width } ?: 0
        val target = if (constraints.maxWidth == Constraints.Infinity) {
            natural
        } else {
            minOf(natural, constraints.maxWidth)
        }
        // Pass 2: a second subcomposition measured at the clamped width, so a long sentence wraps
        // there while a short one hugs its content. A Placeable cannot be re-measured, hence two
        // subcompositions; the content here is plain text/layout with no state to duplicate.
        val placeables = subcompose(FIT_CONTENT_FINAL) { content() }
            .map { it.measure(Constraints(minWidth = 0, maxWidth = target)) }
        layout(placeables.maxOfOrNull { it.width } ?: 0, placeables.sumOf { it.height }) {
            var y = 0
            placeables.forEach { p ->
                p.placeRelative(0, y)
                y += p.height
            }
        }
    }
}

/** Composition key for the single subcomposition inside the `fit-content` branch. */
private const val FIT_CONTENT_NATURAL = "fitContentNatural"
private const val FIT_CONTENT_FINAL = "fitContentFinal"

@Composable
private fun StructuredTagChip(
    node: StructuredNode.Element,
    css: Map<String, String>,
    baseStyle: TextStyle,
) {
    // Remembered: color-mix blending + hex parsing run once per chip style.
    val bg = remember(css, baseStyle.color) {
        css["backgroundColor"]?.let { raw ->
            val hex = if (raw.contains("color-mix", ignoreCase = true)) blendColorMix(raw) ?: raw else raw
            parseCssColor2(hex)
        } ?: baseStyle.color.let { if (it == Color.Unspecified) Color.Gray else it.copy(alpha = 0.15f) }
    }
    val fg = remember(css, bg) {
        css["color"]?.let { raw ->
            val hex = if (raw.contains("color-mix", ignoreCase = true)) blendColorMix(raw) ?: raw else raw
            parseCssColor2(hex)
        } ?: if (bg.luminance() > 0.5f) Color.Black else Color.White
    }
    val text = remember(node) { node.collect() }
    if (text.isBlank()) return
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = baseStyle.fontSize.times(0.8f),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StructuredList(
    node: StructuredNode.Element,
    parsedCss: ParsedCss,
    style: TextStyle,
    dictName: String,
    mediaDataUris: Map<String, String>,
    secondary: Color,
    border: Color,
    onRecursiveLookup: ((String) -> Unit)?,
    ancestors: List<CssAncestor> = emptyList(),
    onLineMetrics: ((TextLayoutResult) -> Unit)? = null,
) {
    val ordered = node.tag == StructuredTag.OrderedList
    val cssMap = remember(node.attributes.data, parsedCss, ancestors) {
        getCssStyles(node.attributes.data, parsedCss) +
            getNestedStyles(node.tag.tagName, node.attributes.data, ancestors, parsedCss)
    }
    val listStyleType = remember(cssMap, node.attributes.style) { (cssMap + node.attributes.style)["listStyleType"] }
    val listStylePosition = remember(cssMap, node.attributes.style) { (cssMap + node.attributes.style)["listStylePosition"] }
    // base.css gives lists inside structured content `padding-left: 1.2em; margin: 0.15em 0` via
    // zero-specificity :where() (line 106), but the reference stylesheet ALSO has
    //   `#entries .gloss-sc-ol, #entries .gloss-sc-ul { padding-left: 1.2em; margin: 0.3em 0 }`  (638)
    //   `#entries .gloss-sc-ol li, #entries .gloss-sc-ul li { margin: 0.15em 0 }`              (643)
    // and the JS renderer marks every structured list with those classes, so the higher-specificity
    // rules always win, so the ID-based values are the ones that apply.
    val baseFontSizeSp = fontSizeSp(style)
    val listBox = remember(cssMap, baseFontSizeSp) { parseBoxStyle(cssMap, baseFontSizeSp) }
    val listPaddingStart = (listBox.paddingStart ?: LIST_PADDING_START_EM * baseFontSizeSp).dp
    // The list's vertical margins are NOT applied here. They are collapsed by [effectiveVMargins],
    // which owns every other block's margins: applying them as padding as well gave each list the
    // margin twice and stopped the first list's top margin collapsing out of its parent.
    val childScope = remember(ancestors, node.attributes.data) {
        ancestors + CssAncestor(dataValueKeys(node.attributes.data), node.tag.tagName)
    }

    Column(Modifier.padding(start = listPaddingStart)) {
        var counter = 0
        // `data-*` of the items already laid out, nearest first, for `A + B` / `A ~ B` rules such as
        // Jitendex's `li[sense-group] + li[sense-group] { margin-top: .5em }`.
        val prevItemData = mutableListOf<Map<String, String>>()
        val items = node.children.filterIsInstance<StructuredNode.Element>()
            .filter { it.tag == StructuredTag.ListItem }
        node.children.forEach { child ->
            if (child is StructuredNode.Element && child.tag == StructuredTag.ListItem) {
                counter++
                val prev = prevItemData.toList()
                prevItemData.add(0, child.attributes.data)
                CompositionLocalProvider(LocalPrecedingSiblings provides prev) {
                    StructuredListItem(
                        child, parsedCss, style, dictName, mediaDataUris, secondary, border,
                        onRecursiveLookup, ordered, counter, listStyleType, childScope,
                        markerPosition = listStylePosition ?: "outside",
                        markerGutter = listPaddingStart,
                        onLineMetrics = onLineMetrics,
                        // A first / last child's own vertical margin collapses WITH the list's
                        // (CSS 2.1 8.3.1), and [effectiveVMargins] already carries the collapsed value out
                        // of this list, so adding the item margin here as well would count it twice.
                        collapseOwnVMargin = items.indexOf(child).let { it == 0 || it == items.size - 1 },
                    )
                }
            } else {
                StructuredNodeView(child, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, childScope)
            }
        }
    }
}

@Composable
private fun StructuredListItem(
    node: StructuredNode.Element,
    parsedCss: ParsedCss,
    style: TextStyle,
    dictName: String,
    mediaDataUris: Map<String, String>,
    secondary: Color,
    border: Color,
    onRecursiveLookup: ((String) -> Unit)?,
    ordered: Boolean,
    index: Int,
    listStyleType: String?,
    ancestors: List<CssAncestor> = emptyList(),
    /** `list-style-position` from the list or the item; CSS defaults to `outside`. */
    markerPosition: String = "outside",
    /** Width of the list's `padding-left`, i.e. the gutter the outside marker sits in. */
    markerGutter: androidx.compose.ui.unit.Dp = 0.dp,
    onLineMetrics: ((TextLayoutResult) -> Unit)? = null,
    /**
     * True for the first and last item, whose own vertical margin collapses with the list's and is
     * therefore already carried by the list's effective margin - see [StructuredList].
     */
    collapseOwnVMargin: Boolean = false,
) {
    // The marker type may sit on the `li` itself (Jitendex `"①"` sense markers
    // via the JSON style object), not just on the list — item wins.
    val itemMarkerType = node.attributes.style["listStyleType"] ?: listStyleType
    // A CSS `list-style-type` (e.g. Jitendex `"①"` sense markers) overrides the
    // `ol` default numbering — the WebView never shows "1." there.
    val marker = when {
        itemMarkerType == "none" -> ""
        itemMarkerType != null && itemMarkerType !in decimalListTypes -> when (itemMarkerType) {
            "circle" -> "◦"
            "square" -> "▪"
            "disc" -> "•"
            else -> itemMarkerType.trim('"', '\'')
                .takeIf { it.isNotEmpty() && it != "inherit" && !it.startsWith("url(") }
                ?: "•"
        }
        ordered -> "$index."
        itemMarkerType == "circle" -> "◦"
        itemMarkerType == "square" -> "▪"
        itemMarkerType == "disc" -> "•"
        else -> itemMarkerType?.trim('"', '\'')
            ?.takeIf { it.isNotEmpty() && it != "inherit" && !it.startsWith("url(") }
            ?: "•"
    }
    // base.css: `#entries .gloss-sc-ol li { margin: 0.15em 0 }` (643) - a default dictionary CSS can
    // override.
    val prevSiblings = LocalPrecedingSiblings.current
    val itemCss = remember(node.attributes.data, parsedCss, ancestors, prevSiblings) {
        getCssStyles(node.attributes.data, parsedCss) +
            getSiblingStyles(node.attributes.data, parsedCss, prevSiblings) +
            getNestedStyles(node.tag.tagName, node.attributes.data, ancestors, parsedCss)
    }
    val itemBaseSp = fontSizeSp(style)
    val itemBox = remember(itemCss, itemBaseSp) { parseBoxStyle(itemCss, itemBaseSp) }
    val itemVMargin = if (collapseOwnVMargin) {
        0.dp
    } else {
        (itemBox.marginTop ?: LIST_ITEM_MARGIN_EM * itemBaseSp).dp
    }

    // `list-style-position` decides the layout. The CSS default is `outside`: the marker sits in the
    // list's padding and every text line starts at the same x. A `Row` sibling is `inside`, which cost
    // Only an explicit `list-style-position: inside` keeps the marker in the text flow.
    val inside = markerPosition.trim().lowercase() == "inside" || marker.isEmpty()

    // `disc` / `circle` / `square` are UA marker BOXES, not text characters: Chrome draws a ~0.3em
    // shape resting on the first line's baseline. Rendering the literal U+25AA instead let the font
    // decide the position and put it 6px high and 1px small, so the shape is drawn here.
    val shape = when (itemMarkerType?.trim()?.lowercase() ?: "disc") {
        "square" -> MarkerShape.SQUARE
        "circle" -> MarkerShape.CIRCLE
        "disc", null -> MarkerShape.DISC
        else -> MarkerShape.TEXT
    }
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val density = LocalDensity.current
    val lineBox = remember(style, density) { measureLineBox(measurer, density, style) }
    val markerSize = with(density) { (0.3f * fontSizeSp(style)).toDp() }

    // The modifier is supplied by the caller because `weight` only exists in a RowScope, which this
    // lambda is not inside.
    val content: @Composable (Modifier) -> Unit = { mod ->
        val runs = remember(node.children) { partitionInlineRuns(node.children, parsedCss) }
        val itemScope = remember(ancestors, node.attributes.data) {
            ancestors + CssAncestor(dataValueKeys(node.attributes.data), "li")
        }
        Column(mod) {
            FlowRuns(runs, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, itemScope, onLineMetrics = onLineMetrics)
        }
    }
    val itemModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = itemVMargin)
        .padding(
            start = itemBox.marginStart?.dp ?: 0.dp,
            end = itemBox.marginEnd?.dp ?: 0.dp,
        )
    val markerColor = style.color
    // The browser reserves the marker's own advance in the gutter. Measured rather than a fixed
    // minimum, so an emoji flag and a single square both get the width they actually occupy.
    val markerWidth = remember(marker, style) {
        with(density) {
            if (marker.isEmpty()) MIN_MARKER_GUTTER
            else measurer.measure(AnnotatedString(marker), style).size.width.toDp().coerceAtLeast(MIN_MARKER_GUTTER)
        }
    }
    val markerNode: @Composable (Modifier) -> Unit = { mod ->
        if (shape == MarkerShape.TEXT) {
            // A custom marker - surasura uses `"🇯🇵 "` and `"ℹ️ "`, Jitendex sense numbers - is a text
            // glyph, so it has to be baseline-aligned like the built-in shapes. Without the offset it
            // was pinned to the TOP of the item while the shapes sat on the baseline, which is what
            // left the flag and info icons visibly higher than the text they belong to.
            val offsetY = with(density) { (lineBox.firstBaselinePx - lineBox.ascentPx).toDp() }
            Text(marker, style = style, maxLines = 1, modifier = mod.offset(y = offsetY))
        } else {
            // Rest the marker on the first line's baseline, as a UA marker box does.
            val offsetY = with(density) { lineBox.firstBaselinePx.toDp() } - markerSize
            Box(
                mod.offset(y = offsetY).size(markerSize),
            ) {
                when (shape) {
                    MarkerShape.SQUARE -> Box(
                        Modifier.fillMaxSize().background(markerColor),
                    )
                    MarkerShape.DISC -> Box(
                        Modifier.fillMaxSize().background(markerColor, CircleShape),
                    )
                    MarkerShape.CIRCLE -> Box(
                        Modifier.fillMaxSize().border(
                            width = 1.dp,
                            color = markerColor,
                            shape = CircleShape,
                        ),
                    )
                    MarkerShape.TEXT -> Unit
                }
            }
        }
    }
    if (inside) {
        Row(itemModifier) {
            Box(Modifier.padding(end = 6.dp).width(markerWidth)) {
                markerNode(Modifier)
            }
            content(Modifier.weight(1f))
        }
    } else {
        // `outside`: the marker occupies the list's `padding-left` gutter, so the text is indented by
        // the list rather than by the marker. CSS 2.1 12.5.1 leaves the marker's offset within that
        // gutter to the user agent, so only the parts the spec fixes are implemented: the marker sits
        // in the gutter, and its baseline matches the first line box's (CSS Lists 3 4.2).
        Box(itemModifier) {
            content(Modifier.fillMaxWidth())
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset(x = -markerGutter)
                    .width(markerWidth),
            ) {
                markerNode(Modifier)
            }
        }
    }
}

private enum class MarkerShape { DISC, CIRCLE, SQUARE, TEXT }

/** Narrowest marker gutter; the built-in shapes are ~0.3em but a custom marker can be wider. */
private val MIN_MARKER_GUTTER = 4.dp

/** A text style's font size in sp, or 16 when unspecified (the em base the CSS parser assumes). */
private fun fontSizeSp(style: TextStyle): Float = style.fontSize.let { if (it.isSp) it.value else 16f }

/**
 * Line-box metrics for one text style, per CSS Inline Layout 3 §5.3:
 *
 *     L = line-height - (A + D),   A' = A + L/2,   D' = D + L/2
 *
 * A and D come from the strut - the *first available font* - which the spec names explicitly ("its
 * layout bounds are derived solely from metrics of its first available font"). Compose derives the line
 * box from the glyphs instead, so the half-leading is applied here to reproduce the spec. The one
 * definition is shared by the paragraph's top inset and the list marker's baseline.
 */
internal class LineBoxMetrics(
    val ascentPx: Float,
    val descentPx: Float,
    val halfLeadingPx: Float,
    val firstBaselinePx: Float,
)

/**
 * A CJK ideograph fills the font's ascent and descent, so one character reports the strut faithfully.
 * A Latin probe ("x", "H") sits on the x-height and would understate the ascent, which would drop
 * anything aligned to the baseline relative to the text.
 */
private const val PROBE_GLYPH = "漢"

internal fun measureLineBox(measurer: TextMeasurer, density: Density, style: TextStyle): LineBoxMetrics {
    val strut = measurer.measure(
        AnnotatedString(PROBE_GLYPH),
        style.copy(lineHeight = TextUnit.Unspecified),
    )
    val fontBoxPx = strut.size.height.toFloat()
    val lineHeightPx = if (style.lineHeight.isSp) with(density) { style.lineHeight.toPx() } else 0f
    val halfLeading = ((lineHeightPx - fontBoxPx) / 2f).coerceAtLeast(0f)
    val ascent = strut.firstBaseline
    return LineBoxMetrics(
        ascentPx = ascent,
        descentPx = fontBoxPx - ascent,
        halfLeadingPx = halfLeading,
        firstBaselinePx = ascent + halfLeading,
    )
}

private val decimalListTypes = setOf(
    "decimal", "decimal-leading-zero", "arabic-indic", "armenian", "georgian",
    "lower-alpha", "upper-alpha", "lower-roman", "upper-roman", "lower-latin",
    "upper-latin", "lower-greek", "inherit", "initial", "unset",
)

@Composable
private fun StructuredRuby(node: StructuredNode.Element, style: TextStyle) {
    // Exclude BOTH rt and rp from the base text (rp = fallback parentheses, never rendered).
    val base = node.children
        .filterNot { it is StructuredNode.Element && (it.tag == StructuredTag.Rt || it.tag == StructuredTag.Rp) }
        .joinToString("") { it.collect() }
    // Concatenate ALL rt children — multi-segment ruby loses readings with firstOrNull.
    val rt = node.children
        .filterIsInstance<StructuredNode.Element>()
        .filter { it.tag == StructuredTag.Rt }
        .joinToString("") { it.children.joinToString("") { c -> c.collect() } }
    if (rt.isBlank()) {
        spanText(base, style, null)
        return
    }
    RubyRun(base = base, reading = rt, baseStyle = style)
}

@Composable
private fun StructuredLink(
    node: StructuredNode.Element,
    parsedCss: ParsedCss,
    style: TextStyle,
    dictName: String,
    mediaDataUris: Map<String, String>,
    secondary: Color,
    border: Color,
    onRecursiveLookup: ((String) -> Unit)?,
    ancestors: List<CssAncestor> = emptyList(),
) {
    val href = node.attributes.properties["href"]
    val text = node.collect()
    if (text.isBlank()) return
    val context = LocalContext.current
    // WebView parity (base.css `.gloss-link { color: inherit; }`, no underline):
    // links inherit the ambient style; ONLY dict CSS may add color/decoration.
    // The old code forced primary+Medium+Underline on every link, which also
    // underlined furigana readings inside xref links (browser `rt` never takes
    // ancestor decoration — verified against headless-Chrome oracle).
    val tagName = node.tag.tagName
    val linkNested = remember(tagName, node.attributes.data, parsedCss, ancestors) {
        getNestedStyles(tagName, node.attributes.data, ancestors, parsedCss)
    }
    val linkCombined = remember(node.attributes.data, parsedCss, node.attributes.style, linkNested) {
        getCssStyles(node.attributes.data, parsedCss) + linkNested + node.attributes.style
    }
    val linkStyle = remember(style, linkCombined) { applyTypography(style, linkCombined) }

    // Links containing `<ruby>` (Jitendex xref `弟おとうと`): flattening via
    // collect() glues the reading as plain text next to the kanji. Render the
    // children inline instead so furigana draws above the base like the WebView.
    if (node.containsRuby()) {
        val target: String = href?.let { extractQuery(it) ?: text } ?: text
        val runs = remember(node.children) { partitionInlineRuns(node.children, parsedCss) }
        if (href != null && (href.startsWith("http://") || href.startsWith("https://"))) {
            Box(Modifier.clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(href))) } }) {
                FlowRuns(runs, parsedCss, linkStyle, dictName, mediaDataUris, secondary, border, null, ancestors, fillMaxWidth = false)
            }
            return
        }
        Box(Modifier.clickable { if (target.isNotEmpty()) onRecursiveLookup?.invoke(target) }) {
            FlowRuns(runs, parsedCss, linkStyle, dictName, mediaDataUris, secondary, border, null, ancestors, fillMaxWidth = false)
        }
        return
    }

    // External URLs open in the browser (WebView parity: navigateStructuredLink routes
    // internal `?query=` links to lookup, everything else to navigation). The old code
    // made the literal "https://…" string the lookup term.
    if (href != null && (href.startsWith("http://") || href.startsWith("https://"))) {
        Text(
            text = text,
            style = linkStyle,
            modifier = Modifier.clickable {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(href)))
                }
            },
        )
        return
    }

    // Internal lookup links (`?query=...`) keep their content text and route to lookup.
    val target: String = href?.let { extractQuery(it) ?: text } ?: text

    val annotated = remember(target, text, linkStyle) {
        buildAnnotatedString {
            if (target.isNotEmpty()) {
                val link = LinkAnnotation.Clickable(target) {
                    onRecursiveLookup?.invoke(target)
                }
                addLink(link, 0, text.length)
            }
            pushStyle(
                SpanStyle(
                    color = linkStyle.color,
                    fontWeight = linkStyle.fontWeight,
                    textDecoration = linkStyle.textDecoration,
                ),
            )
            append(text)
            pop()
        }
    }
    Text(
        text = annotated,
        style = style,
    )
}

@Composable
private fun StructuredImage(
    node: StructuredNode.Element,
    style: TextStyle,
    dictName: String,
    mediaDataUris: Map<String, String>,
    secondary: Color,
) {
    val path = node.attributes.properties["path"]
    if (path.isNullOrBlank()) return
    val uri = resolveMediaUri2(dictName, path, mediaDataUris)
    // The structured content states the size explicitly (sizeUnits plus width/height), so the
    // box is that size whether or not the bitmap loaded - including for the placeholder below.
    val sizeUnits = node.attributes.properties["sizeUnits"]
    val declaredWidth = node.attributes.properties["width"]?.toFloatOrNull()
    val declaredHeight = node.attributes.properties["height"]?.toFloatOrNull()
    val emBase = if (style.fontSize.isSp) style.fontSize.value else 16f
    val emSized = sizeUnits == "em" && declaredWidth != null
    val declaredBox = if (emSized) {
        val h = if (declaredHeight != null) Modifier.height((declaredHeight * emBase).dp) else Modifier
        Modifier.width((declaredWidth!! * emBase).dp).then(h)
    } else {
        Modifier
    }

    if (uri == null) {
        // In inspection mode (Studio preview / Paparazzi snapshots) Coil cannot load real media, so draw
        // a placeholder of the DECLARED size: same box, different content, so size, position and flow
        // stay verifiable without a device. The box is left empty on purpose - the reference renderer
        // draws a bare box for an image with no alt text, and a label inside a 0.7em-wide accent was
        // pure extra ink that the browser does not draw.
        if (LocalInspectionMode.current) {
            Box(
                modifier = declaredBox
                    .background(secondary.copy(alpha = 0.08f), RoundedCornerShape(2.dp))
                    .border(1.dp, secondary.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
            )
        }
        return
    }

    // Reference sizing: em-sized images render at `width em` scaled to the base font size
    // (sizeUnits=em), otherwise the image's natural aspect at the dictionary's max width.
    if (emSized) {
        Box(modifier = Modifier.padding(vertical = 2.dp)) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = declaredBox.heightIn(max = 240.dp),
            )
        }
        return
    }
    Box(modifier = Modifier.padding(vertical = 4.dp)) {
        AsyncImage(model = uri, contentDescription = null, modifier = Modifier.widthIn(max = 240.dp))
    }
}

@Composable
private fun StructuredDetails(
    node: StructuredNode.Element,
    parsedCss: ParsedCss,
    style: TextStyle,
    dictName: String,
    mediaDataUris: Map<String, String>,
    secondary: Color,
    border: Color,
    onRecursiveLookup: ((String) -> Unit)?,
    ancestors: List<CssAncestor> = emptyList(),
) {
    var expanded by remember(node) { mutableStateOf(node.attributes.properties["open"] == "true") }
    val summary = node.children.firstOrNull {
        it is StructuredNode.Element && it.tag == StructuredTag.Summary
    }
    val body = node.children.filterNot { it === summary }

    // `details[data-sc-content="..."] { padding: ... }` styles the disclosure block itself.
    // Remembered: map lookups run once; without this every expand/collapse toggle
    // re-walks the CSS maps.
    val combined = remember(node.attributes.data, parsedCss, node.attributes.style) {
        getCssStyles(node.attributes.data, parsedCss) + node.attributes.style
    }
    val baseFontSizeSp = fontSizeSp(style)
    val box = remember(combined, baseFontSizeSp) { parseBoxStyle(combined, baseFontSizeSp) }
    val detailsScope = remember(ancestors, node.attributes.data) {
        ancestors + CssAncestor(dataValueKeys(node.attributes.data), "details")
    }

    Column(
        Modifier.padding(
            start = box.paddingStart?.dp ?: 0.dp,
            end = box.paddingEnd?.dp ?: 0.dp,
            top = box.paddingTop?.dp ?: 0.dp,
            bottom = box.paddingBottom?.dp ?: 0.dp,
        ),
    ) {
        Row(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (expanded) "▼ " else "▶ ",
                color = secondary,
                fontSize = style.fontSize * 0.75f,
            )
            if (summary != null) {
                StructuredNodeView(summary, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, detailsScope)
            }
            // A <details> with no <summary> still needs to be openable. The caret alone is the
            // affordance; this used to render a hardcoded English "Details" in a CJK UI.
        }
        if (expanded) {
            Column(Modifier.padding(start = 12.dp)) {
                val runs = remember(body) { partitionInlineRuns(body, parsedCss) }
                FlowRuns(runs, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, detailsScope)
            }
        }
    }
}

@Composable
private fun StructuredTable(
    node: StructuredNode.Element,
    parsedCss: ParsedCss,
    style: TextStyle,
    dictName: String,
    mediaDataUris: Map<String, String>,
    secondary: Color,
    border: Color,
    onRecursiveLookup: ((String) -> Unit)?,
    ancestors: List<CssAncestor> = emptyList(),
) {
    // Flatten rows: table > (thead/tbody/tfoot)? > tr > (th|td)
    val rows = remember(node) {
        mutableListOf<List<StructuredTableCell>>().also { collectTableRows(node, it) }
    }
    if (rows.isEmpty()) return

    // `& table` nested rules (e.g. Jitendex forms `margin-top`) on top of the
    // base.css `table { margin: 6px 0 }` collapsing grid.
    val tableNested = remember(node.attributes.data, parsedCss, ancestors) {
        getNestedStyles("table", node.attributes.data, ancestors, parsedCss)
    }
    val baseFontSizeSp = fontSizeSp(style)
    val tableBox = remember(tableNested, baseFontSizeSp) { parseBoxStyle(tableNested, baseFontSizeSp) }
    val tableScope = remember(ancestors, node.attributes.data) {
        ancestors + CssAncestor(dataValueKeys(node.attributes.data), "table")
    }
    TableGrid(
        rows = rows,
        parsedCss = parsedCss,
        style = style,
        dictName = dictName,
        mediaDataUris = mediaDataUris,
        secondary = secondary,
        border = border,
        onRecursiveLookup = onRecursiveLookup,
        ancestors = tableScope,
        modifier = Modifier.padding(
            top = (tableBox.marginTop ?: 6f).dp,
            bottom = (tableBox.marginBottom ?: 6f).dp,
        ),
    )
}

/**
 * Browser-like auto table layout in ONE [SubcomposeLayout] so every row shares
 * the same column widths (per-row layouts produced ragged, full-width rows).
 * Each column is as wide as its widest non-spanned cell (shrink-to-fit, like
 * `table-layout: auto`); spanned cells take the sum of spanned columns; the
 * whole grid shrink-wraps instead of stretching to the parent.
 *
 * `rowspan` cells occupy slots in the rows below (tracked in an occupancy
 * grid); without this, Jitendex forms tables with sense-count rowspans shift
 * every following row left. When the natural width overflows, the grid keeps
 * its natural size inside a horizontal scroll (WebView `overflow` behavior —
 * e.g. Obunsha stroke-order tables) instead of shrinking text.
 */
@Composable
private fun TableGrid(
    rows: List<List<StructuredTableCell>>,
    parsedCss: ParsedCss,
    style: TextStyle,
    dictName: String,
    mediaDataUris: Map<String, String>,
    secondary: Color,
    border: Color,
    onRecursiveLookup: ((String) -> Unit)?,
    ancestors: List<CssAncestor>,
    modifier: Modifier = Modifier,
) {
    if (rows.isEmpty()) return
    var scroll by remember { mutableStateOf(false) }
    if (scroll) {
        androidx.compose.foundation.layout.Row(
            modifier = modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        ) {
            TableGridInner(rows, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors, maxWidthOverride = Int.MAX_VALUE)
        }
    } else {
        TableGridInner(rows, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, ancestors, null) {
            scroll = true
        }
    }
}

/** Placement of one cell: first column, spans, and rows it occupies. */
private data class CellPlacement(val index: Int, val col: Int, val colSpan: Int, val rowSpan: Int)

@Composable
private fun TableGridInner(
    rows: List<List<StructuredTableCell>>,
    parsedCss: ParsedCss,
    style: TextStyle,
    dictName: String,
    mediaDataUris: Map<String, String>,
    secondary: Color,
    border: Color,
    onRecursiveLookup: ((String) -> Unit)?,
    ancestors: List<CssAncestor>,
    maxWidthOverride: Int?,
    modifier: Modifier = Modifier,
    onOverflow: (() -> Unit)? = null,
) {
    if (rows.isEmpty()) return
    androidx.compose.ui.layout.SubcomposeLayout(modifier = modifier) { constraints ->
        val maxW = maxWidthOverride ?: constraints.maxWidth.takeIf { it < Int.MAX_VALUE } ?: 10000
        fun cellSpan(cell: StructuredTableCell, key: String): Int =
            cell.element?.attributes?.properties?.get(key)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        // Occupancy-aware placement: rowSpan cells reserve slots in the rows below.
        val placements = mutableListOf<List<CellPlacement>>()
        val occupied = mutableListOf<MutableList<Boolean>>()
        rows.forEachIndexed { r, row ->
            while (occupied.size <= r) occupied.add(mutableListOf())
            val rowPlace = mutableListOf<CellPlacement>()
            var col = 0
            row.forEachIndexed { c, cell ->
                // Skip slots a `rowspan` from an earlier row already claimed. The list has to be
                // grown on EVERY step: skipping past a full row of occupied slots walks `col` off
                // the end (a row whose only cells are all occupied by a rowspan, e.g. the 3rd and
                // 4th rows of a 2-column JPDB/旺文社 table with one cell each).
                while (true) {
                    while (occupied[r].size <= col) occupied[r].add(false)
                    if (!occupied[r][col]) break
                    col++
                }
                val cs = cellSpan(cell, "colSpan")
                val rs = cellSpan(cell, "rowSpan")
                rowPlace.add(CellPlacement(c, col, cs, rs))
                repeat(cs) { i ->
                    while (occupied[r].size <= col + i) occupied[r].add(false)
                    occupied[r][col + i] = true
                }
                if (rs > 1) {
                    for (rr in r + 1 until r + rs) {
                        while (occupied.size <= rr) occupied.add(mutableListOf())
                        repeat(cs) { i ->
                            while (occupied[rr].size <= col + i) occupied[rr].add(false)
                            occupied[rr][col + i] = true
                        }
                    }
                }
                col += cs
            }
            placements.add(rowPlace)
        }
        val colCount = (occupied.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
        // Row scopes carry each `tr`'s data so `tr[x] th/td` descendant rules
        // match their cells.
        val rowScopes = rows.map { row ->
            ancestors + CssAncestor(dataValueKeys(row.firstOrNull()?.rowData ?: emptyMap()), "tr")
        }
        // Phase 1: intrinsic width of every cell's content (wrap flows — a
        // fillMaxWidth flow would report the whole parent width per column).
        val intrinsics = rows.mapIndexed { r, row ->
            row.mapIndexed { c, cell ->
                val measurables = subcompose("measure_${r}_${c}") {
                    TableCellContent(cell, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, rowScopes[r], stretch = false)
                }
                measurables.maxOfOrNull { it.maxIntrinsicWidth(Int.MAX_VALUE) } ?: 0
            }
        }
        val colWidths = IntArray(colCount)
        rows.forEachIndexed { r, _ ->
            for (p in placements[r]) {
                if (p.colSpan == 1) colWidths[p.col] = maxOf(colWidths[p.col], intrinsics[r][p.index])
            }
        }
        // Spanned cells claim the sum of spanned columns; grow those on deficit.
        rows.forEachIndexed { r, _ ->
            for (p in placements[r]) {
                if (p.colSpan > 1) {
                    var owned = 0
                    repeat(p.colSpan) { owned += colWidths[p.col + it] }
                    if (owned < intrinsics[r][p.index]) {
                        val add = (intrinsics[r][p.index] - owned + p.colSpan - 1) / p.colSpan
                        repeat(p.colSpan) { colWidths[p.col + it] += add }
                    }
                }
            }
        }
        if (colWidths.all { it == 0 }) {
            // Scroll mode is unbounded: fall back to the real constraints for
            // the fair split so empty tables don't inflate to Int.MAX_VALUE.
            val fairBase = maxWidthOverride
                ?.let { constraints.maxWidth.takeIf { w -> w < Int.MAX_VALUE } }
                ?: maxW
            val fair = (fairBase / colCount.coerceAtLeast(1)).coerceAtLeast(1)
            for (i in colWidths.indices) colWidths[i] = fair
        }
        // Overflow: keep natural width and scroll (see TableGrid) instead of
        // shrinking text — but only when bounded (scroll mode is unbounded).
        val total = colWidths.sum()
        if (maxWidthOverride == null && total > maxW && total > 0) {
            onOverflow?.invoke()
        }
        val scale = if (maxWidthOverride == null && total > maxW && total > 0) {
            maxW.toFloat() / total
        } else {
            1f
        }
        for (i in colWidths.indices) colWidths[i] = (colWidths[i] * scale).toInt().coerceAtLeast(1)
        fun cellWidth(r: Int, c: Int): Int {
            val p = placements[r].first { it.index == c }
            var w = 0
            repeat(p.colSpan) { w += colWidths[p.col + it] }
            return w
        }
        fun colX(col: Int): Int {
            var x = 0
            repeat(col.coerceIn(0, colWidths.size)) { x += colWidths[it] }
            return x
        }
        // Phase 2: measure + place with exact column widths.
        val placeables = rows.mapIndexed { r, row ->
            row.mapIndexed { c, _ ->
                subcompose("place_${r}_${c}") {
                    TableCellContent(rows[r][c], parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup, rowScopes[r], stretch = true)
                }.first().measure(androidx.compose.ui.unit.Constraints.fixedWidth(cellWidth(r, c)))
            }
        }
        val rowHeights = placeables.map { cells -> cells.maxOfOrNull { it.height } ?: 0 }.toMutableList()
        // Rowspan cells stretch the rows they cover: distribute any deficit so the
        // spanned content is never clipped (browser grows the rows the same way).
        rows.forEachIndexed { r, _ ->
            for (p in placements[r]) {
                if (p.rowSpan > 1) {
                    val spanEnd = (r + p.rowSpan).coerceAtMost(rowHeights.size)
                    if (spanEnd > r) {
                        val owned = (r until spanEnd).sumOf { rowHeights[it] }
                        val need = placeables[r][p.index].height
                        if (need > owned) {
                            val per = (need - owned + (spanEnd - r) - 1) / (spanEnd - r)
                            for (rr in r until spanEnd) rowHeights[rr] = rowHeights[rr] + per
                        }
                    }
                }
            }
        }
        val gridWidth = colWidths.sum()
        val gridHeight = rowHeights.sum()
        layout(gridWidth, gridHeight) {
            var py = 0
            placeables.forEachIndexed { r, cells ->
                cells.forEachIndexed { c, placeable ->
                    val p = placements[r].first { it.index == c }
                    // Rowspan cells are vertically centered across the rows they cover.
                    var cy = py
                    if (p.rowSpan > 1) {
                        val spanH = (r until (r + p.rowSpan).coerceAtMost(rowHeights.size)).sumOf { rowHeights[it] }
                        cy = py + ((spanH - placeable.height).coerceAtLeast(0) / 2)
                    }
                    placeable.placeRelative(colX(p.col), cy)
                }
                py += rowHeights[r]
            }
        }
    }
}

@Composable
private fun TableCellContent(
    cell: StructuredTableCell,
    parsedCss: ParsedCss,
    style: TextStyle,
    dictName: String,
    mediaDataUris: Map<String, String>,
    secondary: Color,
    border: Color,
    onRecursiveLookup: ((String) -> Unit)?,
    ancestors: List<CssAncestor> = emptyList(),
    // Place phase stretches to the exact column width (centering needs full
    // width); the measure phase must stay wrapped so intrinsics report content
    // width instead of the parent width.
    stretch: Boolean = false,
) {
    val tagName = if (cell.isHeader) "th" else "td"
    val elData = cell.element?.attributes?.data ?: emptyMap()
    // CSS inheritance: a `tr`'s own font/text rules flow into its cells.
    val rowInherit = remember(cell.rowData, parsedCss) {
        getCssStyles(cell.rowData, parsedCss).filterKeys {
            it in inheritedTableKeys
        }
    }
    val direct = remember(elData, parsedCss) { getCssStyles(elData, parsedCss) }
    val nested = remember(tagName, elData, parsedCss, ancestors) {
        getNestedStyles(tagName, elData, ancestors, parsedCss)
    }
    val inline = cell.element?.attributes?.style ?: emptyMap()
    val combined = remember(rowInherit, direct, nested, inline) {
        rowInherit + direct + nested + inline
    }
    // Descendant scope for cell children (badge spans, ruby, links …).
    val cellScope = remember(ancestors, elData, tagName) {
        ancestors + CssAncestor(dataValueKeys(elData), tagName)
    }
    val baseFontSizeSp = fontSizeSp(style)
    val box = remember(combined, baseFontSizeSp) { parseBoxStyle(combined, baseFontSizeSp) }
    // base.css draws every th/td with a 1px collapsing grid border; an explicit
    // `border-style: none` / `border-width: 0` opts back out.
    val hasBorder = box.hasBorder || defaultCellBorder(combined)
    // Jitendex forms nested rules only set `border-width: 1px` (no color):
    // fall back to the theme border like the WebView's `var(--border)`.
    val borderColor = remember(box.borderColor, border) {
        box.borderColor?.let { parseCssColor2(it) } ?: border
    }
    val cellStyle = remember(style, combined, cell.isHeader) {
        applyTypography(style, combined).let {
            when {
                isBoldWeight(combined["fontWeight"]) -> it.copy(fontWeight = FontWeight.Bold)
                isNormalWeight(combined["fontWeight"]) -> it.copy(fontWeight = FontWeight.Normal)
                cell.isHeader -> it.copy(fontWeight = FontWeight.Bold)
                else -> it
            }
        }.let {
            // base.css `th, td { line-height: 1.2 }` — explicit CSS line-height
            // (handled in applyTypography) always wins over this default.
            if (combined["lineHeight"] == null) {
                val fs = it.fontSize.let { s -> if (s.isSp) s.value else 16f }
                it.copy(lineHeight = (fs * 1.2f).sp)
            } else {
                it
            }
        }
    }
    // `td[data-sc-middle]` (Obunsha): cell content vertically centered in the row.
    val cellVertical = if (box.verticalAlign?.trim()?.lowercase() == "middle") {
        Arrangement.Center
    } else {
        Arrangement.Top
    }
    // Browser default: th centers, td starts; CSS text-align always wins.
    val align = box.textAlign
        ?: combined["textAlign"]?.trim()?.lowercase()?.let { v ->
            when (v) {
                "right", "end" -> TextAlign.End
                "center" -> TextAlign.Center
                "left", "start" -> TextAlign.Start
                "justify" -> TextAlign.Justify
                else -> null
            }
        } ?: if (cell.isHeader) TextAlign.Center else null
    val flowArrangement = if (align == TextAlign.Center) Arrangement.Center else Arrangement.Start
    val columnAlign = if (align == TextAlign.Center) Alignment.CenterHorizontally else Alignment.Start
    // The reference computes `padding-top/bottom: 3px` on `th`/`td`, so 3px is what is used. Note that
    // base.css:127 declares `padding: 0.25em`, which at this font size would be 3.5px - the reference
    // does not use that value, so the stylesheet's declaration is not the source of the number here.
    val padS = (box.paddingStart ?: CELL_PADDING_PX).dp
    val padE = (box.paddingEnd ?: CELL_PADDING_PX).dp
    val padT = (box.paddingTop ?: CELL_PADDING_PX).dp
    val padB = (box.paddingBottom ?: CELL_PADDING_PX).dp

    Column(
        modifier = Modifier
            .then(if (stretch) Modifier.fillMaxWidth() else Modifier)
            .then(if (hasBorder) Modifier.border(0.5.dp, borderColor, RectangleShape) else Modifier)
            .padding(start = padS, end = padE, top = padT, bottom = padB),
        horizontalAlignment = columnAlign,
        verticalArrangement = cellVertical,
    ) {
        val cellRuns = remember(cell.nodes) { partitionInlineRuns(cell.nodes, parsedCss) }
        if (cell.isHeader) {
            FlowRuns(
                cellRuns, parsedCss, cellStyle,
                dictName, mediaDataUris, secondary, border, onRecursiveLookup, cellScope,
                fillMaxWidth = stretch, arrangement = flowArrangement,
            )
        } else {
    // Remembered: resolves radial-gradient + color-mix + hex once. TableGrid's
    // SubcomposeLayout runs this composable twice per composition (measure + place),
    // so an unremembered call costs 2× per recomposition per badge cell.
    val badge = remember(elData["class"], parsedCss) { formBadge(elData["class"], parsedCss) }
    val (marker, badgeBg, badgeFg) = badge
            if (marker != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = flowArrangement,
                    modifier = if (stretch) Modifier.fillMaxWidth() else Modifier,
                ) {
                    StructuredFormBadge(marker, badgeBg, badgeFg)
                    if (cell.nodes.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f, fill = stretch && align != TextAlign.Center)) {
                            FlowRuns(cellRuns, parsedCss, cellStyle, dictName, mediaDataUris, secondary, border, onRecursiveLookup, cellScope, fillMaxWidth = stretch, arrangement = flowArrangement)
                        }
                    }
                }
            } else {
                FlowRuns(cellRuns, parsedCss, cellStyle, dictName, mediaDataUris, secondary, border, onRecursiveLookup, cellScope, fillMaxWidth = stretch, arrangement = flowArrangement)
            }
        }
    }
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
private fun defaultCellBorder(combined: Map<String, String>): Boolean {
    if (combined["borderStyle"]?.trim()?.lowercase() == "none") return false
    val w = combined["borderWidth"]?.trim()?.lowercase()
    if (w == "0" || w == "0px") return false
    if (combined["border"]?.trim()?.lowercase() in setOf("none", "0", "0px")) return false
    return true
}

private data class StructuredTableCell(
    val nodes: List<StructuredNode>,
    val isHeader: Boolean,
    val element: StructuredNode.Element? = null,
    val rowData: Map<String, String> = emptyMap(),
)

@Composable
private fun StructuredFormBadge(marker: String, badgeColor: Color?, badgeFg: Color? = null) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(badgeColor ?: Color.Gray),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            marker,
            color = badgeFg ?: Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Recursively collects `tr` rows (each a list of cell nodes) from a table tree. */
private fun collectTableRows(node: StructuredNode.Element, out: MutableList<List<StructuredTableCell>>) {
    if (node.tag == StructuredTag.Tr) {
        val rowData = node.attributes.data
        val cells = node.children
            .filterIsInstance<StructuredNode.Element>()
            .filter { it.tag == StructuredTag.Td || it.tag == StructuredTag.Th }
            .map { StructuredTableCell(it.children, it.tag == StructuredTag.Th, it, rowData) }
        if (cells.isNotEmpty()) out.add(cells)
    } else {
        node.children.forEach { child ->
            if (child is StructuredNode.Element) collectTableRows(child, out)
        }
    }
}

/** The marker glyph + circle/fg colours for a Jitendex `td[data-sc-class="form-*"]` badge. */
private val CLASS_SPLIT_REGEX = Regex("\\s+")

private fun formBadge(dataClass: String?, parsedCss: ParsedCss): Triple<String?, Color?, Color?> {
    if (dataClass.isNullOrBlank()) return Triple(null, null, null)
    // class="form-pri form-irr" — split tokens and merge, matching getCssStyles semantics
    // (selectorStyles only holds single-token keys).
    val styles = getCssStyles(mapOf("class" to dataClass), parsedCss)
    if (styles.isEmpty()) return Triple(null, null, null)
    val marker = (styles["beforeContent"] ?: "").trim('"', '\'', ' ')
        .takeIf { it.isNotEmpty() }
    // `radial-gradient(<color> 50%, white 100%)` — depth-aware first stop so
    // `var(--text-color, var(--fg, #333))` resolves instead of falling to gray.
    val bg = styles["background"]
        ?.let { extractRadialFirstSegment(it) ?: it }
        ?.let { extractBackgroundColor(it) ?: it }
        ?.let { parseCssColor2(it) }
    val fg = styles["color"]
        ?.let { extractBackgroundColor(it) ?: it }
        ?.let { parseCssColor2(it) }
    return Triple(marker, bg, fg)
}

// ---------------------------------------------------------------------------
// Text / helpers
// ---------------------------------------------------------------------------

/** Longest forward slice taken from a CJK tap, matching the WebView renderer's cap. */
internal const val LOOKUP_SCAN_MAX_CHARS = 24

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
internal fun Char.isLookupCjk(): Boolean = when (this) {
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
internal fun lookupWordAt(text: String, offset: Int): String? {
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

@Composable
private fun spanText(text: String, style: TextStyle, onRecursiveLookup: ((String) -> Unit)?) {
    // Only truly empty text is dropped. Dropping `isBlank()` deleted whitespace-only nodes, and
    // GlossaryJsonParser deliberately preserves them - so `<b>foo</b> <i>bar</i>` rendered as
    // "foobar" whenever the space fell between two element boundaries and was not merged into a
    // neighbouring run by partitionInlineRuns.
    if (text.isEmpty()) return
    val targetable = onRecursiveLookup != null && text.isNotBlank()
    if (!targetable) {
        Text(text = text, style = style)
        return
    }
    // Hit-test the tap to a character offset and look up only that word, the way the WebView
    // renderer does with caretRangeFromPoint. The accessibility action keeps whole-string
    // semantics, because a screen reader announces the run, not a caret position.
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    val lookup = onRecursiveLookup
    Text(
        text = text,
        style = style,
        onTextLayout = { layout = it },
        modifier = Modifier
            .clickable(
                onClickLabel = null,
                onClick = { lookup(text) },
            )
            .pointerInput(text) {
                detectTapGestures { pos ->
                    val result = layout ?: return@detectTapGestures
                    val offset = result.getOffsetForPosition(Offset(pos.x, pos.y))
                    val word = lookupWordAt(text, offset)
                    if (word != null && word != text) lookup(word)
                }
            },
    )
}

private fun StructuredNode.collect(): String = when (this) {
    is StructuredNode.Text -> text
    is StructuredNode.Element -> children.joinToString("") { it.collect() }
    StructuredNode.TextBreak -> "\n"
}

/** Like [collect] but omits subtrees hidden by `display: none` / `visibility: hidden`. */
private fun StructuredNode.collectVisible(parsedCss: ParsedCss): String =
    buildString { appendVisible(this@collectVisible, parsedCss) }

private fun StringBuilder.appendVisible(node: StructuredNode, parsedCss: ParsedCss) {
    when (node) {
        is StructuredNode.Text -> append(node.text)
        StructuredNode.TextBreak -> append('\n')
        is StructuredNode.Element -> {
            val combined = getCssStyles(node.attributes.data, parsedCss) + node.attributes.style
            val display = combined["display"]?.trim()?.lowercase()
            if (display == "none" || combined["visibility"]?.trim()?.lowercase() == "hidden") return
            node.children.forEach { appendVisible(it, parsedCss) }
        }
    }
}

private fun applyTypography(base: TextStyle, css: Map<String, String>, density: Float = 1f): TextStyle {
    var style = base
    val currentSp = base.fontSize.value.takeIf { it > 0f } ?: 16f
    // `color` may be a color-mix() (127k uses, e.g. lime mixed with text-color) — blend first.
    css["color"]?.let { raw ->
        val hex = if (raw.contains("color-mix", ignoreCase = true)) blendColorMix(raw) ?: raw else raw
        parseCssColor2(hex)
    }?.let { style = style.copy(color = it) }
    css["fontSize"]?.let { parseFontSize(it, currentSp) }?.let { newSize ->
        // Unitless `line-height: 1.4` scales with the font size in CSS, but Compose
        // stores an absolute sp — rescale it so `1.3em`/`0.8em` boxes (example
        // sentences, xref content) keep proportional spacing instead of going
        // tight/loose. Explicit `line-height` below overwrites this.
        val oldSize = style.fontSize
        val oldLine = style.lineHeight
        style = style.copy(fontSize = newSize)
        if (css["lineHeight"] == null && oldSize.isSp && oldLine.isSp &&
            oldSize.value > 0f && newSize.isSp && newSize.value > 0f
        ) {
            style = style.copy(lineHeight = oldLine * (newSize.value / oldSize.value))
        }
    }
    // `line-height` (base.css `body { line-height: 1.4 }`, tables `1.2`): unitless
    // numbers multiply the (possibly just-scaled) font size; lengths scale like font-size.
    css["lineHeight"]?.let { parseLineHeight(it, style.fontSize.value.takeIf { v -> v > 0f } ?: currentSp) }?.let {
        style = style.copy(lineHeight = it)
    }
    when (css["fontWeight"]) {
        "bold", "bolder" -> style = style.copy(fontWeight = FontWeight.Bold)
        "normal" -> style = style.copy(fontWeight = FontWeight.Normal)
    }
    (css["fontWeight"]?.toIntOrNull())?.let { if (it >= 600) style = style.copy(fontWeight = FontWeight.Bold) }
    if (css["fontStyle"] == "italic") style = style.copy(fontStyle = FontStyle.Italic)
    if (css["textDecoration"]?.contains("underline") == true || css["textDecorationLine"]?.contains("underline") == true) {
        style = style.copy(textDecoration = TextDecoration.Underline)
    }
    // opacity: Oxford tiers 0.5/.7/.8 and rare/furigana — apply via TextStyle color alpha
    css["opacity"]?.trim()?.toFloatOrNull()?.let { raw ->
        val o = when {
            raw in 0f..1f -> raw
            raw > 1f && raw <= 100f -> (raw / 100f).coerceIn(0f, 1f)
            else -> null
        }
        if (o != null) {
            val currentColor = style.color
            if (currentColor != Color.Unspecified) {
                style = style.copy(color = currentColor.copy(alpha = currentColor.alpha * o))
            } else {
                // If no color set, use black with opacity (will be overridden by theme onBg)
                style = style.copy(color = Color.Black.copy(alpha = o))
            }
        }
    }
    // `vertical-align: super` / `sub`. The offset comes from [superBaselineShiftPx]; Compose's own
    // `BaselineShift.Superscript` is a fixed fraction of the SPAN's font size, so both the magnitude
    // and the reference point differ. [BaselineShift] is an em fraction of the span's own size, so the
    // px offset is divided by that.
    // fraction of the span's OWN size, so the px offset is divided by this span's font size.
    val va = css["verticalAlign"]?.trim()?.lowercase()
    if (va == "super" || va == "sub") {
        val parentPx = currentSp * density
        val shiftPx = if (va == "super") superBaselineShiftPx(parentPx) else -subBaselineShiftPx(parentPx)
        val ownPx = fontSizeSp(style) * density
        if (ownPx > 0f) {
            style = style.copy(baselineShift = BaselineShift(shiftPx / ownPx))
        }
    }
    // textAlign right/center/left — 341k uses
    css["textAlign"]?.trim()?.lowercase()?.let { v ->
        val align = when (v) {
            "right", "end" -> TextAlign.End
            "center" -> TextAlign.Center
            "left", "start" -> TextAlign.Start
            "justify" -> TextAlign.Justify
            else -> null
        }
        if (align != null) style = style.copy(textAlign = align)
    }
    // wordBreak keep-all + whiteSpace nowrap — parsed safely, no Compose equivalent for keep-all; nowrap on tags is handled by FlowRow layout
    return style
}

private fun parseFontSize(value: String, baseFontSizeSp: Float): TextUnit? {
    val trimmed = value.trim().lowercase()
    val base = baseFontSizeSp.takeIf { it > 0f } ?: 16f
    return when {
        trimmed.endsWith("px") -> trimmed.removeSuffix("px").toFloatOrNull()?.sp
        trimmed.endsWith("em") -> (trimmed.removeSuffix("em").toFloatOrNull() ?: return null).times(base).sp
        trimmed.endsWith("rem") -> (trimmed.removeSuffix("rem").toFloatOrNull() ?: return null).times(base).sp
        trimmed.endsWith("%") -> ((trimmed.removeSuffix("%").toFloatOrNull() ?: return null) / 100f).times(base).sp
        else -> null
    }
}

private fun parseLineHeight(value: String, baseFontSizeSp: Float): TextUnit? {
    val trimmed = value.trim().lowercase()
    if (trimmed.isEmpty() || trimmed == "normal" || trimmed == "inherit" || trimmed == "initial") return null
    val base = baseFontSizeSp.takeIf { it > 0f } ?: 16f
    trimmed.toFloatOrNull()?.let { return (it * base).sp }
    return parseFontSize(trimmed, base)
}

// Color resolution lives in CssColors.kt (parseCssColor2 / blendColorMix shared with parser).

private val QUERY_PARAM_REGEX = Regex("[?&]([^=]+)=([^&]+)")

private fun extractQuery(href: String): String? {
    val params = QUERY_PARAM_REGEX.findAll(href)
    return params.asSequence().mapNotNull { m ->
        if (m.groupValues[1] == "query") m.groupValues[2].replace("+", " ") else null
    }.firstOrNull()?.let { raw ->
        // WebView's URLSearchParams.get() percent-decodes; match it so kanji
        // queries (`?query=%E4%B8%80…`) look up instead of missing.
        runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }
}

private fun resolveMediaUri2(dictName: String, path: String, map: Map<String, String>): String? {
    if (map.isEmpty()) return null
    val cleaned = path.removePrefix("media://").removePrefix("media:").trimStart('/')
    for (candidate in listOf("$dictName\u0000$path", "$dictName\u0000$cleaned")) {
        map[candidate]?.let { return it }
    }
    return null
}
