package eu.kanade.tachiyomi.ui.category

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import chimahon.novel.data.NovelCategory
import dev.icerock.moko.resources.StringResource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.novel.model.NovelCategory as DbNovelCategory
import tachiyomi.domain.novel.repository.NovelCategoryRepository
import tachiyomi.domain.novel.repository.NovelRepository
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Novel categories backed by the DB repository. The UI keeps the existing
 * [NovelCategory] shape; string ids are DB ids stringified with "default"
 * for the system slot — same mapping the library already uses.
 */
class NovelCategoryScreenModel(
    private val novelCategoryRepository: NovelCategoryRepository = Injekt.get(),
    private val novelRepository: NovelRepository = Injekt.get(),
) : StateScreenModel<NovelCategoryScreenState>(NovelCategoryScreenState.Loading) {

    private val _events: Channel<NovelCategoryEvent> = Channel()
    val events = _events.receiveAsFlow()

    init {
        loadCategories()
    }

    private fun DbNovelCategory.toUiCategory(): NovelCategory {
        return NovelCategory(
            id = if (id == DbNovelCategory.SYSTEM_CATEGORY_ID) NovelCategory.UNCATEGORIZED_ID else id.toString(),
            name = name,
            order = order,
            flags = flags,
            hidden = hidden,
        )
    }

    private fun NovelCategory.toDbId(): Long? {
        return if (id == NovelCategory.UNCATEGORIZED_ID) {
            DbNovelCategory.SYSTEM_CATEGORY_ID
        } else {
            id.toLongOrNull()
        }
    }

    private fun loadCategories() {
        screenModelScope.launch {
            val categories = runCatching { novelCategoryRepository.getAll() }.getOrDefault(emptyList())
            mutableState.update {
                NovelCategoryScreenState.Success(
                    categories = categories
                        .filterNot { it.isSystemCategory }
                        .map { it.toUiCategory() }
                        .toImmutableList(),
                )
            }
        }
    }

    fun createCategory(name: String) {
        screenModelScope.launch {
            val order = runCatching { novelCategoryRepository.getAll() }.getOrDefault(emptyList())
                .maxOfOrNull { it.order }?.plus(1) ?: 0
            runCatching { novelCategoryRepository.create(name, order, false) }
            loadCategories()
        }
    }

    fun deleteCategory(category: NovelCategory) {
        if (category.isSystemCategory) return
        val dbId = category.toDbId() ?: return
        screenModelScope.launch {
            // Transfer books to default (absence of join rows, manga convention).
            runCatching {
                val novels = novelRepository.getAll()
                val categoryMap = novelCategoryRepository.getCategoryIdsByNovelIds(novels.map { it.id })
                novels.forEach { novel ->
                    val remaining = (categoryMap[novel.id].orEmpty() - dbId)
                    novelCategoryRepository.setNovelCategories(novel.id, remaining)
                }
                novelCategoryRepository.delete(dbId)
            }
            loadCategories()
        }
    }

    fun renameCategory(category: NovelCategory, name: String) {
        if (category.isSystemCategory) return
        val dbId = category.toDbId() ?: return
        screenModelScope.launch {
            runCatching {
                val current = novelCategoryRepository.getAll().firstOrNull { it.id == dbId } ?: return@launch
                novelCategoryRepository.update(current.copy(name = name))
            }
            loadCategories()
        }
    }

    fun reorderCategory(category: NovelCategory, newIndex: Int) {
        if (category.isSystemCategory) return
        screenModelScope.launch {
            val all = runCatching { novelCategoryRepository.getAll() }.getOrNull() ?: return@launch
            val userCategories = all.filterNot { it.isSystemCategory }.toMutableList()
            val oldIndex = userCategories.indexOfFirst { it.id == category.toDbId() }
            if (oldIndex != -1) {
                val item = userCategories.removeAt(oldIndex)
                userCategories.add(newIndex.coerceIn(0, userCategories.size), item)
                userCategories.forEachIndexed { index, cat ->
                    runCatching { novelCategoryRepository.update(cat.copy(order = index)) }
                }
            }
            loadCategories()
        }
    }

    fun hideCategory(category: NovelCategory) {
        if (category.isSystemCategory) return
        val dbId = category.toDbId() ?: return
        screenModelScope.launch {
            runCatching {
                val current = novelCategoryRepository.getAll().firstOrNull { it.id == dbId } ?: return@launch
                novelCategoryRepository.update(current.copy(hidden = !current.hidden))
            }
            loadCategories()
        }
    }

    fun showDialog(dialog: NovelCategoryDialog) {
        mutableState.update {
            when (it) {
                NovelCategoryScreenState.Loading -> it
                is NovelCategoryScreenState.Success -> it.copy(dialog = dialog)
            }
        }
    }

    fun dismissDialog() {
        mutableState.update {
            when (it) {
                NovelCategoryScreenState.Loading -> it
                is NovelCategoryScreenState.Success -> it.copy(dialog = null)
            }
        }
    }
}

sealed interface NovelCategoryDialog {
    data object Create : NovelCategoryDialog
    data class Rename(val category: NovelCategory) : NovelCategoryDialog
    data class Delete(val category: NovelCategory) : NovelCategoryDialog
}

sealed interface NovelCategoryEvent {
    sealed class LocalizedMessage(val stringRes: StringResource) : NovelCategoryEvent
    data object InternalError : LocalizedMessage(MR.strings.internal_error)
}

sealed interface NovelCategoryScreenState {
    @Immutable
    data object Loading : NovelCategoryScreenState

    @Immutable
    data class Success(
        val categories: ImmutableList<NovelCategory>,
        val dialog: NovelCategoryDialog? = null,
    ) : NovelCategoryScreenState {
        val isEmpty: Boolean
            get() = categories.isEmpty()
    }
}
