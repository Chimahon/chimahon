package eu.kanade.tachiyomi.data.animedownload

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFprobeSession
import com.arthenica.ffmpegkit.Level
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.animesource.model.SerializableVideo.Companion.serialize
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.animedownload.model.AnimeDownload
import eu.kanade.tachiyomi.data.torrentServer.service.TorrentServerService
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.torrentServer.TorrentServerApi
import eu.kanade.tachiyomi.torrentServer.TorrentServerUtils
import eu.kanade.tachiyomi.source.isSourceForTorrents
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import eu.kanade.tachiyomi.ui.player.mining.isHlsLikeInput
import eu.kanade.tachiyomi.ui.player.isTorrentUrl
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.Request
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.episode.model.Episode
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.RandomAccessFile

class AnimeDownloader(
    private val context: Context,
    private val provider: AnimeDownloadProvider,
    private val cache: AnimeDownloadCache,
    private val animeSourceManager: AnimeSourceManager = Injekt.get(),
    private val networkHelper: NetworkHelper = Injekt.get(),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = AnimeDownloadStore(context)
    private val notifier by lazy { AnimeDownloadNotifier(context) }
    private val downloadClient by lazy {
        networkHelper.client.newBuilder()
            .callTimeout(java.time.Duration.ofMinutes(10))
            .readTimeout(java.time.Duration.ofMinutes(5))
            .build()
    }

    private val _queueState = MutableStateFlow<List<AnimeDownload>>(emptyList())
    private val _stateVersion = MutableStateFlow(0L)

    val queueState = _queueState.asStateFlow()
    val stateVersion = _stateVersion.asStateFlow()

    @Volatile
    private var currentFFmpegSession: FFmpegSession? = null

    private fun notifyQueueChanged() {
        _stateVersion.update { it + 1 }
    }

    private var downloaderJob: Job? = null

    val isRunning: Boolean
        get() = downloaderJob?.isActive == true

    init {
        scope.launchIO {
            val restored = store.restore()
            if (restored.isEmpty()) return@launchIO

            restored.forEach { it.status = AnimeDownload.State.QUEUE }
            addAllToQueue(restored)
        }
    }

    fun start(): Boolean {
        if (downloaderJob?.isActive == true) {
            logcat(LogPriority.INFO) { "AnimeDownloader: already running" }
            return true
        }

        if (_queueState.value.isEmpty() && !store.hasItems()) {
            logcat(LogPriority.INFO) { "AnimeDownloader: nothing to start (queue empty)" }
            return false
        }

        notifier.dismissPaused()

        _queueState.value
            .filter { it.status != AnimeDownload.State.DOWNLOADED }
            .forEach { it.status = AnimeDownload.State.QUEUE }
        notifyQueueChanged()

        logcat(LogPriority.INFO) { "AnimeDownloader: starting with ${_queueState.value.size} items" }
        downloaderJob = scope.launchIO {
            restoreAndProcess()
        }
        return true
    }

    private suspend fun restoreAndProcess() {
        if (_queueState.value.isEmpty()) {
            val restored = store.restore()
            if (restored.isEmpty()) return
            restored.forEach { it.status = AnimeDownload.State.QUEUE }
            addAllToQueue(restored)
        }
        processQueue()
    }

    fun stop(reason: String? = null) {
        cancelFFmpeg()
        downloaderJob?.cancel()
        downloaderJob = null

        _queueState.value
            .filter { it.status == AnimeDownload.State.DOWNLOADING }
            .forEach { it.status = AnimeDownload.State.ERROR }

        if (reason != null) {
            notifier.onWarning(reason)
            return
        }

        if (_queueState.value.isNotEmpty()) {
            notifier.onPaused()
        } else {
            notifier.onComplete()
        }

        AnimeDownloadJob.stop(context)
    }

    fun pause() {
        cancelFFmpeg()
        downloaderJob?.cancel()
        downloaderJob = null
        _queueState.value
            .filter { it.status == AnimeDownload.State.DOWNLOADING }
            .forEach { it.status = AnimeDownload.State.QUEUE }
        notifyQueueChanged()
    }

    fun clearQueue() {
        store.clear()
        _queueState.update { emptyList() }
        notifier.dismissProgress()
    }

    fun queueEpisodes(
        anime: Anime,
        episodes: List<Episode>,
        autoStart: Boolean,
        changeDownloader: Boolean = false,
        video: Video? = null,
    ) {
        val source = animeSourceManager.get(anime.source) as? AnimeHttpSource
        if (source == null) {
            logcat(LogPriority.ERROR) { "AnimeDownloader: source ${anime.source} not found or not AnimeHttpSource" }
            return
        }

        val episodesToQueue = episodes.asSequence()
            .filter { provider.findEpisodeDir(it.name, it.scanlator, anime.title, source) == null }
            .sortedByDescending { it.sourceOrder }
            .filter { episode -> _queueState.value.none { it.episode.id == episode.id } }
            .map { AnimeDownload(source, anime, it, video) }
            .toList()

        if (episodesToQueue.isEmpty()) {
            logcat(LogPriority.WARN) { "AnimeDownloader: nothing to queue (duplicate or already downloaded)" }
            return
        }

        episodesToQueue.forEach { it.status = AnimeDownload.State.QUEUE }
        addAllToQueue(episodesToQueue)
        logcat(LogPriority.INFO) { "AnimeDownloader: queued ${episodesToQueue.size} episode(s)" }

        if (autoStart) {
            AnimeDownloadJob.start(context)
        }
    }

    fun removeFromQueue(episodes: List<Episode>) {
        val episodeIds = episodes.map { it.id }.toSet()
        val removed = _queueState.value.filter { it.episode.id in episodeIds }
        if (removed.isEmpty()) return

        store.removeAll(removed)
        removed.forEach { download ->
            if (download.status == AnimeDownload.State.DOWNLOADING || download.status == AnimeDownload.State.QUEUE) {
                download.status = AnimeDownload.State.NOT_DOWNLOADED
            }
        }
        _queueState.update { queue -> queue.filter { it.episode.id !in episodeIds } }
        notifyQueueChanged()
        if (_queueState.value.isEmpty()) {
            notifier.dismissProgress()
        }
    }

    fun removeFromQueue(anime: Anime) {
        val removed = _queueState.value.filter { it.anime.id == anime.id }
        if (removed.isEmpty()) return

        store.removeAll(removed)
        removed.forEach { download ->
            if (download.status == AnimeDownload.State.DOWNLOADING || download.status == AnimeDownload.State.QUEUE) {
                download.status = AnimeDownload.State.NOT_DOWNLOADED
            }
        }
        _queueState.update { queue -> queue.filter { it.anime.id != anime.id } }
        notifyQueueChanged()
        if (_queueState.value.isEmpty()) {
            notifier.dismissProgress()
        }
    }

    private fun addAllToQueue(downloads: List<AnimeDownload>) {
        if (downloads.isEmpty()) return
        store.addAll(downloads)
        _queueState.update { it + downloads }
        notifyQueueChanged()
    }

    private fun internalClearQueue() {
        store.clear()
        _queueState.update { emptyList() }
    }

    fun updateQueue(downloads: List<AnimeDownload>) {
        if (_queueState.value == downloads) return
        if (downloads.isEmpty()) {
            clearQueue()
            stop()
            return
        }
        val wasRunning = isRunning
        pause()
        internalClearQueue()
        addAllToQueue(downloads)
        if (wasRunning) {
            scope.launch { start() }
        }
    }

    private fun removeFromQueueState(download: AnimeDownload) {
        _queueState.update { queue -> queue.filter { it.episode.id != download.episode.id } }
    }

    private suspend fun processQueue() {
        logcat(LogPriority.INFO) { "AnimeDownloader: processQueue started" }
        supervisorScope {
            while (true) {
                val download = _queueState.value.firstOrNull { it.status == AnimeDownload.State.QUEUE }
                    ?: break

                logcat(LogPriority.INFO) { "AnimeDownloader: downloading ${download.episode.name}" }
                try {
                    downloadEpisode(download)
                    if (download.status == AnimeDownload.State.DOWNLOADED) {
                        store.remove(download)
                        removeFromQueueState(download)
                        notifyQueueChanged()
                        logcat(LogPriority.INFO) { "AnimeDownloader: completed ${download.episode.name}" }
                    } else if (download.status == AnimeDownload.State.ERROR) {
                        notifyQueueChanged()
                        logcat(LogPriority.WARN) { "AnimeDownloader: kept failed download in queue: ${download.episode.name}" }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to download ${download.episode.name}" }
                    download.status = AnimeDownload.State.ERROR
                    notifyQueueChanged()
                    notifier.onError(e.message, download.episode.name, download.anime.title, download.anime.id)
                }
            }
        }

        if (_queueState.value.any { it.status == AnimeDownload.State.ERROR }) {
            notifier.onPaused()
        } else {
            notifier.onComplete()
        }
    }

    private suspend fun downloadEpisode(download: AnimeDownload) {
        if (download.video == null) {
            val hosters = EpisodeLoader.getHosters(download.episode, download.anime, download.source)
            if (hosters.isEmpty()) {
                throw IllegalStateException("No hosters found for ${download.episode.name}")
            }
            val bestVideo = HosterLoader.getBestVideo(download.source, hosters)
                ?: throw IllegalStateException("No videos found for ${download.episode.name}")
            download.video = bestVideo
        }

        val video = download.video
            ?: throw IllegalStateException("Could not resolve video for episode ${download.episode.name}")

        val videoUrl = video.videoUrl
        if (videoUrl.isBlank()) {
            throw IllegalStateException("Video URL is blank for episode ${download.episode.name}")
        }

        download.status = AnimeDownload.State.DOWNLOADING
        notifyQueueChanged()
        notifier.onProgressChange(download)

        val animeDir = provider.getAnimeDir(download.anime.title, download.source)

        val availSpace = DiskUtil.getAvailableStorageSpace(animeDir)
        if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
            throw IllegalStateException("Insufficient storage space (${availSpace / 1024 / 1024}MB free, ${MIN_DISK_SPACE / 1024 / 1024}MB required)")
        }

        val episodeDirName = provider.getEpisodeDirName(download.episode.name, download.episode.scanlator)
        val tmpDirName = episodeDirName + AnimeDownloadProvider.TMP_DIR_SUFFIX
        val tmpDir = animeDir.createDirectory(tmpDirName)
            ?: throw IllegalStateException("Failed to create temp directory")

        try {
            DiskUtil.createNoMediaFile(tmpDir, context)

            if (isTorrentUrl(videoUrl)) {
                resolveTorrentVideo(video)
            }

            val resolvedVideo = resolveExternalTracks(video)

            when {
                resolvedVideo.canUseDirectHttpDownload() -> {
                    try {
                        downloadVideoFile(resolvedVideo, tmpDir, download)
                    } catch (_: HlsStreamRequiresFFmpegException) {
                        ffmpegDownloadVideo(resolvedVideo, tmpDir, download)
                    }
                }
                resolvedVideo.videoUrl.startsWith("http") -> {
                    ffmpegDownloadVideo(resolvedVideo, tmpDir, download)
                }
                else -> {
                    downloadVideoFile(resolvedVideo, tmpDir, download)
                }
            }

            writeMetadata(video, tmpDir)

            if (!isDownloadSuccessful(tmpDir)) {
                throw IllegalStateException("Downloaded video file not found")
            }

            val finalDir = animeDir.findFile(episodeDirName)
            finalDir?.delete()
            tmpDir.renameTo(episodeDirName)

            download.status = AnimeDownload.State.DOWNLOADED
            download.progress = 100
            cache.addEpisode(episodeDirName, animeDir, download.anime)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to download ${download.episode.name}" }
            download.status = AnimeDownload.State.ERROR
            notifyQueueChanged()
            notifier.onError(e.message, download.episode.name, download.anime.title, download.anime.id)
        }
    }

    private fun resolveTorrentVideo(video: Video) {
        TorrentServerService.start()
        TorrentServerService.wait(10)
        var index = 0
        if (video.videoUrl.contains("index=")) {
            index = try {
                video.videoUrl.substringAfter("index=").substringBefore("&").toInt()
            } catch (_: Exception) {
                0
            }
        }
        val torrent = TorrentServerApi.addTorrent(video.videoUrl, video.videoTitle, "", "", false)
        video.videoUrl = TorrentServerUtils.getTorrentPlayLink(torrent, index)
    }

    private suspend fun ffmpegDownloadVideo(video: Video, tmpDir: UniFile, download: AnimeDownload) {
        withContext(Dispatchers.IO) {
            val filename = DiskUtil.buildValidFilename(download.episode.name)
            // Plain cache path: ffkitsaf output handles don't survive across retry sessions
            // ("SAF id not found" on attempt 2+), so mux here and copy into place afterwards.
            val tmpFile = File(context.cacheDir, "anime_dl_${download.episode.id}.mkv")
            try {
                tmpFile.delete()
                val headers = video.headers ?: download.source.headers ?: Headers.Builder().build()
                val headerOptions = headers.joinToString("", "-headers '", "'") {
                    "${it.first}: ${it.second.replace("'", "'\\''")}\r\n"
                }

                val ffmpegOptions = buildFFmpegOptions(video, headerOptions, tmpFile.absolutePath)

                val ffprobeCommand = buildFFprobeDurationCommand(video, headers)
                val inputDuration = getDuration(ffprobeCommand) ?: 0F
                val duration = inputDuration.toLong().coerceAtLeast(1L)

                retryWithBackoff(maxRetries = 3, initialDelay = 2000L) { attempt ->
                    var lastNotifyTime = 0L
                    val statCallback = StatisticsCallback { stats ->
                        val outTime = (stats.time / 1000.0).toLong()
                        if (outTime > 0) {
                            download.progress = ((100 * outTime) / duration).toInt().coerceIn(0, 100)
                            val now = System.currentTimeMillis()
                            if (now - lastNotifyTime >= 1000) {
                                lastNotifyTime = now
                                notifier.onProgressChange(download)
                                notifyQueueChanged()
                            }
                        }
                    }

                    val logCallback = LogCallback { log ->
                        if (log.level <= Level.AV_LOG_WARNING) {
                            log.message?.let { logcat(LogPriority.ERROR) { it } }
                        }
                    }

                    suspendCancellableCoroutine<Unit> { cont ->
                        val session = FFmpegKit.executeWithArgumentsAsync(
                            ffmpegOptions,
                            { returnedSession ->
                                currentFFmpegSession = null
                                if (ReturnCode.isSuccess(returnedSession.getReturnCode())) {
                                    cont.resume(Unit)
                                } else {
                                    val detail = buildFFmpegFailureMessage(
                                        exitCode = returnedSession.getReturnCode().toString(),
                                        failStackTrace = returnedSession.getFailStackTrace(),
                                        logs = returnedSession.getAllLogsAsString(),
                                    )
                                    cont.resumeWithException(Exception(detail))
                                }
                            },
                            logCallback,
                            statCallback,
                        )
                        currentFFmpegSession = session

                        cont.invokeOnCancellation {
                            session.cancel()
                            currentFFmpegSession = null
                        }
                    }
                }

                tmpDir.findFile("$filename.tmp")?.delete()
                val videoFile = tmpDir.createFile("$filename.tmp")
                    ?: throw IllegalStateException("Failed to create temp video file")
                videoFile.openOutputStream().use { output ->
                    tmpFile.inputStream().use { input ->
                        input.copyTo(output, bufferSize = 256 * 1024)
                    }
                }
                tmpDir.findFile("$filename.tmp")?.apply {
                    renameTo("$filename.mkv")
                } ?: throw Exception("Downloaded file not found")
            } finally {
                tmpFile.delete()
            }
        }
    }

    private suspend fun <T> retryWithBackoff(
        maxRetries: Int = 3,
        initialDelay: Long = 2000L,
        block: suspend (attempt: Int) -> T,
    ): T {
        repeat(maxRetries) { attempt ->
            try {
                return block(attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt == maxRetries - 1) throw e
                logcat(LogPriority.WARN, e) { "FFmpeg attempt ${attempt + 1} failed, retrying..." }
                delay(initialDelay * (1L shl attempt))
            }
        }
        throw IllegalStateException("Unreachable")
    }

    private fun buildFFmpegOptions(video: Video, headerOptions: String, ffmpegFilename: String): Array<String> {
        val streamOptions = video.ffmpegStreamArgs.joinToString(" ") { (key, value) -> "-$key \"$value\"" }

        fun formatInputs(tracks: List<Track>) = tracks.joinToString(" ", postfix = " ") {
            buildList {
                if (it.url.startsWith("http")) {
                    if (headerOptions.isNotBlank()) add(headerOptions)
                }
                add("-i")
                add("\"${it.url}\"")
            }.joinToString(" ")
        }

        fun formatMaps(tracks: List<Track>, type: String, offset: Int = 0) = tracks.indices.joinToString(" ") {
            "-map ${it + 1 + offset}:$type"
        }

        fun formatMetadata(tracks: List<Track>, type: String) = tracks.mapIndexed { i, track ->
            "-metadata:s:$type:$i \"title=${track.lang}\""
        }.joinToString(" ")

        val subtitleInputs = formatInputs(video.subtitleTracks)
        val subtitleMaps = formatMaps(video.subtitleTracks, "s")
        val subtitleMetadata = formatMetadata(video.subtitleTracks, "s")

        val audioInputs = formatInputs(video.audioTracks)
        val audioMaps = formatMaps(video.audioTracks, "a", video.subtitleTracks.size)
        val audioMetadata = formatMetadata(video.audioTracks, "a")

        val videoInput = buildList {
            if (video.videoUrl.startsWith("http")) {
                if (headerOptions.isNotBlank()) add(headerOptions)
                if (streamOptions.isNotBlank()) add(streamOptions)
                // HLS-only: extension proxies serve extensionless segment URLs, which this
                // FFmpeg build rejects under its default allowed_segment_extensions list.
                // discardcorrupt drops the mangled/ad-junk packets proxies emit instead of
                // letting them abort the copy-mux at the bitstream filter.
                if (isHlsLikeInput(video.videoUrl)) {
                    add("-allowed_extensions ALL -allowed_segment_extensions ALL -extension_picky 0")
                    add("-fflags +discardcorrupt")
                }
            }
            add("-i")
            add("\"${video.videoUrl}\"")
        }.joinToString(" ")

        val videoOptions = video.ffmpegVideoArgs.joinToString(" ") { (key, value) -> "-$key \"$value\"" }

        val command = listOf(
            videoInput, subtitleInputs, audioInputs,
            "-map 0:v", audioMaps, "-map 0:a?", subtitleMaps, "-map 0:s? -map 0:t?",
            "-f matroska -c:a copy -c:v copy -c:s copy",
            subtitleMetadata, audioMetadata,
            videoOptions,
            "\"$ffmpegFilename\" -y",
        )
            .filter(String::isNotBlank)
            .joinToString(" ")

        return FFmpegKitConfig.parseArguments(command)
    }

    private suspend fun getDuration(ffprobeCommand: Array<String>): Float? {
        return try {
            suspendCancellableCoroutine<FFprobeSession?> { continuation ->
                val session = FFprobeKit.executeWithArgumentsAsync(ffprobeCommand) {
                    if (ReturnCode.isSuccess(it.getReturnCode())) {
                        continuation.resume(it)
                    } else {
                        // Best-effort only: a duration check must never fail the download.
                        val err = it.getAllLogsAsString().trim()
                        logcat(LogPriority.ERROR) { "ffprobe failed: $err" }
                        continuation.resume(null)
                    }
                }
                continuation.invokeOnCancellation { session.cancel() }
            }?.getAllLogsAsString()?.trim()?.toFloatOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "ffprobe duration check failed, continuing without duration" }
            null
        }
    }

    private fun buildFFprobeDurationCommand(video: Video, headers: Headers): Array<String> = buildList {
        if (headers.size > 0) {
            add("-headers")
            add(headers.joinToString("") { "${it.first}: ${it.second}\r\n" })
        }
        add("-rw_timeout"); add("15000000")
        if (isHlsLikeInput(video.videoUrl)) {
            add("-allowed_extensions"); add("ALL"); add("-allowed_segment_extensions"); add("ALL"); add("-extension_picky"); add("0")
        }
        add("-v"); add("error")
        add("-show_entries"); add("format=duration")
        add("-of"); add("default=noprint_wrappers=1:nokey=1")
        add(video.videoUrl)
    }.toTypedArray()

    private fun cancelFFmpeg() {
        currentFFmpegSession?.cancel()
        currentFFmpegSession = null
    }

    private suspend fun downloadVideoFile(video: Video, tmpDir: UniFile, download: AnimeDownload) {
        withContext(Dispatchers.IO) {
            val extension = guessVideoExtension(video.videoUrl)
            val videoFileName = "video.$extension"
            val tmpFile = File(context.cacheDir, "anime_dl_${download.episode.id}.$extension")

            try {
                val headers = video.headers
                    ?: download.source.headers
                    ?: Headers.Builder().build()

                val progressListener = object : ProgressListener {
                    private var lastNotify = 0L
                    override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                        download.downloadedBytes = bytesRead
                        if (download.totalBytes <= 0 && contentLength > 0) {
                            download.totalBytes = contentLength
                        }
                        val total = if (contentLength > 0) contentLength else download.totalBytes
                        if (total > 0) {
                            download.progress = ((bytesRead * 100) / total).toInt().coerceIn(0, 99)
                            val now = System.currentTimeMillis()
                            if (now - lastNotify > 1000) {
                                lastNotify = now
                                notifier.onProgressChange(download)
                                notifyQueueChanged()
                            }
                        }
                    }
                }

                downloadWithResume(video.videoUrl, headers, tmpFile, progressListener)

                val videoFile = tmpDir.createFile(videoFileName)
                    ?: throw IllegalStateException("Failed to create video file in episode dir")
                videoFile.openOutputStream().use { output ->
                    tmpFile.inputStream().use { input ->
                        input.copyTo(output, bufferSize = 256 * 1024)
                    }
                }
            } finally {
                tmpFile.delete()
            }
        }
    }

    private suspend fun downloadWithResume(url: String, headers: Headers, outputFile: File, progressListener: ProgressListener) {
        var attempt = 0
        val maxRetries = 3

        while (attempt < maxRetries) {
            try {
                val downloadedBytes = outputFile.length()

                val requestHeaders = headers.newBuilder()
                    .set("Range", "bytes=$downloadedBytes-")
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .headers(requestHeaders)
                    .build()

                downloadClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == 206) {
                        val contentType = response.body.contentType()?.toString().orEmpty()
                        if (contentType.isHlsContentType()) {
                            throw HlsStreamRequiresFFmpegException()
                        }
                        if (response.code == 200 && downloadedBytes > 0) {
                            outputFile.delete()
                        }

                        val body = response.body
                        val rawContentLength = body.contentLength()
                        val contentLength = if (rawContentLength >= 0) {
                            rawContentLength + (if (response.code == 206) downloadedBytes else 0)
                        } else {
                            -1L
                        }

                        RandomAccessFile(outputFile, "rw").use { raf ->
                            val startPos = if (response.code == 206) downloadedBytes else 0
                            raf.seek(startPos)

                            val buffer = ByteArray(8192)
                            var totalRead = startPos
                            body.byteStream().use { stream ->
                                var read: Int
                                while (stream.read(buffer).also { read = it } != -1) {
                                    raf.write(buffer, 0, read)
                                    totalRead += read
                                    progressListener.update(totalRead, contentLength, false)
                                }
                            }
                        }

                        progressListener.update(contentLength, contentLength, true)
                        return
                    } else if (response.code == 416) {
                        outputFile.delete()
                        attempt++
                    } else {
                        attempt++
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HlsStreamRequiresFFmpegException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Download attempt $attempt failed" }
                attempt++
                if (attempt < maxRetries) {
                    delay((1000L * (1 shl attempt)).coerceAtMost(8000))
                }
            }
        }

        throw java.io.IOException("Failed to download video after $maxRetries attempts")
    }

    private suspend fun resolveExternalTracks(video: Video): Video {
        if (video.subtitleTracks.isEmpty()) return video

        val headers = video.headers ?: Headers.Builder().build()
        val tracksCacheDir = File(context.cacheDir, "anime_tracks").also { it.mkdirs() }
        tracksCacheDir.listFiles()?.forEach { it.delete() }

        val resolvedSubtitles = withContext(Dispatchers.IO) {
            supervisorScope {
                video.subtitleTracks.map { track ->
                    async {
                        downloadTrackFile(track, tracksCacheDir, headers)
                    }
                }.awaitAll().filterNotNull()
            }
        }

        return video.copy(subtitleTracks = resolvedSubtitles)
    }

    private suspend fun downloadTrackFile(track: Track, tracksCacheDir: File, headers: Headers): Track? {
        return try {
            val ext = subtitleExtensionFromUrl(track.url)
            val fileName = "${track.lang}.$ext"
            val localFile = File(tracksCacheDir, fileName)

            val request = Request.Builder()
                .url(track.url)
                .headers(headers)
                .build()

            networkHelper.client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body.byteStream().use { input ->
                        localFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Track(localFile.absolutePath, track.lang)
                } else {
                    logcat(LogPriority.WARN) { "Track ${track.lang} returned ${response.code}, skipping" }
                    null
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to download track: ${track.lang}, skipping" }
            null
        }
    }

    private fun writeMetadata(video: Video, tmpDir: UniFile) {
        val metadata = listOf(video).serialize()
        val metadataFile = tmpDir.createFile("metadata.json") ?: return
        metadataFile.openOutputStream().use { output ->
            output.write(metadata.toByteArray(Charsets.UTF_8))
        }
    }

    private fun isDownloadSuccessful(tmpDir: UniFile): Boolean {
        val downloadedVideos = tmpDir.listFiles().orEmpty()
            .filter { file ->
                val name = file.name.orEmpty()
                file.isFile &&
                    !name.endsWith(".tmp") &&
                    (
                        file.type.orEmpty().startsWith("video/") ||
                            name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
                        )
            }

        return downloadedVideos.size == 1
    }

    private fun guessVideoExtension(url: String): String {
        val path = url.substringBefore('?').substringAfterLast('/')
        val ext = path.substringAfterLast('.', "").lowercase().take(5)
        return when {
            ext in VIDEO_EXTENSIONS -> ext
            else -> "mp4"
        }
    }

}

// Video files are much larger than manga pages — require 500 MB free
private fun Video.canUseDirectHttpDownload(): Boolean {
    return videoUrl.startsWith("http") &&
        !videoUrl.contains("m3u8", ignoreCase = true) &&
        subtitleTracks.isEmpty() &&
        audioTracks.isEmpty() &&
        ffmpegStreamArgs.isEmpty() &&
        ffmpegVideoArgs.isEmpty()
}

private fun String.isHlsContentType(): Boolean {
    return contains("mpegurl", ignoreCase = true) ||
        contains("vnd.apple", ignoreCase = true)
}

private class HlsStreamRequiresFFmpegException : Exception()

internal fun buildFFmpegFailureMessage(
    exitCode: String,
    failStackTrace: String?,
    logs: String?,
): String {
    val details = sequenceOf(failStackTrace, logs)
        .filterNotNull()
        .flatMap { it.lineSequence() }
        .map(String::trim)
        .filter(String::isNotBlank)
        .filterNot(String::isFFmpegBannerLine)
        .toList()
        .takeLast(8)

    return buildString {
        append("FFmpeg exit code: $exitCode")
        if (details.isNotEmpty()) {
            append('\n')
            append(details.joinToString("\n"))
        }
    }
}

private fun String.isFFmpegBannerLine(): Boolean {
    val normalized = lowercase()
    return normalized.startsWith("ffmpeg version") ||
        normalized.startsWith("built with") ||
        normalized.startsWith("configuration:") ||
        normalized.startsWith("libav") ||
        normalized.startsWith("libsw") ||
        normalized.startsWith("libpostproc")
}

private const val MIN_DISK_SPACE = 500L * 1024 * 1024

private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "m4v", "ts")

internal fun subtitleExtensionFromUrl(url: String): String =
    url.substringAfterLast('.').substringBefore('?').take(5).ifBlank { "srt" }
