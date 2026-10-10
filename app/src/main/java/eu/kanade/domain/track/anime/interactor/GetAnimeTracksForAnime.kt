package eu.kanade.domain.track.anime.interactor

import eu.kanade.domain.track.anime.model.toDomainTrack
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.jellyfin.Jellyfin
import eu.kanade.tachiyomi.data.track.model.AnimeTrackSearch
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack
import tachiyomi.domain.track.anime.model.AnimeTrack

/** Ordinary services share the parent's counter; Jellyfin identifies a particular season/movie. */
internal suspend fun GetAnimeTracks.awaitForAnime(
    animeId: Long,
    getAnime: GetAnime,
    insertTrack: InsertAnimeTrack,
): List<AnimeTrack> {
    val anime = getAnime.await(animeId) ?: return await(animeId)
    val parentId = anime.parentId ?: return await(animeId)
    val parentTracks = await(parentId)
    var ownTracks = await(animeId)
    var inheritedJellyfin = parentTracks.any { it.trackerId == TrackerManager.JELLYFIN }
    if (!inheritedJellyfin && ownTracks.none { it.trackerId == TrackerManager.JELLYFIN }) {
        // Collections can contain a series whose child tracks were never bound by an older build.
        var ancestorId = getAnime.await(parentId)?.parentId
        val visited = mutableSetOf(animeId, parentId)
        while (ancestorId != null && visited.add(ancestorId)) {
            if (await(ancestorId).any { it.trackerId == TrackerManager.JELLYFIN }) {
                inheritedJellyfin = true
                break
            }
            ancestorId = getAnime.await(ancestorId)?.parentId
        }
    }
    // Older bindings, and seasons fetched after tracking was enabled, may lack a child track.
    // Seed it without a network call so the first offline playback can still be queued.
    if (inheritedJellyfin &&
        ownTracks.none { it.trackerId == TrackerManager.JELLYFIN }
    ) {
        val track = AnimeTrackSearch.create(TrackerManager.JELLYFIN).apply {
            anime_id = animeId
            title = anime.title
            tracking_url = anime.url
            status = Jellyfin.UNSEEN
        }
        insertTrack.await(track.toDomainTrack(idRequired = false)!!)
        ownTracks = await(animeId)
    }
    return parentTracks.filter { it.trackerId != TrackerManager.JELLYFIN } +
        ownTracks.filter { it.trackerId == TrackerManager.JELLYFIN }
}
