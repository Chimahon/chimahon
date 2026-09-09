package eu.kanade.tachiyomi.ui.category

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.fastMap
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import chimahon.novel.data.NovelCategory
import eu.kanade.presentation.category.CategoryContent
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
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus

class CategoryScreen(
    private val initialTab: Tab = Tab.MANGA,
) : Screen() {

    enum class Tab(val displayName: String) {
        MANGA("Manga"),
        NOVELS("Novels"),
        ANIME("Anime"),
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()

        val mangaScreenModel = rememberScreenModel { CategoryScreenModel() }
        val animeScreenModel = rememberScreenModel { AnimeCategoryScreenModel() }
        val novelScreenModel = rememberScreenModel { NovelCategoryScreenModel() }

        val mangaState by mangaScreenModel.state.collectAsState()
        val animeState by animeScreenModel.state.collectAsState()
        val novelState by novelScreenModel.state.collectAsState()

        val pagerState = rememberPagerState(initialPage = initialTab.ordinal, pageCount = { 3 })
        val fabLazyListState = rememberLazyListState()

        Scaffold(
            topBar = {
                Column {
                    AppBar(
                        title = stringResource(MR.strings.categories),
                        navigateUp = navigator::pop,
                    )
                    PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                        Tab.entries.forEachIndexed { index, tab ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                                text = { TabText(text = tab.displayName) },
                                unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                when (pagerState.currentPage) {
                    0 -> CategoryFloatingActionButton(
                        lazyListState = fabLazyListState,
                        onCreate = { mangaScreenModel.showDialog(CategoryDialog.Create) },
                    )
                    1 -> CategoryFloatingActionButton(
                        lazyListState = fabLazyListState,
                        onCreate = { novelScreenModel.showDialog(NovelCategoryDialog.Create) },
                    )
                    2 -> CategoryFloatingActionButton(
                        lazyListState = fabLazyListState,
                        onCreate = { animeScreenModel.showDialog(AnimeCategoryDialog.Create) },
                    )
                }
            },
        ) { paddingValues ->
            HorizontalPager(
                modifier = Modifier.fillMaxSize(),
                state = pagerState,
            ) { page ->
                when (page) {
                    0 -> MangaTab(
                        state = mangaState,
                        screenModel = mangaScreenModel,
                        contentPadding = paddingValues,
                    )
                    1 -> NovelTab(
                        state = novelState,
                        screenModel = novelScreenModel,
                        contentPadding = paddingValues,
                    )
                    2 -> AnimeTab(
                        state = animeState,
                        screenModel = animeScreenModel,
                        contentPadding = paddingValues,
                    )
                }
            }
        }

        LaunchedEffect(Unit) {
            mangaScreenModel.events.collectLatest { event ->
                if (event is CategoryEvent.LocalizedMessage) {
                    context.toast(event.stringRes)
                }
            }
        }
        LaunchedEffect(Unit) {
            animeScreenModel.events.collectLatest { event ->
                if (event is AnimeCategoryEvent.LocalizedMessage) {
                    context.toast(event.stringRes)
                }
            }
        }
        LaunchedEffect(Unit) {
            novelScreenModel.events.collectLatest { event ->
                if (event is NovelCategoryEvent.LocalizedMessage) {
                    context.toast(event.stringRes)
                }
            }
        }
    }
}

@Composable
private fun AnimeTab(
    state: AnimeCategoryScreenState,
    screenModel: AnimeCategoryScreenModel,
    contentPadding: PaddingValues,
) {
    if (state is AnimeCategoryScreenState.Loading) {
        LoadingScreen()
        return
    }

    val successState = state as AnimeCategoryScreenState.Success

    if (successState.isEmpty) {
        EmptyScreen(
            stringRes = MR.strings.information_empty_category,
            modifier = Modifier.padding(contentPadding),
        )
    } else {
        val lazyListState = rememberLazyListState()
        CategoryContent(
            categories = successState.categories,
            lazyListState = lazyListState,
            paddingValues = contentPadding,
            onClickRename = { screenModel.showDialog(AnimeCategoryDialog.Rename(it)) },
            onClickDelete = { screenModel.showDialog(AnimeCategoryDialog.Delete(it)) },
            onChangeOrder = screenModel::changeOrder,
            onClickHide = screenModel::hideCategory,
        )
    }

    when (val dialog = successState.dialog) {
        null -> {}
        AnimeCategoryDialog.Create -> {
            CategoryCreateDialog(
                onDismissRequest = screenModel::dismissDialog,
                onCreate = screenModel::createCategory,
                categories = successState.categories.fastMap { it.name }.toImmutableList(),
            )
        }
        is AnimeCategoryDialog.Rename -> {
            CategoryRenameDialog(
                onDismissRequest = screenModel::dismissDialog,
                onRename = { screenModel.renameCategory(dialog.category, it) },
                categories = successState.categories.fastMap { it.name }.toImmutableList(),
                category = dialog.category.name,
            )
        }
        is AnimeCategoryDialog.Delete -> {
            CategoryDeleteDialog(
                onDismissRequest = screenModel::dismissDialog,
                onDelete = { screenModel.deleteCategory(dialog.category.id) },
                category = dialog.category.name,
            )
        }
    }
}

@Composable
private fun MangaTab(
    state: CategoryScreenState,
    screenModel: CategoryScreenModel,
    contentPadding: PaddingValues,
) {
    if (state is CategoryScreenState.Loading) {
        LoadingScreen()
        return
    }

    val successState = state as CategoryScreenState.Success

    if (successState.isEmpty) {
        EmptyScreen(
            stringRes = MR.strings.information_empty_category,
            modifier = Modifier.padding(contentPadding),
        )
    } else {
        val lazyListState = rememberLazyListState()
        CategoryContent(
            categories = successState.categories,
            lazyListState = lazyListState,
            paddingValues = contentPadding,
            onClickRename = { screenModel.showDialog(CategoryDialog.Rename(it)) },
            onClickDelete = { screenModel.showDialog(CategoryDialog.Delete(it)) },
            onChangeOrder = screenModel::changeOrder,
            onClickHide = { screenModel.hideCategory(it) },
        )
    }

    when (val dialog = successState.dialog) {
        null -> {}
        CategoryDialog.Create -> {
            CategoryCreateDialog(
                onDismissRequest = screenModel::dismissDialog,
                onCreate = screenModel::createCategory,
                categories = successState.categories.fastMap { it.name }.toImmutableList(),
            )
        }
        is CategoryDialog.Rename -> {
            CategoryRenameDialog(
                onDismissRequest = screenModel::dismissDialog,
                onRename = { screenModel.renameCategory(dialog.category, it) },
                categories = successState.categories.fastMap { it.name }.toImmutableList(),
                category = dialog.category.name,
            )
        }
        is CategoryDialog.Delete -> {
            CategoryDeleteDialog(
                onDismissRequest = screenModel::dismissDialog,
                onDelete = { screenModel.deleteCategory(dialog.category.id) },
                category = dialog.category.name,
            )
        }
    }
}

@Composable
private fun NovelTab(
    state: NovelCategoryScreenState,
    screenModel: NovelCategoryScreenModel,
    contentPadding: PaddingValues,
) {
    if (state is NovelCategoryScreenState.Loading) {
        LoadingScreen()
        return
    }

    val successState = state as NovelCategoryScreenState.Success
    val lazyListState = rememberLazyListState()

    if (successState.isEmpty) {
        EmptyScreen(
            stringRes = MR.strings.information_empty_category,
            modifier = Modifier.padding(contentPadding),
        )
    } else {
        val categoriesState = remember { successState.categories.toMutableStateList() }
        val reorderableState = rememberReorderableLazyListState(lazyListState, contentPadding) { from, to ->
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
            contentPadding = contentPadding +
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

                    val mappedCategory = Category(
                        id = if (category.isSystemCategory) 0L else category.id.hashCode().toLong(),
                        name = categoryName,
                        order = category.order.toLong(),
                        flags = category.flags,
                        hidden = false,
                    )
                    CategoryListItem(
                        modifier = Modifier.animateItem(),
                        category = mappedCategory,
                        onRename = { screenModel.showDialog(NovelCategoryDialog.Rename(category)) },
                        onDelete = { screenModel.showDialog(NovelCategoryDialog.Delete(category)) },
                        onHide = { /* Not supported for novels yet */ },
                    )
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
}
