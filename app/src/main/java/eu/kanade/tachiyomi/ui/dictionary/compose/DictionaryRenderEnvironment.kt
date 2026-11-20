package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.runtime.staticCompositionLocalOf

/** Optional explicit environment for reproducible popup rendering, without changing preferences. */
data class DictionaryRenderEnvironment(
    val dark: Boolean,
    val amoled: Boolean,
    val seedColor: Int,
    val dictionaryTitles: Map<String, String>,
)

val LocalDictionaryRenderEnvironment = staticCompositionLocalOf<DictionaryRenderEnvironment?> { null }
