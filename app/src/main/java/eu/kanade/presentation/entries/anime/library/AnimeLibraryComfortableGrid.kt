package eu.kanade.presentation.entries.anime.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.util.fastAny
import eu.kanade.presentation.library.components.AnimeComfortableGridItem
import eu.kanade.presentation.library.components.DownloadsBadge
import eu.kanade.presentation.library.components.LanguageBadge
import eu.kanade.presentation.library.components.LazyLibraryGrid
import eu.kanade.presentation.library.components.UnviewedBadge
import eu.kanade.presentation.library.components.globalSearchItem
import eu.kanade.tachiyomi.ui.entries.anime.library.AnimeLibraryItem
import tachiyomi.domain.entries.anime.model.AnimeCover
import tachiyomi.domain.library.model.LibraryAnime
import tachiyomi.domain.library.service.AnimeLibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun AnimeLibraryComfortableGrid(
    items: List<AnimeLibraryItem>,
    columns: Int,
    contentPadding: PaddingValues,
    selection: List<LibraryAnime>,
    onClick: (LibraryAnime) -> Unit,
    onLongClick: (LibraryAnime) -> Unit,
    onClickContinueWatching: ((LibraryAnime) -> Unit)?,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    usePanoramaCover: Boolean = false,
) {
    val badgePrefs = remember { Injekt.get<AnimeLibraryPreferences>() }
    val showDownloadBadge by remember { badgePrefs.downloadBadge().changes() }.collectAsState(badgePrefs.downloadBadge().get())
    val showUnseenBadge by remember { badgePrefs.unseenBadge().changes() }.collectAsState(badgePrefs.unseenBadge().get())
    val showLocalBadge by remember { badgePrefs.localBadge().changes() }.collectAsState(badgePrefs.localBadge().get())
    val showLanguageBadge by remember { badgePrefs.languageBadge().changes() }.collectAsState(badgePrefs.languageBadge().get())

    LazyLibraryGrid(
        modifier = Modifier.fillMaxSize(),
        columns = columns,
        contentPadding = contentPadding,
    ) {
        globalSearchItem(searchQuery, onGlobalSearchClicked)

        items(
            items = items,
            contentType = { "anime_library_comfortable_grid_item" },
        ) { libraryItem ->
            val anime = libraryItem.libraryAnime.anime
            AnimeComfortableGridItem(
                isSelected = selection.fastAny { it.id == libraryItem.libraryAnime.id },
                title = anime.title,
                coverData = AnimeCover(
                    animeId = anime.id,
                    sourceId = anime.source,
                    isAnimeFavorite = anime.favorite,
                    ogUrl = anime.thumbnailUrl,
                    lastModified = anime.coverLastModified,
                ),
                coverBadgeStart = {
                    if (showDownloadBadge) DownloadsBadge(count = libraryItem.downloadCount)
                    if (showUnseenBadge) UnviewedBadge(count = libraryItem.unseenCount)
                },
                coverBadgeEnd = {
                    if (showLocalBadge) AnimeSourceIconBadge(source = libraryItem.source)
                    if (showLanguageBadge) {
                        LanguageBadge(
                            isLocal = libraryItem.isLocal,
                            sourceLanguage = libraryItem.sourceLanguage,
                        )
                    }
                },
                onLongClick = { onLongClick(libraryItem.libraryAnime) },
                onClick = { onClick(libraryItem.libraryAnime) },
                onClickContinueWatching = if (onClickContinueWatching != null && libraryItem.unseenCount > 0) {
                    { onClickContinueWatching(libraryItem.libraryAnime) }
                } else {
                    null
                },
                usePanoramaCover = usePanoramaCover,
                fitToPanoramaCover = true,
            )
        }
    }
}
