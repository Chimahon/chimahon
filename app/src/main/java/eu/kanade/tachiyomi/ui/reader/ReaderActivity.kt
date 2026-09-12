package eu.kanade.tachiyomi.ui.reader

import android.Manifest
import android.annotation.SuppressLint
import android.app.assist.AssistContent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.LAYER_TYPE_HARDWARE
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import chimahon.MediaInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.getSystemService
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.transition.doOnEnd
import androidx.core.view.isVisible
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.transition.platform.MaterialContainerTransform
import com.hippo.unifile.UniFile
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.reader.ChapterListDialog
import eu.kanade.presentation.reader.DisplayRefreshHost
import eu.kanade.presentation.reader.OrientationSelectDialog
import eu.kanade.presentation.reader.ReaderContentOverlay
import eu.kanade.presentation.reader.ReaderPageActionsDialog
import eu.kanade.presentation.reader.ReaderPageIndicator
import eu.kanade.presentation.reader.ReadingModeSelectDialog
import eu.kanade.presentation.reader.appbars.NavBarType
import eu.kanade.presentation.reader.appbars.ReaderAppBars
import eu.kanade.presentation.reader.settings.ReaderSettingsDialog
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.coil.TachiyomiImageDecoder
import eu.kanade.tachiyomi.data.connections.discord.DiscordRPCService
import eu.kanade.tachiyomi.data.connections.discord.ReaderData
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.databinding.ReaderActivityBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPopupWebViewWarmup
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.cropAroundAnchor
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.AddToLibraryFirst
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Error
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel.SetAsCoverResult.Success
import eu.kanade.tachiyomi.ui.reader.loader.HttpPageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import chimahon.ocr.CropPresets
import chimahon.ocr.OcrBitmapDecoder
import chimahon.util.ImageEncoder
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.core.common.util.lang.withUIContext
import logcat.logcat
import logcat.LogPriority
import eu.kanade.presentation.reader.stats.MangaStatsSheet
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOcrSource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.OcrLookupPopup
import eu.kanade.tachiyomi.ui.reader.viewer.copyOcrPopupFullText
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerConfig
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerPageHolder
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.VerticalPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonPageHolder
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.isNightMode
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.setComposeContent
import exh.source.isEhBasedSource
import exh.util.defaultReaderType
import exh.util.mangaType
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.UrlUtils
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.seconds
import androidx.compose.ui.graphics.Color as ComposeColor

class ReaderActivity : BaseActivity() {

    companion object {
        private val ocrProgressHudTopPadding = 124.dp

        fun newIntent(context: Context, mangaId: Long?, chapterId: Long?/* SY --> */, page: Int? = null/* SY <-- */): Intent {
            return Intent(context, ReaderActivity::class.java).apply {
                putExtra("manga", mangaId)
                putExtra("chapter", chapterId)
                // SY -->
                putExtra("page", page)
                // SY <--
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    private val readerPreferences = Injekt.get<ReaderPreferences>()
    private val preferences = Injekt.get<BasePreferences>()

    // KMK -->
    val themeCoverBased = Injekt.get<UiPreferences>().themeCoverBased().get()
    // KMK <--

    // AM (CONNECTIONS) -->
    private val connectionsPreferences: ConnectionsPreferences = Injekt.get()
    // <-- AM (CONNECTIONS)

    lateinit var binding: ReaderActivityBinding

    val viewModel by viewModels<ReaderViewModel>()
    private var assistUrl: String? = null

    // SY -->
    private val sourceManager = Injekt.get<SourceManager>()
    // SY <--

    /**
     * Configuration at reader level, like background color or forced orientation.
     */
    private var config: ReaderConfig? = null

    private var menuToggleToast: Toast? = null
    private var readingModeToast: Toast? = null
    private val displayRefreshHost = DisplayRefreshHost()

    private val windowInsetsController by lazy { WindowInsetsControllerCompat(window, window.decorView) }

    private var loadingIndicator: ReaderProgressIndicator? = null

    private var ocrPopupState by mutableStateOf<OcrPopupState?>(null)
    private var ocrPopupVisible by mutableStateOf(false)
    private var ocrSelectionPanelState by mutableStateOf<OcrSelectionPanelState?>(null)

    private val twoFingerTapSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var twoFingerTapTracking = false
    private var twoFingerTapStartTime = 0L
    private var twoFingerTapStartX = 0f
    private var twoFingerTapStartY = 0f
    private var twoFingerTapStartSpan = 0f

    private var pendingNoteId by mutableStateOf<Long?>(null)
    private var pendingGlossaryIndex by mutableStateOf<Int?>(null)

    private val cropImageLauncher = registerForActivityResult(
        com.canhub.cropper.CropImageContract(),
    ) { result ->
        val noteId = pendingNoteId
        val glossaryIndex = pendingGlossaryIndex
        pendingNoteId = null
        pendingGlossaryIndex = null

        if (result.isSuccessful) {
            val uri = result.uriContent
            val bytes = uri?.let { uri ->
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
            if (bytes != null) {
                lifecycleScope.launchIO {
                    updateAnkiCardWithScreenshot(noteId, bytes, glossaryIndex)
                }
            } else {
                toast(MR.strings.decode_image_error)
            }
        } else {
            val error = result.error
            if (error != null) {
                logcat(LogPriority.ERROR, error) { "Image crop failed" }
            } else {
                logcat(LogPriority.DEBUG) { "Image crop cancelled" }
            }
        }
    }

    data class OcrPopupState(
        val lookupString: String,
        val fullText: String,
        val charOffset: Int,
        val webView: android.webkit.WebView,
        val repository: chimahon.DictionaryRepository,
        val anchorX: Float,
        val anchorY: Float,
        val anchorWidth: Float = 0f,
        val anchorHeight: Float = 0f,
        val isVertical: Boolean,
        val activeProfile: chimahon.anki.AnkiProfile,
        val mediaInfo: chimahon.MediaInfo? = null,
        val sourcePage: ReaderPage? = null,
        val deferredLookup: kotlinx.coroutines.Deferred<chimahon.DictionaryRepository.LookupResult2>? = null,
    )

    private data class OcrSelectionPanelState(
        val text: String,
        val anchorX: Float,
        val anchorY: Float,
        val anchorWidth: Float,
        val anchorHeight: Float,
    )

    var isScrollingThroughPages = false
        private set

    /**
     * Called when the activity is created. Initializes the presenter and configuration.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        registerSecureActivity(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.shared_axis_x_push_enter,
                R.anim.shared_axis_x_push_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_push_enter, R.anim.shared_axis_x_push_exit)
        }

        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        super.onCreate(savedInstanceState)

        val shouldWarmOcrResources = viewModel.isOcrEnabled()
        if (shouldWarmOcrResources) {
            val prefs = Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>()

            lifecycleScope.launchIO {
                val manga = viewModel.manga
                // Use manga.source (the raw Long ID) so local/stub sources don't return null.
                // getSource() casts to HttpSource? and would give 0 for non-HTTP sources.
                val sourceId = manga?.source ?: 0L
                val sourceLang = if (sourceId != 0L) {
                    sourceManager.getOrStub(sourceId).lang
                } else {
                    ""
                }
                val profile = prefs.profileResolver.resolve(
                    mangaId = manga?.id ?: 0L,
                    sourceId = sourceId,
                    sourceLang = sourceLang,
                )
                val dictPaths = eu.kanade.tachiyomi.ui.dictionary.getDictionaryPaths(this@ReaderActivity, profile)
                cachedActiveProfile = profile
                cachedTermPaths = dictPaths
                dictionaryRepository.warmUp(dictPaths, profile.id)
                DictionaryPopupWebViewWarmup.warm(this@ReaderActivity, profile.languageCode)
            }

            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    prefs.rawActiveProfileId().changes().collect {
                        // Global profile changed — invalidate cache so next access re-resolves
                        cachedActiveProfile = null
                        cachedTermPaths = null
                    }
                }
            }

            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    prefs.rawProfiles().changes().collect {
                        // Profile content changed — invalidate cache so next access re-resolves
                        cachedActiveProfile = null
                        cachedTermPaths = null
                    }
                }
            }
        }

        binding = ReaderActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.setComposeOverlay()
        if (shouldWarmOcrResources) {
            DictionaryPopupWebViewWarmup.warm(this)
        }

        if (viewModel.needsInit()) {
            val manga = intent.extras?.getLong("manga", -1) ?: -1L
            val chapter = intent.extras?.getLong("chapter", -1) ?: -1L
            // SY -->
            val page = intent.extras?.getInt("page", -1).takeUnless { it == -1 }
            // SY <--
            if (manga == -1L || chapter == -1L) {
                finish()
                return
            }
            NotificationReceiver.dismissNotification(this, manga.hashCode(), Notifications.ID_NEW_CHAPTERS)

            lifecycleScope.launchNonCancellable {
                val initResult = viewModel.init(manga, chapter/* SY --> */, page/* SY <-- */)
                if (!initResult.getOrDefault(false)) {
                    val exception = initResult.exceptionOrNull() ?: IllegalStateException("Unknown err")
                    withUIContext {
                        setInitialChapterError(exception)
                    }
                }
            }
        }

        config = ReaderConfig()
        setMenuVisibility(viewModel.state.value.menuVisible)

        // EXH -->
        enableExhAutoScroll()
        // EXH <--

        // Finish when incognito mode is disabled
        preferences.incognitoMode().changes()
            .drop(1)
            .onEach { if (!it) finish() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.isLoadingAdjacentChapter }
            .distinctUntilChanged()
            .onEach(::setProgressDialog)
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.manga }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { updateViewer() }
            .launchIn(lifecycleScope)

        viewModel.state
            .map { it.viewerChapters }
            .distinctUntilChanged()
            .filterNotNull()
            .onEach { chapters ->
                if (readerPreferences.readerStartupDelay().get()) delay(200)
                setChapters(chapters)
            }
            .launchIn(lifecycleScope)

        viewModel.eventFlow
            .onEach { event ->
                when (event) {
                    ReaderViewModel.Event.ReloadViewerChapters -> {
                        viewModel.state.value.viewerChapters?.let(::setChapters)
                    }
                    ReaderViewModel.Event.PageChanged -> {
                        displayRefreshHost.flash()
                    }
                    ReaderViewModel.Event.InitializeOcrResources -> {
                        DictionaryPopupWebViewWarmup.warm(this, cachedActiveProfile?.languageCode.orEmpty())
                    }
                    is ReaderViewModel.Event.SetOrientation -> {
                        setOrientation(event.orientation)
                    }
                    is ReaderViewModel.Event.SavedImage -> {
                        onSaveImageResult(event.result)
                    }
                    is ReaderViewModel.Event.ShareImage -> {
                        onShareImageResult(event.uri, event.page /* SY --> */, event.secondPage /* SY <-- */)
                    }
                    is ReaderViewModel.Event.CopyImage -> {
                        onCopyImageResult(event.uri)
                    }
                    is ReaderViewModel.Event.SetCoverResult -> {
                        onSetAsCoverResult(event.result)
                    }
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun ReaderActivityBinding.setComposeOverlay(): Unit = composeOverlay.setComposeContent {
        // KMK -->
        TachiyomiTheme(
            seedColor = seedColorState().takeIf { themeCoverBased },
            typography = MaterialTheme.typography.copy(
                bodyLarge = MaterialTheme.typography.bodySmall,
            ),
        ) {
            val context = LocalContext.current
            // KMK <--
            val state by viewModel.state.collectAsState()
            val showPageNumber by readerPreferences.showPageNumber().collectAsState()
            val settingsScreenModel = remember {
                ReaderSettingsScreenModel(
                    readerState = viewModel.state,
                    onChangeReadingMode = viewModel::setMangaReadingMode,
                    onChangeOrientation = viewModel::setMangaOrientationType,
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (!state.menuVisible && showPageNumber) {
                    ReaderPageIndicator(
                        // SY -->
                        currentPage = state.currentPageText,
                        // SY <--
                        totalPages = state.totalPages,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding(),
                    )
                }

                ContentOverlay(state = state)

                AppBars(state = state)

                OcrProgressHud(
                    visible = state.menuVisible,
                    progress = state.ocrScanProgress,
                )

                ocrSelectionPanelState?.let { selectionState ->
                    OcrSelectionPanel(
                        state = selectionState,
                        onDismiss = ::dismissOcrSelectionPanel,
                    )
                }
            }

            if (viewModel.showMangaStats) {
                MangaStatsSheet(
                    context = context,
                    mangaId = viewModel.manga!!.id,
                    sessionCharacters = viewModel.mangaStatsSessionCharacters,
                    sessionTimeMs = viewModel.mangaStatsSessionTimeMs,
                    estimate = viewModel.mangaStatsEstimate,
                    isTracking = viewModel.mangaStatsTracking,
                    onToggleTracking = viewModel::toggleMangaStatsTracking,
                    onDismiss = viewModel::closeMangaStatsSheet,
                )
            }

            // KMK -->
            val externalStoragePermissionNotGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_DENIED
            val permissionRequester = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = {
                    toast(KMR.strings.permission_writing_external_storage_succeed)
                },
            )
            // KMK <--

            val onDismissRequest = viewModel::closeDialog
            when (state.dialog) {
                is ReaderViewModel.Dialog.Loading -> {
                    AlertDialog(
                        onDismissRequest = {},
                        confirmButton = {},
                        text = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    // KMK -->
                                    color = MaterialTheme.colorScheme.primary,
                                    // KMK <--
                                )
                                Text(stringResource(MR.strings.loading))
                            }
                        },
                    )
                }

                is ReaderViewModel.Dialog.Settings -> {
                    ReaderSettingsDialog(
                        onDismissRequest = onDismissRequest,
                        onShowMenus = { setMenuVisibility(true) },
                        onHideMenus = { setMenuVisibility(false) },
                        screenModel = settingsScreenModel,
                    )
                }

                is ReaderViewModel.Dialog.ReadingModeSelect -> {
                    ReadingModeSelectDialog(
                        onDismissRequest = onDismissRequest,
                        screenModel = settingsScreenModel,
                        onChange = { stringRes ->
                            menuToggleToast?.cancel()
                            if (!readerPreferences.showReadingMode().get()) {
                                menuToggleToast = toast(stringRes)
                            }
                        },
                    )
                }

                is ReaderViewModel.Dialog.OrientationModeSelect -> {
                    OrientationSelectDialog(
                        onDismissRequest = onDismissRequest,
                        screenModel = settingsScreenModel,
                        onChange = { stringRes ->
                            menuToggleToast?.cancel()
                            menuToggleToast = toast(stringRes)
                        },
                    )
                }

                is ReaderViewModel.Dialog.PageActions -> {
                    ReaderPageActionsDialog(
                        onDismissRequest = onDismissRequest,
                        onSetAsCover = viewModel::setAsCover,
                        onShare = viewModel::shareImage,
                        // SY -->
                        onSave = { extra ->
                            // KMK -->
                            if (externalStoragePermissionNotGranted) {
                                permissionRequester.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else {
                                // KMK <--
                                viewModel.saveImage(extra)
                            }
                        },
                        onShareCombined = viewModel::shareImages,
                        onSaveCombined = {
                            // KMK -->
                            if (externalStoragePermissionNotGranted) {
                                permissionRequester.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else {
                                // KMK <--
                                viewModel.saveImages()
                            }
                        },
                        hasExtraPage = (state.dialog as? ReaderViewModel.Dialog.PageActions)?.extraPage != null,
                    )
                }

                is ReaderViewModel.Dialog.ChapterList -> {
                    var chapters by remember {
                        mutableStateOf(viewModel.getChapters().toImmutableList())
                    }
                    ChapterListDialog(
                        onDismissRequest = onDismissRequest,
                        screenModel = settingsScreenModel,
                        chapters = chapters,
                        onClickChapter = {
                            viewModel.loadNewChapterFromDialog(it)
                            onDismissRequest()
                        },
                        onBookmark = { chapter ->
                            viewModel.toggleBookmark(chapter.id, !chapter.bookmark)
                            chapters = chapters.map {
                                if (it.chapter.id == chapter.id) {
                                    it.copy(chapter = chapter.copy(bookmark = !chapter.bookmark))
                                } else {
                                    it
                                }
                            }.toImmutableList()
                        },
                        state.dateRelativeTime,
                        // KMK -->
                        onDownloadAction = { chapter, action ->
                            viewModel.handleDownloadAction(chapter, action)
                        },
                        // KMK <--
                    )
                }

                ReaderViewModel.Dialog.AutoScrollHelp -> AlertDialog(
                    onDismissRequest = onDismissRequest,
                    confirmButton = {
                        TextButton(onClick = onDismissRequest) {
                            Text(text = stringResource(MR.strings.action_ok))
                        }
                    },
                    title = { Text(text = stringResource(SYMR.strings.eh_autoscroll_help)) },
                    text = { Text(text = stringResource(SYMR.strings.eh_autoscroll_help_message)) },
                )

                ReaderViewModel.Dialog.BoostPageHelp -> AlertDialog(
                    onDismissRequest = onDismissRequest,
                    confirmButton = {
                        TextButton(onClick = onDismissRequest) {
                            Text(text = stringResource(MR.strings.action_ok))
                        }
                    },
                    title = { Text(text = stringResource(SYMR.strings.eh_boost_page_help)) },
                    text = { Text(text = stringResource(SYMR.strings.eh_boost_page_help_message)) },
                )

                ReaderViewModel.Dialog.RetryAllHelp -> AlertDialog(
                    onDismissRequest = onDismissRequest,
                    confirmButton = {
                        TextButton(onClick = onDismissRequest) {
                            Text(text = stringResource(MR.strings.action_ok))
                        }
                    },
                    title = { Text(text = stringResource(SYMR.strings.eh_retry_all_help)) },
                    text = { Text(text = stringResource(SYMR.strings.eh_retry_all_help_message)) },
                )
                // SY <--
                null -> {}
            }
        }

        BackHandler(enabled = ocrPopupVisible) {
            ocrPopupVisible = false
            // Also clear the visual highlight on whichever webtoon page has an active block.
            val viewer = viewModel.state.value.viewer
            if (viewer is WebtoonViewer) {
                for (i in 0 until viewer.recycler.childCount) {
                    val h = viewer.recycler.getChildViewHolder(
                        viewer.recycler.getChildAt(i),
                    ) as? WebtoonPageHolder
                    if (h != null && h.hasActiveOcrBlock) {
                        h.dismissActiveOcrBlock()
                        break
                    }
                }
            }
        }

        BackHandler(enabled = ocrSelectionPanelState != null) {
            dismissOcrSelectionPanel()
        }

        // The popup WebView is warmed by DictionaryPopupWebViewWarmup without being
        // attached to the reader root during startup.
        val popupState = ocrPopupState
        if (popupState != null || ocrPopupVisible) {
            val defaultProfile = chimahon.anki.AnkiProfile.EMPTY
            val defaultRepo = remember { dictionaryRepository }
            val defaultWebView = remember { ocrWebView ?: createOcrWebView(this@ReaderActivity).also { ocrWebView = it } }

            OcrLookupPopup(
                visible = ocrPopupVisible,
                lookupString = if (ocrPopupVisible && popupState != null) popupState.lookupString else "",
                fullText = popupState?.fullText ?: "",
                charOffset = popupState?.charOffset ?: 0,
                onDismiss = {
                    ocrPopupVisible = false
                },
                webView = popupState?.webView ?: defaultWebView,
                repository = popupState?.repository ?: defaultRepo,
                anchorX = popupState?.anchorX ?: 0f,
                anchorY = popupState?.anchorY ?: 0f,
                anchorWidth = popupState?.anchorWidth ?: 0f,
                anchorHeight = popupState?.anchorHeight ?: 0f,
                isVertical = popupState?.isVertical ?: false,
                mediaInfo = popupState?.mediaInfo,
                onRequestScreenshot = {
                    val bitmap = captureCurrentVisibleBitmap()
                    val profile = popupState?.activeProfile ?: defaultProfile
                    if (bitmap != null && profile.ankiCropMode == "full") {
                        val preset = CropPresets.aspectByKey(profile.ankiCropPreset)
                        if (preset != null) {
                            cropAroundAnchor(
                                bitmap = bitmap,
                                anchorX = popupState?.anchorX ?: 0f,
                                anchorY = popupState?.anchorY ?: 0f,
                                anchorWidth = popupState?.anchorWidth ?: 0f,
                                anchorHeight = popupState?.anchorHeight ?: 0f,
                                aspectX = preset.x,
                                aspectY = preset.y,
                            )
                        } else {
                            bitmap
                        }
                    } else {
                        bitmap
                    }
                },
                onCropTriggered = { noteId, glossaryIndex ->
                    pendingNoteId = noteId
                    pendingGlossaryIndex = glossaryIndex
                    launchImageCropper()
                },
                initialLookupDeferred = if (ocrPopupVisible) popupState?.deferredLookup else null,
                usePopup = false,
                activeProfile = popupState?.activeProfile ?: defaultProfile,
                onTermMatched = { charCount, _ ->
                    val viewer = viewModel.state.value.viewer
                    val anchorRect: android.graphics.RectF? = if (viewer is WebtoonViewer) {
                        var rect: android.graphics.RectF? = null
                        for (i in 0 until viewer.recycler.childCount) {
                            val h = viewer.recycler.getChildViewHolder(viewer.recycler.getChildAt(i)) as? WebtoonPageHolder
                            if (h?.hasActiveOcrBlock == true) {
                                rect = h.refineActiveOcrBlock(charCount)
                                break
                            }
                        }
                        rect
                    } else if (viewer is PagerViewer) {
                        var rect: android.graphics.RectF? = null
                        for (i in 0 until viewer.pager.childCount) {
                            val h = viewer.pager.getChildAt(i) as? PagerPageHolder
                            if (h?.hasActiveOcrBlock == true) {
                                rect = h.refineActiveOcrBlock(charCount)
                                break
                            }
                        }
                        rect
                    } else {
                        null
                    }

                    if (anchorRect != null) {
                        ocrPopupState = ocrPopupState?.copy(
                            anchorX = anchorRect.left,
                            anchorY = anchorRect.top,
                            anchorWidth = anchorRect.width(),
                            anchorHeight = anchorRect.height(),
                        )
                    }
                },
                titleId = viewModel.state.value.manga?.id?.toString(),
            )
        }

        // Set up OCR popup callback on the active reader viewer.
        when (val viewer = viewModel.state.value.viewer) {
            is PagerViewer -> {
                if (viewer.onShowOcrPopup == null) {
                    viewer.onShowOcrPopup = { lookupString, fullText, charOffset, anchorX, anchorY, anchorWidth, anchorHeight, isVertical, _, sourcePage ->
                        val (activeProfile, deferredLookup) = preDeferLookup(lookupString)

                        lifecycleScope.launch(Dispatchers.Default) {
                            val result = try { deferredLookup.await() } catch (_: Exception) { null }
                            val firstMatched = result?.results?.firstOrNull()?.matched
                            val charCount = firstMatched?.codePointCount(0, firstMatched.length)

                            val rect = withContext(Dispatchers.Main) {
                                if (charCount != null) {
                                    val pager = viewer.pager
                                    for (i in 0 until pager.childCount) {
                                        val h = pager.getChildAt(i) as? PagerPageHolder
                                        if (h?.hasActiveOcrBlock == true) return@withContext h.refineActiveOcrBlock(charCount)
                                    }
                                }
                                null as android.graphics.RectF?
                            }

                                withContext(Dispatchers.Main) {
                                val state = viewModel.state.value
                                val mediaInfo = if (state.manga != null && state.currentChapter != null) {
                                    chimahon.MediaInfo(mangaTitle = state.manga!!.title, chapterName = state.currentChapter!!.chapter.name)
                                } else null
                                ensureOcrResources()
                                ocrPopupState = OcrPopupState(
                                    lookupString, fullText, charOffset, ocrWebView!!, dictionaryRepository,
                                    rect?.left ?: anchorX, rect?.top ?: anchorY,
                                    rect?.width() ?: anchorWidth, rect?.height() ?: anchorHeight,
                                    isVertical, getOrRefreshLookupPaths().first, mediaInfo, sourcePage, null
                                )
                                ocrSelectionPanelState = null
                                ocrPopupVisible = true
                            }
                        }
                    }
                }
                if (viewer.onShowOcrSelectionPanel == null) {
                    viewer.onShowOcrSelectionPanel = { text, anchorX, anchorY, anchorWidth, anchorHeight ->
                        runOnUiThread {
                            ocrPopupVisible = false
                            ocrSelectionPanelState = OcrSelectionPanelState(
                                text = text,
                                anchorX = anchorX,
                                anchorY = anchorY,
                                anchorWidth = anchorWidth,
                                anchorHeight = anchorHeight,
                            )
                        }
                    }
                }
                if (viewer.onDismissOcrPopup == null) {
                    viewer.onDismissOcrPopup = {
                        runOnUiThread { ocrPopupVisible = false }
                    }
                }
            }
            is WebtoonViewer -> {
                if (viewer.onShowOcrPopup == null) {
                    viewer.onShowOcrPopup = { lookupString, fullText, charOffset, anchorX, anchorY, anchorWidth, anchorHeight, isVertical, _, sourcePage ->
                        val (activeProfile, deferredLookup) = preDeferLookup(lookupString)

                        lifecycleScope.launch(Dispatchers.Default) {
                            val result = try { deferredLookup.await() } catch (_: Exception) { null }
                            val firstMatched = result?.results?.firstOrNull()?.matched
                            val charCount = firstMatched?.codePointCount(0, firstMatched.length)

                            val rect = withContext(Dispatchers.Main) {
                                if (charCount != null) {
                                    val recycler = viewer.recycler
                                    for (i in 0 until recycler.childCount) {
                                        val h = recycler.getChildViewHolder(recycler.getChildAt(i)) as? WebtoonPageHolder
                                        if (h?.hasActiveOcrBlock == true) return@withContext h.refineActiveOcrBlock(charCount)
                                    }
                                }
                                null as android.graphics.RectF?
                            }

                                withContext(Dispatchers.Main) {
                                val state = viewModel.state.value
                                val mediaInfo = if (state.manga != null && state.currentChapter != null) {
                                    chimahon.MediaInfo(mangaTitle = state.manga!!.title, chapterName = state.currentChapter!!.chapter.name)
                                } else null
                                ensureOcrResources()
                                ocrPopupState = OcrPopupState(
                                    lookupString, fullText, charOffset, ocrWebView!!, dictionaryRepository,
                                    rect?.left ?: anchorX, rect?.top ?: anchorY,
                                    rect?.width() ?: anchorWidth, rect?.height() ?: anchorHeight,
                                    isVertical, getOrRefreshLookupPaths().first, mediaInfo, sourcePage, null
                                )
                                ocrSelectionPanelState = null
                                ocrPopupVisible = true
                            }
                        }
                    }
                }
                if (viewer.onShowOcrSelectionPanel == null) {
                    viewer.onShowOcrSelectionPanel = { text, anchorX, anchorY, anchorWidth, anchorHeight ->
                        runOnUiThread {
                            ocrPopupVisible = false
                            ocrSelectionPanelState = OcrSelectionPanelState(
                                text = text,
                                anchorX = anchorX,
                                anchorY = anchorY,
                                anchorWidth = anchorWidth,
                                anchorHeight = anchorHeight,
                            )
                        }
                    }
                }
                if (viewer.onDismissOcrPopup == null) {
                    viewer.onDismissOcrPopup = {
                        runOnUiThread { ocrPopupVisible = false }
                    }
                }
            }
        }
    }

    @Composable
    private fun BoxScope.OcrSelectionPanel(
        state: OcrSelectionPanelState,
        onDismiss: () -> Unit,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f),
        ) {
            val density = LocalDensity.current
            val rootView = LocalView.current
            val rootLocation = remember { IntArray(2) }
            rootView.getLocationOnScreen(rootLocation)

            val margin = 16.dp
            val availableWidth = (maxWidth - 32.dp).coerceAtLeast(1.dp)
            val panelWidth = minOf(availableWidth, 360.dp)
            val availableHeight = (maxHeight - 32.dp).coerceAtLeast(1.dp)
            val panelMaxHeight = minOf(availableHeight, 280.dp)

            val anchorCenterX = with(density) {
                (state.anchorX + state.anchorWidth / 2f - rootLocation[0]).toDp()
            }
            val anchorTop = with(density) {
                (state.anchorY - rootLocation[1]).toDp()
            }
            val anchorHeight = with(density) {
                state.anchorHeight.toDp()
            }

            val xUpperBound = (maxWidth - panelWidth - margin).let {
                if (it > margin) it else margin
            }
            val requestedX = anchorCenterX - panelWidth / 2
            val panelX = requestedX.coerceIn(margin, xUpperBound)

            val belowY = anchorTop + anchorHeight + 8.dp
            val aboveY = anchorTop - panelMaxHeight - 8.dp
            val fitsBelow = belowY + panelMaxHeight + margin <= maxHeight
            val requestedY = if (fitsBelow) belowY else aboveY
            val yUpperBound = (maxHeight - panelMaxHeight - margin).let {
                if (it > margin) it else margin
            }
            val panelY = requestedY.coerceIn(margin, yUpperBound)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )

            val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
            val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f).toArgb()
            val textPaddingPx = with(density) { 14.dp.toPx().toInt() }
            var copied by remember(state.text) { mutableStateOf(false) }
            LaunchedEffect(copied) {
                if (copied) {
                    delay(1_000)
                    copied = false
                }
            }

            Surface(
                modifier = Modifier
                    .offset(x = panelX, y = panelY)
                    .width(panelWidth)
                    .heightIn(max = panelMaxHeight),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 8.dp,
            ) {
                Column {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = panelMaxHeight - 48.dp),
                        factory = { context ->
                            TextView(context).apply {
                                setTextIsSelectable(true)
                                textSize = 18f
                                setLineSpacing(0f, 1.12f)
                                setPadding(textPaddingPx, textPaddingPx, textPaddingPx, textPaddingPx)
                                setBackgroundColor(Color.TRANSPARENT)
                                isFocusable = true
                                isFocusableInTouchMode = true
                            }
                        },
                        update = { textView ->
                            if (textView.text.toString() != state.text) {
                                textView.text = state.text
                            }
                            textView.setTextColor(textColor)
                            textView.highlightColor = highlightColor
                            textView.post {
                                if (!textView.hasFocus()) {
                                    textView.requestFocus()
                                }
                            }
                        },
                    )
                    TextButton(
                        modifier = Modifier.align(Alignment.End),
                        onClick = {
                            copyOcrPopupFullText(state.text) { label, content ->
                                this@ReaderActivity.copyToClipboard(label, content)
                            }
                            copied = true
                        },
                    ) {
                        AnimatedContent(
                            targetState = copied,
                            label = "OCR text copy feedback",
                        ) { wasCopied ->
                            Icon(
                                imageVector = if (wasCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                contentDescription = null,
                            )
                        }
                        Text(
                            text = stringResource(
                                if (copied) MR.strings.copied_to_clipboard_plain else MR.strings.action_copy_to_clipboard,
                            ),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }

    private fun dismissOcrSelectionPanel() {
        ocrSelectionPanelState = null
        clearActiveOcrBlock()
    }

    private fun clearActiveOcrBlock() {
        when (val viewer = viewModel.state.value.viewer) {
            is PagerViewer -> {
                for (i in 0 until viewer.pager.childCount) {
                    val holder = viewer.pager.getChildAt(i) as? PagerPageHolder
                    if (holder?.hasActiveOcrBlock == true) {
                        holder.dismissActiveOcrBlock()
                        break
                    }
                }
            }
            is WebtoonViewer -> {
                for (i in 0 until viewer.recycler.childCount) {
                    val holder = viewer.recycler.getChildViewHolder(
                        viewer.recycler.getChildAt(i),
                    ) as? WebtoonPageHolder
                    if (holder?.hasActiveOcrBlock == true) {
                        holder.dismissActiveOcrBlock()
                        break
                    }
                }
            }
        }
    }

    /**
     * Called when the activity is destroyed. Cleans up the viewer, configuration and any view.
     */
    override fun onDestroy() {
        super.onDestroy()
        viewModel.state.value.viewer?.destroy()
        releaseOcrResources()
        config = null
        menuToggleToast?.cancel()
        readingModeToast?.cancel()
    }

    override fun onPause() {
        lifecycleScope.launchNonCancellable {
            viewModel.updateHistory()
        }

        // AM (DISCORD) -->
        updateDiscordRPC(exitingReader = true)
        // <-- AM (DISCORD)

        super.onPause()
    }

    /**
     * Set menu visibility again on activity resume to apply immersive mode again if needed.
     * Helps with rotations.
     */
    override fun onResume() {
        super.onResume()
        viewModel.restartReadTimer()

        // AM (DISCORD) -->
        updateDiscordRPC(exitingReader = false)
        // <-- AM (DISCORD)

        setMenuVisibility(viewModel.state.value.menuVisible)
    }

    /**
     * Called when the window focus changes. It sets the menu visibility to the last known state
     * to apply immersive mode again if needed.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setMenuVisibility(viewModel.state.value.menuVisible)
        }
    }

    override fun onProvideAssistContent(outContent: AssistContent) {
        super.onProvideAssistContent(outContent)
        assistUrl?.let { outContent.webUri = it.toUri() }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (readerPreferences.ocrTwoFingerGestureEnabled().get()) {
            observeTwoFingerOcrTap(ev)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun observeTwoFingerOcrTap(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                twoFingerTapTracking = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    twoFingerTapTracking = true
                    twoFingerTapStartTime = event.eventTime
                    twoFingerTapStartX = event.twoFingerFocusX()
                    twoFingerTapStartY = event.twoFingerFocusY()
                    twoFingerTapStartSpan = event.twoFingerSpan()
                } else {
                    twoFingerTapTracking = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFingerTapTracking && !event.isTwoFingerTapCandidate()) {
                    twoFingerTapTracking = false
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (
                    twoFingerTapTracking &&
                    event.pointerCount == 2 &&
                    event.eventTime - twoFingerTapStartTime <= ViewConfiguration.getDoubleTapTimeout().toLong() &&
                    event.isTwoFingerTapCandidate()
                ) {
                    toggleOcrFromReader()
                }
                twoFingerTapTracking = false
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                twoFingerTapTracking = false
            }
        }
    }

    private fun MotionEvent.isTwoFingerTapCandidate(): Boolean {
        if (pointerCount != 2) return false
        return kotlin.math.abs(twoFingerFocusX() - twoFingerTapStartX) <= twoFingerTapSlop &&
            kotlin.math.abs(twoFingerFocusY() - twoFingerTapStartY) <= twoFingerTapSlop &&
            kotlin.math.abs(twoFingerSpan() - twoFingerTapStartSpan) <= twoFingerTapSlop
    }

    private fun MotionEvent.twoFingerFocusX(): Float {
        return (getX(0) + getX(1)) / 2f
    }

    private fun MotionEvent.twoFingerFocusY(): Float {
        return (getY(0) + getY(1)) / 2f
    }

    private fun MotionEvent.twoFingerSpan(): Float {
        return kotlin.math.hypot(getX(0) - getX(1), getY(0) - getY(1))
    }

    private fun toggleOcrFromReader() {
        val enabled = viewModel.toggleOcrEnabled()
        if (enabled) {
            DictionaryPopupWebViewWarmup.warm(this, cachedActiveProfile?.languageCode.orEmpty())
            lifecycleScope.launchIO {
                val prefs = Injekt.get<DictionaryPreferences>()
                val manga = viewModel.manga
                val sourceId = manga?.source ?: 0L
                val sourceLang = if (sourceId != 0L) sourceManager.getOrStub(sourceId).lang else ""
                val profile = prefs.profileResolver.resolve(
                    mangaId = manga?.id ?: 0L,
                    sourceId = sourceId,
                    sourceLang = sourceLang,
                )
                val dictPaths = eu.kanade.tachiyomi.ui.dictionary.getDictionaryPaths(this@ReaderActivity, profile)
                cachedActiveProfile = profile
                cachedTermPaths = dictPaths
                dictionaryRepository.warmUp(dictPaths, profile.id)
                DictionaryPopupWebViewWarmup.warm(this@ReaderActivity, profile.languageCode)
            }
        }
        when (val viewer = viewModel.state.value.viewer) {
            is PagerViewer -> viewer.setOcrEnabled(enabled)
            is WebtoonViewer -> viewer.setOcrEnabled(enabled)
        }
        menuToggleToast?.cancel()
        menuToggleToast = toast(
            if (enabled) MR.strings.action_enable_ocr else MR.strings.action_disable_ocr,
        )
    }

    private fun selectOcrSourceFromReader(source: ReaderOcrSource) {
        if (!viewModel.setOcrSource(source) || !viewModel.isOcrEnabled()) return

        when (val viewer = viewModel.state.value.viewer) {
            is PagerViewer -> {
                viewer.setOcrEnabled(false)
                viewer.setOcrEnabled(true)
            }
            is WebtoonViewer -> {
                viewer.setOcrEnabled(false)
                viewer.setOcrEnabled(true)
            }
        }
    }

    /**
     * Called when the user clicks the back key or the button on the toolbar. The call is
     * delegated to the presenter.
     */
    override fun finish() {
        viewModel.onActivityFinish()
        super.finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                R.anim.shared_axis_x_pop_enter,
                R.anim.shared_axis_x_pop_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.shared_axis_x_pop_enter, R.anim.shared_axis_x_pop_exit)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_N) {
            loadNextChapter()
            return true
        } else if (keyCode == KeyEvent.KEYCODE_P) {
            loadPreviousChapter()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /**
     * Dispatches a key event. If the viewer doesn't handle it, call the default implementation.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ocrSelectionPanelState != null) {
            return super.dispatchKeyEvent(event)
        }

        // Chimahon: Redirect volume keys to the OCR popup if it's active
        if (ocrPopupVisible) {
            ocrPopupState?.let { popup ->
                if (event.action == KeyEvent.ACTION_DOWN &&
                    Injekt.get<DictionaryPreferences>().volumeKeyNavigation().get()
                ) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_VOLUME_UP -> {
                            popup.webView.evaluateJavascript("window.DictionaryRenderer?.navigate(-1);", null)
                            return true
                        }
                        KeyEvent.KEYCODE_VOLUME_DOWN -> {
                            popup.webView.evaluateJavascript("window.DictionaryRenderer?.navigate(1);", null)
                            return true
                        }
                    }
                }
            }
        }

        val handled = viewModel.state.value.viewer?.handleKeyEvent(event) ?: false
        return handled || super.dispatchKeyEvent(event)
    }

    /**
     * Dispatches a generic motion event. If the viewer doesn't handle it, call the default
     * implementation.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (ocrSelectionPanelState != null) {
            return super.dispatchGenericMotionEvent(event)
        }

        val handled = viewModel.state.value.viewer?.handleGenericMotionEvent(event) ?: false
        return handled || super.dispatchGenericMotionEvent(event)
    }

    @Composable
    private fun ContentOverlay(state: ReaderViewModel.State) {
        val flashOnPageChange by readerPreferences.flashOnPageChange().collectAsState()
        val flashOnScroll by readerPreferences.flashOnScroll().collectAsState()

        val colorOverlayEnabled by readerPreferences.colorFilter().collectAsState()
        val colorOverlay by readerPreferences.colorFilterValue().collectAsState()
        val colorOverlayMode by readerPreferences.colorFilterMode().collectAsState()
        val colorOverlayBlendMode = remember(colorOverlayMode) {
            ReaderPreferences.ColorFilterMode.getOrNull(colorOverlayMode)?.second
        }
        val dictionaryPreferences = remember { Injekt.get<DictionaryPreferences>() }
        val ocrOutlineVisible by readerPreferences.ocrOutlineVisible().collectAsState()
        val ocrBoxScaleX by dictionaryPreferences.ocrBoxScaleX().collectAsState()
        val ocrBoxScaleY by dictionaryPreferences.ocrBoxScaleY().collectAsState()
        val ocrBoxOpacity by dictionaryPreferences.ocrBoxOpacity().collectAsState()
        val activeOcrTextOpacity by dictionaryPreferences.activeOcrTextOpacity().collectAsState()
        val activeOcrBgOpacity by dictionaryPreferences.activeOcrBgOpacity().collectAsState()

        LaunchedEffect(state.viewer, ocrOutlineVisible, ocrBoxScaleX, ocrBoxScaleY, ocrBoxOpacity, activeOcrTextOpacity, activeOcrBgOpacity) {
            when (val viewer = state.viewer) {
                is PagerViewer -> {
                    viewer.setOcrOutlineVisible(ocrOutlineVisible)
                    viewer.setOcrBoxScale(ocrBoxScaleX, ocrBoxScaleY)
                    viewer.setOcrBoxOpacity(ocrBoxOpacity)
                    viewer.setActiveOcrTextOpacity(activeOcrTextOpacity)
                    viewer.setActiveOcrBgOpacity(activeOcrBgOpacity)
                }
                is WebtoonViewer -> {
                    viewer.setOcrOutlineVisible(ocrOutlineVisible)
                    viewer.setOcrBoxScale(ocrBoxScaleX, ocrBoxScaleY)
                    viewer.setOcrBoxOpacity(ocrBoxOpacity)
                    viewer.setActiveOcrTextOpacity(activeOcrTextOpacity)
                    viewer.setActiveOcrBgOpacity(activeOcrBgOpacity)
                }
            }
        }

        ReaderContentOverlay(
            brightness = state.brightnessOverlayValue,
            color = colorOverlay.takeIf { colorOverlayEnabled },
            colorBlendMode = colorOverlayBlendMode,
        )

        if (flashOnPageChange || flashOnScroll) {
            DisplayRefreshHost(hostState = displayRefreshHost)
        }
    }

    @Composable
    private fun BoxScope.OcrProgressHud(
        visible: Boolean,
        progress: ReaderViewModel.OcrScanProgress?,
    ) {
        AnimatedVisibility(
            visible = visible && progress != null,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = ocrProgressHudTopPadding, end = 12.dp),
        ) {
            val safeProgress = progress ?: return@AnimatedVisibility
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "OCR ${safeProgress.completedPages}/${safeProgress.totalPages}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }

    @Composable
    fun AppBars(state: ReaderViewModel.State) {
        if (!ifSourcesLoaded()) {
            return
        }

        val isHttpSource = viewModel.getSource() is HttpSource

        val cropBorderPaged by readerPreferences.cropBorders().collectAsState()
        val cropBorderWebtoon by readerPreferences.cropBordersWebtoon().collectAsState()
        // SY -->
        val readingMode = viewModel.getMangaReadingMode()
        val isPagerType = ReadingMode.isPagerType(readingMode)
        val isWebtoon = ReadingMode.WEBTOON.flagValue == readingMode
        val cropBorderContinuousVertical by readerPreferences.cropBordersContinuousVertical().collectAsState()
        val cropEnabled = if (isPagerType) {
            cropBorderPaged
        } else if (isWebtoon) {
            cropBorderWebtoon
        } else {
            cropBorderContinuousVertical
        }
        val readerBottomButtons by readerPreferences.readerBottomButtons().changes().map { it.toImmutableSet() }
            .collectAsState(persistentSetOf())
        val dualPageSplitPaged by readerPreferences.dualPageSplitPaged().collectAsState()

        val forceHorizontalSeekbar by readerPreferences.forceHorizontalSeekbar().collectAsState()
        val landscapeVerticalSeekbar by readerPreferences.landscapeVerticalSeekbar().collectAsState()
        val leftHandedVerticalSeekbar by readerPreferences.leftVerticalSeekbar().collectAsState()
        val configuration = LocalConfiguration.current
        val verticalSeekbarLandscape =
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && landscapeVerticalSeekbar
        val verticalSeekbarHorizontal = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val viewerIsVertical = (state.viewer is WebtoonViewer || state.viewer is VerticalPagerViewer)
        val showVerticalSeekbar =
            !forceHorizontalSeekbar && (verticalSeekbarLandscape || verticalSeekbarHorizontal) && viewerIsVertical
        val navBarType = when {
            !showVerticalSeekbar -> NavBarType.Bottom
            leftHandedVerticalSeekbar -> NavBarType.VerticalLeft
            else -> NavBarType.VerticalRight
        }
        // SY <--

        // Chimahon: OCR overlay preference
        val ocrOverlayEnabled by readerPreferences.ocrOverlayEnabled().collectAsState()
        val ocrEnabled = ocrOverlayEnabled && viewModel.isOcrAllowedForCurrentManga()

        ReaderAppBars(
            visible = state.menuVisible,

            mangaTitle = state.manga?.title,
            chapterTitle = state.currentChapter?.chapter?.name,
            navigateUp = onBackPressedDispatcher::onBackPressed,
            onClickTopAppBar = ::openMangaScreen,
            bookmarked = state.bookmarked,
            onToggleBookmarked = viewModel::toggleChapterBookmark,
            onOpenInWebView = ::openChapterInWebView.takeIf { isHttpSource },
            onOpenInBrowser = ::openChapterInBrowser.takeIf { isHttpSource },
            onShare = ::shareChapter.takeIf { isHttpSource },

            viewer = state.viewer,
            onNextChapter = ::loadNextChapter,
            enabledNext = state.viewerChapters?.nextChapter != null,
            onPreviousChapter = ::loadPreviousChapter,
            enabledPrevious = state.viewerChapters?.prevChapter != null,
            currentPage = state.currentPage,
            totalPages = state.totalPages,
            onPageIndexChange = {
                isScrollingThroughPages = true
                moveToPageIndex(it)
            },

            readingMode = ReadingMode.fromPreference(
                viewModel.getMangaReadingMode(resolveDefault = false),
            ),
            onClickReadingMode = viewModel::openReadingModeSelectDialog,
            orientation = ReaderOrientation.fromPreference(
                viewModel.getMangaOrientation(resolveDefault = false),
            ),
            onClickOrientation = viewModel::openOrientationModeSelectDialog,
            cropEnabled = cropEnabled,
            onClickCropBorder = {
                val enabled = viewModel.toggleCropBorders()
                menuToggleToast?.cancel()
                menuToggleToast = toast(if (enabled) MR.strings.on else MR.strings.off)
            },
            ocrEnabled = ocrEnabled,
            ocrLoading = state.ocrScanProgress != null,
            ocrSource = state.ocrSource,
            mokuroAvailable = viewModel.isMokuroAvailable(),
            onToggleOcr = ::toggleOcrFromReader,
            onSelectOcrSource = ::selectOcrSourceFromReader,
            onClickSettings = viewModel::openSettingsDialog,
            onClickMangaStats = viewModel::openMangaStatsSheet,
            // SY -->
            isExhToolsVisible = state.ehUtilsVisible,
            onSetExhUtilsVisibility = viewModel::showEhUtils,
            isAutoScroll = state.autoScroll,
            isAutoScrollEnabled = state.isAutoScrollEnabled,
            onToggleAutoscroll = viewModel::toggleAutoScroll,
            autoScrollFrequency = state.ehAutoscrollFreq,
            onSetAutoScrollFrequency = viewModel::setAutoScrollFrequency,
            onClickAutoScrollHelp = viewModel::openAutoScrollHelpDialog,
            onClickRetryAll = ::exhRetryAll,
            onClickRetryAllHelp = viewModel::openRetryAllHelp,
            onClickBoostPage = ::exhBoostPage,
            onClickBoostPageHelp = viewModel::openBoostPageHelp,
            currentPageText = state.currentPageText,
            navBarType = navBarType,
            enabledButtons = readerBottomButtons,
            currentReadingMode = ReadingMode.fromPreference(
                viewModel.getMangaReadingMode(resolveDefault = true),
            ),
            dualPageSplitEnabled = dualPageSplitPaged,
            doublePages = state.doublePages,
            onClickChapterList = viewModel::openChapterListDialog,
            onClickPageLayout = {
                if (readerPreferences.pageLayout().get() == PagerConfig.PageLayout.AUTOMATIC) {
                    (viewModel.state.value.viewer as? PagerViewer)?.config?.let { config ->
                        config.doublePages = !config.doublePages
                        reloadChapters(config.doublePages, true)
                    }
                } else {
                    readerPreferences.pageLayout().set(1 - readerPreferences.pageLayout().get())
                }
            },
            onClickShiftPage = ::shiftDoublePages,
            // SY <--
        )
    }

    // KMK -->
    @Composable
    private fun seedColorState(): ComposeColor? {
        val state by viewModel.state.collectAsState()
        return state.manga?.asMangaCover()?.vibrantCoverColor?.let { ComposeColor(it) }
            ?: seedColorStatic()
    }

    private fun seedColorStatic(): ComposeColor? {
        return viewModel.manga?.asMangaCover()?.vibrantCoverColor?.let { ComposeColor(it) }
            ?: intent.extras?.getLong("manga")?.takeIf { it > 0 }
                ?.let { MangaCover.vibrantCoverColorMap[it] }
                ?.let { ComposeColor(it) }
    }
    // KMK <--

    // EXH -->
    private fun enableExhAutoScroll() {
        readerPreferences.autoscrollInterval().changes()
            .combine(viewModel.state.map { it.autoScroll }.distinctUntilChanged()) { interval, enabled ->
                interval.toDouble() to enabled
            }.mapLatest { (intervalFloat, enabled) ->
                if (enabled) {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        val interval = intervalFloat.seconds
                        while (true) {
                            if (!viewModel.state.value.menuVisible) {
                                viewModel.state.value.viewer.let { v ->
                                    when (v) {
                                        is PagerViewer -> v.moveToNext()
                                        is WebtoonViewer -> {
                                            val smoothAutoScroll = readerPreferences.smoothAutoScroll().get() &&
                                                !readerPreferences.eInkMode().get()
                                            if (smoothAutoScroll) {
                                                v.linearScroll(interval)
                                            } else {
                                                v.scrollDown()
                                            }
                                        }
                                    }
                                }
                                delay(interval)
                            } else {
                                delay(100)
                            }
                        }
                    }
                }
            }
            .launchIn(lifecycleScope)
    }

    private fun exhRetryAll() {
        var retried = 0

        viewModel.state.value.viewerChapters
            ?.currChapter
            ?.pages
            ?.forEachIndexed { _, page ->
                var shouldQueuePage = false
                if (page.status is Page.State.Error) {
                    shouldQueuePage = true
                } /*else if (page.status == Page.State.LoadPage ||
                                    page.status == Page.State.DownloadImage) {
                                // Do nothing
                            }*/

                if (shouldQueuePage) {
                    page.status = Page.State.Queue
                } else {
                    return@forEachIndexed
                }

                // If we are using EHentai/ExHentai, get a new image URL
                viewModel.manga?.let { m ->
                    val src = sourceManager.get(m.source)
                    if (src?.isEhBasedSource() == true) {
                        page.imageUrl = null
                    }
                }

                val loader = page.chapter.pageLoader
                if (page.index == exhCurrentpage()?.index && loader is HttpPageLoader) {
                    loader.boostPage(page)
                } else {
                    loader?.retryPage(page)
                }

                retried++
            }

        toast(pluralStringResource(SYMR.plurals.eh_retry_toast, retried, retried))
    }

    private fun exhBoostPage() {
        viewModel.state.value.viewer ?: return
        val curPage = exhCurrentpage() ?: run {
            toast(SYMR.strings.eh_boost_page_invalid)
            return
        }

        if (curPage.status is Page.State.Error) {
            toast(SYMR.strings.eh_boost_page_errored)
        } else if (curPage.status == Page.State.LoadPage || curPage.status == Page.State.DownloadImage) {
            toast(SYMR.strings.eh_boost_page_downloading)
        } else if (curPage.status == Page.State.Ready) {
            toast(SYMR.strings.eh_boost_page_downloaded)
        } else {
            val loader = (viewModel.state.value.viewerChapters?.currChapter?.pageLoader as? HttpPageLoader)
            if (loader != null) {
                loader.boostPage(curPage)
                toast(SYMR.strings.eh_boost_boosted)
            } else {
                toast(SYMR.strings.eh_boost_invalid_loader)
            }
        }
    }

    private fun exhCurrentpage(): ReaderPage? {
        val viewer = viewModel.state.value.viewer
        val currentPage = (((viewer as? PagerViewer)?.currentPage ?: (viewer as? WebtoonViewer)?.currentPage) as? ReaderPage)?.index
        return currentPage?.let { viewModel.state.value.viewerChapters?.currChapter?.pages?.getOrNull(it) }
    }

    fun reloadChapters(doublePages: Boolean, force: Boolean = false) {
        val viewer = viewModel.state.value.viewer as? PagerViewer ?: return
        viewer.updateShifting()
        if (!force && viewer.config.autoDoublePages) {
            setDoublePageMode(viewer)
        } else {
            viewer.config.doublePages = doublePages
            viewModel.setDoublePages(viewer.config.doublePages)
        }
        val currentChapter = viewModel.state.value.currentChapter
        if (doublePages) {
            // If we're moving from singe to double, we want the current page to be the first page
            val currentPage = viewModel.state.value.currentPage
            viewer.config.shiftDoublePage = (
                currentPage + (currentChapter?.pages?.take(currentPage)?.count { it.fullPage || it.isolatedPage } ?: 0)
                ) % 2 != 0
        }
        viewModel.state.value.viewerChapters?.let {
            viewer.setChaptersInternal(it)
        }
    }

    private fun setDoublePageMode(viewer: PagerViewer) {
        val currentOrientation = resources.configuration.orientation
        viewer.config.doublePages = currentOrientation == Configuration.ORIENTATION_LANDSCAPE
        viewModel.setDoublePages(viewer.config.doublePages)
    }

    private fun shiftDoublePages() {
        val viewer = viewModel.state.value.viewer as? PagerViewer ?: return
        viewer.config.let { config ->
            config.shiftDoublePage = !config.shiftDoublePage

            viewModel.manga?.id?.let { mangaId ->
                getSharedPreferences("reader_prefs", MODE_PRIVATE)
                    .edit()
                    .putBoolean("shift_doublepage$mangaId", config.shiftDoublePage)
                    .apply()
            }

            viewModel.state.value.viewerChapters?.let {
                viewer.updateShifting()
                viewer.setChaptersInternal(it)
                invalidateOptionsMenu()
            }
        }
    }
    // EXH <--

    /**
     * Sets the visibility of the menu according to [visible].
     */
    private fun setMenuVisibility(visible: Boolean) {
        viewModel.showMenus(visible)
        if (visible) {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
        } else if (readerPreferences.fullscreen().get()) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Called from the presenter when a manga is ready. Used to instantiate the appropriate viewer.
     */
    private fun updateViewer() {
        val prevViewer = viewModel.state.value.viewer
        val newViewer = ReadingMode.toViewer(
            viewModel.getMangaReadingMode(),
            this,
            // KMK -->
            seedColor = seedColorStatic()?.toArgb(),
            // KMK <--
        )

        if (window.sharedElementEnterTransition is MaterialContainerTransform) {
            // Wait until transition is complete to avoid crash on API 26
            window.sharedElementEnterTransition.doOnEnd {
                setOrientation(viewModel.getMangaOrientation())
            }
        } else {
            setOrientation(viewModel.getMangaOrientation())
        }

        // Destroy previous viewer if there was one
        if (prevViewer != null) {
            prevViewer.destroy()
            binding.viewerContainer.removeAllViews()
        }
        viewModel.onViewerLoaded(newViewer)
        updateViewerInset(readerPreferences.fullscreen().get(), readerPreferences.drawUnderCutout().get())
        binding.viewerContainer.addView(newViewer.getView())

        // SY -->
        if (newViewer is PagerViewer) {
            if (readerPreferences.pageLayout().get() == PagerConfig.PageLayout.AUTOMATIC) {
                setDoublePageMode(newViewer)
            }

            val savedShift = viewModel.manga?.id?.let { mangaId ->
                getSharedPreferences("reader_prefs", MODE_PRIVATE)
                    .getBoolean("shift_doublepage$mangaId", false)
            } ?: false
            newViewer.config.shiftDoublePage = viewModel.state.value.lastShiftDoubleState ?: savedShift
        }

        val manga = viewModel.state.value.manga
        val defaultReaderType = manga?.defaultReaderType(
            manga.mangaType(sourceName = sourceManager.get(manga.source)?.name),
        )
        if (
            readerPreferences.useAutoWebtoon().get() &&
            (manga?.readingMode?.toInt() ?: ReadingMode.DEFAULT.flagValue) == ReadingMode.DEFAULT.flagValue &&
            defaultReaderType != null &&
            defaultReaderType == ReadingMode.WEBTOON.flagValue
        ) {
            readingModeToast?.cancel()
            readingModeToast = toast(SYMR.strings.eh_auto_webtoon_snack)
        } else if (readerPreferences.showReadingMode().get()) {
            // SY <--
            showReadingModeToast(viewModel.getMangaReadingMode())
        }

        loadingIndicator = ReaderProgressIndicator(
            context = this,
            // KMK -->
            seedColor = seedColorStatic()?.toArgb(),
            // KMK <--
        )
        binding.readerContainer.addView(loadingIndicator)

        startPostponedEnterTransition()
    }

    private fun openMangaScreen() {
        viewModel.manga?.id?.let { id ->
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    action = Constants.SHORTCUT_MANGA
                    putExtra(Constants.MANGA_EXTRA, id)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
            )
        }
    }

    private fun openChapterInWebView() {
        val manga = viewModel.manga ?: return
        val source = viewModel.getSource() ?: return
        assistUrl?.let {
            val intent = WebViewActivity.newIntent(this@ReaderActivity, it, source.id, manga.title)
            startActivity(intent)
        }
    }

    private fun openChapterInBrowser() {
        assistUrl?.let {
            openInBrowser(it.toUri(), forceDefaultBrowser = false)
        }
    }

    private fun shareChapter() {
        assistUrl?.let {
            val intent = it.toUri().toShareIntent(this, type = "text/plain")
            startActivity(intent)
        }
    }

    private fun showReadingModeToast(mode: Int) {
        try {
            readingModeToast?.cancel()
            readingModeToast = toast(ReadingMode.fromPreference(mode).stringRes)
        } catch (_: ArrayIndexOutOfBoundsException) {
            logcat(LogPriority.ERROR) { "Unknown reading mode: $mode" }
        }
    }

    /**
     * Called from the presenter whenever a new [viewerChapters] have been set. It delegates the
     * method to the current viewer, but also set the subtitle on the toolbar, and
     * hides or disables the reader prev/next buttons if there's a prev or next chapter
     */
    @SuppressLint("RestrictedApi")
    private fun setChapters(viewerChapters: ViewerChapters) {
        loadingIndicator?.isVisible = false
        // SY -->
        val state = viewModel.state.value
        if (state.indexChapterToShift != null && state.indexPageToShift != null) {
            viewerChapters.currChapter.pages?.find {
                it.index == state.indexPageToShift && it.chapter.chapter.id == state.indexChapterToShift
            }?.let {
                (viewModel.state.value.viewer as? PagerViewer)?.updateShifting(it)
            }
            viewModel.setIndexChapterToShift(null)
            viewModel.setIndexPageToShift(null)
        } else if (state.lastShiftDoubleState != null) {
            val currentChapter = viewerChapters.currChapter
            (viewModel.state.value.viewer as? PagerViewer)?.config?.shiftDoublePage = (
                currentChapter.requestedPage +
                    (
                        currentChapter.pages?.take(currentChapter.requestedPage)
                            ?.count { it.fullPage || it.isolatedPage } ?: 0
                        )
                ) % 2 != 0
        }
        // SY <--

        viewModel.state.value.viewer?.setChapters(viewerChapters)

        lifecycleScope.launchIO {
            viewModel.getChapterUrl()?.let { url ->
                assistUrl = url
            }
        }

        // AM (DISCORD) -->
        updateDiscordRPC(exitingReader = false)
        // <-- AM (DISCORD)
    }

    /**
     * Called from the presenter if the initial load couldn't load the pages of the chapter. In
     * this case the activity is closed and a toast is shown to the user.
     */
    private fun setInitialChapterError(error: Throwable) {
        logcat(LogPriority.ERROR, error)
        finish()
        toast(error.message)
    }

    /**
     * Called from the presenter whenever it's loading the next or previous chapter. It shows or
     * dismisses a non-cancellable dialog to prevent user interaction according to the value of
     * [show]. This is only used when the next/previous buttons on the toolbar are clicked; the
     * other cases are handled with chapter transitions on the viewers and chapter preloading.
     */
    private fun setProgressDialog(show: Boolean) {
        if (show) {
            viewModel.showLoadingDialog()
        } else {
            viewModel.closeDialog()
        }
    }

    /**
     * Moves the viewer to the given page [index]. It does nothing if the viewer is null or the
     * page is not found.
     */
    private fun moveToPageIndex(index: Int) {
        val viewer = viewModel.state.value.viewer ?: return
        val currentChapter = viewModel.state.value.currentChapter ?: return
        val page = currentChapter.pages?.getOrNull(index) ?: return
        viewer.moveToPage(page)
    }

    /**
     * Tells the presenter to load the next chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    private fun loadNextChapter() {
        lifecycleScope.launch {
            viewModel.loadNextChapter()
            moveToPageIndex(0)
        }
    }

    /**
     * Tells the presenter to load the previous chapter and mark it as active. The progress dialog
     * should be automatically shown.
     */
    private fun loadPreviousChapter() {
        lifecycleScope.launch {
            viewModel.loadPreviousChapter()
            moveToPageIndex(0)
        }
    }

    /**
     * Called from the viewer whenever a [page] is marked as active. It updates the values of the
     * bottom menu and delegates the change to the presenter.
     */
    fun onPageSelected(page: ReaderPage, hasExtraPage: Boolean = false) {
        // SY -->
        val currentPageText = if (hasExtraPage) {
            val invertDoublePage = (viewModel.state.value.viewer as? PagerViewer)?.config?.invertDoublePages ?: false
            if ((resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_LTR) xor
                invertDoublePage
            ) {
                "${page.number}-${page.number + 1}"
            } else {
                "${page.number + 1}-${page.number}"
            }
        } else {
            "${page.number}"
        }
        // SY <--
        viewModel.onPageSelected(page, /* SY --> */ currentPageText, hasExtraPage /* SY <-- */)
    }

    /**
     * Called from the continuous viewers (webtoon/novel) when the user scrolls or flings, so the
     * e-ink display can refresh on movement rather than only on page change.
     */
    fun onReaderScroll() {
        if (readerPreferences.flashOnScroll().get()) {
            displayRefreshHost.flashOnScroll()
        }
    }

    /**
     * Called from the viewer whenever a [page] is long clicked. A bottom sheet with a list of
     * actions to perform is shown.
     */
    fun onPageLongTap(page: ReaderPage, /* SY --> */ extraPage: ReaderPage? = null /* SY <-- */) {
        viewModel.openPageDialog(page, /* SY --> */ extraPage /* SY <-- */)
    }

    /**
     * Called from the viewer when the given [chapter] should be preloaded. It should be called when
     * the viewer is reaching the beginning or end of a chapter or the transition page is active.
     */
    fun requestPreloadChapter(chapter: ReaderChapter) {
        lifecycleScope.launchIO { viewModel.preload(chapter) }
    }

    /**
     * Called from the viewer to toggle the visibility of the menu. It's implemented on the
     * viewer because each one implements its own touch and key events.
     */
    fun toggleMenu() {
        setMenuVisibility(!viewModel.state.value.menuVisible)
    }

    /**
     * Called from the viewer to show the menu.
     */
    fun showMenu() {
        if (!viewModel.state.value.menuVisible) {
            setMenuVisibility(true)
        }
    }

    /**
     * Called from the viewer to hide the menu.
     */
    fun hideMenu() {
        if (viewModel.state.value.menuVisible) {
            setMenuVisibility(false)
        }
    }

    /**
     * Called from the presenter when a page is ready to be shared. It shows Android's default
     * sharing tool.
     */
    fun onShareImageResult(uri: Uri, page: ReaderPage /* SY --> */, secondPage: ReaderPage? = null /* SY <-- */) {
        val manga = viewModel.manga ?: return
        val chapter = page.chapter.chapter

        val intent = uri.toShareIntent(
            context = applicationContext,
            message = // SY -->
            if (secondPage != null) {
                stringResource(
                    SYMR.strings.share_pages_info,
                    manga.title,
                    chapter.name,
                    if (resources.configuration.layoutDirection ==
                        View.LAYOUT_DIRECTION_LTR
                    ) {
                        "${page.number}-${page.number + 1}"
                    } else {
                        "${page.number + 1}-${page.number}"
                    },
                )
            } else {
                // SY <--
                stringResource(MR.strings.share_page_info, manga.title, chapter.name, page.number)
            },
        )
        startActivity(intent)
    }

    private fun onCopyImageResult(uri: Uri) {
        val clipboardManager = applicationContext.getSystemService<ClipboardManager>() ?: return
        val clipData = ClipData.newUri(applicationContext.contentResolver, "", uri)
        clipboardManager.setPrimaryClip(clipData)
    }

    /**
     * Called from the presenter when a page is saved or fails. It shows a message or logs the
     * event depending on the [result].
     */
    private fun onSaveImageResult(result: ReaderViewModel.SaveImageResult) {
        when (result) {
            is ReaderViewModel.SaveImageResult.Success -> {
                toast(MR.strings.picture_saved)
            }
            is ReaderViewModel.SaveImageResult.Error -> {
                logcat(LogPriority.ERROR, result.error)
            }
        }
    }

    /**
     * Called from the presenter when a page is set as cover or fails. It shows a different message
     * depending on the [result].
     */
    private fun onSetAsCoverResult(result: ReaderViewModel.SetAsCoverResult) {
        toast(
            when (result) {
                Success -> MR.strings.cover_updated
                AddToLibraryFirst -> MR.strings.notification_first_add_to_library
                Error -> MR.strings.notification_cover_update_failed
            },
        )
    }

    /**
     * Forces the user preferred [orientation] on the activity.
     */
    private fun setOrientation(orientation: Int) {
        val newOrientation = ReaderOrientation.fromPreference(orientation)
        if (newOrientation.flag != requestedOrientation) {
            requestedOrientation = newOrientation.flag
        }
    }

    /**
     * Updates viewer inset depending on fullscreen reader preferences.
     */
    private fun updateViewerInset(fullscreen: Boolean, drawUnderCutout: Boolean) {
        if (!::binding.isInitialized) return
        val view = binding.viewerContainer

        view.applyInsetsPadding(ViewCompat.getRootWindowInsets(view), fullscreen, drawUnderCutout)
        ViewCompat.setOnApplyWindowInsetsListener(view) { view, windowInsets ->
            view.applyInsetsPadding(windowInsets, fullscreen, drawUnderCutout)
            windowInsets
        }
    }

    private fun View.applyInsetsPadding(
        windowInsets: WindowInsetsCompat?,
        fullscreen: Boolean,
        drawUnderCutout: Boolean,
    ) {
        val insets = when {
            !fullscreen -> windowInsets?.getInsets(WindowInsetsCompat.Type.systemBars())
            !drawUnderCutout -> windowInsets?.getInsets(WindowInsetsCompat.Type.displayCutout())
            else -> null
        }
            ?: Insets.NONE

        setPadding(insets.left, insets.top, insets.right, insets.bottom)
    }

    /**
     * Class that handles the user preferences of the reader.
     */
    private inner class ReaderConfig {

        private fun getCombinedPaint(grayscale: Boolean, invertedColors: Boolean): Paint {
            return Paint().apply {
                colorFilter = ColorMatrixColorFilter(
                    ColorMatrix().apply {
                        if (grayscale) {
                            setSaturation(0f)
                        }
                        if (invertedColors) {
                            postConcat(
                                ColorMatrix(
                                    floatArrayOf(
                                        -1f, 0f, 0f, 0f, 255f,
                                        0f, -1f, 0f, 0f, 255f,
                                        0f, 0f, -1f, 0f, 255f,
                                        0f, 0f, 0f, 1f, 0f,
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
        }

        private val grayBackgroundColor = Color.rgb(0x20, 0x21, 0x25)

        /*
         * Initializes the reader subscriptions.
         */
        init {
            readerPreferences.readerTheme().changes()
                .onEach { theme ->
                    binding.readerContainer.setBackgroundColor(
                        when (theme) {
                            0 -> Color.WHITE
                            2 -> grayBackgroundColor
                            3 -> automaticBackgroundColor()
                            else -> Color.BLACK
                        },
                    )
                }
                .launchIn(lifecycleScope)

            preferences.displayProfile().changes()
                .onEach { setDisplayProfile(it) }
                .launchIn(lifecycleScope)

            readerPreferences.keepScreenOn().changes()
                .onEach(::setKeepScreenOn)
                .launchIn(lifecycleScope)

            readerPreferences.customBrightness().changes()
                .onEach(::setCustomBrightness)
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.grayscale().changes(),
                readerPreferences.invertedColors().changes(),
            ) { grayscale, invertedColors -> grayscale to invertedColors }
                .onEach { (grayscale, invertedColors) ->
                    setLayerPaint(grayscale, invertedColors)
                }
                .launchIn(lifecycleScope)

            combine(
                readerPreferences.fullscreen().changes(),
                readerPreferences.drawUnderCutout().changes(),
            ) { fullscreen, drawUnderCutout -> fullscreen to drawUnderCutout }
                .onEach { (fullscreen, drawUnderCutout) ->
                    updateViewerInset(fullscreen, drawUnderCutout)
                }
                .launchIn(lifecycleScope)

            // SY -->
            readerPreferences.pageLayout().changes()
                .drop(1)
                .onEach {
                    viewModel.setDoublePages(
                        (viewModel.state.value.viewer as? PagerViewer)
                            ?.config
                            ?.doublePages
                            ?: false,
                    )
                }
                .launchIn(lifecycleScope)

            readerPreferences.dualPageSplitPaged().changes()
                .drop(1)
                .onEach {
                    if (viewModel.state.value.viewer !is PagerViewer) return@onEach
                    reloadChapters(
                        !it &&
                            when (readerPreferences.pageLayout().get()) {
                                PagerConfig.PageLayout.DOUBLE_PAGES -> true
                                PagerConfig.PageLayout.AUTOMATIC ->
                                    resources.configuration.orientation ==
                                        Configuration.ORIENTATION_LANDSCAPE
                                else -> false
                            },
                        true,
                    )
                }
                .launchIn(lifecycleScope)
            // SY <--
        }

        /**
         * Picks background color for [ReaderActivity] based on light/dark theme preference
         */
        private fun automaticBackgroundColor(): Int {
            return if (baseContext.isNightMode()) {
                grayBackgroundColor
            } else {
                Color.WHITE
            }
        }

        /**
         * Sets the display profile to [path].
         */
        private fun setDisplayProfile(path: String) {
            val file = UniFile.fromUri(baseContext, path.toUri())
            if (file != null && file.exists()) {
                val inputStream = file.openInputStream()
                val outputStream = ByteArrayOutputStream()
                inputStream.use { input ->
                    outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                val data = outputStream.toByteArray()
                SubsamplingScaleImageView.setDisplayProfile(data)
                TachiyomiImageDecoder.displayProfile = data
            }
        }

        /**
         * Sets the keep screen on mode according to [enabled].
         */
        private fun setKeepScreenOn(enabled: Boolean) {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /**
         * Sets the custom brightness overlay according to [enabled].
         */
        private fun setCustomBrightness(enabled: Boolean) {
            if (enabled) {
                readerPreferences.customBrightnessValue().changes()
                    .sample(100)
                    .onEach(::setCustomBrightnessValue)
                    .launchIn(lifecycleScope)
            } else {
                setCustomBrightnessValue(0)
            }
        }

        /**
         * Sets the brightness of the screen. Range is [-75, 100].
         * From -75 to -1 a semi-transparent black view is overlaid with the minimum brightness.
         * From 1 to 100 it sets that value as brightness.
         * 0 sets system brightness and hides the overlay.
         */
        private fun setCustomBrightnessValue(value: Int) {
            // Calculate and set reader brightness.
            val readerBrightness = when {
                value > 0 -> {
                    value / 100f
                }
                value < 0 -> {
                    0.01f
                }
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            window.attributes = window.attributes.apply { screenBrightness = readerBrightness }

            viewModel.setBrightnessOverlayValue(value)
        }
        private fun setLayerPaint(grayscale: Boolean, invertedColors: Boolean) {
            val paint = if (grayscale || invertedColors) getCombinedPaint(grayscale, invertedColors) else null
            binding.viewerContainer.setLayerType(LAYER_TYPE_HARDWARE, paint)
        }
    }

    // KMK -->
    /**
     * Updates the Discord Rich Presence (RPC) status based on the current reader activity.
     *
     * @param exitingReader A boolean flag indicating whether the user is exiting the reader.
     * If true, the Discord RPC status is set to the last used screen.
     * If false, the Discord RPC status is set to the current reader activity, displaying details such as the manga title, chapter number, and chapter title.
     */
    private fun updateDiscordRPC(exitingReader: Boolean) {
        if (!connectionsPreferences.enableDiscordRPC().get()) return

        DiscordRPCService.discordScope.launchIO {
            try {
                if (!exitingReader) {
                    val manga = viewModel.manga ?: return@launchIO
                    val chapter = viewModel.currentChapter ?: return@launchIO

                    DiscordRPCService.setReaderActivity(
                        context = this@ReaderActivity,
                        ReaderData(
                            incognitoMode = viewModel.incognitoMode,
                            mangaId = manga.id,
                            mangaTitle = manga.ogTitle,
                            thumbnailUrl = manga.thumbnailUrl.takeIf { UrlUtils.isOnlineUrl(it) } ?: manga.ogThumbnailUrl,
                            chapterNumber = if (connectionsPreferences.useChapterTitles().get()) {
                                chapter.name
                            } else {
                                chapter.chapterNumber.toString()
                            },
                            startTimestamp = System.currentTimeMillis(),
                        ),
                    )
                } else {
                    with(DiscordRPCService) {
                        setScreen(this@ReaderActivity)
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Error updating Discord RPC: ${e.message}" }
            }
        }
    }
    // KMK <--

    private fun captureCurrentVisibleBitmap(): Bitmap? {
        return viewModel.getCurrentPageBitmap(ocrPopupState?.sourcePage)
    }

    private fun launchImageCropper() {
        val bitmap = captureCurrentVisibleBitmap()
        if (bitmap == null) {
            toast(MR.strings.decode_image_error)
            return
        }

        try {
            val cacheDir = cacheDir
            val file = java.io.File(cacheDir, "crop_temp_${System.currentTimeMillis()}.png")
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.provider",
                file,
            )

            val preset = (cachedActiveProfile ?: Injekt.get<DictionaryPreferences>().profileStore.getActiveProfile())
                .let { CropPresets.aspectByKey(it.ankiCropPreset) }
            val cropOptions = com.canhub.cropper.CropImageOptions().apply {
                cropShape = com.canhub.cropper.CropImageView.CropShape.RECTANGLE
                initialCropWindowPaddingRatio = 0.25f
                if (preset != null) {
                    fixAspectRatio = true
                    aspectRatioX = preset.x
                    aspectRatioY = preset.y
                } else {
                    fixAspectRatio = false
                    aspectRatioX = 1
                    aspectRatioY = 1
                }
                outputCompressQuality = 70
                outputCompressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    android.graphics.Bitmap.CompressFormat.WEBP
                }
                showProgressBar = true
                activityMenuIconColor = android.graphics.Color.WHITE
                activityBackgroundColor = android.graphics.Color.BLACK
                cropMenuCropButtonTitle = "Crop"
            }

            val options = com.canhub.cropper.CropImageContractOptions(
                uri = uri,
                cropImageOptions = cropOptions,
            )
            cropImageLauncher.launch(options)
            bitmap.recycle()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to launch image cropper: ${e.message}" }
            toast(MR.strings.decode_image_error)
        }
    }

    private suspend fun updateAnkiCardWithScreenshot(noteId: Long?, screenshotBytes: ByteArray?, glossaryIndex: Int?) {
        if (noteId == null || screenshotBytes == null) {
            logcat(LogPriority.WARN) { "updateAnkiCardWithScreenshot: noteId or screenshotBytes is null" }
            return
        }

        logcat(LogPriority.DEBUG) { "updateAnkiCardWithScreenshot: noteId=$noteId, bytesSize=${screenshotBytes.size}" }

        val processedBytes = try {
            val bitmap = OcrBitmapDecoder.decode(screenshotBytes)
            try {
                val result = ImageEncoder.encode(bitmap)
                result.bytes
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to re-encode screenshot, using original bytes" }
            screenshotBytes
        }

        try {
            val bridge = chimahon.anki.AnkiDroidBridge(this)
            val hash = try {
                java.security.MessageDigest.getInstance("SHA-1").digest(processedBytes).joinToString("") { "%02x".format(it) }.take(12)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to compute hash, using timestamp" }
                "screenshot_${System.currentTimeMillis()}"
            }
            logcat(LogPriority.DEBUG) { "Storing media with filename: chimahon_$hash.webp" }

            val filename = bridge.storeMedia(
                filename = "chimahon_$hash.webp",
                data = processedBytes,
            )
            logcat(LogPriority.DEBUG) { "Media stored, filename returned: $filename" }

            val prefs = Injekt.get<DictionaryPreferences>()
            // Use the already-resolved profile for this manga/source, not the raw global one.
            val activeProfile = cachedActiveProfile ?: prefs.profileStore.getActiveProfile()
            val fieldMapJson = activeProfile.ankiFieldMap
            logcat(LogPriority.DEBUG) { "Field map JSON (Profile: ${activeProfile.name}): $fieldMapJson" }

            val fieldMap = org.json.JSONObject(fieldMapJson)
            val fields = mutableMapOf<String, String>()
            val keyIterator = fieldMap.keys()
            while (keyIterator.hasNext()) {
                val key = keyIterator.next()
                val value = fieldMap.getString(key)
                logcat(LogPriority.DEBUG) { "Checking field: key=$key, value=$value, contains SCREENSHOT=${value.contains(chimahon.anki.Marker.SCREENSHOT)}" }
                if (value.contains(chimahon.anki.Marker.SCREENSHOT)) {
                    fields[key] = "<img src=\"$filename\">"
                    logcat(LogPriority.DEBUG) { "Added screenshot field: key=$key, imgTag=<img src=\"$filename\">" }
                }
            }

            logcat(LogPriority.DEBUG) { "Fields to update: $fields" }

            if (fields.isNotEmpty()) {
                logcat(LogPriority.DEBUG) { "Calling updateNoteFields for note $noteId" }
                bridge.updateNoteFields(noteId, fields)
                logcat(LogPriority.DEBUG) { "updateNoteFields completed" }
                withUIContext {
                    toast(MR.strings.anki_card_added)
                }
            } else {
                logcat(LogPriority.WARN) { "No fields with screenshot marker found in field map" }
                withUIContext {
                    toast(MR.strings.anki_card_error)
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to update Anki card with screenshot: ${e.message}" }
            withUIContext {
                toast(MR.strings.anki_card_error)
            }
        }
    }

    // ==================== Dictionary Popup State ====================
    private var ocrWebView: android.webkit.WebView? = null
    private val dictionaryRepository: chimahon.DictionaryRepository by injectLazy()

    private fun ensureOcrResources() {
        ocrWebView ?: createOcrWebView(this).also { ocrWebView = it }
    }

    private fun releaseOcrResources() {
        DictionaryPopupWebViewWarmup.recycle(this, ocrWebView)
        ocrWebView = null
    }

    private var cachedActiveProfile: chimahon.anki.AnkiProfile? = null
    private var cachedTermPaths: chimahon.DictionaryPaths? = null

    private fun getOrRefreshLookupPaths(): Pair<chimahon.anki.AnkiProfile, chimahon.DictionaryPaths> {
        val prefs = Injekt.get<eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences>()
        val profile = cachedActiveProfile ?: run {
            val manga = viewModel.manga
            val sourceId = manga?.source ?: 0L
            val sourceLang = if (sourceId != 0L) sourceManager.getOrStub(sourceId).lang else ""
            prefs.profileResolver.resolve(
                mangaId = manga?.id ?: 0L,
                sourceId = sourceId,
                sourceLang = sourceLang,
            ).also { cachedActiveProfile = it }
        }
        val paths = cachedTermPaths
            ?: eu.kanade.tachiyomi.ui.dictionary.getDictionaryPaths(this, profile)
                .also { cachedTermPaths = it }
        return profile to paths
    }

    /**
     * Start lookup work immediately. Session is warm, lookup is fast (~10-20ms),
     * so we can run it synchronously to avoid coroutine overhead.
     */
    private fun preDeferLookup(
        lookupString: String,
    ): Pair<chimahon.anki.AnkiProfile, kotlinx.coroutines.Deferred<chimahon.DictionaryRepository.LookupResult2>> {
        val (profile, termPaths) = getOrRefreshLookupPaths()
        val deferred = lifecycleScope.async(Dispatchers.Default) {
            dictionaryRepository.lookup(lookupString.trim(), termPaths, profile.languageCode)
        }
        return profile to deferred
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createOcrWebView(ctx: Context): android.webkit.WebView {
        val profileLang = getOrRefreshLookupPaths().first.languageCode
        return DictionaryPopupWebViewWarmup.acquire(ctx, profileLang)
    }
}
