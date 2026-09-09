package eu.kanade.tachiyomi.data.library

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import chimahon.novel.manager.NovelSourceManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelUpdate
import tachiyomi.domain.novel.model.toSNNovel
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelMetadataUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val sourceManager: NovelSourceManager = Injekt.get()
    private val novelRepository: NovelRepository = Injekt.get()
    private val notifier = NovelUpdateNotifier(context)

    override suspend fun doWork(): Result = withIOContext {
        setForegroundSafely()

        val favorites = novelRepository.getFavorites()
        if (favorites.isEmpty()) return@withIOContext Result.success()

        val semaphore = Semaphore(3)
        coroutineScope {
            favorites.map { novel ->
                async {
                    semaphore.withPermit {
                        // Skip local novels — they have no remote source to fetch metadata from
                        if (novel.isLocal) return@withPermit
                        try {
                            val source = sourceManager.getNovelSource(novel.source) ?: return@withPermit
                            val networkNovel = source.getNovelDetails(novel.toSNNovel())
                            novelRepository.update(
                                NovelUpdate(
                                    id = novel.id,
                                    title = networkNovel.title,
                                    artist = networkNovel.artist,
                                    author = networkNovel.author,
                                    description = networkNovel.description,
                                    genre = networkNovel.genre,
                                    status = networkNovel.status.toLong(),
                                    thumbnailUrl = networkNovel.thumbnail_url,
                                ),
                            )
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            logcat(LogPriority.ERROR, e) { "Failed to update metadata for novel ${novel.title}" }
                        }
                    }
                }
            }.awaitAll()
        }

        Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifier.progressNotificationBuilder
            .setContentTitle("Updating novel metadata...")
            .build()
        return ForegroundInfo(
            Notifications.ID_LIBRARY_PROGRESS,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    companion object {
        private const val TAG = "NovelMetadataUpdate"

        fun startNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<NovelMetadataUpdateJob>()
                .addTag(TAG)
                .build()
            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.KEEP, request)
        }

        fun isRunning(context: Context): Boolean {
            return context.workManager.isRunning(TAG)
        }
    }
}
