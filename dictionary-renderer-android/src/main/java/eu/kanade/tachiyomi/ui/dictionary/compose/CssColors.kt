package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * Shared CSS color resolution for the Compose dictionary renderer.
 *
 * Handles #hex (3/4/6/8 digits, CSS RRGGBBAA order), named colors, var() hex
 * fallbacks, and full `color-mix(in srgb, C1 P%, C2)` blending (percentages
 * honored — a 5% tint renders as a tint, not a solid color).
 *
 * The functional `rgb()`/`rgba()` notation is handled too. A COMPUTED colour is
 * always reported in that form - `getComputedStyle().color` gives
 * "rgb(31, 31, 31)" - and the glossary body inherits it, so without this the
 * whole corpus was drawn in black where the reference uses rgb(31, 31, 31): a
 * 31/255 difference on every text pixel, well past the comparison tolerance.
 */

/** Best-effort #hex / named color → Color. */
public fun parseCssColor2(raw: String?): Color? {
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
    parseFunctionalColor(v)?.let { return it }
    return namedCssColor[v.lowercase()]
}

/**
 * The `rgb()`/`rgba()` notation, in both the comma-separated CSS3 form
 * ("rgb(31, 31, 31)", "rgba(0, 0, 0, 0.5)") and the CSS4 space-separated form
 * ("rgb(31 31 31 / 50%)"). Channels may be numbers or percentages.
 */
private fun parseFunctionalColor(value: String): Color? {
    val open = value.indexOf('(')
    if (open < 0 || !value.endsWith(")")) return null
    val name = value.substring(0, open).trim().lowercase()
    if (name != "rgb" && name != "rgba") return null
    val parts = value.substring(open + 1, value.length - 1)
        .split(',', '/', ' ', '\t', '\n')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (parts.size < 3) return null
    // Colour channels are 0..255 or a percentage; the alpha is 0..1 or a percentage.
    fun channel(token: String): Float? = if (token.endsWith("%")) {
        token.dropLast(1).toFloatOrNull()?.div(100f)
    } else {
        token.toFloatOrNull()?.div(255f)
    }
    fun alpha(token: String): Float = if (token.endsWith("%")) {
        (token.dropLast(1).toFloatOrNull()?.div(100f) ?: 1f)
    } else {
        token.toFloatOrNull() ?: 1f
    }.coerceIn(0f, 1f)
    val red = channel(parts[0])?.coerceIn(0f, 1f) ?: return null
    val green = channel(parts[1])?.coerceIn(0f, 1f) ?: return null
    val blue = channel(parts[2])?.coerceIn(0f, 1f) ?: return null
    val opacity = if (parts.size >= 4) alpha(parts[3]) else 1f
    return Color(red, green, blue, opacity)
}

public val namedCssColor = mapOf(
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
    "transparent" to Color.Transparent,
)

/** Compose Color → CSS `#RRGGBBAA` string (round-trips through [parseCssColor2]). */
public fun Color.toCssHex(): String {
    fun v(f: Float) = (f.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X%02X".format(v(red), v(green), v(blue), v(alpha))
}

private val COLOR_MIX_SPLIT_PCT = Regex("""^(.*)\s+([\d.]+)%\s*$""")
private val HEX_TOKEN = Regex("""#[0-9a-fA-F]{3,8}""")
private val VAR_HEX_FALLBACK = Regex("""var\(--[^,)]+,\s*([^)]+)\)""")
private val SEGMENT_SPLIT_WS = Regex("""\s+""")

/** Resolve a single color token (hex / named / transparent / var-with-hex-fallback). */
private fun resolveToken(token: String): Color? {
    val t = token.trim().removeSuffix(")")
    HEX_TOKEN.find(t)?.value?.let { return parseCssColor2(it) }
    namedCssColor[t.lowercase()]?.let { return it }
    if (t.startsWith("#")) return parseCssColor2(t)
    return null
}

/**
 * Blend `color-mix(in srgb, C1 P1%, C2)` honoring percentages.
 * Missing percentages default to 50/50 (CSS behavior). Returns `#RRGGBBAA` or null
 * when either side is unresolvable (caller falls back to first-color extraction).
 */
public fun blendColorMix(value: String): String? {
    val lower = value.lowercase()
    val mixIndex = lower.indexOf("color-mix")
    if (mixIndex == -1) return null
    val open = value.indexOf('(', mixIndex)
    if (open == -1) return null
    var depth = 0
    var close = -1
    for (i in open until value.length) {
        when (value[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) {
                    close = i
                    break
                }
            }
        }
    }
    if (close == -1) return null
    val inner = value.substring(open + 1, close)
    val afterSrbg = if (inner.contains("in srgb", ignoreCase = true)) {
        val s = inner.substringAfter("in srgb", "")
        val comma = s.indexOf(',')
        if (comma != -1) s.substring(comma + 1) else return null
    } else inner
    // split the two sides at the top-level comma (depth-aware for nested var())
    var d = 0
    var splitAt = -1
    for (i in afterSrbg.indices) {
        when (afterSrbg[i]) {
            '(' -> d++
            ')' -> d--
            ',' -> if (d == 0) {
                splitAt = i
                break
            }
        }
    }
    if (splitAt == -1) return null
    val firstRaw = afterSrbg.substring(0, splitAt).trim()
    val secondRaw = afterSrbg.substring(splitAt + 1).trim()
    if (firstRaw.isEmpty() || secondRaw.isEmpty()) return null

    val firstMatch = COLOR_MIX_SPLIT_PCT.matchEntire(firstRaw)
    val c1Token = firstMatch?.groupValues?.get(1)?.trim() ?: firstRaw
    val p1 = firstMatch?.groupValues?.get(2)?.toFloatOrNull()?.div(100f) ?: 0.5f
    val c1 = resolveToken(c1Token) ?: return null
    // second side may itself carry a percentage ("transparent 95%") — strip it, p1 rules
    val c2Token = COLOR_MIX_SPLIT_PCT.matchEntire(secondRaw)?.groupValues?.get(1)?.trim() ?: secondRaw
    val c2 = resolveToken(c2Token) ?: return null
    return lerpSrbg(c2, c1, p1.coerceIn(0f, 1f)).toCssHex()
}

/**
 * Naive component-wise interpolation in gamma-encoded sRGB — exactly what CSS
 * `color-mix(in srgb, …)` specifies. Compose's `Color.lerp` interpolates in a
 * perceptual space, which turned 5% tints hue-less (green → black) and broke
 * the faint-tint assertions.
 */
private fun lerpSrbg(c2: Color, c1: Color, t: Float): Color = Color(
    red = c2.red + (c1.red - c2.red) * t,
    green = c2.green + (c1.green - c2.green) * t,
    blue = c2.blue + (c1.blue - c2.blue) * t,
    alpha = c2.alpha + (c1.alpha - c2.alpha) * t,
)

/** Resolve any CSS color-ish string to a `#RRGGBBAA` hex, blending color-mix properly. */
public fun resolveCssColorHex(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.contains("color-mix", ignoreCase = true)) {
        blendColorMix(trimmed)?.let { return it }
    }
    return null
}

/**
 * First color segment of `radial-gradient(...)` (depth-aware, so nested
 * `var(--a, var(--b, #333))` survives). The legacy paren-blind regex failed on
 * exactly that shape, turning Jitendex form badges gray instead of near-black.
 */
public fun extractRadialFirstSegment(value: String): String? {
    val start = value.indexOf("radial-gradient", ignoreCase = true)
    if (start == -1) return null
    val open = value.indexOf('(', start)
    if (open == -1) return null
    var depth = 0
    var close = -1
    for (i in open until value.length) {
        when (value[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) {
                    close = i
                    break
                }
            }
        }
    }
    if (close == -1) return null
    val inner = value.substring(open + 1, close)
    // Split stops at top-level commas, then return the first segment that
    // actually carries a color (skipping shape descriptors like
    // `circle at center` and bare position stops).
    val segments = mutableListOf<String>()
    var d = 0
    var segStart = 0
    for (i in inner.indices) {
        when (inner[i]) {
            '(' -> d++
            ')' -> d--
            ',' -> if (d == 0) {
                segments.add(inner.substring(segStart, i).trim())
                segStart = i + 1
            }
        }
    }
    segments.add(inner.substring(segStart).trim())
    return segments.firstOrNull { seg ->
        seg.contains('#') || seg.contains("var(", ignoreCase = true) ||
            seg.split(SEGMENT_SPLIT_WS).any { namedCssColor.containsKey(it.lowercase()) }
    }
}
