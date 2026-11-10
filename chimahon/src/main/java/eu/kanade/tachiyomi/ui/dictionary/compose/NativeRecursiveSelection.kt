package eu.kanade.tachiyomi.ui.dictionary.compose

import android.graphics.RectF
import androidx.compose.runtime.compositionLocalOf
import java.lang.ref.WeakReference

/** Popup-owned selection; populated only on a tap, never during preparation. */
public class NativeRecursiveSelection {
    public var color: Int? = null
    public var onParentScroll: (() -> Unit)? = null
    private var view: WeakReference<NativeGlossaryView>? = null
    private var hit: NativeGlossaryHit? = null

    internal fun select(view: NativeGlossaryView, hit: NativeGlossaryHit) {
        clear()
        this.view = WeakReference(view)
        this.hit = hit
    }

    /** Matched-text bounds in screen pixels, not WebView CSS coordinates. */
    public fun resolve(codePoints: Int): RectF? = hit?.let { view?.get()?.showRecursiveSelection(it, codePoints) }

    public fun clear() {
        view?.get()?.clearRecursiveSelection()
        view = null
        hit = null
    }
}

public val LocalNativeRecursiveSelection = compositionLocalOf<NativeRecursiveSelection?> { null }
