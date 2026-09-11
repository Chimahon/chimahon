package eu.kanade.tachiyomi.ui.player.mining

import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeSession
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.LogRedirectionStrategy
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal enum class FfmpegFailure { STREAM_MAPPING, SOURCE_READ, SEEK, OUTPUT_WRITE, UNKNOWN }

internal data class FfmpegNativeFailureDiagnostics(val returnCode: Int?, val failStackTrace: String?, val logs: String?)

internal sealed interface FfmpegCommandResult {
    data class Success(val output: String = "") : FfmpegCommandResult
    data class FfmpegFailed(val failure: FfmpegFailure, val nativeDiagnostics: FfmpegNativeFailureDiagnostics? = null) : FfmpegCommandResult
    data object Failed : FfmpegCommandResult
}

internal fun classifyFfmpegFailure(failStackTrace: String?, logs: String?): FfmpegFailure {
    val detail = sequenceOf(failStackTrace, logs).filterNotNull().joinToString("\n").lowercase()
    return when {
        "stream map" in detail && "matches no streams" in detail -> FfmpegFailure.STREAM_MAPPING
        "could not seek" in detail || "failed to seek" in detail || "invalid seek" in detail -> FfmpegFailure.SEEK
        "could not write header for output" in detail || "error opening output" in detail || "failed to avio_open" in detail || "error writing trailer" in detail || "error muxing a packet" in detail -> FfmpegFailure.OUTPUT_WRITE
        "http error" in detail || "server returned" in detail || "failed to open segment" in detail || "error when loading first segment" in detail || "unable to open resource" in detail || "connection refused" in detail || "connection reset by peer" in detail || "network is unreachable" in detail || "connection timed out" in detail -> FfmpegFailure.SOURCE_READ
        else -> FfmpegFailure.UNKNOWN
    }
}

/**
 * True for HLS-ish inputs: `.m3u8` URLs, plus loopback hosts (extension localhost
 * proxies serve HLS without `.m3u8` in the URL). Only these get the HLS demuxer's
 * segment flags — ffmpeg aborts any other input with "Option not found" if present.
 */
internal fun isHlsLikeInput(value: String): Boolean {
    if (value.substringBefore('?').endsWith(".m3u8", ignoreCase = true) || value.contains(".m3u8", ignoreCase = true)) return true
    val host = value.substringAfter("://", "").substringBefore("/").substringBefore(":").lowercase()
    return host == "127.0.0.1" || host == "localhost" || host.startsWith("[")
}

/**
 * Shared suspend runner for bundled ffmpeg/ffprobe jobs. Logs are never printed to the Android
 * console; every session is stored so it can be cancelled and its output inspected.
 */
internal object FfmpegRunner {
    suspend fun ffmpeg(arguments: Array<String>, onNativeFinished: () -> Unit = {}): FfmpegCommandResult =
        execute(
            createSession = { FFmpegSession.create(arguments, {}, discardLogCallback, discardStatisticsCallback, LogRedirectionStrategy.NEVER_PRINT_LOGS) },
            runSession = FFmpegKitConfig::ffmpegExecute,
            cancelSession = FFmpegSession::cancel,
            resultFor = { session ->
                if (ReturnCode.isSuccess(session.getReturnCode())) {
                    FfmpegCommandResult.Success(ffmpegOutput(session))
                } else {
                    failed(session.getReturnCode(), session.getFailStackTrace(), session.getAllLogsAsString(failureLogWaitMillis))
                }
            },
            onNativeFinished = onNativeFinished,
        )

    suspend fun ffprobe(arguments: Array<String>, onNativeFinished: () -> Unit = {}): FfmpegCommandResult =
        execute(
            createSession = { FFprobeSession.create(arguments, {}, discardLogCallback, LogRedirectionStrategy.NEVER_PRINT_LOGS) },
            runSession = FFmpegKitConfig::ffprobeExecute,
            cancelSession = FFprobeSession::cancel,
            resultFor = { session ->
                if (ReturnCode.isSuccess(session.getReturnCode())) {
                    FfmpegCommandResult.Success(session.getOutput()?.trim() ?: "")
                } else {
                    failed(session.getReturnCode(), session.getFailStackTrace(), session.getAllLogsAsString(failureLogWaitMillis))
                }
            },
            onNativeFinished = onNativeFinished,
        )

    private fun ffmpegOutput(session: FFmpegSession): String = buildString {
        session.getOutput()?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
        session.getAllLogsAsString(0)?.takeIf { it.isNotBlank() }?.let { append(it) }
    }.trim()

    private fun failed(returnCode: ReturnCode?, trace: String?, logs: String?): FfmpegCommandResult =
        FfmpegCommandResult.FfmpegFailed(
            classifyFfmpegFailure(trace, logs),
            FfmpegNativeFailureDiagnostics(returnCode?.value, trace, logs),
        )

    private suspend fun <Session : Any> execute(
        createSession: () -> Session,
        runSession: (Session) -> Unit,
        cancelSession: (Session) -> Unit,
        resultFor: (Session) -> FfmpegCommandResult,
        onNativeFinished: () -> Unit,
    ): FfmpegCommandResult = suspendCancellableCoroutine { cont ->
        val started = AtomicBoolean(false)
        val nativeDone = AtomicBoolean(false)
        fun nativeFinished() { if (nativeDone.compareAndSet(false, true)) runCatching(onNativeFinished) }

        val session = try { createSession() } catch (_: Exception) {
            started.set(true); nativeFinished(); cont.resume(FfmpegCommandResult.Failed); return@suspendCancellableCoroutine
        }

        cont.invokeOnCancellation {
            if (!nativeDone.get() && !started.compareAndSet(false, true)) runCatching { cancelSession(session) }
        }

        try {
            Dispatchers.IO.dispatch(cont.context, Runnable {
                if (!started.compareAndSet(false, true)) {
                    nativeFinished()
                } else {
                    val result = try {
                        runSession(session)
                        runCatching { resultFor(session) }.getOrDefault(FfmpegCommandResult.Failed)
                    } catch (_: Exception) {
                        FfmpegCommandResult.Failed
                    } finally {
                        nativeFinished()
                    }
                    if (cont.isActive) cont.resume(result)
                }
            })
        } catch (_: Exception) {
            if (!started.compareAndSet(false, true)) runCatching { cancelSession(session) }
            nativeFinished()
            if (cont.isActive) cont.resume(FfmpegCommandResult.Failed)
        }
    }
    private const val failureLogWaitMillis = 1_000
    private val discardLogCallback = LogCallback {}
    private val discardStatisticsCallback = StatisticsCallback {}
}