package eu.kanade.tachiyomi.ui.stats

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.canopus.chimareader.data.MangaStatsStorage
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.stats.StatsTitlesContent
import eu.kanade.presentation.more.stats.data.StatsType
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetReadMangaNotInLibraryView
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.LocalDate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar

enum class StatsTitlesSort {
    Alphabetical,
    LastRead,
    DateAdded
}

sealed interface StatsTitlesState {
    data object Loading : StatsTitlesState
    data class Success(
        val titles: List<StatsTitleItem>,
        val searchQuery: String? = null,
        val sortOption: StatsTitlesSort = StatsTitlesSort.LastRead,
    ) : StatsTitlesState
}

data class StatsTitleItem(
    val id: String,
    val title: String,
    val author: String?,
    val coverData: Any?,
    val isNovel: Boolean,
    val lastReadDate: LocalDate?,
    val readDurationMs: Long,
    val charactersRead: Int,
    val manga: Manga? = null,
    val novelId: String? = null,
    val dateAdded: Long = 0L,
)

class StatsTitlesScreenModel(
    private val activeProfileId: String?,
    private val allRead: Boolean,
    private val statsType: StatsType,
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getReadMangaNotInLibraryView: GetReadMangaNotInLibraryView = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val dictionaryPreferences: DictionaryPreferences = Injekt.get(),
    private val context: Application = Injekt.get(),
    private val novelRepository: tachiyomi.domain.novel.repository.NovelRepository = Injekt.get(),
    private val novelReadingStatsRepository: tachiyomi.domain.novel.repository.NovelReadingStatsRepository = Injekt.get(),
) : StateScreenModel<StatsTitlesState>(StatsTitlesState.Loading) {

    private var rawTitles = emptyList<StatsTitleItem>()
    val searchQuery = MutableStateFlow<String?>(null)
    val sortOption = MutableStateFlow(StatsTitlesSort.LastRead)

    init {
        screenModelScope.launch {
            loadRawTitles()
            combine(searchQuery, sortOption) { query, sort ->
                val filtered = if (query.isNullOrBlank()) {
                    rawTitles
                } else {
                    rawTitles.filter {
                        it.title.contains(query, ignoreCase = true) ||
                            (it.author?.contains(query, ignoreCase = true) == true)
                    }
                }

                val sorted = when (sort) {
                    StatsTitlesSort.Alphabetical -> filtered.sortedWith(
                        compareBy<StatsTitleItem> { it.title.lowercase() }
                    )
                    StatsTitlesSort.LastRead -> filtered.sortedWith(
                        compareByDescending<StatsTitleItem> { it.lastReadDate != null }
                            .thenByDescending { it.lastReadDate }
                            .thenByDescending { it.readDurationMs }
                            .thenBy { it.title }
                    )
                    StatsTitlesSort.DateAdded -> filtered.sortedWith(
                        compareByDescending<StatsTitleItem> { it.dateAdded }
                            .thenBy { it.title }
                    )
                }

                StatsTitlesState.Success(
                    titles = sorted,
                    searchQuery = query,
                    sortOption = sort,
                )
            }.collect { nextState ->
                mutableState.update { nextState }
            }
        }
    }

    fun search(query: String?) {
        searchQuery.value = query
    }

    fun sort(sort: StatsTitlesSort) {
        sortOption.value = sort
    }

    private suspend fun loadRawTitles() {
        withContext(Dispatchers.IO) {
            val resolver = dictionaryPreferences.profileResolver

            // 1. Get filtered Manga
            val mangaList = if (statsType == StatsType.All || statsType == StatsType.Manga) {
                val libraryManga = getLibraryManga.await() + if (allRead) {
                    getReadMangaNotInLibraryView.await()
                } else {
                    emptyList()
                }
                val distinctLibraryManga = libraryManga.distinctBy { it.id }

                // Filter by profile
                distinctLibraryManga.filter { lm ->
                    val dbManga = lm.manga
                    val source = dbManga.source.let { sourceManager.getOrStub(it) }
                    val profileId = resolver.resolve(
                        mangaId = lm.id,
                        sourceId = dbManga.source,
                        sourceLang = source.lang,
                    ).id
                    activeProfileId == null || profileId == activeProfileId
                }.map { it.manga }
            } else {
                emptyList()
            }

            // 2. Get filtered Novels (DB rows)
            val novelList = if (statsType == StatsType.All || statsType == StatsType.Novels) {
                runCatching { novelRepository.getAll() }.getOrNull().orEmpty()
                    .filter { novel ->
                        val key = novel.localFolder ?: novel.id.toString()
                        val profileId = resolver.resolve(novelId = key).id
                        activeProfileId == null || profileId == activeProfileId
                    }
            } else {
                emptyList()
            }

            // 3. Load Stats to compute last read time, duration, and characters
            val allMangaStats = MangaStatsStorage.loadAll(context)
            val mangaStatsGrouped = allMangaStats.groupBy { it.mangaId }

            val titleItems = mutableListOf<StatsTitleItem>()

            // Process Manga
            mangaList.forEach { manga ->
                val stats = mangaStatsGrouped[manga.id] ?: emptyList()
                val totalDuration = stats.sumOf { it.readingTime }
                val totalChars = stats.sumOf { it.charactersRead }
                val lastRead = stats.mapNotNull {
                    runCatching { LocalDate.parse(it.dateKey) }.getOrNull()
                }.maxOrNull()

                titleItems.add(
                    StatsTitleItem(
                        id = manga.id.toString(),
                        title = manga.title,
                        author = manga.author,
                        coverData = manga,
                        isNovel = false,
                        lastReadDate = lastRead,
                        readDurationMs = totalDuration,
                        charactersRead = totalChars,
                        manga = manga,
                        dateAdded = manga.dateAdded,
                    )
                )
            }

            // Process Novels
            novelList.forEach { novel ->
                val stats = runCatching { novelReadingStatsRepository.getByNovelId(novel.id) }
                    .getOrNull().orEmpty()
                val totalDurationSeconds = stats.sumOf { it.readingTime }
                val totalDurationMs = (totalDurationSeconds * 1000).toLong()
                val totalChars = stats.sumOf { it.charactersRead }
                val lastRead = stats.mapNotNull {
                    runCatching { LocalDate.parse(it.dateKey) }.getOrNull()
                }.maxOrNull()
                val novelKey = novel.localFolder ?: novel.id.toString()

                titleItems.add(
                    StatsTitleItem(
                        id = novelKey,
                        title = novel.title,
                        author = novel.author,
                        coverData = novel.thumbnailUrl?.let { File(it) },
                        isNovel = true,
                        lastReadDate = lastRead,
                        readDurationMs = totalDurationMs,
                        charactersRead = totalChars,
                        novelId = novelKey,
                        dateAdded = novel.dateAdded,
                    )
                )
            }

            rawTitles = titleItems
        }
    }
}

class StatsTitlesScreen(
    private val activeProfileId: String?,
    private val allRead: Boolean,
    private val statsType: StatsType,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        val screenModel = rememberScreenModel {
            StatsTitlesScreenModel(activeProfileId, allRead, statsType)
        }
        val state by screenModel.state.collectAsState()

        val successState = state as? StatsTitlesState.Success

        Scaffold(
            topBar = { scrollBehavior ->
                val titleText = stringResource(MR.strings.label_stats) + " - " + if (allRead) "All read" else "In library"
                SearchToolbar(
                    titleContent = { AppBarTitle(titleText) },
                    searchQuery = successState?.searchQuery,
                    onChangeSearchQuery = { screenModel.search(it) },
                    navigateUp = navigator::pop,
                    actions = {
                        var sortMenuExpanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Sort,
                                contentDescription = stringResource(MR.strings.action_sort),
                            )
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.action_sort_alpha)) },
                                onClick = {
                                    screenModel.sort(StatsTitlesSort.Alphabetical)
                                    sortMenuExpanded = false
                                },
                                trailingIcon = {
                                    if (successState?.sortOption == StatsTitlesSort.Alphabetical) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.action_sort_last_read)) },
                                onClick = {
                                    screenModel.sort(StatsTitlesSort.LastRead)
                                    sortMenuExpanded = false
                                },
                                trailingIcon = {
                                    if (successState?.sortOption == StatsTitlesSort.LastRead) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(MR.strings.action_sort_date_added)) },
                                onClick = {
                                    screenModel.sort(StatsTitlesSort.DateAdded)
                                    sortMenuExpanded = false
                                },
                                trailingIcon = {
                                    if (successState?.sortOption == StatsTitlesSort.DateAdded) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                    }
                                }
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            when (val actualState = state) {
                is StatsTitlesState.Loading -> LoadingScreen()
                is StatsTitlesState.Success -> {
                    StatsTitlesContent(
                        state = actualState,
                        paddingValues = paddingValues,
                        onTitleClick = { item ->
                            navigator.push(
                                StatsScreen(
                                    titleId = item.id,
                                    isNovel = item.isNovel,
                                    titleName = item.title,
                                )
                            )
                        },
                    )
                }
            }
        }
    }
}
