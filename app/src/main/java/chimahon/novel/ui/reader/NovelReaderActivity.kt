package chimahon.novel.ui.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import chimahon.novel.data.BookMetadata
import chimahon.novel.data.BookStorage
import chimahon.novel.data.NovelReaderSettings
import chimahon.ocr.OcrLanguage
import chimahon.ocr.OcrResult
import androidx.core.graphics.ColorUtils
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import tachiyomi.domain.novel.repository.NovelRepository
import java.io.File

open class NovelReaderActivity : ComponentActivity() {

    companion object {
        const val EXTRA_BOOK_DIR = "extra_book_dir"
        const val EXTRA_NOVEL_ID = "extra_novel_id"
        const val EXTRA_CHAPTER_INDEX = "extra_chapter_index"

        /**
         * Set to [ChimaReaderActivity] from AppModule so launches land in the
         * app-side subclass (which has the lookup popup).
         */
        var activityClass: Class<out ComponentActivity> = NovelReaderActivity::class.java

        fun launch(context: Context, bookDir: File, novelId: Long? = null, chapterIndex: Int? = null) {
            val intent = Intent(context, activityClass).apply {
                putExtra(EXTRA_BOOK_DIR, bookDir.absolutePath)
                if (novelId != null) putExtra(EXTRA_NOVEL_ID, novelId)
                if (chapterIndex != null) putExtra(EXTRA_CHAPTER_INDEX, chapterIndex)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }

    /** Controls whether the reader interactions (taps, selection) are enabled. */
    protected var isPopupActive by androidx.compose.runtime.mutableStateOf(false)

    protected var readerViewModel by androidx.compose.runtime.mutableStateOf<ReaderViewModel?>(null)
    protected var bookMetadata: BookMetadata? = null
    protected var showHud by androidx.compose.runtime.mutableStateOf(false)

    protected open fun handleVolumeKey(forward: Boolean): Boolean {
        val vm = readerViewModel ?: return false
        vm.bridge.paginate(forward)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (isPopupActive) return super.onKeyDown(keyCode, event)

        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN,
            android.view.KeyEvent.KEYCODE_PAGE_UP, android.view.KeyEvent.KEYCODE_PAGE_DOWN,
            android.view.KeyEvent.KEYCODE_MENU -> return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (isPopupActive) return super.onKeyUp(keyCode, event)

        val ctrlPressed = (event?.metaState?.and(android.view.KeyEvent.META_CTRL_ON) ?: 0) > 0

        when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> {
                if (handleVolumeKey(false)) return true
            }
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (handleVolumeKey(true)) return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_PAGE_UP -> {
                readerViewModel?.bridge?.paginate(false)
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN -> {
                readerViewModel?.bridge?.paginate(true)
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (ctrlPressed) {
                    readerViewModel?.onChapterEdge(NovelTurnDirection.PREV)
                } else {
                    readerViewModel?.bridge?.paginate(false)
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (ctrlPressed) {
                    readerViewModel?.onChapterEdge(NovelTurnDirection.NEXT)
                } else {
                    readerViewModel?.bridge?.paginate(true)
                }
                return true
            }
            android.view.KeyEvent.KEYCODE_N -> {
                readerViewModel?.onChapterEdge(NovelTurnDirection.NEXT)
                return true
            }
            android.view.KeyEvent.KEYCODE_P -> {
                readerViewModel?.onChapterEdge(NovelTurnDirection.PREV)
                return true
            }
            android.view.KeyEvent.KEYCODE_MENU -> {
                showHud = !showHud
                setSystemBarsVisibility(showHud)
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Override in subclass to receive text selection events from the reader. */
    protected open fun onLookupRequested(word: String, sentence: String, x: Float, y: Float, w: Float, h: Float) = Unit

    /** Override in the app host so interactive OCR follows the configured engine. */
    protected open suspend fun recognizeImage(bitmap: Bitmap, language: OcrLanguage): List<OcrResult> = emptyList()

    /** Override to open a lookup backed by an OCR image for Anki screenshot fields. */
    protected open fun onImageOcrLookupRequested(
        word: String,
        sentence: String,
        sentenceOffset: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        vertical: Boolean,
        bitmap: Bitmap,
    ) = onLookupRequested(word, sentence, x, y, w, h)

    /** Override in subclass to receive the sentence context after onLookupRequested. */
    protected open fun onSentenceReady(sentence: String) = Unit

    /** Override in subclass to dismiss the popup when background is tapped. */
    protected open fun onDismissPopupRequested() = Unit

    protected open fun onDismissPopup() {
        isPopupActive = false
    }

    @Composable
    protected open fun PopupOverlay() {}

    @Composable
    protected open fun AdditionalAppearanceSettings() {}

    /** Subclasses can override to pass a profile ID for per-profile settings. */
    protected open fun getSettingsNamespace(): String? = null

    /** Override to receive selection rects from JS for native highlight overlay. */
    protected open fun getSelectionRectsCallback(): ((String) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        super.onCreate(savedInstanceState)

        val path = intent.getStringExtra(EXTRA_BOOK_DIR)
        if (path.isNullOrEmpty()) {
            finish()
            return
        }

        val root = File(path)

        if (!root.exists() || !root.isDirectory) {
            finish()
            return
        }

        // Explicit id wins; stale ids and legacy opens (old backstacks,
        // widgets) fall back to folder lookup, then the sidecar for
        // unregistered dirs.
        val novelIdExtra = intent.getLongExtra(EXTRA_NOVEL_ID, -1L).takeIf { it >= 0L }
        val chapterIndexExtra = intent.getIntExtra(EXTRA_CHAPTER_INDEX, -1).takeIf { it >= 0 }
        val metadata = runCatching {
            runBlocking(Dispatchers.IO) {
                val repos = Injekt.get<NovelRepository>()
                val row = if (novelIdExtra != null) {
                    runCatching { repos.getNovelById(novelIdExtra) }.getOrNull()
                        ?: runCatching { repos.getNovelByLocalFolder(root.name) }.getOrNull()
                } else {
                    runCatching { repos.getNovelByLocalFolder(root.name) }.getOrNull()
                }
                row?.let { BookMetadata.fromRow(it, root.name) }
            }
        }.getOrNull() ?: BookStorage.loadMetadata(root) ?: BookMetadata(folder = root.name)
        bookMetadata = metadata
        val openNovelId = novelIdExtra
        val openChapterIndex = chapterIndexExtra

        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            Box(Modifier.fillMaxSize()) {
                ReaderScreen(
                    book = metadata,
                    novelId = openNovelId,
                    chapterIndex = openChapterIndex,
                    showHud = showHud,
                    onBack = { finish() },
                    onShowHudChanged = { visible ->
                        showHud = visible
                        setSystemBarsVisibility(visible)
                    },
                    onThemeChanged = { bgColor -> updateSystemBarsTheme(bgColor) },
                    onLookupRequested = { word, sentence, x, y, w, h -> onLookupRequested(word, sentence, x, y, w, h) },
                    onSentenceReady = { sentence -> onSentenceReady(sentence) },
                    onDismissPopupRequested = { onDismissPopupRequested() },
                    isPopupActive = isPopupActive,
                    onViewModelReady = { readerViewModel = it },
                    additionalSettings = { AdditionalAppearanceSettings() },
                    settingsNamespace = getSettingsNamespace(),
                    onSelectionRectsReceived = getSelectionRectsCallback(),
                    recognizeImage = { bitmap, language -> recognizeImage(bitmap, language) },
                    onImageOcrLookupRequested = { word, sentence, sentenceOffset, x, y, w, h, vertical, bitmap ->
                        onImageOcrLookupRequested(word, sentence, sentenceOffset, x, y, w, h, vertical, bitmap)
                    },
                )
                PopupOverlay()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setSystemBarsVisibility(showHud)
    }

    override fun onPause() {
        super.onPause()
        readerViewModel?.let { vm ->
            vm.flushReaderState()
        }
    }

    override fun onStop() {
        super.onStop()
        readerViewModel?.let { vm ->
            vm.flushReaderState()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setSystemBarsVisibility(showHud)
        }
    }

    private fun setSystemBarsVisibility(visible: Boolean) {
        val windowInsetsController = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        if (visible) {
            windowInsetsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        windowInsetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun updateSystemBarsTheme(backgroundColor: Int) {
        val windowInsetsController = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        val isLight = androidx.core.graphics.ColorUtils.calculateLuminance(backgroundColor) > 0.5
        windowInsetsController.isAppearanceLightStatusBars = isLight
        windowInsetsController.isAppearanceLightNavigationBars = isLight
    }
}
