package eu.kanade.tachiyomi.ui.download

import android.view.LayoutInflater
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.NestedMenuItem
import eu.kanade.presentation.theme.colorscheme.AndroidViewColorScheme
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.ocr.OcrQueueItem
import eu.kanade.tachiyomi.data.ocr.OcrQueueStatus
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.i18n.MR
import tachiyomi.i18n.ank.AMR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import kotlin.math.roundToInt

object DownloadQueueScreen : Screen() {
    @Suppress("unused")
    private fun readResolve(): Any = DownloadQueueScreen

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val screenModel = rememberScreenModel {
            DownloadQueueScreenModel(
                // KMK -->
                navigator = navigator,
                // KMK <--
            )
        }
        val downloadList by screenModel.state.collectAsState()
        val ocrQueue by screenModel.ocrQueueState.collectAsState()
        val downloadCount = downloadList.sumOf { item ->
            when (item) {
                is DownloadHeaderItem -> item.subItems.size
                is AnimeDownloadHeaderItem -> item.subItems.size
                else -> 1
            }
        }
        val hasMangaDownloads = downloadList.any { it is DownloadHeaderItem }
        val hasAnimeDownloads = downloadList.any { it is AnimeDownloadHeaderItem }

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
        var fabExpanded by remember { mutableStateOf(true) }
        val nestedScrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    fabExpanded = available.y >= 0
                    return scrollBehavior.nestedScrollConnection.onPreScroll(available, source)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    return scrollBehavior.nestedScrollConnection.onPostScroll(consumed, available, source)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPreFling(available)
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPostFling(consumed, available)
                }
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    titleContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(MR.strings.label_download_queue),
                                maxLines = 1,
                                modifier = Modifier.weight(1f, false),
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (downloadCount > 0) {
                                val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
                                Pill(
                                    text = "$downloadCount",
                                    modifier = Modifier.padding(start = 4.dp),
                                    color = MaterialTheme.colorScheme.onBackground
                                        .copy(alpha = pillAlpha),
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    },
                    navigateUp = navigator::pop,
                    actions = {
                        if (downloadList.isNotEmpty()) {
                            var sortExpanded by remember { mutableStateOf(false) }
                            val onDismissRequest = { sortExpanded = false }
                            DropdownMenu(
                                expanded = sortExpanded,
                                onDismissRequest = onDismissRequest,
                            ) {
                                NestedMenuItem(
                                    text = { Text(text = stringResource(MR.strings.action_order_by_upload_date)) },
                                    children = { closeMenu ->
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_newest)) },
                                            onClick = {
                                                screenModel.reorderQueue(
                                                    { it.download.chapter.dateUpload },
                                                    true,
                                                )
                                                closeMenu()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_oldest)) },
                                            onClick = {
                                                screenModel.reorderQueue(
                                                    { it.download.chapter.dateUpload },
                                                    false,
                                                )
                                                closeMenu()
                                            },
                                        )
                                    },
                                )
                                NestedMenuItem(
                                    text = { Text(text = stringResource(MR.strings.action_order_by_chapter_number)) },
                                    children = { closeMenu ->
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_asc)) },
                                            onClick = {
                                                screenModel.reorderQueue(
                                                    { it.download.chapter.chapterNumber },
                                                    false,
                                                )
                                                closeMenu()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_desc)) },
                                            onClick = {
                                                screenModel.reorderQueue(
                                                    { it.download.chapter.chapterNumber },
                                                    true,
                                                )
                                                closeMenu()
                                            },
                                        )
                                    },
                                )
                                // AY -->
                                // The two queues live in separate header types, so the anime queue
                                // needs its own sort entries or the menu does nothing for it.
                                if (hasAnimeDownloads) {
                                    NestedMenuItem(
                                        text = { Text(text = stringResource(MR.strings.action_order_by_upload_date)) },
                                        children = { closeMenu ->
                                            DropdownMenuItem(
                                                text = { Text(text = stringResource(MR.strings.action_newest)) },
                                                onClick = {
                                                    screenModel.reorderAnimeQueue(
                                                        { it.download.episode.dateUpload },
                                                        true,
                                                    )
                                                    closeMenu()
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(text = stringResource(MR.strings.action_oldest)) },
                                                onClick = {
                                                    screenModel.reorderAnimeQueue(
                                                        { it.download.episode.dateUpload },
                                                        false,
                                                    )
                                                    closeMenu()
                                                },
                                            )
                                        },
                                    )
                                    NestedMenuItem(
                                        text = { Text(text = stringResource(AMR.strings.action_order_by_episode_number)) },
                                        children = { closeMenu ->
                                            DropdownMenuItem(
                                                text = { Text(text = stringResource(MR.strings.action_asc)) },
                                                onClick = {
                                                    screenModel.reorderAnimeQueue(
                                                        { it.download.episode.episodeNumber },
                                                        false,
                                                    )
                                                    closeMenu()
                                                },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(text = stringResource(MR.strings.action_desc)) },
                                                onClick = {
                                                    screenModel.reorderAnimeQueue(
                                                        { it.download.episode.episodeNumber },
                                                        true,
                                                    )
                                                    closeMenu()
                                                },
                                            )
                                        },
                                    )
                                }
                                // <-- AY
                            }

                            val actions = buildList<AppBar.AppBarAction> {
                                if (hasMangaDownloads || hasAnimeDownloads) {
                                    add(
                                        AppBar.Action(
                                            title = stringResource(MR.strings.action_sort),
                                            icon = Icons.AutoMirrored.Outlined.Sort,
                                            onClick = { sortExpanded = true },
                                        ),
                                    )
                                }
                                add(
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_cancel_all),
                                        onClick = { screenModel.clearQueue() },
                                    ),
                                )
                            }

                            AppBarActions(
                                persistentListOf(
                                    *actions.toTypedArray(),
                                ),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                val isRunning by screenModel.isDownloaderRunning.collectAsState()
                SmallExtendedFloatingActionButton(
                    text = {
                        val id = if (isRunning) {
                            MR.strings.action_pause
                        } else {
                            MR.strings.action_resume
                        }
                        Text(text = stringResource(id))
                    },
                    icon = {
                        val icon = if (isRunning) {
                            Icons.Outlined.Pause
                        } else {
                            Icons.Filled.PlayArrow
                        }
                        Icon(imageVector = icon, contentDescription = null)
                    },
                    onClick = {
                        if (isRunning) {
                            screenModel.pauseDownloads()
                        } else {
                            screenModel.startDownloads()
                        }
                    },
                    expanded = fabExpanded,
                    modifier = Modifier.animateFloatingActionButton(
                        visible = downloadCount > 0,
                        alignment = Alignment.BottomEnd,
                    ),
                )
            },
        ) { contentPadding ->
            if (downloadList.isEmpty() && ocrQueue.isEmpty()) {
                EmptyScreen(
                    stringRes = MR.strings.information_no_downloads,
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }

            val density = LocalDensity.current
            val layoutDirection = LocalLayoutDirection.current
            val left = with(density) { contentPadding.calculateLeftPadding(layoutDirection).toPx().roundToInt() }
            val top = with(density) { contentPadding.calculateTopPadding().toPx().roundToInt() }
            val right = with(density) { contentPadding.calculateRightPadding(layoutDirection).toPx().roundToInt() }
            val bottom = with(density) { contentPadding.calculateBottomPadding().toPx().roundToInt() }

            // KMK -->
            val colorScheme = AndroidViewColorScheme(MaterialTheme.colorScheme)
            // KMK <--

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(nestedScrollConnection),
            ) {
                if (ocrQueue.isNotEmpty()) {
                    val maxOcrHeight = LocalConfiguration.current.screenHeightDp.dp / 2
                    OcrQueueSection(
                        ocrQueue = ocrQueue,
                        onCancelClick = { screenModel.cancelOcr(it) },
                        onRetryClick = { screenModel.retryOcr(it) },
                        modifier = Modifier
                            .then(
                                if (downloadList.isEmpty()) {
                                    Modifier.weight(1f)
                                } else {
                                    Modifier.heightIn(max = maxOcrHeight)
                                },
                            )
                            .padding(
                                start = with(density) { left.toDp() },
                                top = with(density) { top.toDp() },
                                end = with(density) { right.toDp() },
                            ),
                    )
                }

                if (ocrQueue.isNotEmpty() && downloadList.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = with(density) { left.toDp() } + 16.dp,
                                top = 16.dp,
                                end = with(density) { right.toDp() } + 16.dp,
                                bottom = 8.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(MR.strings.label_download_queue),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Pill(
                            text = "$downloadCount",
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            fontSize = 12.sp,
                        )
                    }
                }

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (downloadList.isNotEmpty()) {
                                Modifier.weight(1f).clipToBounds()
                            } else {
                                Modifier.height(0.dp)
                            },
                        ),
                    factory = { context ->
                        screenModel.controllerBinding = DownloadListBinding.inflate(LayoutInflater.from(context))
                        screenModel.adapter = DownloadAdapter(
                            screenModel.listener,
                            // KMK -->
                            colorScheme,
                            // KMK <--
                        )
                        screenModel.controllerBinding.root.adapter = screenModel.adapter
                        screenModel.adapter?.isHandleDragEnabled = true
                        screenModel.controllerBinding.root.layoutManager = LinearLayoutManager(context)

                        ViewCompat.setNestedScrollingEnabled(screenModel.controllerBinding.root, true)

                        scope.launchUI {
                            screenModel.getDownloadStatusFlow()
                                .collect(screenModel::onStatusChange)
                        }
                        scope.launchUI {
                            screenModel.getDownloadProgressFlow()
                                .collect(screenModel::onUpdateDownloadedPages)
                        }
                        scope.launchUI {
                            screenModel.getAnimeStatusFlow()
                                .collect(screenModel::onAnimeStatusChange)
                        }
                        scope.launchUI {
                            screenModel.getAnimeProgressFlow()
                                .collect(screenModel::onAnimeProgressChange)
                        }

                        screenModel.controllerBinding.root
                    },
                    update = {
                        screenModel.controllerBinding.root
                            .updatePadding(
                                left = left,
                                top = if (ocrQueue.isEmpty()) top else 0,
                                right = right,
                                bottom = bottom,
                            )

                        screenModel.adapter?.updateDataSet(downloadList)
                    },
                )
            }
        }
    }
}

@Composable
private fun OcrQueueSection(
    ocrQueue: List<OcrQueueItem>,
    onCancelClick: (Long) -> Unit,
    onRetryClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(MR.strings.ocr_processing),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Pill(
                text = "${ocrQueue.size}",
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                fontSize = 12.sp,
            )
        }

        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) {
            ocrQueue.forEach { item ->
                OcrQueueItemRow(
                    item = item,
                    onCancelClick = { onCancelClick(item.chapter.id) },
                    onRetryClick = { onRetryClick(item.chapter.id) },
                )
            }
        }
    }
}

@Composable
private fun OcrQueueItemRow(
    item: OcrQueueItem,
    onCancelClick: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            modifier = Modifier.padding(end = 8.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.manga.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.chapter.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val statusText = when (item.status) {
                OcrQueueStatus.PENDING -> stringResource(MR.strings.ocr_status_pending)
                OcrQueueStatus.WAITING_DOWNLOAD -> stringResource(MR.strings.ocr_waiting_download)
                OcrQueueStatus.PROCESSING -> {
                    if (item.totalPages > 0) {
                        "${item.currentPage}/${item.totalPages}"
                    } else {
                        stringResource(MR.strings.ocr_status_processing)
                    }
                }
                OcrQueueStatus.COMPLETED -> stringResource(MR.strings.ocr_ready)
                OcrQueueStatus.ERROR -> stringResource(MR.strings.ocr_status_error)
                OcrQueueStatus.CANCELLED -> stringResource(MR.strings.cancelled)
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = when (item.status) {
                    OcrQueueStatus.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (item.status) {
                OcrQueueStatus.PENDING, OcrQueueStatus.WAITING_DOWNLOAD -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
                OcrQueueStatus.PROCESSING -> {
                    CircularProgressIndicator(
                        progress = { item.progress },
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
                    )
                }
                OcrQueueStatus.COMPLETED -> {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                OcrQueueStatus.ERROR -> {
                    IconButton(onClick = onRetryClick) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = stringResource(MR.strings.action_retry),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                OcrQueueStatus.CANCELLED -> {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (item.status in
            listOf(
                OcrQueueStatus.PENDING,
                OcrQueueStatus.WAITING_DOWNLOAD,
                OcrQueueStatus.PROCESSING,
                OcrQueueStatus.ERROR,
            )
        ) {
            IconButton(onClick = onCancelClick) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(MR.strings.action_cancel),
                )
            }
        }
    }
}
