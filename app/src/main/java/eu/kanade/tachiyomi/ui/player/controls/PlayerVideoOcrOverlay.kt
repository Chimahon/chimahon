package eu.kanade.tachiyomi.ui.player.controls

import android.graphics.Bitmap
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import chimahon.DictionaryRepository
import chimahon.MediaInfo
import chimahon.ocr.OcrLanguage
import eu.kanade.tachiyomi.data.ocr.recognizePage
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPopupWebViewWarmup
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import eu.kanade.tachiyomi.ui.dictionary.OcrBlockCanvas
import eu.kanade.tachiyomi.ui.dictionary.OcrSelection
import eu.kanade.tachiyomi.ui.dictionary.OcrStatusOverlay
import eu.kanade.tachiyomi.ui.dictionary.OcrTapHint
import eu.kanade.tachiyomi.ui.dictionary.getDictionaryPaths
import eu.kanade.tachiyomi.ui.dictionary.screenLookupCharOffset
import eu.kanade.tachiyomi.ui.dictionary.toScreenLookupBlocks
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.reader.viewer.OcrLineGeometry
import eu.kanade.tachiyomi.ui.reader.viewer.OcrLookupPopup
import eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock
import eu.kanade.tachiyomi.ui.reader.viewer.extractOcrLookupString
import eu.kanade.tachiyomi.ui.reader.viewer.isLookupStartChar
import eu.kanade.tachiyomi.ui.reader.viewer.orderedFullText
import eu.kanade.tachiyomi.ui.reader.viewer.toOrderedOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tachiyomi.core.common.i18n.stringResource as contextStringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.min

private const val TAP_HINT_DURATION_MS = 1_200L

@Composable
internal fun PlayerVideoOcrOverlay(
    viewModel: PlayerViewModel,
    screenshot: Bitmap?,
    onDismiss: () -> Unit,
) {
    if (screenshot == null) return

    val context = LocalContext.current
    val localDensity = LocalDensity.current
    val dictionaryPreferences = remember { Injekt.get<DictionaryPreferences>() }
    val repository = remember { Injekt.get<DictionaryRepository>() }
    val source by viewModel.currentSource.collectAsState()
    val anime by viewModel.currentAnime.collectAsState()
    val episode by viewModel.currentEpisode.collectAsState()
    val activeProfile = remember(source?.id, anime?.id, source?.lang) {
        dictionaryPreferences.profileResolver.resolve(
            animeId = anime?.id ?: 0L,
            sourceId = source?.id ?: 0L,
            sourceLang = source?.lang.orEmpty(),
        )
    }
    val webView: WebView = remember(activeProfile.languageCode) {
        DictionaryPopupWebViewWarmup.acquire(context, activeProfile.languageCode)
    }
    val boxScaleX = dictionaryPreferences.ocrBoxScaleX().get()
    val boxScaleY = dictionaryPreferences.ocrBoxScaleY().get()

    LaunchedEffect(activeProfile) {
        withContext(Dispatchers.IO) {
            repository.warmUp(getDictionaryPaths(context, activeProfile), activeProfile.id)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            DictionaryPopupWebViewWarmup.recycle(context, webView)
        }
    }

    BackHandler(onBack = onDismiss)

    var matchedCharCount by remember { mutableIntStateOf(0) }
    var matchOffset by remember { mutableIntStateOf(0) }
    var blocks by remember { mutableStateOf<List<OcrTextBlock>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<OcrSelection?>(null) }
    var showTapHint by remember { mutableStateOf(false) }
    var lookupNonce by remember { mutableIntStateOf(0) }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val widthPx = with(localDensity) { maxWidth.toPx() }
        val heightPx = with(localDensity) { maxHeight.toPx() }

        LaunchedEffect(screenshot, activeProfile.languageCode) {
            isLoading = true
            error = null
            blocks = emptyList()
            showTapHint = false
            val language = OcrLanguage.entries.find {
                it.bcp47.equals(activeProfile.languageCode, ignoreCase = true)
            } ?: OcrLanguage.JAPANESE

            runCatching {
                withContext(Dispatchers.Default) {
                    recognizePage(screenshot, language)
                        .toScreenLookupBlocks(language.bcp47)
                }
            }.onSuccess { ocrBlocks ->
                blocks = remapToScreenArea(
                    ocrBlocks,
                    imgWidth = screenshot.width,
                    imgHeight = screenshot.height,
                    screenWidth = widthPx,
                    screenHeight = heightPx,
                )
                if (blocks.isEmpty()) {
                    error = context.contextStringResource(MR.strings.screen_lookup_no_text)
                } else {
                    showTapHint = true
                }
            }.onFailure {
                error = it.message ?: context.contextStringResource(MR.strings.screen_lookup_capture_failed)
            }
            isLoading = false

            if (showTapHint) {
                delay(TAP_HINT_DURATION_MS)
                showTapHint = false
            }
        }

        OcrBlockCanvas(
            blocks = blocks,
            boxScaleX = boxScaleX,
            boxScaleY = boxScaleY,
            activeBlock = selection?.block,
            activeMatchCount = matchedCharCount,
            activeMatchOffset = matchOffset,
            selection = selection,
            onBlockTapped = { tapped, tapX, tapY ->
                val charOffset = tapped.screenLookupCharOffset(tapX, tapY, widthPx, heightPx)
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
                            anchorX = tapped.xmin * widthPx,
                            anchorY = tapped.ymin * heightPx,
                            anchorWidth = (tapped.xmax - tapped.xmin) * widthPx,
                            anchorHeight = (tapped.ymax - tapped.ymin) * heightPx,
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
            onEmptyTap = { onDismiss() },
        )

        OcrStatusOverlay(
            isLoading = isLoading,
            error = error,
            loadingText = stringResource(MR.strings.screen_lookup_finding_text),
            modifier = Modifier.align(Alignment.Center),
        )

        OcrTapHint(
            visible = showTapHint && blocks.isNotEmpty() && selection == null,
            hintText = stringResource(MR.strings.screen_lookup_tap_text),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 84.dp),
        )

        val selected = selection
        if (selected != null) {
            val mediaRequest = remember(selected, lookupNonce) {
                viewModel.createVideoOcrAudioMediaRequest()
            }
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
                    type = "anime",
                    mediaInfo = MediaInfo(
                        mangaTitle = anime?.title.orEmpty(),
                        chapterName = episode?.name.orEmpty(),
                    ),
                    onRequestAnimatedScene = { viewModel.captureVideoOcrAnimatedForAnki() },
                    mediaRequest = mediaRequest,
                    onAnkiMediaWarnings = context::showPlayerAnkiMediaWarnings,
                    usePopup = false,
                    titleId = anime?.id?.toString(),
                    onTermMatched = { count, off ->
                        matchedCharCount = count
                        matchOffset = off
                    },
                )
            }
        }
    }
}

private fun remapToScreenArea(
    blocks: List<OcrTextBlock>,
    imgWidth: Int,
    imgHeight: Int,
    screenWidth: Float,
    screenHeight: Float,
): List<OcrTextBlock> {
    if (imgWidth <= 0 || imgHeight <= 0 || screenWidth <= 0f || screenHeight <= 0f) return blocks
    val scale = min(screenWidth / imgWidth, screenHeight / imgHeight)
    val videoW = imgWidth * scale
    val videoH = imgHeight * scale
    val offX = (screenWidth - videoW) / 2f
    val offY = (screenHeight - videoH) / 2f
    return blocks.mapNotNull { block ->
        val newXmin = (offX + block.xmin * videoW) / screenWidth
        val newYmin = (offY + block.ymin * videoH) / screenHeight
        val newXmax = (offX + block.xmax * videoW) / screenWidth
        val newYmax = (offY + block.ymax * videoH) / screenHeight
        if (newXmax <= newXmin || newYmax <= newYmin) return@mapNotNull null
        val newGeometries = block.lineGeometries?.map { geo ->
            OcrLineGeometry(
                xmin = (offX + geo.xmin * videoW) / screenWidth,
                ymin = (offY + geo.ymin * videoH) / screenHeight,
                xmax = (offX + geo.xmax * videoW) / screenWidth,
                ymax = (offY + geo.ymax * videoH) / screenHeight,
                rotation = geo.rotation,
            )
        }
        block.copy(
            xmin = newXmin,
            ymin = newYmin,
            xmax = newXmax,
            ymax = newYmax,
            lineGeometries = newGeometries,
        )
    }
}
