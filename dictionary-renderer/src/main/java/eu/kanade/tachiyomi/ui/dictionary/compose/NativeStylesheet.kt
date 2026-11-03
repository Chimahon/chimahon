package eu.kanade.tachiyomi.ui.dictionary.compose

/** Immutable source stylesheet identity. Parsing and cascade are owned by the C++ core. */
public data class ParsedCss(val nativeSourceCss: String = "") {
    public companion object { public val EMPTY: ParsedCss = ParsedCss() }
}

/** Host identity retained for prepared-document matching; native layout owns ancestry. */
public data class CssAncestor(val tag: String, val data: Map<String, String> = emptyMap())
