package eu.kanade.tachiyomi.data.notification

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import eu.kanade.tachiyomi.data.animedownload.AnimeDownloadManager
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.ocr.OcrManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.library.anime.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.data.sync.SyncDataJob
import eu.kanade.tachiyomi.data.model.ModelUpdateJob
import eu.kanade.tachiyomi.data.updater.AppUpdateDownloadJob
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.getParcelableExtraCompat
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import logcat.LogPriority
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.episode.interactor.GetEpisode
import tachiyomi.domain.episode.interactor.UpdateEpisode
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.episode.model.toEpisodeUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import eu.kanade.tachiyomi.BuildConfig.APPLICATION_ID as ID

/**
 * Global [BroadcastReceiver] that runs on UI thread
 * Pending Broadcasts should be made from here.
 * NOTE: Use local broadcasts if possible.
 */
@OptIn(DelicateCoroutinesApi::class)
class NotificationReceiver : BroadcastReceiver() {

    private val getManga: GetManga by injectLazy()
    private val getAnime: GetAnime by injectLazy()
    private val getChapter: GetChapter by injectLazy()
    private val getEpisode: GetEpisode by injectLazy()
    private val updateChapter: UpdateChapter by injectLazy()
    private val updateEpisode: UpdateEpisode by injectLazy()
    private val downloadManager: DownloadManager by injectLazy()
    private val animeDownloadManager: AnimeDownloadManager by injectLazy()
    private val ocrManager: OcrManager by injectLazy()

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PAUSE_OCR -> runOcrAction { ocrManager.pauseQueue() }
            ACTION_RESUME_OCR -> runOcrAction { ocrManager.resumeQueue() }
            ACTION_CANCEL_OCR -> runOcrAction { ocrManager.cancelAll() }
            ACTION_RETRY_OCR -> runOcrAction { ocrManager.retryAllErrors() }
            // Dismiss notification
            ACTION_DISMISS_NOTIFICATION -> dismissNotification(context, intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1))
            // Resume the download service
            ACTION_RESUME_DOWNLOADS -> {
                downloadManager.startDownloads()
                animeDownloadManager.startDownloads()
            }
            // Pause the download service
            ACTION_PAUSE_DOWNLOADS -> {
                downloadManager.pauseDownloads()
                animeDownloadManager.pauseDownloads()
            }
            // Clear the download queue
            ACTION_CLEAR_DOWNLOADS -> {
                downloadManager.clearQueue()
                animeDownloadManager.clearQueue()
            }
            // Launch share activity and dismiss notification
            ACTION_SHARE_IMAGE ->
                shareImage(
                    context,
                    intent.getStringExtra(EXTRA_URI)!!.toUri(),
                )
            // Share backup file
            ACTION_SHARE_BACKUP ->
                shareFile(
                    context,
                    intent.getParcelableExtraCompat(EXTRA_URI)!!,
                    "application/x-protobuf+gzip",
                )
            ACTION_CANCEL_RESTORE -> cancelRestore(context)

            ACTION_CANCEL_SYNC -> cancelSync(context)
            // Cancel library update and dismiss notification
            ACTION_CANCEL_LIBRARY_UPDATE -> cancelLibraryUpdate(context)
            ACTION_CANCEL_ANIME_LIBRARY_UPDATE -> cancelAnimeLibraryUpdate(context)
            // Start downloading app update
            ACTION_START_MODEL_UPDATE -> {
                ModelUpdateJob.startUpdate(context)
                context.cancelNotification(Notifications.ID_MODEL_UPDATE)
            }
            ACTION_START_APP_UPDATE -> startDownloadAppUpdate(context, intent)
            // Cancel downloading app update
            ACTION_CANCEL_APP_UPDATE_DOWNLOAD -> cancelDownloadAppUpdate(context)

            // KMK -->
            // Stop Discord RPC service
            ACTION_STOP_DISCORD_RPC -> stopDiscordRPC(context)
            // <-- KMK

            // Open reader activity
            ACTION_OPEN_CHAPTER -> {
                openChapter(
                    context,
                    intent.getLongExtra(EXTRA_MANGA_ID, -1),
                    intent.getLongExtra(EXTRA_CHAPTER_ID, -1),
                )
            }
            ACTION_OPEN_EPISODE -> {
                openEpisode(
                    context,
                    intent.getLongExtra(EXTRA_ANIME_ID, -1),
                    intent.getLongExtra(EXTRA_EPISODE_ID, -1),
                )
            }
            // Mark updated manga chapters as read
            ACTION_MARK_AS_READ -> {
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                if (notificationId > -1) {
                    dismissNotification(context, notificationId, intent.getIntExtra(EXTRA_GROUP_ID, 0))
                }
                val urls = intent.getStringArrayExtra(EXTRA_CHAPTER_URL) ?: return
                val mangaId = intent.getLongExtra(EXTRA_MANGA_ID, -1)
                if (mangaId > -1) {
                    markAsRead(urls, mangaId)
                }
            }
            // Download manga chapters
            ACTION_DOWNLOAD_CHAPTER -> {
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                if (notificationId > -1) {
                    dismissNotification(context, notificationId, intent.getIntExtra(EXTRA_GROUP_ID, 0))
                }
                val urls = intent.getStringArrayExtra(EXTRA_CHAPTER_URL) ?: return
                val mangaId = intent.getLongExtra(EXTRA_MANGA_ID, -1)
                if (mangaId > -1) {
                    downloadChapters(urls, mangaId)
                }
            }
            ACTION_MARK_AS_SEEN -> {
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                if (notificationId > -1) {
                    dismissNotification(context, notificationId, intent.getIntExtra(EXTRA_GROUP_ID, 0))
                }
                val urls = intent.getStringArrayExtra(EXTRA_EPISODE_URL) ?: return
                val animeId = intent.getLongExtra(EXTRA_ANIME_ID, -1)
                if (animeId > -1) {
                    markAsSeen(urls, animeId)
                }
            }
            ACTION_DOWNLOAD_EPISODE -> {
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
                if (notificationId > -1) {
                    dismissNotification(context, notificationId, intent.getIntExtra(EXTRA_GROUP_ID, 0))
                }
                val urls = intent.getStringArrayExtra(EXTRA_EPISODE_URL) ?: return
                val animeId = intent.getLongExtra(EXTRA_ANIME_ID, -1)
                if (animeId > -1) {
                    downloadEpisodes(urls, animeId)
                }
            }
        }
    }

    private fun runOcrAction(action: suspend () -> Unit) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                action()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to handle OCR notification action" }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Dismiss the notification
     *
     * @param notificationId the id of the notification
     */
    private fun dismissNotification(context: Context, notificationId: Int) {
        context.cancelNotification(notificationId)
    }

    /**
     * Called to start share intent to share image
     *
     * @param context context of application
     * @param uri path of file
     */
    private fun shareImage(context: Context, uri: Uri) {
        context.startActivity(uri.toShareIntent(context))
    }

    /**
     * Called to start share intent to share backup file
     *
     * @param context context of application
     * @param uri path of file
     */
    private fun shareFile(context: Context, uri: Uri, fileMimeType: String) {
        context.startActivity(uri.toShareIntent(context, fileMimeType))
    }

    /**
     * Starts reader activity
     *
     * @param context context of application
     * @param mangaId id of manga
     * @param chapterId id of chapter
     */
    private fun openChapter(context: Context, mangaId: Long, chapterId: Long) {
        val manga = runBlocking { getManga.await(mangaId) }
        val chapter = runBlocking { getChapter.await(chapterId) }
        if (manga != null && chapter != null) {
            val intent = ReaderActivity.newIntent(context, manga.id, chapter.id).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        } else {
            context.toast(MR.strings.chapter_error)
        }
    }

    private fun openEpisode(context: Context, animeId: Long, episodeId: Long) {
        val anime = runBlocking { getAnime.await(animeId) }
        val episode = runBlocking { getEpisode.await(episodeId) }
        if (anime != null && episode != null) {
            val intent = PlayerActivity.newIntent(context, anime.id, episode.id).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        } else {
            context.toast(MR.strings.download_error)
        }
    }

    /**
     * Method called when user wants to stop a backup restore job.
     *
     * @param context context of application
     */
    private fun cancelRestore(context: Context) {
        BackupRestoreJob.stop(context)
    }

    /**
     * Method called when user wants to stop a library update
     *
     * @param context context of application
     */
    private fun cancelLibraryUpdate(context: Context) {
        LibraryUpdateJob.stop(context)
        AnimeLibraryUpdateJob.stop(context)
    }

    private fun cancelAnimeLibraryUpdate(context: Context) {
        AnimeLibraryUpdateJob.stop(context)
    }

    private fun startDownloadAppUpdate(context: Context, intent: Intent) {
        val url = intent.getStringExtra(AppUpdateDownloadJob.EXTRA_DOWNLOAD_URL) ?: return
        val title = intent.getStringExtra(AppUpdateDownloadJob.EXTRA_DOWNLOAD_TITLE)
        AppUpdateDownloadJob.start(context, url, title)
    }

    private fun cancelDownloadAppUpdate(context: Context) {
        AppUpdateDownloadJob.stop(context)
    }

    /**
     * Method called when user wants to stop a backup restore job.
     *
     * @param context context of application
     */
    private fun cancelSync(context: Context) {
        SyncDataJob.stop(context)
    }

    /**
     * Method called when user wants to mark manga chapters as read
     *
     * @param chapterUrls URLs of chapter to mark as read
     * @param mangaId id of manga
     */
    private fun markAsRead(chapterUrls: Array<String>, mangaId: Long) {
        val downloadPreferences: DownloadPreferences = Injekt.get()
        val sourceManager: SourceManager = Injekt.get()

        launchIO {
            val toUpdate = chapterUrls.mapNotNull { getChapter.await(it, mangaId) }
                .map {
                    val chapter = it.copy(read = true)
                    if (downloadPreferences.removeAfterMarkedAsRead().get()) {
                        val manga = getManga.await(mangaId)
                        if (manga != null) {
                            val source = sourceManager.get(manga.source)
                            if (source != null) {
                                downloadManager.deleteChapters(listOf(it), manga, source)
                            }
                        }
                    }
                    chapter.toChapterUpdate()
                }
            updateChapter.awaitAll(toUpdate)
        }
    }

    /**
     * Method called when user wants to download chapters
     *
     * @param chapterUrls URLs of chapter to download
     * @param mangaId id of manga
     */
    private fun downloadChapters(chapterUrls: Array<String>, mangaId: Long) {
        launchIO {
            val manga = getManga.await(mangaId) ?: return@launchIO
            val chapters = chapterUrls.mapNotNull { getChapter.await(it, mangaId) }
            downloadManager.downloadChapters(manga, chapters)
        }
    }

    private fun markAsSeen(episodeUrls: Array<String>, animeId: Long) {
        launchIO {
            val toUpdate = episodeUrls.mapNotNull { getEpisode.await(it, animeId) }
                .map { it.copy(seen = true).toEpisodeUpdate() }
            updateEpisode.awaitAll(toUpdate)
        }
    }

    private fun downloadEpisodes(episodeUrls: Array<String>, animeId: Long) {
        launchIO {
            val anime = getAnime.await(animeId) ?: return@launchIO
            val episodes = episodeUrls.mapNotNull { getEpisode.await(it, animeId) }
            animeDownloadManager.downloadEpisodes(anime, episodes)
        }
    }

    // KMK -->
    /**
     * Stop the Discord RPC service
     *
     * @param context context of application
     */
    private fun stopDiscordRPC(context: Context) {
        val serviceIntent = Intent(context, eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService::class.java)
        context.stopService(serviceIntent)
        context.cancelNotification(Notifications.ID_DISCORD_RPC)
    }
    // <-- KMK

    companion object {
        private const val NAME = "NotificationReceiver"

        private const val ACTION_SHARE_IMAGE = "$ID.$NAME.SHARE_IMAGE"

        private const val ACTION_SHARE_BACKUP = "$ID.$NAME.SEND_BACKUP"

        private const val ACTION_CANCEL_RESTORE = "$ID.$NAME.CANCEL_RESTORE"

        private const val ACTION_CANCEL_SYNC = "$ID.$NAME.CANCEL_SYNC"

        private const val ACTION_CANCEL_LIBRARY_UPDATE = "$ID.$NAME.CANCEL_LIBRARY_UPDATE"
        private const val ACTION_CANCEL_ANIME_LIBRARY_UPDATE = "$ID.$NAME.CANCEL_ANIME_LIBRARY_UPDATE"

        private const val ACTION_START_MODEL_UPDATE = "$ID.$NAME.START_MODEL_UPDATE"
        private const val ACTION_START_APP_UPDATE = "$ID.$NAME.ACTION_START_APP_UPDATE"
        private const val ACTION_CANCEL_APP_UPDATE_DOWNLOAD = "$ID.$NAME.CANCEL_APP_UPDATE_DOWNLOAD"

        private const val ACTION_MARK_AS_READ = "$ID.$NAME.MARK_AS_READ"
        private const val ACTION_MARK_AS_SEEN = "$ID.$NAME.MARK_AS_SEEN"
        private const val ACTION_OPEN_CHAPTER = "$ID.$NAME.ACTION_OPEN_CHAPTER"
        private const val ACTION_OPEN_EPISODE = "$ID.$NAME.ACTION_OPEN_EPISODE"
        private const val ACTION_DOWNLOAD_CHAPTER = "$ID.$NAME.ACTION_DOWNLOAD_CHAPTER"
        private const val ACTION_DOWNLOAD_EPISODE = "$ID.$NAME.ACTION_DOWNLOAD_EPISODE"

        private const val ACTION_RESUME_DOWNLOADS = "$ID.$NAME.ACTION_RESUME_DOWNLOADS"
        private const val ACTION_PAUSE_DOWNLOADS = "$ID.$NAME.ACTION_PAUSE_DOWNLOADS"
        private const val ACTION_CLEAR_DOWNLOADS = "$ID.$NAME.ACTION_CLEAR_DOWNLOADS"
        private const val ACTION_PAUSE_OCR = "$ID.$NAME.ACTION_PAUSE_OCR"
        private const val ACTION_RESUME_OCR = "$ID.$NAME.ACTION_RESUME_OCR"
        private const val ACTION_CANCEL_OCR = "$ID.$NAME.ACTION_CANCEL_OCR"
        private const val ACTION_RETRY_OCR = "$ID.$NAME.ACTION_RETRY_OCR"

        private const val ACTION_DISMISS_NOTIFICATION = "$ID.$NAME.ACTION_DISMISS_NOTIFICATION"

        // KMK -->
        private const val ACTION_STOP_DISCORD_RPC = "$ID.$NAME.STOP_DISCORD_RPC"
        // <-- KMK

        private const val EXTRA_URI = "$ID.$NAME.URI"
        private const val EXTRA_NOTIFICATION_ID = "$ID.$NAME.NOTIFICATION_ID"
        private const val EXTRA_GROUP_ID = "$ID.$NAME.EXTRA_GROUP_ID"
        private const val EXTRA_MANGA_ID = "$ID.$NAME.EXTRA_MANGA_ID"
        private const val EXTRA_CHAPTER_ID = "$ID.$NAME.EXTRA_CHAPTER_ID"
        private const val EXTRA_CHAPTER_URL = "$ID.$NAME.EXTRA_CHAPTER_URL"
        private const val EXTRA_ANIME_ID = "$ID.$NAME.EXTRA_ANIME_ID"
        private const val EXTRA_EPISODE_ID = "$ID.$NAME.EXTRA_EPISODE_ID"
        private const val EXTRA_EPISODE_URL = "$ID.$NAME.EXTRA_EPISODE_URL"

        /**
         * Returns a [PendingIntent] that resumes the download of a chapter
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun pauseOcrPendingBroadcast(context: Context) = ocrPendingBroadcast(context, ACTION_PAUSE_OCR)

        internal fun resumeOcrPendingBroadcast(context: Context) = ocrPendingBroadcast(context, ACTION_RESUME_OCR)

        internal fun cancelOcrPendingBroadcast(context: Context) = ocrPendingBroadcast(context, ACTION_CANCEL_OCR)

        internal fun retryOcrPendingBroadcast(context: Context) = ocrPendingBroadcast(context, ACTION_RETRY_OCR)

        private fun ocrPendingBroadcast(context: Context, action: String): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        internal fun resumeDownloadsPendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_RESUME_DOWNLOADS
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that pauses the download queue
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun pauseDownloadsPendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_PAUSE_DOWNLOADS
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns a [PendingIntent] that clears the download queue
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun clearDownloadsPendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_CLEAR_DOWNLOADS
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that starts a service which dismissed the notification
         *
         * @param context context of application
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun dismissNotificationPendingBroadcast(context: Context, notificationId: Int): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_DISMISS_NOTIFICATION
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that starts a service which dismissed the notification
         *
         * @param context context of application
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun dismissNotification(context: Context, notificationId: Int, groupId: Int? = null) {
            /*
            Group notifications always have at least 2 notifications:
            - Group summary notification
            - Single manga notification

            If the single notification is dismissed by the system, ie by a user swipe or tapping on the notification,
            it will auto dismiss the group notification if there's no other single updates.

            When programmatically dismissing this notification, the group notification is not automatically dismissed.
             */
            val groupKey = context.notificationManager.activeNotifications.find {
                it.id == notificationId
            }?.groupKey

            if (groupId != null && groupId != 0 && !groupKey.isNullOrEmpty()) {
                val notifications = context.notificationManager.activeNotifications.filter {
                    it.groupKey == groupKey
                }

                if (notifications.size == 2) {
                    context.cancelNotification(groupId)
                    return
                }
            }

            context.cancelNotification(notificationId)
        }

        /**
         * Returns [PendingIntent] that starts a share activity
         *
         * @param context context of application
         * @param uri location path of file
         * @return [PendingIntent]
         */
        internal fun shareImagePendingBroadcast(context: Context, uri: Uri): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_SHARE_IMAGE
                putExtra(EXTRA_URI, uri.toString())
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that starts a reader activity containing chapter.
         *
         * @param context context of application
         * @param manga manga of chapter
         * @param chapter chapter that needs to be opened
         */
        internal fun openChapterPendingActivity(context: Context, manga: Manga, chapter: Chapter): PendingIntent {
            val newIntent = ReaderActivity.newIntent(context, manga.id, chapter.id)
            return PendingIntent.getActivity(
                context,
                manga.id.hashCode(),
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that starts a player activity containing episode.
         *
         * @param context context of application
         * @param anime anime of episode
         * @param episode episode that needs to be opened
         */
        internal fun openEpisodePendingActivity(context: Context, anime: Anime, episode: Episode): PendingIntent {
            val newIntent = PlayerActivity.newIntent(context, anime.id, episode.id)
            return PendingIntent.getActivity(
                context,
                anime.id.hashCode(),
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that opens the manga info controller.
         *
         * @param context context of application
         * @param manga manga of chapter
         */
        internal fun openChapterPendingActivity(context: Context, manga: Manga, groupId: Int): PendingIntent {
            val newIntent =
                Intent(context, MainActivity::class.java).setAction(Constants.SHORTCUT_MANGA)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(Constants.MANGA_EXTRA, manga.id)
                    .putExtra("notificationId", manga.id.hashCode())
                    .putExtra("groupId", groupId)
            return PendingIntent.getActivity(
                context,
                manga.id.hashCode(),
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that marks a chapter as read and deletes it if preferred
         *
         * @param context context of application
         * @param manga manga of chapter
         */
        internal fun markAsReadPendingBroadcast(
            context: Context,
            manga: Manga,
            chapters: Array<Chapter>,
            groupId: Int,
        ): PendingIntent {
            val newIntent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_MARK_AS_READ
                putExtra(EXTRA_CHAPTER_URL, chapters.map { it.url }.toTypedArray())
                putExtra(EXTRA_MANGA_ID, manga.id)
                putExtra(EXTRA_NOTIFICATION_ID, manga.id.hashCode())
                putExtra(EXTRA_GROUP_ID, groupId)
            }
            return PendingIntent.getBroadcast(
                context,
                manga.id.hashCode(),
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that downloads chapters
         *
         * @param context context of application
         * @param manga manga of chapter
         */
        internal fun downloadChaptersPendingBroadcast(
            context: Context,
            manga: Manga,
            chapters: Array<Chapter>,
            groupId: Int,
        ): PendingIntent {
            val newIntent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_DOWNLOAD_CHAPTER
                putExtra(EXTRA_CHAPTER_URL, chapters.map { it.url }.toTypedArray())
                putExtra(EXTRA_MANGA_ID, manga.id)
                putExtra(EXTRA_NOTIFICATION_ID, manga.id.hashCode())
                putExtra(EXTRA_GROUP_ID, groupId)
            }
            return PendingIntent.getBroadcast(
                context,
                manga.id.hashCode(),
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that opens the manga info controller
         *
         * @param context context of application
         * @param mangaId id of the entry to open
         */
        internal fun openEntryPendingActivity(context: Context, mangaId: Long): PendingIntent {
            val newIntent = Intent(context, MainActivity::class.java).setAction(Constants.SHORTCUT_MANGA)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(Constants.MANGA_EXTRA, mangaId)
                .putExtra("notificationId", mangaId.hashCode())

            return PendingIntent.getActivity(
                context,
                mangaId.hashCode(),
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that opens the anime info controller
         *
         * @param context context of application
         * @param animeId id of the entry to open
         */
        internal fun openAnimeEntryPendingActivity(context: Context, animeId: Long): PendingIntent {
            val newIntent = Intent(context, MainActivity::class.java).setAction(Constants.SHORTCUT_ANIME)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(Constants.ANIME_EXTRA, animeId)
                .putExtra("notificationId", animeId.hashCode())

            return PendingIntent.getActivity(
                context,
                animeId.hashCode(),
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that opens the anime info controller.
         *
         * @param context context of application
         * @param anime anime of episode
         */
        internal fun openEpisodePendingActivity(context: Context, anime: Anime, groupId: Int): PendingIntent {
            val newIntent = Intent(context, MainActivity::class.java).setAction(Constants.SHORTCUT_ANIME)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(Constants.ANIME_EXTRA, anime.id)
                .putExtra("notificationId", anime.id.hashCode())
                .putExtra("groupId", groupId)
            return PendingIntent.getActivity(
                context,
                anime.id.hashCode(),
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that marks episodes as seen.
         *
         * @param context context of application
         * @param anime anime of episodes
         * @param episodes episodes to mark seen
         */
        internal fun markAsSeenPendingBroadcast(
            context: Context,
            anime: Anime,
            episodes: Array<Episode>,
            groupId: Int,
        ): PendingIntent {
            val newIntent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_MARK_AS_SEEN
                putExtra(EXTRA_EPISODE_URL, episodes.map { it.url }.toTypedArray())
                putExtra(EXTRA_ANIME_ID, anime.id)
                putExtra(EXTRA_NOTIFICATION_ID, anime.id.hashCode())
                putExtra(EXTRA_GROUP_ID, groupId)
            }
            return PendingIntent.getBroadcast(
                context,
                anime.id.hashCode(),
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that downloads episodes.
         *
         * @param context context of application
         * @param anime anime of episodes
         * @param episodes episodes to download
         */
        internal fun downloadEpisodesPendingBroadcast(
            context: Context,
            anime: Anime,
            episodes: Array<Episode>,
            groupId: Int,
        ): PendingIntent {
            val newIntent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_DOWNLOAD_EPISODE
                putExtra(EXTRA_EPISODE_URL, episodes.map { it.url }.toTypedArray())
                putExtra(EXTRA_ANIME_ID, anime.id)
                putExtra(EXTRA_NOTIFICATION_ID, anime.id.hashCode())
                putExtra(EXTRA_GROUP_ID, groupId)
            }
            return PendingIntent.getBroadcast(
                context,
                anime.id.hashCode(),
                newIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that starts a service which stops the library update
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun cancelLibraryUpdatePendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_CANCEL_LIBRARY_UPDATE
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that starts a service which stops the anime library update.
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun cancelAnimeLibraryUpdatePendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_CANCEL_ANIME_LIBRARY_UPDATE
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that starts the [AppUpdateDownloadJob] to download an app update.
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun updateModelsPendingBroadcast(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            Notifications.ID_MODEL_UPDATE,
            Intent(context, NotificationReceiver::class.java).setAction(ACTION_START_MODEL_UPDATE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        internal fun downloadAppUpdatePendingBroadcast(
            context: Context,
            url: String,
            title: String? = null,
        ): PendingIntent {
            return Intent(context, NotificationReceiver::class.java).run {
                action = ACTION_START_APP_UPDATE
                putExtra(AppUpdateDownloadJob.EXTRA_DOWNLOAD_URL, url)
                title?.let { putExtra(AppUpdateDownloadJob.EXTRA_DOWNLOAD_TITLE, it) }
                PendingIntent.getBroadcast(
                    context,
                    Notifications.ID_APP_UPDATER,
                    this,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        }

        /**
         *
         */
        internal fun cancelDownloadAppUpdatePendingBroadcast(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_CANCEL_APP_UPDATE_DOWNLOAD
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that opens the extensions controller.
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun openExtensionsPendingActivity(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Constants.SHORTCUT_EXTENSIONS
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that directly launches a share activity for a backup file.
         *
         * @param context context of application
         * @param uri uri of backup file
         * @return [PendingIntent]
         */
        internal fun shareBackupPendingActivity(context: Context, uri: Uri): PendingIntent {
            val intent = uri.toShareIntent(context, "application/x-protobuf+gzip").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that opens the error log file in an external viewer
         *
         * @param context context of application
         * @param uri uri of error log file
         * @return [PendingIntent]
         */
        internal fun openErrorLogPendingActivity(context: Context, uri: Uri): PendingIntent {
            val intent = Intent().apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, "text/plain")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }

        // KMK -->
        /**
         * Returns [PendingIntent] that opens the error log file in an external viewer
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun openErrorLogPendingActivity(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Constants.SHORTCUT_LIBRARY_UPDATE_ERRORS
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        // KMK <--

        /**
         * Returns [PendingIntent] that cancels a backup restore job.
         *
         * @param context context of application
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun cancelRestorePendingBroadcast(context: Context, notificationId: Int): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_CANCEL_RESTORE
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * Returns [PendingIntent] that cancels a sync restore job.
         *
         * @param context context of application
         * @param notificationId id of notification
         * @return [PendingIntent]
         */
        internal fun cancelSyncPendingBroadcast(context: Context, notificationId: Int): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_CANCEL_SYNC
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        // KMK -->
        /**
         * Returns a [PendingIntent] that stops the Discord RPC service
         *
         * @param context context of application
         * @return [PendingIntent]
         */
        internal fun stopDiscordRPCService(context: Context): PendingIntent {
            val intent = Intent(context, NotificationReceiver::class.java).apply {
                action = ACTION_STOP_DISCORD_RPC
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        // KMK <--
    }
}
