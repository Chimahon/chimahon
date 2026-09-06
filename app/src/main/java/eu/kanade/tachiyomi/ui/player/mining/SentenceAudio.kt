package eu.kanade.tachiyomi.ui.player.mining

import android.content.Context
import android.net.Uri
import chimahon.anki.AnkiSentenceAudioDiagnostic
import chimahon.anki.AnkiSentenceAudioFailure
import chimahon.anki.AnkiSentenceAudioInputSource
import chimahon.anki.AnkiSentenceAudioPlayableFallback
import chimahon.anki.AnkiSentenceAudioPreparation
import chimahon.anki.AnkiSentenceAudioSource
import com.arthenica.ffmpegkit.FFmpegKitConfig
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.Closeable
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal enum class SentenceAudioInputKind { LOCAL_FILE, CONTENT_URI, REMOTE_HTTP }

internal enum class SentenceAudioInputOrigin { ORIGINAL_VIDEO, PLAYABLE_VIDEO, EXTERNAL_AUDIO }

internal data class SentenceAudioInputSpec(
    val value: String,
    val kind: SentenceAudioInputKind,
    val headers: List<Pair<String, String>>,
    val audioStreamIndex: Int? = null,
    val origin: SentenceAudioInputOrigin,
)

internal data class SentenceAudioInputSnapshot(
    val originalVideoValue: String,
    val playableValue: String?,
    val headers: List<Pair<String, String>>,
    val ffmpegStreamArgs: List<Pair<String, String>>,
    val ffmpegVideoArgs: List<Pair<String, String>>,
    val seekable: Boolean?,
    val selectedAudioId: Int?,
    val audioTrackCount: Int,
    val selectedAudioFfmpegIndex: Int?,
    val selectedAudioIsExternal: Boolean,
    val selectedExternalAudioValue: String?,
    val torrentPlayback: Boolean = false,
)

internal sealed interface SentenceAudioInputResolution {
    data class Available(val input: SentenceAudioInputSpec) : SentenceAudioInputResolution
    data class Unavailable(val failure: AnkiSentenceAudioFailure) : SentenceAudioInputResolution
}

internal sealed interface SentenceAudioPlayableFallbackResolution {
    data class Available(val input: SentenceAudioInputSpec) : SentenceAudioPlayableFallbackResolution
    data object Missing : SentenceAudioPlayableFallbackResolution
    data object SameAsOriginal : SentenceAudioPlayableFallbackResolution
    data object Unavailable : SentenceAudioPlayableFallbackResolution
}

internal fun resolveSeekability(mpvSeekable: Boolean?, originalVideoValue: String): Boolean =
    mpvSeekable ?: stableLocalFile(originalVideoValue)

private fun stableLocalFile(value: String): Boolean {
    val fileUri = runCatching { URI(value) }.getOrNull()
        ?.takeIf { it.scheme.equals("file", ignoreCase = true) }
    return if (fileUri != null) {
        runCatching { File(fileUri).isFile }.getOrDefault(false)
    } else {
        File(value).isFile
    }
}

internal object SentenceAudioInputResolver {
    fun resolve(snapshot: SentenceAudioInputSnapshot): SentenceAudioInputSpec? =
        when (val resolution = resolveForCapture(snapshot)) {
            is SentenceAudioInputResolution.Available -> resolution.input
            is SentenceAudioInputResolution.Unavailable -> null
        }

    fun resolveForCapture(snapshot: SentenceAudioInputSnapshot): SentenceAudioInputResolution {
        if (snapshot.selectedAudioIsExternal) {
            return resolveExternalAudio(snapshot)
        }
        if (snapshot.selectedAudioId != null && snapshot.selectedAudioFfmpegIndex == null && snapshot.audioTrackCount != 1) {
            return SentenceAudioInputResolution.Unavailable(AnkiSentenceAudioFailure.TRACK_MAPPING_UNAVAILABLE)
        }
        return resolveOriginalVideo(snapshot)
    }

    fun resolvePlayableFallback(
        snapshot: SentenceAudioInputSnapshot,
        original: SentenceAudioInputSpec,
    ): SentenceAudioPlayableFallbackResolution {
        if (snapshot.playableValue.isNullOrBlank()) return SentenceAudioPlayableFallbackResolution.Missing
        val playable = resolveValue(
            value = snapshot.playableValue,
            snapshot = snapshot,
            origin = SentenceAudioInputOrigin.PLAYABLE_VIDEO,
        ) ?: return SentenceAudioPlayableFallbackResolution.Unavailable
        return if (playable.value == original.value && playable.kind == original.kind && playable.headers == original.headers) {
            SentenceAudioPlayableFallbackResolution.SameAsOriginal
        } else {
            SentenceAudioPlayableFallbackResolution.Available(playable)
        }
    }

    private fun resolveOriginalVideo(snapshot: SentenceAudioInputSnapshot): SentenceAudioInputResolution {
        if (snapshot.ffmpegStreamArgs.isNotEmpty() || snapshot.ffmpegVideoArgs.isNotEmpty()) {
            return SentenceAudioInputResolution.Unavailable(AnkiSentenceAudioFailure.SOURCE_UNAVAILABLE)
        }
        if (snapshot.torrentPlayback) {
            return resolveTorrentStream(snapshot)
        }
        if (snapshot.seekable != true) {
            return SentenceAudioInputResolution.Unavailable(AnkiSentenceAudioFailure.SOURCE_UNAVAILABLE)
        }
        val original = snapshot.originalVideoValue.takeIf(String::isNotBlank)
        val value = original ?: snapshot.playableValue
        val origin = if (original != null) SentenceAudioInputOrigin.ORIGINAL_VIDEO else SentenceAudioInputOrigin.PLAYABLE_VIDEO
        return resolveValue(value, snapshot, origin)?.let(SentenceAudioInputResolution::Available)
            ?: SentenceAudioInputResolution.Unavailable(AnkiSentenceAudioFailure.SOURCE_UNAVAILABLE)
    }

    /**
     * Torrent playback starts from a magnet/.torrent value that mpv serves through a local
     * TorrServer HTTP stream. That playable stream is what gets handed to FFmpeg. The seekable
     * gate is intentionally skipped so FFmpeg itself gets a chance to report the real problem
     * (probe or extract errors) instead of being rejected before it can run.
     */
    private fun resolveTorrentStream(snapshot: SentenceAudioInputSnapshot): SentenceAudioInputResolution {
        val value = snapshot.playableValue?.takeIf(String::isNotBlank)
            ?: snapshot.originalVideoValue.takeIf(String::isNotBlank)
            ?: return SentenceAudioInputResolution.Unavailable(AnkiSentenceAudioFailure.SOURCE_UNAVAILABLE)
        return resolveValue(value, snapshot, SentenceAudioInputOrigin.PLAYABLE_VIDEO)
            ?.let(SentenceAudioInputResolution::Available)
            ?: SentenceAudioInputResolution.Unavailable(AnkiSentenceAudioFailure.SOURCE_UNAVAILABLE)
    }

    private fun resolveExternalAudio(snapshot: SentenceAudioInputSnapshot): SentenceAudioInputResolution {
        if (snapshot.seekable != true && !snapshot.torrentPlayback) {
            return SentenceAudioInputResolution.Unavailable(AnkiSentenceAudioFailure.SOURCE_UNAVAILABLE)
        }
        if (snapshot.ffmpegStreamArgs.isNotEmpty() || snapshot.ffmpegVideoArgs.isNotEmpty()) {
            return SentenceAudioInputResolution.Unavailable(AnkiSentenceAudioFailure.SOURCE_UNAVAILABLE)
        }
        val externalValue = snapshot.selectedExternalAudioValue?.takeIf(String::isNotBlank)
        if (externalValue != null) {
            val externalSpec = resolveValue(externalValue, snapshot, SentenceAudioInputOrigin.EXTERNAL_AUDIO)
            if (externalSpec != null) {
                return SentenceAudioInputResolution.Available(externalSpec)
            }
        }
        return resolveOriginalVideo(snapshot)
    }

    fun resolveOriginalVideoSpec(snapshot: SentenceAudioInputSnapshot): SentenceAudioInputSpec? =
        (resolveOriginalVideo(snapshot) as? SentenceAudioInputResolution.Available)?.input

    private fun resolveValue(
        value: String?,
        snapshot: SentenceAudioInputSnapshot,
        origin: SentenceAudioInputOrigin,
    ): SentenceAudioInputSpec? {
        val raw = value?.takeIf(String::isNotBlank) ?: return null
        if (isDash(raw) || isTransient(raw)) return null
        val normalized = normalizeInput(raw) ?: return null
        val headers = when (normalized.second) {
            SentenceAudioInputKind.REMOTE_HTTP -> validateRemoteInput(normalized.first, snapshot.headers) ?: return null
            SentenceAudioInputKind.LOCAL_FILE, SentenceAudioInputKind.CONTENT_URI -> emptyList()
        }
        val audioStreamIndex = if (origin == SentenceAudioInputOrigin.EXTERNAL_AUDIO || !snapshot.selectedAudioIsExternal) {
            snapshot.selectedAudioFfmpegIndex?.takeIf { it >= 0 }
        } else {
            null
        }
        return SentenceAudioInputSpec(
            value = normalized.first,
            kind = normalized.second,
            headers = headers,
            audioStreamIndex = audioStreamIndex,
            origin = origin,
        )
    }

    private fun validateRemoteInput(value: String, headers: List<Pair<String, String>>): List<Pair<String, String>>? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (!uri.userInfo.isNullOrBlank() || host.isNullOrBlank() || hasRejectedValidationQuery(uri.rawQuery.orEmpty(), host, scheme)) return null
        return headers.takeIf { it.all(::isAllowedHeader) }
    }

    private fun normalizeInput(value: String): Pair<String, SentenceAudioInputKind>? = when {
        value.startsWith("content://", ignoreCase = true) -> value to SentenceAudioInputKind.CONTENT_URI
        value.startsWith("file:", ignoreCase = true) -> runCatching { File(URI(value)).absolutePath }.getOrNull()
            ?.takeIf(String::isNotBlank)?.let { it to SentenceAudioInputKind.LOCAL_FILE }
        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true) -> value to SentenceAudioInputKind.REMOTE_HTTP
        value.startsWith("/") || File(value).isAbsolute -> value to SentenceAudioInputKind.LOCAL_FILE
        else -> null
    }

    private fun isAllowedHeader(header: Pair<String, String>): Boolean {
        val (name, value) = header
        return name.lowercase(Locale.ROOT) in allowedHttpHeaders && value.length <= maxHeaderValueLength &&
            value.none { it == '\u0000' || it == '\r' || it == '\n' || (it.code < 0x20 && it != '\t') }
    }

    private fun hasRejectedValidationQuery(query: String, host: String, scheme: String?): Boolean = query.split('&').any { parameter ->
        if (parameter.isBlank()) return@any false
        val name = runCatching { URLDecoder.decode(parameter.substringBefore('='), StandardCharsets.UTF_8.name()).lowercase(Locale.ROOT) }.getOrNull()
            ?: return true
        name in rejectedValidationQueryNames ||
            (name in signedMediaQueryNames && (scheme != "https" || !host.isYouTubeVideoCdn())) ||
            sensitiveQueryPrefixes.any(name::startsWith)
    }

    private fun String.isYouTubeVideoCdn() = this == "googlevideo.com" || endsWith(".googlevideo.com")

    fun sanitizeForLog(url: String): String {
        if (url.isBlank()) return url
        val queryStart = url.indexOf('?')
        val pathPart = if (queryStart >= 0) url.substring(0, queryStart) else url
        val queryPart = if (queryStart >= 0) url.substring(queryStart + 1) else null

        val redactedQuery = queryPart?.split('&')?.joinToString("&") { param ->
            val key = param.substringBefore('=')
            val decodedKey = runCatching {
                URLDecoder.decode(key, StandardCharsets.UTF_8.name()).lowercase(Locale.ROOT)
            }.getOrDefault(key.lowercase(Locale.ROOT))
            if (decodedKey in sensitiveLogQueryNames || sensitiveQueryPrefixes.any(decodedKey::startsWith)) {
                "$key=[REDACTED]"
            } else {
                param
            }
        }

        return if (redactedQuery != null) "$pathPart?$redactedQuery" else pathPart
    }

    private fun isDash(value: String) = value.substringBefore('?').endsWith(".mpd", ignoreCase = true) || value.startsWith("dash://", ignoreCase = true)
    private fun isTransient(value: String): Boolean {
        val scheme = value.substringBefore("://", "").lowercase(Locale.ROOT)
        return scheme in transientSchemes || value.startsWith("magnet:", ignoreCase = true) || value.substringBefore('?').endsWith(".torrent", ignoreCase = true)
    }

    private val allowedHttpHeaders = setOf("user-agent", "accept", "accept-encoding", "accept-language", "cache-control", "origin", "pragma", "referer")
    private val rejectedValidationQueryNames = setOf("access_token", "api_key", "auth", "authorization", "credential", "credentials", "key", "policy", "token")
    private val signedMediaQueryNames = setOf("signature", "signed", "sig", "lsig")
    private val sensitiveLogQueryNames = setOf("access_token", "api_key", "auth", "authorization", "credential", "credentials", "key", "policy", "signature", "signed", "sig", "lsig", "token")
    private val sensitiveQueryPrefixes = setOf("x-amz-", "x-goog-")
    private val transientSchemes = setOf("blob", "data", "fd", "fdclose", "edl", "memory", "lavf", "ytdl")
    private const val maxHeaderValueLength = 8_192
}

internal object SentenceAudioFfmpegArguments {
    fun audioProbe(input: SentenceAudioInputSpec, acquiredInputValue: String, tlsCaFile: String? = null): Array<String> = probe(input, acquiredInputValue, input.audioStreamIndex?.toString() ?: "a:0", "stream=codec_type,codec_name:stream_side_data", tlsCaFile, true)
    fun allAudioProbe(input: SentenceAudioInputSpec, acquiredInputValue: String, tlsCaFile: String? = null): Array<String> = probe(input, acquiredInputValue, "a", "stream=index,codec_type,codec_name:stream_side_data", tlsCaFile, true)
    fun audioDiscoveryProbe(input: SentenceAudioInputSpec, acquiredInputValue: String, tlsCaFile: String? = null): Array<String> = probe(input, acquiredInputValue, "a", "stream=index,codec_type,codec_name:stream_side_data", tlsCaFile, false)
    private fun probe(input: SentenceAudioInputSpec, acquired: String, selector: String, entries: String, ca: String?, restrict: Boolean) = buildList {
        addInputOptions(input, ca, restrict); add("-v"); add("error"); add("-select_streams"); add(selector); add("-show_entries"); add(entries); add("-of"); add("default=noprint_wrappers=1"); add(acquired)
    }.toTypedArray()
    fun sentenceAudio(input: SentenceAudioInputSpec, acquired: String, start: Double, end: Double, output: String, tlsCaFile: String? = null) = buildList {
        addInputOptions(input, tlsCaFile); add("-ss"); add(start.seconds()); add("-i"); add(acquired); add("-map"); add(input.audioStreamIndex?.let { "0:$it" } ?: "0:a:0"); add("-vn"); add("-sn"); add("-dn"); add("-t"); add((end - start).seconds()); add("-c:a"); add("aac"); add("-b:a"); add("128k"); add("-y"); add(output)
    }.toTypedArray()
    private fun MutableList<String>.addInputOptions(input: SentenceAudioInputSpec, tlsCaFile: String?, restrict: Boolean = true) {
        if (restrict) { add("-codec_whitelist"); add(ALLOWED_INPUT_DECODERS) }
        if (input.kind == SentenceAudioInputKind.REMOTE_HTTP) { require(!tlsCaFile.isNullOrBlank()); add("-protocol_whitelist"); add("http,https,tls,tcp,crypto,hls,applehttp,concat"); add("-rw_timeout"); add("15000000") }
        if (isHlsInput(input.value)) { add("-allowed_extensions"); add("ALL"); add("-allowed_segment_extensions"); add("ALL"); add("-extension_picky"); add("0") }
        if (input.headers.isNotEmpty()) { add("-headers"); add(input.headers.joinToString("") { "${it.first}: ${it.second}\r\n" }) }
    }
    private fun Double.seconds() = String.format(Locale.ROOT, "%.6f", this).trimEnd('0').trimEnd('.')
    internal const val ALLOWED_INPUT_DECODERS = "aac,aac_fixed,ac3,alac,ass,av1,dca,dvdsub,eac3,eia_608,ffv1,flac,h263,h264,hevc,libdav1d,mjpeg,mov_text,mp3,mp3float,mpeg1video,mpeg2video,mpeg4,opus,pcm_f32le,pcm_s16le,pcm_s24le,pcm_s32le,png,prores,realtext,srt,ssa,subrip,text,theora,truehd,vorbis,vp8,vp9,webvtt"
}

/** Parses the small, explicitly requested audio subset of ffprobe's key/value output. */
internal object SentenceAudioMediaProbe {
    fun inspectSelectedAudio(output: String): AudioInspection {
        val values = output.keyValues()
        if (values.isEmpty()) return AudioInspection.StreamMissing
        if (output.hasProtectionMarker()) return AudioInspection.Protected
        return if (values["codec_type"] == "audio") AudioInspection.Readable else AudioInspection.NotAudio
    }

    fun audioStreams(output: String): List<AudioStream> = output.streamBlocks().mapNotNull { block ->
        val values = block.keyValues()
        if (values["codec_type"] != "audio") return@mapNotNull null
        AudioStream(index = values["index"]?.toIntOrNull(), protected = block.hasProtectionMarker())
    }

    sealed interface AudioInspection {
        data object Readable : AudioInspection
        data object StreamMissing : AudioInspection
        data object NotAudio : AudioInspection
        data object Protected : AudioInspection
    }

    data class AudioStream(val index: Int?, val protected: Boolean)

    private fun String.keyValues(): Map<String, String> = lineSequence().mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) null else {
            line.substring(0, separator).trim().lowercase(Locale.ROOT) to
                line.substring(separator + 1).trim().lowercase(Locale.ROOT)
        }
    }.toMap()

    private fun String.streamBlocks(): List<String> {
        val blocks = mutableListOf<String>()
        var current: StringBuilder? = null
        lineSequence().forEach { line ->
            when (line.trim()) {
                "[STREAM]" -> current = StringBuilder()
                "[/STREAM]" -> current?.let { blocks += it.toString(); current = null }
                else -> current?.append(line)?.append('\n')
            }
        }
        return blocks.ifEmpty { listOf(this) }
    }

    private fun String.hasProtectionMarker(): Boolean {
        val normalized = lowercase(Locale.ROOT)
        return protectionMarkers.any(normalized::contains)
    }

    private val protectionMarkers = setOf("cenc", "cbcs", "crypto", "encrypted", "encryption", "drm")
}

internal interface SentenceAudioMpvPropertyReader {
    fun string(name: String): String?
    fun int(name: String): Int?
    fun boolean(name: String): Boolean?
}

internal data class SentenceAudioMpvSnapshot(
    val playableValue: String?,
    val selectedAudioId: Int?,
    val selectedExternalAudioValue: String?,
    val selectedAudioIsExternal: Boolean,
    val audioTrackCount: Int,
    val selectedAudioFfmpegIndex: Int?,
    val seekable: Boolean?,
)

internal class SentenceAudioMpvSnapshotReader(private val reader: SentenceAudioMpvPropertyReader) {
    fun read(): SentenceAudioMpvSnapshot {
        val selectedId = reader.string("aid")?.toIntOrNull() ?: reader.int("aid")
        val count = reader.int("track-list/count") ?: 0
        val tracks = (0 until count).map { index ->
            Track(index, reader.string("track-list/$index/type"), reader.int("track-list/$index/id"))
        }
        val selected = tracks.firstOrNull { it.type == "audio" && it.id == selectedId }
        val selectedIndex = selected?.index
        val externalFilename = selectedIndex?.let { reader.string("track-list/$it/external-filename") }?.takeIf(String::isNotBlank)
        val ffIndex = selectedIndex?.let { reader.int("track-list/$it/ff-index") }?.takeIf { it >= 0 }
        return SentenceAudioMpvSnapshot(
            playableValue = reader.string("path"),
            selectedAudioId = selectedId,
            selectedExternalAudioValue = externalFilename,
            selectedAudioIsExternal = selectedIndex?.let { reader.boolean("track-list/$it/external") == true } == true || externalFilename != null,
            audioTrackCount = tracks.count { it.type == "audio" },
            selectedAudioFfmpegIndex = ffIndex,
            seekable = reader.boolean("seekable"),
        )
    }
    private data class Track(val index: Int, val type: String?, val id: Int?)
}

internal enum class SentenceAudioDiagnosticStage { REQUEST_VALIDATION, FALLBACK_DECISION, SELECTED_AUDIO_PROBE, ALL_AUDIO_PROBE, AUDIO_DISCOVERY_PROBE, AUDIO_EXTRACTION, OUTPUT_VALIDATION, OUTPUT_READ }
internal enum class SentenceAudioDiagnosticFallback { NOT_APPLICABLE, MISSING, SAME_AS_ORIGINAL, UNAVAILABLE, ATTEMPTED }
internal data class SentenceAudioDiagnosticEvent(
    val stage: SentenceAudioDiagnosticStage,
    val input: SentenceAudioInputSpec?,
    val fallback: SentenceAudioDiagnosticFallback,
    val failure: AnkiSentenceAudioFailure? = null,
    val result: FfmpegCommandResult? = null,
    val exceptionType: String? = null,
)
internal fun interface SentenceAudioDiagnosticLogger { fun record(event: SentenceAudioDiagnosticEvent) }
internal object NoOpSentenceAudioDiagnosticLogger : SentenceAudioDiagnosticLogger { override fun record(event: SentenceAudioDiagnosticEvent) = Unit }
internal class LogcatSentenceAudioDiagnosticLogger : SentenceAudioDiagnosticLogger {
    override fun record(event: SentenceAudioDiagnosticEvent) {
        logcat(LogPriority.INFO) {
            "[sentence-audio] ${SentenceAudioDiagnosticJournal.render(event)}"
        }
    }
}

internal fun createSentenceAudioDiagnosticLogger(): SentenceAudioDiagnosticLogger =
    if (BuildConfig.DEBUG) LogcatSentenceAudioDiagnosticLogger() else NoOpSentenceAudioDiagnosticLogger

internal object SentenceAudioDiagnosticJournal {
    fun render(event: SentenceAudioDiagnosticEvent): String = buildString {
        appendLine("recorded_at_utc=${System.currentTimeMillis()}")
        appendLine("stage=${event.stage.name}")
        event.input?.let {
            appendLine("input_source=${it.origin.name}")
            appendLine("input_kind=${it.kind.name}")
            appendLine("audio_stream_index=${it.audioStreamIndex ?: "none"}")
            appendLine("input_value_sanitized=${SentenceAudioInputResolver.sanitizeForLog(it.value)}")
        }
        appendLine("fallback=${event.fallback.name}")
        event.failure?.let { appendLine("failure=${it.name}") }
        event.result?.let { appendResult(it) }
        event.exceptionType?.let { appendLine("exception_type=$it") }
        appendLine("---")
    }

    private fun StringBuilder.appendResult(result: FfmpegCommandResult) = when (result) {
        is FfmpegCommandResult.Success -> appendLine("command_result=SUCCESS")
        FfmpegCommandResult.Failed -> appendLine("command_result=EXECUTION_FAILED")
        is FfmpegCommandResult.FfmpegFailed -> appendNativeFailure(result)
    }

    private fun StringBuilder.appendNativeFailure(result: FfmpegCommandResult.FfmpegFailed) {
        appendLine("command_result=NATIVE_FAILED")
        appendLine("native_failure=${result.failure.name}")
        val diagnostics = result.nativeDiagnostics ?: return
        diagnostics.returnCode?.let { appendLine("return_code=$it") }
        val detail = redact(sequenceOf(diagnostics.failStackTrace, diagnostics.logs).filterNotNull().joinToString("\n"))
            .takeLast(maxNativeDiagnosticChars)
            .trim()
            .takeIf(String::isNotEmpty) ?: return
        appendLine("native_diagnostic_begin")
        appendLine(detail)
        appendLine("native_diagnostic_end")
    }

    internal fun redact(value: String): String =
        value.replace(urlPattern, "<redacted-url>")
            .replace(sensitiveQueryPattern, "${'$'}1=<redacted>")
            .replace(sensitiveHeaderPattern, "${'$'}1<redacted>")
            .replace(localPathPattern, "<redacted-path>")
    private const val maxNativeDiagnosticChars = 32 * 1024
    private val urlPattern = Regex("""(?i)\b(?:https?|file)://[^\s"'<>]+""")
    private val sensitiveQueryPattern = Regex("""(?i)\b(access_token|api_key|auth|authorization|credential|credentials|key|policy|signature|signed|sig|lsig|token|x-amz-[^=\s]+|x-goog-[^=\s]+)=([^&\s]+)""")
    private val sensitiveHeaderPattern = Regex("""(?im)^((?:authorization|cookie|referer|origin|user-agent|accept(?:-[a-z-]+)?|cache-control|pragma|proxy-authorization|x-[a-z0-9-]+)\s*:\s*).*$""")
    private val localPathPattern = Regex("""(?i)(?:[a-z]:\\|/(?:data|storage|sdcard|mnt|cache|files)/)[^\s"'<>]+""")
}

private const val nativeErrorMaxChars = 2 * 1024

/** Immutable inputs captured while OCR is selected; this service never reads live player state. */
internal data class SentenceAudioCaptureRequest(
    val inputSnapshot: SentenceAudioInputSnapshot?,
    val startSeconds: Double?,
    val endSeconds: Double?,
    val inputFailure: AnkiSentenceAudioFailure? = null,
)

internal interface SentenceAudioInputLease : Closeable {
    val ffmpegValue: String
    val tlsCaFile: String?
}

internal fun interface SentenceAudioInputAcquirer {
    suspend fun acquire(input: SentenceAudioInputSpec): SentenceAudioInputLease?
}

internal class SentenceAudioCaptureService(
    private val cacheDirectory: File,
    private val inputAcquirer: SentenceAudioInputAcquirer,
    private val timeoutMillis: Long = 60_000L,
    private val diagnosticLogger: SentenceAudioDiagnosticLogger = NoOpSentenceAudioDiagnosticLogger,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun prepare(request: SentenceAudioCaptureRequest): AnkiSentenceAudioPreparation {
        val snapshot = request.inputSnapshot ?: return unavailable(
            request.inputFailure ?: AnkiSentenceAudioFailure.SOURCE_UNAVAILABLE,
            null,
            SentenceAudioDiagnosticStage.REQUEST_VALIDATION,
        )
        val range = request.validRangeOrNull() ?: return unavailable(
            AnkiSentenceAudioFailure.TIMING_UNAVAILABLE,
            null,
            SentenceAudioDiagnosticStage.REQUEST_VALIDATION,
        )
        val resolved = SentenceAudioInputResolver.resolveForCapture(snapshot)
        val original = when (resolved) {
            is SentenceAudioInputResolution.Available -> resolved.input
            is SentenceAudioInputResolution.Unavailable -> return unavailable(
                resolved.failure,
                null,
                SentenceAudioDiagnosticStage.REQUEST_VALIDATION,
            )
        }
        return withTimeoutOrNull(timeoutMillis) {
            withContext(ioDispatcher) {
                when (val result = resolveInput(snapshot, original)) {
                    is Resolution.Ready -> extract(result.input, range)
                    is Resolution.Unavailable -> AnkiSentenceAudioPreparation.Unavailable(result.failure, result.diagnostic)
                }
            }
        } ?: unavailable(
            AnkiSentenceAudioFailure.EXTRACTION_TIMED_OUT,
            original,
            SentenceAudioDiagnosticStage.AUDIO_EXTRACTION,
        )
    }

    private fun SentenceAudioCaptureRequest.validRangeOrNull(): ClosedFloatingPointRange<Double>? {
        val start = startSeconds ?: return null
        val end = endSeconds ?: return null
        return start.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { validStart -> end.takeIf { it.isFinite() && it > validStart }?.let { validStart..it } }
    }

    private suspend fun resolveInput(snapshot: SentenceAudioInputSnapshot, input: SentenceAudioInputSpec): Resolution {
        var current = resolveOne(input)
        if (current is Resolution.Ready) {
            return current
        }
        val currentFailure = (current as? Resolution.Unavailable)?.failure ?: AnkiSentenceAudioFailure.SOURCE_UNAVAILABLE

        if (input.origin == SentenceAudioInputOrigin.EXTERNAL_AUDIO) {
            if (input.audioStreamIndex != null) {
                val unindexedExternal = input.copy(audioStreamIndex = null)
                val retryExternal = resolveInventory(unindexedExternal)
                if (retryExternal is Resolution.Ready) return retryExternal
                if (retryExternal is Resolution.Unavailable) current = retryExternal
            }

            val originalFallback = SentenceAudioInputResolver.resolveOriginalVideoSpec(snapshot)
                ?.copy(audioStreamIndex = null)
            if (originalFallback != null && originalFallback.value != input.value) {
                val fallbackFailure = (current as? Resolution.Unavailable)?.failure ?: currentFailure
                record(SentenceAudioDiagnosticStage.FALLBACK_DECISION, input, SentenceAudioDiagnosticFallback.ATTEMPTED, fallbackFailure)
                val second = resolveOne(originalFallback)
                if (second is Resolution.Ready) return second
                if (second is Resolution.Unavailable) current = second
            }

            return current
        }

        if (current is Resolution.Unavailable && !current.failure.isPlayableFallbackRetryable()) {
            return current
        }

        val activeFailure = (current as? Resolution.Unavailable)?.failure ?: currentFailure

        return when (val fallback = SentenceAudioInputResolver.resolvePlayableFallback(snapshot, input)) {
            is SentenceAudioPlayableFallbackResolution.Available -> {
                record(SentenceAudioDiagnosticStage.FALLBACK_DECISION, input, SentenceAudioDiagnosticFallback.ATTEMPTED, activeFailure)
                resolveOne(fallback.input)
            }
            SentenceAudioPlayableFallbackResolution.Missing -> unavailableResolution(activeFailure, input, AnkiSentenceAudioPlayableFallback.MISSING)
            SentenceAudioPlayableFallbackResolution.SameAsOriginal -> unavailableResolution(activeFailure, input, AnkiSentenceAudioPlayableFallback.SAME_AS_ORIGINAL)
            SentenceAudioPlayableFallbackResolution.Unavailable -> unavailableResolution(activeFailure, input, AnkiSentenceAudioPlayableFallback.UNAVAILABLE)
        }
    }

    private fun unavailableResolution(
        failure: AnkiSentenceAudioFailure,
        input: SentenceAudioInputSpec,
        fallback: AnkiSentenceAudioPlayableFallback,
    ): Resolution.Unavailable {
        record(SentenceAudioDiagnosticStage.FALLBACK_DECISION, input, fallback.toDiagnosticFallback(), failure)
        return Resolution.Unavailable(failure, input.diagnostic(fallback))
    }

    private suspend fun resolveOne(input: SentenceAudioInputSpec): Resolution {
        return when (val selected = probe(input, ProbeMode.SELECTED_RESTRICTED)) {
            ProbeResult.SourceUnavailable -> Resolution.Unavailable(AnkiSentenceAudioFailure.SOURCE_UNAVAILABLE, input.diagnostic())
            is ProbeResult.Failed -> Resolution.Unavailable(AnkiSentenceAudioFailure.AUDIO_PROBE_FAILED, input.diagnostic(result = selected.result))
            is ProbeResult.Success -> when (SentenceAudioMediaProbe.inspectSelectedAudio(selected.output)) {
                SentenceAudioMediaProbe.AudioInspection.Readable -> Resolution.Ready(input)
                SentenceAudioMediaProbe.AudioInspection.Protected -> Resolution.Unavailable(AnkiSentenceAudioFailure.AUDIO_STREAM_PROTECTED, input.diagnostic())
                SentenceAudioMediaProbe.AudioInspection.StreamMissing,
                SentenceAudioMediaProbe.AudioInspection.NotAudio,
                -> resolveInventory(input)
            }
        }
    }

    private suspend fun resolveInventory(input: SentenceAudioInputSpec): Resolution {
        return when (val inventory = probe(input, ProbeMode.ALL_RESTRICTED)) {
            ProbeResult.SourceUnavailable -> Resolution.Unavailable(AnkiSentenceAudioFailure.SOURCE_UNAVAILABLE, input.diagnostic())
            is ProbeResult.Failed -> Resolution.Unavailable(AnkiSentenceAudioFailure.AUDIO_PROBE_FAILED, input.diagnostic(result = inventory.result))
            is ProbeResult.Success -> {
                val streams = SentenceAudioMediaProbe.audioStreams(inventory.output)
                val only = streams.singleOrNull()
                when {
                    only?.index != null && !only.protected -> Resolution.Ready(input.copy(audioStreamIndex = only.index))
                    streams.size > 1 -> Resolution.Unavailable(AnkiSentenceAudioFailure.TRACK_MAPPING_UNAVAILABLE, input.diagnostic())
                    only?.protected == true -> Resolution.Unavailable(AnkiSentenceAudioFailure.AUDIO_STREAM_PROTECTED, input.diagnostic())
                    only != null -> Resolution.Unavailable(AnkiSentenceAudioFailure.TRACK_MAPPING_UNAVAILABLE, input.diagnostic())
                    else -> resolveDiscovery(input)
                }
            }
        }
    }

    private suspend fun resolveDiscovery(input: SentenceAudioInputSpec): Resolution {
        return when (val discovery = probe(input, ProbeMode.ALL_UNRESTRICTED_DISCOVERY)) {
            ProbeResult.SourceUnavailable -> Resolution.Unavailable(AnkiSentenceAudioFailure.SOURCE_UNAVAILABLE, input.diagnostic())
            is ProbeResult.Failed -> Resolution.Unavailable(AnkiSentenceAudioFailure.AUDIO_PROBE_FAILED, input.diagnostic(result = discovery.result))
            is ProbeResult.Success -> when {
                SentenceAudioMediaProbe.audioStreams(discovery.output).isNotEmpty() -> Resolution.Unavailable(AnkiSentenceAudioFailure.AUDIO_CODEC_RESTRICTED, input.diagnostic())
                input.kind == SentenceAudioInputKind.REMOTE_HTTP && input.audioStreamIndex != null -> Resolution.Ready(input)
                else -> Resolution.Unavailable(AnkiSentenceAudioFailure.AUDIO_STREAMS_NOT_FOUND, input.diagnostic())
            }
        }
    }

    private suspend fun probe(input: SentenceAudioInputSpec, mode: ProbeMode): ProbeResult {
        val stage = mode.stage
        val lease = inputAcquirer.acquire(input) ?: run {
            record(stage, input, input.fallback(), AnkiSentenceAudioFailure.SOURCE_UNAVAILABLE)
            return ProbeResult.SourceUnavailable
        }
        val cleanup = NativeCleanup(lease::close)
        return try {
            val result = FfmpegRunner.ffprobe(
                when (mode) {
                    ProbeMode.SELECTED_RESTRICTED -> SentenceAudioFfmpegArguments.audioProbe(input, lease.ffmpegValue, lease.tlsCaFile)
                    ProbeMode.ALL_RESTRICTED -> SentenceAudioFfmpegArguments.allAudioProbe(input, lease.ffmpegValue, lease.tlsCaFile)
                    ProbeMode.ALL_UNRESTRICTED_DISCOVERY -> SentenceAudioFfmpegArguments.audioDiscoveryProbe(input, lease.ffmpegValue, lease.tlsCaFile)
                },
                cleanup::nativeFinished,
            )
            record(stage, input, input.fallback(), AnkiSentenceAudioFailure.AUDIO_PROBE_FAILED.takeIf { result !is FfmpegCommandResult.Success }, result)
            when (result) {
                is FfmpegCommandResult.Success -> ProbeResult.Success(result.output)
                else -> ProbeResult.Failed(result)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cleanup.nativeFinished()
            record(stage, input, input.fallback(), AnkiSentenceAudioFailure.AUDIO_PROBE_FAILED, exceptionType = e.javaClass.name)
            ProbeResult.Failed()
        } finally {
            cleanup.release()
        }
    }

    private suspend fun extract(input: SentenceAudioInputSpec, range: ClosedFloatingPointRange<Double>): AnkiSentenceAudioPreparation {
        val primary = executeOnce(input, range)
        if (primary is AnkiSentenceAudioPreparation.Ready) return primary

        if (primary is AnkiSentenceAudioPreparation.Unavailable &&
            primary.failure == AnkiSentenceAudioFailure.EXTRACTION_STREAM_MAPPING_FAILED &&
            input.audioStreamIndex != null
        ) {
            val inventory = resolveInventory(input.copy(audioStreamIndex = null))
            if (inventory is Resolution.Ready) {
                val retry = executeOnce(inventory.input, range)
                if (retry is AnkiSentenceAudioPreparation.Ready) return retry
            }
        }

        return primary
    }

    private suspend fun executeOnce(input: SentenceAudioInputSpec, range: ClosedFloatingPointRange<Double>): AnkiSentenceAudioPreparation {
        val lease = inputAcquirer.acquire(input) ?: return unavailable(AnkiSentenceAudioFailure.SOURCE_UNAVAILABLE, input, SentenceAudioDiagnosticStage.AUDIO_EXTRACTION)
        val output = File(cacheDirectory, "chimahon_sentence_audio_${System.nanoTime()}.m4a")
        val inputCleanup = NativeCleanup(lease::close)
        val outputCleanup = NativeCleanup(output::delete)
        return try {
            output.delete()
            val result = FfmpegRunner.ffmpeg(
                SentenceAudioFfmpegArguments.sentenceAudio(input, lease.ffmpegValue, range.start, range.endInclusive, output.absolutePath, lease.tlsCaFile),
            ) { inputCleanup.nativeFinished(); outputCleanup.nativeFinished() }
            val failure = result.extractionFailure()
            record(SentenceAudioDiagnosticStage.AUDIO_EXTRACTION, input, input.fallback(), failure, result)
            if (failure != null) return unavailable(failure, input, result = result)
            if (!output.isFile || output.length() == 0L) return unavailable(AnkiSentenceAudioFailure.EXTRACTION_OUTPUT_MISSING, input, SentenceAudioDiagnosticStage.OUTPUT_VALIDATION, result)
            val bytes = try { output.readBytes() } catch (e: Exception) {
                return unavailable(AnkiSentenceAudioFailure.EXTRACTION_OUTPUT_READ_FAILED, input, SentenceAudioDiagnosticStage.OUTPUT_READ, result, e.javaClass.name)
            }
            AnkiSentenceAudioPreparation.Ready(AnkiSentenceAudioSource.fromBytes(bytes, "m4a"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            inputCleanup.nativeFinished()
            outputCleanup.nativeFinished()
            unavailable(AnkiSentenceAudioFailure.EXTRACTION_FAILED, input, SentenceAudioDiagnosticStage.AUDIO_EXTRACTION, exceptionType = e.javaClass.name)
        } finally {
            inputCleanup.release()
            outputCleanup.release()
        }
    }

    private fun FfmpegCommandResult.extractionFailure(): AnkiSentenceAudioFailure? = when (this) {
        is FfmpegCommandResult.Success -> null
        FfmpegCommandResult.Failed -> AnkiSentenceAudioFailure.EXTRACTION_FAILED
        is FfmpegCommandResult.FfmpegFailed -> when (failure) {
            FfmpegFailure.STREAM_MAPPING -> AnkiSentenceAudioFailure.EXTRACTION_STREAM_MAPPING_FAILED
            FfmpegFailure.SOURCE_READ -> AnkiSentenceAudioFailure.EXTRACTION_SOURCE_READ_FAILED
            FfmpegFailure.SEEK -> AnkiSentenceAudioFailure.EXTRACTION_SEEK_FAILED
            FfmpegFailure.OUTPUT_WRITE -> AnkiSentenceAudioFailure.EXTRACTION_OUTPUT_WRITE_FAILED
            FfmpegFailure.UNKNOWN -> AnkiSentenceAudioFailure.EXTRACTION_FAILED
        }
    }

    private fun unavailable(
        failure: AnkiSentenceAudioFailure,
        input: SentenceAudioInputSpec?,
        stage: SentenceAudioDiagnosticStage? = null,
        result: FfmpegCommandResult? = null,
        exceptionType: String? = null,
    ): AnkiSentenceAudioPreparation.Unavailable {
        stage?.let { record(it, input, input?.fallback() ?: SentenceAudioDiagnosticFallback.NOT_APPLICABLE, failure, result, exceptionType) }
        return AnkiSentenceAudioPreparation.Unavailable(failure, input?.diagnostic(result = result))
    }

    private fun record(stage: SentenceAudioDiagnosticStage, input: SentenceAudioInputSpec?, fallback: SentenceAudioDiagnosticFallback, failure: AnkiSentenceAudioFailure?, result: FfmpegCommandResult? = null, exceptionType: String? = null) {
        diagnosticLogger.record(SentenceAudioDiagnosticEvent(stage, input, fallback, failure, result, exceptionType))
    }

    private fun SentenceAudioInputSpec.diagnostic(fallback: AnkiSentenceAudioPlayableFallback? = null, result: FfmpegCommandResult? = null) = AnkiSentenceAudioDiagnostic(
        inputSource = when (origin) {
            SentenceAudioInputOrigin.ORIGINAL_VIDEO -> AnkiSentenceAudioInputSource.ORIGINAL_VIDEO
            SentenceAudioInputOrigin.PLAYABLE_VIDEO -> AnkiSentenceAudioInputSource.MPV_PLAYABLE_VIDEO
            SentenceAudioInputOrigin.EXTERNAL_AUDIO -> AnkiSentenceAudioInputSource.MPV_EXTERNAL_AUDIO
        },
        playableFallback = fallback,
        nativeError = result.nativeDiagnosticText(),
    )

    private fun FfmpegCommandResult?.nativeDiagnosticText(): String? {
        val diagnostics = (this as? FfmpegCommandResult.FfmpegFailed)?.nativeDiagnostics ?: return null
        val text = buildString {
            diagnostics.returnCode?.let { appendLine("ffmpeg exit code $it") }
            sequenceOf(diagnostics.failStackTrace, diagnostics.logs).filterNotNull().forEach(::appendLine)
        }
        return SentenceAudioDiagnosticJournal.redact(text.trim())
            .takeIf(String::isNotEmpty)
            ?.take(nativeErrorMaxChars)
    }
    private fun SentenceAudioInputSpec.fallback() = if (origin == SentenceAudioInputOrigin.PLAYABLE_VIDEO) SentenceAudioDiagnosticFallback.ATTEMPTED else SentenceAudioDiagnosticFallback.NOT_APPLICABLE
    private fun AnkiSentenceAudioPlayableFallback.toDiagnosticFallback() = when (this) {
        AnkiSentenceAudioPlayableFallback.MISSING -> SentenceAudioDiagnosticFallback.MISSING
        AnkiSentenceAudioPlayableFallback.SAME_AS_ORIGINAL -> SentenceAudioDiagnosticFallback.SAME_AS_ORIGINAL
        AnkiSentenceAudioPlayableFallback.UNAVAILABLE -> SentenceAudioDiagnosticFallback.UNAVAILABLE
    }
    private fun AnkiSentenceAudioFailure.isPlayableFallbackRetryable() = this == AnkiSentenceAudioFailure.SOURCE_UNAVAILABLE || this == AnkiSentenceAudioFailure.AUDIO_PROBE_FAILED || this == AnkiSentenceAudioFailure.AUDIO_STREAMS_NOT_FOUND

    private sealed interface Resolution {
        data class Ready(val input: SentenceAudioInputSpec) : Resolution
        data class Unavailable(val failure: AnkiSentenceAudioFailure, val diagnostic: AnkiSentenceAudioDiagnostic?) : Resolution
    }
    private enum class ProbeMode(val stage: SentenceAudioDiagnosticStage) {
        SELECTED_RESTRICTED(SentenceAudioDiagnosticStage.SELECTED_AUDIO_PROBE),
        ALL_RESTRICTED(SentenceAudioDiagnosticStage.ALL_AUDIO_PROBE),
        ALL_UNRESTRICTED_DISCOVERY(SentenceAudioDiagnosticStage.AUDIO_DISCOVERY_PROBE),
    }
    private sealed interface ProbeResult { data object SourceUnavailable : ProbeResult; data class Failed(val result: FfmpegCommandResult? = null) : ProbeResult; data class Success(val output: String) : ProbeResult }
}

/** Defer a resource release until FFmpegKit reports that its native work is no longer using it. */
private class NativeCleanup(private val action: () -> Unit) {
    private val lock = Any()
    private var nativeFinished = false
    private var releaseRequested = false
    private var released = false
    fun nativeFinished() = synchronized(lock) { nativeFinished = true; releaseIfReady() }
    fun release() = synchronized(lock) { releaseRequested = true; releaseIfReady() }
    private fun releaseIfReady() { if (nativeFinished && releaseRequested && !released) { released = true; action() } }
}

internal class AndroidSentenceAudioInputAcquirer(
    context: Context,
) : SentenceAudioInputAcquirer {
    private val applicationContext = context.applicationContext
    private val caBundle = File(applicationContext.filesDir, "cacert.pem")

    override suspend fun acquire(input: SentenceAudioInputSpec): SentenceAudioInputLease? = when (input.kind) {
        SentenceAudioInputKind.CONTENT_URI -> acquireContentUri(input.value)
        SentenceAudioInputKind.REMOTE_HTTP -> getCaBundle()?.let { lease(input.value, it.absolutePath) }
        SentenceAudioInputKind.LOCAL_FILE -> lease(input.value)
    }

    private fun getCaBundle(): File? = synchronized(caBundleLock) {
        caBundle.takeIf { it.isFile && it.canRead() && it.length() > 0L } ?: runCatching {
            applicationContext.assets.open("cacert.pem").use { input ->
                caBundle.outputStream().use(input::copyTo)
            }
            caBundle.takeIf { it.isFile && it.canRead() && it.length() > 0L }
        }.getOrNull()
    }

    private fun acquireContentUri(value: String): SentenceAudioInputLease? {
        val ffmpegValue = runCatching {
            FFmpegKitConfig.getSafParameterForRead(applicationContext, Uri.parse(value))
        }.getOrNull() ?: return null
        if (ffmpegValue.isBlank()) return null
        return lease(ffmpegValue)
    }

    private fun lease(value: String, caFile: String? = null): SentenceAudioInputLease = object : SentenceAudioInputLease {
        override val ffmpegValue = value
        override val tlsCaFile = caFile
        override fun close() = Unit
    }

    private companion object {
        val caBundleLock = Any()
    }
}