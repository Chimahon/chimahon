@file:Suppress("NOTHING_TO_INLINE")

package eu.kanade.tachiyomi.util.view

import android.content.res.Resources
import android.graphics.Rect
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.MenuRes
import androidx.appcompat.widget.PopupMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.LocalBackgroundTextMeasurementExecutor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.ViewConfiguration
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.R
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Warms Compose's text-layout cache on a background thread.
 *
 * Measuring a `Text` walks its characters through `Layout.getDesiredWidth`, which is a JNI call per
 * run - the dominant cost when a dictionary result lays out a screenful of glosses, and the reason
 * opening a popup dropped hundreds of frames. `BasicText` will precompute those metrics off the
 * main thread if a `LocalBackgroundTextMeasurementExecutor` is provided; the local defaults to
 * `null`, so nothing happens unless this is set.
 *
 * A single shared daemon thread: the work is pure cache warming, so serialising it is fine and
 * cheaper than a pool.
 */
@PublishedApi
internal val textMeasurementExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "text-measurement").apply { isDaemon = true }
}

inline fun ComponentActivity.setComposeContent(
    parent: CompositionContext? = null,
    crossinline content: @Composable () -> Unit,
) {
    setContent(parent) {
        TachiyomiTheme {
            val defaultViewConfig = LocalViewConfiguration.current
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.bodySmall,
                LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                LocalViewConfiguration provides object : ViewConfiguration {
                    override val longPressTimeoutMillis: Long = 200
                    override val doubleTapTimeoutMillis: Long = defaultViewConfig.doubleTapTimeoutMillis
                    override val doubleTapMinTimeMillis: Long = defaultViewConfig.doubleTapMinTimeMillis
                    override val touchSlop: Float = defaultViewConfig.touchSlop
                },
                LocalBackgroundTextMeasurementExecutor provides textMeasurementExecutor,
            ) {
                content()
            }
        }
    }
}

fun ComposeView.setComposeContent(
    content: @Composable () -> Unit,
) {
    setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    setContent {
        TachiyomiTheme {
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.bodySmall,
                LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                LocalBackgroundTextMeasurementExecutor provides textMeasurementExecutor,
            ) {
                content()
            }
        }
    }
}

/**
 * Shows a popup menu on top of this view.
 *
 * @param menuRes menu items to inflate the menu with.
 * @param initMenu function to execute when the menu after is inflated.
 * @param onMenuItemClick function to execute when a menu item is clicked.
 */
inline fun View.popupMenu(
    @MenuRes menuRes: Int,
    noinline initMenu: (Menu.() -> Unit)? = null,
    noinline onMenuItemClick: MenuItem.() -> Unit,
): PopupMenu {
    val popup = PopupMenu(context, this, Gravity.NO_GRAVITY, R.attr.actionOverflowMenuStyle, 0)
    popup.menuInflater.inflate(menuRes, popup.menu)

    if (initMenu != null) {
        popup.menu.initMenu()
    }
    popup.setOnMenuItemClickListener {
        it.onMenuItemClick()
        true
    }

    popup.show()
    return popup
}

fun View?.isVisibleOnScreen(): Boolean {
    if (this == null) {
        return false
    }
    if (!this.isShown) {
        return false
    }
    val actualPosition = Rect()
    this.getGlobalVisibleRect(actualPosition)
    val screen =
        Rect(0, 0, Resources.getSystem().displayMetrics.widthPixels, Resources.getSystem().displayMetrics.heightPixels)
    return actualPosition.intersect(screen)
}
