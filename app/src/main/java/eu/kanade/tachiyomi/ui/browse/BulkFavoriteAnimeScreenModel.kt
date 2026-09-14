package eu.kanade.tachiyomi.ui.browse

import androidx.compose.runtime.Immutable
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastDistinctBy
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.entries.anime.interactor.UpdateAnime
import eu.kanade.domain.track.anime.interactor.AddAnimeTracks
import eu.kanade.presentation.components.BulkSelectionToolbar
import eu.kanade.tachiyomi.data.cache.AnimeCoverCache
import eu.kanade.tachiyomi.util.removeCovers
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.mapAsCheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.category.interactor.GetAnimeCategories
import tachiyomi.domain.category.interactor.SetAnimeCategories
import tachiyomi.domain.category.model.AnimeCategory
import tachiyomi.domain.entries.anime.interactor.GetDuplicateLibraryAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.episode.interactor.SetAnimeDefaultEpisodeFlags
import tachiyomi.domain.library.service.AnimeLibraryPreferences
import tachiyomi.domain.season.interactor.SetAnimeDefaultSeasonFlags
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class BulkFavoriteAnimeScreenModel(
    initialState: State = State(),
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val libraryPreferences: AnimeLibraryPreferences = Injekt.get(),
    private val getDuplicateLibraryAnime: GetDuplicateLibraryAnime = Injekt.get(),
    private val getCategories: GetAnimeCategories = Injekt.get(),
    private val setAnimeCategories: SetAnimeCategories = Injekt.get(),
    private val updateAnime: UpdateAnime = Injekt.get(),
    private val coverCache: AnimeCoverCache = Injekt.get(),
    private val setAnimeDefaultEpisodeFlags: SetAnimeDefaultEpisodeFlags = Injekt.get(),
    private val setAnimeDefaultSeasonFlags: SetAnimeDefaultSeasonFlags = Injekt.get(),
    private val addTracks: AddAnimeTracks = Injekt.get(),
) : StateScreenModel<BulkFavoriteAnimeScreenModel.State>(initialState) {

    fun backHandler() {
        toggleSelectionMode(false)
    }

    fun toggleSelectionMode(newMode: Boolean? = null) {
        mutableState.update { state ->
            val mode = newMode ?: !state.selectionMode
            state.copy(
                selectionMode = mode,
                selection = if (mode) state.selection else persistentListOf(),
            )
        }
    }

    fun select(anime: Anime) {
        toggleSelection(anime, toSelectedState = true)
    }

    /**
     * @param toSelectedState set to `true` to only Select, set to `false` to only Unselect
     */
    fun toggleSelection(anime: Anime, toSelectedState: Boolean? = null) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val isSelected = list.fastAny { it.id == anime.id }
                val shouldSelect = toSelectedState ?: !isSelected
                // Both condition to avoid adding duplicate entries
                if (shouldSelect && !isSelected) {
                    list.add(anime)
                } else if (!shouldSelect && isSelected) {
                    list.removeAll { it.id == anime.id }
                }
            }
            state.copy(
                selection = newSelection,
                selectionMode = newSelection.isNotEmpty(),
            )
        }
    }

    fun reverseSelection(animes: List<Anime>) {
        mutableState.update { state ->
            val newSelection = animes.filterNot { anime ->
                state.selection.contains(anime)
            }
                .fastDistinctBy { it.id }
                .toPersistentList()
            state.copy(
                selection = newSelection,
                selectionMode = newSelection.isNotEmpty(),
            )
        }
    }

    /**
     * Called when user click on [BulkSelectionToolbar]'s `Favorite` button.
     * It will then look for any duplicated anime.
     * - If there is any, it will show the duplicate dialogs.
     * - If not then it will call the [addFavoriteDuplicate].
     */
    fun addFavorite(startIdx: Int = 0) {
        screenModelScope.launch {
            startRunning()
            val entryWithDuplicates = findDuplicateLibraryAnime(startIdx)
            if (entryWithDuplicates != null) {
                val (index, anime, duplicates) = entryWithDuplicates
                if (state.value.selection.size == 1) {
                    // If only one anime is selected, show the multiple-duplicates dialog.
                    setDialog(Dialog.AddDuplicateAnime(anime, duplicates))
                } else {
                    setDialog(Dialog.BulkAllowDuplicate(anime, duplicates, index))
                }
            } else {
                addFavoriteDuplicate()
            }
        }
    }

    /**
     * Add anime to library if there is default category or no category exists.
     * If not, it shows the categories list.
     *
     * @param skipAllDuplicates if true, skip all duplicates and add all selected anime to library.
     * if false, allow all duplicates and add all selected anime to library
     */
    internal fun addFavoriteDuplicate(skipAllDuplicates: Boolean = false) {
        screenModelScope.launch {
            val animeList = if (skipAllDuplicates) getNotDuplicateLibraryAnimes() else state.value.selection
            if (animeList.isEmpty()) {
                stopRunning()
                toggleSelectionMode(false)
                return@launch
            }
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory().get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            when {
                // Default category set
                defaultCategory != null -> {
                    stopRunning()
                    setAnimesCategories(animeList, listOf(defaultCategory.id), emptyList())
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    stopRunning()
                    // Automatic 'Default' or no categories
                    setAnimesCategories(animeList, emptyList(), emptyList())
                }

                else -> {
                    // Get indexes of the common categories to preselect.
                    // Note: the anime category dialog only supports checked/unchecked states,
                    // so mixed categories are shown unchecked.
                    val common = getCommonCategories(animeList)
                    val preselected = categories
                        .mapAsCheckboxState { it in common }
                        .toImmutableList()
                    stopRunning()
                    setDialog(Dialog.ChangeAnimesCategory(animeList, preselected))
                }
            }
        }
    }

    private suspend fun getNotDuplicateLibraryAnimes(): List<Anime> {
        return state.value.selection.filterNot { anime ->
            getDuplicateLibraryAnime.await(anime).isNotEmpty()
        }
    }

    private suspend fun findDuplicateLibraryAnime(startIdx: Int = 0): Triple<Int, Anime, List<Anime>>? {
        val animes = state.value.selection
        animes.fastForEachIndexed { index, anime ->
            if (index < startIdx) return@fastForEachIndexed
            val duplicates = getDuplicateLibraryAnime.await(anime)
            if (duplicates.isEmpty()) return@fastForEachIndexed
            return Triple(index, anime, duplicates)
        }
        return null
    }

    internal fun removeDuplicateSelectedAnime(index: Int) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                list.removeAt(index)
            }
            state.copy(selection = newSelection)
        }
    }

    /**
     * Bulk update categories of anime using old and new common categories.
     *
     * @param animeList the list of anime to move.
     * @param addCategories the categories to add for all anime.
     * @param removeCategories the categories to remove in all anime.
     */
    internal fun setAnimesCategories(animeList: List<Anime>, addCategories: List<Long>, removeCategories: List<Long>) {
        screenModelScope.launchNonCancellable {
            startRunning()
            animeList.fastForEach { anime ->
                val categoryIds = getCategories.await(anime.id)
                    .map { it.id }
                    .subtract(removeCategories.toSet())
                    .plus(addCategories)
                    .toList()

                moveAnimeToCategoriesAndAddToLibrary(anime, categoryIds)
            }
            stopRunning()
        }
        toggleSelectionMode(false)
    }

    private fun moveAnimeToCategoriesAndAddToLibrary(anime: Anime, categories: List<Long>) {
        moveAnimeToCategory(anime.id, categories)
        if (anime.favorite) return

        screenModelScope.launchIO {
            try {
                val source = sourceManager.getOrStub(anime.source)
                setAnimeDefaultEpisodeFlags.await(anime)
                setAnimeDefaultSeasonFlags.await(anime)
                addTracks.bindEnhancedTrackers(anime, source)
                updateAnime.awaitUpdateFavorite(anime.id, true)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    private fun moveAnimeToCategory(animeId: Long, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setAnimeCategories.await(animeId, categoryIds)
        }
    }

    /**
     * Returns the common categories for the given list of anime.
     *
     * @param animes the list of anime.
     */
    private suspend fun getCommonCategories(animes: List<Anime>): Collection<AnimeCategory> {
        if (animes.isEmpty()) return emptyList()
        return animes
            .map { getCategories.await(it.id).toSet() }
            .reduce { set1, set2 -> set1.intersect(set2) }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    private suspend fun getCategories(): List<AnimeCategory> {
        return getCategories.subscribe()
            .firstOrNull()
            ?.filterNot { it.isSystemCategory }
            .orEmpty()
    }

    private fun moveAnimeToCategories(anime: Anime, vararg categories: AnimeCategory) {
        moveAnimeToCategories(anime, categories.filter { it.id != 0L }.map { it.id })
    }

    private fun moveAnimeToCategories(anime: Anime, categoryIds: List<Long>) {
        screenModelScope.launchIO {
            setAnimeCategories.await(
                animeId = anime.id,
                categoryIds = categoryIds.toList(),
            )
        }
    }

    /**
     * Adds or removes an anime from the library.
     *
     * @param anime the anime to update.
     */
    internal fun changeAnimeFavorite(anime: Anime) {
        val source = sourceManager.getOrStub(anime.source)

        screenModelScope.launch {
            if (!anime.favorite) {
                setAnimeDefaultEpisodeFlags.await(anime)
                setAnimeDefaultSeasonFlags.await(anime)
                addTracks.bindEnhancedTrackers(anime, source)
                updateAnime.awaitUpdateFavorite(anime.id, true)
            } else {
                val new = anime.removeCovers(coverCache)
                updateAnime.awaitUpdateFavorite(new.id, false)
            }
        }
    }

    internal fun addFavorite(anime: Anime) {
        screenModelScope.launch {
            val categories = getCategories()
            val defaultCategoryId = libraryPreferences.defaultCategory().get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            when {
                // Default category set
                defaultCategory != null -> {
                    moveAnimeToCategories(anime, defaultCategory)
                    changeAnimeFavorite(anime)
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    moveAnimeToCategories(anime)
                    changeAnimeFavorite(anime)
                }

                // Choose a category
                else -> {
                    val preselectedIds = getCategories.await(anime.id).map { it.id }
                    setDialog(
                        Dialog.ChangeAnimesCategory(
                            listOf(anime),
                            categories.mapAsCheckboxState { it.id in preselectedIds }.toImmutableList(),
                        ),
                    )
                }
            }
        }
    }

    fun addRemoveAnime(anime: Anime, haptic: HapticFeedback? = null) {
        screenModelScope.launchIO {
            val duplicates = getDuplicateLibraryAnime.await(anime)
            when {
                anime.favorite -> setDialog(Dialog.RemoveAnime(anime))
                duplicates.isNotEmpty() -> setDialog(
                    Dialog.AddDuplicateAnime(anime, duplicates),
                )
                else -> addFavorite(anime)
            }
            haptic?.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    internal fun showMigrateDialog(anime: Anime, duplicate: Anime) {
        setDialog(Dialog.Migrate(target = anime, current = duplicate))
    }

    private fun setDialog(dialog: Dialog?) {
        mutableState.update {
            it.copy(dialog = dialog)
        }
    }

    internal fun dismissDialog() {
        mutableState.update {
            it.copy(dialog = null)
        }
    }

    private fun startRunning() {
        mutableState.update {
            it.copy(isRunning = true)
        }
    }

    internal fun stopRunning() {
        mutableState.update {
            it.copy(isRunning = false)
        }
    }

    sealed interface Dialog {
        data class Migrate(val target: Anime, val current: Anime) : Dialog
        data class AddDuplicateAnime(val anime: Anime, val duplicates: List<Anime>) : Dialog
        data class BulkAllowDuplicate(val anime: Anime, val duplicates: List<Anime>, val currentIdx: Int) : Dialog
        data class RemoveAnime(val anime: Anime) : Dialog
        data class ChangeAnimesCategory(
            val animes: List<Anime>,
            val initialSelection: ImmutableList<CheckboxState.State<AnimeCategory>>,
        ) : Dialog
    }

    @Immutable
    data class State(
        val dialog: Dialog? = null,
        val selection: PersistentList<Anime> = persistentListOf(),
        val selectionMode: Boolean = false,
        val isRunning: Boolean = false,
    )
}
