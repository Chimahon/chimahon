package eu.kanade.tachiyomi.ui.dictionary.compose

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.viewinterop.AndroidView
import chimahon.diagnostics.LookupProfiler
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs

public val LocalNativeGlossaryDocuments = compositionLocalOf<Map<List<StructuredNode>, NativeGlossaryDocument>> { emptyMap() }
public val LocalLookupProfilingId = compositionLocalOf { 0L }

@Composable
public fun StructuredGlossaryContent(nodes: List<StructuredNode>, parsedCss: ParsedCss, dictName: String,
    fontSize: Int, onBg: Color, secondary: Color, border: Color, onRecursiveLookup: ((String) -> Unit)?,
    modifier: Modifier = Modifier, ancestors: List<CssAncestor> = emptyList(), fontFamily: FontFamily? = null) {
    NativeGlossaryContent(nodes, parsedCss, dictionaryBaseStyle(onBg, fontSize, fontFamily), dictName,
        secondary, border, onRecursiveLookup, modifier, ancestors)
}

@Composable
public fun NativeGlossaryContent(nodes: List<StructuredNode>, css: ParsedCss, style: TextStyle, dictName: String,
    secondary: Color, border: Color, onLookup: ((String) -> Unit)?, modifier: Modifier, ancestors: List<CssAncestor>) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val resolver = LocalFontFamilyResolver.current
    val provider = LocalNativeGlossaryDocuments.current
    val profilingId = LocalLookupProfilingId.current
    var toggled by remember(nodes) { mutableStateOf<Set<StructuredNode.Element>>(emptySet()) }
    var fontRevision by remember(nodes, resolver) { mutableIntStateOf(0) }
    val media = LocalMediaDataUris.current
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        require(width != Constraints.Infinity) { "Dictionary documents require a bounded viewport" }
        val initial = provider[nodes]?.takeIf { fontRevision == 0 && toggled.isEmpty() && it.matches(nodes, css, style, ancestors,
            density, direction, resolver, secondary, border, width) }
        val prepared by produceState(initialValue = initial, nodes, css, style, ancestors, density, direction, resolver, secondary, border, width, toggled, fontRevision) {
            value = initial ?: withContext(Dispatchers.Default) {
                NativeGlossaryCompiler(css, density, direction, resolver, secondary, border, toggled,
                    checkCancellation = { coroutineContext.ensureActive() }, profilingId = profilingId)
                    .prepare(nodes, style, ancestors, width)
            }
        }
        val staleFonts = prepared?.hasStaleFonts == true
        LaunchedEffect(prepared, staleFonts) { if (staleFonts) fontRevision++ }
        val document = prepared?.takeIf { it.matches(nodes, css, style, ancestors, density, direction, resolver, secondary, border, width) }
        if (document != null) {
            val toggle: (StructuredNode.Element) -> Unit = { node -> toggled = if (node in toggled) toggled - node else toggled + node }
            Layout(content = {
                AndroidView(factory = { NativeGlossaryView(it) }, update = { it.bind(document, dictName, onLookup, toggle) })
                document.images.forEach { image ->
                    val path = image.source.attributes.properties["path"].orEmpty()
                    val uri = resolveMediaUri2(dictName, path, media)
                    AsyncImage(model = uri, contentDescription = image.source.attributes.properties["title"])
                }
            }) { children, bounds ->
                val body = children.first().measure(Constraints.fixed(document.width, document.height))
                val images = document.images.mapIndexed { index, image ->
                    children[index + 1].measure(Constraints.fixed(image.width, image.height))
                }
                layout(bounds.constrainWidth(document.width), bounds.constrainHeight(document.height)) {
                    body.place(0, 0)
                    images.forEachIndexed { index, image -> image.place(document.images[index].x, document.images[index].y) }
                }
            }
        }
    }
}

/** Native text surface: measurement reads dimensions; drawing never walks the glossary source. */
public class NativeGlossaryView(context: Context) : View(context) {
    private var document: NativeGlossaryDocument? = null
    private var drawn: NativeGlossaryDocument? = null
    private var dictionary = ""
    private var lookup: ((String) -> Unit)? = null
    private var toggle: ((StructuredNode.Element) -> Unit)? = null
    private val chromePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val viewport = Rect()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragged = false

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun bind(value: NativeGlossaryDocument, name: String, onLookup: ((String) -> Unit)?, onToggle: (StructuredNode.Element) -> Unit) {
        lookup = onLookup
        toggle = onToggle
        dictionary = name
        if (document !== value) {
            val resized = document?.width != value.width || document?.height != value.height
            document = value
            if (resized) requestLayout()
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val value = document
        setMeasuredDimension(resolveSize(value?.width ?: 0, widthMeasureSpec), resolveSize(value?.height ?: 0, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val value = document ?: return
        canvas.getClipBounds(viewport)
        LookupProfiler.section(value.profilingId, "native.document.paint") {
            for (item in value.items) {
                if (item !is NativeCircleClip && (item.y >= viewport.bottom || item.y + item.height <= viewport.top || item.x >= viewport.right || item.x + item.width <= viewport.left)) continue
                item.draw(canvas, chromePaint)
            }
        }
        if (drawn !== value) {
            drawn = value
            LookupProfiler.mark(value.profilingId, "document.first_draw", dictionary)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; dragged = false; return true }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) dragged = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged) {
                    val hit = document?.hit(event.x, event.y)
                    hit?.disclosure?.let { toggle?.invoke(it) } ?: hit?.lookup?.let { lookup?.invoke(it) }
                    performClick()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { dragged = true; return true }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.TextView"
        info.text = document?.text
        document?.disclosures?.forEachIndexed { index, item ->
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(DISCLOSURE_ACTION + index,
                item.source.children.firstOrNull { it is StructuredNode.Element && it.tag == StructuredTag.Summary }?.collect() ?: "Details"))
        }
        document?.links?.forEachIndexed { index, target ->
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(LINK_ACTION + index, target))
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        val index = action - DISCLOSURE_ACTION
        document?.disclosures?.getOrNull(index)?.let { toggle?.invoke(it.source); return true }
        document?.links?.getOrNull(action - LINK_ACTION)?.let { lookup?.invoke(it); return true }
        return super.performAccessibilityAction(action, arguments)
    }

    companion object {
        private const val DISCLOSURE_ACTION = 0x01020000
        private const val LINK_ACTION = 0x01030000
    }
}
