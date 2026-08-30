package eu.kanade.tachiyomi.data.track.anilist.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ALMediaListCollectionResult(
    val data: ALMediaListCollectionData,
)

@Serializable
data class ALMediaListCollectionData(
    @SerialName("MediaListCollection")
    val mediaListCollection: ALMediaListCollection,
)

@Serializable
data class ALMediaListCollection(
    val lists: List<ALMediaListGroup> = emptyList(),
)

@Serializable
data class ALMediaListGroup(
    val entries: List<ALMediaListEntry> = emptyList(),
)

@Serializable
data class ALMediaListEntry(
    val id: Long,
    val status: String,
    val progress: Int,
    val media: ALMediaListEntryMedia,
)

@Serializable
data class ALMediaListEntryMedia(
    val id: Long,
    val title: ALItemTitle,
)
