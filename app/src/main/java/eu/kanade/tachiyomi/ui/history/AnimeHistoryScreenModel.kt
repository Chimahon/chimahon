package eu.kanade.tachiyomi.ui.history

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.core.util.insertSeparators
import eu.kanade.domain.track.anime.interactor.AddAnimeTracks
import eu.kanade.presentation.history.AnimeHistoryUiModel
import eu.kanade.tachiyomi.util.lang.toLocalDate
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetAnimeCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.AnimeCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.interactor.GetDuplicateLibraryAnime
import tachiyomi.domain.entries.anime.interactor.UpdateAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeUpdate
import tachiyomi.domain.episode.model.Episode
import tachiyomi.domain.history.interactor.GetAnimeHistory
import tachiyomi.domain.history.interactor.GetNextEpisodes
import tachiyomi.domain.history.interactor.RemoveAnimeHistory
import tachiyomi.domain.history.model.AnimeHistoryWithRelations
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeHistoryScreenModel(
    private val addTracks: AddAnimeTracks = Injekt.get(),
    private val getCategories: GetAnimeCategories = Injekt.get(),
    private val getDuplicateLibraryAnime: GetDuplicateLibraryAnime = Injekt.get(),
    private val getHistory: GetAnimeHistory = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val getNextEpisodes: GetNextEpisodes = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val removeHistory: RemoveAnimeHistory = Injekt.get(),
    private val setAnimeCategories: SetAnimeCategories = Injekt.get(),
    private val updateAnime: UpdateAnime = Injekt.get(),
    val snackbarHostState: SnackbarHostState = SnackbarHostState(),
    private val sourceManager: AnimeSourceManager = Injekt.get(),
) : StateScreenModel<AnimeHistoryScreenModel.State>(State()) {

    private val _events: Channel<Event> = Channel(Channel.UNLIMITED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        screenModelScope.launch {
            state
                .flatMapLatest { flowOf(it.searchQuery) }
                .distinctUntilChanged()
                .flatMapLatest { query ->
                    getHistory.subscribe(query ?: "")
                        .distinctUntilChanged()
                        .catch { error ->
                            logcat(LogPriority.ERROR, error)
                            _events.send(Event.InternalError)
                        }
                        .flowOn(Dispatchers.IO)
                }
                .collect { newList ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            list = newList.toAnimeHistoryUiModels().toImmutableList(),
                        )
                    }
                }
        }
    }

    fun updateSearchQuery(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    private fun List<AnimeHistoryWithRelations>.toAnimeHistoryUiModels(): List<AnimeHistoryUiModel> {
        return map { AnimeHistoryUiModel.Item(it) }
            .insertSeparators { before, after ->
                val beforeDate = before?.item?.watchedAt?.time?.toLocalDate()
                val afterDate = after?.item?.watchedAt?.time?.toLocalDate()
                when {
                    beforeDate != afterDate && afterDate != null -> AnimeHistoryUiModel.Header(afterDate)
                    else -> null
                }
            }
    }

    suspend fun getNextEpisode(): Episode? {
        return withIOContext { getNextEpisodes.await(onlyUnseen = false).firstOrNull() }
    }

    suspend fun getNextEpisode(animeId: Long, episodeId: Long): Episode? {
        return withIOContext { getNextEpisodes.await(animeId, episodeId, onlyUnseen = false).firstOrNull() }
    }

    suspend fun getLastHistory(): AnimeHistoryWithRelations? {
        return withIOContext { getHistory.awaitLast() }
    }

    fun getNextEpisodeForAnime(animeId: Long, episodeId: Long) {
        screenModelScope.launchIO {
            sendNextEpisodeEvent(getNextEpisodes.await(animeId, episodeId, onlyUnseen = false))
        }
    }

    private suspend fun sendNextEpisodeEvent(episodes: List<Episode>) {
        _events.send(Event.OpenEpisode(episodes.firstOrNull()))
    }

    fun removeFromHistory(history: AnimeHistoryWithRelations) {
        screenModelScope.launchIO {
            removeHistory.await(listOf(history.id))
        }
    }

    fun removeAllFromHistory(animeId: Long) {
        screenModelScope.launchIO {
            removeHistory.awaitAnime(listOf(animeId))
        }
    }

    fun removeAllHistory() {
        screenModelScope.launchIO {
            val result = removeHistory.awaitAll()
            if (!result) return@launchIO
            _events.send(Event.HistoryCleared)
        }
    }

    fun setDialog(dialog: Dialog?) {
        mutableState.update { it.copy(dialog = dialog) }
    }

    suspend fun getCategories(): List<AnimeCategory> {
        return getCategories.await().filterNot { it.isSystemCategory }
    }

    private fun moveAnimeToCategory(animeId: Long, categories: AnimeCategory?) {
        val categoryIds = listOfNotNull(categories).map { it.id }
        moveAnimeToCategory(animeId, categoryIds)
    }

    private fun moveAnimeToCategory(animeId: Long, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setAnimeCategories.await(animeId, categoryIds)
        }
    }

    fun moveAnimeToCategoriesAndAddToLibrary(anime: Anime, categories: List<Long>) {
        moveAnimeToCategory(anime.id, categories)
        if (anime.favorite) return

        screenModelScope.launchIO {
            updateAnime.await(AnimeUpdate(id = anime.id, favorite = true))
        }
    }

    private suspend fun getAnimeCategoryIds(anime: Anime): List<Long> {
        return getCategories.await(anime.id).map { it.id }
    }

    fun addFavorite(animeId: Long) {
        screenModelScope.launchIO {
            val anime = getAnime.await(animeId) ?: return@launchIO

            val duplicate = getDuplicateLibraryAnime.await(anime).firstOrNull()
            if (duplicate != null) {
                mutableState.update { it.copy(dialog = Dialog.DuplicateAnime(anime, duplicate)) }
                return@launchIO
            }

            addFavorite(anime)
        }
    }

    fun addFavorite(anime: Anime) {
        screenModelScope.launchIO {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultAnimeCategory().get().toLong()
            val defaultCategory = categories.find { it.id == defaultCategoryId }

            when {
                defaultCategory != null -> {
                    val result = updateAnime.await(AnimeUpdate(id = anime.id, favorite = true))
                    if (!result) return@launchIO
                    moveAnimeToCategory(anime.id, defaultCategory)
                }
                defaultCategoryId == 0L || categories.isEmpty() -> {
                    val result = updateAnime.await(AnimeUpdate(id = anime.id, favorite = true))
                    if (!result) return@launchIO
                    moveAnimeToCategory(anime.id, null)
                }
                else -> showChangeCategoryDialog(anime)
            }

            addTracks.bindEnhancedTrackers(anime, sourceManager.getOrStub(anime.source))
        }
    }

    fun showChangeCategoryDialog(anime: Anime) {
        screenModelScope.launch {
            val categories = getCategories()
            val selection = getAnimeCategoryIds(anime)
            val mappedCategories = categories.map { category ->
                Category(
                    id = category.id,
                    name = category.name,
                    order = category.order,
                    flags = category.flags,
                    hidden = category.hidden,
                )
            }
            mutableState.update { currentState ->
                currentState.copy(
                    dialog = Dialog.ChangeCategory(
                        anime = anime,
                        initialSelection = mappedCategories.mapAsCheckboxState { it.id in selection }.toImmutableList(),
                    ),
                )
            }
        }
    }

    @Immutable
    data class State(
        val searchQuery: String? = null,
        val list: ImmutableList<AnimeHistoryUiModel>? = null,
        val isLoading: Boolean = true,
        val dialog: Dialog? = null,
    )

    sealed interface Dialog {
        data object DeleteAll : Dialog
        data class Delete(val history: AnimeHistoryWithRelations) : Dialog
        data class DuplicateAnime(val anime: Anime, val duplicate: Anime) : Dialog
        data class Migrate(val current: Anime, val target: Anime) : Dialog
        data class ChangeCategory(
            val anime: Anime,
            val initialSelection: ImmutableList<CheckboxState<Category>>,
        ) : Dialog
    }

    fun showMigrateDialog(current: Anime, target: Anime) {
        mutableState.update { it.copy(dialog = Dialog.Migrate(current, target)) }
    }

    fun onDialogDismissed() {
        mutableState.update { it.copy(dialog = null) }
    }

    sealed interface Event {
        data class OpenEpisode(val episode: Episode?) : Event
        data object InternalError : Event
        data object HistoryCleared : Event
    }
}
