package eu.kanade.tachiyomi.ui.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.fastMap
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import chimahon.novel.data.NovelCategory
import eu.kanade.presentation.category.components.CategoryCreateDialog
import eu.kanade.presentation.category.components.CategoryDeleteDialog
import eu.kanade.presentation.category.components.CategoryFloatingActionButton
import eu.kanade.presentation.category.components.CategoryListItem
import eu.kanade.presentation.category.components.CategoryRenameDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.collectLatest
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus

class NovelCategoryScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelCategoryScreenModel() }

        val state by screenModel.state.collectAsState()

        if (state is NovelCategoryScreenState.Loading) {
            LoadingScreen()
            return
        }

        val successState = state as NovelCategoryScreenState.Success

        val lazyListState = rememberLazyListState()
        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.label_novel_categories),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                CategoryFloatingActionButton(
                    lazyListState = lazyListState,
                    onCreate = { screenModel.showDialog(NovelCategoryDialog.Create) },
                )
            },
        ) { paddingValues ->
            if (successState.isEmpty) {
                EmptyScreen(
                    stringRes = MR.strings.information_empty_category,
                    modifier = Modifier.padding(paddingValues),
                )
            } else {
                val categoriesState = remember { successState.categories.toMutableStateList() }
                val reorderableState = rememberReorderableLazyListState(lazyListState, paddingValues) { from, to ->
                    val item = categoriesState.removeAt(from.index)
                    categoriesState.add(to.index, item)
                    screenModel.reorderCategory(item, to.index)
                }

                LaunchedEffect(successState.categories) {
                    if (!reorderableState.isAnyItemDragging) {
                        categoriesState.clear()
                        categoriesState.addAll(successState.categories)
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = lazyListState,
                    contentPadding = paddingValues +
                        topSmallPaddingValues +
                        PaddingValues(horizontal = MaterialTheme.padding.medium),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    items(
                        items = categoriesState,
                        key = { category -> "novel-category-${category.id}" },
                    ) { category ->
                        ReorderableItem(reorderableState, "novel-category-${category.id}") {
                            val categoryName = if (category.isSystemCategory) {
                                stringResource(MR.strings.label_default)
                            } else {
                                category.name
                            }

                            // Map NovelCategory to Category for UI reuse
                            val mappedCategory = Category(
                                id = if (category.isSystemCategory) 0L else category.id.hashCode().toLong(),
                                name = categoryName,
                                order = category.order.toLong(),
                                flags = category.flags,
                                hidden = category.hidden
                            )
                            CategoryListItem(
                                modifier = Modifier.animateItem(),
                                category = mappedCategory,
                                onRename = { screenModel.showDialog(NovelCategoryDialog.Rename(category)) },
                                onDelete = { screenModel.showDialog(NovelCategoryDialog.Delete(category)) },
                                onHide = { screenModel.hideCategory(category) },
                            )
                        }
                    }
                }
            }
        }

        when (val dialog = successState.dialog) {
            null -> {}
            NovelCategoryDialog.Create -> {
                CategoryCreateDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    onCreate = screenModel::createCategory,
                    categories = successState.categories.fastMap { it.name }.toImmutableList(),
                )
            }
            is NovelCategoryDialog.Rename -> {
                CategoryRenameDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    onRename = { screenModel.renameCategory(dialog.category, it) },
                    categories = successState.categories.fastMap { it.name }.toImmutableList(),
                    category = dialog.category.name,
                )
            }
            is NovelCategoryDialog.Delete -> {
                CategoryDeleteDialog(
                    onDismissRequest = screenModel::dismissDialog,
                    onDelete = { screenModel.deleteCategory(dialog.category) },
                    category = dialog.category.name,
                )
            }
        }

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                if (event is NovelCategoryEvent.LocalizedMessage) {
                    context.toast(event.stringRes)
                }
            }
        }
    }
}
