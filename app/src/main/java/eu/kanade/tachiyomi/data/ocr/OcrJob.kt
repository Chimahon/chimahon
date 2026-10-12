package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicInteger

@OptIn(FlowPreview::class)
class OcrJob(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    private val ocrManager: OcrManager = Injekt.get()
    private val notifier = OcrNotifier(applicationContext)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifier.progress(ocrManager.queueState.value)
        return ForegroundInfo(
            Notifications.ID_OCR_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result = coroutineScope {
        // Show foreground notification immediately to keep worker alive
        setForegroundSafely()
        notifier.dismissStatus()
        val completed = AtomicInteger()
        val updates = launch {
            ocrManager.queueState.sample(500).collect { setForegroundSafely() }
        }

        try {
            ocrManager.runPendingQueue(
                stopRequested = { isStopped || ocrManager.isPaused.value },
                onChapterCompleted = { completed.incrementAndGet() },
            )
            if (ocrManager.isPaused.value) {
                notifier.showPaused(ocrManager.queueState.value)
            } else if (!isStopped) {
                if (ocrManager.hasRunnableTasks()) {
                    start(applicationContext, append = true)
                } else {
                    notifier.showResult(ocrManager.queueState.value, completed.get())
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            logcat(LogPriority.WARN, e) { "OcrJob cancelled while processing OCR queue" }
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "OcrJob failed" }
            notifier.showResult(ocrManager.queueState.value, completed.get())
            Result.failure()
        } finally {
            updates.cancel()
        }
    }

    companion object {
        private const val TAG = "OcrJob"

        fun start(context: Context, append: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<OcrJob>()
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    TAG,
                    if (append) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                    request,
                )
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(TAG)
        }

        fun isRunning(context: Context): Boolean {
            return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(TAG)
                .get()
                .let { list -> list.count { it.state == WorkInfo.State.RUNNING } == 1 }
        }
    }
}
