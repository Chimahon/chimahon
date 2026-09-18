package eu.kanade.tachiyomi.ui.youtube

import eu.kanade.tachiyomi.animesource.model.Video

private const val DISABLE_EXTERNAL_SUBTITLE_LOOKUP = "externalSubtitleLookup=false"

fun Video.withoutExternalSubtitleLookup(): Video {
    if (!allowsExternalSubtitleLookup()) return this
    val metadata = internalData
        .lineSequence()
        .filter { it.isNotBlank() }
        .plus(DISABLE_EXTERNAL_SUBTITLE_LOOKUP)
        .joinToString("\n")

    return copy(
        internalData = metadata,
        initialized = initialized,
    ).apply { videoPageUrl = this@withoutExternalSubtitleLookup.videoPageUrl }
}

fun Video.allowsExternalSubtitleLookup(): Boolean {
    return internalData
        .lineSequence()
        .none { it.trim().equals(DISABLE_EXTERNAL_SUBTITLE_LOOKUP, ignoreCase = true) }
}

data class YouTubeVideoMetadata
(
    // Video
    var videoId: String,
    var videoName: String,
    var videoUrl: String,

    var videoLength: Long,
    var videoUploadDate: Long,
    val videoType: String?, /* video, short, livestream */
    var videoThumbnailUrl: String,
    var videoDescription: String,
    var videoStreams: List<Video>,

    // Channel
    var channelId: String,
    var channelName: String,
    var channelUrl: String,
)

data class YouTubeChannelMetadata
(
    var id: String,
    var name: String,
    var url: String,

    var description: String,
    var avatarUrl: String,
    var bannerUrl: String,
)

data class YouTubeVideoItem(
    var id: String,
    val name: String,
    val url: String,

    val duration: Long,
    val uploadDate: Long,
    val videoType: String?, /* video, short, livestream */
    val thumbnailUrl: String,
    val shortDescription: String?,
    val viewCount: Long,
)
