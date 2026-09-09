package eu.kanade.tachiyomi.data.library

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notify
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelUpdateNotifier(
    private val context: Context,
    private val securityPreferences: SecurityPreferences = Injekt.get(),
) {

    val progressNotificationBuilder by lazy {
        context.notificationBuilder(Notifications.CHANNEL_LIBRARY_PROGRESS) {
            setContentTitle(context.stringResource(MR.strings.app_name))
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setColor(ContextCompat.getColor(context, R.color.ic_launcher))
            setOngoing(true)
            setOnlyAlertOnce(true)
        }
    }

    fun showProgress(completed: Int, total: Int) {
        context.notify(
            Notifications.ID_NOVEL_UPDATES_TO_EXTS,
            Notifications.CHANNEL_LIBRARY_PROGRESS,
        ) {
            setContentTitle(context.stringResource(MR.strings.app_name))
            setContentText("$completed/$total")
            setSmallIcon(R.drawable.ic_refresh_24dp)
            setColor(ContextCompat.getColor(context, R.color.ic_launcher))
            setOngoing(true)
            setOnlyAlertOnce(true)
            setProgress(total, completed, false)
        }
    }

    fun cancelProgress() {
        androidx.core.app.NotificationManagerCompat.from(context)
            .cancel(Notifications.ID_NOVEL_UPDATES_TO_EXTS)
    }

    fun notifyNewChapters(novelTitlesWithCount: Map<String, Int>) {        if (novelTitlesWithCount.isEmpty()) return

        val totalNew = novelTitlesWithCount.values.sum()
        context.notify(
            Notifications.ID_NOVEL_UPDATES_TO_EXTS,
            Notifications.CHANNEL_NEW_NOVEL_CHAPTERS,
        ) {
            setContentTitle(
                context.pluralStringResource(
                    MR.plurals.notification_chapters_generic,
                    totalNew,
                    totalNew,
                ),
            )
            if (!securityPreferences.hideNotificationContent().get()) {
                val text = novelTitlesWithCount.entries.joinToString("\n") { (title, count) ->
                    "$title ($count)"
                }
                setContentText(text)
                setStyle(NotificationCompat.BigTextStyle().bigText(text))
            }
            setSmallIcon(R.drawable.ic_book_24dp)
            setColor(ContextCompat.getColor(context, R.color.ic_launcher))
            setAutoCancel(true)
        }
    }
}
