package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

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
        StructuredTag.Image -> StructuredImage(node, dictName, mediaDataUris)
        StructuredTag.Table -> StructuredTable(
            node, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup,
        )
        StructuredTag.Details, StructuredTag.Summary -> StructuredDetails(
            node, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup,
        )
        StructuredTag.UnorderedList, StructuredTag.OrderedList, StructuredTag.ListItem -> StructuredList(
            node, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup,
        )
        StructuredTag.Div, StructuredTag.Span -> StructuredBox(
            node, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup,
        )
        else -> node.children.forEach { child ->
            StructuredNodeView(child, parsedCss, style, dictName, mediaDataUris, secondary, border, onRecursiveLookup)
        }
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

    val combined = getCssStyles(node.attributes.data, parsedCss) + node.attributes.style
    val styled = applyTypography(style, combined)

    // Tag chip spans: `span[data-sc-class="tag"]` etc.
    if (node.tag == StructuredTag.Span && node.attributes.data["class"]?.contains("tag") == true) {
        StructuredTagChip(node, combined, style)
        return
    }

    val baseFontSizeSp = style.fontSize.let { if (it.isSp) it.value else 16f }
    val box = parseBoxStyle(combined, baseFontSizeSp)

    val isInline = node.children.all {
        it is StructuredNode.Text || (it is StructuredNode.Element && it.tag == StructuredTag.Span)
    }

    if (!box.hasAnyStyle && isInline) {
        // Plain inline span/div → emit inline text only (fast path).
        spanText(node.collect(), styled, onRecursiveLookup)
        return
    }

    val shape = RoundedCornerShape(box.borderRadius?.dp ?: 6.dp)
    val bgColor = box.backgroundColor?.let { parseCssColor2(it) }
        ?: if (box.hasBackground) secondary.copy(alpha = 0.08f) else Color.Transparent
    val borderColor = box.borderColor?.let { parseCssColor2(it) } ?: border

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
            StructuredNodeView(child, parsedCss, styled, dictName, mediaDataUris, secondary, border, onRecursiveLookup)
        }
    }

    // Left-accent boxes (`border-style: none none none solid`): a colored edge bar with the
    // content beside it, exactly like the WebView's accent border.
    if (box.leftAccent) {
        Row(
            modifier = Modifier
                .padding(
                    start = box.marginStart?.dp ?: 0.dp,
                    end = box.marginEnd?.dp ?: 0.dp,
                    top = box.marginTop?.dp ?: 0.dp,
                    bottom = box.marginBottom?.dp ?: 0.dp,
                ),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .padding(top = 2.dp, bottom = 2.dp)
                    .clip(RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp))
                    .background(borderColor),
            )
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
            StructuredNodeView(child, parsedCss, styled, dictName, mediaDataUris, secondary, border, onRecursiveLookup)
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
    val cssMap = getCssStyles(node.attributes.data, parsedCss)
    val listStyleType = (cssMap + node.attributes.style)["listStyleType"]

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
    Row(Modifier.padding(vertical = 1.dp)) {
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
    val base = node.children
        .filterNot { it is StructuredNode.Element && it.tag == StructuredTag.Rt }
        .joinToString("") { it.collect() }
    val rt = node.children
        .filterIsInstance<StructuredNode.Element>()
        .firstOrNull { it.tag == StructuredTag.Rt }
        ?.children?.joinToString("") { it.collect() }.orEmpty()
    if (rt.isBlank()) {
        spanText(base, style, null)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 2.dp)) {
        Text(base, style = style)
        Text(
            rt,
            color = style.color ?: Color.Unspecified,
            fontSize = style.fontSize * 0.6f,
            modifier = Modifier.padding(start = 3.dp),
        )
    }
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

    val query = href?.let { extractQuery(it) }
    val display = if (query != null) text else if (!href.isNullOrBlank()) "$text ($href)" else text
    val target = query ?: href ?: text

    val annotated = remember(target, display, linkColor) {
        buildAnnotatedString {
            if (target.isNotEmpty()) {
                val link = LinkAnnotation.Clickable(target) {
                    onRecursiveLookup?.invoke(target)
                }
                addLink(link, 0, display.length)
            }
            pushStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline))
            append(display)
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
    dictName: String,
    mediaDataUris: Map<String, String>,
) {
    val path = node.attributes.properties["path"]
    if (path.isNullOrBlank()) return
    val uri = resolveMediaUri2(dictName, path, mediaDataUris)
    if (uri == null) return
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

    Column {
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
            Row {
                row.forEach { cell ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .border(1.dp, border, RoundedCornerShape(2.dp))
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

private fun formBadge(dataClass: String?, parsedCss: ParsedCss): Pair<String?, Color?> {
    if (dataClass.isNullOrBlank()) return null to null
    val styles = parsedCss.selectorStyles[dataClass] ?: return null to null
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
        val full = when (hex.length) {
            3 -> hex.map { "$it$it" }.joinToString("").let { "FF$it" }
            4 -> hex.map { "$it$it" }.joinToString("")
            6 -> "FF$hex"
            8 -> hex
            else -> return null
        }
        return runCatching { Color(full.toLong(16)) }.getOrNull()
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