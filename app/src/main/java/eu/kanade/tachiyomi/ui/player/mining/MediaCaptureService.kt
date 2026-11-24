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
import eu.kanade.tachiyomi.ui.player.settings.SubtitlePreferences
import eu.kanade.tachiyomi.util.storage.toFFmpegReadString
import `is`.xyz.mpv.MPVLib
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
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
    private val getSubtitleAudioPaddingMillis: () -> Pair<Int, Int>,
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

    suspend fun captureVideoFrameForOcr(): Bitmap? =
        captureFrameViaMpv() ?: captureFrameViaFfmpeg(getTimeSeconds())

    /** Grabs the current frame directly from mpv without subtitles; falls back to FFmpeg if null. */
    private suspend fun captureFrameViaMpv(): Bitmap? {
        val frameFile = File(cachePath, "${System.nanoTime()}_mpv_ocr_frame.png")
        return withIOContext {
            runCatching {
                frameFile.delete()
                MPVLib.command(arrayOf("screenshot-to-file", frameFile.absolutePath, "video"))
                if (!frameFile.isFile || frameFile.length() <= 0L) {
                    return@runCatching null
                }
                BitmapFactory.decodeFile(frameFile.absolutePath)
            }.onFailure {
                logcat(LogPriority.WARN, it) { "Failed to capture OCR frame via mpv" }
            }.getOrNull().also {
                frameFile.delete()
            }
        }
    }

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
    private class FfmpegSource(
        val input: String,
        val headers: List<Pair<String, String>>,
        val forceMpegTs: Boolean,
    )

    private fun ffmpegInput(): FfmpegSource? {
        val video = getVideo() ?: return null
        val rawInput = selectFfmpegRawInput(MPVLib.getPropertyString("path"), video.videoUrl)
            ?: return null
        val input = when {
            rawInput.startsWith("content://", ignoreCase = true) -> Uri.parse(rawInput).toFFmpegReadString(context)
            rawInput.startsWith("file:", ignoreCase = true) ->
                runCatching { File(URI(rawInput)).absolutePath }.getOrElse { Uri.parse(rawInput).path ?: rawInput }
            else -> rawInput
        }
        val source = getSource() as? AnimeHttpSource
        val headers = (video.headers ?: source?.headers)?.toList().orEmpty()
        return FfmpegSource(
            input,
            if (input.startsWith("http", ignoreCase = true)) headers else emptyList(),
            video.ffmpegStreamArgs.orEmpty().any { it.first == "force_mpegts" && it.second == "1" },
        )
    }

    private fun MutableList<String>.addInputOptions(source: FfmpegSource) {
        if (source.headers.isNotEmpty()) {
            add("-headers"); add(source.headers.joinToString("") { "${it.first}: ${it.second}\r\n" })
        }
        // HLS-only (see isHlsLikeInput): ffmpeg aborts other inputs if these are present.
        if (isHlsLikeInput(source.input) || source.forceMpegTs) {
            add("-allowed_extensions"); add("ALL")
            add("-allowed_segment_extensions"); add("ALL")
            add("-extension_picky"); add("0")
        }
        if (source.forceMpegTs) {
            add("-force_mpegts"); add("1")
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

    fun createSubtitleAudioMediaRequest(startSeconds: Double?, endSeconds: Double?): AnkiMediaRequest {
        // Padding must not turn missing or invalid cue timings into a usable range.
        if (startSeconds == null || endSeconds == null || !startSeconds.isFinite() || !endSeconds.isFinite() ||
            startSeconds < 0.0 || endSeconds <= startSeconds
        ) {
            return createSentenceAudioMediaRequest(startSeconds, endSeconds)
        }
        val (beforeMillis, afterMillis) = getSubtitleAudioPaddingMillis()
        val before = beforeMillis.coerceIn(0, SubtitlePreferences.AUDIO_PADDING_MAX_MILLIS) / 1000.0
        val after = afterMillis.coerceIn(0, SubtitlePreferences.AUDIO_PADDING_MAX_MILLIS) / 1000.0
        return createSentenceAudioMediaRequest((startSeconds - before).coerceAtLeast(0.0), endSeconds + after)
    }

    fun createVideoOcrAudioMediaRequest(): AnkiMediaRequest {
        val center = getTimeSeconds()
        val padding = getOcrPaddingSeconds()
        return createSentenceAudioMediaRequest(center - padding, center + padding)
    }

    private fun createSentenceAudioMediaRequest(startSeconds: Double?, endSeconds: Double?): AnkiMediaRequest {
        val video = getVideo()
        val mpv = readMpvSnapshot()
        val activeInput = video?.let { activeSentenceAudioInput(it, mpv) }
        val frozen = SentenceAudioCaptureRequest(
            input = activeInput,
            startSeconds = startSeconds,
            endSeconds = endSeconds,
        )
        return AnkiMediaRequest(
            LazyAnkiSentenceAudioProvider {
                prepareSentenceAudioOverride?.invoke(frozen) ?: sentenceAudioCaptureService.prepare(frozen)
            },
        )
    }

    /**
     * Mining reads the stream mpv is already playing, including signed HLS URLs and
     * extension localhost proxies. The extension's original URL is only a fallback.
     */
    private fun activeSentenceAudioInput(
        video: Video,
        mpv: SentenceAudioMpvSnapshot,
    ): SentenceAudioInputSpec? {
        val headers = (video.headers ?: (getSource() as? AnimeHttpSource)?.headers)?.toList().orEmpty()
        val forceMpegTs = video.ffmpegStreamArgs.orEmpty().any { (name, value) ->
            name == "force_mpegts" && value == "1"
        }
        val external = mpv.selectedExternalAudioValue?.takeIf { it.isNotBlank() }
        val candidates = buildList {
            if (external != null) add(external to true)
            mpv.playableValue?.takeIf { it.isNotBlank() }?.let { add(it to false) }
            video.videoUrl.takeIf { it.isNotBlank() }?.let { add(it to false) }
        }
        for ((raw, isExternal) in candidates) {
            val normalized = when {
                raw.startsWith("content://", ignoreCase = true) ->
                    raw to SentenceAudioInputKind.CONTENT_URI
                raw.startsWith("http://", ignoreCase = true) ||
                    raw.startsWith("https://", ignoreCase = true) ->
                    raw to SentenceAudioInputKind.REMOTE_HTTP
                raw.startsWith("file:", ignoreCase = true) ->
                    runCatching { File(URI(raw)).absolutePath }.getOrNull()
                        ?.let { it to SentenceAudioInputKind.LOCAL_FILE }
                raw.startsWith("/") || File(raw).isAbsolute ->
                    raw to SentenceAudioInputKind.LOCAL_FILE
                else -> null
            } ?: continue
            return SentenceAudioInputSpec(
                value = normalized.first,
                kind = normalized.second,
                headers = if (normalized.second == SentenceAudioInputKind.REMOTE_HTTP) headers else emptyList(),
                audioStreamIndex = if (isExternal) null else mpv.selectedAudioFfmpegIndex,
                origin = if (isExternal) {
                    SentenceAudioInputOrigin.EXTERNAL_AUDIO
                } else {
                    SentenceAudioInputOrigin.PLAYABLE_VIDEO
                },
                forceMpegTs = forceMpegTs && !isExternal,
            )
        }
        return null
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

/**
 * Selects the best FFmpeg-readable input between mpv's active `path` and the episode's `videoUrl`.
 * Skips schemes FFmpeg cannot open (notably `fd://` used by local SAF playback) so callers fall
 * back to the underlying `content://` URI.
 */
internal fun selectFfmpegRawInput(mpvPath: String?, videoUrl: String?): String? {
    for (raw in sequenceOf(mpvPath, videoUrl)) {
        val candidate = raw?.trim()?.takeIf { it.isNotEmpty() } ?: continue
        val supported = candidate.startsWith("content://", ignoreCase = true) ||
            candidate.startsWith("http://", ignoreCase = true) ||
            candidate.startsWith("https://", ignoreCase = true) ||
            candidate.startsWith("file:", ignoreCase = true) ||
            candidate.startsWith("/") ||
            File(candidate).isAbsolute
        if (supported) return candidate
    }
    return null
}
