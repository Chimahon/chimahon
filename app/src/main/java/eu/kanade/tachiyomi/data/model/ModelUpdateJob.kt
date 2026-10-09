package eu.kanade.tachiyomi.data.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.ocr.ModelDownloader
import eu.kanade.tachiyomi.data.panel.PanelModelDownloader
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class ModelUpdateJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val panelDownloader by lazy { Injekt.get<PanelModelDownloader>() }

    override suspend fun doWork(): Result {
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
        if (network?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return Result.retry()
        val downloader = Injekt.get<ModelDownloader>()
        if (!downloader.isDownloaded && !downloader.isPaddleDownloaded && !panelDownloader.isDownloaded) {
            applicationContext.cancelNotification(Notifications.ID_MODEL_UPDATE)
            return Result.success()
        }
        val download = inputData.getBoolean(DOWNLOAD, false)
        val preferences = Injekt.get<PreferenceStore>()
        val lastCheck = preferences.getLong(Preference.appStateKey("models_last_update_check"), 0L)
        val lastAppVersion = preferences.getInt(Preference.appStateKey("models_last_update_app_version"), 0)
        if (!download && lastAppVersion.get() == BuildConfig.VERSION_CODE &&
            System.currentTimeMillis() - lastCheck.get() < TimeUnit.DAYS.toMillis(15)
        ) {
            notifyUpdates(downloader)
            return Result.success()
        }
        val ocrChecked = (!downloader.isDownloaded && !downloader.isPaddleDownloaded) || downloader.checkForUpdates()
        val panelChecked = !panelDownloader.isDownloaded || panelDownloader.checkForUpdates()
        if ((!ocrChecked || !panelChecked) && !download) {
            notifyUpdates(downloader)
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
        if (ocrChecked && panelChecked) {
            lastCheck.set(System.currentTimeMillis())
            lastAppVersion.set(BuildConfig.VERSION_CODE)
        }
        if (download && downloader.updateInstalledModels().isFailure) {
            notifyUpdates(downloader)
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
        if (download && panelDownloader.hasUpdate && panelDownloader.downloadWithNotifications(force = true).isFailure) {
            notifyUpdates(downloader)
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
        notifyUpdates(downloader)
        if (!ocrChecked || !panelChecked) return if (runAttemptCount < 3) Result.retry() else Result.failure()
        return Result.success()
    }

    private fun notifyUpdates(downloader: ModelDownloader) {
        val pending = buildList {
            if (downloader.hasLensUpdate) add(applicationContext.stringResource(MR.strings.model_ocr_lens))
            if (downloader.hasPaddleUpdate) add(applicationContext.stringResource(MR.strings.model_ocr_paddle))
            if (panelDownloader.hasUpdate) add(applicationContext.stringResource(MR.strings.model_panel_detection))
        }
        if (pending.isEmpty()) {
            applicationContext.cancelNotification(Notifications.ID_MODEL_UPDATE)
            return
        }
        val action = NotificationReceiver.updateModelsPendingBroadcast(applicationContext)
        applicationContext.notify(Notifications.ID_MODEL_UPDATE, Notifications.CHANNEL_MODEL_UPDATE) {
            setSmallIcon(android.R.drawable.stat_sys_download)
            setContentTitle(applicationContext.stringResource(MR.strings.model_update_notification_title))
            setContentText(applicationContext.stringResource(MR.strings.model_update_notification_text, pending.joinToString()))
            setContentIntent(action)
            addAction(android.R.drawable.stat_sys_download, applicationContext.stringResource(MR.strings.model_update_notification_action), action)
            setOnlyAlertOnce(true)
            setAutoCancel(true)
        }
    }

    companion object {
        private const val CHECK_WORK = "model-update-check"
        private const val UPDATE_WORK = "model-update-download"
        private const val DOWNLOAD = "download"

        fun checkOnAppOpen(context: Context) = enqueue(context, false)
        fun startUpdate(context: Context) = enqueue(context, true)

        private fun enqueue(context: Context, download: Boolean) {
            val request = OneTimeWorkRequestBuilder<ModelUpdateJob>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .setInputData(workDataOf(DOWNLOAD to download))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                if (download) UPDATE_WORK else CHECK_WORK,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
