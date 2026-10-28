package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.Rect
import android.icu.text.BreakIterator
import android.os.Build
import android.text.Layout
import android.text.Spanned
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
import androidx.compose.ui.unit.sp
import chimahon.diagnostics.LookupProfiler
import java.util.Locale
import java.util.IdentityHashMap
import java.text.Bidi
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Width used when measuring a table cell's natural size: wide enough that nothing wraps. */
private const val MEASURE_WIDTH = 100000

/** Lowers source into positioned native flows; only tables negotiate intrinsic widths. */
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
    private val inlineState = NativeInlineState()
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
    private var inspectedChild: StructuredNode.Element? = null
    private var inspectedDeclarations: NativeCssDeclarations? = null
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
    private var measuringIntrinsic = false
    private var activeTableCell: TableCellInput? = null
    private val paragraphLayout = NativeParagraphLayout(resources)
    private val rubyBase = RubyTextParts(TextStyle.Default)
    private val rubyReading = RubyTextParts(TextStyle.Default)
    private val tableLineBreaks by lazy { BreakIterator.getLineInstance(Locale.JAPANESE) }
    private fun px(value: Float) = (value * density.density).roundToInt()
    private fun verticalPx(value: Float) = value * density.density
    private fun cellStroke(box: BoxStyle, own: NativeCssDeclarations, collapsed: Boolean): Int {
        if (!box.hasBorder && !defaultCellBorder(own)) return 0
        val width = (box.borderWidth ?: CELL_BORDER_PX) * density.density
        return if (width <= 0f) 0 else if (collapsed) floor(width).toInt().coerceAtLeast(1) else width.roundToInt()
    }
    private fun argb(value: String?, default: Color = Color.Transparent) = value?.let { parseCssColor2(it) }?.toArgb() ?: default.toArgb()

    /** The first in-flow child that generates a block box; whitespace-only text does not count. */
    private inline fun inspectFirstBlockChild(node: StructuredNode.Element, scope: NativeCssContext,
        consume: (StructuredNode.Element, NativeCssDeclarations) -> Unit) {
        var earlierElement = false
        for (child in node.children) {
            // Anything that generates content before the child separates the two margins, so the
            // search has to STOP at it instead of looking further: case 13's <li>text<ul/></li>
            // collapsed the list's margin to the TOP of the li and pushed the text 4 CSS down.
            if (child is StructuredNode.Text) {
                if (!child.text.isBlank()) return
                continue
            }
            if (child !is StructuredNode.Element) return
            if (child.tag == StructuredTag.Rt || child.tag == StructuredTag.Rp) { earlierElement = true; continue }
            val own = declarations(child, scope, cacheCell = false)
            if (hidden(own)) { earlierElement = true; continue }
            // The existing margin probe has no sibling context. Reuse its declarations only
            // when that is also the context the normal child visit will use.
            if (!earlierElement || css.siblingRules.isEmpty()) {
                inspectedChild = child
                inspectedDeclarations = own
            }
            if (!isInlineLevel(child) || own[NativeCssProperty.Display] == "block") consume(child, own)
            return
        }
    }

    /**
     * The top margin this element contributes, in the same unit as the block defaults. Mirrors the
     * per-tag defaults that [block] applies, so a parent can fold its first child's margin into
     * its own collapsed margin.
     */
    private fun marginTopOf(node: StructuredNode.Element, style: NativeTextStyle, own: NativeCssDeclarations): Float {
        val default = when (node.tag) {
            StructuredTag.Table -> TABLE_MARGIN_PX
            StructuredTag.OrderedList, StructuredTag.UnorderedList -> LIST_MARGIN_EM * fontSizeSp(style)
            StructuredTag.Details -> 0f
            StructuredTag.ListItem -> if (listDepth > 0) LIST_ITEM_MARGIN_EM * fontSizeSp(style) else 0f
            else -> if (!isInlineLevel(node) || own[NativeCssProperty.Display] == "block") {
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
        val scope = NativeCssContext(css, resources.cascade)
        for (ancestor in rootAncestors) scope.push(ancestor.values, ancestor.tag)
        val height = LookupProfiler.section(profilingId, "native.document.lower_layout") {
            flow(nodes, resources.nativeStyle(style), scope, 0, width.coerceAtLeast(1), 0f, true, items)
        }
        return NativeGlossaryDocument(nodes, css, style, rootAncestors, density, direction, resolver, secondary, border,
            width, ceil(height).toInt(), items.toList(), resources, profilingId)
    }

    private fun declarations(node: StructuredNode.Element, scope: NativeCssContext, preceding: NativeCssSiblings? = null,
        cacheCell: Boolean = true): NativeCssDeclarations {
        if (node === inspectedChild) {
            inspectedChild = null
            return checkNotNull(inspectedDeclarations).also { inspectedDeclarations = null }
        }
        resources.elements++
        resources.cssMatches++
        // A source element has the same selector context in both width passes of its cell.
        // Margin probes are excluded: they intentionally have no preceding-sibling context.
        val cell = if (cacheCell) activeTableCell else null
        cell?.declarations?.get(node)?.let { return it }
        val started = if (resources.stageTimings) System.nanoTime() else 0L
        val target = cssNode(node)
        val merged = if (preceding != null) scope.declarations(target, preceding) else scope.declarations(target)
        if (started != 0L) resources.declarationsNanos += System.nanoTime() - started
        cell?.declarations?.put(node, merged)
        return merged
    }

    private fun hidden(declarations: NativeCssDeclarations) =
        declarations[NativeCssProperty.Display] == "none" || declarations[NativeCssProperty.Visibility] in HIDDEN

    private fun isOpen(node: StructuredNode.Element): Boolean =
        (node.attributes.properties["open"] == "true") xor (node in expanded)

    private fun cssNode(node: StructuredNode.Element): StructuredNode.Element {
        if (node.tag != StructuredTag.Details) return node
        val open = isOpen(node)
        if (open == (node.attributes.properties["open"] == "true")) return node
        val properties = if (open) node.attributes.properties + ("open" to "true") else node.attributes.properties - "open"
        return node.copy(attributes = node.attributes.copy(properties = properties))
    }

    private fun semantic(style: NativeTextStyle, tag: StructuredTag): NativeTextStyle = when (tag) {
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

    private fun flow(nodes: List<StructuredNode>, style: NativeTextStyle, scope: NativeCssContext, x: Int, width: Int,
        y: Float, interactive: Boolean, output: MutableList<NativeGlossaryItem>, vertical: Boolean = false,
        rubyLineHeight: Boolean = false, textIndent: Int = 0, marginOwner: TextCursor? = null,
        before: NativeCssDeclarations? = null, pendingBottom: Float = 0f): Float {
        val cursor = TextCursor(x, width.coerceAtLeast(1), y, style, interactive, output, vertical, rubyLineHeight,
            textIndent)
        cursor.pendingBottom = pendingBottom
        val cell = activeTableCell
        val paragraphs = cell?.paragraphs?.get(nodes)
        val prepared = if (!measuringIntrinsic && marginCollapseTarget == null) paragraphs?.firstOrNull {
            it.style === style && it.vertical == vertical && it.indent == textIndent &&
                it.before === before && it.whiteSpace == inlineState.whiteSpace && it.trim == inlineState.trim
        } else null
        val rubyStart = cell?.rubyIndex ?: 0
        if (prepared != null) {
            cursor.restore(prepared)
            cell!!.rubyIndex += prepared.rubyCount
            // A parent's margin inspection may have resolved this inline subtree's first child.
            inspectedChild = null
            inspectedDeclarations = null
        } else {
            before?.let { cursor.before(it, style) }
            visitChildren(nodes, style, scope, cursor)
            if (measuringIntrinsic && cell != null && cursor.canRetainParagraph) {
                cursor.retainParagraph = { paragraph ->
                    cell.paragraphs.getOrPut(nodes) { ArrayList() }.add(
                        TableParagraphInput(paragraph, style, vertical, textIndent, before,
                            inlineState.whiteSpace, inlineState.trim, cell.rubyIndex - rubyStart))
                }
            }
        }
        val bottom = cursor.finish()
        if (marginOwner != null) marginOwner.pendingBottom = maxOf(marginOwner.pendingBottom, cursor.pendingBottom)
        return bottom
    }

    private fun visitChildren(nodes: List<StructuredNode>, inherited: NativeTextStyle, scope: NativeCssContext, cursor: TextCursor) {
        val trackSiblings = css.siblingRules.isNotEmpty()
        var previous: StructuredAttributes? = null
        var preceding: NativeCssSiblings? = null
        for (node in nodes) {
            if (trackSiblings && node is StructuredNode.Element) previous?.let { attributes ->
                val history = preceding ?: NativeCssSiblings(css.nativeSiblingSelectors).also { preceding = it }
                history.append(attributes)
            }
            visit(node, inherited, scope, cursor, preceding)
            if (trackSiblings && node is StructuredNode.Element) previous = node.attributes
        }
    }

    private fun visit(node: StructuredNode, inherited: NativeTextStyle, scope: NativeCssContext, cursor: TextCursor,
        preceding: NativeCssSiblings?) {
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
                val style = inlineAlignment(inherited, resolved, own[NativeCssProperty.VerticalAlign], node.tag)
                scope.push(cssNode(node))
                val isList = node.tag == StructuredTag.UnorderedList || node.tag == StructuredTag.OrderedList
                if (isList) listDepth++
                val previousWhiteSpace = inlineState.whiteSpace
                inlineState.whiteSpace = own[NativeCssProperty.WhiteSpace] ?: previousWhiteSpace
                val previousTrim = inlineState.trim
                inlineState.trim = own[NativeCssProperty.TextSpacingTrim] ?: previousTrim
                try {
                    when (node.tag) {
                        StructuredTag.Ruby -> ruby(node, style, scope, cursor)
                        StructuredTag.Break -> cursor.appendBreak(style)
                        StructuredTag.Table -> block(cursor, style, own, resources.box(own, style), TABLE_MARGIN_PX, skipMarginTop = skipMarginTop) { x, width, y ->
                            table(node, style, scope, x, width, y, cursor.output)
                        }
                        StructuredTag.OrderedList, StructuredTag.UnorderedList -> block(cursor, style, own, resources.box(own, style), LIST_MARGIN_EM * fontSizeSp(style), padStart = false, skipMarginTop = skipMarginTop) { x, width, y ->
                            list(node, style, own, scope, x, width, y, cursor.interactive, cursor)
                        }
                        StructuredTag.Details -> block(cursor, style, own, resources.box(own, style), 0f, skipMarginTop = skipMarginTop) { x, width, y ->
                            details(node, style, scope, x, width, y, cursor.interactive, cursor.output)
                        }
                        StructuredTag.Image -> {
                            // HTML's img is an inline replaced element by default. A dictionary
                            // that wants a figure sets display:block; treating every image as a
                            // block put each 1em sense icon on its own line and pushed the text
                            // below it (国語辞典オンライン's 大舞台, +246 device px).
                            if (own[NativeCssProperty.Display]?.trim()?.lowercase() == "block") {
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
                        else -> if (!isInlineLevel(node) || own[NativeCssProperty.Display] == "block") {
                            cursor.preventRetention()
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
                            if (!box.separatesVerticalMargins && (box.paddingTop ?: 0f) <= 0f) {
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
                            if (node.children.isEmpty() && own[NativeCssProperty.BeforeContent].isNullOrEmpty() && !box.separatesVerticalMargins &&
                                (box.paddingTop ?: 0f) <= 0f && (box.paddingBottom ?: 0f) <= 0f) {
                                val top = if (skipMarginTop || documentStart) 0f else verticalPx(collapsed)
                                cursor.pendingBottom = maxOf(cursor.pendingBottom, top,
                                    verticalPx(box.marginBottom ?: collapsed))
                                inheritedTextIndent = previousIndent
                                return
                            }
                            try {
                                block(cursor, style, own, box, collapsed, skipMarginTop = skipMarginTop, resolvedMargin = collapsed) { x, width, y ->
                                    flow(node.children, style, scope, x, width, y, cursor.interactive, cursor.output,
                                        vertical = isVerticalWriting(own), rubyLineHeight = true, textIndent = indent,
                                        marginOwner = cursor, before = own.before)
                                }
                            } finally {
                                marginCollapseTarget = null
                                inheritedTextIndent = previousIndent
                            }
                        } else {
                            val box = resources.box(own, style)
                            cursor.space(px((box.marginStart ?: 0f) + (box.paddingStart ?: 0f)), style)
                            val start = cursor.length
                            val previousLink = inlineState.link
                            val previousAlign = imageAlign
                            if (node.tag == StructuredTag.Link && cursor.interactive) {
                                inlineState.link = node.attributes.properties["href"]?.let { extractQuery(it) ?: node.collect() }
                            }
                            if (own[NativeCssProperty.VerticalAlign] != null) imageAlign = own[NativeCssProperty.VerticalAlign]
                            try {
                                own.before?.let { cursor.before(it, style) }
                                visitChildren(node.children, style, scope, cursor)
                            } finally {
                                inlineState.link = previousLink
                                imageAlign = previousAlign
                            }
                            cursor.decorate(start, box, style)
                            cursor.space(px((box.paddingEnd ?: 0f) + (box.marginEnd ?: 0f)), style)
                        }
                    }
                } finally {
                    inlineState.whiteSpace = previousWhiteSpace
                    inlineState.trim = previousTrim
                    if (isList) listDepth--
                    scope.pop()
                }
            }
        }
    }

    private inline fun block(cursor: TextCursor, style: NativeTextStyle, own: NativeCssDeclarations, box: BoxStyle, defaultMargin: Float, padStart: Boolean = true,
        skipMarginTop: Boolean = false, resolvedMargin: Float? = null, content: (Int, Int, Float) -> Float) {
        cursor.flush()
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
        val circle = !measuringIntrinsic && own[NativeCssProperty.ClipPath]?.trim() == "circle()"
        val background = if (measuringIntrinsic) 0 else argb(box.backgroundColor)
        val stroke = if (!measuringIntrinsic && box.hasBorder && !box.leftAccent) px(box.borderWidth ?: 1f).toFloat() else 0f
        val borderColor = if (stroke > 0f) argb(box.borderColor, border) else 0
        val gradient = if (measuringIntrinsic) null else
            (own[NativeCssProperty.BackgroundImage] ?: own[NativeCssProperty.Background])?.trim()?.takeIf { it.startsWith("radial-gradient(") }
        val painted = background ushr 24 != 0 || stroke > 0f && borderColor ushr 24 != 0 || gradient != null
        val paintTop = top.roundToInt()
        val clipSlot = if (circle) cursor.output.size.also { cursor.output.add(NativeGlossaryRect(x, paintTop, width, 0)) } else -1
        val paintSlot = if (painted) cursor.output.size.also { cursor.output.add(NativeGlossaryRect(x, paintTop, width, 0)) } else -1
        val accentWidth = if (box.hasBorder && box.leftAccent) px(box.borderWidth ?: 3f) else 0
        val start = if (padStart) px(box.paddingStart ?: 0f) else 0
        val end = px(box.paddingEnd ?: 0f)
        cursor.y = content(x + accentWidth + if (direction == LayoutDirection.Rtl) end else start,
            (width - start - end - accentWidth).coerceAtLeast(1), top + verticalPx(box.paddingTop ?: 0f))
        if (box.separatesVerticalMargins || (box.paddingBottom ?: 0f) > 0f) {
            cursor.y += cursor.pendingBottom
            cursor.pendingBottom = 0f
        }
        cursor.y += verticalPx(box.paddingBottom ?: 0f)
        val height = (cursor.y.roundToInt() - paintTop).coerceAtLeast(0)
        if (painted) cursor.output[paintSlot] = NativeGlossaryRect(x, paintTop, width, height,
            background, borderColor, stroke, px(box.borderRadius ?: 0f).toFloat(), radialBackground(gradient, width, height))
        if (!measuringIntrinsic && box.leftAccent) cursor.output.add(NativeGlossaryRect(x, paintTop, px(box.borderWidth ?: 3f), height, argb(box.borderColor, border)))
        if (circle) {
            cursor.output[clipSlot] = NativeCircleClip(x, paintTop, width, height, true)
            cursor.output.add(NativeCircleClip(x, paintTop, width, height, false))
        }
        cursor.pendingBottom = maxOf(cursor.pendingBottom, verticalPx(box.marginBottom ?: defaultMargin))
    }

    private fun list(node: StructuredNode.Element, style: NativeTextStyle, own: NativeCssDeclarations, scope: NativeCssContext,
        x: Int, width: Int, y: Float, interactive: Boolean, owner: TextCursor): Float {
        var bottom = y
        var pendingBottom = 0f
        val output = owner.output
        val box = resources.box(own, style)
        val gutter = px(box.paddingStart ?: LIST_PADDING_START_EM * fontSizeSp(style)).coerceAtLeast(0)
        val trackSiblings = css.siblingRules.isNotEmpty()
        var previous: NativeCssSiblings? = null
        var previousItem: StructuredAttributes? = null
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
            if (trackSiblings) previousItem?.let { attributes ->
                val history = previous ?: NativeCssSiblings(css.nativeSiblingSelectors).also { previous = it }
                history.append(attributes)
            }
            val itemCss = declarations(child, scope, previous)
            if (trackSiblings) previousItem = child.attributes
            val itemStyle = resources.style(style, itemCss)
            if (hidden(itemCss)) { number++; continue }
            val itemBox = resources.box(itemCss, itemStyle)
            bottom += pendingBottom
            pendingBottom = 0f
            val type = itemCss[NativeCssProperty.ListStyleType] ?: own[NativeCssProperty.ListStyleType]
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
            val inside = (itemCss[NativeCssProperty.ListStylePosition] ?: own[NativeCssProperty.ListStylePosition]) == "inside"
            val outsideMarker = if (!measuringIntrinsic && marker.isNotEmpty() && !inside) resources.marker(marker, itemStyle) else null
            val markerSlot = output.size
            if (outsideMarker != null) output.add(NativeGlossaryRect(0, 0, 0, 0))
            // Outside markers use the item's border edge; padding insets only its content.
            val padStart = px(itemBox.paddingStart ?: 0f)
            val padEnd = px(itemBox.paddingEnd ?: 0f)
            val itemX = if (direction == LayoutDirection.Rtl) x + padEnd else x + gutter + padStart
            val start = bottom
            val firstItem = output.size
            val item = TextCursor(itemX, (width - gutter - padStart - padEnd).coerceAtLeast(1), bottom, itemStyle, interactive, output)
            scope.push(child)
            try {
                if (inside && marker.isNotEmpty()) item.noLookup(marker, itemStyle)
                visitChildren(child.children, itemStyle, scope, item)
                bottom = item.finish()
            } finally {
                scope.pop()
            }
            if (outsideMarker != null) {
                // Align to the first formatted content line, including one in a descendant block.
                var baseline = start - outsideMarker.ascent
                for (index in firstItem until output.size) {
                    val flow = output[index] as? NativeTextFlow ?: continue
                    baseline = flow.y + flow.layout.getLineBaseline(0).toFloat()
                    break
                }
                val markerX = if (direction == LayoutDirection.Rtl) x + width - gutter else x + gutter - outsideMarker.width
                output[markerSlot] = NativeListMarker(markerX, baseline, outsideMarker)
            }
            if (bottom == start && marker.isNotEmpty()) bottom += resources.profile(itemStyle).resource.lineHeightPx.coerceAtLeast(1f)
            pendingBottom = maxOf(item.pendingBottom,
                verticalPx(itemBox.marginBottom ?: LIST_ITEM_MARGIN_EM * fontSizeSp(itemStyle)))
            number++
        }
        owner.pendingBottom = maxOf(owner.pendingBottom, pendingBottom)
        return bottom
    }

    private fun details(node: StructuredNode.Element, style: NativeTextStyle, scope: NativeCssContext, x: Int, width: Int,
        y: Float, interactive: Boolean, output: MutableList<NativeGlossaryItem>): Float {
        val open = isOpen(node)
        val summary = node.children.firstOrNull { it is StructuredNode.Element && it.tag == StructuredTag.Summary }
        val own = if (summary is StructuredNode.Element) declarations(summary, scope) else NativeCssDeclarations.EMPTY
        val headerStyle = resources.style(style, own)
        val header = TextCursor(x, width, y, headerStyle, false, output)
        if (!hidden(own)) {
            block(header, headerStyle, own, resources.box(own, headerStyle), 0f) { sx, sw, sy ->
                val content = TextCursor(sx, sw, sy, headerStyle, false, output)
                if (own[NativeCssProperty.ListStyleType] != "none" && own[NativeCssProperty.Display] !in setOf("block", "inline", "inline-block")) {
                    content.disclosureMarker(open, headerStyle)
                }
                own.before?.let { content.before(it, headerStyle) }
                if (summary is StructuredNode.Element) {
                    scope.push(summary)
                    try { visitChildren(summary.children, headerStyle, scope, content) } finally { scope.pop() }
                } else content.append("Details", headerStyle)
                content.finish()
            }
        }
        var bottom = header.finish()
        if (interactive) output.add(NativeGlossaryDisclosure(x, y.roundToInt(), width, (bottom.roundToInt() - y.roundToInt()).coerceAtLeast(1), node))
        if (open) bottom = flow(node.children.filterNot { it === summary }, style, scope, x,
            width, bottom, interactive, output, pendingBottom = header.pendingBottom)
        return bottom
    }

    private fun imageBox(node: StructuredNode.Element, style: NativeTextStyle, scope: NativeCssContext,
        maxWidth: Int): Pair<Int, Int> {
        val data = IMAGE_CONTAINER_DATA
        val container = mergeCssStyles(getCssStyles(data, css, "span"), scope.nestedStyles("span", data))
        val (width, height) = glossaryImageSize(node.attributes.data + node.attributes.properties, container,
            fontSizeSp(style), css.variables["font-size-no-units"]?.toFloatOrNull()?.takeIf { it > 0f } ?: 14f,
            maxWidth / density.density)
        return px(width).coerceAtLeast(1) to ceil(height * density.density).toInt().coerceAtLeast(1)
    }

    private fun image(node: StructuredNode.Element, style: NativeTextStyle, scope: NativeCssContext, cursor: TextCursor) {
        val (width, height) = imageBox(node, style, scope, cursor.width)
        cursor.output.add(NativeGlossaryImage(cursor.x, cursor.y.roundToInt(), width, height, node))
        cursor.y += height
    }

    private fun inlineImage(node: StructuredNode.Element, style: NativeTextStyle, own: NativeCssDeclarations,
        scope: NativeCssContext, cursor: TextCursor) {
        val (width, height) = imageBox(node, style, scope, cursor.width)
        val shift = imageShift(style, own[NativeCssProperty.VerticalAlign] ?: imageAlign, height)
        cursor.inlineImage(node, style, width, height, shift)
    }

    /** CSS `text-indent` in device px; null when the declaration is absent so inheritance applies. */
    private fun textIndentOf(own: NativeCssDeclarations, style: NativeTextStyle): Int? {
        val raw = own[NativeCssProperty.TextIndent]?.trim()?.lowercase() ?: return null
        if (raw == "0" || raw == "0px" || raw == "initial" || raw == "unset") return 0
        val dp = parseDpValue(raw, fontSizeSp(style)) ?: return null
        return px(dp)
    }

    /**
     * CSS `vertical-align` distance from the baseline to the image's bottom margin edge, positive
     * downward. Blink aligns replaced elements the same way for the keyword values; the line box
     * grows only when the shifted box leaves the font's ascent/descent.
     */
    private fun imageShift(style: NativeTextStyle, align: String?, height: Int): Int {
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

    private fun inlineAlignment(parent: NativeTextStyle, style: NativeTextStyle, declared: String?, tag: StructuredTag): NativeTextStyle {
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
        val metrics = font.font.metricsInt
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

    private class RubyInput(val source: StructuredNode.Element, val style: TextStyle, val parts: List<RubyPart>)
    private class RubyPart(val text: String, val style: TextStyle, val metrics: RubyMetrics? = null, val extraHeight: Int = 0)

    private fun ruby(node: StructuredNode.Element, style: NativeTextStyle, scope: NativeCssContext, cursor: TextCursor) {
        val started = if (resources.stageTimings) System.nanoTime() else 0L
        val cell = activeTableCell
        if (cell == null) {
            lowerRuby(node, style.textStyle, scope) { text, runStyle, metrics, height -> appendRuby(cursor, text, runStyle, metrics, height) }
        } else {
            val input = cell.ruby(node, style.textStyle) {
                val parts = ArrayList<RubyPart>()
                lowerRuby(node, style.textStyle, scope) { text, runStyle, metrics, height -> parts.add(RubyPart(text, runStyle, metrics, height)) }
                RubyInput(node, style.textStyle, parts)
            }
            for (part in input.parts) appendRuby(cursor, part.text, part.style, part.metrics, part.extraHeight)
        }
        if (resources.stageTimings) resources.rubyLowerNanos += System.nanoTime() - started
    }

    private fun appendRuby(cursor: TextCursor, text: String, style: TextStyle, metrics: RubyMetrics?, height: Int) {
        if (metrics == null) cursor.noLookup(text, resources.nativeStyle(style)) else {
            resources.rubyPairs++
            cursor.rubyHeight(height)
            cursor.ruby(text, resources.nativeStyle(style), metrics)
        }
    }

    private inline fun lowerRuby(node: StructuredNode.Element, style: TextStyle, scope: NativeCssContext,
        emit: (String, TextStyle, RubyMetrics?, Int) -> Unit) {
        val base = rubyBase.also { it.reset(style) }
        for (child in node.children) {
            checkCancellation()
            if (child is StructuredNode.Element && child.tag == StructuredTag.Rp) continue
            if (child is StructuredNode.Element && child.tag == StructuredTag.Rt) {
                val own = declarations(child, scope)
                val readingStyle = resources.readingStyle(style, own)
                val reading = rubyReading.also { it.reset(readingStyle) }
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
                        val metrics = resources.ruby.column(baseText, reading.text.toString(), style, readingStyle, base.runs(), reading.runs())
                        emit(baseText, style, metrics, (metrics.readingLayout.fontSizePx - resources.cssHalfLeading(style)).roundToInt())
                    } else unannotatedRuby(base, emit)
                }
                base.clear()
            } else rubyText(child, style, scope, base, false)
        }
        unannotatedRuby(base, emit)
    }

    private inline fun unannotatedRuby(parts: RubyTextParts, emit: (String, TextStyle, RubyMetrics?, Int) -> Unit) {
        val runs = parts.runs()
        if (runs.isEmpty()) {
            if (parts.text.isNotEmpty()) emit(parts.text.toString(), parts.style, null, 0)
        } else {
            val text = parts.text.toString()
            for (run in runs) emit(text.substring(run.start, run.end), run.style, null, 0)
        }
    }

    private fun rubyText(node: StructuredNode, style: TextStyle, scope: NativeCssContext, parts: RubyTextParts, annotation: Boolean) {
        checkCancellation()
        when (node) {
            is StructuredNode.Text -> parts.append(node.text, style)
            StructuredNode.TextBreak -> parts.append(" ", style)
            is StructuredNode.Element -> {
                if (node.tag == StructuredTag.Rp || node.tag == StructuredTag.Rt) return
                val own = declarations(node, scope)
                if (hidden(own)) return
                val resolved = resources.style(semantic(resources.nativeStyle(style), node.tag).textStyle, own)
                val childStyle = if (annotation && resolved.textDecoration != null) resolved.copy(textDecoration = null) else resolved
                scope.push(node)
                try { for (child in node.children) rubyText(child, childStyle, scope, parts, annotation) } finally { scope.pop() }
            }
        }
    }

    private data class Cell(val position: GlossaryCellPosition, val items: List<NativeGlossaryItem>, val height: Float,
        val box: BoxStyle, val verticalMiddle: Boolean, val stroke: Float)
    private class TableIntrinsics(val minimum: IntArray, var border: Int = 0)
    private class TableCellInput(val cell: StructuredTableCell, val position: GlossaryCellPosition,
        val row: CssAncestor, val own: NativeCssDeclarations, val style: NativeTextStyle, val box: BoxStyle, val stroke: Int) {
        val declarations = IdentityHashMap<StructuredNode.Element, NativeCssDeclarations>()
        val paragraphs = IdentityHashMap<List<StructuredNode>, ArrayList<TableParagraphInput>>()
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
        val previousTrim = inlineState.trim
        val previousIntrinsic = measuringIntrinsic
        activeTableCell = input
        input.rubyIndex = 0
        inlineState.trim = input.own[NativeCssProperty.TextSpacingTrim] ?: previousTrim
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
            inlineState.trim = previousTrim
            activeTableCell = previousCell
        }
    }

    private fun table(node: StructuredNode.Element, style: NativeTextStyle, scope: NativeCssContext, x: Int, width: Int,
        y: Float, output: MutableList<NativeGlossaryItem>): Float = LookupProfiler.section(profilingId, "native.table.layout") {
        val started = if (resources.stageTimings) System.nanoTime() else 0L
        resources.tables++
        val rows = ArrayList<List<StructuredTableCell>>()
        val run = ArrayList<StructuredTableCell>()
        val tableScope = scope.ancestors()
        // Legacy table scopes contain attribute/tag/position facts, not source node handles.
        // Construct their native selector state once, then share it across every cell.
        val tableCss = NativeCssContext(css, resources.cascade)
        for (frame in tableScope) tableCss.push(frame.values, frame.tag, frame.siblingIndex, frame.isLastSibling)
        val tableOwn = declarations(node, scope, cacheCell = false)
        val fractions = gridTrackFractions(tableOwn)
        var splicedRows = false
        for (child in node.children) {
            if (child is StructuredNode.Element && collectTableRows(child, rows, run,
                    gridItems = fractions != null) {
                    tableCss.tableRowDeclarations(it)[NativeCssProperty.Display]?.trim()?.lowercase() == "contents"
                }) splicedRows = true
        }
        if (run.isNotEmpty()) rows.add(run.toList())
        if (rows.isEmpty()) {
            if (resources.stageTimings) resources.tableNanos += System.nanoTime() - started
            return@section y
        }
        fun StructuredTableCell.span(key: String) = element?.attributes?.properties?.get(key)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val placed = GlossaryTableGrid.place(rows.map { row -> row.map { GlossaryCellSpan(it.span("colSpan"), it.span("rowSpan")) } })
        val grid = if (fractions != null && placed.columns < fractions.size) placed.copy(columns = fractions.size) else placed
        // Grid cells retain their source row/column positions when tr is display: contents.
        val automatic = fractions == null && !splicedRows
        val collapsed = automatic && tableOwn[NativeCssProperty.BorderCollapse] != "separate"
        val inputs = ArrayList<TableCellInput>(grid.cells.size)
        val inheritance = IdentityHashMap<Map<String, String>, NativeCssRuleBlock>()
        for ((rowIndex, row) in rows.withIndex()) {
            val frame = CssAncestor(dataValueKeys(row.firstOrNull()?.rowData ?: emptyMap()), "tr",
                rowIndex + 1, rowIndex == rows.lastIndex)
            tableCss.push(frame.values, frame.tag, frame.siblingIndex, frame.isLastSibling)
            try { for (cell in row) {
                val inherited = inheritance.getOrPut(cell.rowData) { tableCss.tableRowInheritance(cell.rowData) }
                val own = tableCss.tableCellDeclarations(cell, inherited)
                val resolved = resources.style(style, own)
                val box = resources.box(own, resolved)
                inputs.add(TableCellInput(cell, grid.cells[inputs.size], frame, own,
                    resources.nativeStyle(tableCellStyle(resolved.textStyle, own, cell.isHeader, box)), box, cellStroke(box, own, collapsed)))
            } } finally { tableCss.pop() }
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
        val bottom = y + tops.last() + outerBorder
        if (resources.stageTimings) resources.tableNanos += System.nanoTime() - started
        bottom
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
            val minimumOnly = minContent && box.wordBreak != "keep-all"
            val natural = cellContent(input, scope, intrinsic = true) {
                flow(cell.nodes, cellStyle, scope, 0, MEASURE_WIDTH, 0f, false, scratch)
                var widest = 0
                var minimum = 0
                for (item in scratch) {
                    if (item !is NativeTextFlow) continue
                    val layout = item.layout
                    if (layout.lineCount == 0) continue
                    val minimumAdvance = if (minimumOnly || intrinsics != null && box.wordBreak != "keep-all")
                        minContentAdvance(layout) else 0
                    if (minimumOnly) {
                        widest = maxOf(widest, minimumAdvance)
                    } else {
                        // Centred lines can start at a negative left. Round each advance up:
                        // truncating fractional font metrics makes a max-content cell wrap.
                        for (line in 0 until layout.lineCount) {
                            val advance = ceil(layout.getLineRight(line) - layout.getLineLeft(line)).toInt()
                            widest = maxOf(widest, item.x + advance)
                        }
                    }
                    if (intrinsics != null) {
                        minimum = maxOf(minimum, if (box.wordBreak == "keep-all" || (own[NativeCssProperty.WhiteSpace] ?: inlineState.whiteSpace) == "nowrap") widest
                            else minimumAdvance)
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

    /** Reads caret advances from the existing layout; never creates a narrow-width StaticLayout. */
    private fun minContentAdvance(layout: Layout): Int {
        val text = layout.text
        val value = text.toString()
        val breaks = tableLineBreaks
        breaks.setText(value)
        // Reading each boundary through getPrimaryHorizontal remeasures the line prefix.
        // Android 34 can measure the whole simple LTR line once. Multiline/bidi flows retain
        // Android's caret choice, which is ambiguous at directional and paragraph boundaries.
        val bounds = if (Build.VERSION.SDK_INT >= 34 && text.isNotEmpty() && layout.lineCount == 1 &&
            layout.getParagraphDirection(0) == Layout.DIR_LEFT_TO_RIGHT &&
            !Bidi.requiresBidi(value.toCharArray(), 0, value.length)) {
            FloatArray(text.length * 4).also { layout.fillCharacterBounds(0, text.length, it, 0) }
        } else null
        fun horizontal(offset: Int): Float = if (bounds == null) layout.getPrimaryHorizontal(offset)
            else if (offset == text.length) bounds[(offset - 1) * 4 + 2] else bounds[offset * 4]
        val spanned = text as? Spanned
        val replacements = spanned?.getSpans(0, text.length, ReplacementSpan::class.java).orEmpty()
        val ranges = LongArray(replacements.size) {
            (spanned!!.getSpanStart(replacements[it]).toLong() shl 32) or spanned.getSpanEnd(replacements[it]).toLong()
        }.apply { sort() }
        var replacement = 0
        var start = breaks.first()
        var startX = horizontal(start)
        var widest = 0f
        var end = breaks.next()
        while (end != BreakIterator.DONE) {
            // Breaks and span starts are ordered; never rescan every ruby for each boundary.
            while (replacement < ranges.size && (ranges[replacement] and 0xffffffffL) <= end.toLong()) replacement++
            if (replacement == ranges.size || (ranges[replacement] ushr 32) >= end.toLong()) {
                var trimmed = end
                while (trimmed > start && text[trimmed - 1].isWhitespace()) trimmed--
                val endX = horizontal(trimmed)
                widest = maxOf(widest, kotlin.math.abs(endX - startX))
                start = end
                startX = if (trimmed == end) endX else horizontal(end)
            }
            end = breaks.next()
        }
        return ceil(widest).toInt()
    }

    private inner class TextCursor(val x: Int, val width: Int, var y: Float, val style: NativeTextStyle,
        val interactive: Boolean, val output: MutableList<NativeGlossaryItem>, val vertical: Boolean = false,
        val rubyLineHeight: Boolean = false, val textIndent: Int = 0) {
        private val builder = NativeParagraphBuilder(style, resources, inlineState, vertical, interactive, checkCancellation)
        private var rubyExtra = 0
        private var flushed = false
        private var readyParagraph: NativeParagraph? = null
        var pendingBottom = 0f
        val length: Int get() = builder.length
        val canRetainParagraph: Boolean get() = !flushed && !builder.hasImages && length > 0
        var retainParagraph: ((NativeParagraph) -> Unit)? = null

        fun preventRetention() { flushed = true }
        fun restore(input: TableParagraphInput) {
            readyParagraph = input.paragraph
            y += pendingBottom
            pendingBottom = 0f
            documentStart = false
        }

        private fun contentStarted(started: Boolean) {
            if (!started) return
            documentStart = false
            y += pendingBottom
            pendingBottom = 0f
        }

        fun append(value: String, style: NativeTextStyle) = contentStarted(builder.append(value, style))
        fun appendRaw(value: String, style: NativeTextStyle) = contentStarted(builder.appendRaw(value, style))
        fun appendBreak(style: NativeTextStyle) = contentStarted(builder.appendBreak(style))
        fun noLookup(value: String, style: NativeTextStyle) = contentStarted(builder.noLookup(value, style))

        fun before(own: NativeCssDeclarations, inherited: NativeTextStyle) {
            val value = own["content"] ?: return
            if (value.isEmpty() || hidden(own)) return
            val style = resources.style(inherited, own)
            val box = resources.box(own, style)
            val display = own[NativeCssProperty.Display]
            if (display == "block") flush()
            space(px(box.marginStart ?: 0f), style)
            if (display == "inline-block") {
                val prepared = resources.ruby.prepare(value, style.textStyle)
                val start = length
                noLookup(value, style)
                val padStart = px(box.paddingStart ?: 0f)
                val padEnd = px(box.paddingEnd ?: 0f)
                val contentWidth = own["width"]?.let { parseDpValue(it, fontSizeSp(style)) }?.let(::px)
                    ?: ceil(prepared.advancePx).toInt()
                val transform = own["transform"].orEmpty().trim()
                val shiftY = if (transform.startsWith("translateY(") && transform.endsWith(')')) {
                    parseDpValue(transform.substringAfter('(').dropLast(1), fontSizeSp(style))?.let(::verticalPx) ?: 0f
                } else 0f
                builder.addSpan(NativeGeneratedTextSpan(prepared, contentWidth + padStart + padEnd, padStart, shiftY), start)
            } else {
                space(px(box.paddingStart ?: 0f), style)
                val start = length
                noLookup(value, style)
                decorate(start, box, style)
                space(px(box.paddingEnd ?: 0f), style)
            }
            space(px(box.marginEnd ?: 0f), style)
            if (display == "block") flush()
        }

        fun disclosureMarker(open: Boolean, style: NativeTextStyle) {
            val start = length
            noLookup("\uFFFC", style)
            builder.addSpan(NativeDisclosureMarkerSpan(resources.profile(style).resource.paint.textSize,
                open, direction == LayoutDirection.Rtl), start)
        }

        fun ruby(base: String, style: NativeTextStyle, metrics: RubyMetrics) =
            contentStarted(builder.ruby(base, style, metrics))

        fun inlineImage(node: StructuredNode.Element, style: NativeTextStyle, width: Int, height: Int, shift: Int) =
            contentStarted(builder.image(node, style, width, height, shift))

        /** Flow's reading strut, applied only to lines containing annotations. */
        fun rubyHeight(pixels: Int) { rubyExtra = maxOf(rubyExtra, pixels) }

        fun space(width: Int, style: NativeTextStyle) {
            if (width <= 0) return
            val start = length
            appendRaw(" ", style)
            builder.addSpan(NativeSpacingSpan(width), start)
        }

        fun decorate(start: Int, box: BoxStyle, style: NativeTextStyle) {
            if (start !in 0 until length || !box.hasBorder && !box.hasBackground) return
            val font = resources.profile(style).resource
            builder.decorate(NativeInlineDecoration(start, length,
                font.paint.ascent() - font.baselineShiftPx, font.paint.descent() - font.baselineShiftPx,
                px(box.paddingStart ?: 0f), px(box.paddingEnd ?: 0f),
                px(box.paddingTop ?: 0f), px(box.paddingBottom ?: 0f),
                argb(box.backgroundColor), argb(box.borderColor, this.style.color.takeIf { it.isSpecified } ?: border),
                if (box.hasBorder) px(box.borderWidth ?: 1f).toFloat() else 0f, px(box.borderRadius ?: 2f).toFloat()))
        }

        fun flush() {
            flushed = true
            val paragraph = readyParagraph?.also { checkCancellation() } ?: builder.finish(textIndent, rubyExtra) ?: return
            retainParagraph?.invoke(paragraph)
            retainParagraph = null
            y = paragraphLayout.place(paragraph, x, width, y, interactive,
                intrinsic = measuringIntrinsic, tableCell = tableCellDepth > 0,
                rubyLineHeight = rubyLineHeight, output = output)
            readyParagraph = null
        }

        fun finish(): Float {
            flush()
            // A trailing collapsed margin is outside this flow's content box.
            return y
        }
    }

    /** Owned by one cell's intrinsic/final negotiation, never retained across documents. */
    private class TableParagraphInput(val paragraph: NativeParagraph, val style: NativeTextStyle,
        val vertical: Boolean, val indent: Int, val before: NativeCssDeclarations?,
        val whiteSpace: String, val trim: String, val rubyCount: Int)

    /** True when the element's own declarations ask for a vertical writing mode. */
    private fun isVerticalWriting(own: NativeCssDeclarations): Boolean =
        own[NativeCssProperty.WritingMode]?.trim()?.lowercase()?.startsWith("vertical") == true
    companion object {
        private val IMAGE_CONTAINER_DATA = mapOf("class" to "gloss-image-container")
        private val HIDDEN = setOf("hidden", "collapse")
        private val INLINE_TEXT_ALIGNMENTS = setOf("baseline", "text-bottom", "text-top", "middle", "super", "sub")
    }
}

private class NativeSpacingSpan(private val width: Int) : android.text.style.ReplacementSpan() {
    override fun getSize(paint: android.graphics.Paint, text: CharSequence, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?) = width
    override fun draw(canvas: android.graphics.Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) = Unit
}
