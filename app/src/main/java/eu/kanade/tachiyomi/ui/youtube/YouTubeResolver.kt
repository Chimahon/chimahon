package eu.kanade.tachiyomi.ui.youtube

import android.net.Uri
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream

class YouTubeResolver {

    companion object {
        @Volatile
        private var initialized = false
        private val initLock = Any()
        private val resolveMutex = Mutex()
        private val client = OkHttpClient()

        private fun ensureInitialized() {
            if (initialized) return
            synchronized(initLock) {
                if (initialized) return
                NewPipe.init(object : Downloader() {
                    override fun execute(request: Request): Response {
                        val req = okhttp3.Request.Builder()
                            .url(request.url())
                            .method(
                                request.httpMethod(),
                                request.dataToSend()?.let { it.toRequestBody(null) },
                            )
                            .apply {
                                request.headers().forEach { (key, values) ->
                                    values.forEach { addHeader(key, it) }
                                }
                            }
                            .build()
                        val res = client.newCall(req).execute()
                        val body = res.body.string()
                        return Response(
                            res.code,
                            res.message,
                            res.headers.toMultimap(),
                            body,
                            request.url(),
                        )
                    }
                })
                initialized = true
            }
        }

        // Returns streams + video metadata
        suspend fun resolveVideo(videoId: String, preferredQuality: String = YouTubePreferences.DEFAULT_QUALITY):
            YouTubeVideoMetadata = resolveMutex.withLock {
            withContext(Dispatchers.IO) {
                ensureInitialized()
                val linkHandler = ServiceList.YouTube.getStreamLHFactory().fromId(videoId)
                val extractor = ServiceList.YouTube.getStreamExtractor(linkHandler)
                extractor.fetchPage()

                val subtitleTracks = listOf(
                    runCatching { extractor.subtitlesDefault }.getOrDefault(emptyList()),
                    runCatching { extractor.getSubtitles(MediaFormat.VTT) }.getOrDefault(emptyList()),
                    runCatching { extractor.getSubtitles(MediaFormat.SRT) }.getOrDefault(emptyList()),
                    runCatching { extractor.getSubtitles(MediaFormat.TTML) }.getOrDefault(emptyList()),
                )
                    .flatten()
                    .filter { it.content.isNotBlank() }
                    .map { sub ->
                        Track(
                            url = sub.content,
                            lang = sub.displayLanguageName
                                ?: sub.languageTag
                                ?: sub.locale?.displayLanguage
                                ?: "",
                        )
                    }
                    .distinctBy { it.url to it.lang }

                val audioTracks = runCatching {
                    extractor.audioStreams
                        .filter { it.content.isNotBlank() }
                        .sortedWith(
                            compareByDescending<AudioStream> {
                                it.audioTrackName?.contains("original", ignoreCase = true) == true ||
                                    it.audioLocale?.displayName?.contains("original", ignoreCase = true) == true ||
                                    it.quality?.contains("original", ignoreCase = true) == true
                            }.thenByDescending { it.averageBitrate },
                        )
                        .map { audio ->
                            Track(
                                url = audio.content,
                                lang = audio.audioTrackName
                                    ?: audio.audioLocale?.displayLanguage
                                    ?: audio.quality
                                    ?: "Audio",
                            )
                        }
                        .distinctBy { it.url to it.lang }
                }.getOrDefault(emptyList())

                val streams = (extractor.videoStreams + extractor.videoOnlyStreams)
                    .filter { it.content.isNotBlank() }
                    .sortedWith(
                        compareByDescending<VideoStream> {
                            parseResolution(it.getResolution().ifBlank { it.quality.orEmpty() })
                        }.thenBy { it.isVideoOnly() },
                    )
                    .distinctBy { stream ->
                        listOf(
                            parseResolution(stream.getResolution().ifBlank { stream.quality.orEmpty() }),
                            stream.content,
                        )
                    }

                val streamVideos = streams.mapNotNull { stream ->
                    val resolution = stream.getResolution().ifBlank { stream.quality ?: "Video" }
                    val needsExternalAudio = stream.isVideoOnly()

                    Video(
                        videoUrl = stream.content,
                        videoTitle = resolution,
                        resolution = parseResolution(resolution).takeIf { it > 0 },
                        subtitleTracks = subtitleTracks,
                        audioTracks = if (needsExternalAudio) audioTracks else emptyList(),
                        initialized = true,
                    ).apply { videoPageUrl = extractor.url }
                        .withoutExternalSubtitleLookup()

                }

                val videos = streamVideos.distinctBy { it.videoTitle to it.videoUrl }.toMutableList()

                // HLS fallback (mpv plays .m3u8 natively): add an adaptive entry when the
                // progressive/DASH ladder is thin, so users always have more qualities.
                val hlsUrl = runCatching { extractor.hlsUrl }.getOrNull()?.takeIf { it.isNotBlank() }
                if (hlsUrl != null) {
                    val maxRes = videos.maxOfOrNull { parseResolution(it.videoTitle) } ?: 0
                    if (videos.size < 3 || maxRes < 1080) {
                        videos += Video(
                            videoUrl = hlsUrl,
                            videoTitle = "Auto (HLS)",
                            resolution = null,
                            subtitleTracks = subtitleTracks,
                            audioTracks = emptyList(),
                            initialized = true,
                        ).apply { videoPageUrl = extractor.url }
                            .withoutExternalSubtitleLookup()
                    }
                }

                val preferred = selectPreferredVideo(videos, preferredQuality)
                val videosWithPreferred = videos.map { video ->
                    video.copy(preferred = video == preferred)
                }

                val streamInfo = StreamInfo.getInfo(extractor)
                val streamType = streamInfo.streamType

                val uploadTimestamp: Long = extractor.uploadDate
                    ?.offsetDateTime()
                    ?.toInstant()
                    ?.toEpochMilli()
                    ?: 0L

                val channelId = extractor.uploaderUrl.substringAfterLast("/")

                YouTubeVideoMetadata(
                    videoId = extractor.id,
                    videoName = extractor.name,
                    videoUrl = YouTubeSource.WATCH_PREFIX + extractor.id,

                    videoLength = extractor.length,
                    videoUploadDate = uploadTimestamp,
                    videoThumbnailUrl = runCatching {
                        extractor.thumbnails?.maxByOrNull { it.width }!!.url
                    }.getOrDefault(""),
                    videoType = when (streamType) {
                        StreamType.LIVE_STREAM,
                        StreamType.POST_LIVE_STREAM -> "Livestream"
                        StreamType.VIDEO_STREAM -> "Video"
                        StreamType.AUDIO_STREAM -> "Audio"
                        StreamType.AUDIO_LIVE_STREAM,
                        StreamType.POST_LIVE_AUDIO_STREAM -> "Audio livestream"
                        StreamType.NONE -> null
                        else -> null
                    },
                    videoDescription = extractor.description.content,
                    videoStreams = videosWithPreferred,

                    channelId = channelId,
                    channelName = extractor.uploaderName,
                    channelUrl = YouTubeSource.CHANNEL_PREFIX + channelId,
                )
            }
        }

        suspend fun resolveChannel(channelId: String): YouTubeChannelMetadata = resolveMutex.withLock {
                withContext(Dispatchers.IO) {
                    ensureInitialized()
                    val linkHandler = ServiceList.YouTube.channelLHFactory.fromId(channelId)
                    val extractor = ServiceList.YouTube.getChannelExtractor(linkHandler)
                    extractor.fetchPage()

                    YouTubeChannelMetadata(
                        id = extractor.id,
                        name = extractor.name,
                        url = YouTubeSource.CHANNEL_PREFIX + extractor.id,

                        description = extractor.description,
                        avatarUrl = runCatching {
                                extractor.avatars.maxByOrNull { it.width }?.url!!
                            }.getOrDefault(""),
                        bannerUrl = runCatching {
                                extractor.banners.maxByOrNull { it.width }?.url!!
                            }.getOrDefault(""),
                    )
                }
            }

        suspend fun resolveVideosFromTab(channelId: String, tab: String /* videos, shorts, livestreams */): List<YouTubeVideoItem> = resolveMutex.withLock {
            withContext(Dispatchers.IO) {
                ensureInitialized()
                val extractor = ServiceList.YouTube.getChannelTabExtractorFromId(channelId, tab)
                extractor.fetchPage()

                extractor.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .map { item ->
                        val uploadTimestamp: Long = item.uploadDate
                            ?.offsetDateTime()
                            ?.toInstant()
                            ?.toEpochMilli()
                            ?: 0L

                        val videoId = getVideoId(item.url)

                        YouTubeVideoItem(
                            id = videoId,
                            name = item.name,
                            url = YouTubeSource.WATCH_PREFIX + videoId,

                            duration = item.duration,
                            uploadDate = uploadTimestamp,
                            videoType = when (tab) {
                                "videos" -> "Video"
                                "shorts" -> "Short"
                                "livestreams" -> "Livestream"
                                else -> null
                            },
                            thumbnailUrl = runCatching {
                                    item.thumbnails?.maxByOrNull { it.width }!!.url
                                }.getOrDefault(""),
                            viewCount = item.viewCount,
                            shortDescription = item.shortDescription,
                        )
                    }
            }
        }

        fun getVideoId(videoUrl: String): String
        {
            val videoId = extractVideoId(videoUrl)
                ?: throw IllegalArgumentException("Could not find YouTube video id")

            return videoId
        }

        private fun extractVideoId(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isYouTubeVideoId()) return trimmed

            val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
            val host = uri.host.orEmpty().lowercase().removePrefix("www.")
            val pathSegments = uri.pathSegments.orEmpty()

            if (host.contains("youtu.be")) {
                pathSegments.firstOrNull()?.takeIf { it.isYouTubeVideoId() }?.let { return it }
            }

            if (host.contains("youtube.com") || host.contains("youtube-nocookie.com")) {
                when (pathSegments.firstOrNull()) {
                    "watch" -> uri.getQueryParameter("v")
                    "embed", "shorts", "live", "v" -> pathSegments.getOrNull(1)
                    else -> null
                }
                    ?.takeIf { it.isYouTubeVideoId() }
                    ?.let { return it }
            }

            return null
        }

        private fun String.isYouTubeVideoId(): Boolean {
            return length == 11 && all { it.isLetterOrDigit() || it == '_' || it == '-' }
        }

        fun parseResolution(res: String): Int {
            val normalized = res.lowercase()
            return when {
                "8k" in normalized -> 4320
                "4k" in normalized || "2160" in normalized -> 2160
                "highres" in normalized -> 1080
                else -> Regex("""(\d{3,4})\s*p?""")
                    .find(normalized)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                    ?: when {
                        // Legacy YouTube quality labels without digits
                        "medium" in normalized -> 480
                        "small" in normalized -> 240
                        else -> 0
                    }
            }
        }

        private fun selectPreferredVideo(
            videos: List<Video>,
            preferredQuality: String,
        ): Video? {
            if (videos.isEmpty()) return null

            val targetPixels = parseResolution(preferredQuality)
            val qualityVideos = videos.filter { parseResolution(it.videoTitle) > 0 }
            return qualityVideos.firstOrNull { video ->
                parseResolution(video.videoTitle) <= targetPixels
            } ?: qualityVideos.lastOrNull() ?: videos.first()
        }
    }
}
