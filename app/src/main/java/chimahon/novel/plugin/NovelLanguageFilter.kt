package chimahon.novel.plugin

/** The "all" code selects multilingual sources, rather than overriding other language switches. */
internal fun isNovelLanguageEnabled(language: String, enabledLanguages: Set<String>): Boolean {
    return normalizeNovelLanguage(language) in enabledLanguages
}
