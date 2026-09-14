package eu.kanade.domain.entries.anime.model

import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.cache.AnimeBackgroundCache
import eu.kanade.tachiyomi.data.cache.AnimeCoverCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.toUpdateStrategy
import eu.kanade.tachiyomi.animesource.model.AnimeUpdateStrategy
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.entries.anime.model.Anime
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// TODO: move these into the domain model
val Anime.downloadedFilter: TriState
    get() {
        if (forceDownloaded()) return TriState.ENABLED_IS
        return when (downloadedFilterRaw) {
            Anime.EPISODE_SHOW_DOWNLOADED -> TriState.ENABLED_IS
            Anime.EPISODE_SHOW_NOT_DOWNLOADED -> TriState.ENABLED_NOT
            else -> TriState.DISABLED
        }
    }
fun Anime.episodesFiltered(): Boolean {
    return unseenFilter != TriState.DISABLED ||
        downloadedFilter != TriState.DISABLED ||
        bookmarkedFilter != TriState.DISABLED ||
        // AM (FILLERMARK) -->
        fillermarkedFilter != TriState.DISABLED
    // <-- AM (FILLERMARK)
}
fun Anime.forceDownloaded(): Boolean {
    return favorite && Injekt.get<BasePreferences>().downloadedOnly().get()
}

val Anime.seasonDownloadedFilter: TriState
    get() {
        if (forceDownloaded()) return TriState.ENABLED_IS
        return when (seasonFlags and Anime.SEASON_DOWNLOADED_MASK) {
            Anime.SEASON_SHOW_DOWNLOADED -> TriState.ENABLED_IS
            Anime.SEASON_SHOW_NOT_DOWNLOADED -> TriState.ENABLED_NOT
            else -> TriState.DISABLED
        }
    }

val Anime.seasonUnseenFilter: TriState
    get() = when (seasonFlags and Anime.SEASON_UNSEEN_MASK) {
        Anime.SEASON_SHOW_UNSEEN -> TriState.ENABLED_IS
        Anime.SEASON_SHOW_SEEN -> TriState.ENABLED_NOT
        else -> TriState.DISABLED
    }

val Anime.seasonStartedFilter: TriState
    get() = when (seasonFlags and Anime.SEASON_STARTED_MASK) {
        Anime.SEASON_SHOW_STARTED -> TriState.ENABLED_IS
        Anime.SEASON_SHOW_NOT_STARTED -> TriState.ENABLED_NOT
        else -> TriState.DISABLED
    }

val Anime.seasonCompletedFilter: TriState
    get() = when (seasonFlags and Anime.SEASON_COMPLETED_MASK) {
        Anime.SEASON_SHOW_COMPLETED -> TriState.ENABLED_IS
        Anime.SEASON_SHOW_NOT_COMPLETED -> TriState.ENABLED_NOT
        else -> TriState.DISABLED
    }

val Anime.seasonBookmarkedFilter: TriState
    get() = when (seasonFlags and Anime.SEASON_BOOKMARKED_MASK) {
        Anime.SEASON_SHOW_BOOKMARKED -> TriState.ENABLED_IS
        Anime.SEASON_SHOW_NOT_BOOKMARKED -> TriState.ENABLED_NOT
        else -> TriState.DISABLED
    }

val Anime.seasonFillermarkedFilter: TriState
    get() = when (seasonFlags and Anime.SEASON_FILLERMARKED_MASK) {
        Anime.SEASON_SHOW_FILLERMARKED -> TriState.ENABLED_IS
        Anime.SEASON_SHOW_NOT_FILLERMARKED -> TriState.ENABLED_NOT
        else -> TriState.DISABLED
    }

fun Anime.seasonsFiltered(): Boolean {
    return seasonDownloadedFilter != TriState.DISABLED ||
        seasonUnseenFilter != TriState.DISABLED ||
        seasonStartedFilter != TriState.DISABLED ||
        seasonCompletedFilter != TriState.DISABLED ||
        seasonBookmarkedFilter != TriState.DISABLED ||
        seasonFillermarkedFilter != TriState.DISABLED
}

fun Anime.toSAnime(): SAnime = SAnime.create().also {
    it.url = url
    it.title = title
    it.artist = artist
    it.author = author
    it.description = description
    it.genre = genre.orEmpty().joinToString()
    it.status = status.toInt()
    it.thumbnail_url = thumbnailUrl
    it.background_url = backgroundUrl
    it.fetch_type = fetchType
    it.season_number = seasonNumber
    it.initialized = initialized
}

fun Anime.copyFrom(other: SAnime): Anime {
    // SY -->
    val title = other.titleOrUrl().ifBlank { ogTitle }
    val author = other.author ?: ogAuthor
    val artist = other.artist ?: ogArtist
    val thumbnailUrl = other.thumbnail_url ?: ogThumbnailUrl
    val backgroundUrl = other.background_url ?: backgroundUrl
    val description = other.description ?: ogDescription
    val genres = if (other.genre != null) {
        other.getGenres()
    } else {
        ogGenre
    }
    // SY <--
    return this.copy(
        // SY -->
        ogTitle = title,
        ogAuthor = author,
        ogArtist = artist,
        ogThumbnailUrl = thumbnailUrl,
        backgroundUrl = backgroundUrl,
        ogDescription = description,
        ogGenre = genres,
        // SY <--
        // SY -->
        ogStatus = other.status.toLong(),
        // SY <--
        fetchType = other.fetch_type,
        seasonNumber = other.season_number,
        updateStrategy = other.update_strategy.toUpdateStrategy(),
        initialized = other.initialized,
    )
}

fun SAnime.toDomainAnime(sourceId: Long): Anime {
    return Anime.create().copy(
        url = url,
        // SY -->
        ogTitle = titleOrUrl(),
        ogArtist = artist,
        ogAuthor = author,
        ogThumbnailUrl = thumbnail_url,
        backgroundUrl = background_url,
        ogDescription = description,
        ogGenre = getGenres(),
        ogStatus = status.toLong(),
        // SY <--
        updateStrategy = update_strategy.toUpdateStrategy(),
        fetchType = fetch_type,
        seasonNumber = season_number,
        initialized = initialized,
        source = sourceId,
    )
}

fun SAnime.titleOrUrl(): String {
    val sourceTitle = runCatching { title.trim() }.getOrDefault("")
    if (sourceTitle.isNotBlank()) return sourceTitle

    val sourceUrl = runCatching { url.trim() }.getOrDefault("")
    return sourceUrl
        .substringBefore('?')
        .trimEnd('/', '\\')
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { sourceUrl.ifBlank { "Unknown anime" } }
}

fun Anime.hasCustomCover(coverCache: CoverCache = Injekt.get()): Boolean {
    return coverCache.getCustomCoverFile(id).exists()
}

fun Anime.hasCustomCover(coverCache: AnimeCoverCache): Boolean {
    return coverCache.getCustomCoverFile(id).exists()
}

fun Anime.hasCustomBackground(backgroundCache: AnimeBackgroundCache = Injekt.get()): Boolean {
    return backgroundCache.getCustomBackgroundFile(id).exists()
}
