package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.webkit.WebView
import androidx.annotation.ColorInt
import androidx.core.view.isVisible
import chimahon.DictionaryRepository
import chimahon.MediaInfo
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.databinding.ReaderErrorBinding
import eu.kanade.tachiyomi.data.panel.PanelModelDownloader
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.OcrCoordinateMapper
import eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock
import eu.kanade.tachiyomi.ui.reader.viewer.OcrPageRegion
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderProgressIndicator
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import eu.kanade.tachiyomi.widget.ViewPagerAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import mihon.domain.panel.interactor.DetectPanels
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.Panel
import tachiyomi.core.common.util.system.ReadingDirection
import tachiyomi.core.common.util.system.flattenToString
import tachiyomi.core.common.util.system.logcat
import tachiyomi.decoder.ImageDecoder
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.max

/**
 * View of the ViewPager that contains a page of a chapter.
 */
@SuppressLint("ViewConstructor")
class PagerPageHolder(
    readerThemedContext: Context,
    val viewer: PagerViewer,
    val page: ReaderPage,
    private var extraPage: ReaderPage? = null,
    // KMK -->
    @ColorInt private val seedColor: Int? = null,
    // KMK <--
) : ReaderPageImageView(readerThemedContext), ViewPagerAdapter.PositionableView {

    /**
     * Item that identifies this view. Needed by the adapter to not recreate views.
     */
    override val item
        get() = page to extraPage

    /**
     * Loading progress bar to indicate the current progress.
     */
    private var progressIndicator: ReaderProgressIndicator? = null // = ReaderProgressIndicator(readerThemedContext)

    /**
     * Error layout to show when the image fails to load.
     */
    private var errorLayout: ReaderErrorBinding? = null

    private val scope = MainScope()

    /**
     * Job for loading the page and processing changes to the page's status.
     */
    private var loadJob: Job? = null
    private var ocrLoadJob: Job? = null

    // Dimensions recorded at merge-time so the OCR mapper can compute offsets correctly.
    // Only valid when extraPage != null and the merge succeeded (not fullPage / isolatedPage).
    private var mergedPage1W: Int = 0
    private var mergedPage1H: Int = 0
    private var mergedPage2W: Int = 0
    private var mergedPage2H: Int = 0
    private var mergedCenterMargin: Int = 0
    private var mergedIsLTR: Boolean = true

    /**
     * Job for loading the page.
     */
    private var extraLoadJob: Job? = null

    private val detectPanels: DetectPanels by lazy { Injekt.get() }

    private var panels: List<Panel> = emptyList()
    private var currentPanelIndex = -1
    private var panelDetectionJob: Job? = null
    private var panelDetectionGeneration = 0
    private var pendingBackwardNavigation = false

    /**
     * Detected crop rect for the image currently displayed.
     * Calculated during `setImage` if `imageCropBorders` is enabled.
     */
    private var pageCropRect: android.graphics.RectF? = null
    private var ocrOriginalIsWide = false

    // Dimensions recorded when handleWideImage adds a center margin to a single wide image.
    private var wideImageWidth: Int = 0
    private var wideCenterPadding: Int = 0

    init {
        // KMK: Dual page loading
        loadJob = scope.launch { loadPageAndProcessStatus(1) }
        extraLoadJob = scope.launch { loadPageAndProcessStatus(2) }

        onShowOcrPopup = { lookupString, fullText, charOffset, anchorX, anchorY, anchorWidth, anchorHeight, isVertical, mediaInfo, block ->
            val sourcePage = block?.let { getPageForBlock(it) } ?: page
            viewer.onShowOcrPopup?.invoke(lookupString, fullText, charOffset, anchorX, anchorY, anchorWidth, anchorHeight, isVertical, mediaInfo, sourcePage)
        }
        onShowOcrSelectionPanel = { text, anchorX, anchorY, anchorWidth, anchorHeight ->
            viewer.onShowOcrSelectionPanel?.invoke(text, anchorX, anchorY, anchorWidth, anchorHeight)
        }
        onDismissOcrPopup = {
            viewer.onDismissOcrPopup?.invoke()
        }
    }

    /**
     * Called when this view is detached from the window. Unsubscribes any active subscription.
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadJob?.cancel()
        loadJob = null
        extraLoadJob?.cancel()
        extraLoadJob = null
        // Chimahon: Cancel OCR jobs
        ocrLoadJob?.cancel()
        ocrLoadJob = null
        clearOcr()
        panelDetectionJob?.cancel()
        panelDetectionJob = null
    }

    private fun initProgressIndicator() {
        if (progressIndicator == null) {
            progressIndicator = ReaderProgressIndicator(
                context = context,
                // KMK -->
                seedColor = seedColor,
                // KMK <--
            )
            addView(progressIndicator)
        }
    }

    /**
     * Loads the page and processes changes to the page's status.
     *
     * Returns immediately if the page has no PageLoader.
     * Otherwise, this function does not return. It will continue to process status changes until
     * the Job is cancelled.
     */
    private suspend fun loadPageAndProcessStatus(pageIndex: Int) {
        // SY -->
        val page = if (pageIndex == 1) page else extraPage
        page ?: return
        // SY <--
        val loader = page.chapter.pageLoader ?: return
        supervisorScope {
            launchIO {
                loader.loadPage(page)
            }
            page.statusFlow.collectLatest { state ->
                when (state) {
                    Page.State.Queue -> setQueued()
                    Page.State.LoadPage -> setLoading()
                    Page.State.DownloadImage -> {
                        setDownloading()
                        page.progressFlow.collectLatest { value ->
                            progressIndicator?.setProgress(value)
                        }
                    }
                    Page.State.Ready -> setImage()
                    is Page.State.Error -> setError(state.error)
                }
            }
        }
    }

    /**
     * Called when the page is queued.
     */
    private fun setQueued() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is loading.
     */
    private fun setLoading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is downloading.
     */
    private fun setDownloading() {
        initProgressIndicator()
        progressIndicator?.show()
        removeErrorLayout()
    }

    /**
     * Called when the page is ready.
     */
    private suspend fun setImage() {
        if (extraPage == null) {
            progressIndicator?.setProgress(0)
        } else {
            progressIndicator?.setProgress(95)
        }

        panels = emptyList()
        currentPanelIndex = -1
        pendingBackwardNavigation = false

        panelDetectionJob?.cancel()
        panelDetectionGeneration++

        val streamFn = page.stream ?: return
        val streamFn2 = extraPage?.stream

        try {
            val loadResult = withIOContext {
                streamFn().buffered(16).use { source ->
                    // SY -->
                    if (extraPage != null) {
                        streamFn2?.invoke()
                            ?.buffered(16)
                    } else {
                        null
                    }.use { source2 ->
                        val original = Buffer().readFrom(source)
                        ocrOriginalIsWide = ImageUtil.isWideImage(original)
                        val itemSource = if (viewer.config.dualPageSplit) {
                            process(item.first, original)
                        } else {
                            mergePages(original, source2?.let { Buffer().readFrom(it) })
                        }
                        // SY <--
                        val isAnimated = ImageUtil.isAnimatedAndSupported(itemSource)
                        val background = if (!isAnimated && viewer.config.automaticBackground) {
                            ImageUtil.chooseBackground(context, itemSource.peek())
                        } else {
                            null
                        }

                        val cropRect = if (!isAnimated && viewer.config.imageCropBorders) {
                            OcrCoordinateMapper.detectCropRect(itemSource)
                        } else null
                        pageCropRect = cropRect

                        val panelBitmap = if (!isAnimated && viewer.config.panelNavigation) {
                            decodePanelBitmap(itemSource)
                        } else {
                            null
                        }

                        LoadResult(itemSource, isAnimated, background, panelBitmap)
                    }
                }
            }
            withUIContext {
                setImage(
                    loadResult.source,
                    loadResult.isAnimated,
                    Config(
                        zoomDuration = viewer.config.doubleTapAnimDuration,
                        minimumScaleType = viewer.config.imageScaleType,
                        cropBorders = viewer.config.imageCropBorders,
                        zoomStartPosition = viewer.config.imageZoomType,
                        landscapeZoom = viewer.config.landscapeZoom,
                        // KMK -->
                        disableZoomIn = viewer.config.disableZoomIn,
                        doubleTapZoom = viewer.config.doubleTapZoom,
                        landscapeZoomScaleType = viewer.config.landscapeZoomScaleType,
                        // KMK <--
                        eInkMode = viewer.config.eInkMode,
                    ),
                )
                if (!loadResult.isAnimated) {
                    pageBackground = loadResult.background
                }
                removeErrorLayout()
                maybeStartPanelDetection(loadResult)
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e)
            withUIContext {
                setError(e)
            }
        }
    }

    private fun maybeStartPanelDetection(loadResult: LoadResult) {
        if (!viewer.config.panelNavigation || loadResult.isAnimated) {
            loadResult.panelBitmap?.bitmap?.recycle()
            logcat(LogPriority.VERBOSE) {
                "Panel nav detection skipped index=${page.index} enabled=${viewer.config.panelNavigation} animated=${loadResult.isAnimated}"
            }
            return
        }

        val downloader = Injekt.get<PanelModelDownloader>()
        if (!downloader.isDownloaded) {
            logcat(LogPriority.VERBOSE) {
                "Panel nav detection skipped index=${page.index}: model not downloaded, triggering download"
            }
            loadResult.panelBitmap?.bitmap?.recycle()
            downloader.triggerDownload()
            return
        }

        val generation = panelDetectionGeneration
        val cacheKey = panelDetectionCacheKey()
        val decoded = loadResult.panelBitmap
        if (decoded == null) {
            logcat(LogPriority.VERBOSE) { "Panel nav detection skipped index=${page.index}: no processed panel bitmap" }
            return
        }

        panelDetectionJob = scope.launchIO {
            val result = try {
                detectPanels.await(
                    cacheKey = cacheKey,
                    image = decoded.bitmap,
                    originalWidth = decoded.originalWidth,
                    originalHeight = decoded.originalHeight,
                    direction = readingDirection(),
                )
            } finally {
                decoded.bitmap.recycle()
            }

            withUIContext {
                if (generation != panelDetectionGeneration) return@withUIContext

                panels = result.panels
                currentPanelIndex = if (pendingBackwardNavigation && panels.isNotEmpty()) {
                    panels.size
                } else {
                    -1
                }
                pendingBackwardNavigation = false
                logcat(LogPriority.VERBOSE) {
                    "Panel nav detection assigned index=${page.index} panels=${panels.size} " +
                        "ordered=${panels.joinToString { it.rect.flattenToString() }}"
                }
            }
        }
    }

    private fun decodePanelBitmap(source: BufferedSource): DecodedPanelBitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        source.peek().inputStream().use {
            BitmapFactory.decodeStream(it, null, options)
        }

        val largestDimension = maxOf(options.outWidth, options.outHeight)
        if (largestDimension <= 0) {
            logcat(LogPriority.VERBOSE) { "Panel nav decodeBitmap failed: dimensions <= 0" }
            return null
        }

        val sampleSize = generateSequence(1) { it * 2 }
            .first { largestDimension / it <= 800 }

        val bitmap = source.peek().inputStream().use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
        }

        if (bitmap == null) {
            logcat(LogPriority.VERBOSE) {
                "Panel nav decodeBitmap failed: bitmap null after decode with sampleSize=$sampleSize"
            }
            return null
        }

        logcat(LogPriority.VERBOSE) {
            "Panel nav decodeBitmap success sampleSize=$sampleSize " +
                "bitmapSize=${bitmap.width}x${bitmap.height} " +
                "originalSize=${options.outWidth}x${options.outHeight} " +
                "config=${bitmap.config}"
        }

        return DecodedPanelBitmap(
            bitmap = bitmap,
            originalWidth = options.outWidth,
            originalHeight = options.outHeight,
        )
    }

    private fun readingDirection(): ReadingDirection {
        return when (viewer) {
            is R2LPagerViewer -> ReadingDirection.RTL
            is VerticalPagerViewer -> ReadingDirection.VERTICAL
            else -> ReadingDirection.LTR
        }
    }

    fun hasPanels(): Boolean = panels.isNotEmpty()

    fun hasNextPanel(): Boolean = hasPanels() && currentPanelIndex < panels.lastIndex

    fun hasPreviousPanel(): Boolean = hasPanels() && currentPanelIndex > 0

    fun zoomToNextPanel(): Boolean {
        var nextIndex = currentPanelIndex + 1
        while (nextIndex <= panels.lastIndex) {
            logcat(LogPriority.VERBOSE) {
                "Panel nav next attempt index=${page.index} currentPanelIndex=$currentPanelIndex nextIndex=$nextIndex rect=${panels[nextIndex].rect.flattenToString()}"
            }
            if (zoomToPanel(panels[nextIndex])) {
                currentPanelIndex = nextIndex
                logcat(LogPriority.VERBOSE) {
                    "Panel nav next result index=${page.index} zoomed=true currentPanelIndex=$currentPanelIndex"
                }
                return true
            }
            // Zoom was too small, skip to next
            nextIndex++
        }
        logcat(LogPriority.VERBOSE) {
            "Panel nav next unavailable index=${page.index} currentPanelIndex=$currentPanelIndex panelCount=${panels.size}"
        }
        return false
    }

    fun zoomToPreviousPanel(): Boolean {
        var previousIndex = currentPanelIndex - 1
        while (previousIndex >= 0) {
            logcat(LogPriority.VERBOSE) {
                "Panel nav previous attempt index=${page.index} currentPanelIndex=$currentPanelIndex previousIndex=$previousIndex rect=${panels[previousIndex].rect.flattenToString()}"
            }
            if (zoomToPanel(panels[previousIndex])) {
                currentPanelIndex = previousIndex
                logcat(LogPriority.VERBOSE) {
                    "Panel nav previous result index=${page.index} zoomed=true currentPanelIndex=$currentPanelIndex"
                }
                return true
            }
            // Zoom was too small, skip to previous
            previousIndex--
        }
        logcat(LogPriority.VERBOSE) {
            "Panel nav previous unavailable index=${page.index} currentPanelIndex=$currentPanelIndex panelCount=${panels.size}"
        }
        return false
    }

    protected override fun onPageReady(forward: Boolean) {
        logcat(LogPriority.VERBOSE) {
            "Panel nav onPageReady index=${page.index} forward=$forward panelCount=${panels.size} visible=${isVisibleOnScreen()}"
        }
        super.onPageReady(forward)
    }

    override fun onPageSelected(forward: Boolean) {
        super.onPageSelected(forward)
        // When navigating backward into a page, position after the last panel
        // so the first backward tap zooms to the last panel
        if (!forward) {
            if (hasPanels()) {
                currentPanelIndex = panels.size
            } else {
                pendingBackwardNavigation = true
            }
        }
        logcat(LogPriority.VERBOSE) {
            "Panel nav onPageSelected index=${page.index} forward=$forward panelCount=${panels.size} currentPanelIndex=$currentPanelIndex"
        }
    }

    private fun panelDetectionCacheKey(): String {
        val pageType = if (page is InsertPage) "insert" else "page"
        return buildString {
            append(page.chapter.chapter.id)
            append(':')
            append(page.index)
            append(':')
            append(pageType)
            append(':')
            append(page.url)
            append(':')
            append(page.imageUrl ?: "")
        }
    }

    private fun process(page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        if (viewer.config.dualPageRotateToFit) {
            return rotateDualPage(imageSource)
        }

        if (!viewer.config.dualPageSplit) {
            return imageSource
        }

        if (page is InsertPage) {
            return splitInHalf(imageSource)
        }

        val isDoublePage = ImageUtil.isWideImage(imageSource)
        if (!isDoublePage) {
            return imageSource
        }

        onPageSplit(page)

        return splitInHalf(imageSource)
    }

    private fun rotateDualPage(imageSource: BufferedSource): BufferedSource {
        val isDoublePage = ImageUtil.isWideImage(imageSource)
        return if (isDoublePage) {
            val rotation = if (viewer.config.dualPageRotateToFitInvert) -90f else 90f
            ImageUtil.rotateImage(imageSource, rotation)
        } else {
            imageSource
        }
    }

    private fun mergePages(imageSource: BufferedSource, imageSource2: BufferedSource?): BufferedSource {
        // Handle adding a center margin to wide images if requested
        if (imageSource2 == null) {
            return handleWideImage(imageSource)
        }

        if (page.fullPage) return imageSource
        if (ImageUtil.isAnimatedAndSupported(imageSource)) {
            page.fullPage = true
            splitDoublePages()
            return imageSource
        } else if (ImageUtil.isAnimatedAndSupported(imageSource2)) {
            page.isolatedPage = true
            extraPage?.fullPage = true
            splitDoublePages()
            return imageSource
        }

        val imageBitmap = decodeImage(imageSource)
        if (imageBitmap == null) {
            imageSource2.close()
            page.fullPage = true
            splitDoublePages()
            logcat(LogPriority.ERROR) { "Cannot combine pages" }
            return imageSource
        }

        scope.launch { progressIndicator?.setProgress(96) }
        if (imageBitmap.height < imageBitmap.width) {
            imageSource2.close()
            page.fullPage = true
            splitDoublePages()
            return imageSource
        }

        val imageBitmap2 = decodeImage(imageSource2)
        if (imageBitmap2 == null) {
            imageSource2.close()
            extraPage?.fullPage = true
            page.isolatedPage = true
            splitDoublePages()
            logcat(LogPriority.ERROR) { "Cannot combine pages" }
            return imageSource
        }

        scope.launch { progressIndicator?.setProgress(97) }
        if (imageBitmap2.height < imageBitmap2.width) {
            imageSource2.close()
            extraPage?.fullPage = true
            page.isolatedPage = true
            splitDoublePages()
            return imageSource
        }

        val isLTR = (viewer !is R2LPagerViewer) xor viewer.config.invertDoublePages
        val centerMargin = calculateCenterMargin(imageBitmap.height, imageBitmap2.height)

        // Store dimensions for the OCR mapper
        mergedPage1W = imageBitmap.width
        mergedPage1H = imageBitmap.height
        mergedPage2W = imageBitmap2.width
        mergedPage2H = imageBitmap2.height
        mergedCenterMargin = centerMargin
        mergedIsLTR = isLTR

        imageSource.close()
        imageSource2.close()

        return ImageUtil.mergeBitmaps(imageBitmap, imageBitmap2, isLTR, centerMargin, viewer.config.pageCanvasColor) {
            updateProgress(it)
        }
    }

    private fun handleWideImage(imageSource: BufferedSource): BufferedSource {
        // Read image dimensions for the center-margin computation.
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(imageSource.peek().inputStream(), null, opts)
        wideImageWidth = opts.outWidth

        if (
            !ImageUtil.isAnimatedAndSupported(imageSource) &&
            wideImageWidth > opts.outHeight &&
            viewer.config.centerMarginType and PagerConfig.CenterMarginType.WIDE_PAGE_CENTER_MARGIN > 0 &&
            !viewer.config.imageCropBorders
        ) {
            wideCenterPadding = 96 / (max(1, height) / opts.outHeight.coerceAtLeast(1)).coerceAtLeast(1)
            return ImageUtil.addHorizontalCenterMargin(imageSource, height, context)
        }
        wideCenterPadding = 0
        return imageSource
    }

    private fun decodeImage(imageSource: BufferedSource): Bitmap? {
        return try {
            ImageDecoder.newInstance(imageSource.inputStream())?.decode()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Cannot decode image" }
            null
        }
    }

    private fun calculateCenterMargin(height: Int, height2: Int): Int {
        return if (viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN > 0 &&
            !viewer.config.imageCropBorders
        ) {
            96 / (this.height.coerceAtLeast(1) / max(height, height2).coerceAtLeast(1)).coerceAtLeast(1)
        } else {
            0
        }
    }

    private fun updateProgress(progress: Int) {
        scope.launch {
            if (progress == 100) {
                progressIndicator?.hide()
            } else {
                progressIndicator?.setProgress(progress)
            }
        }
    }

    private fun splitDoublePages() {
        scope.launch {
            delay(100)
            viewer.splitDoublePages(page)
            if (extraPage?.fullPage == true || page.fullPage) {
                extraPage = null
            }
        }
    }

    private fun splitInHalf(imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.LEFT
            viewer !is L2RPagerViewer && page !is InsertPage -> ImageUtil.Side.RIGHT
            else -> error("We should choose a side!")
        }

        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }

        val sideMargin = if ((viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN) >
            0 &&
            viewer.config.doublePages &&
            !viewer.config.imageCropBorders
        ) {
            48
        } else {
            0
        }

        return ImageUtil.splitInHalf(imageSource, side, sideMargin)
    }

    private fun onPageSplit(page: ReaderPage) {
        val newPage = InsertPage(page)
        viewer.onPageSplit(page, newPage)
    }

    /**
     * Called when the page has an error.
     */
    private fun setError(error: Throwable?) {
        progressIndicator?.hide()
        showErrorLayout(error)
    }

    override fun onImageLoaded() {
        super.onImageLoaded()
        progressIndicator?.hide()
        ocrOutlineVisible = viewer.activity.viewModel.isOcrOutlineVisible()
        ocrBoxScaleX = viewer.activity.viewModel.getOcrBoxScaleX()
        ocrBoxScaleY = viewer.activity.viewModel.getOcrBoxScaleY()
        ocrBoxOpacity = viewer.activity.viewModel.getOcrBoxOpacity()
        activeOcrTextOpacity = viewer.activity.viewModel.getActiveOcrTextOpacity()
        activeOcrBgOpacity = viewer.activity.viewModel.getActiveOcrBgOpacity()
        ocrScanResolution = viewer.activity.viewModel.getOcrScanResolution()
        val ocrEnabled = viewer.activity.viewModel.isOcrEnabled()
        this.ocrEnabled = ocrEnabled
        if (!ocrEnabled) {
            clearOcr()
            return
        }

        ocrLoadJob?.cancel()
        ocrLoadJob = scope.launch {
            loadOcrWithTransform()
        }
    }

    fun ocrRegions(screenRegion: RectF): List<OcrPageRegion> {
        val selected = normalizedOcrRegion(screenRegion) ?: return emptyList()
        val region = OcrCoordinateMapper.regionToSource(selected, sourceBounds = pageCropRect ?: RectF(0f, 0f, 1f, 1f))
            ?: return emptyList()
        val ep = extraPage
        if (ep != null && mergedPage1W > 0 && !page.fullPage && !page.isolatedPage) {
            val totalWidth = (mergedPage1W + mergedCenterMargin + mergedPage2W).toFloat()
            val totalHeight = maxOf(mergedPage1H, mergedPage2H).toFloat()
            val page1X = if (mergedIsLTR) 0f else (mergedPage2W + mergedCenterMargin).toFloat()
            val page2X = if (mergedIsLTR) (mergedPage1W + mergedCenterMargin).toFloat() else 0f
            fun part(target: ReaderPage, x: Float, width: Int, height: Int): OcrPageRegion? {
                val top = (totalHeight - height) / 2f
                val canvas = RectF(x / totalWidth, top / totalHeight, (x + width) / totalWidth, (top + height) / totalHeight)
                return OcrCoordinateMapper.regionToSource(region, canvas)?.let { OcrPageRegion(target, it) }
            }
            return listOfNotNull(part(page, page1X, mergedPage1W, mergedPage1H), part(ep, page2X, mergedPage2W, mergedPage2H))
        }
        var regions = listOf(region)
        if (wideCenterPadding > 0 && wideImageWidth > 0) {
            val ratio = wideImageWidth.toFloat() / (wideImageWidth + wideCenterPadding)
            val gap = 1f - ratio
            regions = listOfNotNull(
                OcrCoordinateMapper.regionToSource(region, RectF(0f, 0f, ratio / 2f, 1f), RectF(0f, 0f, .5f, 1f)),
                OcrCoordinateMapper.regionToSource(region, RectF(ratio / 2f + gap, 0f, 1f, 1f), RectF(.5f, 0f, 1f, 1f)),
            )
        }
        if (viewer.config.dualPageRotateToFit && ocrOriginalIsWide) {
            regions = regions.map { OcrCoordinateMapper.regionBeforeRotation(it, !viewer.config.dualPageRotateToFitInvert) }
        } else if (viewer.config.dualPageSplit && !viewer.config.dualPageRotateToFit && (page is InsertPage || ocrOriginalIsWide)) {
            val keepLeft = ((viewer is L2RPagerViewer) xor (page is InsertPage)) xor viewer.config.dualPageInvert
            val sourceHalf = if (keepLeft) RectF(0f, 0f, .5f, 1f) else RectF(.5f, 0f, 1f, 1f)
            regions = regions.mapNotNull { OcrCoordinateMapper.regionToSource(it, sourceBounds = sourceHalf) }
        }
        val originalPage = (page as? InsertPage)?.parent ?: page
        return regions.map { OcrPageRegion(originalPage, it) }
    }

    fun applyOcrEnabled(enabled: Boolean) {
        ocrOutlineVisible = viewer.activity.viewModel.isOcrOutlineVisible()
        ocrBoxScaleX = viewer.activity.viewModel.getOcrBoxScaleX()
        ocrBoxScaleY = viewer.activity.viewModel.getOcrBoxScaleY()
        ocrBoxOpacity = viewer.activity.viewModel.getOcrBoxOpacity()
        activeOcrTextOpacity = viewer.activity.viewModel.getActiveOcrTextOpacity()
        activeOcrBgOpacity = viewer.activity.viewModel.getActiveOcrBgOpacity()
        ocrScanResolution = viewer.activity.viewModel.getOcrScanResolution()
        ocrEnabled = enabled
        if (!enabled) {
            ocrLoadJob?.cancel()
            ocrLoadJob = null
            clearOcr()
            return
        }

        ocrLoadJob?.cancel()
        ocrLoadJob = scope.launch {
            loadOcrWithTransform()
        }
    }

    fun applyOcrOutlineVisible(visible: Boolean) {
        ocrOutlineVisible = visible
    }

    fun applyOcrBoxScale(scaleX: Float, scaleY: Float) {
        ocrBoxScaleX = scaleX
        ocrBoxScaleY = scaleY
    }

    fun applyOcrBoxOpacity(opacity: Float) {
        ocrBoxOpacity = opacity
    }

    fun applyActiveOcrTextOpacity(opacity: Float) {
        activeOcrTextOpacity = opacity
    }

    fun applyActiveOcrBgOpacity(opacity: Float) {
        activeOcrBgOpacity = opacity
    }

    /**
     * Fetch OCR blocks for the current page(s) and remap coordinates to match the
     * bitmap that is actually displayed (crop / split / merge).
     *
     * Five modes are handled:
     *   1. **Merge** (extraPage != null, merge succeeded): fetch OCR for both pages,
     *      project each page's blocks onto the shared merged canvas.
     *   2. **Rotate** (dualPageRotateToFit, image is wide): transpose xy-coordinates
     *      for a 90-degree rotation (CW or CCW).
     *   3. **Split** (dualPageSplit, page is InsertPage or wide): keep only the half
     *      that is visible and rescale x-coordinates to [0,1].
     *   4. **Center margin** (handleWideImage added a centre gap): shift right-half
     *      x-coordinates to account for the padding.
     *   5. **Crop** (imageCropBorders): read the image stream, detect crop margins with
     *      the same algorithm as ImageDecoder's borders.cpp, and shift OCR blocks.
     *
     * Modes 2+5 and 3+5 can combine (crop applied after rotate/split).
     */
    private suspend fun loadOcrWithTransform() {
        try {
            val viewModel = viewer.activity.viewModel
            val cropBordersEnabled = viewer.config.imageCropBorders
            val dualSplitEnabled = viewer.config.dualPageSplit

            // ── Case 1: Merge mode (two pages combined into one wide bitmap) ────────────
            val ep = extraPage
            if (ep != null && mergedPage1W > 0 && !page.fullPage && !page.isolatedPage) {
                logcat { "OCR merge-mode: page=${page.index} extra=${ep.index}" }
                val rawBlocks1 = viewModel.getOcrBlocks(page)
                val rawBlocks2 = viewModel.getOcrBlocks(ep)
                var merged = OcrCoordinateMapper.mapToMerged(
                    blocks1 = rawBlocks1,
                    page1W = mergedPage1W,
                    page1H = mergedPage1H,
                    blocks2 = rawBlocks2,
                    page2W = mergedPage2W,
                    page2H = mergedPage2H,
                    isLTR = mergedIsLTR,
                    centerMarginPx = mergedCenterMargin,
                )

                // Apply crop remap if needed
                val cropRect = pageCropRect
                if (cropBordersEnabled && merged.isNotEmpty() && cropRect != null) {
                    merged = OcrCoordinateMapper.remapToCrop(merged, cropRect)
                    logcat { "OCR merge crop-remap done: ${merged.size} blocks" }
                }

                setOcrBlocks(merged)
                return
            }

            // ── Case 2 (+ 3, 4, 5): Split / Rotate / Center-margin / single page ────────
            logcat { "OCR request start: chapter=${page.chapter.chapter.id} page=${page.index}" }
            var blocks = viewModel.getOcrBlocks(page)

            // ── Case 2: Rotation remap (dualPageRotateToFit) ─────────────────────────────
            if (viewer.config.dualPageRotateToFit) {
                val streamFn = page.stream
                if (streamFn != null) {
                    val isWide = withIOContext {
                        streamFn().use { ImageUtil.isWideImage(okio.Buffer().readFrom(it)) }
                    }
                    if (isWide) {
                        val clockwise = !viewer.config.dualPageRotateToFitInvert
                        blocks = OcrCoordinateMapper.mapToRotated(blocks, clockwise)
                        logcat { "OCR rotate remap: ${blocks.size} blocks after remap" }
                    }
                }
            }

            // ── Case 3: Split remap (dualPageSplit) ─────────────────────────────────────
            if (dualSplitEnabled && !viewer.config.dualPageRotateToFit) {
                // Only apply when the image was actually split (InsertPage or wide)
                val isSplitPage = page is InsertPage || run {
                    val streamFn = page.stream
                    streamFn != null && withIOContext {
                        streamFn().use { ImageUtil.isWideImage(okio.Buffer().readFrom(it)) }
                    }
                }
                if (isSplitPage) {
                    val keepLeft = when {
                        viewer is L2RPagerViewer && page is InsertPage -> false  // InsertPage = right half in L2R
                        viewer !is L2RPagerViewer && page is InsertPage -> true  // InsertPage = left half in R2L
                        viewer is L2RPagerViewer && page !is InsertPage -> true  // Original = left half in L2R
                        else -> false
                    }.let { side -> if (viewer.config.dualPageInvert) !side else side }

                    blocks = OcrCoordinateMapper.mapToSplit(blocks, keepLeft)
                    logcat { "OCR split (keepLeft=$keepLeft): ${blocks.size} blocks after remap" }
                }
            }

            // ── Case 4: Center margin remap (single wide image) ─────────────────────────
            if (wideCenterPadding > 0 && wideImageWidth > 0) {
                blocks = OcrCoordinateMapper.mapWithCenterMargin(blocks, wideImageWidth, wideCenterPadding)
                logcat { "OCR center margin remap: ${blocks.size} blocks" }
            }

            // ── Case 5: Crop borders ────────────────────────────────────────────────────
            val cropRect = pageCropRect
            if (cropBordersEnabled && blocks.isNotEmpty() && cropRect != null) {
                blocks = OcrCoordinateMapper.remapToCrop(blocks, cropRect)
                logcat { "OCR crop-remap done: ${blocks.size} blocks" }
            }

            setOcrBlocks(blocks)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "OCR loadOcrWithTransform failed" }
        }
    }

    /**
     * Called when an image fails to decode.
     */
    override fun onImageLoadError(error: Throwable?) {
        super.onImageLoadError(error)
        setError(error)
    }

    /**
     * Called when an image is zoomed in/out.
     */
    override fun onScaleChanged(newScale: Float) {
        super.onScaleChanged(newScale)
        viewer.activity.hideMenu()
    }

    private fun showErrorLayout(error: Throwable?): ReaderErrorBinding {
        if (errorLayout == null) {
            errorLayout = ReaderErrorBinding.inflate(LayoutInflater.from(context), this, true)
            errorLayout?.actionRetry?.viewer = viewer
            errorLayout?.actionRetry?.setOnClickListener {
                page.chapter.pageLoader?.retryPage(page)
            }
        }

        val imageUrl = page.imageUrl
        errorLayout?.actionOpenInWebView?.isVisible = imageUrl != null
        if (imageUrl != null) {
            if (imageUrl.startsWith("http", true)) {
                errorLayout?.actionOpenInWebView?.viewer = viewer
                errorLayout?.actionOpenInWebView?.setOnClickListener {
                    val sourceId = viewer.activity.viewModel.manga?.source

                    val intent = WebViewActivity.newIntent(context, imageUrl, sourceId)
                    context.startActivity(intent)
                }
            }
        }

        errorLayout?.errorMessage?.text = with(context) { error?.formattedMessage }
            ?: context.stringResource(MR.strings.decode_image_error)

        errorLayout?.root?.isVisible = true
        return errorLayout!!
    }

    /**
     * Removes the decode error layout from the holder, if found.
     */
    private fun removeErrorLayout() {
        errorLayout?.root?.isVisible = false
        errorLayout = null
    }

    private fun getPageForBlock(block: OcrTextBlock): ReaderPage {
        val ep = extraPage ?: return page
        if (mergedPage1W <= 0) return page

        val totalW = (mergedPage1W + mergedCenterMargin + mergedPage2W).toFloat()
        val splitX = (mergedPage1W + mergedCenterMargin / 2f) / totalW

        val isLeft = block.xmin < splitX
        return if (mergedIsLTR) {
            if (isLeft) page else ep
        } else {
            if (isLeft) ep else page
        }
    }
}

private data class LoadResult(
    val source: BufferedSource,
    val isAnimated: Boolean,
    val background: Drawable?,
    val panelBitmap: DecodedPanelBitmap?,
)

private data class DecodedPanelBitmap(
    val bitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int,
)
