package eu.kanade.presentation.reader

import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Selects a crop over the live page without leaving the reader. */
@Composable
fun OcrRegionSelector(
    scanning: Boolean,
    onCancel: () -> Unit,
    onSelected: (RectF) -> Unit,
) {
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    val outline = MaterialTheme.colorScheme.primary
    BackHandler(onBack = onCancel)

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(scanning) {
                if (scanning) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                } else {
                    fun Offset.clamped() = Offset(x.coerceIn(0f, size.width.toFloat()), y.coerceIn(0f, size.height.toFloat()))
                    detectDragGestures(
                        onDragStart = { start = it.clamped(); end = start },
                        onDrag = { change, _ -> change.consume(); end = change.position.clamped() },
                        onDragCancel = { start = null; end = null },
                        onDragEnd = {
                            val first = start
                            val last = end
                            if (first != null && last != null) {
                                val region = RectF(minOf(first.x, last.x), minOf(first.y, last.y), maxOf(first.x, last.x), maxOf(first.y, last.y))
                                if (region.width() >= 8.dp.toPx() && region.height() >= 8.dp.toPx()) onSelected(region)
                            }
                        },
                    )
                }
            },
        ) {
            val first = start
            val last = end
            if (first == null || last == null) {
                drawRect(Color.Black.copy(alpha = .15f))
            } else {
                val left = minOf(first.x, last.x)
                val top = minOf(first.y, last.y)
                val right = maxOf(first.x, last.x)
                val bottom = maxOf(first.y, last.y)
                val shade = Color.Black.copy(alpha = .35f)
                drawRect(shade, size = Size(size.width, top))
                drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
                drawRect(shade, Offset(0f, top), Size(left, bottom - top))
                drawRect(shade, Offset(right, top), Size(size.width - right, bottom - top))
                drawRect(outline, Offset(left, top), Size(right - left, bottom - top), style = Stroke(2.dp.toPx()))
            }
        }
        Surface(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp), shape = MaterialTheme.shapes.medium) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(if (scanning) MR.strings.ocr_region_scanning else MR.strings.ocr_region_select),
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = onCancel) { Text(stringResource(MR.strings.action_cancel)) }
            }
        }
    }
}
