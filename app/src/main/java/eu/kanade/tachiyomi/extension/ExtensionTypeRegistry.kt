package eu.kanade.tachiyomi.extension

import eu.kanade.tachiyomi.extension.model.Extension

/**
 * Clean registry for current and future extension types.
 * Each ContentType maps to one feature string, one store, and one manager.
 * Adding a new type (e.g. NOVEL_JS) is: new enum value + one registry entry + new TabContent.
 * No second ExtensionManager — one manager, filtered flows per type.
 */
object ExtensionTypeRegistry {

    data class TypeDef(
        val contentType: Extension.ContentType,
        val features: Set<String>,
        val repoBaseUrlPreferenceKey: String,
        val sourceManager: Any? = null,
    )

    private val types = listOf(
        TypeDef(
            contentType = Extension.ContentType.MANGA,
            features = setOf("tachiyomi.extension"),
            repoBaseUrlPreferenceKey = "extension_repos",
        ),
        TypeDef(
            contentType = Extension.ContentType.NOVEL,
            features = setOf("ireader.extension", "lnreader.js.plugin"),
            repoBaseUrlPreferenceKey = "novel_extension_repos",
        ),
    )

    fun forContentType(contentType: Extension.ContentType): TypeDef? =
        types.find { it.contentType == contentType }

    fun forFeature(feature: String): TypeDef? =
        types.find { feature in it.features }

    fun allFeatures(): Set<String> = types.flatMap { it.features }.toSet()
}
