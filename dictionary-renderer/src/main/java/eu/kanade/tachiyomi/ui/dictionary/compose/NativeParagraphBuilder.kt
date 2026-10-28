package eu.kanade.tachiyomi.ui.dictionary.compose

import android.text.Spanned
import android.text.style.LeadingMarginSpan
import kotlin.math.roundToInt

/** Traversal-owned inherited state; no layout, content, or UI state is stored here. */
internal class NativeInlineState {
    var link: String? = null
    var whiteSpace = "normal"
    var trim = "normal"
    val preserveWhiteSpace: Boolean get() = whiteSpace == "pre-wrap" || whiteSpace == "pre" || whiteSpace == "break-spaces"
}

/** Assembles one paragraph at a time. It never knows an available width or a screen position. */
internal class NativeParagraphBuilder(
    private val style: NativeTextStyle,
    private val resources: NativeGlossaryResources,
    private val state: NativeInlineState,
    private val vertical: Boolean,
    private val interactive: Boolean,
    private val checkCancellation: () -> Unit,
) {
    private var text = NativeParagraphText()
    private val spacing = CssWhiteSpace()
    private var runStyle: NativeTextStyle? = null
    private var runProfile: NativeTextProfile? = null
    private var paragraphProfile: NativeTextProfile? = null
    private var runTrim = "normal"
    private var runStart = 0
    private var runLink: String? = null
    private var linkStart = 0
    private var minimumHeight = 0f
    private var hasShiftedText = false
    private var shiftedRuns: ArrayList<NativeShiftedRun>? = null
    private var rubies: ArrayList<NativeRubySpan>? = null
    private var decorations: ArrayList<NativeInlineDecoration>? = null
    private var images: ArrayList<NativeInlineImage>? = null
    val length: Int get() = text.length
    val hasImages: Boolean get() = !images.isNullOrEmpty()

    private fun rootProfile(): NativeTextProfile = paragraphProfile ?: resources.profile(style).also { paragraphProfile = it }

    /** Returns true only when this append starts a nonempty paragraph. */
    fun append(value: String, style: NativeTextStyle): Boolean =
        appendInternal(verticalize(spacing.append(value, state.preserveWhiteSpace)), style)

    fun appendRaw(value: String, style: NativeTextStyle): Boolean {
        if (value.any { !isCssWhiteSpace(it) }) spacing.markContent()
        return appendInternal(value, style)
    }

    fun appendBreak(style: NativeTextStyle): Boolean {
        val started = appendInternal("\n", style)
        spacing.reset()
        return started
    }

    fun noLookup(value: String, style: NativeTextStyle): Boolean {
        val start = length
        val started = appendRaw(value, style)
        if (start < length) addSpan(NativeNoLookup(), start)
        return started
    }

    fun addSpan(value: Any, start: Int) { text.addSpan(value, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    fun ruby(base: String, style: NativeTextStyle, metrics: RubyMetrics): Boolean {
        val start = length
        val started = append(base, style)
        if (start < length) {
            val span = NativeRubySpan(base, metrics, start, length)
            addSpan(span, start)
            val runs = rubies ?: ArrayList<NativeRubySpan>().also { rubies = it }
            runs.add(span)
        }
        return started
    }

    fun image(node: StructuredNode.Element, style: NativeTextStyle, width: Int, height: Int, shift: Int): Boolean {
        val start = length
        val started = appendRaw("\uFFFC", style)
        addSpan(NativeInlineImageSpan(width, height, shift), start)
        val items = images ?: ArrayList<NativeInlineImage>().also { images = it }
        items.add(NativeInlineImage(start, width, height, shift, node))
        return started
    }

    fun decorate(decoration: NativeInlineDecoration) {
        val items = decorations ?: ArrayList<NativeInlineDecoration>().also { decorations = it }
        items.add(decoration)
    }

    private fun verticalize(value: String): String {
        if (!vertical || value.length < 2) return value
        return buildString(value.length * 2) {
            for (char in value) {
                if (isNotEmpty()) append('\n')
                append(char)
            }
        }
    }

    private fun appendInternal(value: String, style: NativeTextStyle): Boolean {
        if (value.isEmpty()) return false
        resources.appends++
        val started = text.isEmpty()
        if (runStyle !== style || runTrim != state.trim) {
            endRun()
            runStyle = style
            runProfile = null
            runTrim = state.trim
            hasShiftedText = hasShiftedText || (style.baselineShift?.multiplier ?: 0f) != 0f
        }
        if (runLink != state.link) { endLink(); runLink = state.link }
        text.append(value)
        val profile = runProfile ?: resources.profile(style).also { runProfile = it }
        minimumHeight = maxOf(minimumHeight, profile.resource.lineHeightPx)
        return started
    }

    private fun endRun() {
        if (runStyle != null && runStart < length) {
            val base = rootProfile()
            val profile = checkNotNull(runProfile)
            profile.spanFor(base)?.let { text.addSpan(it, runStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (profile.resource.baselineShiftPx.roundToInt() != 0 || profile.resource.lineHeightPx > base.resource.lineHeightPx) {
                val runs = shiftedRuns ?: ArrayList<NativeShiftedRun>().also { shiftedRuns = it }
                runs.add(NativeShiftedRun(runStart, length, profile.resource))
            }
            if (runTrim != "normal") text.addSpan(NativeTextSpacingTrim(runTrim), runStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        runStart = length
    }

    private fun endLink() {
        val target = runLink
        if (interactive && target != null && linkStart < length && text.hasLookupText(linkStart, length))
            text.addSpan(NativeLookupLink(target), linkStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        linkStart = length
    }

    fun finish(indent: Int, rubyExtra: Int): NativeParagraph? {
        if (text.isEmpty()) return null
        // CSS 2.1 16.6.1: the terminal break does not generate another line box.
        if (text[length - 1] == '\n') text.trimLastCharacter()
        if (text.isEmpty()) {
            rubies = null
            shiftedRuns = null
            return null
        }
        val started = if (resources.stageTimings) System.nanoTime() else 0L
        checkCancellation()
        endRun()
        endLink()
        val profile = rootProfile()
        val rubyRuns = rubies.orEmpty()
        val ranges = text.ranges()
        if (rubyRuns.isNotEmpty()) reserveRubyAnnotations(text, ranges, rubyRuns, profile)
        if (!vertical) applyHanPunctuationSpacing(text, ranges, profile.resource.font)
        if (indent != 0) {
            text.addSpan(LeadingMarginSpan.Standard(indent, 0), 0, text.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }
        val content = text.build()
        val paragraph = NativeParagraph(content, style, profile, minimumHeight, shiftedRuns.orEmpty(),
            rubyRuns, rubyExtra, hasShiftedText, decorations.orEmpty(), images.orEmpty())
        // Transfer ownership of inline lists; nothing retained by the paragraph is cleared.
        text = NativeParagraphText()
        rubies = null
        shiftedRuns = null
        decorations = null
        images = null
        runStyle = null
        runProfile = null
        runStart = 0
        runLink = null
        linkStart = 0
        minimumHeight = 0f
        hasShiftedText = false
        spacing.reset()
        if (resources.stageTimings) resources.paragraphFinishNanos += System.nanoTime() - started
        return paragraph
    }

    private fun reserveRubyAnnotations(text: NativeParagraphText, ranges: NativeParagraphRanges,
        runs: List<NativeRubySpan>, profile: NativeTextProfile) {
        // Carry fractional ReplacementSpan advance instead of rounding every base upward.
        var rounding = 0f
        for (span in runs) {
            val metrics = span.metrics
            if (metrics.readingLayout.advancePx <= metrics.baseLayout.advancePx) {
                rounding = span.reserveAnnotations(0f, 0f, metrics.readingLayout.fontSizePx, rounding)
                continue
            }
            val baseFont = metrics.baseLayout.fontSizePx
            fun neighbor(from: Int, to: Int): Float {
                if (from < 0 || to > text.length || from >= to || text[from] == '\n' ||
                    ranges.replacements.overlapping(from, to) != null) return 0f
                val paint = ranges.styles.lastOverlapping(from, to)?.profile?.resource?.paint
                    ?: profile.resource.paint
                return if (paint.textSize > baseFont) 0f else paint.measureText(text, from, to)
            }
            val before = if (span.start > 0) neighbor(Character.offsetByCodePoints(text, span.start, -1), span.start) else 0f
            val after = if (span.end < text.length) neighbor(span.end, Character.offsetByCodePoints(text, span.end, 1)) else 0f
            rounding = span.reserveAnnotations(before, after, metrics.readingLayout.fontSizePx, rounding)
        }
    }
}
