package eu.kanade.presentation.entries.novel.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AttachMoney
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedSuggestionChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.entries.anime.components.MarkdownRender
import eu.kanade.presentation.entries.components.DotSeparatorText
import eu.kanade.presentation.manga.components.MangaActionButton
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import eu.kanade.tachiyomi.util.system.copyToClipboard
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.DISABLED_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication
import tachiyomi.presentation.core.util.secondaryItemAlpha
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

@Composable
fun NovelInfoBox(
    appBarPadding: Dp,
    novel: SNNovel,
    sourceName: String,
    isStubSource: Boolean,
    onCoverClick: () -> Unit,
    onSourceClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // Backdrop
        val thumbnailUrl = novel.thumbnail_url
        val backdropGradientColors = listOf(
            Color.Transparent,
            MaterialTheme.colorScheme.background,
        )
        if (!thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbnailUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = backdropGradientColors,
                                startY = size.height / 2,
                            ),
                        )
                    }
                    .background(MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.4f))
                    .blur(7.dp)
                    .alpha(0.24f),
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            )
        }

        // Novel & source info
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = appBarPadding + 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MangaCover.Book(
                    data = ImageRequest.Builder(LocalContext.current)
                        .data(thumbnailUrl)
                        .crossfade(true)
                        .build(),
                    modifier = Modifier
                        .sizeIn(maxWidth = 100.dp)
                        .aspectRatio(2f / 3f),
                    contentDescription = novel.title,
                    onClick = onCoverClick,
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    NovelContentInfo(
                        title = novel.title,
                        author = novel.author,
                        artist = novel.artist,
                        status = novel.status,
                        sourceName = sourceName,
                        isStubSource = isStubSource,
                        onSourceClick = onSourceClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.NovelContentInfo(
    title: String,
    author: String?,
    artist: String?,
    status: Int,
    sourceName: String,
    isStubSource: Boolean,
    onSourceClick: (() -> Unit)?,
    textAlign: TextAlign? = null,
) {
    val context = LocalContext.current
    Text(
        text = title.ifBlank { stringResource(MR.strings.unknown_title) },
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.clickableNoIndication(
            onLongClick = {
                if (title.isNotBlank()) {
                    context.copyToClipboard(title, title)
                }
            },
            onClick = {
                if (title.isNotBlank()) {
                    context.copyToClipboard(title, title)
                }
            },
        ),
        textAlign = textAlign,
    )

    Spacer(modifier = Modifier.height(2.dp))

    Row(
        modifier = Modifier.secondaryItemAlpha(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.PersonOutline,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = author?.takeIf { it.isNotBlank() }
                ?: stringResource(MR.strings.unknown_author),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.clickableNoIndication(
                onLongClick = {
                    if (!author.isNullOrBlank()) {
                        context.copyToClipboard(author, author)
                    }
                },
                onClick = {
                    if (!author.isNullOrBlank()) {
                        context.copyToClipboard(author, author)
                    }
                },
            ),
            textAlign = textAlign,
        )
    }

    if (!artist.isNullOrBlank() && author != artist) {
        Row(
            modifier = Modifier.secondaryItemAlpha(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Brush,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.clickableNoIndication(
                    onLongClick = { context.copyToClipboard(artist, artist) },
                    onClick = { context.copyToClipboard(artist, artist) },
                ),
                textAlign = textAlign,
            )
        }
    }

    Spacer(modifier = Modifier.height(2.dp))

    Row(
        modifier = Modifier.secondaryItemAlpha(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (status) {
                SNNovel.ONGOING -> Icons.Outlined.Schedule
                SNNovel.COMPLETED -> Icons.Outlined.DoneAll
                SNNovel.LICENSED -> Icons.Outlined.AttachMoney
                SNNovel.PUBLISHING_FINISHED -> Icons.Outlined.Done
                SNNovel.CANCELLED -> Icons.Outlined.Close
                SNNovel.ON_HIATUS -> Icons.Outlined.Pause
                else -> Icons.Outlined.Block
            },
            contentDescription = null,
            modifier = Modifier
                .padding(end = 4.dp)
                .size(16.dp),
        )
        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
            Text(
                text = when (status) {
                    SNNovel.ONGOING -> stringResource(MR.strings.ongoing)
                    SNNovel.COMPLETED -> stringResource(MR.strings.completed)
                    SNNovel.LICENSED -> stringResource(MR.strings.licensed)
                    SNNovel.PUBLISHING_FINISHED -> stringResource(MR.strings.publishing_finished)
                    SNNovel.CANCELLED -> stringResource(MR.strings.cancelled)
                    SNNovel.ON_HIATUS -> stringResource(MR.strings.on_hiatus)
                    else -> stringResource(MR.strings.unknown)
                },
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
            DotSeparatorText()
            if (isStubSource) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = sourceName,
                modifier = Modifier.clickableNoIndication {
                    onSourceClick?.invoke()
                },
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun NovelCoverDialog(
    imageUrl: String?,
    title: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickableNoIndication(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(imageUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }
        }
    }
}

@Composable
fun NovelActionRow(
    favorite: Boolean,
    nextUpdate: Long,
    isUserIntervalMode: Boolean,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onEditIntervalClicked: (() -> Unit)?,
    onEditCategory: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val defaultActionButtonColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)

    val nextUpdateDays = remember(nextUpdate) {
        return@remember if (nextUpdate > 0) {
            val now = Instant.now()
            now.until(Instant.ofEpochMilli(nextUpdate), ChronoUnit.DAYS).toInt().coerceAtLeast(0)
        } else {
            null
        }
    }

    Row(modifier = modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
        MangaActionButton(
            title = if (favorite) {
                stringResource(MR.strings.in_library)
            } else {
                stringResource(MR.strings.add_to_library)
            },
            icon = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            color = if (favorite) MaterialTheme.colorScheme.primary else defaultActionButtonColor,
            onClick = onAddToLibraryClicked,
            onLongClick = onEditCategory,
        )
        MangaActionButton(
            title = when (nextUpdateDays) {
                null -> stringResource(MR.strings.not_applicable)
                0 -> stringResource(MR.strings.manga_interval_expected_update_soon)
                else -> pluralStringResource(
                    MR.plurals.day,
                    count = nextUpdateDays,
                    nextUpdateDays,
                )
            },
            icon = Icons.Filled.HourglassEmpty,
            color = if (isUserIntervalMode) MaterialTheme.colorScheme.primary else defaultActionButtonColor,
            onClick = { onEditIntervalClicked?.invoke() },
        )
        if (onWebViewClicked != null) {
            MangaActionButton(
                title = stringResource(MR.strings.action_open_in_web_view),
                icon = Icons.Outlined.Public,
                color = defaultActionButtonColor,
                onClick = onWebViewClicked,
            )
        }
    }
}

@Composable
fun NovelFetchIntervalDialog(
    currentInterval: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var selected by remember(currentInterval) { mutableStateOf(currentInterval) }
    val options = listOf(
        stringResource(MR.strings.label_default) to 0,
        pluralStringResource(MR.plurals.day, 1, 1) to -1,
        pluralStringResource(MR.plurals.day, 2, 2) to -2,
        pluralStringResource(MR.plurals.day, 3, 3) to -3,
        pluralStringResource(MR.plurals.day, 7, 7) to -7,
        pluralStringResource(MR.plurals.day, 14, 14) to -14,
        pluralStringResource(MR.plurals.day, 28, 28) to -28,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.pref_library_update_smart_update)) },
        text = {
            Column {
                options.forEach { (label, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickableNoIndication { selected = value }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected == value,
                            onClick = { selected = value },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = label)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(selected)
                onDismiss()
            }) {
                Text(text = stringResource(MR.strings.action_ok))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExpandableNovelDescription(
    description: String?,
    tagsProvider: () -> List<String>?,
    onTagSearch: (String) -> Unit,
    onCopyTagToClipboard: (tag: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        var expanded by rememberSaveable { mutableStateOf(false) }
        val desc =
            description.takeIf { !it.isNullOrBlank() } ?: stringResource(
                MR.strings.description_placeholder,
            )
        val trimmedDescription = remember(desc) {
            desc.toCollapsedNovelMarkdown()
        }
        NovelSummary(
            expandedDescription = desc,
            shrunkDescription = trimmedDescription,
            expanded = expanded,
            modifier = Modifier
                .padding(top = 8.dp)
                .padding(horizontal = 16.dp)
                .clickableNoIndication { expanded = !expanded },
        )
        val tags = tagsProvider()
        if (!tags.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .padding(vertical = 12.dp)
                    .animateContentSize(animationSpec = spring())
                    .fillMaxWidth(),
            ) {
                var showMenu by remember { mutableStateOf(false) }
                var tagSelected by remember { mutableStateOf("") }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(MR.strings.action_search)) },
                        onClick = {
                            onTagSearch(tagSelected)
                            showMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(MR.strings.action_copy_to_clipboard)) },
                        onClick = {
                            onCopyTagToClipboard(tagSelected)
                            showMenu = false
                        },
                    )
                }
                if (expanded) {
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                    ) {
                        tags.forEach {
                            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                                ElevatedSuggestionChip(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    onClick = {
                                        tagSelected = it
                                        showMenu = true
                                    },
                                    label = {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    colors = SuggestionChipDefaults.elevatedSuggestionChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ),
                                )
                            }
                        }
                    }
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                    ) {
                        items(items = tags) {
                            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                                ElevatedSuggestionChip(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                    onClick = {
                                        tagSelected = it
                                        showMenu = true
                                    },
                                    label = {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    colors = SuggestionChipDefaults.elevatedSuggestionChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val novelWhitespaceLineRegex = Regex("[\\r\\n]{2,}", setOf(RegexOption.MULTILINE))
private val novelMarkdownImageRegex = Regex("""!\[[^\]]*]\([^)]*\)""")
private val novelHtmlImageRegex = Regex("""<img\b[^>]*>""", setOf(RegexOption.IGNORE_CASE))

@Composable
private fun NovelSummary(
    expandedDescription: String,
    shrunkDescription: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val animProgress by animateFloatAsState(if (expanded) 1f else 0f)
    Layout(
        modifier = modifier.clipToBounds(),
        contents = listOf(
            {
                Text(
                    text = "\n\n", // Shows at least 3 lines
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            {
                MarkdownRender(content = expandedDescription)
            },
            {
                SelectionContainer {
                    MarkdownRender(
                        content = if (expanded) expandedDescription else shrunkDescription,
                        loadImages = expanded,
                        modifier = Modifier.secondaryItemAlpha(),
                    )
                }
            },
            {
                val colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background)
                Box(
                    modifier = Modifier.background(Brush.verticalGradient(colors = colors)),
                    contentAlignment = Alignment.Center,
                ) {
                    val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_caret_down)
                    Icon(
                        painter = rememberAnimatedVectorPainter(image, !expanded),
                        contentDescription = stringResource(
                            if (expanded) MR.strings.manga_info_collapse else MR.strings.manga_info_expand,
                        ),
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.background(Brush.radialGradient(colors = colors.asReversed())),
                    )
                }
            },
        ),
    ) { (shrunk, expanded, actual, scrim), constraints ->
        val shrunkHeight = shrunk.single()
            .measure(constraints)
            .height
        val expandedHeight = expanded.single()
            .measure(constraints)
            .height
        val heightDelta = expandedHeight - shrunkHeight
        val scrimHeight = 24.dp.roundToPx()

        val actualPlaceable = actual.single()
            .measure(constraints)
        val scrimPlaceable = scrim.single()
            .measure(Constraints.fixed(width = constraints.maxWidth, height = scrimHeight))

        val currentHeight = shrunkHeight + ((heightDelta + scrimHeight) * animProgress).roundToInt()
        layout(constraints.maxWidth, currentHeight) {
            actualPlaceable.place(0, 0)

            val scrimY = currentHeight - scrimHeight
            scrimPlaceable.place(0, scrimY)
        }
    }
}

private fun String.toCollapsedNovelMarkdown(): String {
    return replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(novelMarkdownImageRegex, "")
        .replace(novelHtmlImageRegex, "")
        .replace(novelWhitespaceLineRegex, "\n")
        .lines()
        .joinToString("  \n") { it.trimEnd() }
        .trim()
}
