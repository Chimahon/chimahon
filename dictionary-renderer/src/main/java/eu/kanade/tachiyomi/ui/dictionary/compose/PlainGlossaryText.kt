package eu.kanade.tachiyomi.ui.dictionary.compose

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup

/** Text-only presentation is independent of native glossary layout. */
public fun plainGlossaryText(raw: String): String {
    if (raw.startsWith("<")) return Jsoup.parseBodyFragment(raw).text()
    if (!raw.startsWith("[") && !raw.startsWith("{")) return raw
    return buildString {
        fun visit(value: Any?) {
            when (value) {
                is String -> append(value)
                is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i))
                is JSONObject -> {
                    val tag = value.optString("tag")
                    if (tag == "rt" || tag == "rp" || value.optJSONObject("data")?.optString("content") == "attribution") return
                    if (tag == "br") append('\n')
                    visit(value.opt("text") ?: value.opt("content") ?: value.optJSONObject("ruby")?.opt("text"))
                    if (tag in setOf("div", "p", "li", "tr")) append('\n')
                }
            }
        }
        visit(JSONTokener(raw).nextValue())
    }
}
