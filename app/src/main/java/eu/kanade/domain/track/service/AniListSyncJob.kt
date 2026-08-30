package eu.kanade.domain.track.service

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.domain.track.interactor.SyncAniListToLibrary
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import exh.util.WorkerUtil
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class AniListSyncJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val syncAniListToLibrary: SyncAniListToLibrary = Injekt.get()

    override suspend fun doWork(): Result {
        setForegroundSafely()
        return withIOContext {
            try {
                syncAniListToLibrary.await()
                Result.success()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                Result.failure()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val builder = NotificationCompat.Builder(context, Notifications.CHANNEL_LIBRARY_PROGRESS)
            .setSmallIcon(R.drawable.ic_refresh_24dp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(context.stringResource(MR.strings.syncing_ani_list))
        return ForegroundInfo(
            Notifications.ID_ANILIST_SYNC,
            builder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        private const val TAG = "AniListSync"
        private const val WORK_NAME_AUTO = "AniListSync-auto"
        private const val WORK_NAME_MANUAL = "AniListSync-manual"

        fun setupTask(context: Context) {
            val constraints = Constraints(
                requiredNetworkType = NetworkType.CONNECTED,
            )
            val request = PeriodicWorkRequestBuilder<AniListSyncJob>(6, TimeUnit.HOURS, 15, TimeUnit.MINUTES)
                .addTag(TAG)
                .addTag(WORK_NAME_AUTO)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .build()

            context.workManager.enqueueUniquePeriodicWork(
                WORK_NAME_AUTO,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun startNow(context: Context): Boolean {
            val wm = context.workManager
            if (wm.isRunning(TAG)) {
                return false
            }

            val request = OneTimeWorkRequestBuilder<AniListSyncJob>()
                .addTag(TAG)
                .addTag(WORK_NAME_MANUAL)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            wm.enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)

            return true
        }

        suspend fun isPeriodicSyncScheduled(context: Context): Boolean {
            return WorkerUtil.isPeriodicJobScheduled(context, WORK_NAME_AUTO)
        }
    }
}
