package eu.kanade.tachiyomi.data.coil

import coil3.key.Keyer
import coil3.request.Options
import eu.kanade.domain.entries.anime.model.hasCustomBackground
import eu.kanade.domain.entries.anime.model.hasCustomCover
import eu.kanade.tachiyomi.data.cache.AnimeCoverCache
import tachiyomi.domain.entries.anime.model.AnimeCover
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.domain.entries.anime.model.Anime as DomainAnime

class AnimeKeyer : Keyer<DomainAnime> {
    override fun key(data: DomainAnime, options: Options): String {
        val hasBackground = data.backgroundUrl != null || data.hasCustomBackground()

        return when {
            options.useBackground && data.hasCustomBackground() -> {
                "anime-background;${data.id};${data.backgroundLastModified}"
            }
            options.useBackground && hasBackground -> {
                "anime-background;${data.backgroundUrl.orKeyOf("background-none", data.id)};${data.backgroundLastModified}"
            }
            data.hasCustomCover() -> "anime;${data.id};${data.coverLastModified}"
            else -> "anime;${data.thumbnailUrl.orKeyOf("cover-none", data.id)};${data.coverLastModified}"
        }
    }
}

class AnimeCoverKeyer(
    private val coverCache: AnimeCoverCache = Injekt.get(),
) : Keyer<AnimeCover> {
    override fun key(data: AnimeCover, options: Options): String {
        return if (coverCache.getCustomCoverFile(data.animeId).exists()) {
            "anime;${data.animeId};${data.lastModified}"
        } else {
            "anime;${data.url.orKeyOf("cover-none", data.animeId)};${data.lastModified}"
        }
    }
}

/**
 * Entries without a cover/background would all resolve to the very same cache key (ie. `"null;0"`),
 * so Coil's memory & disk cache would happily serve an unrelated entry's image for any of them.
 * Fall back to an id based key to keep every entry unique.
 */
private fun String?.orKeyOf(prefix: String, id: Long): String {
    return if (isNullOrEmpty()) "$prefix;$id" else this
}
