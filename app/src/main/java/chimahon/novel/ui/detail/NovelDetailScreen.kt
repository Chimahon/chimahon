package chimahon.novel.ui.detail

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAll
import androidx.compose.ui.util.fastAny
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import chimahon.novel.ui.browse.BrowseNovelSourceScreen
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.canopus.chimareader.ui.reader.NovelReaderActivity
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.sourcenovel.HttpNovelSource
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.NovelsPageSource
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.launch
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class NovelDetailScreen(
    private val novel: SNNovel,
    private val sourceId: Long,
) : Screen() {

    companion object {
        fun fromSourceId(novel: SNNovel, sourceId: Long): NovelDetailScreen? {
            return NovelDetailScreen(novel, sourceId)
        }
    }

    @Composable
    override fun Content() {
        val sourceManager = remember { Injekt.get<chimahon.novel.manager.NovelSourceManager>() }
        val sources by sourceManager.catalogueSources.collectAsState(initial = emptyList())
        val source = remember(sources, sourceId) { sources.find { it.id == sourceId } }
        if (source == null) {
            LoadingScreen()
            return
        }
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val screenModel = rememberScreenModel { NovelDetailScreenModel(novel, sourceId) }
        val state by screenModel.state.collectAsState()
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    scope.launch { screenModel.resume() }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val chapterListState = rememberLazyListState()
        val chapters = state.chapters
        val isAnySelected = state.selectionMode
        val hasUnread = remember(chapters) { chapters.fastAny { !it.isRead } }
        val selectedChapterCount = remember(chapters, state.selectedChapters) {
            chapters.count { it.id in state.selectedChapters }
        }

        BackHandler(enabled = isAnySelected) {
            screenModel.clearSelection()
        }

        Scaffold(
            topBar = {
                if (isAnySelected) {
                    TopAppBar(
                        title = { Text("$selectedChapterCount selected") },
                        navigationIcon = {
                            IconButton(onClick = { screenModel.clearSelection() }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close selection")
                            }
                        },
                        actions = {
                            TextButton(onClick = { screenModel.selectAll() }) {
                                Text("Select all")
                            }
                            TextButton(onClick = { screenModel.invertSelection() }) {
                                Text("Invert")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                } else {
                    TopAppBar(
                        title = { Text(state.novel.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(onClick = { navigator.pop() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            val httpSource = source as? HttpNovelSource
                            if (!state.isLoading && httpSource != null) {
                                IconButton(
                                    onClick = {
                                        runCatching { httpSource.getNovelUrl(state.novel) }
                                            .getOrNull()
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { url ->
                                                navigator.push(
                                                    WebViewScreen(
                                                        url = url,
                                                        initialTitle = state.novel.title,
                                                    ),
                                                )
                                            }
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.Public,
                                        contentDescription = stringResource(MR.strings.action_open_in_web_view),
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
            },
            bottomBar = {
                val selectedItems = remember(chapters, state.selectedChapters) {
                    chapters.filter { it.id in state.selectedChapters }
                }
                MangaBottomActionMenu(
                    visible = isAnySelected,
                    onBookmarkClicked = {
                        screenModel.markSelectedChaptersBookmark(true)
                    }.takeIf { selectedItems.fastAny { !it.isBookmarked } },
                    onRemoveBookmarkClicked = {
                        screenModel.markSelectedChaptersBookmark(false)
                    }.takeIf { selectedItems.fastAll { it.isBookmarked } },
                    onMarkAsReadClicked = {
                        screenModel.markSelectedChaptersRead(true)
                    }.takeIf { selectedItems.fastAny { !it.isRead } },
                    onMarkAsUnreadClicked = {
                        screenModel.markSelectedChaptersRead(false)
                    }.takeIf { selectedItems.fastAny { it.isRead || it.lastPageRead > 0L } },
                )
            },
            floatingActionButton = {
                if (hasUnread && !isAnySelected) {
                    SmallFloatingActionButton(
                        onClick = {
                            val next = screenModel.getNextUnreadChapter()
                            if (next != null) {
                                openChapter(context, source, state.novel, next, chapters, screenModel, scope)
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(MR.strings.action_start))
                    }
                }
            },
        ) { contentPadding ->
            when {
                state.isLoading -> LoadingScreen(modifier = Modifier.padding(contentPadding))
                else -> {
                    PullRefresh(
                        refreshing = state.isRefreshingData,
                        onRefresh = { screenModel.refresh() },
                        enabled = !isAnySelected,
                        indicatorPadding = PaddingValues(top = contentPadding.calculateTopPadding()),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = chapterListState,
                            contentPadding = contentPadding,
                        ) {
                            item(key = "novel_header") {
                                NovelHeader(
                                    novel = state.novel,
                                    source = source,
                                    onSourceClick = if (source is NovelsPageSource) {
                                        { navigator.push(BrowseNovelSourceScreen(null, sourceId)) }
                                    } else {
                                        null
                                    },
                                )
                            }

                            item(key = "novel_action_row") {
                                NovelActionRow(
                                    isFavorite = state.isFavorite,
                                    onToggleFavorite = screenModel::toggleFavorite,
                                    onWebViewClick = {
                                        val httpSource = source as? HttpNovelSource
                                        if (httpSource != null) {
                                            runCatching { httpSource.getNovelUrl(state.novel) }
                                                .getOrNull()
                                                ?.takeIf { it.isNotBlank() }
                                                ?.let { url ->
                                                    navigator.push(
                                                        WebViewScreen(
                                                            url = url,
                                                            initialTitle = state.novel.title,
                                                        ),
                                                    )
                                                }
                                        }
                                    },
                                )
                            }

                            val description = state.novel.description
                            if (!description.isNullOrBlank()) {
                                item(key = "novel_description") {
                                    var expanded by rememberSaveable { mutableStateOf(false) }
                                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                        Text(
                                            text = description,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = if (expanded) Int.MAX_VALUE else 4,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (description.length > 150) {
                                            TextButton(onClick = { expanded = !expanded }) {
                                                Text(
                                                    text = if (expanded) "Show less" else "Show more",
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            val genres = state.novel.genre
                            if (!genres.isNullOrBlank()) {
                                item(key = "novel_genres") {
                                    val genreList = genres.split(",").map { it.trim() }.filter { it.isNotBlank() }
                                    FlowRow(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        genreList.forEach { genre ->
                                            Surface(
                                                shape = RoundedCornerShape(16.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                            ) {
                                                Text(
                                                    text = genre,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            state.detailError?.takeIf { it.isNotBlank() }?.let { error ->
                                item(key = "novel_detail_error") {
                                    Text(
                                        text = error,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                            }

                            item(key = "novel_chapter_header") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "Chapters (${chapters.size})",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onBackground,
                                    )
                                }
                                HorizontalDivider()
                            }

                            if (chapters.isEmpty()) {
                                item(key = "novel_no_chapters") {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = state.chapterError?.takeIf { it.isNotBlank() } ?: "No chapters",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (state.chapterError != null) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                }
                            } else {
                                items(chapters, key = { "ch_${it.snChapter.url}" }) { item ->
                                    val readProgressText = if (item.lastPageRead > 0 && !item.isRead) {
                                        "${item.lastPageRead} chars"
                                    } else null

                                    MangaChapterListItem(
                                        title = item.snChapter.name,
                                        date = if (item.snChapter.date_upload > 0) {
                                            relativeDateText(item.snChapter.date_upload)
                                        } else null,
                                        readProgress = readProgressText,
                                        scanlator = item.snChapter.scanlator,
                                        sourceName = null,
                                        read = item.isRead,
                                        bookmark = item.isBookmarked,
                                        selected = item.id in state.selectedChapters,
                                        downloadIndicatorEnabled = false,
                                        downloadStateProvider = { Download.State.NOT_DOWNLOADED },
                                        downloadProgressProvider = { 0 },
                                        chapterSwipeStartAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                                        chapterSwipeEndAction = LibraryPreferences.ChapterSwipeAction.Disabled,
                                        onLongClick = {
                                            if (state.isFavorite) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                screenModel.toggleChapterSelection(item.id)
                                            }
                                        },
                                        onClick = {
                                            if (state.selectionMode) {
                                                screenModel.toggleChapterSelection(item.id)
                                            } else {
                                                openChapter(context, source, state.novel, item, chapters, screenModel, scope)
                                            }
                                        },
                                        onDownloadClick = null,
                                        onChapterSwipe = {},
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NovelHeader(
    novel: SNNovel,
    source: NovelSource,
    onSourceClick: (() -> Unit)?,
) {
        Box(modifier = Modifier.fillMaxWidth()) {
        val thumbnailUrl = novel.thumbnail_url
        val bgColor = MaterialTheme.colorScheme.background
        val tintColor = MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.4f)
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
                                colors = listOf(
                                    Color.Transparent,
                                    bgColor,
                                ),
                                startY = size.height / 2,
                            ),
                        )
                    }
                    .background(tintColor)
                    .blur(7.dp)
                    .alpha(0.2f),
            )
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            if (!thumbnailUrl.isNullOrBlank()) {
                MangaCover.Book(
                    data = ImageRequest.Builder(LocalContext.current)
                        .data(thumbnailUrl)
                        .crossfade(true)
                        .build(),
                    modifier = Modifier
                        .width(120.dp)
                        .aspectRatio(2f / 3f),
                    contentDescription = novel.title,
                )
                Spacer(Modifier.width(16.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = novel.title,
                    style = MaterialTheme.typography.headlineSmall,
                )
                val author = novel.author
                if (!author.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                val statusText = when (novel.status) {
                    SNNovel.ONGOING -> "Ongoing"
                    SNNovel.COMPLETED -> "Completed"
                    SNNovel.LICENSED -> "Licensed"
                    SNNovel.PUBLISHING_FINISHED -> "Publishing Finished"
                    SNNovel.CANCELLED -> "Cancelled"
                    SNNovel.ON_HIATUS -> "On Hiatus"
                    else -> "Unknown"
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (onSourceClick != null) {
                        Modifier.clickable(onClick = onSourceClick)
                    } else {
                        Modifier
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NovelActionRow(
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onWebViewClick: (() -> Unit)?,
) {
    val defaultActionButtonColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onToggleFavorite,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isFavorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(if (isFavorite) "In Library" else "Add to Library")
        }
        if (onWebViewClick != null) {
            OutlinedButton(
                onClick = onWebViewClick,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("Web View")
            }
        }
    }
}

private fun openChapter(
    context: Context,
    source: NovelSource,
    novel: SNNovel,
    item: NovelChapterItem,
    chapters: List<NovelChapterItem>,
    screenModel: NovelDetailScreenModel,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    scope.launch {
        try {
            val chapterIndex = chapters.indexOf(item)
            if (chapterIndex >= 0 && chapters.size > 1) {
                val bookDir = SourceChapterBookBuilder.build(
                    context = context,
                    source = source,
                    novel = novel,
                    chapters = chapters.map { it.snChapter },
                    startChapterIndex = chapterIndex,
                )
                NovelReaderActivity.launch(context, bookDir)
            } else {
                val bookDir = SourceChapterBookBuilder.buildSingleChapter(
                    context = context,
                    source = source,
                    novel = novel,
                    chapter = item.snChapter,
                )
                NovelReaderActivity.launch(context, bookDir)
            }
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Failed to open chapter: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
