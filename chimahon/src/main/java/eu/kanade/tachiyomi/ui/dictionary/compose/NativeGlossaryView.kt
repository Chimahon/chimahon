package eu.kanade.tachiyomi.ui.dictionary.compose

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.toArgb
import chimahon.diagnostics.LookupProfiler
import kotlin.math.abs

public val LocalNativeGlossaryDocuments = compositionLocalOf<Map<List<StructuredNode>, NativeGlossaryDocument>> { emptyMap() }
public val LocalLookupProfilingId = compositionLocalOf { 0L }

public fun createNativeGlossaryView(context: Context, document: NativeGlossaryDocument): NativeGlossaryView =
    NativeRenderDiagnostics.section(document.profilingId, document.profilingPlanId, "document.view.create",
        "document=${System.identityHashCode(document)}") { NativeGlossaryView(context) }

/** Native text surface: measurement reads dimensions; drawing never walks the glossary source. */
public class NativeGlossaryView(context: Context) : View(context) {
    private var document: NativeGlossaryDocument? = null
    private var drawn: NativeGlossaryDocument? = null
    private var measured: NativeGlossaryDocument? = null
    private var laidOut: NativeGlossaryDocument? = null
    private var dictionary = ""
    private var lookup: ((String) -> Unit)? = null
    private var toggle: ((StructuredNode.Element) -> Unit)? = null
    private var longAction: ((StructuredNode.Element) -> Unit)? = null
    private var kanjiLookup: ((String) -> Unit)? = null
    private var recursiveLookup: ((NativeGlossaryHit) -> Unit)? = null
    private var recursiveSelection: NativeRecursiveSelection? = null
    private var selectionRects: List<android.graphics.RectF> = emptyList()
    private var longPressed = false
    private val longPress = Runnable {
        document?.hit(downX, downY)?.disclosure?.let { action ->
            longAction?.let { longPressed = true; it(action) }
        }
    }
    private val chromePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val viewport = Rect()
    private val visibleRasterBounds = Rect()
    private val previousRasterBounds = Rect()
    private val viewportListener = android.view.ViewTreeObserver.OnPreDrawListener {
        if (!getLocalVisibleRect(visibleRasterBounds)) visibleRasterBounds.setEmpty()
        if (previousRasterBounds != visibleRasterBounds) {
            previousRasterBounds.set(visibleRasterBounds)
            invalidate()
        }
        true
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragged = false

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun mark(value: NativeGlossaryDocument, phase: String, detail: String = "") {
        NativeRenderDiagnostics.mark(value.profilingId, value.profilingPlanId, phase,
            "document=${System.identityHashCode(value)} view=${System.identityHashCode(this)} $detail")
    }

    fun bind(value: NativeGlossaryDocument, name: String, onLookup: ((String) -> Unit)?, onToggle: (StructuredNode.Element) -> Unit,
        onLongAction: ((StructuredNode.Element) -> Unit)? = null, onKanjiLookup: ((String) -> Unit)? = null,
        onRecursiveLookup: ((NativeGlossaryHit) -> Unit)? = null, selection: NativeRecursiveSelection? = null) {
        lookup = onLookup
        toggle = onToggle
        longAction = onLongAction
        kanjiLookup = onKanjiLookup
        recursiveLookup = onRecursiveLookup
        recursiveSelection = selection
        dictionary = name
        if (document !== value) {
            clearRecursiveSelection()
            val resized = document?.width != value.width || document?.height != value.height
            document = value
            mark(value, "document.bind", "$name width=${value.width} height=${value.height} items=${value.items.size} resized=$resized attached=$isAttachedToWindow")
            if (resized) requestLayout()
            invalidate()
        }
    }

    internal fun clearRecursiveSelection() {
        if (selectionRects.isNotEmpty()) {
            selectionRects = emptyList()
            invalidate()
        }
    }

    internal fun showRecursiveSelection(hit: NativeGlossaryHit, codePoints: Int): android.graphics.RectF? {
        val surface = hit.surface ?: return null
        if (!isAttachedToWindow || document?.items?.any { it === surface } != true) return null
        selectionRects = surface.selectionRects(hit.textOffset, codePoints)
        if (selectionRects.isEmpty()) return null
        invalidate()
        val bounds = android.graphics.RectF(selectionRects.first())
        selectionRects.drop(1).forEach(bounds::union)
        val position = IntArray(2)
        getLocationOnScreen(position)
        bounds.offset(position[0].toFloat(), position[1].toFloat())
        return bounds
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val value = document
        LookupProfiler.section(value?.profilingId ?: 0L, "native.view.measure") {
            setMeasuredDimension(resolveSize(value?.width ?: 0, widthMeasureSpec), resolveSize(value?.height ?: 0, heightMeasureSpec))
        }
        if (value != null && measured !== value) {
            measured = value
            mark(value, "document.first_measure", "width=$measuredWidth height=$measuredHeight")
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val value = document ?: return
        if (laidOut !== value) {
            laidOut = value
            mark(value, "document.first_layout", "changed=$changed width=${right - left} height=${bottom - top}")
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(viewportListener)
        document?.let { mark(it, "document.attached") }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val value = document ?: return
        if (drawn !== value) mark(value, "document.first_draw.begin")
        val checkpoint = canvas.save()
        if (isAttachedToWindow) {
            if (!getLocalVisibleRect(visibleRasterBounds)) {
                canvas.restoreToCount(checkpoint)
                return
            }
            canvas.clipRect(visibleRasterBounds)
        }
        canvas.getClipBounds(viewport)
        val paintSink = if (drawn !== value && value.profilingId != 0L) LookupProfiler.sink?.takeIf { it.enabled } else null
        val paintStart = paintSink?.now() ?: 0L
        val paintCpu = paintSink?.cpu() ?: 0L
        LookupProfiler.section(value.profilingId, "native.document.paint") {
            for (item in value.items) {
                if (item.y >= viewport.bottom || item.y + item.height <= viewport.top || item.x >= viewport.right || item.x + item.width <= viewport.left) continue
                item.draw(canvas, chromePaint)
            }
            if (selectionRects.isNotEmpty()) {
                val previousColor = chromePaint.color
                val previousStyle = chromePaint.style
                chromePaint.color = recursiveSelection?.color ?: value.secondary.toArgb()
                chromePaint.style = Paint.Style.FILL
                for (rect in selectionRects) canvas.drawRect(rect.left, rect.bottom - 2 * resources.displayMetrics.density,
                    rect.right, rect.bottom, chromePaint)
                chromePaint.color = previousColor
                chromePaint.style = previousStyle
            }
        }
        canvas.restoreToCount(checkpoint)
        paintSink?.event(value.profilingId, "document.first_paint", paintStart, paintSink.now(), paintSink.cpu() - paintCpu,
            "plan=${value.profilingPlanId} document=${System.identityHashCode(value)} view=${System.identityHashCode(this)} inclusive")
        if (drawn !== value) {
            drawn = value
            mark(value, "document.first_draw", dictionary)
            if (value.profilingId != 0L && android.os.Build.VERSION.SDK_INT >= 29 && isHardwareAccelerated && viewTreeObserver.isAlive) {
                viewTreeObserver.registerFrameCommitCallback {
                    mark(value, "document.frame_commit", "render-thread commit;not display presentation")
                    // First vsync after the commit is the closest app-side estimate of presentation.
                    android.view.Choreographer.getInstance().postFrameCallback {
                        mark(value, "document.frame_presented", "next vsync after commit")
                    }
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; dragged = false; longPressed = false
                if (longAction != null) postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) {
                    dragged = true
                    removeCallbacks(longPress)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!dragged && !longPressed) {
                    val hit = document?.hit(event.x, event.y)
                    hit?.disclosure?.let { toggle?.invoke(it) } ?: hit?.lookup?.let {
                        if (recursiveLookup != null) {
                            val position = IntArray(2)
                            getLocationInWindow(position)
                            val tapped = hit.copy(tapX = (position[0] + event.x) / resources.displayMetrics.density,
                                tapY = (position[1] + event.y) / resources.displayMetrics.density)
                            tapped.surface = hit.surface
                            tapped.textOffset = hit.textOffset
                            recursiveSelection?.select(this, tapped)
                            recursiveLookup?.invoke(tapped)
                        } else if (hit.kanji && kanjiLookup != null) kanjiLookup?.invoke(it) else lookup?.invoke(it)
                    }
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); dragged = true; return true }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(viewportListener)
        document?.items?.filterIsInstance<NativeCommandSurface>()?.forEach { it.clearRaster() }
        previousRasterBounds.setEmpty()
        super.onDetachedFromWindow()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.TextView"
        info.text = document?.text
        document?.disclosures?.forEachIndexed { index, item ->
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(DISCLOSURE_ACTION + index,
                item.source.children.filterIsInstance<StructuredNode.Text>().joinToString("") { it.text }.ifBlank { "Details" }))
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        val index = action - DISCLOSURE_ACTION
        document?.disclosures?.getOrNull(index)?.let { toggle?.invoke(it.source); return true }
        return super.performAccessibilityAction(action, arguments)
    }

    companion object {
        private const val DISCLOSURE_ACTION = 0x01020000
        private const val LINK_ACTION = 0x01030000
    }
}
