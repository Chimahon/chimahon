package chimahon.novel.ui.reader

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import chimahon.novel.data.BookMetadata
import chimahon.novel.data.BookStorage
import chimahon.novel.data.Bookmark
import chimahon.novel.data.CustomReaderTheme
import chimahon.novel.data.FileNames
import chimahon.novel.data.FontManager
import chimahon.novel.data.NovelReaderSettings
import chimahon.novel.data.Statistics
import chimahon.novel.data.StatisticsAutostartMode
import chimahon.novel.data.Theme
import chimahon.novel.data.epub.EpubBook
import chimahon.novel.data.epub.SpineItemType
import chimahon.novel.data.epub.VirtualNovelBook
import chimahon.novel.reader.NovelChapterLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.model.NovelChapterUpdate
import tachiyomi.domain.novel.model.isNovelReadComplete
import tachiyomi.domain.novel.repository.NovelChapterRepository
import tachiyomi.domain.novel.repository.NovelHistoryRepository
import tachiyomi.domain.novel.repository.NovelRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

// ─── Commands ─────────────────────────────────────────────────────────────────

sealed interface WebViewCommand {
    data class LoadChapter(val url: String, val progress: Double) : WebViewCommand
    data class LoadChapterHtml(val url: String, val html: String, val progress: Double) : WebViewCommand
    data class JumpToFragment(val fragment: String) : WebViewCommand
    data class UpdateTextColor(val hex: String?) : WebViewCommand
    data class ChangeMode(val continuous: Boolean) : WebViewCommand
    data class ApplySettings(val settings: ReaderSettings) : WebViewCommand
    data class ChangeFocusMode(val focusMode: Boolean) : WebViewCommand
    data class Paginate(val forward: Boolean) : WebViewCommand
    data object ClearSelection : WebViewCommand
    data class HighlightSelection(val charCount: Int) : WebViewCommand
    data class GetSelectionRects(val charCount: Int, val startOffset: Int = 0) : WebViewCommand
}

data class ReaderSettings(
    val fontSize: Double = 18.0,
    val lineHeight: Double = 1.6,
    val characterSpacing: Double = 0.0,
    val paragraphSpacing: Double = 0.0,
    val horizontalPadding: Double = 10.0,
    val verticalPadding: Double = 10.0,
    val selectedFont: String = "System Serif",
    val fontUrl: String? = null, // Custom font file URL for @font-face
    val theme: String = "system", // "light", "dark", "sepia", "system"
    val backgroundColor: Int = 0xFFFFFFFF.toInt(),
    val textColor: Int = 0xFF000000.toInt(),
    val verticalWriting: Boolean = true,
    val justifyText: Boolean = false,
    val avoidPageBreak: Boolean = true,
    val hideFurigana: Boolean = false,
    val layoutAdvanced: Boolean = false,
    val tapZonePercent: Int = 20,
    val continuousMode: Boolean = false,
)

enum class EinkRefreshColor {
    BLACK,
    WHITE,
    WHITE_BLACK,
}

// ─── Bridge ───────────────────────────────────────────────────────────────────

class WebViewBridge {
    var chapterUrl: String? by mutableStateOf(null)
        private set
    var chapterTitle: String? by mutableStateOf(null)
        private set
    var progress: Double by mutableDoubleStateOf(0.0)
        private set
    val pendingCommands = mutableStateListOf<WebViewCommand>()

    fun send(command: WebViewCommand) {
        pendingCommands += command
    }

    fun updateState(url: String, progress: Double, title: String? = null) {
        chapterUrl = url
        chapterTitle = title
        this.progress = progress
    }

    fun updateProgress(progress: Double) {
        this.progress = progress
    }

    fun paginate(forward: Boolean) {
        send(WebViewCommand.Paginate(forward))
    }
}

// ─── Loader ───────────────────────────────────────────────────────────────────

class ReaderLoaderViewModel(
    context: Context,
    book: BookMetadata,
    private val openNovelId: Long? = null,
    private val novelRepository: tachiyomi.domain.novel.repository.NovelRepository = uy.kohesive.injekt.Injekt.get(),
    private val novelChapterRepository: tachiyomi.domain.novel.repository.NovelChapterRepository = uy.kohesive.injekt.Injekt.get(),
) {
    var document: EpubBook? = null
        private set

    val rootUrl: File? =
        book.folder?.let { BookStorage.getBookDirectory(context, it) }

    init {
        loadBook(book, context)
    }

    private fun loadBook(book: BookMetadata, context: Context) {
        val root = rootUrl ?: return
        // The EPUB parse never throws raw errors (e.g. container.xml) into
        // the UI: unparseable shells resolve to null and the screen shows its
        // generic open-failure message instead.
        document = loadVirtualBook(book, root, openNovelId)
            ?: runCatching { BookStorage.loadEpub(root) }.getOrNull()
    }

    /**
     * Source novels open without package files: spine/TOC come from the DB
     * chapter rows (same list detail shows), content fills per chapter
     * through the loader. Local books keep the file parse.
     *
     * Identity is the explicit novel id first — the transient [book]
     * metadata can lose its source linkage (synthetic/sidecar builds) while
     * the caller still knows the row. URL lookup stays as fallback.
     */
    private fun loadVirtualBook(book: BookMetadata, root: File, openNovelId: Long?): EpubBook? {
        if (book.novelSourceId == null && openNovelId == null) return null
        return try {
            // Loader init runs off-Main (produceState IO); blocking IO here
            // matches the surrounding init style.
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                val novel = openNovelId
                    ?.let { runCatching { novelRepository.getNovelById(it) }.getOrNull() }
                    ?: run {
                        val novelUrl = book.novelUrl ?: return@runBlocking null
                        val sourceId = book.novelSourceId ?: return@runBlocking null
                        runCatching { novelRepository.getNovelByUrlAndSourceId(novelUrl, sourceId) }.getOrNull()
                    } ?: return@runBlocking null
                // Local books always file-parse (their identity is the folder).
                if (novel.isLocal) return@runBlocking null
                val chapters = novelChapterRepository.getChaptersByNovelId(novel.id)
                    .sortedBy { it.chapterNumber }
                    .takeIf { it.isNotEmpty() } ?: return@runBlocking null
                VirtualNovelBook.fromChapters(
                    title = book.title,
                    author = book.author,
                    language = book.lang,
                    chapters = chapters.map { VirtualNovelBook.ChapterRef(title = it.name) },
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}

// ─── Reader ───────────────────────────────────────────────────────────────────

class ReaderViewModel(
    val document: EpubBook,
    val rootUrl: File,
    val settings: NovelReaderSettings,
    private val scope: CoroutineScope,
    private val openNovelId: Long? = null,
    private val openChapterIndex: Int? = null,
) {
    var index by mutableIntStateOf(0)
    var currentProgress by mutableDoubleStateOf(0.0)

    // Settings state
    var theme by mutableStateOf(Theme.SYSTEM)
    var fontSize by mutableDoubleStateOf(18.0)
    var lineHeight by mutableDoubleStateOf(1.6)
    var horizontalPadding by mutableDoubleStateOf(10.0)
    var verticalPadding by mutableDoubleStateOf(10.0)
    var selectedFont by mutableStateOf("System")
    var continuousMode by mutableStateOf(false)
    var customBackgroundColor by mutableIntStateOf(0xFFF2E2C9.toInt())
    var customTextColor by mutableIntStateOf(0xFF000000.toInt())
    var customThemes by mutableStateOf<List<CustomReaderTheme>>(emptyList())
    var verticalWriting by mutableStateOf(true)
    /**
     * Local imports skip the between-chapters transition: all content is on
     * disk so turns are instant and the overlay only adds friction. Source
     * novels (novelSourceId set, src_* cache books) keep it. Resolved once
     * here on IO; mirrors listLocalBooks/loadVirtualBook identity.
     */
    var isLocalBook by mutableStateOf(true)
        private set
    var characterSpacing by mutableDoubleStateOf(0.0)
    var paragraphSpacing by mutableDoubleStateOf(0.0)
    var justifyText by mutableStateOf(false)
    var avoidPageBreak by mutableStateOf(true)
    var hideFurigana by mutableStateOf(false)
    var layoutAdvanced by mutableStateOf(false)
    var tapZonePercent by mutableIntStateOf(20)
    var keepScreenOn by mutableStateOf(false)
    var systemLightSepia by mutableStateOf(false)
    var einkRefreshOnPageTurn by mutableStateOf(false)
    var einkRefreshOnScroll by mutableStateOf(false)
    var einkRefreshDurationMillis by mutableIntStateOf(100)
    var einkRefreshDelayMillis by mutableIntStateOf(0)
    var einkRefreshPageInterval by mutableIntStateOf(1)
    var einkRefreshColor by mutableStateOf("BLACK")
    var chapterCacheSizeMb by mutableIntStateOf(100)
    var statisticsAutostartMode by mutableStateOf(StatisticsAutostartMode.OFF)

    // All mutable state lives above init: init assignments can never hit a null delegate.
    // Same key the manga reader uses; null when the store is unreachable.
    private fun transitionPref(): tachiyomi.core.common.preference.Preference<Boolean>? = try {
        Injekt.get<tachiyomi.core.common.preference.PreferenceStore>()
            .getBoolean("always_show_chapter_transition", true)
    } catch (_: Exception) {
        null
    }

    var alwaysShowChapterTransition by mutableStateOf(transitionPref()?.get() ?: true)
        private set

    private val transitionPrefSync = scope.launch {
        try {
            transitionPref()?.changes()?.collect { alwaysShowChapterTransition = it }
        } catch (_: Exception) {
        }
    }

    var chapterTransition by mutableStateOf<NovelChapterTransition?>(null)
        private set

    // In-flight transition fill. Dismissing the overlay cancels it so a bailed
    // turn can never yank the chapter forward after the user left.
    private var transitionEnsureJob: Job? = null

    // Tracks statistics for current reading session
    var totalExploredCharCount by mutableIntStateOf(0)

    val accumulatedCharCounts = androidx.compose.runtime.mutableStateMapOf<Int, Int>()

    val totalCharacters: Int
        get() = accumulatedCharCounts[document.linearSpineItems.size] ?: 0

    val currentCharacter: Int
        get() = totalExploredCharCount

    val currentChapterEndCharacter: Int
        get() = accumulatedCharCounts.getOrDefault(index + 1, 0)

    var fullStatistics = mutableStateListOf<Statistics>()
    private var lastPersistTimeMs = System.currentTimeMillis()
    private var lastSavedChapterIndex = 0
    private var lastSavedProgress = 0.0
    private var lastSavedCharacterCount = 0

    lateinit var statisticsTracker: ReaderStatisticsTracker
    private var trackingLocked = false
    private var appBackgrounded = false
    /**
     * When the current chapter visit began. History is written once per
     * visit with the measured stay — never per progress tick.
     */
    private var chapterSessionStartMs: Long = 0L

    val bridge = WebViewBridge()
    val chapterCount = document.spine().items.size

    /** DB identity for this book (null when unregistered); resolved once at open. */
    private var openNovel: Novel? = null

    /**
     * Per-novel direction key (folder else row id, same shape as lookup
     * overrides); null when unregistered. Chosen once in init alongside
     * [openNovel].
     */
    private var novelDirectionKey: String? = null

    /** Japanese tag family (BCP47 + common EPUB `dc:language` spellings). */
    private fun isJapaneseLang(lang: String?): Boolean {
        val base = lang?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return false
        return base == "ja" || base == "jp" || base == "jpn" || base == "japanese"
    }

    /**
     * The reader talks to the novel repositories directly (resolved via
     * Injekt). Null standalone, in which case the sidecar is the only store
     * and every DB call below returns early. Declared above `init`, which
     * needs the repos synchronously during construction.
     */
    private val novelRepos: Triple<NovelRepository, NovelChapterRepository, NovelHistoryRepository>? =
        runCatching {
            Triple(
                Injekt.get<NovelRepository>(),
                Injekt.get<NovelChapterRepository>(),
                Injekt.get<NovelHistoryRepository>(),
            )
        }.getOrNull()

    /**
     * Returns true if the current chapter is an image-only page
     */
    val isCurrentChapterImageOnly: Boolean
        get() {
            val spineItem = document.linearSpineItems.getOrNull(index) ?: return false
            return spineItem.type == SpineItemType.IMAGE_ONLY
        }

    /**
     * Gets the image URL for the current chapter if it's image-only
     */
    val currentImageUrl: String?
        get() = document.getImageUrl(index)

    init {
        // Initialize state from settings (blocking first value for initial render)
        runBlocking(Dispatchers.IO) {
            theme = settings.theme.first()
            fontSize = settings.fontSize.first()
            lineHeight = settings.lineHeight.first()
            horizontalPadding = settings.horizontalPadding.first()
            verticalPadding = settings.verticalPadding.first()
            selectedFont = settings.selectedFont.first()
            continuousMode = settings.continuousMode.first()
            customBackgroundColor = settings.customBackgroundColor.first()
            customTextColor = settings.customTextColor.first()
            customThemes = settings.customThemes.first()
            verticalWriting = settings.verticalWriting.first()
            paragraphSpacing = settings.paragraphSpacing.first()
            avoidPageBreak = settings.avoidPageBreak.first()
            hideFurigana = settings.readerHideFurigana.first()
            tapZonePercent = settings.chapterTapZones.first()
            keepScreenOn = settings.keepScreenOn.first()
            systemLightSepia = settings.systemLightSepia.first()
            einkRefreshOnPageTurn = settings.einkRefreshOnPageTurn.first()
            einkRefreshOnScroll = settings.einkRefreshOnScroll.first()
            einkRefreshDurationMillis = settings.einkRefreshDurationMillis.first()
            einkRefreshDelayMillis = settings.einkRefreshDelayMillis.first()
            einkRefreshPageInterval = settings.einkRefreshPageInterval.first()
            einkRefreshColor = settings.einkRefreshColor.first()
            statisticsAutostartMode = settings.statisticsAutostartMode.first()
            // Explicit id wins, else folder lookup for legacy opens (old
            // backstacks, widgets); null = unregistered, sidecar fallback.
            openNovel = openNovelId?.let { id ->
                runCatching { novelRepos?.first?.getNovelById(id) }.getOrNull()
            } ?: runCatching { novelRepos?.first?.getNovelByLocalFolder(rootUrl.name) }.getOrNull()
            isLocalBook = openNovel?.let { it.isLocal || it.source == Novel.LOCAL_SOURCE_ID }
                ?: try {
                    BookStorage.loadMetadata(rootUrl)?.novelSourceId == null
                } catch (_: Exception) {
                    true
                }
            // Per-novel direction: stored per-novel value wins; first
            // open with a known language infers (ja → vertical, else
            // horizontal) and stores it; unknown language keeps the global
            // pref.
            novelDirectionKey = openNovel?.let { novel ->
                novel.localFolder?.takeIf { it.isNotBlank() } ?: novel.id.toString()
            }
            val storedDirection = novelDirectionKey
                ?.let { key -> runCatching { settings.verticalWritingFor(key).first() }.getOrNull() }
            verticalWriting = storedDirection ?: run {
                val lang = openNovel?.lang
                    ?: runCatching { BookStorage.loadMetadata(rootUrl)?.lang }.getOrNull()
                if (lang.isNullOrBlank()) {
                    settings.verticalWriting.first()
                } else {
                    isJapaneseLang(lang).also { inferred ->
                        novelDirectionKey?.let { key ->
                            runCatching { settings.setVerticalWritingFor(key, inferred) }
                        }
                    }
                }
            }

            val dbChapters = openNovel?.let { novel ->
                runCatching { novelRepos?.second?.getChaptersByNovelId(novel.id)?.sortedBy { it.chapterNumber } }.getOrNull()
            }.orEmpty()
            if (openChapterIndex != null && dbChapters.isNotEmpty()) {
                // Explicit open target (detail/history tap): DB position,
                // manga `last_page_read` parity — never a sidecar round-trip.
                index = openChapterIndex.coerceIn(0, dbChapters.size - 1)
                val target = dbChapters[index]
                currentProgress = target.progress.coerceIn(0.0, 1.0)
                // Seeded, not exact: first persist recomputes the true total from
                // prefix sums, same as every chapter turn.
                totalExploredCharCount = 0
            } else {
                // No explicit target: DB resume (or start at zero). The sidecar
                // survives only for unregistered books.
                val bookmark = dbResumeBookmark() ?: BookStorage.loadBookmark(rootUrl)
                index = bookmark?.chapterIndex ?: 0
                currentProgress = bookmark?.progress ?: 0.0
                totalExploredCharCount = bookmark?.characterCount ?: 0
            }
        }
        lastSavedChapterIndex = index
        lastSavedProgress = currentProgress
        lastSavedCharacterCount = totalExploredCharCount

        val stats = BookStorage.loadStatistics(rootUrl)
        if (stats != null) {
            fullStatistics.addAll(stats)

            // Migration: Heuristic to detect milliseconds stored as seconds
            val migrated = fullStatistics.map {
                val speed = if (it.readingTime > 0) (it.charactersRead / it.readingTime * 3600) else 10000.0
                if (speed < 500.0 && it.readingTime > 0) {
                    Log.i("ReaderViewModel", "TTSU-STATS: Migrating entry '${it.dateKey}' from MS to Seconds (Speed: $speed)")
                    it.copy(readingTime = it.readingTime / 1000.0)
                } else {
                    it
                }
            }
            fullStatistics.clear()
            // Deduplicate: keep the entry with the latest lastStatisticModified per dateKey
            val deduplicated = migrated.groupBy { it.dateKey }.mapValues { (_, entries) ->
                entries.maxBy { it.lastStatisticModified }
            }.values.toList()
            fullStatistics.addAll(deduplicated)
        }

        statisticsTracker = ReaderStatisticsTracker(
            title = document.title ?: "Unknown",
            initialStatistics = fullStatistics,
            enabled = true,
        )

        if (statisticsAutostartMode == StatisticsAutostartMode.ON) {
            statisticsTracker.start(totalExploredCharCount)
        }

        scope.launch {
            while (true) {
                delay(1000)
                if (!trackingLocked && !appBackgrounded) {
                    statisticsTracker.update(totalExploredCharCount)
                }
                if (System.currentTimeMillis() - lastPersistTimeMs >= 60000L) {
                    lastPersistTimeMs = System.currentTimeMillis()
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { persistToDisk() }
                }
            }
        }

        // Virtual books have no files to walk: counts arrive per fill below.
        // Prefix sums stay in memory only (derived data, never persisted).
        if (document.extractedDir != null || document.zipPath.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                var runningTotal = 0
                for (i in 0 until document.linearSpineItems.size) {
                    accumulatedCharCounts[i] = runningTotal
                    runningTotal += document.getChapterCharacters(i)
                }
                accumulatedCharCounts[document.linearSpineItems.size] = runningTotal
            }
        }

        // Initial chapter: kicked by the host via startInitialChapter().

        // Start collecting updates from settings flow in the background
        scope.launch {
            settings.theme.collect { theme = it }
        }
        scope.launch {
            settings.fontSize.collect { fontSize = it }
        }
        scope.launch {
            settings.lineHeight.collect { lineHeight = it }
        }
        scope.launch {
            settings.horizontalPadding.collect { horizontalPadding = it }
        }
        scope.launch {
            settings.verticalPadding.collect { verticalPadding = it }
        }
        scope.launch {
            settings.selectedFont.collect { selectedFont = it }
        }
        scope.launch {
            settings.continuousMode.collect { continuousMode = it }
        }
        scope.launch {
            settings.customBackgroundColor.collect { customBackgroundColor = it }
        }
        scope.launch {
            settings.customTextColor.collect { customTextColor = it }
        }
        scope.launch {
            settings.customThemes.collect { customThemes = it }
        }
        scope.launch {
            // Per-novel direction wins when this open resolved one;
            // unregistered opens track the global pref as before.
            val key = novelDirectionKey
            if (key != null) {
                settings.verticalWritingFor(key).collect { verticalWriting = it ?: verticalWriting }
            } else {
                settings.verticalWriting.collect { verticalWriting = it }
            }
        }
        scope.launch {
            settings.characterSpacing.collect { characterSpacing = it }
        }
        scope.launch {
            settings.paragraphSpacing.collect { paragraphSpacing = it }
        }
        scope.launch {
            settings.avoidPageBreak.collect { avoidPageBreak = it }
        }
        scope.launch {
            settings.readerHideFurigana.collect { hideFurigana = it }
        }
        scope.launch {
            settings.chapterTapZones.collect { tapZonePercent = it }
        }
        scope.launch {
            settings.justifyText.collect { justifyText = it }
        }
        scope.launch {
            settings.layoutAdvanced.collect { layoutAdvanced = it }
        }
        scope.launch {
            settings.keepScreenOn.collect { keepScreenOn = it }
        }
        scope.launch {
            settings.systemLightSepia.collect { systemLightSepia = it }
        }
        scope.launch {
            settings.einkRefreshOnPageTurn.collect { einkRefreshOnPageTurn = it }
        }
        scope.launch {
            settings.einkRefreshOnScroll.collect { einkRefreshOnScroll = it }
        }
        scope.launch {
            settings.einkRefreshDurationMillis.collect { einkRefreshDurationMillis = it }
        }
        scope.launch {
            settings.einkRefreshDelayMillis.collect { einkRefreshDelayMillis = it }
        }
        scope.launch {
            settings.einkRefreshPageInterval.collect { einkRefreshPageInterval = it }
        }
        scope.launch {
            settings.einkRefreshColor.collect { einkRefreshColor = it }
        }
        scope.launch {
            settings.chapterCacheSizeMb.collect { chapterCacheSizeMb = it }
        }
        scope.launch {
            settings.statisticsAutostartMode.collect { statisticsAutostartMode = it }
        }
    }

    fun updateTheme(value: Theme) = scope.launch { settings.setTheme(value) }
    fun updateFontSize(value: Double) = scope.launch { settings.setFontSize(value) }
    fun updateLineHeight(value: Double) = scope.launch { settings.setLineHeight(value) }
    fun updateHorizontalPadding(value: Double) = scope.launch { settings.setHorizontalPadding(value) }
    fun updateVerticalPadding(value: Double) = scope.launch { settings.setVerticalPadding(value) }
    fun updateSelectedFont(value: String) = scope.launch { settings.setSelectedFont(value) }
    fun updateContinuousMode(value: Boolean) = scope.launch { settings.setContinuousMode(value) }
    fun updateCustomBackgroundColor(value: Int) = scope.launch { settings.setCustomBackgroundColor(value) }
    fun updateCustomTextColor(value: Int) = scope.launch { settings.setCustomTextColor(value) }
    fun applyCustomTheme(value: CustomReaderTheme) = scope.launch { settings.setCustomTheme(value) }
    fun addCustomTheme(value: CustomReaderTheme) = scope.launch { settings.addCustomTheme(value) }
    fun deleteCustomTheme(value: CustomReaderTheme) = scope.launch { settings.deleteCustomTheme(value) }
    fun renameCustomTheme(value: CustomReaderTheme, newName: String) = scope.launch { settings.renameCustomTheme(value, newName) }
    fun updateVerticalWriting(value: Boolean) = scope.launch {
        // Per-novel when this open resolved one; the global settings
        // screen keeps writing the global key through the same call shape.
        val key = novelDirectionKey
        if (key != null) {
            settings.setVerticalWritingFor(key, value)
        } else {
            settings.setVerticalWriting(value)
        }
    }
    fun updateJustifyText(value: Boolean) = scope.launch { settings.setJustifyText(value) }
    fun updateAvoidPageBreak(value: Boolean) = scope.launch { settings.setAvoidPageBreak(value) }
    fun updateHideFurigana(value: Boolean) = scope.launch { settings.setReaderHideFurigana(value) }
    fun updateCharacterSpacing(value: Double) = scope.launch { settings.setCharacterSpacing(value) }
    fun updateParagraphSpacing(value: Double) = scope.launch { settings.setParagraphSpacing(value) }
    fun updateLayoutAdvanced(value: Boolean) = scope.launch { settings.setLayoutAdvanced(value) }
    fun updateTapZonePercent(value: Int) = scope.launch { settings.setChapterTapZones(value) }
    fun updateKeepScreenOn(value: Boolean) = scope.launch { settings.setKeepScreenOn(value) }
    fun updateSystemLightSepia(value: Boolean) = scope.launch { settings.setSystemLightSepia(value) }
    fun updateEinkRefreshOnPageTurn(value: Boolean) = scope.launch { settings.setEinkRefreshOnPageTurn(value) }

    fun updateEinkRefreshOnScroll(value: Boolean) = scope.launch { settings.setEinkRefreshOnScroll(value) }
    fun updateEinkRefreshDurationMillis(value: Int) = scope.launch { settings.setEinkRefreshDurationMillis(value) }
    fun updateEinkRefreshDelayMillis(value: Int) = scope.launch { settings.setEinkRefreshDelayMillis(value) }
    fun updateEinkRefreshPageInterval(value: Int) = scope.launch { settings.setEinkRefreshPageInterval(value) }
    fun updateEinkRefreshColor(value: String) = scope.launch { settings.setEinkRefreshColor(value) }
    fun updateChapterCacheSizeMb(value: Int) = scope.launch { settings.setChapterCacheSizeMb(value) }
    fun updateStatisticsAutostartMode(value: StatisticsAutostartMode) = scope.launch { settings.setStatisticsAutostartMode(value) }

    fun onPageTurned() {
        if (statisticsAutostartMode == StatisticsAutostartMode.PAGETURN) {
            statisticsTracker.startForPageTurnIfNeeded(totalExploredCharCount)
        }
    }

    fun getReaderSettings(context: Context): ReaderSettings {
        val fontUrl = if (FontManager.isCustomFont(context, selectedFont)) {
            FontManager.getFontUri(context, selectedFont)
        } else {
            null
        }

        val (bg, txt) = when (theme) {
            Theme.LIGHT -> 0xFFFFFFFF.toInt() to 0xFF000000.toInt()
            Theme.DARK -> 0xFF121212.toInt() to 0xFFE0E0E0.toInt()
            Theme.SEPIA -> 0xFFF2E2C9.toInt() to 0xFF3C2C1C.toInt()
            Theme.PURE_BLACK -> 0xFF000000.toInt() to 0xFFE0E0E0.toInt()
            Theme.CUSTOM -> customBackgroundColor to customTextColor
            Theme.SYSTEM -> {
                val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                if (isDark) {
                    if (systemLightSepia) {
                        0xFF1C140C.toInt() to 0xFFF2E2C9.toInt() // Inverted Sepia
                    } else {
                        0xFF121212.toInt() to 0xFFE0E0E0.toInt()
                    }
                } else {
                    if (systemLightSepia) {
                        0xFFF2E2C9.toInt() to 0xFF3C2C1C.toInt() // Sepia
                    } else {
                        0xFFFFFFFF.toInt() to 0xFF000000.toInt()
                    }
                }
            }
        }

        return ReaderSettings(
            fontSize = fontSize,
            lineHeight = lineHeight,
            characterSpacing = characterSpacing,
            paragraphSpacing = paragraphSpacing,
            horizontalPadding = horizontalPadding,
            verticalPadding = verticalPadding,
            selectedFont = selectedFont,
            fontUrl = fontUrl?.toString(),
            theme = theme.name.lowercase(),
            backgroundColor = bg,
            textColor = txt,
            verticalWriting = verticalWriting,
            justifyText = justifyText,
            avoidPageBreak = avoidPageBreak,
            hideFurigana = hideFurigana,
            layoutAdvanced = layoutAdvanced,
            tapZonePercent = tapZonePercent,
            continuousMode = continuousMode,
        )
    }

    fun getCurrentChapterTitle(): String? {
        return getChapterTitle(index)
    }

    fun getChapterTitle(chapterIndex: Int): String? {
        val href = document.getChapterHref(chapterIndex) ?: return null
        // Try TOC lookup first
        val tocLabel = findTocLabel(document.tableOfContents, href)
        if (tocLabel != null) return tocLabel
        // Fallback to file name without path
        return href.substringAfterLast("/").substringBefore(".")
    }

    private fun findTocLabel(toc: List<chimahon.novel.data.epub.TocEntry>, href: String): String? {
        val fileName = href.substringAfterLast("/")
        for (entry in toc) {
            val entryHref = entry.href ?: continue
            // Check if the href ends with the same file name
            if (entryHref.endsWith(fileName) || entryHref.contains(fileName.substringBefore("."))) {
                return entry.label
            }
            val found = findTocLabel(entry.children, href)
            if (found != null) return found
        }
        return null
    }

    fun getFlattenedToc(): List<chimahon.novel.data.epub.TocEntry> {
        val flat = mutableListOf<chimahon.novel.data.epub.TocEntry>()
        fun flatten(entries: List<chimahon.novel.data.epub.TocEntry>, depth: Int = 0) {
            for (e in entries) {
                // Add depth indentation to label if it's nested
                val indent = "  ".repeat(depth)
                flat.add(e.copy(label = "$indent${e.label}"))
                flatten(e.children, depth + 1)
            }
        }
        flatten(document.tableOfContents)
        return flat
    }

    fun getSpineIndexForHref(href: String): Int? {
        val decodedHref = java.net.URLDecoder.decode(href.substringBefore('#').substringBefore('?'), "UTF-8")
        val fileName = decodedHref.substringAfterLast("/")

        for (i in 0 until document.linearSpineItems.size) {
            val chapterHref = document.getChapterHref(i) ?: continue
            val chapterFileName = chapterHref.substringAfterLast("/")

            if (chapterHref.endsWith(decodedHref) || chapterFileName == fileName) {
                return i
            }
        }
        return null
    }

    fun saveBookmark(progress: Double, updateTracker: Boolean = true, force: Boolean = false) {
        // Unclamped/NaN fractions (JS edge cases) must never reach the sidecar,
        // stats, or DB — the writer side already guards, this guards the reader.
        if (!progress.isFinite()) return
        val safeProgress = progress.coerceIn(0.0, 1.0)
        currentProgress = safeProgress
        bridge.updateProgress(safeProgress)
        persistBookmark(safeProgress, force)
        if (updateTracker && !trackingLocked && !appBackgrounded) {
            statisticsTracker.update(totalExploredCharCount)
        }
        // No per-tick stats flush: the 60s loop + flushReaderState cover durability.
    }

    fun flushReaderState() {
        persistBookmark(currentProgress, force = true)
        if (!trackingLocked && !appBackgrounded) {
            statisticsTracker.update(totalExploredCharCount)
        }
        // Leaving (background/dispose/close): close the chapter visit like a
        // turn — synchronously. On dispose the composition scope is already
        // dying, so the fire-and-forget launches used mid-session would die
        // with it; a session with no chapter turn would otherwise save
        // nothing (no history row at all).
        val exitedIndex = index
        val now = System.currentTimeMillis()
        val started = chapterSessionStartMs
        chapterSessionStartMs = now
        val durationMs = if (started > 0L) (now - started).coerceAtLeast(0L) else 0L
        val stats = statisticsTracker.statisticsForPersistence()
        if (openNovel == null) {
            runCatching { BookStorage.saveStatistics(stats, rootUrl) }
        }
        runBlocking(Dispatchers.IO) {
            runCatching { persistChapterRow(exitedIndex, currentProgress, totalExploredCharCount) }
                .onFailure { Log.w("NovelReader", "close chapter persist failed", it) }
            if (durationMs > 0) {
                runCatching { persistHistoryVisit(exitedIndex, durationMs) }
                    .onFailure { Log.w("NovelReader", "close history persist failed", it) }
            }
            runCatching { flushStatsToDb(stats) }
                .onFailure { Log.w("NovelReader", "close stats persist failed", it) }
        }
    }

    /** Chapter content (downloads/cache/network); null standalone → files. */
    private val chapterLoader by lazy {
        runCatching { Injekt.get<NovelChapterLoader>() }.getOrNull()
    }

    /**
     * Manga-path identity: open book -> DB novel + number-ordered chapter ids.
     * The init-threaded row wins; legacy metadata/location fallbacks cover
     * unregistered opens. Fresh resolve per call so mid-session list edits
     * can't silently shift indices onto the wrong chapter.
     */
    private suspend fun resolveOwnNovelChapters(): Pair<Long, List<Long>>? {
        val repos = novelRepos ?: return null
        openNovel?.let { novel ->
            val chapterIds = repos.second.getChaptersByNovelId(novel.id)
                .sortedBy { it.chapterNumber }
                .map { it.id }
            return novel.id to chapterIds
        }
        val metadata = try {
            BookStorage.loadMetadata(rootUrl)
        } catch (_: Exception) {
            null
        }
        val sourceId = metadata?.novelSourceId
        val novelUrl = metadata?.novelUrl
        val maybeNovel = if (sourceId != null && novelUrl != null) {
            repos.first.getNovelByUrlAndSourceId(novelUrl, sourceId)
        } else if (!rootUrl.name.startsWith("src_")) {
            // Local homes resolve like source rows (manga-local parity);
            // "local://" is the NovelLocalSource URL scheme.
            repos.first.getNovelByUrlAndSourceId("local://${rootUrl.name}", Novel.LOCAL_SOURCE_ID)
        } else {
            null
        }
        val dbNovel = maybeNovel ?: return null
        // chapterNumber order: bookmark indices are written against the
        // number-sorted spine everywhere (open, sync, bridge, loader).
        val chapterIds = repos.second.getChaptersByNovelId(dbNovel.id)
            .sortedBy { it.chapterNumber }
            .map { it.id }
        return dbNovel.id to chapterIds
    }

    /** Manga per-page `updateChapter`: chapter row only, never history. */
    private suspend fun persistChapterRow(chapterIndex: Int, progress: Double, characterCount: Int) {
        val repos = novelRepos ?: return
        val chapterId = resolveOwnNovelChapters()?.second?.getOrNull(chapterIndex) ?: return
        if (progress.isNovelReadComplete() || characterCount > 0) {
            val chapter = repos.second.getChapterById(chapterId) ?: return
            val markRead = progress.isNovelReadComplete() && !chapter.read
            val newCharCount = max(characterCount.toLong(), chapter.lastPageRead)
            val safeProgress = if (progress.isFinite()) progress.coerceIn(0.0, 1.0) else chapter.progress
            if (markRead || newCharCount != chapter.lastPageRead || safeProgress != chapter.progress) {
                repos.second.update(
                    NovelChapterUpdate(
                        id = chapter.id,
                        read = if (markRead) true else null,
                        lastPageRead = newCharCount,
                        progress = safeProgress,
                    ),
                )
            }
        }
    }

    /** Manga per-visit `updateHistory`: when + how-long only, never position. */
    private suspend fun persistHistoryVisit(chapterIndex: Int, durationMs: Long) {
        if (durationMs <= 0) return
        val repos = novelRepos ?: return
        val chapterId = resolveOwnNovelChapters()?.second?.getOrNull(chapterIndex) ?: return
        repos.third.upsertHistory(
            chapterId = chapterId,
            lastRead = System.currentTimeMillis(),
            timeRead = durationMs,
        )
    }

    /**
     * Ends the visit for [exitedIndex] with its measured stay, manga
     * `updateHistory` parity: single history row per visit, skipped when the
     * stay is zero. Timing restarts so a later return measures fresh.
     */
    private fun endChapterSession(exitedIndex: Int) {
        val now = System.currentTimeMillis()
        val started = chapterSessionStartMs
        chapterSessionStartMs = now
        if (started <= 0L) return
        val durationMs = now - started
        if (durationMs <= 0) return
        scope.launch(Dispatchers.IO) {
            runCatching { persistHistoryVisit(exitedIndex, durationMs) }
                .onFailure { Log.w("NovelReader", "history visit failed", it) }
        }
    }

    /**
     * User-initiated turn past a chapter edge (swipe, tap zone, keys). Shows
     * the between-chapters transition instead of jumping silently, unless
     * the shared pref is off — then it navigates directly. Local books always
     * navigate directly (content is on disk; no overlay). Programmatic callers
     * (TTS, chapter list, links) keep using next/previous/jump directly.
     */
    fun onChapterEdge(direction: NovelTurnDirection): Boolean {
        val target = if (direction == NovelTurnDirection.NEXT) index + 1 else index - 1
        if (target !in 0 until chapterCount) {
            if (!isLocalBook) chapterTransition = NovelChapterTransition(index, null, direction)
            return false
        }
        if (!alwaysShowChapterTransition || isLocalBook) {
            return if (direction == NovelTurnDirection.NEXT) nextChapter() else previousChapter()
        }
        chapterTransition = NovelChapterTransition(index, target, direction)
        return false
    }

    fun confirmTransition() {
        val target = chapterTransition?.toIndex
        chapterTransition = null
        if (target == null) return
        if (target > index) nextChapter() else previousChapter()
    }

    fun dismissTransition() {
        transitionEnsureJob?.cancel()
        transitionEnsureJob = null
        chapterTransition = null
    }

    fun nextChapter(): Boolean {
        if (index >= chapterCount - 1) return false
        // Paging past the last page genuinely finishes the chapter.
        saveBookmark(1.0)
        loadChapter(index + 1, 0.0)
        return true
    }

    fun previousChapter(): Boolean {
        if (index <= 0) return false
        // Going back never finishes the chapter: keep the real position, or a
        // briefly-opened chapter gets marked read and resumes at its end.
        saveBookmark(currentProgress)
        loadChapter(index - 1, 1.0)
        return true
    }

    fun jumpToChapter(spineIndex: Int, fragment: String? = null) {
        chapterTransition = null
        if (spineIndex == index) {
            loadChapter(spineIndex, 0.0, fragment)
            return
        }
        // Jumping away (list, links) is not finishing: same rule as above.
        saveBookmark(currentProgress)
        // Manga `requestedPage` parity: land on the stored position, not zero.
        scope.launch {
            loadChapter(spineIndex, storedProgressFor(spineIndex), fragment)
        }
    }

    private suspend fun storedProgressFor(spineIndex: Int): Double {
        return try {
            withContext(Dispatchers.IO) {
                val chapterId = resolveOwnNovelChapters()?.second?.getOrNull(spineIndex) ?: return@withContext null
                novelRepos?.second?.getChapterById(chapterId)?.progress
            }
        } catch (_: Exception) {
            null
        } ?: 0.0
    }

    /**
     * Resolves a raw `file://` URL (possibly carrying a #fragment) that came from an in-page
     * link click inside the WebView, finds the matching spine index, saves the current progress
     * bookmark, and then delegates to [jumpToChapter].
     *
     * If the URL cannot be matched to any spine item (e.g. external http link) the call is
     * silently ignored – the WebView already blocked the navigation via shouldOverrideUrlLoading.
     */
    fun jumpToUrl(url: String) {
        val fragment = url.substringAfter("#", missingDelimiterValue = "")
        val targetPath = url.substringBefore("#")
            .removePrefix("file://")
            .replace("\\", "/")

        val spineCount = document.linearSpineItems.size
        for (i in 0 until spineCount) {
            val chapterPath = document.chapterAbsolutePath(i.toUInt())
                ?.replace("\\", "/")
            val href = document.getChapterHref(i)
            if ((chapterPath != null && chapterPath == targetPath) ||
                (href != null && (targetPath == href || targetPath.endsWith("/$href")))) {
                // Save progress before jumping so stats are consistent
                saveBookmark(currentProgress)
                jumpToChapter(i, fragment.ifEmpty { null })
                return
            }
        }
        android.util.Log.w("ReaderViewModel", "jumpToUrl: no spine match for $url")
    }

    /**
     * Manga `last_page_read` parity: no sidecar (wiped cache, fresh restore)
     * → resume from the DB chapter row and heal the sidecar, instead of
     * restarting at chapter 0. Standalone books have no rows: null. Local
     * homes resolve like source rows. Runs inside init's IO block.
     */
    private suspend fun dbResumeBookmark(): Bookmark? {
        val (novelId, chapterIds) = runCatching { resolveOwnNovelChapters() }.getOrNull() ?: return null
        val repos = novelRepos ?: return null
        val history = runCatching { repos.third.getLatestHistoryByNovelId(novelId) }.getOrNull() ?: return null
        val chapters = runCatching { repos.second.getChaptersByNovelId(novelId) }
            .getOrNull()?.sortedBy { it.chapterNumber } ?: return null
        val index = chapters.indexOfFirst { it.id == history.chapterId }.takeIf { it >= 0 } ?: return null
        // Sanity: history chapter must be in the resolved spine.
        if (chapterIds.getOrNull(index) != history.chapterId) return null
        val chapter = chapters[index]
        return Bookmark(
            chapterIndex = index,
            progress = chapter.progress.coerceIn(0.0, 1.0),
            characterCount = chapter.lastPageRead.toInt(),
            lastModified = System.currentTimeMillis(),
        )
    }

    /** Sync probe for overlay badges: real file content on disk, nothing more. */
    fun isChapterContentCached(chapterIndex: Int): Boolean = try {
        document.chapterAbsolutePath(chapterIndex.toUInt())
            ?.let { java.io.File(it) }
            ?.takeIf { it.isFile && it.length() > 0L }
            ?.let { file ->
                // Same marker the builder writes (its const is app-private); gone with files in 2c.
                file.bufferedReader().use { reader ->
                    val buf = CharArray(1024)
                    val read = reader.read(buf, 0, buf.size)
                    read > 0 && !String(buf, 0, read).contains("novel-placeholder")
                }
            } ?: false
    } catch (_: Exception) {
        false
    }

    fun retryTransitionLoad() {
        val target = chapterTransition?.toIndex ?: return
        scope.launch {
            jumpToChapter(target)
        }
    }

    /** First open through the loader (content or error, never a placeholder). Host calls once, post-construction. */
    fun startInitialChapter() {
        scope.launch { loadInitialChapter() }
    }

    /**
     * Recomputes prefix sums from memoized per-chapter counts (override-aware).
     * Called on IO after each fill; unfilled virtual chapters contribute 0 until read.
     */
    private fun refreshAccumulatedCounts() {
        var runningTotal = 0
        for (i in 0 until document.linearSpineItems.size) {
            accumulatedCharCounts[i] = runningTotal
            runningTotal += document.getChapterCharacters(i)
        }
        accumulatedCharCounts[document.linearSpineItems.size] = runningTotal
    }

    private fun loadInitialChapter() {
        transitionEnsureJob?.cancel()
        transitionEnsureJob = scope.launch {
            // Ready files send immediately (no overlay flash); anything else
            // holds the transition overlay while the loader fills.
            if (isChapterContentCached(index)) {
                try {
                    sendChapter(loadChapterContent(index))
                    chapterSessionStartMs = System.currentTimeMillis()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    chapterTransition = NovelChapterTransition(index, index, NovelTurnDirection.NEXT, error = "Couldn't load chapter")
                }
                return@launch
            }
            chapterTransition = NovelChapterTransition(index, index, NovelTurnDirection.NEXT, isLoading = true)
            try {
                val html = loadChapterContent(index)
                document.contentOverride[index] = html
                withContext(Dispatchers.IO) { refreshAccumulatedCounts() }
                chapterTransition = null
                chapterSessionStartMs = System.currentTimeMillis()
                sendChapter(html)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                chapterTransition = NovelChapterTransition(index, index, NovelTurnDirection.NEXT, error = "Couldn't load chapter")
            }
        }
    }

    /**
     * Chapter HTML via the host loader (downloads, cache, network), falling back
     * to the package file for standalone mode. Throws when nothing can supply it;
     * callers map that to their loading/error states. No readiness polling.
     */
    private suspend fun loadChapterContent(spineIndex: Int): String {
        try {
            chapterLoader?.loadChapterHtml(rootUrl.name, spineIndex, openNovel?.id)?.let { return it }
        } catch (_: Exception) {
        }
        return readPackageFile(spineIndex) ?: throw IllegalStateException("Couldn't load chapter")
    }

    private fun readPackageFile(spineIndex: Int): String? = runCatching {
        document.chapterAbsolutePath(spineIndex.toUInt())
            ?.let { java.io.File(it) }
            ?.takeIf { it.isFile }
            ?.readText()
            // Placeholders are never content: a stale shell must surface as an
            // error card (with retry), never as a blank "loading" page.
            ?.takeUnless { it.contains("novel-placeholder") }
    }.getOrNull()

    /**
     * Identity URL for a spine chapter. Pure string math, no file existence:
     * file books resolve to their real path, virtual books to the stable
     * package-shaped URL the view, dedupe and link matching all share.
     */
    private fun chapterFileUrl(spineIndex: Int): String? {
        val href = document.getChapterHref(spineIndex) ?: return null
        val baseDir = document.extractedDir ?: rootUrl
        return "file://${File(baseDir, href).absolutePath.replace("\\", "/")}"
    }

    private fun sendChapter(html: String) {
        // Throw (don't silently drop): callers map failure to the error card,
        // otherwise the overlay would spin on loading forever.
        val fileUrl = chapterFileUrl(index) ?: throw IllegalStateException("Couldn't load chapter")
        val chapterTitle = getCurrentChapterTitle()
        bridge.updateState(fileUrl, currentProgress, chapterTitle)
        bridge.send(WebViewCommand.LoadChapterHtml(fileUrl, html, currentProgress))
    }

    private fun prefetchAround(center: Int) {
        val loader = chapterLoader ?: return
        scope.launch(Dispatchers.IO) {
            listOf(center - 1, center + 1)
                .filter { it in 0 until chapterCount }
                .forEach { runCatching { loader.loadChapterHtml(rootUrl.name, it, openNovel?.id) } }
        }
    }

    private fun loadChapter(newIndex: Int, progress: Double, fragment: String? = null) {
        // Flush any accumulated session delta to persistent statistics BEFORE we
        // change the index. persistBookmark() calls calculateExploredCharCount()
        // which uses the current index — if we change it first the delta is lost.
        persistBookmark(currentProgress)
        if (!trackingLocked && !appBackgrounded) {
            statisticsTracker.update(totalExploredCharCount)
        }

        val fromIndex = index
        val direction = if (newIndex >= fromIndex) NovelTurnDirection.NEXT else NovelTurnDirection.PREV
        // Image-only chapters are single views (manga single-image parity):
        // landing on one counts as complete so it reads as viewed.
        val effectiveProgress =
            if (document.linearSpineItems.getOrNull(newIndex)?.type == SpineItemType.IMAGE_ONLY) {
                1.0
            } else {
                progress
            }
        // Local books skip the loading overlay too (instant disk reads); the
        // error overlay below still shows for both when a fill actually fails.
        if (!isLocalBook) {
            chapterTransition = NovelChapterTransition(fromIndex, newIndex, direction, isLoading = true)
        }
        transitionEnsureJob?.cancel()
        transitionEnsureJob = scope.launch {
            try {
                val html = loadChapterContent(newIndex)
                document.contentOverride[newIndex] = html
                withContext(Dispatchers.IO) { refreshAccumulatedCounts() }
                index = newIndex
                // The departed chapter's visit ends here (manga chapter-change
                // history); same-index reloads (retry) continue the visit, and
                // start timing if a failed initial open left it unset.
                if (newIndex != fromIndex) {
                    endChapterSession(fromIndex)
                } else if (chapterSessionStartMs <= 0L) {
                    chapterSessionStartMs = System.currentTimeMillis()
                }
                // Reset tracker baseline to the new position so neither the timer loop
                // nor the saveBookmark call below register a false delta from the jump.
                statisticsTracker.resetBaseline(calculateExploredCharCount(effectiveProgress))
                saveBookmark(effectiveProgress, updateTracker = false, force = true)
                chapterTransition = null
                sendChapter(html)
                if (!fragment.isNullOrEmpty()) {
                    bridge.send(WebViewCommand.JumpToFragment(fragment))
                }
                prefetchAround(newIndex)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                chapterTransition = NovelChapterTransition(fromIndex, newIndex, direction, error = "Couldn't load chapter")
            }
        }
    }

    private fun calculateExploredCharCount(progress: Double): Int {
        // Prefix sums when available (init walk, refreshed per fill); the loop
        // below covers gaps with memoized per-chapter counts.
        val base = accumulatedCharCounts[index]
        val next = accumulatedCharCounts[index + 1]
        if (base != null && next != null) {
            return base + ((next - base) * progress).toInt()
        }
        var count = 0
        for (i in 0 until index) {
            count += document.getChapterCharacters(i)
        }
        val currentChapterChars = document.getChapterCharacters(index)
        count += (currentChapterChars * progress).toInt()
        return count
    }

    private fun persistBookmark(progress: Double, force: Boolean = false) {
        // Image-only chapters have no scroll position (manga single-image
        // parity: viewed = complete): sub-complete ticks carry nothing to
        // persist, so the stuck 0.0 can never poison resume or counts.
        if (isCurrentChapterImageOnly && progress < 1.0) return
        val characterCount = calculateExploredCharCount(progress)
        totalExploredCharCount = characterCount

        val changed = force ||
            index != lastSavedChapterIndex ||
            characterCount != lastSavedCharacterCount ||
            abs(progress - lastSavedProgress) > BOOKMARK_PROGRESS_EPSILON

        if (!changed) return

        // Ticks fire continuously while scrolling: disk writes leave Main, except
        // turn/background/close (force) which need synchronous durability.
        // Sidecar survives only for unregistered books; rows carry the rest.
        if (openNovel == null) {
            val snapshot = Bookmark(
                chapterIndex = index,
                progress = progress,
                characterCount = characterCount,
                lastModified = System.currentTimeMillis(),
            )
            if (force) {
                BookStorage.save(snapshot, rootUrl, FileNames.bookmark)
            } else {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { BookStorage.save(snapshot, rootUrl, FileNames.bookmark) }
                }
            }
        }
        lastSavedChapterIndex = index
        lastSavedProgress = progress
        lastSavedCharacterCount = characterCount

        // Manga per-page `updateChapter`: mirror position + read flag into the
        // chapter row (repos absent standalone → sidecar only).
        if (novelRepos != null) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { persistChapterRow(index, progress, characterCount) }
                    .onFailure { Log.w("NovelReader", "persist failed", it) }
            }
        }

        // No per-tick stats flush: the 60s loop + flushReaderState cover durability.
    }

    fun setTrackingLocked(locked: Boolean) {
        if (locked) {
            if (statisticsTracker.state.isTracking) {
                statisticsTracker.update(totalExploredCharCount)
            }
            trackingLocked = true
        } else {
            statisticsTracker.resetBaseline(totalExploredCharCount)
            trackingLocked = false
        }
    }

    fun togglePause() {
        statisticsTracker.togglePause(totalExploredCharCount)
    }

    fun onAppBackgrounded() {
        flushReaderState()
        appBackgrounded = true
    }

    fun onAppForegrounded() {
        statisticsTracker.resetBaseline(totalExploredCharCount)
        appBackgrounded = false
    }

    private fun persistToDisk() {
        val stats = statisticsTracker.statisticsForPersistence()
        // Sidecar survives only for unregistered books; rows carry the rest.
        if (openNovel == null) {
            BookStorage.saveStatistics(stats, rootUrl)
        }
        persistStatsToDb(stats)
    }

    /** Last flushed per-day totals, so the additive DB upsert gets deltas. */
    @Volatile
    private var flushedStatsByDate: Map<String, Pair<Int, Double>>? = null

    private fun persistStatsToDb(stats: List<Statistics>) {
        scope.launch(Dispatchers.IO) {
            runCatching { flushStatsToDb(stats) }
                .onFailure { Log.w("NovelReader", "stats persist failed", it) }
        }
    }

    /** Additive per-day upsert (deltas against the last flush); suspends for the close path. */
    private suspend fun flushStatsToDb(stats: List<Statistics>) {
        val novelId = openNovel?.id ?: return
        val repo = runCatching {
            Injekt.get<tachiyomi.domain.novel.repository.NovelReadingStatsRepository>()
        }.getOrNull() ?: return
        runCatching {
                val seed = flushedStatsByDate ?: repo.getByNovelId(novelId)
                    .associate { it.dateKey to (it.charactersRead to it.readingTime) }
                    .also { flushedStatsByDate = it }
                val deltas = stats.mapNotNull { entry ->
                    val (prevChars, prevTime) = seed[entry.dateKey] ?: (0 to 0.0)
                    val charDelta = (entry.charactersRead - prevChars).coerceAtLeast(0)
                    val timeDelta = (entry.readingTime - prevTime).coerceAtLeast(0.0)
                    if (charDelta == 0 && timeDelta == 0.0) {
                        null
                    } else {
                        entry to (charDelta to timeDelta)
                    }
                }
                flushedStatsByDate = seed + stats.associate {
                    it.dateKey to (it.charactersRead to it.readingTime)
                }
                deltas.forEach { (entry, delta) ->
                    repo.upsert(
                        novelId = novelId,
                        dateKey = entry.dateKey,
                        charactersRead = delta.first,
                        readingTime = delta.second,
                        minReadingSpeed = entry.minReadingSpeed,
                        altMinReadingSpeed = entry.altMinReadingSpeed,
                        lastReadingSpeed = entry.lastReadingSpeed,
                        maxReadingSpeed = entry.maxReadingSpeed,
                        completedBook = entry.completedBook,
                    )
                }
            }.onFailure { Log.w("NovelReader", "stats persist failed", it) }
    }

    companion object {
        private const val BOOKMARK_PROGRESS_EPSILON = 0.0001
    }
}
