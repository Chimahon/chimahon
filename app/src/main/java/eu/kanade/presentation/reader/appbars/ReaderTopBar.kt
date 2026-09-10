package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults.rememberTooltipPositionProvider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOcrSource
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.TextButton
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderTopBar(
    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    onOpenInWebView: (() -> Unit)?,
    onOpenInBrowser: (() -> Unit)?,
    onShare: (() -> Unit)?,
    modifier: Modifier = Modifier,
    ocrEnabled: Boolean = false,
    ocrLoading: Boolean = false,
    ocrSource: ReaderOcrSource = ReaderOcrSource.AUTOMATIC,
    mokuroAvailable: Boolean = false,
    onToggleOcr: (() -> Unit)? = null,
    onSelectOcrSource: (ReaderOcrSource) -> Unit = {},
) {
    AppBar(
        modifier = modifier,
        backgroundColor = Color.Transparent,
        title = mangaTitle,
        subtitle = chapterTitle,
        navigateUp = navigateUp,
        actions = {
            onToggleOcr?.let {
                OcrSourceAction(
                    enabled = ocrEnabled || !ocrLoading,
                    ocrEnabled = ocrEnabled,
                    selectedSource = ocrSource,
                    mokuroAvailable = mokuroAvailable,
                    onToggleOcr = it,
                    onSelectSource = onSelectOcrSource,
                )
            }
            AppBarActions(
                actions = persistentListOf<AppBar.AppBarAction>().builder()
                    .apply {
                        add(
                            AppBar.Action(
                                title = stringResource(
                                    if (bookmarked) {
                                        MR.strings.action_remove_bookmark
                                    } else {
                                        MR.strings.action_bookmark
                                    },
                                ),
                                icon = if (bookmarked) {
                                    Icons.Outlined.Bookmark
                                } else {
                                    Icons.Outlined.BookmarkBorder
                                },
                                onClick = onToggleBookmarked,
                            ),
                        )
                        onOpenInWebView?.let {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_open_in_web_view),
                                    onClick = it,
                                ),
                            )
                        }
                        onOpenInBrowser?.let {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_open_in_browser),
                                    onClick = it,
                                ),
                            )
                        }
                        onShare?.let {
                            add(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_share),
                                    onClick = it,
                                ),
                            )
                        }
                    }
                    .build(),
            )
        },
    )
}

@Composable
private fun OcrSourceAction(
    enabled: Boolean,
    ocrEnabled: Boolean,
    selectedSource: ReaderOcrSource,
    mokuroAvailable: Boolean,
    onToggleOcr: () -> Unit,
    onSelectSource: (ReaderOcrSource) -> Unit,
) {
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    val actionTitle = stringResource(
        if (ocrEnabled) {
            MR.strings.action_disable_ocr
        } else {
            MR.strings.action_enable_ocr
        },
    )

    TooltipBox(
        positionProvider = rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = {
            PlainTooltip {
                Text(actionTitle)
            }
        },
        state = rememberTooltipState(),
        focusable = false,
    ) {
        Box {
            TextButton(
                onClick = onToggleOcr,
                onLongClick = { sourceMenuExpanded = true },
                enabled = enabled,
            ) {
                Text(
                    text = stringResource(MR.strings.action_ocr),
                    color = if (ocrEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }

            DropdownMenu(
                expanded = sourceMenuExpanded,
                onDismissRequest = { sourceMenuExpanded = false },
            ) {
                ReaderOcrSource.availableSources(BuildConfig.HAS_LOCAL_OCR, mokuroAvailable).forEach { source ->
                    DropdownMenuItem(
                        text = { Text(source.displayName()) },
                        onClick = {
                            sourceMenuExpanded = false
                            onSelectSource(source)
                        },
                        trailingIcon = {
                            if (source == selectedSource) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderOcrSource.displayName(): String {
    return stringResource(
        when (this) {
            ReaderOcrSource.AUTOMATIC -> KMR.strings.ocr_source_automatic
            ReaderOcrSource.MOKURO -> KMR.strings.ocr_source_mokuro
            ReaderOcrSource.GOOGLE_LENS -> KMR.strings.ocr_source_google_lens
            ReaderOcrSource.LOCAL -> KMR.strings.ocr_source_local
            ReaderOcrSource.PADDLE -> KMR.strings.ocr_source_paddle
        },
    )
}
