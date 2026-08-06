package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Replicates the WebView's tag color categories (base.css `--tag-*-background-color`)
 * and the JS `resolveTagCategory` inference (renderer.js).
 */
object DictionaryTagColors {
    fun resolveCategory(label: String, defaultCategory: String? = null): String {
        if (defaultCategory != null && defaultCategory != "default") return defaultCategory
        val t = label.lowercase()
        if (t == "freq" || t == "avg" || t == "frequency") return "frequency"
        if (t.contains("arch") || t.contains("obs") || t.contains("hist")) return "archaism"
        if (t.contains("popular")) return "popular"
        if (t.contains("freq")) return "frequent"
        if (t.contains("col") || t.contains("pol") || t.contains("hon") || t.contains("fam")) return "style"
        if (t.contains("dial")) return "dialect"
        if (t.contains("v5") || t.contains("v1") || t.contains("adj") || t.contains("adv") || t.contains("vs") || t.contains("vi") || t.contains("vt") || t.contains("exp") || t.contains("int")) return "partOfSpeech"
        return "default"
    }

    /** Raw #RRGGBB base arrow for each category (from base.css). */
    fun categoryBase(category: String): Color = when (category) {
        "name" -> Color(0xFFB6327A)
        "expression" -> Color(0xFFF0AD4E)
        "popular" -> Color(0xFF0275D8)
        "frequent" -> Color(0xFF5BC0DE)
        "archaism" -> Color(0xFFD9534F)
        "dictionary" -> Color(0xFFAA66CC)
        "partOfSpeech" -> Color(0xFF565656)
        "search" -> Color(0xFF8A8A91)
        "pronunciation_dictionary" -> Color(0xFF6640BE)
        "dialect" -> Color(0xFF2B70B4)
        "style" -> Color(0xFF69696E)
        else -> Color(0xFF8A8A91)
    }
}

private fun darken(c: Color, factor: Float): Color {
    val r = (c.red * factor).coerceIn(0f, 1f)
    val g = (c.green * factor).coerceIn(0f, 1f)
    val b = (c.blue * factor).coerceIn(0f, 1f)
    return Color(r, g, b, c.alpha)
}

/**
 * Render a colored dictionary tag pill, matching `.tag-label` + optional `.tag-body`
 * from base.css. Frequency tags use the muted `--secondary` scheme; other categories
 * use their solid background with white text. E-ink mode removes color/borders.
 */
@Composable
fun dictionaryTag(
    label: String,
    secondary: Color,
    eInk: Boolean = false,
    body: String? = null,
    background: Color? = null,
    category: String? = null,
) {
    val resolved = category ?: DictionaryTagColors.resolveCategory(label)
    val base = background ?: DictionaryTagColors.categoryBase(resolved)
    val isFrequency = resolved == "frequency"

    val labelBg = when {
        eInk -> Color.Transparent
        isFrequency -> secondary.copy(alpha = 0.10f)
        else -> base
    }
    val labelFg = when {
        eInk -> secondary
        isFrequency -> secondary
        else -> Color.White
    }
    val bodyBg = when {
        eInk -> Color.Transparent
        isFrequency -> secondary.copy(alpha = 0.15f)
        else -> darken(base, 0.8f)
    }
    val bodyFg = if (eInk) secondary else Color.White

    Row(Modifier.wrapContentSize().padding(end = 3.dp, bottom = 3.dp)) {
        if (body == null) {
            Text(
                text = label,
                color = labelFg,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                modifier = Modifier
                    .background(labelBg, RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        } else {
            Text(
                text = label,
                color = labelFg,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                modifier = Modifier
                    .background(labelBg, RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
            Text(
                text = body,
                color = bodyFg,
                fontWeight = FontWeight.SemiBold,
                fontSize = 10.sp,
                modifier = Modifier
                    .padding(start = 1.dp)
                    .background(bodyBg, RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}