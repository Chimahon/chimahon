package eu.kanade.tachiyomi.data.library

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import chimahon.novel.interactor.FetchNovelInterval
import chimahon.novel.interactor.SyncNovelChapters
import chimahon.novel.interactor.UpdateNovel
import chimahon.novel.manager.NovelSourceManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.isRunning
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_CHARGING
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_NETWORK_NOT_METERED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_ONLY_ON_WIFI
import tachiyomi.domain.library.service.LibraryPreferences.Companion.NOVEL_HAS_UNREAD
import tachiyomi.domain.library.service.LibraryPreferences.Companion.NOVEL_NON_COMPLETED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.NOVEL_NON_READ
import tachiyomi.domain.library.service.LibraryPreferences.Companion.NOVEL_OUTSIDE_RELEASE_PERIOD
import tachiyomi.domain.novel.model.NovelUpdate
import tachiyomi.domain.novel.model.toSNNovel
import tachiyomi.domain.novel.repository.NovelCategoryRepository
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/**
 * Background refresh for favorited extension novels: fetches each source's
 * chapter list and inserts newly released chapters so unread counts, the
 * updates flow, and built-EPUB caches stay current.
 */
class NovelUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val novelRepository: NovelRepository = Injekt.get()
    private val novelSourceManager: NovelSourceManager = Injekt.get()
    private val syncNovelChapters: SyncNovelChapters = Injekt.get()
    private val fetchNovelInterval: FetchNovelInterval = Injekt.get()
    private val updateNovel: UpdateNovel = Injekt.get()
    private val libraryPreferences: LibraryPreferences = Injekt.get()
    private val novelChapterRepository: NovelChapterRepository = Injekt.get()
    private val novelCategoryRepository: NovelCategoryRepository = Injekt.get()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_NOVEL_UPDATES_TO_EXTS,
            NovelUpdateNotifier(context).progressNotificationBuilder.build(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        val sourcesById = novelSourceManager.getCatalogueSources().associateBy { it.id }
        if (sourcesById.isEmpty()) return Result.success()

        val notifier = NovelUpdateNotifier(context)
        val updatedTitles = LinkedHashMap<String, Int>()

        val novels = novelRepository.getFavorites()
        if (novels.isEmpty()) return Result.success(workDataOf(KEY_UPDATED_COUNT to 0))

        // Apply category filtering
        val includedCategories = libraryPreferences.updateNovelCategories().get()
        val excludedCategories = libraryPreferences.updateNovelCategoriesExclude().get()
        val categoryFilteredNovels = if (includedCategories.isEmpty() && excludedCategories.isEmpty()) {
            novels
        } else {
            val categoryMap = novelCategoryRepository.getCategoryIdsByNovelIds(novels.map { it.id })
            novels.filter { novel ->
                val ids = categoryMap[novel.id]?.map { it.toString() }?.toSet().orEmpty()
                val inIncluded = includedCategories.isEmpty() || ids.any { it in includedCategories }
                val inExcluded = ids.any { it in excludedCategories }
                inIncluded && !inExcluded
            }
        }

        // Smart update restrictions
        val restrictions = libraryPreferences.autoUpdateNovelRestrictions().get()

        // Release window computed once: due novels run first, the rest wait
        // for their nextUpdate.
        val now = java.time.ZonedDateTime.now()
        val fetchWindow = fetchNovelInterval.getWindow(now)
        val orderedNovels = categoryFilteredNovels.sortedWith(
            compareBy(
                // Due (never scheduled counts as due) before scheduled-future.
                { novel -> if (novel.nextUpdate <= 0L || novel.nextUpdate <= fetchWindow.second) 0 else 1 },
                { novel -> novel.nextUpdate },
                { novel -> novel.title.lowercase() },
            ),
        )

        setForegroundSafely()
        try {
            for ((index, novel) in orderedNovels.withIndex()) {
                currentCoroutineContext().ensureActive()
                // Skip local novels
                if (novel.isLocal) continue

                val source = sourcesById[novel.source] ?: continue

                // Favorite re-check: the library can change mid-run.
                if (runCatching { novelRepository.getNovelById(novel.id) }.getOrNull()?.favorite != true) {
                    continue
                }

                // Outside the release window: skip until nextUpdate (the pref
                // key existed but was never enforced).
                if (NOVEL_OUTSIDE_RELEASE_PERIOD in restrictions && novel.nextUpdate > fetchWindow.second) {
                    continue
                }

                // Apply smart update filters
                if (restrictions.isNotEmpty()) {
                    val chapters = novelChapterRepository.getChaptersByNovelId(novel.id)
                    val unreadCount = chapters.count { !it.read }
                    val hasStarted = chapters.any { it.read || it.lastPageRead > 0 }
                    val totalChapters = chapters.size

                    if (NOVEL_NON_COMPLETED in restrictions && novel.status == tachiyomi.domain.novel.model.Novel.COMPLETED) continue
                    if (NOVEL_HAS_UNREAD in restrictions && unreadCount != 0) continue
                    if (NOVEL_NON_READ in restrictions && totalChapters > 0 && !hasStarted) continue
                }

                notifier.showProgress(index, orderedNovels.size)
                // Per-item guard: a sync failure skips the novel instead of
                // aborting the whole worker.
                runCatching {
                    val sourceChapters = source.getChapterList(novel.toSNNovel())
                    val result = syncNovelChapters.await(novel.id, sourceChapters)
                    if (result.newChapterCount > 0) {
                        updatedTitles[novel.title] = result.newChapterCount
                        novelRepository.update(
                            NovelUpdate(id = novel.id, lastUpdate = System.currentTimeMillis()),
                        )
                    }
                    // Learn the release rhythm: recompute when never scheduled
                    // or the window already passed.
                    if (novel.fetchInterval == 0 || novel.nextUpdate < fetchWindow.first) {
                        updateNovel.await(fetchNovelInterval.toNovelUpdate(novel, now, fetchWindow))
                    }
                }
            }
        } catch (e: CancellationException) {
            notifier.cancelProgress()
            throw e
        } catch (e: Exception) {
            notifier.cancelProgress()
            return Result.failure(workDataOf(KEY_UPDATED_COUNT to updatedTitles.size))
        }
        notifier.cancelProgress()
        if (updatedTitles.isNotEmpty()) {
            notifier.notifyNewChapters(updatedTitles)
        }
        return Result.success(workDataOf(KEY_UPDATED_COUNT to updatedTitles.size))
    }

    companion object {
        private const val TAG = "NovelUpdate"
        private const val WORK_NAME_AUTO = "NovelUpdate-auto"
        private const val WORK_NAME_MANUAL = "NovelUpdate-manual"
        const val KEY_UPDATED_COUNT = "novel_updated_count"

        fun isRunning(context: Context): Boolean = context.workManager.isRunning(TAG)

        suspend fun isPeriodicUpdateScheduled(context: Context): Boolean =
            context.workManager.getWorkInfosForUniqueWork(WORK_NAME_AUTO).get().any { !it.state.isFinished }

        fun setupTask(context: Context, prefInterval: Int? = null) {
            val preferences = Injekt.get<LibraryPreferences>()
            val interval = prefInterval ?: preferences.autoUpdateInterval().get()
            if (interval > 0) {
                val restrictions = preferences.autoUpdateDeviceRestrictions().get()
                val networkType = if (DEVICE_NETWORK_NOT_METERED in restrictions) {
                    NetworkType.UNMETERED
                } else {
                    NetworkType.CONNECTED
                }
                val networkRequest = NetworkRequest.Builder().apply {
                    removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    if (DEVICE_ONLY_ON_WIFI in restrictions) {
                        addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    }
                    if (DEVICE_NETWORK_NOT_METERED in restrictions) {
                        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    }
                }.build()
                val constraints = Constraints.Builder()
                    .setRequiredNetworkRequest(networkRequest, networkType)
                    .setRequiresCharging(DEVICE_CHARGING in restrictions)
                    .setRequiresBatteryNotLow(true)
                    .build()

                val request = PeriodicWorkRequestBuilder<NovelUpdateJob>(
                    interval.toLong(),
                    TimeUnit.HOURS,
                    10,
                    TimeUnit.MINUTES,
                )
                    .addTag(TAG)
                    .addTag(WORK_NAME_AUTO)
                    .setConstraints(constraints)
                    .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                    .build()

                context.workManager.enqueueUniquePeriodicWork(
                    WORK_NAME_AUTO,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            } else {
                context.workManager.cancelUniqueWork(WORK_NAME_AUTO)
            }
        }

        fun startNow(context: Context): Boolean {
            val wm = context.workManager
            if (wm.isRunning(TAG)) return false

            val request = OneTimeWorkRequestBuilder<NovelUpdateJob>()
                .addTag(TAG)
                .addTag(WORK_NAME_MANUAL)
                .build()
            wm.enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)
            return true
        }
    }
}
