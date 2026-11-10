package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

public const val DEFAULT_LINE_HEIGHT_FACTOR = 1.4f

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
    if (path.startsWith("data:image/")) return path
    if (map.isEmpty()) return null
    val cleaned = path.removePrefix("media://").removePrefix("media:").trimStart('/')
    for (candidate in listOf("$dictName\u0000$path", "$dictName\u0000$cleaned")) {
        map[candidate]?.let { return it }
    }
    return null
}
