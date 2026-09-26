package eu.kanade.tachiyomi.ui.dictionary

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import chimahon.DictionaryRepository
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.ocr.recognizePage
import eu.kanade.tachiyomi.ui.reader.viewer.OcrLookupPopup
import eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock
import eu.kanade.tachiyomi.ui.reader.viewer.extractOcrLookupString
import eu.kanade.tachiyomi.ui.reader.viewer.isLookupStartChar
import eu.kanade.tachiyomi.ui.reader.viewer.orderedFullText
import eu.kanade.tachiyomi.ui.reader.viewer.toOrderedOffset
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

private const val TAP_HINT_DURATION_MS = 1_200L

/**
 * Captures a still with the system camera app, OCRs it once using the global OCR engine and
 * the active dictionary profile's language, then overlays the recognized text boxes on the
 * photo. Tapping a box opens the regular [OcrLookupPopup] anchored to it.
 */
class CameraOcrScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val density = LocalDensity.current

        val titleText = stringResource(KMR.strings.camera_ocr_title)
        val captureText = stringResource(KMR.strings.camera_ocr_capture)
        val retakeText = stringResource(KMR.strings.camera_ocr_retake)
        val unavailableText = stringResource(KMR.strings.camera_ocr_unavailable)
        val permissionDeniedText = stringResource(KMR.strings.camera_ocr_permission_denied)
        val captureFailedText = stringResource(KMR.strings.camera_ocr_capture_failed)
        val findingText = stringResource(MR.strings.screen_lookup_finding_text)
        val noTextText = stringResource(MR.strings.screen_lookup_no_text)
        val tapText = stringResource(MR.strings.screen_lookup_tap_text)

        val dictionaryPreferences = remember { Injekt.get<DictionaryPreferences>() }
        val repository = remember { Injekt.get<DictionaryRepository>() }

        val rawProfiles by dictionaryPreferences.rawProfiles().collectAsState()
        val rawActiveProfileId by dictionaryPreferences.rawActiveProfileId().collectAsState()
        val profileStore = dictionaryPreferences.profileStore
        val activeProfile = remember(rawProfiles, rawActiveProfileId) { profileStore.getActiveProfile() }

        val webView: WebView = remember(activeProfile.languageCode) {
            DictionaryPopupWebViewWarmup.acquire(context, activeProfile.languageCode)
        }
        DisposableEffect(webView) {
            onDispose { DictionaryPopupWebViewWarmup.recycle(context, webView) }
        }

        LaunchedEffect(activeProfile) {
            withContext(Dispatchers.IO) {
                repository.warmUp(getDictionaryPaths(context, activeProfile), activeProfile.id)
            }
        }

        // Only the path is saved; the bitmap is re-decoded after a configuration change.
        var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var isLoading by remember { mutableStateOf(false) }
        var blocks by remember { mutableStateOf<List<OcrTextBlock>>(emptyList()) }
        var selection by remember { mutableStateOf<OcrSelection?>(null) }
        var showTapHint by remember { mutableStateOf(false) }
        var lookupNonce by remember { mutableIntStateOf(0) }
        var matchedCharCount by remember { mutableIntStateOf(0) }
        var matchOffset by remember { mutableIntStateOf(0) }

        val captureFile = remember(context) { createCaptureFile(context) }

        var launchError by remember { mutableStateOf<String?>(null) }
        val captureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            launchError = null
            capturePath = if (saved) captureFile.absolutePath else null
        }

        fun startCapture() {
            captureFile.delete()
            val uri = runCatching { captureUri(context, captureFile) }.getOrNull()
            if (uri == null) {
                launchError = captureFailedText
                return
            }
            try {
                captureLauncher.launch(uri)
            } catch (e: android.content.ActivityNotFoundException) {
                launchError = unavailableText
            } catch (e: SecurityException) {
                // The system blocks IMAGE_CAPTURE when we don't hold CAMERA.
                launchError = permissionDeniedText
            }
        }

        // The app declares CAMERA (it also arrives via the ZXing manifest), so the system
        // refuses to launch the system camera until we actually hold it.
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCapture()
            } else {
                launchError = permissionDeniedText
            }
        }

        fun launchCapture() {
            selection = null
            blocks = emptyList()
            showTapHint = false
            matchedCharCount = 0
            matchOffset = 0
            errorMessage = null
            launchError = null
            if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCapture()
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        fun navigateOut() {
            selection = null
            captureFile.delete()
            capturePath = null
            navigator.pop()
        }

        BackHandler(enabled = selection != null) { selection = null }
        BackHandler(enabled = selection == null) { navigateOut() }

        DisposableEffect(Unit) {
            onDispose {
                bitmap?.takeUnless { it.isRecycled }?.recycle()
            }
        }

        LaunchedEffect(capturePath, activeProfile.languageCode) {
            val path = capturePath
            if (path == null) {
                bitmap?.takeUnless { it.isRecycled }?.recycle()
                bitmap = null
                blocks = emptyList()
                isLoading = false
                return@LaunchedEffect
            }

            isLoading = true
            errorMessage = null
            val decoded = withContext(Dispatchers.IO) { decodeCapture(File(path)) }
            if (decoded == null) {
                bitmap?.takeUnless { it.isRecycled }?.recycle()
                bitmap = null
                blocks = emptyList()
                isLoading = false
                errorMessage = captureFailedText
                return@LaunchedEffect
            }

            bitmap = decoded

            val language = resolveOcrLanguage(activeProfile.languageCode)
            runCatching {
                withContext(Dispatchers.Default) {
                    recognizePage(decoded, language).toScreenLookupBlocks(language.bcp47)
                }
            }.onSuccess { recognized ->
                blocks = recognized
                if (recognized.isEmpty()) {
                    errorMessage = noTextText
                } else {
                    showTapHint = true
                }
            }.onFailure {
                blocks = emptyList()
                errorMessage = it.message ?: captureFailedText
            }
            isLoading = false

            if (showTapHint) {
                delay(TAP_HINT_DURATION_MS)
                showTapHint = false
            }
        }

        val boxScaleX = dictionaryPreferences.ocrBoxScaleX().get()
        val boxScaleY = dictionaryPreferences.ocrBoxScaleY().get()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = titleText,
                    navigateUp = ::navigateOut,
                    actions = {
                        if (bitmap != null) {
                            AppBarActions(
                                actions = persistentListOf(
                                    AppBar.Action(
                                        title = retakeText,
                                        icon = Icons.Outlined.PhotoCamera,
                                        onClick = ::launchCapture,
                                    ),
                                ),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                val canvasWidth = with(density) { maxWidth.toPx() }
                val canvasHeight = with(density) { maxHeight.toPx() }
                val currentBitmap = bitmap

                if (currentBitmap == null) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        FilledTonalButton(onClick = ::launchCapture) {
                            Icon(
                                imageVector = Icons.Outlined.PhotoCamera,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(captureText)
                        }
                        val message = launchError ?: errorMessage
                        if (message != null) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            )
                        }
                    }
                } else {
                    val fittedRect = fitImageRect(
                        imgWidth = currentBitmap.width,
                        imgHeight = currentBitmap.height,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                    )
                    val canvasBlocks = remapBlocksToCanvas(blocks, fittedRect, canvasWidth, canvasHeight)

                    Image(
                        bitmap = currentBitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )

                    OcrBlockCanvas(
                        blocks = canvasBlocks,
                        boxScaleX = boxScaleX,
                        boxScaleY = boxScaleY,
                        activeBlock = selection?.block,
                        activeMatchCount = matchedCharCount,
                        activeMatchOffset = matchOffset,
                        selection = selection,
                        onBlockTapped = { tapped, tapX, tapY ->
                            val charOffset = tapped.screenLookupCharOffset(tapX, tapY)
                            val orderedCharOffset = tapped.toOrderedOffset(charOffset)
                            val text = tapped.orderedFullText
                            if (selection?.block == tapped && selection?.sentenceOffset == orderedCharOffset) {
                                selection = null
                                showTapHint = false
                                matchedCharCount = 0
                                matchOffset = 0
                            } else if (orderedCharOffset in text.indices && isLookupStartChar(text[orderedCharOffset])) {
                                val lookupString = extractOcrLookupString(text, orderedCharOffset)
                                if (lookupString.isNotBlank()) {
                                    lookupNonce++
                                    showTapHint = false
                                    matchedCharCount = 0
                                    matchOffset = 0
                                    selection = OcrSelection(
                                        block = tapped,
                                        lookupString = lookupString,
                                        sentence = text,
                                        sentenceOffset = orderedCharOffset,
                                        anchorX = tapped.xmin * canvasWidth,
                                        anchorY = tapped.ymin * canvasHeight,
                                        anchorWidth = (tapped.xmax - tapped.xmin) * canvasWidth,
                                        anchorHeight = (tapped.ymax - tapped.ymin) * canvasHeight,
                                    )
                                } else {
                                    selection = null
                                    showTapHint = false
                                }
                            } else {
                                selection = null
                                showTapHint = false
                            }
                        },
                        onEmptyTap = { selection = null },
                    )

                    OcrStatusOverlay(
                        isLoading = isLoading,
                        error = errorMessage.takeIf { isLoading.not() },
                        loadingText = findingText,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    OcrTapHint(
                        visible = showTapHint && canvasBlocks.isNotEmpty() && selection == null,
                        hintText = tapText,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp),
                    )

                    val selected = selection
                    if (selected != null) {
                        key(selected.lookupString, lookupNonce) {
                            OcrLookupPopup(
                                visible = true,
                                lookupString = selected.lookupString,
                                fullText = selected.sentence,
                                charOffset = selected.sentenceOffset,
                                onDismiss = { selection = null },
                                webView = webView,
                                repository = repository,
                                anchorX = selected.anchorX,
                                anchorY = selected.anchorY,
                                anchorWidth = selected.anchorWidth,
                                anchorHeight = selected.anchorHeight,
                                isVertical = selected.block.vertical,
                                activeProfile = activeProfile,
                                screenshot = currentBitmap,
                                onRequestScreenshot = { currentBitmap },
                                onTermMatched = { count, off ->
                                    matchedCharCount = count
                                    matchOffset = off
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
