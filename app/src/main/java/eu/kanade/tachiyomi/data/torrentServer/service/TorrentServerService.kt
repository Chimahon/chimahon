package eu.kanade.tachiyomi.data.torrentServer.service

import android.app.Application
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.torrentServer.TorrentServerApi
import eu.kanade.tachiyomi.torrentServer.TorrentServerUtils
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.coroutines.EmptyCoroutineContext

class TorrentServerService : Service() {
    private val serviceScope = CoroutineScope(EmptyCoroutineContext)
    private val applicationContext = Injekt.get<Application>()
    private var timedOut = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (timedOut) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_START -> {
                startServer()
                if (!notification(applicationContext)) {
                    timedOut = true
                    stopSelf(startId)
                    stopServer()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }
            ACTION_STOP -> {
                stopServer()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15+ limits dataSync foreground services. Stop cleanly when the
     * limit is reached instead of letting the system kill the process.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        timedOut = true
        stopSelf()
        stopServer()
    }

    private fun startServer() {
        serviceScope.launch {
            if (TorrentServerApi.echo() == "") {
                torrServer.TorrServer.startTorrentServer(filesDir.absolutePath)
                wait(10)
                TorrentServerUtils.setTrackersList()
            }
        }
    }

    private fun stopServer() {
        serviceScope.launch {
            torrServer.TorrServer.stopTorrentServer()
            TorrentServerApi.shutdown()
            applicationContext.cancelNotification(Notifications.ID_TORRENT_SERVER)
            stopSelf()
        }
    }

    private fun notification(context: Context): Boolean {
        val exitPendingIntent = PendingIntent.getService(
            applicationContext,
            0,
            Intent(applicationContext, TorrentServerService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = context.notificationBuilder(Notifications.CHANNEL_TORRENT_SERVER) {
            setSmallIcon(R.drawable.ic_chimahon)
            setContentText("Torrent server is running")
            setContentTitle("Chimahon")
            setAutoCancel(false)
            setOngoing(true)
            setUsesChronometer(true)
            addAction(R.drawable.ic_close_24dp, "Stop", exitPendingIntent)
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    Notifications.ID_TORRENT_SERVER,
                    builder.build(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(Notifications.ID_TORRENT_SERVER, builder.build())
            }
            true
        } catch (e: Exception) {
            // The dataSync quota can be exhausted before this start, especially
            // when Android restarts a sticky service after the timeout.
            Log.w(TAG, "Unable to start torrent foreground service", e)
            false
        }
    }

    companion object {
        private const val TAG = "TorrentServerService"
        const val ACTION_START = "start_torrent_server"
        const val ACTION_STOP = "stop_torrent_server"
        private val applicationContext = Injekt.get<Application>()

        fun start() {
            try {
                val intent = Intent(applicationContext, TorrentServerService::class.java).apply {
                    action = ACTION_START
                }
                applicationContext.startService(intent)
            } catch (_: Exception) {}
        }

        fun stop() {
            try {
                val intent = Intent(applicationContext, TorrentServerService::class.java).apply {
                    action = ACTION_STOP
                }
                applicationContext.startService(intent)
            } catch (_: Exception) {}
        }

        fun wait(timeout: Int = -1): Boolean {
            var count = if (timeout < 0) -20 else 0
            while (TorrentServerApi.echo() == "") {
                Thread.sleep(1000)
                count++
                if (count > timeout) return false
            }
            return true
        }
    }
}
