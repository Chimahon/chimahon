package eu.kanade.tachiyomi.util

import eu.kanade.domain.entries.anime.interactor.UpdateAnime
import eu.kanade.domain.entries.anime.model.hasCustomBackground
import eu.kanade.domain.entries.anime.model.hasCustomCover
import eu.kanade.domain.entries.anime.model.toSAnime
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.cache.AnimeBackgroundCache
import eu.kanade.tachiyomi.data.cache.AnimeCoverCache
import eu.kanade.tachiyomi.data.cache.CoverCache
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.source.local.entries.anime.isLocal
import tachiyomi.source.local.image.anime.LocalAnimeBackgroundManager
import tachiyomi.source.local.image.anime.LocalAnimeCoverManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import java.time.Instant

fun Anime.prepUpdateCover(coverCache: AnimeCoverCache, remoteAnime: SAnime, refreshSameUrl: Boolean): Anime {
    val newUrl = remoteAnime.thumbnail_url ?: return this

    if (newUrl.isEmpty()) return this

    if (!refreshSameUrl && thumbnailUrl == newUrl) return this

    return when {
        isLocal() -> {
            this.copy(coverLastModified = Instant.now().toEpochMilli())
        }
        hasCustomCover(coverCache) -> {
            coverCache.deleteFromCache(this, false)
            this
        }
        else -> {
            coverCache.deleteFromCache(this, false)
            this.copy(coverLastModified = Instant.now().toEpochMilli())
        }
    }
}

fun Anime.prepUpdateBackground(
    backgroundCache: AnimeBackgroundCache,
    remoteAnime: SAnime,
    refreshSameUrl: Boolean,
): Anime {
    val newUrl = remoteAnime.background_url ?: return this

    if (newUrl.isEmpty()) return this

    if (!refreshSameUrl && backgroundUrl == newUrl) return this

    return when {
        isLocal() -> {
            this.copy(backgroundLastModified = Instant.now().toEpochMilli())
        }
        hasCustomBackground(backgroundCache) -> {
            backgroundCache.deleteFromCache(this, false)
            this
        }
        else -> {
            backgroundCache.deleteFromCache(this, false)
            this.copy(backgroundLastModified = Instant.now().toEpochMilli())
        }
    }
}

suspend fun Anime.updateLocalCoverFromSourceFetch(
    source: AnimeSource,
    sourceAnime: SAnime,
    updateAnime: UpdateAnime = Injekt.get(),
    coverCache: CoverCache = Injekt.get(),
) {
    val generatedCover = sourceAnime.thumbnail_url?.takeIf { it.isNotBlank() } ?: return
    if (!source.isLocal() || generatedCover == thumbnailUrl) return

    updateAnime.awaitUpdateFromSource(this, sourceAnime, manualFetch = false, coverCache)
}

fun Anime.removeCovers(coverCache: AnimeCoverCache = Injekt.get()): Anime {
    if (isLocal()) return this
    return if (coverCache.deleteFromCache(this, true) > 0) {
        return copy(coverLastModified = Instant.now().toEpochMilli())
    } else {
        this
    }
}

fun Anime.removeBackgrounds(backgroundCache: AnimeBackgroundCache): Anime {
    if (isLocal()) return this
    return if (backgroundCache.deleteFromCache(this, true) > 0) {
        return copy(backgroundLastModified = Instant.now().toEpochMilli())
    } else {
        this
    }
}

suspend fun Anime.editCover(
    coverManager: LocalAnimeCoverManager,
    stream: InputStream,
    updateAnime: UpdateAnime = Injekt.get(),
    coverCache: AnimeCoverCache = Injekt.get(),
) {
    if (isLocal()) {
        coverManager.update(toSAnime(), stream)
        updateAnime.awaitUpdateCoverLastModified(id)
    } else if (favorite) {
        coverCache.setCustomCoverToCache(this, stream)
        updateAnime.awaitUpdateCoverLastModified(id)
    }
}

suspend fun Anime.editBackground(
    backgroundManager: LocalAnimeBackgroundManager,
    stream: InputStream,
    updateAnime: UpdateAnime = Injekt.get(),
    backgroundCache: AnimeBackgroundCache = Injekt.get(),
) {
    if (isLocal()) {
        backgroundManager.update(toSAnime(), stream)
        updateAnime.awaitUpdateBackgroundLastModified(id)
    } else if (favorite) {
        backgroundCache.setCustomBackgroundToCache(this, stream)
        updateAnime.awaitUpdateBackgroundLastModified(id)
    }
}

fun buildProgressString(lastSecondSeen: Long?, totalSeconds: Long?): String? {
    if (lastSecondSeen == null || totalSeconds == null || totalSeconds == 0L) return null
    val hours = lastSecondSeen / 3600
    val minutes = (lastSecondSeen % 3600) / 60
    val secs = lastSecondSeen % 60
    val current = if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
    val totalHours = totalSeconds / 3600
    val totalMinutes = (totalSeconds % 3600) / 60
    val totalSecs = totalSeconds % 60
    val total = if (totalHours > 0) "%d:%02d:%02d".format(totalHours, totalMinutes, totalSecs) else "%d:%02d".format(totalMinutes, totalSecs)
    return "$current/$total"
}
