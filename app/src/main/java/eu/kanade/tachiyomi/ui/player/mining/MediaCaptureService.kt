package eu.kanade.tachiyomi.ui.player.mining

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import chimahon.anki.AnkiMediaRequest
import chimahon.anki.AnkiSentenceAudioPreparation
import chimahon.anki.LazyAnkiSentenceAudioProvider
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.torrentServer.TorrentServerUtils
import eu.kanade.tachiyomi.util.storage.toFFmpegReadString
import `is`.xyz.mpv.MPVLib
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * Captures media from the player for Anki mining: still OCR frames, sentence audio slices,
 * and animated AVIF scenes encoded through the bundled ffmpeg libsvtav1 encoder.
 */
internal class MediaCaptureService(
    private val context: Context,
    private val cachePath: String,
    private val getVideo: () -> Video?,
    private val getSource: () -> AnimeSource?,
    private val getTimeSeconds: () -> Double,
    private val getOcrPaddingSeconds: () -> Double,
    private val readMpvSnapshot: () -> SentenceAudioMpvSnapshot,
    private val prepareSentenceAudioOverride: (suspend (SentenceAudioCaptureRequest) -> AnkiSentenceAudioPreparation)? = null,
) {

    private val sentenceAudioCaptureService by lazy {
        SentenceAudioCaptureService(
            File(cachePath),
            AndroidSentenceAudioInputAcquirer(context),
            diagnosticLogger = createSentenceAudioDiagnosticLogger(),
        )
    }

    suspend fun captureVideoFrameForOcr(): Bitmap? = captureFrameViaFfmpeg(getTimeSeconds())

    /** FFmpeg fallback for the screenshot sheet when mpv can't write images; returns a JPEG stream. */
    suspend fun captureScreenshotStreamViaFfmpeg(): InputStream? {
        val bitmap = captureFrameViaFfmpeg(getTimeSeconds()) ?: return null
        return withIOContext {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                ByteArrayInputStream(out.toByteArray())
            }
        }
    }

    /** Input FFmpeg reads plus the HTTP headers to forward for the video mpv is currently playing. */
    private class FfmpegSource(val input: String, val headers: List<Pair<String, String>>)

    private fun ffmpegInput(): FfmpegSource? {
        val video = getVideo() ?: return null
        val rawInput = MPVLib.getPropertyString("path")
            ?.takeIf { it.isNotBlank() }
            ?: video.videoUrl
        val input = when {
            video.videoUrl.startsWith("content://") -> Uri.parse(video.videoUrl).toFFmpegReadString(context)
            rawInput.startsWith("file://") -> Uri.parse(rawInput).path ?: rawInput
            else -> rawInput
        }
        val source = getSource() as? AnimeHttpSource
        val headers = (video.headers ?: source?.headers)?.toList().orEmpty()
        return FfmpegSource(input, if (input.startsWith("http")) headers else emptyList())
    }

    private fun MutableList<String>.addInputOptions(source: FfmpegSource) {
        if (source.headers.isNotEmpty()) {
            add("-headers"); add(source.headers.joinToString("") { "${it.first}: ${it.second}\r\n" })
        }
        // HLS-only (see isHlsLikeInput): ffmpeg aborts other inputs if these are present.
        if (isHlsLikeInput(source.input)) {
            add("-allowed_extensions"); add("ALL")
            add("-allowed_segment_extensions"); add("ALL")
            add("-extension_picky"); add("0")
        }
    }

    /** Extracts a single JPEG frame from the current video with the bundled ffmpeg. */
    private suspend fun captureFrameViaFfmpeg(seconds: Double): Bitmap? {
        val source = ffmpegInput() ?: return null
        val frameFile = File(cachePath, "${System.currentTimeMillis()}_ffmpeg_ocr_frame.jpg")
        return runCatching {
            withIOContext {
                frameFile.delete()
                val args = buildList {
                    addInputOptions(source)
                    add("-ss"); add(seconds.coerceAtLeast(0.0).formatSeconds())
                    add("-i"); add(source.input)
                    add("-frames:v"); add("1")
                    add("-f"); add("image2")
                    add("-c:v"); add("mjpeg")
                    add("-q:v"); add("4")
                    add("-y"); add(frameFile.absolutePath)
                }.toTypedArray()
                val result = FfmpegRunner.ffmpeg(args)
                if (result !is FfmpegCommandResult.Success || !frameFile.isFile || frameFile.length() <= 0L) {
                    val diagnostics = (result as? FfmpegCommandResult.FfmpegFailed)?.nativeDiagnostics
                    listOfNotNull(diagnostics?.failStackTrace, diagnostics?.logs).joinToString("\n")
                        .takeIf(String::isNotBlank)?.let { logcat(LogPriority.WARN) { it } }
                    return@withIOContext null
                }
                BitmapFactory.decodeFile(frameFile.absolutePath)
            }
        }.onFailure {
            logcat(LogPriority.WARN, it) { "Failed to capture OCR frame via ffmpeg" }
        }.getOrNull().also {
            frameFile.delete()
        }
    }

    fun createSubtitleAudioMediaRequest(startSeconds: Double?, endSeconds: Double?): AnkiMediaRequest =
        createSentenceAudioMediaRequest(startSeconds, endSeconds)

    fun createVideoOcrAudioMediaRequest(): AnkiMediaRequest {
        val center = getTimeSeconds()
        val padding = getOcrPaddingSeconds()
        return createSentenceAudioMediaRequest(center - padding, center + padding)
    }

    private fun createSentenceAudioMediaRequest(startSeconds: Double?, endSeconds: Double?): AnkiMediaRequest {
        val video = getVideo()
        val mpv = readMpvSnapshot()
        val source = getSource() as? AnimeHttpSource
        val snapshot = video?.let {
            SentenceAudioInputSnapshot(
                originalVideoValue = it.videoUrl,
                playableValue = mpv.playableValue,
                headers = (it.headers ?: source?.headers)?.toList().orEmpty(),
                ffmpegStreamArgs = it.ffmpegStreamArgs.orEmpty(),
                ffmpegVideoArgs = it.ffmpegVideoArgs.orEmpty(),
                seekable = resolveSeekability(mpv.seekable, it.videoUrl),
                selectedAudioId = mpv.selectedAudioId,
                audioTrackCount = mpv.audioTrackCount,
                selectedAudioFfmpegIndex = mpv.selectedAudioFfmpegIndex,
                selectedAudioIsExternal = mpv.selectedAudioIsExternal,
                selectedExternalAudioValue = mpv.selectedExternalAudioValue,
                torrentPlayback = isTorrentPlayback(it.videoUrl),
            )
        }
        val frozen = SentenceAudioCaptureRequest(snapshot, startSeconds, endSeconds)
        return AnkiMediaRequest(
            LazyAnkiSentenceAudioProvider {
                prepareSentenceAudioOverride?.invoke(frozen) ?: sentenceAudioCaptureService.prepare(frozen)
            },
        )
    }

    /**
     * Torrent playback starts from a magnet/.torrent value that is re-opened as a local
     * TorrServer HTTP stream, so the original value can't be handed to FFmpeg for sentence audio.
     * The playable value mpv is reading is used instead.
     */
    private fun isTorrentPlayback(videoUrl: String): Boolean {
        if (videoUrl.startsWith("magnet:", ignoreCase = true) ||
            videoUrl.substringBefore('?').endsWith(".torrent", ignoreCase = true)
        ) {
            return true
        }
        val torrentHost = torrentHostPrefix
        return torrentHost != null && videoUrl.startsWith(torrentHost)
    }

    private val torrentHostPrefix: String? by lazy {
        runCatching { TorrentServerUtils.hostUrl }.getOrNull()
    }

    /**
     * Extracts an animated AVIF scene from the current video around [startSeconds]-[endSeconds]
     * by encoding it directly with the bundled ffmpeg libsvtav1 encoder. Returns null on failure
     * so callers can fall back to a still.
     */
    suspend fun captureAnimatedVideoForAnki(startSeconds: Double?, endSeconds: Double?): ByteArray? {
        val start = startSeconds ?: return null
        val end = endSeconds ?: return null
        if (end <= start) return null
        val source = ffmpegInput() ?: return null
        val avifFile = File(context.cacheDir, "chimahon_scene_${System.currentTimeMillis()}.avif")
        return withIOContext {
            runCatching {
                avifFile.delete()
                val duration = (end - start).coerceIn(0.25, 10.0)
                val args = buildList {
                    addInputOptions(source)
                    add("-ss"); add(start.coerceAtLeast(0.0).formatSeconds())
                    add("-t"); add(duration.formatSeconds())
                    add("-i"); add(source.input)
                    add("-an")
                    add("-sn")
                    add("-dn")
                    add("-map"); add("0:v:0")
                    add("-vf"); add(
                        "scale=w=640:h=640:force_original_aspect_ratio=decrease," +
                            "crop=trunc(iw/2)*2:trunc(ih/2)*2,setsar=1,fps=8,format=yuv420p",
                    )
                    add("-frames:v"); add("80")
                    add("-c:v"); add("libsvtav1")
                    add("-preset"); add("8")
                    add("-crf"); add("35")
                    add("-strict"); add("-2")
                    add("-f"); add("avif")
                    add(avifFile.absolutePath)
                    add("-y")
                }.toTypedArray()
                val result = FfmpegRunner.ffmpeg(args)
                if (result !is FfmpegCommandResult.Success || !avifFile.isFile || avifFile.length() <= 0L) {
                    logcat(LogPriority.ERROR) {
                        "Animated AVIF capture failed; " + listOfNotNull(
                            (result as? FfmpegCommandResult.FfmpegFailed)?.nativeDiagnostics?.failStackTrace?.takeIf { it.isNotBlank() },
                            (result as? FfmpegCommandResult.FfmpegFailed)?.nativeDiagnostics?.logs?.takeIf { it.isNotBlank() },
                        ).joinToString("\n")
                    }
                    return@runCatching null
                }
                avifFile.readBytes()
            }.getOrNull()
        }.also { avifFile.delete() }
    }

    suspend fun captureVideoOcrAnimatedForAnki(): ByteArray? {
        val centerSeconds = getTimeSeconds()
        val paddingSeconds = getOcrPaddingSeconds()
        return captureAnimatedVideoForAnki(
            startSeconds = centerSeconds - paddingSeconds,
            endSeconds = centerSeconds + paddingSeconds,
        )
    }

    private fun Double.formatSeconds(): String {
        return String.format(Locale.US, "%.3f", this)
    }
}