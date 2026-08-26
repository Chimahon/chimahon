package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import com.turtlekazu.furiganable.compose.m3.TextWithReading

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
) {
    val baseStyle = TextStyle(color = onBg, fontSize = fontSize.sp)
    Column(modifier = modifier) {
        nodes.forEach { node ->
            StructuredNodeView(
                node = node,
                parsedCss = parsedCss,
                style = baseStyle,
                dictName = dictName,
                mediaDataUris = mediaDataUris,
                secondary = secondary,
                border = border,
                onRecursiveLookup = onRecursiveLookup,
            )
        }
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
) {
    when (node) {
        is StructuredNode.Text -> spanText(node.text, style, onRecursiveLookup)
        StructuredNode.TextBreak -> Spacer(Modifier.height(2.dp))
        is StructuredNode.Element -> StructuredElementView(
            node, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup,
        )
    }
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
) {
    when (node.tag) {
        StructuredTag.Link -> StructuredLink(node, style, onRecursiveLookup)
        StructuredTag.Ruby -> StructuredRuby(node, style)
        StructuredTag.Image -> StructuredImage(node, style, dictName, mediaDataUris)
        StructuredTag.Table -> StructuredTable(
            node, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup,
        )
        StructuredTag.Details -> StructuredDetails(
            node, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup,
        )
        StructuredTag.Summary -> StructuredBox(
            node, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup,
        )
        StructuredTag.UnorderedList, StructuredTag.OrderedList, StructuredTag.ListItem -> StructuredList(
            node, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup,
        )
        StructuredTag.Div, StructuredTag.Span -> StructuredBox(
            node, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup,
        )
        StructuredTag.Break -> Spacer(Modifier.height(2.dp))
        StructuredTag.HorizontalRule -> androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
            color = border,
        )
        // Semantic inline tags: browser-default styling the WebView gets for free.
        StructuredTag.Bold -> semanticChildren(node, parsedCss, style.copy(fontWeight = FontWeight.Bold), dictName, mediaDataUris, secondary, border, onRecursiveLookup)
        StructuredTag.Italic -> semanticChildren(node, parsedCss, style.copy(fontStyle = FontStyle.Italic), dictName, mediaDataUris, secondary, border, onRecursiveLookup)
        StructuredTag.Underline -> semanticChildren(node, parsedCss, style.copy(textDecoration = TextDecoration.Underline), dictName, mediaDataUris, secondary, border, onRecursiveLookup)
        StructuredTag.Strike -> semanticChildren(node, parsedCss, style.copy(textDecoration = TextDecoration.LineThrough), dictName, mediaDataUris, secondary, border, onRecursiveLookup)
        StructuredTag.Superscript -> semanticChildren(node, parsedCss, style.copy(baselineShift = BaselineShift.Superscript, fontSize = style.fontSize * 0.75f), dictName, mediaDataUris, secondary, border, onRecursiveLookup)
        StructuredTag.Subscript -> semanticChildren(node, parsedCss, style.copy(baselineShift = BaselineShift.Subscript, fontSize = style.fontSize * 0.75f), dictName, mediaDataUris, secondary, border, onRecursiveLookup)
        StructuredTag.Small -> semanticChildren(node, parsedCss, style.copy(fontSize = style.fontSize * 0.8f), dictName, mediaDataUris, secondary, border, onRecursiveLookup)
        StructuredTag.Mark -> semanticChildren(
            node, parsedCss,
            style.copy(background = Color(0xFFFFF176)),
            dictName, mediaDataUris, secondary, border, onRecursiveLookup,
        )
        else -> node.children.forEach { child ->
            StructuredNodeView(child, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup)
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
) {
    node.children.forEach { child ->
        StructuredNodeView(child, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup)
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
) {
    if (node.attributes.data["content"] == "attribution") return

    val combined = remember(node.attributes.data, parsedCss, node.attributes.style) {
        getCssStyles(node.attributes.data, parsedCss) + node.attributes.style
    }
    val styled = remember(style, combined) { applyTypography(style, combined) }

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

    val baseFontSizeSp = style.fontSize.let { if (it.isSp) it.value else 16f }
    val box = remember(combined, baseFontSizeSp) { parseBoxStyle(combined, baseFontSizeSp) }

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

    if (!box.hasAnyStyle && isInline) {
        // Plain inline span/div → emit inline text only (fast path). Hidden subtrees are skipped.
        // Use effectiveStyled which carries verticalAlign / textAlign / opacity.
        if (box.textAlign != null) {
            // Block alignment needs width; emit as full-width Text rather than inline
            val text = node.collectVisible(parsedCss)
            if (text.isNotBlank()) {
                Text(
                    text = text,
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
                    spanText(node.collectVisible(parsedCss), effectiveStyled, onRecursiveLookup)
                }
            } else {
                spanText(node.collectVisible(parsedCss), effectiveStyled, onRecursiveLookup)
            }
        }
        return
    }

    val shape = RoundedCornerShape(box.borderRadius?.dp ?: 6.dp)
    val bgColor = box.backgroundColor?.let { parseCssColor2(it) }
        ?: if (box.hasBackground) secondary.copy(alpha = 0.08f) else Color.Transparent
    val borderColor = box.borderColor?.let { parseCssColor2(it) } ?: border

    // Opacity handling: wrap box content with alpha. For Text opacity, also apply via TextStyle color alpha
    // (handled in applyTypography + effectiveStyled). Box opacity uses Modifier.alpha.
    val outerAlphaModifier = if (box.opacity != null) Modifier.alpha(box.opacity) else Modifier
    val fillWidthModifier = if (box.textAlign != null) Modifier.fillMaxWidth() else Modifier

    val content = Column(
        modifier = Modifier
            .padding(
                start = box.paddingStart?.dp ?: 0.dp,
                end = box.paddingEnd?.dp ?: 0.dp,
                top = box.paddingTop?.dp ?: 0.dp,
                bottom = box.paddingBottom?.dp ?: if (box.hasBackground || box.hasBorder) 3.dp else 0.dp,
            ),
    ) {
        node.children.forEach { child ->
            StructuredNodeView(child, parsedCss, effectiveStyled, dictName, mediaDataUris, secondary, border, onRecursiveLookup)
        }
    }

    // Left-accent boxes (`border-style: none none none solid`): a colored edge bar with the
    // content beside it, exactly like the WebView's accent border. Drawn behind the row —
    // a standalone 3dp Box in a wrap-content Row measured 4dp tall (invisible stub).
    if (box.leftAccent) {
        Row(
            modifier = Modifier
                .then(outerAlphaModifier)
                .then(fillWidthModifier)
                .drawBehind {
                    drawRect(
                        color = borderColor,
                        topLeft = Offset.Zero,
                        size = Size(3.dp.toPx(), size.height),
                    )
                }
                .padding(start = 3.dp)
                .padding(
                    start = box.marginStart?.dp ?: 0.dp,
                    end = box.marginEnd?.dp ?: 0.dp,
                    top = box.marginTop?.dp ?: 0.dp,
                    bottom = box.marginBottom?.dp ?: 0.dp,
                ),
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
                Box(Modifier.padding(start = if (box.paddingStart == null) 6.dp else 0.dp)) { content }
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
            top = box.marginTop?.dp ?: 0.dp,
            bottom = box.marginBottom?.dp ?: 0.dp,
        )
        .clip(shape)
    if (box.hasBackground) modifier = modifier.background(bgColor)
    if (box.hasBorder) modifier = modifier.border(1.dp, borderColor, shape)
    Column(modifier = modifier) {
        node.children.forEach { child ->
            StructuredNodeView(child, parsedCss, effectiveStyled, dictName, mediaDataUris, secondary, border, onRecursiveLookup)
        }
    }
}

@Composable
private fun StructuredTagChip(
    node: StructuredNode.Element,
    css: Map<String, String>,
    baseStyle: TextStyle,
) {
    val bg = css["backgroundColor"]?.let { parseCssColor2(it) } ?: baseStyle.color?.copy(alpha = 0.15f) ?: Color.Gray
    val fg = css["color"]?.let { parseCssColor2(it) } ?: if (bg.luminance() > 0.5f) Color.Black else Color.White
    val text = node.collect()
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
) {
    val ordered = node.tag == StructuredTag.OrderedList
    val cssMap = remember(node.attributes.data, parsedCss) { getCssStyles(node.attributes.data, parsedCss) }
    val listStyleType = remember(cssMap, node.attributes.style) { (cssMap + node.attributes.style)["listStyleType"] }

    Column {
        var counter = 0
        node.children.forEach { child ->
            if (child is StructuredNode.Element && child.tag == StructuredTag.ListItem) {
                counter++
                StructuredListItem(
                    child, parsedCss, style, dictName, mediaDataUris, secondary, border,
                    onRecursiveLookup, ordered, counter, listStyleType,
                )
            } else {
                StructuredNodeView(child, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup)
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
) {
    val marker = when {
        listStyleType == "none" -> ""
        ordered -> "$index."
        listStyleType == "circle" -> "◦"
        listStyleType == "square" -> "▪"
        listStyleType == "disc" -> "•"
        else -> listStyleType?.trim('"', '\'')
            ?.takeIf { it.isNotEmpty() && it != "inherit" && !it.startsWith("url(") }
            ?: "•"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            marker,
            color = secondary,
            style = style.copy(fontSize = style.fontSize * 0.9f),
            modifier = Modifier
                .padding(end = 6.dp)
                .widthIn(min = 12.dp),
        )
        Column(Modifier.weight(1f)) {
            node.children.forEach { child ->
                StructuredNodeView(child, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup)
            }
        }
    }
}

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
    // Draw furigana ABOVE the base via TextWithReading — a Row of sibling Texts made the
    // reading look like duplicated text next to the kanji.
    TextWithReading(
        formattedText = "[$base[$rt]]",
        style = style,
        furiganaFontSize = style.fontSize * 0.6f,
    )
}

@Composable
private fun StructuredLink(
    node: StructuredNode.Element,
    style: TextStyle,
    onRecursiveLookup: ((String) -> Unit)?,
) {
    val href = node.attributes.properties["href"]
    val text = node.collect()
    if (text.isBlank()) return
    val linkColor = MaterialTheme.colorScheme.primary
    val context = LocalContext.current

    // External URLs open in the browser (WebView parity: navigateStructuredLink routes
    // internal `?query=` links to lookup, everything else to navigation). The old code
    // made the literal "https://…" string the lookup term.
    if (href != null && (href.startsWith("http://") || href.startsWith("https://"))) {
        Text(
            text = text,
            style = style.copy(
                color = linkColor,
                fontWeight = FontWeight.Medium,
                textDecoration = TextDecoration.Underline,
            ),
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

    val annotated = remember(target, text, linkColor) {
        buildAnnotatedString {
            if (target.isNotEmpty()) {
                val link = LinkAnnotation.Clickable(target) {
                    onRecursiveLookup?.invoke(target)
                }
                addLink(link, 0, text.length)
            }
            pushStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline))
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
) {
    val path = node.attributes.properties["path"]
    if (path.isNullOrBlank()) return
    val uri = resolveMediaUri2(dictName, path, mediaDataUris)
    if (uri == null) return

    // Reference sizing: em-sized images render at `width em` scaled to the base font size
    // (sizeUnits=em), otherwise the image's natural aspect at the dictionary's max width.
    val sizeUnits = node.attributes.properties["sizeUnits"]
    if (sizeUnits == "em") {
        val emWidth = node.attributes.properties["width"]?.toFloatOrNull()
        if (emWidth != null) {
            val baseSp = if (style.fontSize.isSp) style.fontSize.value else 16f
            Box(modifier = Modifier.padding(vertical = 2.dp)) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier
                        .width((emWidth * baseSp).dp)
                        .heightIn(max = 240.dp),
                )
            }
            return
        }
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
) {
    var expanded by remember(node) { mutableStateOf(node.attributes.properties["open"] == "true") }
    val summary = node.children.firstOrNull {
        it is StructuredNode.Element && it.tag == StructuredTag.Summary
    }
    val body = node.children.filterNot { it === summary }

    // `details[data-sc-content="..."] { padding: ... }` styles the disclosure block itself.
    val combined = getCssStyles(node.attributes.data, parsedCss) + node.attributes.style
    val baseFontSizeSp = style.fontSize.let { if (it.isSp) it.value else 16f }
    val box = parseBoxStyle(combined, baseFontSizeSp)

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
                StructuredNodeView(summary, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup)
            } else {
                Text("Details", style = style.copy(color = secondary))
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 12.dp)) {
                body.forEach { child ->
                    StructuredNodeView(child, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup)
                }
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
) {
    // Flatten rows: table > (thead/tbody/tfoot)? > tr > (th|td)
    val rows = mutableListOf<List<StructuredTableCell>>()
    collectTableRows(node, rows)
    if (rows.isEmpty()) return

    Column(Modifier.padding(vertical = 4.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { cell ->
                    // colSpan handling: GlossaryJsonParser stores colSpan as string property "colSpan"
                    val colSpan = cell.element?.attributes?.properties?.get("colSpan")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val weight = colSpan.toFloat()
                    Column(
                        modifier = Modifier
                            .weight(weight)
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            // border collapse: use 0.5.dp to avoid double borders between cells (each cell draws its own border)
                            .border(0.5.dp, border, RoundedCornerShape(2.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    ) {
                        if (cell.isHeader) {
                            Text(
                                cell.nodes.joinToString("") { it.collect() },
                                style = style.copy(fontWeight = FontWeight.Bold),
                            )
                        } else {
                            val (marker, badgeColor) = formBadge(cell.element?.attributes?.data?.get("class"), parsedCss)
                            if (marker != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StructuredFormBadge(marker, badgeColor)
                                    if (cell.nodes.isNotEmpty()) {
                                        Spacer(Modifier.width(4.dp))
                                        cell.nodes.forEach { child ->
                                            StructuredNodeView(child, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup)
                                        }
                                    }
                                }
                            } else {
                                cell.nodes.forEach { child ->
                                    StructuredNodeView(child, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class StructuredTableCell(
    val nodes: List<StructuredNode>,
    val isHeader: Boolean,
    val element: StructuredNode.Element? = null,
)

@Composable
private fun StructuredFormBadge(marker: String, badgeColor: Color?) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(badgeColor ?: Color.Gray),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            marker,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Recursively collects `tr` rows (each a list of cell nodes) from a table tree. */
private fun collectTableRows(node: StructuredNode.Element, out: MutableList<List<StructuredTableCell>>) {
    if (node.tag == StructuredTag.Tr) {
        val cells = node.children
            .filterIsInstance<StructuredNode.Element>()
            .filter { it.tag == StructuredTag.Td || it.tag == StructuredTag.Th }
            .map { StructuredTableCell(it.children, it.tag == StructuredTag.Th, it) }
        if (cells.isNotEmpty()) out.add(cells)
    } else {
        node.children.forEach { child ->
            if (child is StructuredNode.Element) collectTableRows(child, out)
        }
    }
}

/** The marker glyph + circle colour for a Jitendex `td[data-sc-class="form-*"]` badge. */
private val RADIAL_GRADIENT_REGEX = Regex("""radial-gradient\(([^)]+)\s+50%""")
private val CLASS_SPLIT_REGEX = Regex("\\s+")

private fun formBadge(dataClass: String?, parsedCss: ParsedCss): Pair<String?, Color?> {
    if (dataClass.isNullOrBlank()) return null to null
    // class="form-pri form-irr" — split tokens and merge, matching getCssStyles semantics
    // (selectorStyles only holds single-token keys).
    val styles = getCssStyles(mapOf("class" to dataClass), parsedCss)
    if (styles.isEmpty()) return null to null
    val marker = (styles["beforeContent"] ?: "").trim('"', '\'', ' ')
        .takeIf { it.isNotEmpty() }
    val bg = styles["background"]
        ?.let { RADIAL_GRADIENT_REGEX.find(it)?.groupValues?.get(1)?.trim() }
        ?.let { parseCssColor2(it) }
    return marker to bg
}

// ---------------------------------------------------------------------------
// Text / helpers
// ---------------------------------------------------------------------------

@Composable
private fun spanText(text: String, style: TextStyle, onRecursiveLookup: ((String) -> Unit)?) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = style,
        modifier = if (onRecursiveLookup != null) Modifier.clickable { onRecursiveLookup(text) } else Modifier,
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

private fun applyTypography(base: TextStyle, css: Map<String, String>): TextStyle {
    var style = base
    val currentSp = base.fontSize.value.takeIf { it > 0f } ?: 16f
    css["color"]?.let { parseCssColor2(it) }?.let { style = style.copy(color = it) }
    css["fontSize"]?.let { parseFontSize(it, currentSp) }?.let { style = style.copy(fontSize = it) }
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
    // verticalAlign: super — 109k uses for 0.7em super notes
    when (css["verticalAlign"]?.trim()?.lowercase()) {
        "super" -> style = style.copy(baselineShift = BaselineShift.Superscript)
        "sub" -> style = style.copy(baselineShift = BaselineShift.Subscript)
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

/** Best-effort #hex / named color → Color. */
internal fun parseCssColor2(raw: String?): Color? {
    if (raw.isNullOrBlank()) return null
    val v = raw.trim()
    if (v.startsWith("#")) {
        val hex = v.removePrefix("#")
        // CSS hex is RRGGBBAA / RGBA; Compose Color(Long) wants AARRGGBB — reorder alpha.
        return when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("").let { runCatching { Color(("FF$it").toLong(16)) }.getOrNull() }
            4 -> {
                val r = hex[0].toString().repeat(2)
                val g = hex[1].toString().repeat(2)
                val b = hex[2].toString().repeat(2)
                val a = hex[3].toString().repeat(2)
                runCatching { Color((a + r + g + b).toLong(16)) }.getOrNull()
            }
            6 -> runCatching { Color(("FF$hex").toLong(16)) }.getOrNull()
            8 -> runCatching {
                Color((hex.substring(6, 8) + hex.substring(0, 6)).toLong(16))
            }.getOrNull()
            else -> null
        }
    }
    return namedCssColor[v.lowercase()]
}

private val namedCssColor = mapOf(
    "green" to Color(0xFF008000),
    "purple" to Color(0xFF800080),
    "orange" to Color(0xFFFFA500),
    "brown" to Color(0xFFA52A2A),
    "crimson" to Color(0xFFDC143C),
    "white" to Color.White,
    "black" to Color.Black,
    "goldenrod" to Color(0xFFDAA520),
    "lime" to Color(0xFF00FF00),
    "blue" to Color(0xFF0000FF),
    "red" to Color(0xFFFF0000),
)

private val QUERY_PARAM_REGEX = Regex("[?&]([^=]+)=([^&]+)")

private fun extractQuery(href: String): String? {
    val params = QUERY_PARAM_REGEX.findAll(href)
    return params.asSequence().mapNotNull { m ->
        if (m.groupValues[1] == "query") m.groupValues[2].replace("+", " ") else null
    }.firstOrNull()
}

private fun resolveMediaUri2(dictName: String, path: String, map: Map<String, String>): String? {
    if (map.isEmpty()) return null
    val cleaned = path.removePrefix("media://").removePrefix("media:").trimStart('/')
    for (candidate in listOf("$dictName\u0000$path", "$dictName\u0000$cleaned")) {
        map[candidate]?.let { return it }
    }
    return null
}