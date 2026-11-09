package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.Locale
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import chimahon.ocr.OcrTextOverlayPainter
import chimahon.ocr.OcrHitTester
import chimahon.ocr.OcrNormalizedBounds
import eu.kanade.tachiyomi.ui.reader.viewer.pager.Pager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * OCR-aware subclass of [SubsamplingScaleImageView].
 *
 * Extends SSIV to render OCR text boxes and handle OCR-specific touch interactions.
 * All OCR state is read from [ocrHost] — this view is a thin shell with zero state of its own.
 *
 * ## Architecture
 * - onDraw: renders OCR boxes and text (reads state from ocrHost)
 * - onTouchEvent: dispatches to GestureDetector for single tap detection on blocks
 * - gestureDetector: uses safe methods (onDown, onSingleTapUp) per SSIV wiki §9
 * - All hit testing done in source pixel space (via viewToSourceCoord)
 *
 * ## Coordinate Spaces
 * - Normalized (0.0–1.0): OcrTextBlock storage format
 * - Source pixels (sWidth × sHeight): SSIV's image pixel space
 * - View/Screen pixels: current screen position after zoom/pan transformation
 */
private val JP_VERTICAL_PUNCT = setOf(
    '、', '。', '！', '？', '，', '．', '：', '；',
    '」', '』', '）', '】', '〉', '》', '］', '｝',
    '﹂', '﹄', '︶', '﹈', '︼', '︾', '﹀', '…', '‥',
)

class OcrSubsamplingImageView(
    context: Context,
) : SubsamplingScaleImageView(context) {

    // Reference to parent ReaderPageImageView for state access
    var ocrHost: ReaderPageImageView? = null

    // Gesture detector for tap handling
    private val gestureDetector = GestureDetector(context, OcrGestureListener())

    // Cache hit test result to avoid redundant calculations during a single gesture
    private var cachedHitBlock: OcrTextBlock? = null
    private var cachedHitX = Float.NaN
    private var cachedHitY = Float.NaN
    private var downOnOcrBox = false
    private var downX = 0f
    private var downY = 0f
    private var swipeReleased = false

    // Webtoon mode keeps scroll/zoom gestures in RecyclerView instead of SSIV.
    var forwardTouchToSuper: Boolean = true

    var dismissHandledInThisGesture = false

    // Text paint for rendering OCR text
    private val textPaint = TextPaint().apply {
        isAntiAlias = true
        textSize = 14f * context.resources.displayMetrics.density
        color = Color.BLACK
    }

    private val borderPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f * context.resources.displayMetrics.density
        color = Color.argb(180, 0, 170, 255)
    }

    private val backgroundPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.argb(180, 255, 255, 255)
    }

    private val highlightPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.argb(100, 130, 150, 200) // Soft blue highlight
    }

    // ==================== Rendering ====================

    override fun onDraw(canvas: Canvas) {
        // Always call super first — required by SSIV wiki
        super.onDraw(canvas)

        // Guard 1: SSIV must be ready before using sWidth/sHeight
        if (!isReady) return

        // Guard 2: no host or OCR disabled
        val host = ocrHost ?: return
        if (!host.ocrEnabled) return

        // Guard 3: no blocks to draw
        if (host.ocrBlocks.isEmpty()) return

        // Apply locale from first block's language for proper CJK glyph rendering
        val blockLang = host.ocrBlocks.firstOrNull()?.language
        if (!blockLang.isNullOrEmpty()) {
            textPaint.setTextLocale(Locale.forLanguageTag(blockLang))
        }

        // Draw passive OCR text boxes at the configured opacity. The active tapped
        // box keeps the old transparent-background feel and paints only the OCR text.
        // Seam OCR keeps full line geometry; clip at the image edge without shrinking it.
        val imageTopLeft = sourceToViewCoord(0f, 0f) ?: return
        val imageBottomRight = sourceToViewCoord(sWidth.toFloat(), sHeight.toFloat()) ?: return
        canvas.save()
        canvas.clipRect(imageTopLeft.x, imageTopLeft.y, imageBottomRight.x, imageBottomRight.y)
        for (block in host.ocrBlocks) {
            drawOcrBlock(canvas, block, host)
        }
        canvas.restore()
    }

    /**
     * Draw a single OCR block. Inactive blocks use the configured opacity; the active
     * block is rendered at full opacity with match highlighting.
     */
    private fun drawOcrBlock(canvas: Canvas, block: OcrTextBlock, host: ReaderPageImageView) {
        // Calculate center and size, then apply scale around center
        val centerX = (block.xmin + block.xmax) / 2f
        val centerY = (block.ymin + block.ymax) / 2f
        val width = block.xmax - block.xmin
        val height = block.ymax - block.ymin

        // Convert normalized coords to source pixel coords with scale applied around center
        val srcXMin = (centerX - (width * host.ocrBoxScaleX) / 2f) * sWidth
        val srcYMin = (centerY - (height * host.ocrBoxScaleY) / 2f) * sHeight
        val srcXMax = (centerX + (width * host.ocrBoxScaleX) / 2f) * sWidth
        val srcYMax = (centerY + (height * host.ocrBoxScaleY) / 2f) * sHeight

        // Convert source coords to screen coords
        val tlScreen = sourceToViewCoord(srcXMin, srcYMin) ?: return
        val brScreen = sourceToViewCoord(srcXMax, srcYMax) ?: return

        val screenW = brScreen.x - tlScreen.x
        val screenH = brScreen.y - tlScreen.y

        val hasLineGeometry = block.lineGeometries?.size == block.lines.size
        if (host.ocrOutlineVisible && !hasLineGeometry) {
            canvas.drawRect(
                tlScreen.x,
                tlScreen.y,
                brScreen.x,
                brScreen.y,
                borderPaint,
            )
        }

        if (block == host.activeOcrBlock) {
            val activeTextAlpha = (255 * host.activeOcrTextOpacity).roundToInt().coerceIn(0, 255)
            val activeBackgroundAlpha = (255 * host.activeOcrBgOpacity).roundToInt().coerceIn(0, 255)
            drawOcrTextBox(
                canvas = canvas,
                block = block,
                host = host,
                tlScreen = tlScreen,
                brScreen = brScreen,
                screenW = screenW,
                screenH = screenH,
                textAlpha = activeTextAlpha,
                backgroundAlpha = activeBackgroundAlpha,
                highlightAlpha = 100,
                highlightMatches = true,
                cacheLayout = true,
            )
            return
        }

        val textAlpha = (255 * host.ocrBoxOpacity).roundToInt().coerceIn(0, 255)
        val backgroundAlpha = (255 * host.ocrBoxOpacity).roundToInt().coerceIn(0, 255)
        if (textAlpha > 0 || backgroundAlpha > 0 || (host.ocrOutlineVisible && hasLineGeometry)) {
            drawOcrTextBox(
                canvas = canvas,
                block = block,
                host = host,
                tlScreen = tlScreen,
                brScreen = brScreen,
                screenW = screenW,
                screenH = screenH,
                textAlpha = textAlpha,
                backgroundAlpha = backgroundAlpha,
                highlightAlpha = 0,
                highlightMatches = false,
                cacheLayout = false,
            )
        }
    }

    /**
     * Draw OCR text with caller-provided background and text opacity.
     */
    private fun drawOcrTextBox(
        canvas: Canvas,
        block: OcrTextBlock,
        host: ReaderPageImageView,
        tlScreen: PointF,
        brScreen: PointF,
        screenW: Float,
        screenH: Float,
        textAlpha: Int,
        backgroundAlpha: Int,
        highlightAlpha: Int,
        highlightMatches: Boolean,
        cacheLayout: Boolean,
    ) {
        val oldTextAlpha = textPaint.alpha
        val oldBackgroundAlpha = backgroundPaint.alpha
        val oldHighlightAlpha = highlightPaint.alpha
        textPaint.alpha = textAlpha
        backgroundPaint.alpha = backgroundAlpha
        highlightPaint.alpha = highlightAlpha

        canvas.save()

        try {
            val geometries = block.lineGeometries
            if (geometries != null && geometries.size == block.lines.size) {
                // Per-line rendering logic (matches reference userscript)
                var currentOffset = 0
                for (i in block.lines.indices) {
                    val text = block.lines[i]
                    drawLineOrientedText(canvas, text, currentOffset, geometries[i], block.vertical, host, highlightMatches)
                    currentOffset += text.length
                }
            } else {
                // Fallback: block-level rendering
                // Draw semi-transparent background for the entire block only if no geometries
                canvas.drawRect(
                    tlScreen.x,
                    tlScreen.y,
                    brScreen.x,
                    brScreen.y,
                    backgroundPaint,
                )
                if (block.vertical) {
                    drawVerticalOcrText(canvas, block, tlScreen, screenW, screenH, host, highlightMatches)
                } else {
                    val layout = buildLayoutForHorizontal(block, screenW, screenH, host, useCache = cacheLayout)
                    if (cacheLayout) {
                        host.ocrLayoutCache = Pair(block, layout)
                    }
                    canvas.translate(tlScreen.x, tlScreen.y)
                    layout.draw(canvas)
                }
            }
        } finally {
            canvas.restore()
            textPaint.alpha = oldTextAlpha
            backgroundPaint.alpha = oldBackgroundAlpha
            highlightPaint.alpha = oldHighlightAlpha
        }
    }

    /**
     * Draw a single line with its own orientation and scale.
     */
    private fun drawLineOrientedText(
        canvas: Canvas,
        text: String,
        lineStartOffset: Int,
        geo: OcrLineGeometry,
        isVertical: Boolean,
        host: ReaderPageImageView,
        highlightMatches: Boolean,
    ) {
        if (text.isBlank()) return

        val rect = getLineRect(geo, host.ocrBoxScaleX, host.ocrBoxScaleY) ?: return
        val lineIsVertical = OcrHitTester.isLineVertical(isVertical, geo.xmin, geo.ymin, geo.xmax, geo.ymax)
        val matchedStart = host.activeOcrCharOffset - lineStartOffset
        val matchedEnd = matchedStart + host.activeOcrMatchedCount
        OcrTextOverlayPainter.drawLine(
            canvas = canvas,
            text = text,
            rect = rect,
            rotation = geo.rotation,
            vertical = lineIsVertical,
            density = context.resources.displayMetrics.density,
            scaleCompensation = getParentScaleCompensation(),
            textPaint = textPaint,
            backgroundPaint = backgroundPaint,
            borderPaint = borderPaint.takeIf { host.ocrOutlineVisible },
            highlightPaint = if (highlightMatches && host.activeOcrMatchedCount > 0) highlightPaint else null,
            highlightRange = if (highlightMatches && host.activeOcrMatchedCount > 0) matchedStart until matchedEnd else null,
            drawVerticalCharacter = ::drawVerticalChar,
            verticalCenter = ::verticalCenter,
        )
    }

    fun getLineRect(geo: OcrLineGeometry, scaleX: Float, scaleY: Float): RectF? {
        if (!isReady) return null
        val centerX = (geo.xmin + geo.xmax) / 2f
        val centerY = (geo.ymin + geo.ymax) / 2f
        val width = geo.xmax - geo.xmin
        val height = geo.ymax - geo.ymin

        val srcXMin = (centerX - (width * scaleX) / 2f) * sWidth
        val srcYMin = (centerY - (height * scaleY) / 2f) * sHeight
        val srcXMax = (centerX + (width * scaleX) / 2f) * sWidth
        val srcYMax = (centerY + (height * scaleY) / 2f) * sHeight

        val tl = sourceToViewCoord(srcXMin, srcYMin) ?: return null
        val br = sourceToViewCoord(srcXMax, srcYMax) ?: return null
        return RectF(tl.x, tl.y, br.x, br.y)
    }

    fun getLineSubstringRect(
        text: String,
        geo: OcrLineGeometry,
        start: Int,
        end: Int,
        scaleX: Float,
        scaleY: Float,
        isVertical: Boolean,
    ): RectF? {
        val rect = getLineRect(geo, scaleX, scaleY) ?: return null
        if (start <= 0 && end >= text.length) return rect

        val sW = rect.width()
        val sH = rect.height()
        val charW = sW / text.length.coerceAtLeast(1)
        val charH = sH / text.length.coerceAtLeast(1)

        return if (isVertical) {
            RectF(rect.left, rect.top + start * charH, rect.right, rect.top + end * charH)
        } else {
            RectF(rect.left + start * charW, rect.top, rect.left + end * charW, rect.bottom)
        }
    }

    /**
     * Get the screen-space bounding box for a range of characters in a block.
     * Reuses the same geometry logic as [onDraw] to ensure alignment.
     */
    fun getMatchedWordRect(
        block: OcrTextBlock,
        startOffset: Int,
        count: Int,
        scaleX: Float,
        scaleY: Float,
    ): RectF? {
        if (!isReady || count <= 0) return null

        val geometries = block.lineGeometries
        var currentOffset = 0
        val rects = mutableListOf<RectF>()

        if (geometries != null && geometries.size == block.lines.size) {
            for (i in block.lines.indices) {
                val text = block.lines[i]
                val lineEnd = currentOffset + text.length
                if (startOffset < lineEnd && (startOffset + count) > currentOffset) {
                    val lineRect = getLineSubstringRect(
                        text,
                        geometries[i],
                        maxOf(0, startOffset - currentOffset),
                        minOf(text.length, startOffset + count - currentOffset),
                        scaleX,
                        scaleY,
                        block.vertical,
                    )
                    if (lineRect != null) rects.add(lineRect)
                }
                currentOffset = lineEnd
            }
        }

        if (rects.isEmpty()) return null

        val result = RectF(rects[0])
        for (i in 1 until rects.size) {
            result.union(rects[i])
        }
        if (block.sharedId != null) {
            val topLeft = sourceToViewCoord(0f, 0f) ?: return null
            val bottomRight = sourceToViewCoord(sWidth.toFloat(), sHeight.toFloat()) ?: return null
            if (!result.intersect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)) return null
        }

        // Convert view-relative to screen-relative coordinates
        val location = IntArray(2)
        getLocationOnScreen(location)
        result.offset(location[0].toFloat(), location[1].toFloat())

        return result
    }

    private fun verticalCenter(rowTop: Float, i: Int, rowStep: Float, ch: Char): Float =
        if (ch in JP_VERTICAL_PUNCT) rowTop + rowStep * (i + 1f) - rowStep * 0.15f
        else rowTop + rowStep * (i + 0.5f)

    private fun drawVerticalOcrText(
        canvas: Canvas,
        block: OcrTextBlock,
        tlScreen: PointF,
        screenW: Float,
        screenH: Float,
        host: ReaderPageImageView,
        highlightMatches: Boolean,
    ) {
        val columns = block.lines.filter { it.isNotEmpty() }.ifEmpty { listOf(block.fullText) }
        if (columns.isEmpty()) return

        val columnCount = columns.size.coerceAtLeast(1)
        val maxCharsPerColumn = columns.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1

        val density = context.resources.displayMetrics.density
        val columnWidth = (screenW / columnCount).coerceAtLeast(1f)
        val rowStep = (screenH / maxCharsPerColumn).coerceAtLeast(1f)

        // Fixed uniform grid: all columns use the same vertical step for each character.
        val targetSize = minOf(columnWidth, rowStep) * 0.8f
        textPaint.textSize = targetSize.coerceAtLeast(8f * density)
        textPaint.textAlign = Paint.Align.CENTER

        val fm = textPaint.fontMetrics
        val baselineShift = -(fm.ascent + fm.descent) / 2f
        val contentTop = tlScreen.y + (screenH - rowStep * maxCharsPerColumn) / 2f

        var currentOffset = 0
        columns.forEachIndexed { columnIndex, text ->
            // Japanese vertical text convention: first column starts at right edge.
            val x = tlScreen.x + screenW - columnWidth * (columnIndex + 0.5f)
            val matchedStart = host.activeOcrCharOffset - currentOffset
            val matchedEnd = matchedStart + host.activeOcrMatchedCount

            text.forEachIndexed { charIndex, ch ->
                val yCenter = verticalCenter(contentTop, charIndex, rowStep, ch)

                if (highlightMatches && host.activeOcrMatchedCount > 0 && charIndex in matchedStart until matchedEnd) {
                    canvas.drawRect(
                        x - columnWidth / 2f,
                        contentTop + rowStep * charIndex,
                        x + columnWidth / 2f,
                        contentTop + rowStep * (charIndex + 1),
                        highlightPaint
                    )
                }

                drawVerticalChar(canvas, ch, x, yCenter, baselineShift, textPaint)
            }
            currentOffset += text.length
        }
    }

    private fun drawVerticalChar(
        canvas: Canvas,
        ch: Char,
        x: Float,
        yCenter: Float,
        baselineShift: Float,
        paint: Paint,
    ) {
        val rotateChars = setOf('ー', '-', '—', '~', '〜', '…', '=')
        if (ch in rotateChars) {
            canvas.save()
            canvas.rotate(90f, x, yCenter)
            canvas.drawText(ch.toString(), x, yCenter + baselineShift, paint)
            canvas.restore()
        } else {
            val str = when (ch) {
                '「' -> "﹁"
                '」' -> "﹂"
                '『' -> "﹃"
                '』' -> "﹄"
                '（' -> "︵"
                '）' -> "︶"
                '[' -> "﹇"
                ']' -> "﹈"
                '【' -> "︻"
                '】' -> "︼"
                '《' -> "︽"
                '》' -> "︾"
                '〈' -> "︿"
                '〉' -> "﹀"
                else -> ch.toString()
            }
            canvas.drawText(str, x, yCenter + baselineShift, paint)
        }
    }

    /**
     * Build StaticLayout for horizontal text, with shrink-to-fit.
     */
    private fun buildLayoutForHorizontal(
        block: OcrTextBlock,
        screenW: Float,
        screenH: Float,
        host: ReaderPageImageView,
        useCache: Boolean = true,
    ): StaticLayout {
        // Check cache first
        if (useCache) {
            host.ocrLayoutCache?.takeIf { it.first == block }?.second?.let { return it }
        }

        val lineCount = block.lines.size.coerceAtLeast(1)
        var textSize = screenH / lineCount / 1.2f * getParentScaleCompensation() // slight padding

        // Build layout with shrink-to-fit loop
        var layout: StaticLayout
        do {
            textPaint.textSize = textSize.coerceAtLeast(8f * context.resources.displayMetrics.density)
            layout = StaticLayout.Builder.obtain(
                block.fullText,
                0,
                block.fullText.length,
                textPaint,
                screenW.toInt().coerceAtLeast(1),
            )
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()

            if (layout.height > screenH && textSize > 8f * context.resources.displayMetrics.density) {
                textSize *= 0.85f
            } else {
                break
            }
        } while (true)

        return layout
    }

    private fun getParentScaleCompensation(): Float {
        // Webtoon can apply parent RecyclerView scaling; compensate OCR text size so rendering
        // remains comparable to paged mode at the same visual zoom.
        if (forwardTouchToSuper) return 1f

        var accumulatedScale = 1f
        var current: View? = this
        while (true) {
            val view = current ?: break
            val sx = abs(view.scaleX).coerceAtLeast(0.01f)
            val sy = abs(view.scaleY).coerceAtLeast(0.01f)
            accumulatedScale *= minOf(sx, sy)
            current = view.parent as? View
        }

        return (1f / accumulatedScale).coerceIn(1f, 2f)
    }

    // ==================== Touch Handling ====================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val host = ocrHost
        val ocrEnabled = host?.ocrEnabled == true
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downOnOcrBox = false
            swipeReleased = false
            dismissHandledInThisGesture = false
        }

        // On ACTION_DOWN, check if we hit an OCR block and cache the result
        if (event.actionMasked == MotionEvent.ACTION_DOWN && ocrEnabled) {
            cachedHitBlock = hitTestSource(event.x, event.y)
            cachedHitX = event.x
            cachedHitY = event.y
            downX = event.x
            downY = event.y
            downOnOcrBox = cachedHitBlock != null
            
            // Dismiss active block immediately when touching outside it.
            if (cachedHitBlock == null && host?.activeOcrBlock != null) {
                host.dismissActiveOcrBlock()
                dismissHandledInThisGesture = true
            }

            if (downOnOcrBox) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }

        if (event.actionMasked == MotionEvent.ACTION_MOVE && downOnOcrBox && !swipeReleased) {
            val dx = kotlin.math.abs(event.x - downX)
            val dy = kotlin.math.abs(event.y - downY)
            if (dx > touchSlop || dy > touchSlop) {
                swipeReleased = true
                if (!forwardTouchToSuper) {
                    // Webtoon mode: Release intercept lock so RecyclerView can handle scrolling
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }

        // Let gesture detector process the event first (for safe single tap methods)
        val gestureResult = if (ocrEnabled) {
            gestureDetector.onTouchEvent(event)
        } else {
            false
        }

        var superResult = false
        if (forwardTouchToSuper) {
            superResult = super.onTouchEvent(event)
        }

        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
            // Clear hit test cache
            cachedHitBlock = null
            cachedHitX = Float.NaN
            cachedHitY = Float.NaN
            downOnOcrBox = false
            swipeReleased = false
        }

        return gestureResult || superResult
    }

    /**
     * Hit test in source pixel space (view coordinate → source coordinate → normalized coordinate).
     *
     * Returns the best matching block for the point, or null if no block is hit.
     */
    private fun hitTestSource(viewX: Float, viewY: Float): OcrTextBlock? {
        val host = ocrHost?.takeIf { it.ocrEnabled } ?: return null
        if (!isReady) return null

        // Convert view coordinates to source coordinates
        val sourcePoint = viewToSourceCoord(viewX, viewY) ?: return null

        // Convert source coordinates to normalized coordinates
        val nx = sourcePoint.x / sWidth
        val ny = sourcePoint.y / sHeight
        if (nx !in 0f..1f || ny !in 0f..1f) return null

        // Keep OCR hit testing precise but allow tiny edge tolerance for side taps.
        val edgePaddingViewPx = 2f * context.resources.displayMetrics.density
        val sourcePaddingPx = edgePaddingViewPx / scale.coerceAtLeast(1f)
        val nxPadding = sourcePaddingPx / sWidth
        val nyPadding = sourcePaddingPx / sHeight

        return OcrHitTester.findBlock(
            blocks = host.ocrBlocks,
            x = nx,
            y = ny,
            scaleX = host.ocrBoxScaleX,
            scaleY = host.ocrBoxScaleY,
            paddingX = nxPadding,
            paddingY = nyPadding,
        ) { block ->
            OcrNormalizedBounds(block.xmin, block.ymin, block.xmax, block.ymax)
        }
    }

    /**
     * Test if a screen point lies within any OCR block.
     * Used by external viewers (pager/webtoon) to filter tap gestures.
     */
    fun isPointOnActiveOrAnyOcrBlock(viewX: Float, viewY: Float): Boolean {
        // Can be called by outside viewers
        return hitTestSource(viewX, viewY) != null
    }

    // ==================== Gesture Listener ====================

    /**
     * Get cached hit test result if coordinates match, otherwise recalculate.
     * Allows gesture detector to reuse ACTION_DOWN hit test result.
     */
    private fun getCachedHitBlock(viewX: Float, viewY: Float): OcrTextBlock? {
        // Use cache if coordinates are close enough (within 3dp tolerance)
        val tolerance = 3f * context.resources.displayMetrics.density
        if (!cachedHitX.isNaN() && !cachedHitY.isNaN() &&
            kotlin.math.abs(viewX - cachedHitX) < tolerance &&
            kotlin.math.abs(viewY - cachedHitY) < tolerance
        ) {
            return cachedHitBlock
        }
        // Fallback: recalculate (gesture detector may call with slightly different coordinates)
        return hitTestSource(viewX, viewY)
    }

    /**
     * Gesture listener using safe methods only (per SSIV wiki §9).
     */
    inner class OcrGestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean {
            val hitBlock = getCachedHitBlock(e.x, e.y)
            // Outside-touch dismissal is handled on ACTION_DOWN; only consume when tapping a block.
            return hitBlock != null
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val host = ocrHost ?: return false
            val block = getCachedHitBlock(e.x, e.y)
            if (block == null) {
                if (host.activeOcrBlock != null) {
                    host.dismissActiveOcrBlock()
                    return true
                }
                return false
            }
            // Pass both view and screen coordinates
            return host.handleOcrTap(block, e.x, e.y, e.rawX, e.rawY)
        }

        override fun onLongPress(e: MotionEvent) {
            if (swipeReleased) return
            val host = ocrHost ?: return
            val block = getCachedHitBlock(e.x, e.y) ?: return
            parent?.requestDisallowInterceptTouchEvent(true)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            host.handleOcrLongPress(block, e.x, e.y)
        }
    }
}
