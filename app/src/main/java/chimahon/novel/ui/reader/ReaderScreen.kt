package chimahon.novel.ui.reader

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import chimahon.novel.data.BookImporter
import chimahon.novel.data.BookMetadata
import chimahon.novel.data.Statistics
import chimahon.novel.interactor.RegisterLocalNovelHome
import chimahon.novel.reader.assessTextDamage
import chimahon.novel.reader.cleanBrokenBookFiles
import chimahon.novel.source.LocalNovelFiles
import chimahon.novel.sync.ttu.SyncDirection
import chimahon.novel.sync.ttu.TtuBookRef
import chimahon.novel.sync.ttu.TtuSyncManager
import chimahon.ocr.OcrLanguage
import chimahon.ocr.OcrResult
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private sealed interface ReaderLoadState {
    data object Loading : ReaderLoadState
    data class Error(val message: String, val showReimport: Boolean = false) : ReaderLoadState
    data class Ready(val viewModel: ReaderViewModel) : ReaderLoadState
}

enum class ActiveSheet {
    Appearance, Chapters, Statistics
}

@Composable
fun ReaderScreen(
    book: BookMetadata,
    novelId: Long? = null,
    chapterIndex: Int? = null,
    onBack: () -> Unit,
    showHud: Boolean = false,
    onShowHudChanged: (Boolean) -> Unit = {},
    onThemeChanged: (backgroundColor: Int) -> Unit = {},
    onLookupRequested: (String, String, Float, Float, Float, Float) -> Unit = { _, _, _, _, _, _ -> },
    onSentenceReady: (sentence: String) -> Unit = {},
    onDismissPopupRequested: () -> Unit = {},
    isPopupActive: Boolean = false,
    onViewModelReady: (ReaderViewModel?) -> Unit = {},
    additionalSettings: @Composable ColumnScope.() -> Unit = {},
    settingsNamespace: String? = null,
    onSelectionRectsReceived: ((String) -> Unit)? = null,
    recognizeImage: suspend (Bitmap, OcrLanguage) -> List<OcrResult> = { _, _ -> emptyList() },
    onImageOcrLookupRequested: (String, String, Int, Float, Float, Float, Float, Boolean, Bitmap) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
) {
    val context = LocalContext.current

    var focusMode by remember { mutableStateOf(false) }
    var activeSheet by remember { mutableStateOf<ActiveSheet?>(null) }
    var tappedImageUri by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val settings = remember(settingsNamespace) { chimahon.novel.data.NovelReaderSettings(context, settingsNamespace) }

    // Collect swipe and tap settings
    val chapterSwipeDistance by settings.chapterSwipeDistance.collectAsState(initial = 96)

    val currentTheme by settings.theme.collectAsState(initial = chimahon.novel.data.Theme.SYSTEM)
    val systemLightSepia by settings.systemLightSepia.collectAsState(initial = false)
    val customBg by settings.customBackgroundColor.collectAsState(initial = 0xFFF2E2C9.toInt())
    val customTxt by settings.customTextColor.collectAsState(initial = 0xFF000000.toInt())

    val initialSettings = remember(currentTheme, systemLightSepia, customBg, customTxt) {
        val (bg, txt) = when (currentTheme) {
            chimahon.novel.data.Theme.LIGHT -> 0xFFFFFFFF.toInt() to 0xFF000000.toInt()
            chimahon.novel.data.Theme.DARK -> 0xFF121212.toInt() to 0xFFE0E0E0.toInt()
            chimahon.novel.data.Theme.SEPIA -> 0xFFF2E2C9.toInt() to 0xFF3C2C1C.toInt()
            chimahon.novel.data.Theme.PURE_BLACK -> 0xFF000000.toInt() to 0xFFE0E0E0.toInt()
            chimahon.novel.data.Theme.CUSTOM -> customBg to customTxt
            chimahon.novel.data.Theme.SYSTEM -> {
                val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                if (isDark) {
                    if (systemLightSepia) 0xFF1C140C.toInt() to 0xFFF2E2C9.toInt()
                    else 0xFF121212.toInt() to 0xFFE0E0E0.toInt()
                } else {
                    if (systemLightSepia) 0xFFF2E2C9.toInt() to 0xFF3C2C1C.toInt()
                    else 0xFFFFFFFF.toInt() to 0xFF000000.toInt()
                }
            }
        }
        ReaderSettings(backgroundColor = bg, textColor = txt)
    }

    var loadingMessage by remember { mutableStateOf("Opening...") }
    var reloadCounter by remember { mutableIntStateOf(0) }

    // Damaged-book recovery: reimport the EPUB into the same folder (the
    // row is reused by folder identity), then reload the whole pipeline.
    val epubPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val imported = runCatching {
                val result = BookImporter.importEpub(
                    context,
                    uri,
                    targetRootUni = LocalNovelFiles.publicRootUni(context),
                )
                val metadata = result.metadata ?: return@runCatching false
                Injekt.get<RegisterLocalNovelHome>().register(metadata.id)
                true
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (imported) {
                    reloadCounter++
                } else {
                    context.toast("Import failed")
                }
            }
        }
    }

    val loadState by produceState<ReaderLoadState>(initialValue = ReaderLoadState.Loading, book.id, reloadCounter) {
        var loader: ReaderLoaderViewModel? = null
        var damageCleaned = false
        var healFailed = false
        value = try {
            // Post-import reloads must re-warm the extraction cache: the
            // loader resolves through it, never extracts by itself.
            if (reloadCounter > 0) {
                withContext(Dispatchers.IO) {
                    book.folder?.let { LocalNovelFiles.ensureReadableDir(context, it) }
                }
            }
            val firstLoad = withContext(Dispatchers.IO) {
                ReaderLoaderViewModel(context, book, novelId)
            }
            loader = firstLoad
            val bootLoader = checkNotNull(loader)

            // Broken text (truncated extractions, failed migrations) resolves
            // blank: clean regenerable files once and reload. Unrecoverable
            // books fall through to the error card with a reimport action.
            val docToCheck = bootLoader.document
            val rootToCheck = bootLoader.rootUrl
            if (docToCheck != null && rootToCheck != null && docToCheck.extractedDir != null) {
                val damaged = withContext(Dispatchers.IO) {
                    assessTextDamage(docToCheck)
                }
                if (damaged) {
                    loadingMessage = "Repairing book files..."
                    val folder = book.folder ?: rootToCheck.name
                    val report = withContext(Dispatchers.IO) {
                        cleanBrokenBookFiles(context, folder, rootToCheck)
                    }
                    damageCleaned = true
                    Log.w(
                        "ReaderScreen",
                        "cleaned broken book '${book.title}': cacheDropped=${report.cacheDropped}, " +
                            "treePruned=${report.treePruned}, sourceEpubPresent=${report.sourceEpubPresent}",
                    )
                    if (report.sourceEpubPresent) {
                        // Re-warm before rebuilding: cleanup dropped the cache
                        // the loader resolves through.
                        withContext(Dispatchers.IO) {
                            LocalNovelFiles.ensureReadableDir(context, folder)
                        }
                        val healedLoader = withContext(Dispatchers.IO) {
                            ReaderLoaderViewModel(context, book, novelId)
                        }
                        loader = healedLoader
                        val healed = healedLoader.document
                        healFailed = healed == null || withContext(Dispatchers.IO) {
                            assessTextDamage(healed)
                        }
                    } else {
                        healFailed = true
                    }
                }
            }

            val doneLoader = checkNotNull(loader)
            val document = if (healFailed) {
                null
            } else {
                doneLoader.document
            } ?: error("Could not open book")
            val rootUrl = doneLoader.rootUrl ?: error("Missing root URL")

            // TTU import-only sync (blocking): remote progress lands before
            // the VM seeds its resume position below.
            val ttuSyncManager = runCatching {
                Injekt.get<TtuSyncManager>()
            }.getOrNull()
            if (ttuSyncManager?.isEnabled == true && ttuSyncManager.autoSyncOnOpen) {
                loadingMessage = "Syncing reading progress..."
                withContext(Dispatchers.IO) {
                    val folder = book.folder ?: rootUrl.name
                    runCatching {
                        ttuSyncManager.syncBook(
                            TtuBookRef(folder, book.title ?: folder),
                            SyncDirection.AUTO,
                            importOnly = true,
                        )
                    }
                }
            }

            // Constructed off-main: init does disk reads (settings, bookmark,
            // statistics) that must never block the UI thread.
            val viewModel = withContext(Dispatchers.IO) {
                ReaderViewModel(
                    document = document,
                    rootUrl = rootUrl,
                    settings = settings,
                    scope = scope,
                    openNovelId = novelId,
                    openChapterIndex = chapterIndex,
                )
            }
            ReaderLoadState.Ready(viewModel)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (damageCleaned) {
                ReaderLoadState.Error(
                    message = "Book files were damaged and cleaned up. Choose the EPUB file to restore this book.",
                    showReimport = true,
                )
            } else {
                // Parse failed with a readable root on disk: content problem,
                // so the reimport action applies here too.
                val contentProblem = loader?.rootUrl != null && loader?.document == null
                ReaderLoadState.Error(
                    message = error.message ?: "Could not open book",
                    showReimport = contentProblem,
                )
            }
        }
    }

    val currentSettings = if (loadState is ReaderLoadState.Ready) {
        val vm = (loadState as ReaderLoadState.Ready).viewModel
        LaunchedEffect(vm) {
            onViewModelReady(vm)
            vm.startInitialChapter()
        }
        vm.getReaderSettings(context)
    } else {
        LaunchedEffect(Unit) {
            onViewModelReady(null)
        }
        initialSettings
    }

    LaunchedEffect(currentSettings.backgroundColor) {
        onThemeChanged(currentSettings.backgroundColor)
    }

    val bgColor = Color(currentSettings.backgroundColor)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Log.d("ReaderScreen", "BoxWithConstraints: maxHeight=$maxHeight, maxWidth=$maxWidth")

        // Capture initial height once, then use requiredHeight to override parent constraints
        var webViewHeightDp by remember { mutableStateOf<Dp?>(null) }
        val density = LocalDensity.current

        val heightModifier = if (webViewHeightDp != null) {
            Modifier.requiredHeight(webViewHeightDp!!)
        } else {
            Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    if (webViewHeightDp == null && size.height > 0) {
                        webViewHeightDp = with(density) { size.height.toDp() }
                        Log.d("ReaderScreen", "Captured fixed height: $webViewHeightDp")
                    }
                }
        }

        ReaderThemedArea(currentSettings) {
            when (val state = loadState) {
                ReaderLoadState.Loading -> ReaderMessage(loadingMessage, loading = true)
                is ReaderLoadState.Error -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ReaderMessage(state.message)
                    if (state.showReimport) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { epubPicker.launch("application/epub+zip") }) {
                            Text("Choose EPUB file")
                        }
                    }
                }
                is ReaderLoadState.Ready -> {
                    val viewModel = state.viewModel

                    val view = LocalView.current
                    DisposableEffect(viewModel.keepScreenOn) {
                        view.keepScreenOn = viewModel.keepScreenOn
                        onDispose {
                            view.keepScreenOn = false
                        }
                    }

                    DisposableEffect(Unit) {
                        onDispose {
                            viewModel.flushReaderState()
                        }
                    }

                    LaunchedEffect(isPopupActive) {
                        if (!isPopupActive) {
                            viewModel.bridge.send(WebViewCommand.ClearSelection)
                        }
                    }

                    val tapZonePx = with(density) { 64.dp.toPx() }.toInt()

                    val einkRefreshHost = remember { EinkRefreshHost() }

                    // Single WebView handles all chapters
                    ReaderWebView(
                        modifier = Modifier.fillMaxWidth().then(heightModifier),
                        bridge = viewModel.bridge,
                        continuousMode = viewModel.continuousMode,
                        isImageOnly = viewModel.isCurrentChapterImageOnly,
                        readerSettings = viewModel.getReaderSettings(context),
                        focusMode = focusMode,
                        onNextChapter = {
                            viewModel.onChapterEdge(NovelTurnDirection.NEXT)
                        },
                        onPreviousChapter = {
                            viewModel.onChapterEdge(NovelTurnDirection.PREV)
                        },
                        onProgressChanged = { viewModel.saveBookmark(it) },
                        onLoadFailed = { },
                        onTap = { if (focusMode) focusMode = false },
                        onTapTop = { onShowHudChanged(!showHud) },
                        onTapBottom = { onShowHudChanged(!showHud) },
                        swipeThreshold = chapterSwipeDistance,
                        tapZonePx = tapZonePx,
                        isPopupActive = isPopupActive,
                        onDismissPopupRequested = onDismissPopupRequested,
                        onTextSelected = { word, sentence, x, y, w, h -> onLookupRequested(word, sentence, x, y, w, h) },
                        onSentenceReady = onSentenceReady,
                        onInternalLinkClicked = { viewModel.jumpToUrl(it) },
                        onSelectionRectsReceived = onSelectionRectsReceived,
                        onPageTurned = {
                            viewModel.onPageTurned()
                            if (viewModel.einkRefreshOnPageTurn) einkRefreshHost.trigger()
                        },
                        onScrollMoved = { if (viewModel.einkRefreshOnScroll) einkRefreshHost.triggerScroll() },
                        onImageTapped = { uri -> tappedImageUri = uri },
                    )

                    if (viewModel.einkRefreshOnPageTurn || viewModel.einkRefreshOnScroll) {
                        EinkRefreshOverlay(
                            hostState = einkRefreshHost,
                            durationMillis = viewModel.einkRefreshDurationMillis,
                            delayMillis = viewModel.einkRefreshDelayMillis,
                            color = runCatching { EinkRefreshColor.valueOf(viewModel.einkRefreshColor) }
                                .getOrDefault(EinkRefreshColor.BLACK),
                            interval = viewModel.einkRefreshPageInterval,
                            modifier = Modifier
                                .fillMaxSize()
                                .align(Alignment.Center)
                                .zIndex(20f),
                        )
                    }
                }
            }
        }

        // Overlays — direct BoxWithConstraints children (BoxScope available)
        val readyVm = (loadState as? ReaderLoadState.Ready)?.viewModel

        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, readyVm) {
            if (readyVm == null) return@DisposableEffect onDispose {}
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> readyVm.onAppBackgrounded()
                    Lifecycle.Event.ON_RESUME -> readyVm.onAppForegrounded()
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        if (readyVm != null) {
            // Persistent tracking indicator
            if (readyVm.statisticsTracker.state.isTracking) {
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = "Tracking active",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 32.dp, end = 12.dp)
                        .size(12.dp),
                )
            }

            // Top HUD
            AnimatedVisibility(
                visible = showHud,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                ReaderTopBar(
                    title = readyVm.document.title().orEmpty(),
                    onBack = onBack,
                    onToggleHud = { onShowHudChanged(false) },
                    backgroundColor = currentSettings.backgroundColor,
                    contentColor = currentSettings.textColor,
                    modifier = Modifier
                        .statusBarsPadding()
                )
            }

            // Bottom HUD
            AnimatedVisibility(
                visible = showHud,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                ReaderBottomBar(
                    focusMode = focusMode,
                    progressText = "${(readyVm.currentProgress * 100).toInt()}%",
                    backgroundColor = currentSettings.backgroundColor,
                    contentColor = currentSettings.textColor,
                    onToggleHud = { onShowHudChanged(false) },
                    onToggleFocusMode = { focusMode = true },
                    onOpenChapters = { activeSheet = ActiveSheet.Chapters },
                    onOpenAppearance = { activeSheet = ActiveSheet.Appearance },
                    onOpenStatistics = { activeSheet = ActiveSheet.Statistics },
                )
            }
        }

        // Sheets outside the Box - true overlay that doesn't affect WebView size
        when (val state = loadState) {
            is ReaderLoadState.Ready -> {
                val viewModel = state.viewModel
                // Pause tracking while any sheet is open, resume when dismissed
                LaunchedEffect(activeSheet) {
                    viewModel.setTrackingLocked(activeSheet != null)
                }
                activeSheet?.let { sheet ->
                    ReaderThemedArea(viewModel.getReaderSettings(context)) {
                        when (sheet) {
                            ActiveSheet.Appearance -> AppearanceSheet(viewModel, additionalSettings) { activeSheet = null }
                            ActiveSheet.Chapters -> ChapterListSheet(viewModel) { activeSheet = null }
                            ActiveSheet.Statistics -> StatisticsSheet(viewModel) { activeSheet = null }
                        }
                    }
                }
                viewModel.chapterTransition?.let { transition ->
                    ReaderThemedArea(viewModel.getReaderSettings(context)) {
                        NovelChapterTransitionOverlay(
                            transition = transition,
                            fromTitle = viewModel.getChapterTitle(transition.fromIndex),
                            toTitle = transition.toIndex?.let { viewModel.getChapterTitle(it) },
                            fromDownloaded = viewModel.isChapterContentCached(transition.fromIndex),
                            toDownloaded = transition.toIndex?.let { viewModel.isChapterContentCached(it) } ?: false,
                            continuousMode = viewModel.continuousMode,
                            verticalWriting = viewModel.verticalWriting,
                            onContinue = viewModel::confirmTransition,
                            onRetry = viewModel::retryTransitionLoad,
                            onDismiss = viewModel::dismissTransition,
                        )
                    }
                }
            }
            else -> {}
        }

        // Full-screen zoomable image viewer for novel illustrations
        tappedImageUri?.let { uri ->
            NovelImageViewer(
                imageUri = uri,
                readerBackgroundColor = currentSettings.backgroundColor,
                ocrLanguage = OcrLanguage.entries.firstOrNull {
                    it.bcp47.equals(book.lang, ignoreCase = true) ||
                        book.lang?.startsWith("${it.bcp47}-", ignoreCase = true) == true
                } ?: OcrLanguage.JAPANESE,
                recognizeImage = recognizeImage,
                onOcrLookupRequested = onImageOcrLookupRequested,
                isPopupActive = isPopupActive,
                onDismissPopupRequested = onDismissPopupRequested,
                onDismiss = { tappedImageUri = null },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(
    title: String,
    onBack: () -> Unit,
    onToggleHud: () -> Unit,
    backgroundColor: Int,
    contentColor: Int,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null
        ) { onToggleHud() },
        title = {
            Text(
                title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
                color = Color(contentColor)
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color(contentColor)
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(backgroundColor).copy(alpha = 0.9f)
        )
    )
}

@Composable
private fun ReaderBottomBar(
    focusMode: Boolean,
    progressText: String,
    backgroundColor: Int,
    contentColor: Int,
    onToggleHud: () -> Unit,
    onToggleFocusMode: () -> Unit,
    onOpenChapters: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenStatistics: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(backgroundColor).copy(alpha = 0.9f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onToggleHud() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = progressText,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(contentColor).copy(alpha = 0.7f)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onOpenChapters) {
                Icon(
                    Icons.AutoMirrored.Filled.List,
                    contentDescription = "Chapters",
                    tint = Color(contentColor)
                )
            }
            IconButton(onClick = onOpenAppearance) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Appearance",
                    tint = Color(contentColor)
                )
            }
            IconButton(onClick = onOpenStatistics) {
                Icon(
                    Icons.Outlined.QueryStats,
                    contentDescription = "Statistics",
                    tint = Color(contentColor)
                )
            }
        }
    }
}

@Composable
private fun ReaderMessage(
    text: String,
    modifier: Modifier = Modifier,
    loading: Boolean = false
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loading) CircularProgressIndicator()
        Spacer(modifier = Modifier.padding(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ReaderThemedArea(
    readerSettings: ReaderSettings,
    content: @Composable () -> Unit
) {
    val bgColor = Color(readerSettings.backgroundColor)
    val textColor = Color(readerSettings.textColor)

    // Create a comprehensive ColorScheme based on the reader's background and text colors.
    // This overrides global app theme values while inside the themed area.
    val colorScheme = MaterialTheme.colorScheme.copy(
        primary = textColor,
        onPrimary = bgColor,
        primaryContainer = textColor.copy(alpha = 0.12f),
        onPrimaryContainer = textColor,
        secondary = textColor.copy(alpha = 0.8f),
        onSecondary = bgColor,
        secondaryContainer = textColor.copy(alpha = 0.08f),
        onSecondaryContainer = textColor,
        tertiary = textColor.copy(alpha = 0.7f),
        onTertiary = bgColor,
        surface = bgColor,
        onSurface = textColor,
        surfaceVariant = bgColor.copy(alpha = 0.9f),
        onSurfaceVariant = textColor.copy(alpha = 0.7f),
        background = bgColor,
        onBackground = textColor,
        outline = textColor.copy(alpha = 0.5f),
        outlineVariant = textColor.copy(alpha = 0.2f),
        surfaceContainer = bgColor,
        surfaceContainerHigh = bgColor,
        surfaceContainerHighest = bgColor
    )

    MaterialTheme(colorScheme = colorScheme, content = content)
}
