package eu.kanade.tachiyomi.ui.download

import android.view.MenuItem
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.Navigator
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.animedownload.AnimeDownloadManager
import eu.kanade.tachiyomi.data.animedownload.model.AnimeDownload
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.ocr.OcrManager
import eu.kanade.tachiyomi.data.ocr.OcrQueueItem
import eu.kanade.tachiyomi.data.ocr.isActionable
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DownloadQueueScreenModel(
    private val downloadManager: DownloadManager = Injekt.get(),
    private val animeDownloadManager: AnimeDownloadManager = Injekt.get(),
    // KMK -->
    private val navigator: Navigator? = null,
    // KMK <--
    // Chimahon: OCR manager
    private val ocrManager: OcrManager = Injekt.get(),
) : ScreenModel {

    private val _state = MutableStateFlow(emptyList<AbstractFlexibleItem<*>>())
    val state = _state.asStateFlow()

    // OCR queue state
    val ocrQueueState: StateFlow<List<OcrQueueItem>> = ocrManager.queueState

    lateinit var controllerBinding: DownloadListBinding

    var adapter: DownloadAdapter? = null

    private val progressJobs = mutableMapOf<Download, Job>()

    val listener = object : DownloadAdapter.DownloadItemListener {
        override fun onItemReleased(position: Int) {
            val adapter = adapter ?: return
            val mangaDownloads = mutableListOf<Download>()
            val animeDownloads = mutableListOf<AnimeDownload>()
            adapter.headerItems.forEach { header ->
                when (header) {
                    is DownloadHeaderItem -> {
                        mangaDownloads += adapter.getSectionItems(header)
                            .filterIsInstance<DownloadItem>()
                            .map { it.download }
                    }
                    is AnimeDownloadHeaderItem -> {
                        animeDownloads += adapter.getSectionItems(header)
                            .filterIsInstance<AnimeDownloadItem>()
                            .map { it.download }
                    }
                }
            }
            if (mangaDownloads.isNotEmpty()) reorder(mangaDownloads)
            if (animeDownloads.isNotEmpty()) reorderAnime(animeDownloads)
        }

        override fun onMenuItemClick(position: Int, menuItem: MenuItem) {
            val item = adapter?.getItem(position) ?: return
            if (item is DownloadItem) {
                when (menuItem.itemId) {
                    R.id.move_to_top, R.id.move_to_bottom -> {
                        val headerItems = adapter?.headerItems ?: return
                        val newDownloads = mutableListOf<Download>()
                        headerItems.forEach { headerItem ->
                            if (headerItem is DownloadHeaderItem) {
                                if (headerItem == item.header) {
                                    headerItem.removeSubItem(item)
                                    if (menuItem.itemId == R.id.move_to_top) {
                                        headerItem.addSubItem(0, item)
                                    } else {
                                        headerItem.addSubItem(item)
                                    }
                                }
                                newDownloads.addAll(headerItem.subItems.map { it.download })
                            }
                        }
                        reorder(newDownloads)
                    }
                    R.id.move_to_top_series, R.id.move_to_bottom_series -> {
                        val (selectedSeries, otherSeries) = adapter?.currentItems
                            ?.filterIsInstance<DownloadItem>()
                            ?.map(DownloadItem::download)
                            ?.partition { item.download.manga.id == it.manga.id }
                            ?: Pair(emptyList(), emptyList())
                        if (menuItem.itemId == R.id.move_to_top_series) {
                            reorder(selectedSeries + otherSeries)
                        } else {
                            reorder(otherSeries + selectedSeries)
                        }
                    }
                    R.id.cancel_download -> {
                        cancel(listOf(item.download))
                    }
                    R.id.cancel_series -> {
                        val allDownloadsForSeries = adapter?.currentItems
                            ?.filterIsInstance<DownloadItem>()
                            ?.filter { item.download.manga.id == it.download.manga.id }
                            ?.map(DownloadItem::download)
                        if (!allDownloadsForSeries.isNullOrEmpty()) {
                            cancel(allDownloadsForSeries)
                        }
                    }
                    R.id.show_manga -> {
                        val mangaId = item.download.manga.id
                        showManga(mangaId = mangaId)
                    }
                }
            } else if (item is AnimeDownloadItem) {
                when (menuItem.itemId) {
                    R.id.move_to_top, R.id.move_to_bottom -> {
                        val headerItems = adapter?.headerItems ?: return
                        val newDownloads = mutableListOf<AnimeDownload>()
                        headerItems.forEach { headerItem ->
                            if (headerItem is AnimeDownloadHeaderItem) {
                                if (headerItem == item.header) {
                                    headerItem.removeSubItem(item)
                                    if (menuItem.itemId == R.id.move_to_top) {
                                        headerItem.addSubItem(0, item)
                                    } else {
                                        headerItem.addSubItem(item)
                                    }
                                }
                                newDownloads.addAll(headerItem.subItems.map { it.download })
                            }
                        }
                        reorderAnime(newDownloads)
                    }
                    R.id.move_to_top_series, R.id.move_to_bottom_series -> {
                        val (selectedSeries, otherSeries) = adapter?.currentItems
                            ?.filterIsInstance<AnimeDownloadItem>()
                            ?.map(AnimeDownloadItem::download)
                            ?.partition { item.download.anime.id == it.anime.id }
                            ?: Pair(emptyList(), emptyList())
                        if (menuItem.itemId == R.id.move_to_top_series) {
                            reorderAnime(selectedSeries + otherSeries)
                        } else {
                            reorderAnime(otherSeries + selectedSeries)
                        }
                    }
                    R.id.cancel_download -> {
                        cancelAnimeDownload(item.download)
                    }
                    R.id.cancel_series -> {
                        val allDownloadsForSeries = adapter?.currentItems
                            ?.filterIsInstance<AnimeDownloadItem>()
                            ?.filter { item.download.anime.id == it.download.anime.id }
                            ?.map(AnimeDownloadItem::download)
                        if (!allDownloadsForSeries.isNullOrEmpty()) {
                            animeDownloadManager.cancelQueuedDownloads(allDownloadsForSeries)
                        }
                    }
                    R.id.show_anime -> {
                        showAnime(item.download.anime.id)
                    }
                }
            }
        }
    }

    init {
        screenModelScope.launch {
            combine(
                downloadManager.queueState,
                animeDownloadManager.queueState,
            ) { mangaDownloads, animeDownloads ->
                buildList<AbstractFlexibleItem<*>> {
                    mangaDownloads
                        .groupBy { it.source }
                        .forEach { entry ->
                            val header = DownloadHeaderItem(entry.key.id, entry.key.name, entry.value.size)
                            header.addSubItems(0, entry.value.map { DownloadItem(it, header) })
                            add(header)
                        }
                    animeDownloads
                        .groupBy { it.source }
                        .forEach { entry ->
                            val header = AnimeDownloadHeaderItem(entry.key.id, entry.key.name, entry.value.size)
                            header.addSubItems(0, entry.value.map { AnimeDownloadItem(it, header) })
                            add(header)
                        }
                }
            }.collect { newList -> _state.update { newList } }
        }
    }

    override fun onDispose() {
        for (job in progressJobs.values) {
            job.cancel()
        }
        progressJobs.clear()
        adapter = null
    }

    val isDownloaderRunning = combine(
        downloadManager.isDownloaderRunning,
        animeDownloadManager.isDownloaderRunning,
    ) { manga, anime -> manga || anime }
        .stateIn(screenModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isOcrRunning: StateFlow<Boolean> = ocrManager.queueState
        .map { queue -> queue.any { it.status.isActionable() } }
        .stateIn(screenModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun getDownloadStatusFlow() = downloadManager.statusFlow()
    fun getDownloadProgressFlow() = downloadManager.progressFlow()
    fun getAnimeStatusFlow() = animeDownloadManager.statusFlow()
    fun getAnimeProgressFlow() = animeDownloadManager.progressFlow()

    fun startDownloads() {
        downloadManager.startDownloads()
        animeDownloadManager.startDownloads()
    }

    fun pauseDownloads() {
        downloadManager.pauseDownloads()
        animeDownloadManager.pauseDownloads()
    }

    fun clearQueue() {
        downloadManager.clearQueue()
        animeDownloadManager.clearQueue()
    }

    fun reorder(downloads: List<Download>) {
        downloadManager.reorderQueue(downloads)
    }

    fun reorderAnime(downloads: List<AnimeDownload>) {
        animeDownloadManager.reorderQueue(downloads)
    }

    fun cancel(downloads: List<Download>) {
        downloadManager.cancelQueuedDownloads(downloads)
    }

    // KMK -->
    fun showManga(mangaId: Long) {
        navigator?.push(MangaScreen(mangaId))
    }

    fun showAnime(animeId: Long) {
        navigator?.push(AnimeScreen(animeId))
    }
    // KMK <--

    fun cancelAnimeDownload(download: AnimeDownload) {
        animeDownloadManager.cancelQueuedDownloads(listOf(download))
    }

    // Chimahon: OCR methods
    fun cancelOcr(chapterId: Long) {
        screenModelScope.launch {
            ocrManager.cancelChapter(chapterId)
        }
    }

    fun retryOcr(chapterId: Long) {
        screenModelScope.launch {
            ocrManager.retryChapter(chapterId)
        }
    }

    fun <R : Comparable<R>> reorderQueue(selector: (DownloadItem) -> R, reverse: Boolean = false) {
        val adapter = adapter ?: return
        val newDownloads = mutableListOf<Download>()
        adapter.headerItems.forEach { headerItem ->
            if (headerItem !is DownloadHeaderItem) return@forEach
            headerItem.subItems = headerItem.subItems.sortedBy(selector).toMutableList().apply {
                if (reverse) {
                    reverse()
                }
            }
            newDownloads.addAll(headerItem.subItems.map { it.download })
        }
        reorder(newDownloads)
    }

    /**
     * Anime counterpart of [reorderQueue]. The two queues are kept in separate header types, so
     * without this the sort menu silently did nothing for anime downloads.
     */
    fun <R : Comparable<R>> reorderAnimeQueue(selector: (AnimeDownloadItem) -> R, reverse: Boolean = false) {
        val adapter = adapter ?: return
        val newDownloads = mutableListOf<AnimeDownload>()
        adapter.headerItems.forEach { headerItem ->
            if (headerItem !is AnimeDownloadHeaderItem) return@forEach
            headerItem.subItems = headerItem.subItems.sortedBy(selector).toMutableList().apply {
                if (reverse) {
                    reverse()
                }
            }
            newDownloads.addAll(headerItem.subItems.map { it.download })
        }
        reorderAnime(newDownloads)
    }

    fun onStatusChange(download: Download) {
        when (download.status) {
            Download.State.DOWNLOADING -> {
                launchProgressJob(download)
                onUpdateDownloadedPages(download)
            }
            Download.State.DOWNLOADED -> {
                cancelProgressJob(download)
                onUpdateProgress(download)
                onUpdateDownloadedPages(download)
            }
            Download.State.ERROR -> cancelProgressJob(download)
            else -> { /* unused */ }
        }
    }

    private fun launchProgressJob(download: Download) {
        val job = screenModelScope.launch {
            while (download.pages == null) {
                delay(50)
            }

            val progressFlows = download.pages!!.map(Page::progressFlow)
            combine(progressFlows, Array<Int>::sum)
                .distinctUntilChanged()
                .debounce(50)
                .collectLatest {
                    onUpdateProgress(download)
                }
        }

        progressJobs.remove(download)?.cancel()
        progressJobs[download] = job
    }

    private fun cancelProgressJob(download: Download) {
        progressJobs.remove(download)?.cancel()
    }

    private fun onUpdateProgress(download: Download) {
        getHolder(download)?.notifyProgress()
    }

    fun onUpdateDownloadedPages(download: Download) {
        getHolder(download)?.notifyDownloadedPages()
    }

    private fun getHolder(download: Download): DownloadHolder? {
        return controllerBinding.root.findViewHolderForItemId(download.chapter.id) as? DownloadHolder
    }

    fun getAnimeHolder(episodeId: Long): AnimeDownloadHolder? {
        return controllerBinding.root.findViewHolderForItemId(episodeId) as? AnimeDownloadHolder
    }

    fun onAnimeStatusChange(download: AnimeDownload) {
        getAnimeHolder(download.episode.id)?.updateProgress(download)
    }

    fun onAnimeProgressChange(download: AnimeDownload) {
        getAnimeHolder(download.episode.id)?.updateProgress(download)
    }
}
