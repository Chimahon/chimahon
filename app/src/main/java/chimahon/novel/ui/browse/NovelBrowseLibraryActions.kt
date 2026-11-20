package chimahon.novel.ui.browse

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import chimahon.novel.interactor.SetNovelCategories
import chimahon.novel.interactor.UpdateNovel
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.tachiyomi.sourcenovel.NovelSource
import eu.kanade.tachiyomi.sourcenovel.model.SNNovel
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.NovelLibraryPreferences
import tachiyomi.domain.novel.model.Novel
import tachiyomi.domain.novel.repository.NovelCategoryRepository
import tachiyomi.domain.novel.repository.NovelRepository
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

internal class NovelBrowseLibraryScreenModel(
    private val novels: NovelRepository = Injekt.get(),
    private val categories: NovelCategoryRepository = Injekt.get(),
    private val updateNovel: UpdateNovel = Injekt.get(),
    private val setCategories: SetNovelCategories = Injekt.get(),
    private val preferences: NovelLibraryPreferences = Injekt.get(),
) : StateScreenModel<NovelBrowseLibraryScreenModel.State>(State()) {

    fun onLongClick(novel: SNNovel, source: NovelSource) = runAction {
        val row = novels.getNovelByUrlAndSourceId(novel.url, source.id) ?: run {
            val id = novels.insert(Novel.fromSourceNovel(novel, source.id, source.lang.takeUnless { it == "all" }))
            novels.getNovelById(id)
        }
        if (row.favorite) {
            mutableState.update { it.copy(dialog = Dialog.Remove(row)) }
            return@runAction
        }
        val all = categories.getAll().filterNot { it.isSystemCategory }
        val defaultId = preferences.defaultCategory().get().toLong()
        val defaultCategory = all.firstOrNull { it.id == defaultId }
        when {
            defaultCategory != null -> add(row.id, listOf(defaultCategory.id))
            defaultId == 0L || all.isEmpty() -> add(row.id, emptyList())
            else -> {
                val assigned = categories.getByNovelId(row.id).map { it.id }.toSet()
                val selection = all.map { category ->
                    val item = Category(category.id, category.name, category.order.toLong(), category.flags, false)
                    if (item.id in assigned) CheckboxState.State.Checked(item) else CheckboxState.State.None(item)
                }.toImmutableList()
                mutableState.update { it.copy(dialog = Dialog.ChooseCategory(row.id, selection)) }
            }
        }
    }

    fun confirmRemove(novel: Novel) = runAction {
        dismiss()
        check(updateNovel.awaitUpdateFavorite(novel.id, false))
    }

    fun confirmAdd(novelId: Long, categoryIds: List<Long>) = runAction {
        dismiss()
        val validIds = categories.getAll().filter { !it.isSystemCategory && it.id in categoryIds }.map { it.id }
        add(novelId, validIds)
    }

    private suspend fun add(novelId: Long, categoryIds: List<Long>) {
        setCategories.await(novelId, categoryIds)
        check(updateNovel.awaitUpdateFavorite(novelId, true))
    }

    private fun runAction(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null) }
        screenModelScope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun dismiss() { mutableState.update { it.copy(dialog = null) } }
    fun clearError() { mutableState.update { it.copy(error = null) } }

    data class State(val dialog: Dialog? = null, val busy: Boolean = false, val error: String? = null)

    sealed interface Dialog {
        data class Remove(val novel: Novel) : Dialog
        data class ChooseCategory(val novelId: Long, val selection: ImmutableList<CheckboxState<Category>>) : Dialog
    }
}

@Composable
internal fun Screen.novelBrowseLibraryActions(): (SNNovel, NovelSource) -> Unit {
    val model = rememberScreenModel { NovelBrowseLibraryScreenModel() }
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(state.error) {
        state.error?.let { context.toast(it); model.clearError() }
    }
    when (val dialog = state.dialog) {
        is NovelBrowseLibraryScreenModel.Dialog.Remove -> AlertDialog(
            onDismissRequest = model::dismiss,
            title = { Text(stringResource(MR.strings.are_you_sure)) },
            text = { Text(stringResource(MR.strings.remove_manga, dialog.novel.title)) },
            confirmButton = {
                TextButton(onClick = { model.confirmRemove(dialog.novel) }) { Text(stringResource(MR.strings.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = model::dismiss) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
        is NovelBrowseLibraryScreenModel.Dialog.ChooseCategory -> ChangeCategoryDialog(
            initialSelection = dialog.selection,
            onDismissRequest = model::dismiss,
            onEditCategories = { model.dismiss(); navigator.push(CategoryScreen(CategoryScreen.Tab.NOVELS)) },
            onConfirm = { include, _ -> model.confirmAdd(dialog.novelId, include) },
        )
        null -> Unit
    }
    return { novel, source ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        model.onLongClick(novel, source)
    }
}
