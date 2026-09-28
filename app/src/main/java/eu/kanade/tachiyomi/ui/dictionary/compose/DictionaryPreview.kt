package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Quick local preview for dictionary rendering without installing on device.
 * Open this file in Android Studio and click the gutter preview icon.
 *
 * Shows how Jitendex / JMdict structured glosses look with/without CSS.
 * Add your own glossary JSON or styles.css snippet to iterate instantly.
 */
private val SAMPLE_JITENDEX_CSS = """
.sense { background-color: #f0f0f0; padding: 6px; border-radius: 6px; }
[data-sc-content="glossary"] { padding-left: 1.2em; }
[data-sc-class="tag"] { background-color: #565656; color: white; border-radius: 0.3em; padding: 0.2em 0.3em; }
.test-box { background-color: color-mix(in srgb, red 5%, transparent); border-color: #1A73E8; border-style: none none none solid; border-width: calc(3em / var(--font-size-no-units, 14)); border-radius: 0.4rem; padding: 0.5rem; }
""".trimIndent()

private val SAMPLE_JITENDEX_GLOSS = """
[
  {"tag": "div", "data": {"content": "glossary"}, "content": [
    {"tag": "span", "data": {"class": "tag"}, "content": "common"},
    {"tag": "div", "data": {"content": "sense"}, "style": {"marginTop": "0.5rem"}, "content": [
      {"tag": "span", "style": {"fontWeight": "bold"}, "content": "to eat"},
      {"tag": "span", "style": {"color": "color-mix(in srgb, lime, var(--text-color, #333))"}, "content": " (sense 1)"}
    ]},
    {"tag": "div", "data": {"content": "glossary"}, "style": {"backgroundColor": "color-mix(in srgb, #1A73E8 5%, transparent)", "borderColor": "#1A73E8", "borderStyle": "none none none solid", "borderWidth": "calc(3em / var(--font-size-no-units, 14))", "marginTop": "0.5rem", "padding": "0.5rem", "borderRadius": "0.4rem"}, "content": "example box with left accent"},
    {"tag": "ul", "content": [{"tag": "li", "style": {"listStyleType": "circle"}, "content": "first bullet"}, {"tag": "li", "content": "second"}]},
    {"tag": "span", "style": {"fontSize": "0.7em", "verticalAlign": "super"}, "content": "super note"},
    {"tag": "ruby", "content": [{"tag": "rt", "content": "た"}, "食"]}
  ]}
]
""".trimIndent()

private val SAMPLE_FLAT_GLOSS = """[{"tag":"span","style":{"fontWeight":"bold"},"content":"plain bold text and "}, "normal"]"""

@Preview(name = "Jitendex structured (with CSS)", showBackground = true, backgroundColor = 0xFFF8F8F8, widthDp = 360)
@Composable
fun PreviewJitendexStructured() {
    val css = parseDictionaryCss(SAMPLE_JITENDEX_CSS)
    // debug: log parsed keys in preview (visible in logcat if you add Log.d)
    // parsedCss.selectorStyles.keys -> ["sense", "glossary", "tag", "test-box"]
    StructuredGlossaryContent(
        nodes = (parseStructuredGlossary(SAMPLE_JITENDEX_GLOSS) as? StructuredEntry.Tree)?.nodes ?: emptyList(),
        parsedCss = css,
        dictName = "Jitendex.org",
        mediaDataUris = emptyMap(),
        fontSize = 14,
        onBg = Color(0xFF1F1F1F),
        secondary = Color(0xFF757575),
        border = Color(0x14212121),
        onRecursiveLookup = {},
        modifier = Modifier.padding(12.dp)
    )
}

@Preview(name = "Plain-text flatten", showBackground = true, widthDp = 360)
@Composable
fun PreviewPlainText() {
    val entry = StructuredEntry.Tree((parseStructuredGlossary(SAMPLE_JITENDEX_GLOSS) as StructuredEntry.Tree).nodes)
    val text = entry.nodes.joinToString("") { it.flatten() }
        .replace(Regex("\\n[ \\t]*\\n+"), "\n").replace(Regex("[ \\t]{2,}"), " ").trim()
    Column(Modifier.padding(12.dp)) {
        androidx.compose.material3.Text(text, color = Color.Black, fontSize = 13.sp, lineHeight = 17.sp)
        androidx.compose.material3.Text("— collapsed whitespace, single newline —", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

/**
 * Quick CSS debug helper: call from anywhere (e.g. DictionaryEntryCompose) to log
 * whether dict CSS is actually reaching the renderer without needing a device.
 * Example: Log.d("CssDebug", debugCssStats(styles))
 */
fun debugCssStats(styles: List<chimahon.DictionaryStyle>): String = buildString {
    append("styles.size=${styles.size} ")
    styles.forEach { s ->
        val parsed = parseDictionaryCss(s.styles)
        append("[${s.dictName}: selectors=${parsed.selectorStyles.size}, boxSelectors=${parsed.boxSelectors.size}, presence=${parsed.presenceRules.size}, cssLen=${s.styles.length}] ")
    }
}
