package eu.kanade.tachiyomi.data.ocr

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy

internal class OcrNotifier(private val context: Context) {

    private val preferences: SecurityPreferences by injectLazy()

    fun progress(queue: List<OcrQueueItem>): Notification {
        val processing = queue.filter { it.status == OcrQueueStatus.PROCESSING }
        val current = processing.firstOrNull()
        val summary = queueSummary(queue)
        return context.notificationBuilder(Notifications.CHANNEL_OCR_PROGRESS) {
            setSmallIcon(android.R.drawable.stat_sys_download)
            setContentTitle(context.stringResource(MR.strings.ocr_running))
            setContentText(summary)
            if (current != null && !preferences.hideNotificationContent().get()) {
                setContentTitle("${current.manga.title} — ${current.chapter.name}")
                val detail = context.stringResource(
                    MR.strings.ocr_notification_page,
                    current.currentPage,
                    current.totalPages,
                )
                setContentText(detail)
                setStyle(NotificationCompat.BigTextStyle().bigText("$detail\n$summary"))
            }
            setSubText(context.stringResource(MR.strings.ocr_processing))
            setProgress(
                processing.sumOf { it.totalPages },
                processing.sumOf { it.currentPage },
                processing.sumOf { it.totalPages } == 0,
            )
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
            setOnlyAlertOnce(true)
            setOngoing(true)
            addAction(
                R.drawable.ic_pause_24dp,
                context.stringResource(MR.strings.action_pause),
                NotificationReceiver.pauseOcrPendingBroadcast(context),
            )
            addCancelAction()
        }.build()
    }

    fun showPaused(queue: List<OcrQueueItem>) {
        if (queue.isEmpty()) return
        context.notify(
            Notifications.ID_OCR_STATUS,
            context.notificationBuilder(Notifications.CHANNEL_OCR_PROGRESS) {
                setSmallIcon(R.drawable.ic_pause_24dp)
                setContentTitle(context.stringResource(MR.strings.ocr_paused))
                setContentText(queueSummary(queue))
                setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
                setOnlyAlertOnce(true)
                addAction(
                    R.drawable.ic_play_arrow_24dp,
                    context.stringResource(MR.strings.action_resume),
                    NotificationReceiver.resumeOcrPendingBroadcast(context),
                )
                addCancelAction()
            }.build(),
        )
    }

    fun showResult(queue: List<OcrQueueItem>, completed: Int) {
        if (queue.any { it.status.isRunnable() }) return
        val errors = queue.count { it.status == OcrQueueStatus.ERROR }
        val waiting = queue.count { it.status == OcrQueueStatus.WAITING_DOWNLOAD }
        if (errors == 0 && waiting == 0 && completed == 0) return
        context.notify(
            Notifications.ID_OCR_STATUS,
            context.notificationBuilder(Notifications.CHANNEL_OCR_STATUS) {
                setSmallIcon(
                    if (errors > 0) R.drawable.ic_warning_white_24dp else android.R.drawable.stat_sys_download_done,
                )
                setContentTitle(
                    context.stringResource(
                        when {
                            errors > 0 -> MR.strings.ocr_notification_failed
                            waiting > 0 -> MR.strings.ocr_waiting_download
                            else -> MR.strings.ocr_notification_complete
                        },
                    ),
                )
                setContentText(context.stringResource(MR.strings.ocr_notification_result, completed, errors, waiting))
                setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
                setAutoCancel(true)
                setOnlyAlertOnce(true)
                if (errors > 0) {
                    addAction(
                        R.drawable.ic_refresh_24dp,
                        context.stringResource(MR.strings.ocr_retry_failed),
                        NotificationReceiver.retryOcrPendingBroadcast(context),
                    )
                }
                if (waiting > 0) {
                    addAction(
                        R.drawable.ic_pause_24dp,
                        context.stringResource(MR.strings.action_pause),
                        NotificationReceiver.pauseOcrPendingBroadcast(context),
                    )
                }
                if (queue.isNotEmpty()) addCancelAction()
            }.build(),
        )
    }

    fun dismissStatus() = context.cancelNotification(Notifications.ID_OCR_STATUS)

    private fun queueSummary(queue: List<OcrQueueItem>): String = context.stringResource(
        MR.strings.ocr_notification_queue,
        queue.count { it.status == OcrQueueStatus.PROCESSING },
        queue.count { it.status == OcrQueueStatus.PENDING },
        queue.count { it.status == OcrQueueStatus.WAITING_DOWNLOAD },
        queue.count { it.status == OcrQueueStatus.ERROR },
    )

    private fun NotificationCompat.Builder.addCancelAction() {
        addAction(
            R.drawable.ic_close_24dp,
            context.stringResource(MR.strings.action_cancel_all),
            NotificationReceiver.cancelOcrPendingBroadcast(context),
        )
    }
}
