package chimahon.novel.download

import android.content.Context
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

class NovelDownloadNotifier(private val context: Context) {

    private val progressNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_NOVEL_DOWNLOADER_PROGRESS) {
            setSmallIcon(android.R.drawable.stat_sys_download)
            setAutoCancel(false)
            setOngoing(true)
            setShowWhen(false)
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
        }
    }

    private val errorNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_NOVEL_DOWNLOADER_ERROR) {
            setSmallIcon(R.drawable.ic_photo_24dp)
            setAutoCancel(true)
            setContentIntent(NotificationHandler.openDownloadManagerPendingActivity(context))
        }
    }

    fun showProgress(title: String, chapterName: String, progress: Int, max: Int) {
        val notification = progressNotificationBuilder
            .setContentTitle(title)
            .setContentText(chapterName)
            .setProgress(max, progress, false)
            .build()
        context.notificationManager.notify(Notifications.ID_NOVEL_DOWNLOAD_PROGRESS, notification)
    }

    fun showPaused() {
        val notification = progressNotificationBuilder
            .setContentTitle(context.stringResource(MR.strings.download_notifier_downloader_title))
            .setContentText(context.stringResource(MR.strings.download_notifier_download_paused))
            .setSmallIcon(R.drawable.ic_pause_24dp)
            .setAutoCancel(false)
            .setProgress(0, 0, false)
            .build()
        context.notificationManager.notify(Notifications.ID_NOVEL_DOWNLOAD_PROGRESS, notification)
    }

    fun dismissProgress() {
        context.notificationManager.cancel(Notifications.ID_NOVEL_DOWNLOAD_PROGRESS)
    }

    fun showError(title: String, error: String?) {
        val notification = errorNotificationBuilder
            .setContentTitle(title)
            .setContentText(error ?: context.stringResource(MR.strings.download_notifier_unknown_error))
            .build()
        context.notificationManager.notify(Notifications.ID_NOVEL_DOWNLOAD_ERROR, notification)
    }
}
