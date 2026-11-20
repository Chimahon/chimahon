package eu.kanade.tachiyomi.ui.player.mining

import android.content.Context
import android.net.Uri
import chimahon.anki.AnkiSentenceAudioDiagnostic
import chimahon.anki.AnkiSentenceAudioFailure
import chimahon.anki.AnkiSentenceAudioInputSource
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
    val forceMpegTs: Boolean = false,
)

internal fun sanitizeSentenceAudioForLog(url: String): String {
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

private val sensitiveLogQueryNames = setOf("access_token", "api_key", "auth", "authorization", "credential", "credentials", "key", "policy", "signature", "signed", "sig", "lsig", "token")
private val sensitiveQueryPrefixes = setOf("x-amz-", "x-goog-")

internal object SentenceAudioFfmpegArguments {
    fun sentenceAudio(input: SentenceAudioInputSpec, acquired: String, start: Double, end: Double, output: String, tlsCaFile: String? = null) = buildList {
        addInputOptions(input, tlsCaFile); add("-ss"); add(start.seconds()); add("-i"); add(acquired); add("-map"); add(input.audioStreamIndex?.let { "0:$it" } ?: "0:a:0"); add("-vn"); add("-sn"); add("-dn"); add("-t"); add((end - start).seconds()); add("-c:a"); add("aac"); add("-b:a"); add("128k"); add("-y"); add(output)
    }.toTypedArray()
    private fun MutableList<String>.addInputOptions(input: SentenceAudioInputSpec, tlsCaFile: String?) {
        if (input.kind == SentenceAudioInputKind.REMOTE_HTTP) { require(!tlsCaFile.isNullOrBlank()); add("-rw_timeout"); add("15000000") }
        if (isHlsLikeInput(input.value) || input.forceMpegTs) {
            add("-allowed_extensions"); add("ALL"); add("-allowed_segment_extensions"); add("ALL"); add("-extension_picky"); add("0")
        }
        if (input.forceMpegTs) { add("-force_mpegts"); add("1") }
        if (input.headers.isNotEmpty()) { add("-headers"); add(input.headers.joinToString("") { "${it.first}: ${it.second}\r\n" }) }
    }
    private fun Double.seconds() = String.format(Locale.ROOT, "%.6f", this).trimEnd('0').trimEnd('.')
}

internal interface SentenceAudioMpvPropertyReader {
    fun string(name: String): String?
    fun int(name: String): Int?
}

internal data class SentenceAudioMpvSnapshot(
    val playableValue: String?,
    val selectedExternalAudioValue: String?,
    val selectedAudioFfmpegIndex: Int?,
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
            selectedExternalAudioValue = externalFilename,
            selectedAudioFfmpegIndex = ffIndex,
        )
    }
    private data class Track(val index: Int, val type: String?, val id: Int?)
}

internal enum class SentenceAudioDiagnosticStage { REQUEST_VALIDATION, AUDIO_EXTRACTION, OUTPUT_VALIDATION, OUTPUT_READ }
internal data class SentenceAudioDiagnosticEvent(
    val stage: SentenceAudioDiagnosticStage,
    val input: SentenceAudioInputSpec?,
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
            appendLine("input_value_sanitized=${sanitizeSentenceAudioForLog(it.value)}")
        }
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
    val input: SentenceAudioInputSpec?,
    val startSeconds: Double?,
    val endSeconds: Double?,
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
        val input = request.input ?: return unavailable(
            AnkiSentenceAudioFailure.SOURCE_UNAVAILABLE,
            null,
            SentenceAudioDiagnosticStage.REQUEST_VALIDATION,
        )
        val range = request.validRangeOrNull() ?: return unavailable(
            AnkiSentenceAudioFailure.TIMING_UNAVAILABLE,
            null,
            SentenceAudioDiagnosticStage.REQUEST_VALIDATION,
        )
        return withTimeoutOrNull(timeoutMillis) {
            withContext(ioDispatcher) {
                extract(input, range)
            }
        } ?: unavailable(
            AnkiSentenceAudioFailure.EXTRACTION_TIMED_OUT,
            input,
            SentenceAudioDiagnosticStage.AUDIO_EXTRACTION,
        )
    }
    private fun SentenceAudioCaptureRequest.validRangeOrNull(): ClosedFloatingPointRange<Double>? {
        val start = startSeconds ?: return null
        val end = endSeconds ?: return null
        return start.takeIf { it.isFinite() && it >= 0.0 }
            ?.let { validStart -> end.takeIf { it.isFinite() && it > validStart }?.let { validStart..it } }
    }

    private suspend fun extract(input: SentenceAudioInputSpec, range: ClosedFloatingPointRange<Double>): AnkiSentenceAudioPreparation {
        val primary = executeOnce(input, range)
        if (primary is AnkiSentenceAudioPreparation.Ready) return primary

        if (primary is AnkiSentenceAudioPreparation.Unavailable &&
            primary.failure == AnkiSentenceAudioFailure.EXTRACTION_STREAM_MAPPING_FAILED &&
            input.audioStreamIndex != null
        ) {
            val retry = executeOnce(input.copy(audioStreamIndex = null), range)
            if (retry is AnkiSentenceAudioPreparation.Ready) return retry
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
            record(SentenceAudioDiagnosticStage.AUDIO_EXTRACTION, input, failure, result)
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
        stage?.let { record(it, input, failure, result, exceptionType) }
        return AnkiSentenceAudioPreparation.Unavailable(failure, input?.diagnostic(result = result))
    }

    private fun record(stage: SentenceAudioDiagnosticStage, input: SentenceAudioInputSpec?, failure: AnkiSentenceAudioFailure?, result: FfmpegCommandResult? = null, exceptionType: String? = null) {
        diagnosticLogger.record(SentenceAudioDiagnosticEvent(stage, input, failure, result, exceptionType))
    }

    private fun SentenceAudioInputSpec.diagnostic(result: FfmpegCommandResult? = null) = AnkiSentenceAudioDiagnostic(
        inputSource = when (origin) {
            SentenceAudioInputOrigin.ORIGINAL_VIDEO -> AnkiSentenceAudioInputSource.ORIGINAL_VIDEO
            SentenceAudioInputOrigin.PLAYABLE_VIDEO -> AnkiSentenceAudioInputSource.MPV_PLAYABLE_VIDEO
            SentenceAudioInputOrigin.EXTERNAL_AUDIO -> AnkiSentenceAudioInputSource.MPV_EXTERNAL_AUDIO
        },
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
