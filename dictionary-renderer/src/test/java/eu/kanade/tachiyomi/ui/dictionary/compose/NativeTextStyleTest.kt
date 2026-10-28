package eu.kanade.tachiyomi.ui.dictionary.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class NativeTextStyleTest {
    private fun resources(stageTimings: Boolean = false): NativeGlossaryResources {
        val resolver = Proxy.newProxyInstance(FontFamily.Resolver::class.java.classLoader,
            arrayOf(FontFamily.Resolver::class.java)) { _, _, _ -> error("Style analysis must not resolve a font") }
        return NativeGlossaryResources(Density(1f), LayoutDirection.Ltr, resolver as FontFamily.Resolver,
            collectStageTimings = stageTimings)
    }

    @Test fun detailedTimingIsOptInAndTracksTheCurrentProfilingRequest() {
        val worker = resources()
        assertEquals(false, worker.stageTimings)
        worker.profilingId = 7L
        assertEquals(true, worker.stageTimings)
        assertEquals(7L, worker.ruby.profilingId)
        worker.profilingId = 0L
        assertEquals(false, worker.stageTimings)
        val diagnostic = resources(stageTimings = true)
        diagnostic.profilingId = 0L
        assertEquals(true, diagnostic.stageTimings)
    }

    @Test fun traversalStyleIdentityIsCanonicalAndCopiesPreserveComposeValues() {
        val worker = resources()
        val original = TextStyle(color = Color.Red, fontSize = 13.sp, lineHeight = 19.sp)
        val style = worker.nativeStyle(original)
        assertSame(style, worker.nativeStyle(original.copy()))
        assertEquals(original.copy(fontWeight = FontWeight.Bold), style.copy(fontWeight = FontWeight.Bold).textStyle)
        assertEquals(fontSizeSp(original), fontSizeSp(style))
    }

    @Test fun modifiersReuseComputedIdentityAndKeepRelativeInheritance() {
        val worker = resources()
        val original = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
        val style = worker.nativeStyle(original)
        val css = NativeCssDeclarations.compile(mapOf("fontSize" to "1.5em", "color" to "red"))
        val result = worker.style(style, css)
        assertSame(result, worker.style(style, css))
        assertSame(result.textStyle, worker.style(original.copy(), css))
        assertEquals(GlossaryTypography.read(css).apply(original), result.textStyle)
        assertSame(style, worker.style(style, NativeCssDeclarations.EMPTY))
    }

    @Test fun rubyReadingsKeepTheExistingTypographyRules() {
        val worker = resources()
        val base = TextStyle(color = Color.Red, fontSize = 20.sp, lineHeight = 28.sp,
            fontWeight = FontWeight.Bold)
        val css = NativeCssDeclarations.compile(mapOf("fontSize" to "60%", "lineHeight" to "1.2em", "color" to "blue"))
        val reading = worker.readingStyle(base, css)
        assertSame(reading, worker.readingStyle(base.copy(), css))
        assertEquals(rubyAnnotationStyle(base, GlossaryTypography.read(css).apply(base), css), reading)
        assertEquals(0, worker.profileMisses)
    }

    @Test fun rubyReadingsRemainRelativeToEachParentWithoutResolvingFonts() {
        val worker = resources()
        val small = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
        val large = TextStyle(fontSize = 40.sp, lineHeight = 56.sp)
        val css = NativeCssDeclarations.EMPTY
        assertEquals(8.sp, worker.readingStyle(small, css).fontSize)
        assertEquals(20.sp, worker.readingStyle(large, css).fontSize)
        assertSame(worker.readingStyle(small, css), worker.readingStyle(small, css))
        assertEquals(0, worker.profileMisses)
    }
}
