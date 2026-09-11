package eu.kanade.tachiyomi.ui.dictionary

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.view.Display
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notificationBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.roundToInt

object ScreenLookupServiceState {
    val isRunning = MutableStateFlow(false)
    var isEntryInProgress = false
}

class ScreenLookupService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    @Volatile private var imageReader: ImageReader? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var virtualDisplaySize: CaptureSize? = null
    private var lastCaptureError: String? = null
    private var floatingButton: View? = null
    private var floatingButtonParams: WindowManager.LayoutParams? = null
    private var captureJob: Job? = null
    private var overlayController: ScreenLookupOverlayController? = null
    private val closeSystemDialogsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS == intent.action) {
                overlayController?.dismiss()
            }
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) clampFloatingButton()
        }
    }
    private val windowManager: WindowManager
        get() = getSystemService()!!

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            closeSystemDialogsReceiver,
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            ContextCompat.RECEIVER_EXPORTED,
        )
        runCatching {
            getSystemService<DisplayManager>()?.registerDisplayListener(displayListener, mainHandler)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getProjectionIntent()
                if (resultCode == 0 || resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, this.stringResource(MR.strings.screen_lookup_overlay_required), Toast.LENGTH_LONG).show()
                    stopSelf()
                    return START_NOT_STICKY
                }
                startLookupForeground()
                if (!startProjection(resultCode, resultData)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                showFloatingButton()
                ScreenLookupServiceState.isRunning.value = true
                ScreenLookupTileService.requestUpdate(this)
            }
            ACTION_CAPTURE -> captureFromButton()
            ACTION_SHOW_BUTTON -> setFloatingButtonVisible(true)
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { getSystemService<DisplayManager>()?.unregisterDisplayListener(displayListener) }
        runCatching { unregisterReceiver(closeSystemDialogsReceiver) }
        captureJob?.cancel()
        overlayController?.release()
        overlayController = null
        scope.cancel()
        removeFloatingButton()
        releaseProjection()
        ScreenLookupServiceState.isRunning.value = false
        ScreenLookupTileService.requestUpdate(this)
        super.onDestroy()
    }

    private fun startLookupForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ScreenLookupService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return notificationBuilder(Notifications.CHANNEL_COMMON) {
            setSmallIcon(R.drawable.ic_chimahon)
            setContentTitle(this@ScreenLookupService.stringResource(MR.strings.screen_lookup_notification_title))
            setContentText(this@ScreenLookupService.stringResource(MR.strings.screen_lookup_notification_text))
            setOngoing(true)
            setShowWhen(false)
            setCategory(NotificationCompat.CATEGORY_SERVICE)
            setPriority(NotificationCompat.PRIORITY_LOW)
            addAction(
                R.drawable.ic_close_24dp,
                this@ScreenLookupService.stringResource(MR.strings.action_stop),
                stopIntent,
            )
        }.build()
    }

    private fun startProjection(resultCode: Int, resultData: Intent): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, this.stringResource(MR.strings.screen_lookup_overlay_required), Toast.LENGTH_LONG).show()
            return false
        }
        if (projection != null) return true

        val manager = getSystemService<MediaProjectionManager>() ?: return false
        val nextProjection = runCatching { manager.getMediaProjection(resultCode, resultData) }
            .onFailure { logcat(LogPriority.ERROR, it) { "Failed to start media projection" } }
            .getOrNull()
            ?: return false

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                scope.launch { stopSelf() }
            }

            // onCapturedContentResize intentionally not overridden.
            // Android 16 fires this callback in a storm during VirtualDisplay
            // setup before createVirtualDisplay returns, creating multiple
            // ImageReaders whose surfaces are immediately abandoned (the
            // "BufferQueue has been abandoned" loop in the logs). PlayTranslate
            // avoids this entirely by checking dimensions fresh on every
            // captureFrame() call. We follow the same pattern.
        }
        nextProjection.registerCallback(callback, mainHandler)
        projection = nextProjection
        projectionCallback = callback
        return ensureVirtualDisplay()
    }

    private fun ensureVirtualDisplay(): Boolean {
        val mediaProjection = projection ?: return false
        val size = captureSize()
        if (virtualDisplay != null && imageReader != null) {
            if (virtualDisplaySize != size) resizeVirtualDisplay(size)
            return true
        }

        // Allocate reader before createVirtualDisplay so its surface is ready.
        // Only assign imageReader/virtualDisplay after both succeed — mirrors
        // PlayTranslate's pattern of not storing state until the VD is live.
        val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)
        virtualDisplaySize = size
        val vd = runCatching {
            mediaProjection.createVirtualDisplay(
                "ChimahonScreenLookup",
                size.width,
                size.height,
                resources.configuration.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mainHandler,
            )
        }.onFailure { e ->
            logcat(LogPriority.ERROR, e) { "Failed to create screen lookup virtual display" }
            reader.close()
        }.getOrNull() ?: return false
        imageReader = reader
        virtualDisplay = vd
        return true
    }

    private fun resizeVirtualDisplay(size: CaptureSize) {
        val display = virtualDisplay ?: return
        val oldReader = imageReader
        val newReader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)

        try {
            display.resize(size.width, size.height, resources.configuration.densityDpi)
            display.setSurface(newReader.surface)
        } catch (e: Exception) {
            newReader.close()
            logcat(LogPriority.ERROR, e) { "VirtualDisplay resize failed" }
            releaseProjection()
            return
        }

        imageReader = newReader
        virtualDisplaySize = size
        oldReader?.close()
    }

    private fun captureFromButton() {
        if (overlayController?.isShowing == true) {
            overlayController?.dismiss()
            return
        }
        if (captureJob?.isActive == true) return

        captureJob = scope.launch {
            // Hide button so it isn't burned into the screenshot.
            setFloatingButtonVisible(false)
            try {
                val bitmap = captureWithProjection()
                if (bitmap == null) {
                    toastCaptureFailed()
                    return@launch
                }
                showLookupOverlay(bitmap)
            } finally {
                setFloatingButtonVisible(true)
            }
        }
    }

    private suspend fun captureWithProjection(): Bitmap? {
        lastCaptureError = null
        if (projection == null) return null
        if (!ensureVirtualDisplay()) return null

        // Drop pre-hide frame, then wait for SurfaceFlinger to recompose
        // without the button before acquiring a fresh frame.
        runCatching { imageReader?.acquireLatestImage()?.close() }
        delay(HIDE_BUTTON_DELAY_MS)
        return withContext(Dispatchers.Default) {
            runCatching { acquireBitmap() }
                .onFailure { e ->
                    lastCaptureError = e.message
                    logcat(LogPriority.ERROR, e) { "capture failed" }
                }
                .getOrNull()
        }
    }

    private suspend fun acquireBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        repeat(2) { attempt ->
            try {
                reader.acquireLatestImage()?.use { return it.toBitmap() }
            } catch (_: IllegalStateException) {}
            if (attempt == 0) delay(48)
        }
        return null
    }

    private fun toastCaptureFailed() {
        toast(MR.strings.screen_lookup_capture_failed)
        lastCaptureError?.let { msg ->
            Toast.makeText(this, "Error: $msg", Toast.LENGTH_LONG).show()
        }
    }

    private fun showLookupOverlay(bitmap: Bitmap) {
        val controller = overlayController ?: ScreenLookupOverlayController(
            context = this,
            windowManager = windowManager,
            onDismiss = { setFloatingButtonVisible(true) },
        ).also { overlayController = it }
        controller.show(bitmap)
        bringFloatingButtonToFront()
    }

    private fun bringFloatingButtonToFront() {
        val btn = floatingButton ?: return
        val params = floatingButtonParams ?: return
        runCatching { windowManager.removeView(btn) }
        windowManager.addView(btn, params)
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.first()
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        paddedBitmap.copyPixelsFromBuffer(buffer)
        if (paddedWidth == width) return paddedBitmap

        return Bitmap.createBitmap(paddedBitmap, 0, 0, width, height).also {
            paddedBitmap.recycle()
        }
    }

    private fun showFloatingButton() {
        if (floatingButton != null || !Settings.canDrawOverlays(this)) return

        val size = buttonSizeDp().dp
        val buttonColor = buttonBackgroundColor()
        val button = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(buttonColor)
            }
            elevation = 8.dp.toFloat()
            alpha = buttonAlpha()
            contentDescription = this@ScreenLookupService.stringResource(MR.strings.screen_lookup_capture_button)
            addView(
                ImageView(this@ScreenLookupService).apply {
                    setImageResource(R.drawable.ic_chimahon)
                    imageTintList = ColorStateList.valueOf(buttonIconColor(buttonColor))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(14.dp, 14.dp, 14.dp, 14.dp)
                },
                FrameLayout.LayoutParams(size, size),
            )
        }

        val metrics = captureSize()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.width - size - 16.dp
            y = (metrics.height * 0.42f).roundToInt()
        }

        button.installDragHandler(params)
        floatingButton = button
        floatingButtonParams = params
        windowManager.addView(button, params)
    }

    private fun View.installDragHandler(params: WindowManager.LayoutParams) {
        val touchSlop = 8.dp
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).roundToInt()
                    val dy = (event.rawY - downRawY).roundToInt()
                    moved = moved || kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop
                    val bounds = captureSize()
                    params.x = (startX + dx).coerceIn(0, (bounds.width - params.width).coerceAtLeast(0))
                    params.y = (startY + dy).coerceIn(0, (bounds.height - params.height).coerceAtLeast(0))
                    runCatching { windowManager.updateViewLayout(this, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) captureFromButton()
                    true
                }
                else -> false
            }
        }
    }

    private fun setFloatingButtonVisible(visible: Boolean) {
        floatingButton?.apply {
            alpha = if (visible) buttonAlpha() else 0f
            visibility = if (visible) View.VISIBLE else View.INVISIBLE
            isEnabled = visible
        }
    }

    private fun removeFloatingButton() {
        floatingButton?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        floatingButton = null
        floatingButtonParams = null
    }

    private fun clampFloatingButton() {
        val params = floatingButtonParams ?: return
        val button = floatingButton ?: return
        val bounds = captureSize()
        params.x = params.x.coerceIn(0, (bounds.width - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (bounds.height - params.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(button, params) }
    }

    private fun releaseProjection() {
        // Order matters: release VirtualDisplay first (detaches the surface),
        // then close ImageReader (abandons the BufferQueue). Closing the reader
        // before releasing the display leaves the VirtualDisplay pointing at an
        // abandoned surface — same race that caused the Android 16 log storm.
        // Mirrors PlayTranslate's teardown() order exactly.
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        virtualDisplaySize = null
        // Close reader after display is released — BufferQueue can now be
        // abandoned cleanly with no one writing to it.
        imageReader?.close()
        imageReader = null

        val callback = projectionCallback
        val currentProjection = projection
        if (callback != null && currentProjection != null) {
            runCatching { currentProjection.unregisterCallback(callback) }
        }
        projectionCallback = null
        runCatching { projection?.stop() }
        projection = null
    }

    private fun captureSize(): CaptureSize = resolveCaptureSize()

    private fun resolveCaptureSize(): CaptureSize {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val display = getSystemService<DisplayManager>()
                    ?.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    val wm = createDisplayContext(display)
                        .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                        .getSystemService<WindowManager>()
                    val bounds = wm?.currentWindowMetrics?.bounds
                    if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                        return CaptureSize(bounds.width(), bounds.height())
                    }
                }
            } catch (_: Exception) {}
        }
        val metrics = resources.displayMetrics
        return CaptureSize(metrics.widthPixels, metrics.heightPixels)
    }

    private fun Intent.getProjectionIntent(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun toast(stringRes: dev.icerock.moko.resources.StringResource) {
        Toast.makeText(this, this.stringResource(stringRes), Toast.LENGTH_SHORT).show()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).roundToInt()

    private data class CaptureSize(
        val width: Int,
        val height: Int,
    )

    private fun buttonSizeDp(): Int {
        return try {
            Injekt.get<DictionaryPreferences>().ocrButtonSize().get()
        } catch (_: Exception) {
            BUTTON_SIZE_DP
        }
    }

    private fun buttonAlpha(): Float {
        return try {
            Injekt.get<DictionaryPreferences>().ocrButtonAlpha().get()
        } catch (_: Exception) {
            BUTTON_ALPHA
        }
    }

    private fun buttonBackgroundColor(): Int {
        val stored = try {
            Injekt.get<DictionaryPreferences>().ocrButtonColor().get()
        } catch (_: Exception) {
            0
        }
        if (stored != 0) return stored
        return ContextCompat.getColor(this, R.color.tachiyomi_primary)
    }

    private fun buttonIconColor(background: Int): Int {
        // White icon on dark backgrounds, black icon on light backgrounds.
        val luminance = 0.299 * Color.red(background) + 0.587 * Color.green(background) + 0.114 * Color.blue(background)
        return if (luminance < 128) Color.WHITE else Color.BLACK
    }

    companion object {
        private const val ACTION_START = "eu.kanade.tachiyomi.dictionary.SCREEN_LOOKUP_START"
        private const val ACTION_STOP = "eu.kanade.tachiyomi.dictionary.SCREEN_LOOKUP_STOP"
        private const val ACTION_SHOW_BUTTON = "eu.kanade.tachiyomi.dictionary.SCREEN_LOOKUP_SHOW_BUTTON"
        private const val ACTION_CAPTURE = "eu.kanade.tachiyomi.dictionary.SCREEN_LOOKUP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 320_420
        private const val BUTTON_SIZE_DP = 56
        private const val BUTTON_ALPHA = 0.92f
        private const val IMAGE_TIMEOUT_MS = 1_500L
        private const val HIDE_BUTTON_DELAY_MS = 250L

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenLookupService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenLookupService::class.java).setAction(ACTION_STOP),
            )
        }

        fun showButton(context: Context) {
            context.startService(
                Intent(context, ScreenLookupService::class.java).setAction(ACTION_SHOW_BUTTON),
            )
        }

        fun capture(context: Context) {
            context.startService(
                Intent(context, ScreenLookupService::class.java).setAction(ACTION_CAPTURE),
            )
        }
    }
}
