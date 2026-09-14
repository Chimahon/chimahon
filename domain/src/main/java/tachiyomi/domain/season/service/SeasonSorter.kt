package tachiyomi.domain.season.service

import tachiyomi.core.common.util.lang.compareToWithCollator
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.SeasonAnime

val seasonSortAlphabetically: (SeasonAnime, SeasonAnime) -> Int = { i1, i2 ->
    i1.anime.title.lowercase().compareToWithCollator(i2.anime.title.lowercase())
}

/**
 * Season sort comparator ported from Anikku (komikku-app/anikku),
 * adapted to Chimahon's [Anime] season flag constants.
 *
 * NOTE: Chimahon stores the sorting mode with [Anime.SEASON_SORT_MASK] while the
 * UI offers [Anime.SEASON_SORTING_SOURCE]..[Anime.SEASON_SORTING_EP_FETCH_DATE].
 * Unknown values fall back to source order.
 */
fun getSeasonSortComparator(anime: Anime): Comparator<SeasonAnime> = Comparator { s1, s2 ->
    when (anime.seasonSorting) {
        Anime.SEASON_SORTING_NUMBER -> {
            s1.anime.seasonNumber.compareTo(s2.anime.seasonNumber)
        }
        Anime.SEASON_SORTING_UPLOAD_DATE -> {
            s1.latestUpload.compareTo(s2.latestUpload)
        }
        Anime.SEASON_SORTING_ALPHABET -> {
            seasonSortAlphabetically(s1, s2)
        }
        Anime.SEASON_SORTING_UNSEEN -> {
            s1.unseenCount.compareTo(s2.unseenCount)
        }
        Anime.SEASON_SORTING_LAST_SEEN -> {
            s1.lastSeen.compareTo(s2.lastSeen)
        }
        Anime.SEASON_SORTING_EP_FETCH_DATE -> {
            s1.fetchedAt.compareTo(s2.fetchedAt)
        }
        else -> {
            s1.anime.seasonSourceOrder.compareTo(s2.anime.seasonSourceOrder)
        }
    }
}
