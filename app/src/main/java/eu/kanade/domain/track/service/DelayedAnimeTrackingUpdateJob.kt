package eu.kanade.domain.track.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import eu.kanade.domain.track.interactor.TrackEpisode
import eu.kanade.domain.track.store.DelayedAnimeTrackingStore
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class DelayedAnimeTrackingUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        if (runAttemptCount > 3) {
            return Result.failure()
        }

        val getTracks = Injekt.get<GetAnimeTracks>()
        val trackEpisode = Injekt.get<TrackEpisode>()
        val delayedTrackingStore = Injekt.get<DelayedAnimeTrackingStore>()

        withIOContext {
            delayedTrackingStore.getItems()
                .mapNotNull {
                    val track = if (it.jellyfinAnimeId != null) {
                        getTracks.await(it.jellyfinAnimeId).find { track -> track.trackerId == TrackerManager.JELLYFIN }
                    } else {
                        getTracks.awaitOne(it.trackId)
                    }
                    if (track == null) {
                        delayedTrackingStore.remove(it.trackId, it.episodeUrl, it.jellyfinAnimeId)
                    }
                    track?.let { track -> it to track }
                }
                .forEach { (pending, animeTrack) ->
                    if (animeTrack.trackerId == TrackerManager.JELLYFIN && pending.jellyfinAnimeId == null) {
                        // Migrate an older queue entry before a refresh replaces its track row ID.
                        delayedTrackingStore.add(
                            pending.trackId,
                            pending.lastEpisodeSeen.toDouble(),
                            pending.episodeUrl,
                            animeTrack.animeId,
                        )
                        delayedTrackingStore.remove(pending.trackId, pending.episodeUrl)
                    }
                    logcat(LogPriority.DEBUG) {
                        "Updating delayed anime track item: ${animeTrack.animeId}, " +
                            "last episode seen: ${pending.lastEpisodeSeen}"
                    }
                    trackEpisode.await(
                        context,
                        animeTrack.animeId,
                        pending.lastEpisodeSeen.toDouble(),
                        setupJobOnFailure = false,
                        episodeUrl = pending.episodeUrl,
                        trackerId = if (animeTrack.trackerId == TrackerManager.JELLYFIN) TrackerManager.JELLYFIN else null,
                    )
                }
        }

        return if (delayedTrackingStore.getItems().isEmpty()) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "DelayedAnimeTrackingUpdate"

        fun setupTask(context: Context) {
            val constraints = Constraints(
                requiredNetworkType = NetworkType.CONNECTED,
            )

            val request = OneTimeWorkRequestBuilder<DelayedAnimeTrackingUpdateJob>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()

            context.workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
