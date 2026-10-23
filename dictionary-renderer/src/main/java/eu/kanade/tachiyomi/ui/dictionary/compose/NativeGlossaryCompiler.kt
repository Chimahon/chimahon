package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.icu.text.BreakIterator
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.style.LeadingMarginSpan
import android.text.style.ReplacementSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import chimahon.diagnostics.LookupProfiler
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Width used when measuring a table cell's natural size: wide enough that nothing wraps. */
private const val MEASURE_WIDTH = 100000

/** Lowers the source directly into positioned native flows. There is no eligibility or intrinsic pass. */
public class NativeGlossaryCompiler(
    private val css: ParsedCss,
    private val density: Density,
    private val direction: LayoutDirection,
    private val resolver: FontFamily.Resolver,
    private val secondary: Color,
    private val border: Color,
    private val expanded: Set<StructuredNode.Element> = emptySet(),
    private val checkCancellation: () -> Unit = {},
    private val profilingId: Long = 0,
    val resources: NativeGlossaryResources = NativeGlossaryResources(density, direction, resolver),
) {
    init { resources.profilingId = profilingId }
    private var activeLink: String? = null
    /**
     * CSS `vertical-align` of the inline box that currently contains an image. The property is not
     * inherited, but an inline box shrinks to its content; when that content is a single replaced
     * element, aligning the box moves the element with it.
     */
    private var imageAlign: String? = null
    /** `text-indent` is inherited: a nested block starts its first line at the same offset. */
    private var inheritedTextIndent = 0
    /**
     * The element whose top margin was folded into its parent's collapsed margin (CSS 2.1 8.3.1).
     * The element skips its own margin when it is visited.
     */
    private var marginCollapseTarget: StructuredNode.Element? = null
    /**
     * True until the document has produced its first content.
     *
     * CSS 2.1 8.3.1: a block's top margin collapses with the margin of its containing block, and
     * the collapsed margin sits OUTSIDE the containing block. The gloss body has no margin of its
     * own, so the first block's margin is placed above the body - which is where the reference's
     * capture starts, because it is cropped to the body's box. Applying it inside the document
     * pushed the first line 4 to 5 CSS px lower than the reference's on every case that opens with
     * a list or a block.
     */
    private var documentStart = true
    private var listDepth = 0
    private var tableCellDepth = 0
    private var whiteSpace = "normal"
    private var textSpacingTrim = "normal"
    private var measuringIntrinsic = false
    private var activeTableCell: TableCellInput? = null
    private val tableLineBreaks by lazy { BreakIterator.getLineInstance(Locale.JAPANESE) }
    private fun px(value: Float) = (value * density.density).roundToInt()
    private fun verticalPx(value: Float) = value * density.density
    private fun cellStroke(box: BoxStyle, own: Map<String, String>, collapsed: Boolean): Int {
        if (!box.hasBorder && !defaultCellBorder(own)) return 0
        val width = (box.borderWidth ?: CELL_BORDER_PX) * density.density
        return if (width <= 0f) 0 else if (collapsed) floor(width).toInt().coerceAtLeast(1) else width.roundToInt()
    }
    private fun argb(value: String?, default: Color = Color.Transparent) = value?.let { parseCssColor2(it) }?.toArgb() ?: default.toArgb()

    /** The first in-flow child that generates a block box; whitespace-only text does not count. */
    private inline fun inspectFirstBlockChild(node: StructuredNode.Element, scope: NativeCssContext,
        consume: (StructuredNode.Element, Map<String, String>) -> Unit) {
        for (child in node.children) {
            // Anything that generates content before the child separates the two margins, so the
            // search has to STOP at it instead of looking further: case 13's <li>text<ul/></li>
            // collapsed the list's margin to the TOP of the li and pushed the text 4 CSS down.
            if (child is StructuredNode.Text) {
                if (!child.text.isBlank()) return
                continue
            }
            if (child !is StructuredNode.Element) return
            if (child.tag == StructuredTag.Rt || child.tag == StructuredTag.Rp) continue
            val own = declarations(child, scope)
            if (hidden(own)) continue
            if (!isInlineLevel(child) || own["display"] == "block") consume(child, own)
            return
        }
    }

    /**
     * The top margin this element contributes, in the same unit as the block defaults. Mirrors the
     * per-tag defaults that [block] applies, so a parent can fold its first child's margin into
     * its own collapsed margin.
     */
    private fun marginTopOf(node: StructuredNode.Element, style: TextStyle, own: Map<String, String>): Float {
        val default = when (node.tag) {
            StructuredTag.Table -> TABLE_MARGIN_PX
            StructuredTag.OrderedList, StructuredTag.UnorderedList -> LIST_MARGIN_EM * fontSizeSp(style)
            StructuredTag.Details -> 0f
            StructuredTag.ListItem -> if (listDepth > 0) LIST_ITEM_MARGIN_EM * fontSizeSp(style) else 0f
            else -> if (!isInlineLevel(node) || own["display"] == "block") {
                if (node.tag == StructuredTag.Div) BLOCK_MARGIN_PX else 0f
            } else return 0f
        }
        return resources.box(own, style).marginTop ?: default
    }

    fun prepare(nodes: List<StructuredNode>, style: TextStyle, ancestors: List<CssAncestor>, width: Int): NativeGlossaryDocument {
        require(width >= 0 && width != Constraints.Infinity)
        val items = ArrayList<NativeGlossaryItem>()
        val rootAncestors = ancestors.map { it.copy(values = it.values.toSet()) }
        listDepth = rootAncestors.count { it.tag == "ul" || it.tag == "ol" }
        val scope = NativeCssContext(css)
        for (ancestor in rootAncestors) scope.push(ancestor.values, ancestor.tag)
        val height = LookupProfiler.section(profilingId, "native.document.lower_layout") {
            flow(nodes, style, scope, 0, width.coerceAtLeast(1), 0f, true, items)
        }
        return NativeGlossaryDocument(nodes, css, style, rootAncestors, density, direction, resolver, secondary, border,
            width, ceil(height).toInt(), items.toList(), resources, profilingId)
    }

    private fun declarations(node: StructuredNode.Element, scope: NativeCssContext, preceding: List<Map<String, String>> = emptyList()): Map<String, String> {
        resources.elements++
        resources.cssMatches++
        val started = System.nanoTime()
        val merged = scope.declarations(node, preceding)
        resources.declarationsNanos += System.nanoTime() - started
        // Interned so the typography and box caches can use identity keys instead of hashing the
        // whole map again on every lookup.
        return resources.intern(merged)
    }

    private fun hidden(declarations: Map<String, String>) = declarations["display"] == "none" || declarations["visibility"] in HIDDEN

    private fun semantic(style: TextStyle, tag: StructuredTag): TextStyle = when (tag) {
        StructuredTag.Bold -> style.copy(fontWeight = FontWeight.Bold)
        StructuredTag.Italic -> style.copy(fontStyle = FontStyle.Italic)
        StructuredTag.Underline -> style.copy(textDecoration = TextDecoration.Underline)
        StructuredTag.Strike -> style.copy(textDecoration = TextDecoration.LineThrough)
        StructuredTag.Small -> style.copy(fontSize = style.fontSize * 0.8f, lineHeight = style.lineHeight * 0.8f)
        StructuredTag.Superscript -> style.copy(fontSize = style.fontSize * 0.75f, baselineShift = BaselineShift.Superscript)
        StructuredTag.Subscript -> style.copy(fontSize = style.fontSize * 0.75f, baselineShift = BaselineShift.Subscript)
        StructuredTag.Mark -> style.copy(background = secondary.copy(alpha = 0.2f))
        else -> style
    }

    private fun flow(nodes: List<StructuredNode>, style: TextStyle, scope: NativeCssContext, x: Int, width: Int,
        y: Float, interactive: Boolean, output: MutableList<NativeGlossaryItem>, vertical: Boolean = false,
        rubyLineHeight: Boolean = false, textIndent: Int = 0, marginOwner: TextCursor? = null,
        before: String? = null): Float {
        val cursor = TextCursor(x, width.coerceAtLeast(1), y, style, interactive, output, vertical, rubyLineHeight,
            textIndent)
        before?.let { cursor.noLookup(it, style) }
        visitChildren(nodes, style, scope, cursor)
        val bottom = cursor.finish()
        if (marginOwner != null) marginOwner.pendingBottom = maxOf(marginOwner.pendingBottom, cursor.pendingBottom)
        return bottom
    }

    private fun visitChildren(nodes: List<StructuredNode>, inherited: TextStyle, scope: NativeCssContext, cursor: TextCursor) {
        val preceding = if (css.siblingRules.isEmpty()) null else ArrayList<Map<String, String>>()
        for (node in nodes) {
            visit(node, inherited, scope, cursor, preceding ?: emptyList())
            if (preceding != null && node is StructuredNode.Element) preceding.add(0, node.attributes.data)
        }
    }

    private fun visit(node: StructuredNode, inherited: TextStyle, scope: NativeCssContext, cursor: TextCursor,
        preceding: List<Map<String, String>>) {
        checkCancellation()
        // The element whose top margin was already folded into its parent's collapsed margin.
        val skipMarginTop = node === marginCollapseTarget
        if (skipMarginTop) marginCollapseTarget = null
        when (node) {
            is StructuredNode.Text -> cursor.append(node.text, inherited)
            StructuredNode.TextBreak -> cursor.appendBreak(inherited)
            is StructuredNode.Element -> {
                if (node.tag == StructuredTag.Rt || node.tag == StructuredTag.Rp) return
                val own = declarations(node, scope, preceding)
                if (hidden(own) || node.attributes.data["content"] == "attribution") return
                val resolved = resources.style(semantic(inherited, node.tag), own)
                val style = inlineAlignment(inherited, resolved, own["verticalAlign"], node.tag)
                scope.push(node)
                val isList = node.tag == StructuredTag.UnorderedList || node.tag == StructuredTag.OrderedList
                if (isList) listDepth++
                val previousWhiteSpace = whiteSpace
                whiteSpace = own["whiteSpace"] ?: previousWhiteSpace
                val previousTrim = textSpacingTrim
                textSpacingTrim = own["textSpacingTrim"] ?: previousTrim
                try {
                    when (node.tag) {
                        StructuredTag.Ruby -> ruby(node, style, scope, cursor)
                        StructuredTag.Break -> cursor.appendBreak(style)
                        StructuredTag.Table -> block(cursor, style, own, TABLE_MARGIN_PX, skipMarginTop = skipMarginTop) { x, width, y ->
                            table(node, style, scope, x, width, y, cursor.output)
                        }
                        StructuredTag.OrderedList, StructuredTag.UnorderedList -> block(cursor, style, own, LIST_MARGIN_EM * fontSizeSp(style), padStart = false, skipMarginTop = skipMarginTop) { x, width, y ->
                            list(node, style, own, scope, x, width, y, cursor.interactive, cursor)
                        }
                        StructuredTag.Details -> block(cursor, style, own, 0f, skipMarginTop = skipMarginTop) { x, width, y ->
                            details(node, style, scope, x, width, y, cursor.interactive, cursor.output)
                        }
                        StructuredTag.Image -> {
                            // HTML's img is an inline replaced element by default. A dictionary
                            // that wants a figure sets display:block; treating every image as a
                            // block put each 1em sense icon on its own line and pushed the text
                            // below it (国語辞典オンライン's 大舞台, +246 device px).
                            if (own["display"]?.trim()?.lowercase() == "block") {
                                cursor.flush()
                                image(node, style, scope, cursor)
                            } else {
                                inlineImage(node, style, own, scope, cursor)
                            }
                        }
                        StructuredTag.HorizontalRule -> {
                            cursor.flush()
                            cursor.output.add(NativeGlossaryRect(cursor.x, (cursor.y + verticalPx(4f)).roundToInt(), cursor.width, px(1f).coerceAtLeast(1), border.toArgb()))
                            cursor.y += verticalPx(9f)
                        }
                        else -> if (!isInlineLevel(node) || own["display"] == "block") {
                            // CSS 2.1 8.3.1: a block's top margin collapses with the top margin of
                            // its first in-flow child when nothing separates them, and the collapsed
                            // margin is the LARGER of the two and sits outside the parent. Adding the
                            // parent's and the child's put every nested list 4px lower than the
                            // browser did: 用例.jp's glosses started 12 device px too low for exactly
                            // this reason (div margin 4px vs ul margin 0.3em = 4.2px).
                            val box = resources.box(own, style)
                            val ownMargin = box.marginTop ?: when (node.tag) {
                                StructuredTag.Div -> BLOCK_MARGIN_PX
                                StructuredTag.ListItem -> if (listDepth > 0) LIST_ITEM_MARGIN_EM * fontSizeSp(style) else 0f
                                else -> 0f
                            }
                            var collapsed = ownMargin
                            if (!box.hasBorder && (box.paddingTop ?: 0f) <= 0f) {
                                inspectFirstBlockChild(node, scope) { child, childOwn ->
                                    val childMargin = marginTopOf(child, style, childOwn)
                                    if (childMargin > 0f) {
                                        collapsed = maxOf(ownMargin, childMargin)
                                        marginCollapseTarget = child
                                    }
                                }
                            }
                            val previousIndent = inheritedTextIndent
                            val indent = textIndentOf(own, style) ?: previousIndent
                            inheritedTextIndent = indent
                            // CSS 2.1 8.3.1: an empty block's own margins collapse through it, so it
                            // adds no height of its own; case 13's trailing empty <ul> pushed the
                            // document 4 CSS px down. Its margins merge with its neighbours'.
                            if (node.children.isEmpty() && own["beforeContent"].isNullOrEmpty() && !box.hasBorder &&
                                (box.paddingTop ?: 0f) <= 0f && (box.paddingBottom ?: 0f) <= 0f) {
                                val top = if (skipMarginTop || documentStart) 0f else verticalPx(collapsed)
                                cursor.pendingBottom = maxOf(cursor.pendingBottom, top,
                                    verticalPx(box.marginBottom ?: collapsed))
                                inheritedTextIndent = previousIndent
                                return
                            }
                            try {
                                block(cursor, style, own, collapsed, skipMarginTop = skipMarginTop, resolvedMargin = collapsed) { x, width, y ->
                                    flow(node.children, style, scope, x, width, y, cursor.interactive, cursor.output,
                                        vertical = isVerticalWriting(own), rubyLineHeight = true, textIndent = indent,
                                        marginOwner = cursor, before = own["beforeContent"])
                                }
                            } finally {
                                marginCollapseTarget = null
                                inheritedTextIndent = previousIndent
                            }
                        } else {
                            val box = resources.box(own, style)
                            cursor.space(px((box.marginStart ?: 0f) + (box.paddingStart ?: 0f)), style)
                            val start = cursor.length
                            val previousLink = activeLink
                            val previousAlign = imageAlign
                            if (node.tag == StructuredTag.Link && cursor.interactive) {
                                activeLink = node.attributes.properties["href"]?.let { extractQuery(it) ?: node.collect() }
                            }
                            if (own["verticalAlign"] != null) imageAlign = own["verticalAlign"]
                            try {
                                own["beforeContent"]?.let { cursor.noLookup(it, style) }
                                visitChildren(node.children, style, scope, cursor)
                            } finally {
                                activeLink = previousLink
                                imageAlign = previousAlign
                            }
                            cursor.decorate(start, box, style)
                            cursor.space(px((box.paddingEnd ?: 0f) + (box.marginEnd ?: 0f)), style)
                        }
                    }
                } finally {
                    whiteSpace = previousWhiteSpace
                    textSpacingTrim = previousTrim
                    if (isList) listDepth--
                    scope.pop()
                }
            }
        }
    }

    private inline fun block(cursor: TextCursor, style: TextStyle, own: Map<String, String>, defaultMargin: Float, padStart: Boolean = true,
        skipMarginTop: Boolean = false, resolvedMargin: Float? = null, content: (Int, Int, Float) -> Float) {
        cursor.flush()
        val box = resources.box(own, style)
        // The first block's top margin collapses out of the document; see [documentStart].
        val collapsesOut = documentStart
        documentStart = false
        cursor.y += maxOf(cursor.pendingBottom,
            if (skipMarginTop || collapsesOut) 0f else verticalPx(resolvedMargin ?: box.marginTop ?: defaultMargin))
        cursor.pendingBottom = 0f
        val left = px(box.marginStart ?: 0f)
        val right = px(box.marginEnd ?: 0f)
        val x = cursor.x + if (direction == LayoutDirection.Rtl) right else left
        val width = (cursor.width - left - right).coerceAtLeast(1)
        val top = cursor.y
        val slot = cursor.output.size
        val circle = own["clipPath"]?.trim() == "circle()"
        val paintTop = top.roundToInt()
        cursor.output.add(NativeGlossaryRect(x, paintTop, width, 0))
        if (circle) cursor.output.add(NativeGlossaryRect(x, paintTop, width, 0))
        val start = if (padStart) px(box.paddingStart ?: 0f) else 0
        val end = px(box.paddingEnd ?: 0f)
        cursor.y = content(x + if (direction == LayoutDirection.Rtl) end else start,
            (width - start - end).coerceAtLeast(1), top + verticalPx(box.paddingTop ?: 0f))
        if (box.hasBorder || (box.paddingBottom ?: 0f) > 0f) {
            cursor.y += cursor.pendingBottom
            cursor.pendingBottom = 0f
        }
        cursor.y += verticalPx(box.paddingBottom ?: 0f)
        val stroke = if (box.hasBorder && !box.leftAccent) px(box.borderWidth ?: 1f).toFloat() else 0f
        val height = (cursor.y.roundToInt() - paintTop).coerceAtLeast(0)
        cursor.output[slot + if (circle) 1 else 0] = NativeGlossaryRect(x, paintTop, width, height,
            argb(box.backgroundColor), argb(box.borderColor, border), stroke, px(box.borderRadius ?: 0f).toFloat(),
            radialBackground(own["backgroundImage"] ?: own["background"], width, height))
        if (box.leftAccent) cursor.output.add(NativeGlossaryRect(x, paintTop, px(box.borderWidth ?: 3f), height, argb(box.borderColor, border)))
        if (circle) {
            cursor.output[slot] = NativeCircleClip(x, paintTop, width, height, true)
            cursor.output.add(NativeCircleClip(x, paintTop, width, height, false))
        }
        cursor.pendingBottom = maxOf(cursor.pendingBottom, verticalPx(box.marginBottom ?: defaultMargin))
    }

    private fun list(node: StructuredNode.Element, style: TextStyle, own: Map<String, String>, scope: NativeCssContext,
        x: Int, width: Int, y: Float, interactive: Boolean, owner: TextCursor): Float {
        var bottom = y
        var pendingBottom = 0f
        val output = owner.output
        val box = resources.box(own, style)
        val gutter = px(box.paddingStart ?: LIST_PADDING_START_EM * fontSizeSp(style)).coerceAtLeast(0)
        val previous = ArrayList<Map<String, String>>()
        var number = node.attributes.properties["start"]?.toIntOrNull() ?: 1
        for (child in node.children) {
            checkCancellation()
            if (child !is StructuredNode.Element || child.tag != StructuredTag.ListItem) {
                if (child is StructuredNode.Text && child.text.isBlank()) continue
                bottom += pendingBottom
                pendingBottom = 0f
                bottom = flow(listOf(child), style, scope, x + gutter, (width - gutter).coerceAtLeast(1), bottom, interactive, output)
                continue
            }
            val itemCss = declarations(child, scope, previous)
            if (css.siblingRules.isNotEmpty()) previous.add(0, child.attributes.data)
            val itemStyle = resources.style(style, itemCss)
            if (hidden(itemCss)) { number++; continue }
            val itemBox = resources.box(itemCss, itemStyle)
            bottom += pendingBottom
            pendingBottom = 0f
            val type = itemCss["listStyleType"] ?: own["listStyleType"]
            // Blink's UA stylesheet renders the outside marker as the glyph FOLLOWED BY A SPACE
            // ("\u25aa ", "\u2022 ", "1. "). The marker box is right-aligned against the content
            // edge, so the space is what pushes the glyph left of the text. Without it the native
            // marker sat ~4px right of the browser's and was narrower: the reference's square
            // spanned x 10..23, the native's x 21..30.
            val marker = when (type) {
                "none" -> ""
                "circle" -> "\u25e6 "
                "square" -> "\u25aa "
                "disc" -> "\u2022 "
                "decimal-leading-zero" -> "${number.toString().padStart(2, '0')}. "
                null, "decimal" -> if (node.tag == StructuredTag.OrderedList) "$number. " else "\u2022 "
                "inherit", "initial", "unset" -> if (node.tag == StructuredTag.OrderedList) "$number. " else "\u2022 "
                else -> if (type.startsWith("url(")) "\u2022 " else type.trim('"', '\'')
            }
            val inside = (itemCss["listStylePosition"] ?: own["listStylePosition"]) == "inside"
            if (marker.isNotEmpty() && !inside) {
                // Outside markers preserve whitespace and never wrap (CSS Lists 3, ::marker).
                // Position by the full advance: end alignment trims the marker's trailing space.
                val markerWidth = ceil(Layout.getDesiredWidth(marker, resources.profile(itemStyle).resource.paint))
                    .toInt().coerceAtLeast(1)
                val markerX = if (direction == LayoutDirection.Rtl) x + width - gutter else x + gutter - markerWidth
                val markerLayout = resources.layout(marker, markerWidth, itemStyle.copy(textAlign = TextAlign.Left))
                output.add(NativeTextFlow(markerLayout, markerX, bottom.roundToInt(), false))
            }
            // Outside markers use the item's border edge; padding insets only its content.
            val padStart = px(itemBox.paddingStart ?: 0f)
            val padEnd = px(itemBox.paddingEnd ?: 0f)
            val itemX = if (direction == LayoutDirection.Rtl) x + padEnd else x + gutter + padStart
            val start = bottom
            val item = TextCursor(itemX, (width - gutter - padStart - padEnd).coerceAtLeast(1), bottom, itemStyle, interactive, output)
            scope.push(child)
            try {
                if (inside && marker.isNotEmpty()) item.noLookup(marker, itemStyle)
                visitChildren(child.children, itemStyle, scope, item)
                bottom = item.finish()
            } finally {
                scope.pop()
            }
            if (bottom == start && marker.isNotEmpty()) bottom += resources.profile(itemStyle).resource.lineHeightPx.coerceAtLeast(1f)
            pendingBottom = maxOf(item.pendingBottom,
                verticalPx(itemBox.marginBottom ?: LIST_ITEM_MARGIN_EM * fontSizeSp(itemStyle)))
            number++
        }
        owner.pendingBottom = maxOf(owner.pendingBottom, pendingBottom)
        return bottom
    }

    private fun details(node: StructuredNode.Element, style: TextStyle, scope: NativeCssContext, x: Int, width: Int,
        y: Float, interactive: Boolean, output: MutableList<NativeGlossaryItem>): Float {
        val openByDefault = node.attributes.properties["open"] == "true"
        val open = if (node in expanded) !openByDefault else openByDefault
        val summary = node.children.firstOrNull { it is StructuredNode.Element && it.tag == StructuredTag.Summary }
        val own = if (summary is StructuredNode.Element) declarations(summary, scope) else emptyMap()
        val headerStyle = resources.style(style, own)
        val header = TextCursor(x, width, y, headerStyle, false, output)
        if (!hidden(own) && own["listStyleType"] != "none" && own["display"] !in setOf("block", "inline", "inline-block")) {
            header.disclosureMarker(open, headerStyle)
        }
        if (summary is StructuredNode.Element) {
            if (!hidden(own)) {
                scope.push(summary)
                try { visitChildren(summary.children, headerStyle, scope, header) } finally { scope.pop() }
            }
        }
        else header.append("Details", style)
        var bottom = header.finish()
        if (interactive) output.add(NativeGlossaryDisclosure(x, y.roundToInt(), width, (bottom.roundToInt() - y.roundToInt()).coerceAtLeast(1), node))
        if (open) bottom = flow(node.children.filterNot { it === summary }, style, scope, x,
            width, bottom, interactive, output)
        return bottom
    }

    private fun imageBox(node: StructuredNode.Element, style: TextStyle, scope: NativeCssContext,
        maxWidth: Int): Pair<Int, Int> {
        val data = IMAGE_CONTAINER_DATA
        val container = mergeCssStyles(getCssStyles(data, css, "span"), scope.nestedStyles("span", data))
        val (width, height) = glossaryImageSize(node.attributes.data + node.attributes.properties, container,
            fontSizeSp(style), css.variables["font-size-no-units"]?.toFloatOrNull()?.takeIf { it > 0f } ?: 14f,
            maxWidth / density.density)
        return px(width).coerceAtLeast(1) to ceil(height * density.density).toInt().coerceAtLeast(1)
    }

    private fun image(node: StructuredNode.Element, style: TextStyle, scope: NativeCssContext, cursor: TextCursor) {
        val (width, height) = imageBox(node, style, scope, cursor.width)
        cursor.output.add(NativeGlossaryImage(cursor.x, cursor.y.roundToInt(), width, height, node))
        cursor.y += height
    }

    private fun inlineImage(node: StructuredNode.Element, style: TextStyle, own: Map<String, String>,
        scope: NativeCssContext, cursor: TextCursor) {
        val (width, height) = imageBox(node, style, scope, cursor.width)
        val shift = imageShift(style, own["verticalAlign"] ?: imageAlign, height)
        cursor.inlineImage(node, style, width, height, shift)
    }

    /** CSS `text-indent` in device px; null when the declaration is absent so inheritance applies. */
    private fun textIndentOf(own: Map<String, String>, style: TextStyle): Int? {
        val raw = own["textIndent"]?.trim()?.lowercase() ?: return null
        if (raw == "0" || raw == "0px" || raw == "initial" || raw == "unset") return 0
        val dp = parseDpValue(raw, fontSizeSp(style)) ?: return null
        return px(dp)
    }

    /**
     * CSS `vertical-align` distance from the baseline to the image's bottom margin edge, positive
     * downward. Blink aligns replaced elements the same way for the keyword values; the line box
     * grows only when the shifted box leaves the font's ascent/descent.
     */
    private fun imageShift(style: TextStyle, align: String?, height: Int): Int {
        val value = align?.trim()?.lowercase() ?: return 0
        if (value.isEmpty() || value == "baseline") return 0
        val paint = resources.profile(style).resource.paint
        val metrics = paint.fontMetricsInt
        val em = px(fontSizeSp(style))
        return when (value) {
            "text-bottom", "bottom" -> metrics.descent
            // `top` is measured from the line box, which is not known before the line is built;
            // the font top is the same edge whenever the line-height equals the font height.
            "text-top", "top" -> metrics.ascent + height
            "middle" -> {
                val bounds = Rect()
                paint.getTextBounds("x", 0, 1, bounds)
                (height - bounds.height()) / 2
            }
            "sub" -> em / 5
            "super" -> -em / 3
            else -> {
                val number = Regex("""^[+-]?(?:\d+(?:\.\d+)?|\.\d+)""").find(value)?.value
                    ?.toFloatOrNull() ?: return 0
                val distance = when {
                    value.endsWith("em") -> px(number * fontSizeSp(style))
                    value.endsWith("px") -> px(number)
                    else -> return 0
                }
                -distance
            }
        }
    }

    private fun inlineAlignment(parent: TextStyle, style: TextStyle, declared: String?, tag: StructuredTag): TextStyle {
        if (tag == StructuredTag.Image) return style
        val align = declared ?: when (tag) {
            StructuredTag.Superscript -> "super"
            StructuredTag.Subscript -> "sub"
            else -> return style
        }
        val distance = inlineBaselineDistance(align, fontSizeSp(style),
            style.lineHeight.value.takeIf { style.lineHeight.isSp } ?: fontSizeSp(style) * DEFAULT_LINE_HEIGHT_FACTOR)
        if (align !in INLINE_TEXT_ALIGNMENTS && distance == null) return style
        val parentFont = resources.profile(parent).resource
        val font = resources.profile(style).resource
        val metrics = font.paint.fontMetricsInt
        val halfLeading = floor((font.lineHeightPx - (metrics.descent - metrics.ascent)) / 2f)
        val above = -metrics.ascent + halfLeading
        val shift = parentFont.baselineShiftPx + when (align) {
            "text-bottom" -> font.lineHeightPx - above - parentFont.paint.descent()
            "text-top" -> -above - parentFont.paint.ascent()
            "middle" -> {
                val bounds = Rect()
                parentFont.paint.getTextBounds("x", 0, 1, bounds)
                (font.lineHeightPx + bounds.height()) / 2f - above
            }
            "super" -> parentFont.paint.textSize / 3f + density.density
            "sub" -> -(parentFont.paint.textSize / 5f + density.density)
            else -> verticalPx(distance ?: 0f)
        }
        return style.copy(baselineShift = BaselineShift(shift / -font.paint.ascent()))
    }

    private class RubyParts(val style: TextStyle) {
        val text = StringBuilder()
        private val runs = ArrayList<RubyTextSegment>()
        fun append(value: String, style: TextStyle) {
            if (value.isEmpty()) return
            text.append(value)
            val last = runs.lastOrNull()
            if (last?.style == style) runs[runs.lastIndex] = RubyTextSegment(last.text + value, style)
            else runs.add(RubyTextSegment(value, style))
        }
        fun segments() = if (runs.size == 1 && runs[0].style == style) emptyList() else runs.toList()
        fun clear() { text.setLength(0); runs.clear() }
    }

    private class RubyInput(val source: StructuredNode.Element, val style: TextStyle, val parts: List<RubyPart>)
    private class RubyPart(val text: String, val style: TextStyle, val metrics: RubyMetrics? = null, val extraHeight: Int = 0)

    private fun ruby(node: StructuredNode.Element, style: TextStyle, scope: NativeCssContext, cursor: TextCursor) {
        val cell = activeTableCell
        if (cell == null) {
            lowerRuby(node, style, scope) { text, runStyle, metrics, height -> appendRuby(cursor, text, runStyle, metrics, height) }
        } else {
            val input = cell.ruby(node, style) {
                val parts = ArrayList<RubyPart>()
                lowerRuby(node, style, scope) { text, runStyle, metrics, height -> parts.add(RubyPart(text, runStyle, metrics, height)) }
                RubyInput(node, style, parts)
            }
            for (part in input.parts) appendRuby(cursor, part.text, part.style, part.metrics, part.extraHeight)
        }
    }

    private fun appendRuby(cursor: TextCursor, text: String, style: TextStyle, metrics: RubyMetrics?, height: Int) {
        if (metrics == null) cursor.noLookup(text, style) else {
            resources.rubyPairs++
            cursor.rubyHeight(height)
            cursor.ruby(text, style, metrics)
        }
    }

    private inline fun lowerRuby(node: StructuredNode.Element, style: TextStyle, scope: NativeCssContext,
        emit: (String, TextStyle, RubyMetrics?, Int) -> Unit) {
        val base = RubyParts(style)
        for (child in node.children) {
            checkCancellation()
            if (child is StructuredNode.Element && child.tag == StructuredTag.Rp) continue
            if (child is StructuredNode.Element && child.tag == StructuredTag.Rt) {
                val own = declarations(child, scope)
                val inherited = resources.style(style, own)
                // The annotation is a fraction of the RUBY element's font size, not of the base
                // text's. Chrome's UA default for `rt` is 50%, and a declared `font-size: 0.5em` on
                // the rt means the same thing - 0.5 of the ruby font size. The declared value used
                // to be thrown away and the INHERITED size used instead, so the annotation was
                // drawn at the base text's size, roughly twice the reference's: the reference
                // measured rt/ruby = 0.5 in every case, and the native drew it at 1.0.
                val rubySizeSp = fontSizeSp(style)
                val declaredSize = if (own["fontSize"] == null) {
                    rubySizeSp * 0.5f
                } else {
                    parseMarginValue(own["fontSize"].orEmpty(), rubySizeSp) ?: rubySizeSp * 0.5f
                }
                // Blink clamps a relative font size to its 8 CSS px minimum logical font size:
                // the reference reads a 14 px base's 0.5em annotation at 8 px, not 7 px.
                val size = declaredSize.coerceAtLeast(8f).sp
                // The UA stylesheet declares `rt { line-height: normal }`, so a numeric line-height
                // inherited by the ruby does not reach the annotation unless the dictionary sets it
                // on the rt element itself.
                val readingLineHeight = if (own["lineHeight"] != null) inherited.lineHeight else TextUnit.Unspecified
                val readingStyle = inherited.copy(fontSize = size, lineHeight = readingLineHeight, textDecoration = null)
                val reading = RubyParts(readingStyle)
                if (!hidden(own)) {
                    scope.push(child)
                    try { for (part in child.children) rubyText(part, readingStyle, scope, reading, true) } finally { scope.pop() }
                }
                val baseText = base.text.toString()
                if (baseText.isNotEmpty()) {
                    if (reading.text.isNotEmpty()) {
                        // Blink grows the line by the reading's em height minus the half-leading
                        // the line already carries above the base em box, not by the whole em
                        // (ruby_utils.cc).
                        val readingSize = resources.profile(readingStyle).resource.paint.textSize
                        val metrics = resources.ruby.column(baseText, reading.text.toString(), style, readingStyle, base.segments(), reading.segments())
                        emit(baseText, style, metrics, (readingSize - resources.cssHalfLeading(style)).roundToInt())
                    } else unannotatedRuby(base, emit)
                }
                base.clear()
            } else rubyText(child, style, scope, base, false)
        }
        unannotatedRuby(base, emit)
    }

    private inline fun unannotatedRuby(parts: RubyParts, emit: (String, TextStyle, RubyMetrics?, Int) -> Unit) {
        val segments = parts.segments()
        if (segments.isEmpty()) {
            if (parts.text.isNotEmpty()) emit(parts.text.toString(), parts.style, null, 0)
        } else for (segment in segments) emit(segment.text, segment.style, null, 0)
    }

    private fun rubyText(node: StructuredNode, style: TextStyle, scope: NativeCssContext, parts: RubyParts, annotation: Boolean) {
        checkCancellation()
        when (node) {
            is StructuredNode.Text -> parts.append(node.text, style)
            StructuredNode.TextBreak -> parts.append(" ", style)
            is StructuredNode.Element -> {
                if (node.tag == StructuredTag.Rp || node.tag == StructuredTag.Rt) return
                val own = declarations(node, scope)
                if (hidden(own)) return
                val resolved = resources.style(semantic(style, node.tag), own)
                val childStyle = if (annotation) resolved.copy(textDecoration = null) else resolved
                scope.push(node)
                try { for (child in node.children) rubyText(child, childStyle, scope, parts, annotation) } finally { scope.pop() }
            }
        }
    }

    private data class Cell(val position: GlossaryCellPosition, val items: List<NativeGlossaryItem>, val height: Float,
        val box: BoxStyle, val verticalMiddle: Boolean, val stroke: Float)
    private class TableIntrinsics(val minimum: IntArray, var border: Int = 0)
    private class TableCellInput(val cell: StructuredTableCell, val position: GlossaryCellPosition,
        val row: CssAncestor, val own: Map<String, String>, val style: TextStyle, val box: BoxStyle, val stroke: Int) {
        // Width changes line breaks, not the cell's ruby sequence, styles or shaped glyphs.
        private val rubies = ArrayList<RubyInput>()
        var rubyIndex = 0

        fun ruby(node: StructuredNode.Element, style: TextStyle, lower: () -> RubyInput): RubyInput {
            val index = rubyIndex++
            if (index == rubies.size) rubies.add(lower())
            return rubies[index].also { check(it.source === node && it.style == style) }
        }
    }

    private inline fun <T> cellContent(input: TableCellInput, scope: NativeCssContext, intrinsic: Boolean, block: () -> T): T {
        val previousCell = activeTableCell
        val previousTrim = textSpacingTrim
        val previousIntrinsic = measuringIntrinsic
        activeTableCell = input
        input.rubyIndex = 0
        textSpacingTrim = input.own["textSpacingTrim"] ?: previousTrim
        measuringIntrinsic = intrinsic || previousIntrinsic
        val row = input.row
        scope.push(row.values, row.tag, row.siblingIndex, row.isLastSibling)
        scope.push(dataValueKeys(input.cell.element?.attributes?.data ?: emptyMap()), if (input.cell.isHeader) "th" else "td")
        if (!intrinsic) tableCellDepth++
        return try { block() } finally {
            if (!intrinsic) tableCellDepth--
            scope.pop()
            scope.pop()
            measuringIntrinsic = previousIntrinsic
            textSpacingTrim = previousTrim
            activeTableCell = previousCell
        }
    }

    private fun table(node: StructuredNode.Element, style: TextStyle, scope: NativeCssContext, x: Int, width: Int,
        y: Float, output: MutableList<NativeGlossaryItem>): Float = LookupProfiler.section(profilingId, "native.table.layout") {
        resources.tables++
        val rows = ArrayList<List<StructuredTableCell>>()
        val run = ArrayList<StructuredTableCell>()
        val tableScope = scope.ancestors()
        val tableOwn = declarations(node, scope)
        val fractions = gridTrackFractions(tableOwn)
        var splicedRows = false
        for (child in node.children) {
            if (child is StructuredNode.Element && collectTableRowsFlat(child, css, tableScope, rows, run,
                    gridItems = fractions != null)) splicedRows = true
        }
        if (run.isNotEmpty()) rows.add(run.toList())
        if (rows.isEmpty()) return@section y
        fun StructuredTableCell.span(key: String) = element?.attributes?.properties?.get(key)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val placed = GlossaryTableGrid.place(rows.map { row -> row.map { GlossaryCellSpan(it.span("colSpan"), it.span("rowSpan")) } })
        val grid = if (fractions != null && placed.columns < fractions.size) placed.copy(columns = fractions.size) else placed
        // Grid cells retain their source row/column positions when tr is display: contents.
        val automatic = fractions == null && !splicedRows
        val collapsed = automatic && tableOwn["borderCollapse"] != "separate"
        val inputs = ArrayList<TableCellInput>(grid.cells.size)
        for ((rowIndex, row) in rows.withIndex()) {
            val frame = CssAncestor(dataValueKeys(row.firstOrNull()?.rowData ?: emptyMap()), "tr",
                rowIndex + 1, rowIndex == rows.lastIndex)
            val rowScope = tableScope + frame
            for (cell in row) {
                val own = resources.intern(tableCellCss(cell, css, rowScope))
                val resolved = resources.style(style, own)
                val box = resources.box(own, resolved)
                inputs.add(TableCellInput(cell, grid.cells[inputs.size], frame, own,
                    tableCellStyle(resolved, own, cell.isHeader, box), box, cellStroke(box, own, collapsed)))
            }
        }
        var outerBorder = 0
        val edges = when {
            fractions != null && fractions.size <= grid.columns -> {
                // Explicit fr tracks share the remaining width; out-of-template columns are auto.
                // Measure only those implicit columns, not a second intrinsic pass over every cell.
                val implicit = if (fractions.size < grid.columns) measureCellWidths(inputs, scope,
                    grid, implicitFrom = fractions.size) else intArrayOf()
                grid.columnEdgesFromFractions(width, fractions, implicit)
            }
            // Non-grid cells without row boxes still require CSS anonymous-table grouping.
            splicedRows -> grid.columnEdges(width, measureCellWidths(inputs, scope, grid, minContent = true))
            automatic -> {
                val intrinsics = TableIntrinsics(IntArray(grid.cells.size))
                val maximum = measureCellWidths(inputs, scope, grid, intrinsics = intrinsics, collapsed = collapsed)
                outerBorder = if (collapsed) intrinsics.border else 0
                grid.autoColumnEdges((width - outerBorder).coerceAtLeast(1), grid.columnWidths(intrinsics.minimum), maximum)
            }
            else -> grid.columnEdges(width, measureCellWidths(inputs, scope, grid))
        }
        val cells = ArrayList<Cell>()
        for (input in inputs) {
            checkCancellation()
            val cell = input.cell
            val own = input.own
            val position = input.position
            val box = input.box
            val cellStyle = input.style
            val start = px(box.paddingStart ?: CELL_PADDING_INLINE_PX)
            val end = px(box.paddingEnd ?: CELL_PADDING_INLINE_PX)
            val top = verticalPx(box.paddingTop ?: CELL_PADDING_BLOCK_PX)
            val bottom = verticalPx(box.paddingBottom ?: CELL_PADDING_BLOCK_PX)
            val cellWidth = edges[position.column + position.columns] - edges[position.column]
            val stroke = input.stroke
            val borderStart = if (collapsed) stroke / 2 else 0
            val borderEnd = if (collapsed) stroke - borderStart else 0
            // CSS box model: the border is inside the cell's width, so the text box is the
            // width minus both paddings AND both borders. Leaving the border out gave the
            // 48px 使い分け columns an extra 4 CSS px, which fit a third glyph on a line the
            // reference wraps after two.
            val contentWidth = (cellWidth - start - end - stroke * if (collapsed) 1 else 2).coerceAtLeast(1)
            var textWidth = contentWidth
            if (splicedRows) {
                // The reference wraps these anonymous-row cells after two glyphs even in
                // the wider 48.3px columns: the extra ~10px is column slack, not text space.
                val glyph = resources.profile(cellStyle).resource.paint.textSize
                // ReplacementSpan widths are whole pixels per column, so reserve both
                // rounded slots rather than rounding their combined fractional advance.
                textWidth = minOf(textWidth, ceil(glyph).toInt() * 2)
            }
            // The two-glyph box only forces the wrap; the line itself is centred in the
            // whole content box, so the slack stays on both sides instead of all of it
            // sitting to the right of the text.
            val textInset = if (cellStyle.textAlign == TextAlign.Center) (contentWidth - textWidth) / 2 else 0
            val items = ArrayList<NativeGlossaryItem>()
            val height = cellContent(input, scope, intrinsic = false) {
                flow(cell.nodes, cellStyle, scope, start + borderStart + textInset, textWidth,
                    top + borderStart, false, items,
                    vertical = isVerticalWriting(own), rubyLineHeight = splicedRows) + bottom + borderEnd
            }
            cells.add(Cell(position, items, height, box, box.verticalAlign == "middle", stroke.toFloat()))
        }
        val tops = grid.rowEdges(FloatArray(cells.size) { cells[it].height })
        for (cell in cells) {
            val p = cell.position
            val cellX = x + outerBorder / 2 + if (direction == LayoutDirection.Rtl) {
                (if (collapsed) edges.last() else width) - edges[p.column + p.columns]
            } else edges[p.column]
            val cellTop = y + outerBorder / 2 + tops[p.row]
            val cellY = cellTop.roundToInt()
            val cellHeight = tops[p.row + p.rows] - tops[p.row]
            val cellWidth = edges[p.column + p.columns] - edges[p.column]
            output.add(NativeGlossaryRect(cellX, cellY, cellWidth, (cellTop + cellHeight).roundToInt() - cellY,
                argb(cell.box.backgroundColor), argb(cell.box.borderColor, border), cell.stroke,
                collapsedEdges = if (collapsed) 1 or (if (direction == LayoutDirection.Rtl) 2 else 8) or
                    (if (p.column + p.columns == grid.columns) (if (direction == LayoutDirection.Rtl) 8 else 2) else 0) or
                    (if (p.row + p.rows == grid.rows) 4 else 0) else 0))
            val offset = if (cell.verticalMiddle) (cellHeight - cell.height) / 2 else 0f
            for (item in cell.items) output.add(item.moved(cellX, (cellTop + offset).roundToInt()))
        }
        y + tops.last() + outerBorder
    }

    /** Cell widths: natural (max-content) unless [minContent] asks for the anonymous-row minimum. */
    private fun measureCellWidths(
        inputs: List<TableCellInput>,
        scope: NativeCssContext,
        grid: GlossaryTableGrid,
        minContent: Boolean = false,
        intrinsics: TableIntrinsics? = null,
        collapsed: Boolean = false,
        implicitFrom: Int = 0,
    ): IntArray {
        val widths = ArrayList<Int>()
        for (input in inputs) {
            val cell = input.cell
            val position = input.position
            if (position.column + position.columns <= implicitFrom) {
                widths += 0
                continue
            }
            val own = input.own
            val box = input.box
            val cellStyle = input.style
            val scratch = ArrayList<NativeGlossaryItem>()
            // `word-break: keep-all` keeps a CJK run unbreakable, so its min-content width is
            // the run's full width - 小学館's 犯す/侵す/冒す cells are 37.9px, not 26.4.
            val narrow = minContent && box.wordBreak != "keep-all"
            val natural = cellContent(input, scope, intrinsic = true) {
                flow(cell.nodes, cellStyle, scope, 0, if (narrow) 1 else MEASURE_WIDTH, 0f, false, scratch)
                var widest = 0
                var minimum = 0
                for (item in scratch) {
                    if (item !is NativeTextFlow) continue
                    val layout = item.layout
                    if (layout.lineCount == 0) continue
                    // Centred lines can start at a negative left. Round each advance up:
                    // truncating fractional font metrics makes a max-content cell wrap.
                    for (line in 0 until layout.lineCount) {
                        val rubyExtra = if (implicitFrom > 0) gridRubyIntrinsicExtra(layout, line, cellStyle) else 0f
                        val advance = ceil(layout.getLineRight(line) - layout.getLineLeft(line) + rubyExtra).toInt()
                        widest = maxOf(widest, (if (narrow) 0 else item.x) + advance)
                    }
                    if (intrinsics != null) {
                        minimum = maxOf(minimum, if (box.wordBreak == "keep-all" || (own["whiteSpace"] ?: whiteSpace) == "nowrap") widest
                            else minContentAdvance(layout))
                    }
                    if (!minContent) continue
                    // The reference sizes an anonymous column as if the reading advanced at the
                    // BASE size: 小学館's two-kana readings give the 37.9px columns and its
                    // three-kana りょう/びょう readings the 48.3px ones, even though the drawn
                    // annotation is half size.
                    val spanned = layout.text as? Spanned ?: continue
                    for (ruby in spanned.getSpans(0, spanned.length, NativeRubySpan::class.java)) {
                        val reading = ruby.metrics.readingText
                        if (reading.isNotEmpty()) {
                            val readingWidth = resources.profile(cellStyle).resource.paint.measureText(reading)
                            // The ruby slot is already rounded by ReplacementSpan. Keep that
                            // integer advance when adding the remaining fractional text width.
                            val remainder = (readingWidth - ruby.metrics.baseLayout.advancePx).coerceAtLeast(0f)
                            widest = maxOf(widest, ruby.metrics.widthPx + ceil(remainder).toInt())
                        }
                    }
                }
                // CSS 2.1 17.5.2: a cell's max-content contribution is its content plus
                // padding and border. Measuring only the text made every column narrower
                // than its own content, so layout then subtracted the padding again and
                // re-wrapped text the browser kept on one line (国語辞典オンライン's 読み方
                // header stacked one glyph per line, and its reading broke mid-word).
                val start = px(box.paddingStart ?: CELL_PADDING_INLINE_PX)
                val end = px(box.paddingEnd ?: CELL_PADDING_INLINE_PX)
                val stroke = input.stroke
                val decoration = start + end + stroke * if (collapsed) 1 else 2
                intrinsics?.minimum?.set(widths.size, (minimum + decoration).coerceAtMost(widest + decoration))
                if (intrinsics != null) intrinsics.border = maxOf(intrinsics.border, stroke)
                widest + decoration
            }
            widths += natural.coerceAtLeast(1)
        }
        return grid.columnWidths(widths.toIntArray())
    }

    /** Grid auto tracks include annotation width; normal paragraph carets reserve only the base. */
    private fun gridRubyIntrinsicExtra(layout: Layout, line: Int, style: TextStyle): Float {
        val text = layout.text as? Spanned ?: return 0f
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line)
        var extra = 0f
        for (span in text.getSpans(start, end, NativeRubySpan::class.java)) {
            val metrics = span.metrics
            val readingStyle = metrics.readingStyle ?: continue
            val baseFont = resources.profile(metrics.baseLayout.style).resource.paint.textSize
            fun neighbor(from: Int, to: Int): Float {
                if (from < start || to > end || from >= to || text[from] == '\n' ||
                    text.getSpans(from, to, ReplacementSpan::class.java).isNotEmpty()) return 0f
                val paint = text.getSpans(from, to, NativeStyleSpan::class.java).lastOrNull()?.profile?.resource?.paint
                    ?: resources.profile(style).resource.paint
                if (paint.textSize > baseFont) return 0f
                return paint.measureText(text, from, to)
            }
            val spanStart = text.getSpanStart(span)
            val spanEnd = text.getSpanEnd(span)
            val before = if (spanStart > start) neighbor(Character.offsetByCodePoints(text, spanStart, -1), spanStart) else 0f
            val after = if (spanEnd < end) neighbor(spanEnd, Character.offsetByCodePoints(text, spanEnd, 1)) else 0f
            extra += rubyIntrinsicExtra(metrics.widthPx.toFloat(), metrics.readingLayout.advancePx,
                resources.profile(readingStyle).resource.paint.textSize, before, after)
        }
        return extra
    }

    /** Reads caret advances from the existing layout; never creates a narrow-width StaticLayout. */
    private fun minContentAdvance(layout: Layout): Int {
        val text = layout.text
        val breaks = tableLineBreaks
        breaks.setText(text.toString())
        val spanned = text as? Spanned
        val replacements = spanned?.getSpans(0, text.length, ReplacementSpan::class.java).orEmpty()
        var start = breaks.first()
        var widest = 0f
        var end = breaks.next()
        while (end != BreakIterator.DONE) {
            if (replacements.none { end > spanned!!.getSpanStart(it) && end < spanned.getSpanEnd(it) }) {
                var trimmed = end
                while (trimmed > start && text[trimmed - 1].isWhitespace()) trimmed--
                widest = maxOf(widest, kotlin.math.abs(layout.getPrimaryHorizontal(trimmed) - layout.getPrimaryHorizontal(start)))
                start = end
            }
            end = breaks.next()
        }
        return ceil(widest).toInt()
    }

    private inner class TextCursor(val x: Int, val width: Int, var y: Float, val style: TextStyle,
        val interactive: Boolean, val output: MutableList<NativeGlossaryItem>, val vertical: Boolean = false,
        val rubyLineHeight: Boolean = false, val textIndent: Int = 0) {
        private var text = SpannableStringBuilder()
        private var runStyle: TextStyle? = null
        private var runTrim = "normal"
        // TextStyle.hashCode() is a 15-field structural hash that Compose recomputes on every call,
        // so a HashMap lookup keyed on a TextStyle is not cheap. These memos keep that hash to once
        // per style run and once per paragraph instead of once per text append.
        private var runProfile: NativeTextProfile? = null
        private var paragraphProfile: NativeTextProfile? = null
        private var runStart = 0
        private var paragraphStyle = style
        private var minimumHeightPx = 0f
        private var hasShiftedText = false
        /** The reading's own line box, added to annotated lines when [rubyLineHeight] is on. */
        private var rubyExtra = 0
        private var runLink: String? = null
        private var linkStart = 0
        private val decorations = ArrayList<InlineDecoration>()
        private val inlineImages = ArrayList<InlineImage>()
        var pendingBottom = 0f
        // CSS white-space processing (2.1 16.6): under `normal`, a run of whitespace collapses to
        // one space, and whitespace at the start of a line is dropped. Android's StaticLayout
        // treats a newline as a HARD line break, so a newline inside a text node - which the
        // browser merely collapses to a space - split the gloss into extra lines. 用例.jp and
        // 新語時事用語 ship pretty-printed glosses, so this cost them whole lines of drift.
        private val spacing = CssWhiteSpace()
        val length: Int get() = text.length

        /**
         * CSS white-space processing (2.1 16.6): a run of whitespace collapses to one space, and
         * whitespace at the start of a line is dropped.
         *
         * A NEWLINE is not collapsed, though: the reference turns it into a `<br>`
         * (renderer.js `appendTextWithLineBreaks`), so it is a line break. Treating it as
         * collapsible whitespace merged whole paragraphs into one line - 明鏡国語辞典, 新語時事用語
         * and JLPT each came out hundreds of pixels short.
         */
        private fun collapseWhitespace(value: String): String {
            return spacing.append(value, whiteSpace == "pre-wrap" || whiteSpace == "pre" || whiteSpace == "break-spaces")
        }
        private fun endLink() {
            val target = runLink
            if (interactive && target != null && linkStart < text.length && (linkStart until text.length).any { index ->
                    !text[index].isWhitespace() &&
                        text.getSpans(index, index + 1, NativeNoLookup::class.java).isEmpty()
                }) text.setSpan(NativeLookupLink(target), linkStart, text.length, SPAN_FLAGS)
            linkStart = text.length
        }
        private fun endRun() {
            val current = runStyle
            if (current != null && runStart < text.length) {
                val base = paragraphProfile ?: resources.profile(paragraphStyle).also { paragraphProfile = it }
                text.setSpan(checkNotNull(runProfile).spanFor(base), runStart, text.length, SPAN_FLAGS)
                if (runTrim != "normal") text.setSpan(NativeTextSpacingTrim(runTrim), runStart, text.length, SPAN_FLAGS)
            }
            runStart = text.length
        }
        fun append(value: String, style: TextStyle) {
            appendInternal(verticalize(collapseWhitespace(value)), style)
        }

        /**
         * Vertical writing (`writing-mode: vertical-rl`): the glyphs run DOWN the column instead
         * of across the line, and each one advances by the line height. StaticLayout has no
         * vertical mode, so each glyph is given its own line and the line break does the work.
         *
         * 小学館's example images caption their table cells this way - `td[data-sc-caption] {
         * writing-mode: vertical-rl }` - and the native laid those captions out horizontally,
         * which is what left 小学館's worst case 276 pixels short of the reference.
         */
        private fun verticalize(value: String): String {
            if (!vertical || value.length < 2) return value
            val out = StringBuilder(value.length * 2)
            for (char in value) {
                if (out.isNotEmpty()) out.append('\n')
                out.append(char)
            }
            return out.toString()
        }

        /** Appends text verbatim: an explicit break, a list marker or a layout spacer. */
        fun appendRaw(value: String, style: TextStyle) {
            if (value.any { !isCssWhiteSpace(it) }) spacing.markContent()
            appendInternal(value, style)
        }

        /** An explicit `<br>`: a real line break, and the next line starts fresh. */
        fun appendBreak(style: TextStyle) {
            appendInternal("\n", style)
            spacing.reset()
        }

        private fun appendInternal(value: String, style: TextStyle) {
            if (value.isEmpty()) return
            resources.appends++
            documentStart = false
            if (text.isEmpty()) {
                y += pendingBottom; pendingBottom = 0f
                paragraphStyle = this.style
                paragraphProfile = null
            }
            if (runStyle != style || runTrim != textSpacingTrim) {
                endRun(); runStyle = style; runProfile = null
                runTrim = textSpacingTrim
                hasShiftedText = hasShiftedText || (style.baselineShift?.multiplier ?: 0f) != 0f
            }
            if (runLink != activeLink) { endLink(); runLink = activeLink }
            text.append(value)
            // The run's profile is stable for the whole run, so look it up once per run rather than
            // once per append: each lookup re-hashes a TextStyle.
            val profile = runProfile ?: resources.profile(style).also { runProfile = it }
            minimumHeightPx = maxOf(minimumHeightPx, profile.resource.lineHeightPx)
        }
        fun noLookup(value: String, style: TextStyle) {
            val start = text.length
            appendRaw(value, style)
            if (start < text.length) text.setSpan(NativeNoLookup(), start, text.length, SPAN_FLAGS)
        }
        fun disclosureMarker(open: Boolean, style: TextStyle) {
            val start = text.length
            noLookup("\uFFFC", style)
            text.setSpan(NativeDisclosureMarkerSpan(resources.profile(style).resource.paint.textSize,
                open, direction == LayoutDirection.Rtl), start, text.length, SPAN_FLAGS)
        }
        fun ruby(base: String, style: TextStyle, metrics: RubyMetrics) {
            val start = text.length
            append(base, style)
            text.setSpan(NativeRubySpan(base, metrics), start, text.length, SPAN_FLAGS)
        }
        /** Appends a placeholder for an inline replaced element; the view paints the bitmap. */
        fun inlineImage(node: StructuredNode.Element, style: TextStyle, width: Int, height: Int, shift: Int) {
            val start = text.length
            appendRaw("\uFFFC", style)
            text.setSpan(NativeInlineImageSpan(width, height, shift), start, text.length, SPAN_FLAGS)
            inlineImages.add(InlineImage(start, width, height, shift, node))
        }
        /** The reading's own line box, counted once however many rubies a line carries. */
        fun rubyHeight(pixels: Int) {
            rubyExtra = maxOf(rubyExtra, pixels)
        }
        fun space(width: Int, style: TextStyle) {
            if (width <= 0) return
            val start = text.length
            appendRaw(" ", style)
            text.setSpan(NativeSpacingSpan(width), start, text.length, SPAN_FLAGS)
        }
        fun decorate(start: Int, box: BoxStyle, style: TextStyle) {
            if (start in 0 until text.length && (box.hasBorder || box.hasBackground)) {
                // The inline box's content area is the font's ascent to descent, not the line box.
                val font = resources.profile(style).resource
                decorations.add(InlineDecoration(start, text.length, box,
                    font.paint.ascent() - font.baselineShiftPx, font.paint.descent() - font.baselineShiftPx))
            }
        }
        fun flush() {
            if (text.isEmpty()) return
            // CSS 2.1 16.6.1: a line break at the end of a block generates no line box. Plain-text
            // payloads always end with a newline, and a trailing `<br>` is the same case, so the
            // empty last line the reference never shows would otherwise become document height.
            if (text[text.length - 1] == '\n') text.delete(text.length - 1, text.length)
            if (text.isEmpty()) return
            checkCancellation()
            endRun()
            endLink()
            val profile = checkNotNull(paragraphProfile)
            if (!vertical) applyHanPunctuationSpacing(text, profile.resource.font)
            var gridHeight = 0f
            if (minimumHeightPx > 0f) {
                val base = profile.resource.paint.fontMetricsInt
                // CSS's paragraph strut remains even when every child uses a smaller font.
                gridHeight = maxOf(minimumHeightPx, profile.resource.lineHeightPx)
                NativeCssLineHeight(gridHeight, rubyExtra,
                    -base.ascent, base.descent, y == 0f, hasShiftedText,
                    lineBoxOrigin = tableCellDepth > 0,
                    cropFirstRubyLine = !rubyLineHeight && y == 0f).also {
                    text.setSpan(it, 0, text.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                }
            }
            // CSS 2.1 16.1: text-indent shifts the first line only, and a negative value may pull
            // it left of the block's content edge (the hanging indent in 小学館's level-1 senses).
            if (textIndent != 0) {
                text.setSpan(LeadingMarginSpan.Standard(textIndent, 0), 0, text.length,
                    Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            }
            val layout = resources.layout(SpannedString(text), width, paragraphStyle, profile)
            for (decoration in decorations) {
                val first = layout.getLineForOffset(decoration.start)
                val last = layout.getLineForOffset(decoration.end - 1)
                for (line in first..last) {
                    val path = Path()
                    val bounds = RectF()
                    layout.getSelectionPath(maxOf(decoration.start, layout.getLineStart(line)), minOf(decoration.end, layout.getLineEnd(line)), path)
                    path.computeBounds(bounds, true)
                    // CSS paints an inline box's background and border over the box's CONTENT AREA
                    // - the font's ascent to descent around the baseline - plus its padding, and
                    // not over the whole line box. The selection path is the line box, so using
                    // it stretched every tag chip to the full line height: 小学館's 動 badge came
                    // out 30x85 where the reference draws a 30x30 square.
                    val baseline = layout.getLineTop(line) - layout.getLineAscent(line)
                    val top = baseline + decoration.ascent
                    val bottom = baseline + decoration.descent
                    val box = decoration.box
                    val padStart = px(box.paddingStart ?: 0f)
                    val padEnd = px(box.paddingEnd ?: 0f)
                    val padTop = px(box.paddingTop ?: 0f)
                    val padBottom = px(box.paddingBottom ?: 0f)
                    output.add(NativeGlossaryRect(x + bounds.left.roundToInt() - padStart,
                        (y + top - padTop).roundToInt(),
                        (ceil(bounds.width()).toInt() + padStart + padEnd),
                        (bottom - top).roundToInt() + padTop + padBottom, argb(box.backgroundColor),
                        argb(box.borderColor, paragraphStyle.color.takeIf { it.isSpecified } ?: border),
                        if (box.hasBorder) px(box.borderWidth ?: 1f).toFloat() else 0f, px(box.borderRadius ?: 2f).toFloat()))
                }
            }
            for (image in inlineImages) {
                val line = layout.getLineForOffset(image.start)
                val primary = layout.getPrimaryHorizontal(image.start)
                // Inline replaced element: bottom edge on the line baseline, leading edge at the
                // caret (the right edge in RTL, so the box starts one width to its left).
                val left = if (layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT) {
                    primary - image.width
                } else {
                    primary
                }
                output.add(NativeGlossaryImage((x + left).roundToInt(),
                    (y + layout.getLineBaseline(line) + image.shift - image.height).roundToInt(),
                    image.width, image.height, image.node))
            }
            output.add(NativeTextFlow.prepare(layout, x, y.roundToInt(), interactive,
                prepareRubyDrawing = !measuringIntrinsic))
            // CSS line-height controls flow advance; glyph overflow does not enlarge the box.
            y += layout.height
            if (!hasShiftedText && inlineImages.isEmpty() && (!rubyLineHeight || rubyExtra == 0) && gridHeight > 0f) {
                val exactHeight = gridHeight * layout.lineCount
                y += exactHeight - exactHeight.roundToInt()
            }
            text = SpannableStringBuilder()
            runStyle = null; runStart = 0; minimumHeightPx = 0f; runLink = null; linkStart = 0
            hasShiftedText = false
            decorations.clear()
            inlineImages.clear()
            spacing.reset()
        }
        fun finish(): Float {
            flush()
            // CSS 2.1 8.3.1: the last child's bottom margin collapses with its parent's, and the
            // chain collapses out of the document. The reference capture is cropped to the body
            // box, so a trailing margin is outside it. Adding the margin at every nested flow
            // multiplied a three-div gloss by three: 三省堂国語辞典 case 27 was 32 device px
            // taller than the reference with identical line positions.
            return y
        }
    }

    private data class InlineDecoration(val start: Int, val end: Int, val box: BoxStyle, val ascent: Float, val descent: Float)
    private data class InlineImage(val start: Int, val width: Int, val height: Int, val shift: Int,
        val node: StructuredNode.Element)

    /** True when the element's own declarations ask for a vertical writing mode. */
    private fun isVerticalWriting(own: Map<String, String>): Boolean =
        own["writingMode"]?.trim()?.lowercase()?.startsWith("vertical") == true
    companion object {
        private val IMAGE_CONTAINER_DATA = mapOf("class" to "gloss-image-container")
        private const val SPAN_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        private val HIDDEN = setOf("hidden", "collapse")
        private val INLINE_TEXT_ALIGNMENTS = setOf("baseline", "text-bottom", "text-top", "middle", "super", "sub")
    }
}

private class NativeSpacingSpan(private val width: Int) : android.text.style.ReplacementSpan() {
    override fun getSize(paint: android.graphics.Paint, text: CharSequence, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?) = width
    override fun draw(canvas: android.graphics.Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) = Unit
}
