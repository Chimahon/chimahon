package eu.kanade.domain.animeextension.model

import eu.kanade.tachiyomi.animeextension.model.AnimeExtension

data class AnimeExtensions(
    val updates: List<AnimeExtension.Installed>,
    val installed: List<AnimeExtension.Installed>,
    val available: List<AnimeExtension.Available>,
    val untrusted: List<AnimeExtension.Untrusted>,
    // Chimahon -->
    /** Remembered-from-sync anime extensions available in this device's catalog, one per package. */
    val fromSync: List<AnimeExtension.Available> = emptyList(),
    // Chimahon <--
)
