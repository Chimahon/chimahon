package eu.kanade.tachiyomi.ui.browse.novelextension

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import chimahon.novel.plugin.NovelPluginManager
import eu.kanade.domain.extension.interactor.GetExtensionLanguages.Companion.getLanguageIconID
import eu.kanade.domain.source.interactor.ToggleNovelLanguage
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NovelExtensionFilterScreenModel(
    private val preferences: SourcePreferences = Injekt.get(),
    private val pluginManager: NovelPluginManager = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val toggleLanguage: ToggleNovelLanguage = Injekt.get(),
) : StateScreenModel<NovelExtensionFilterState>(NovelExtensionFilterState.Loading) {

    init {
        screenModelScope.launch {
            combine(
                pluginManager.catalog,
                extensionManager.installedNovelExtensionsFlow,
                extensionManager.availableNovelExtensionsFlow,
                preferences.enabledNovelLanguages().changes(),
            ) { catalog, installedApk, availableApk, enabledLanguages ->
                val allLangs = (
                    catalog.available.map { it.normalizedLanguage() } +
                    catalog.installed.map { it.descriptor.normalizedLanguage() } +
                    installedApk.map { it.lang } +
                    availableApk.map { it.lang }
                ).filter { it.isNotBlank() }
                    .distinct()
                    .sortedWith(compareBy({ it != "all" }, { it }))

                NovelExtensionFilterState.Success(
                    languages = allLangs.toImmutableList(),
                    enabledLanguages = enabledLanguages.toImmutableSet(),
                )
            }.collectLatest { state ->
                mutableState.update { state }
            }
        }
    }

    fun toggle(language: String) {
        toggleLanguage.await(language)
    }
}

sealed interface NovelExtensionFilterState {

    @Immutable
    data object Loading : NovelExtensionFilterState

    @Immutable
    data class Success(
        val languages: ImmutableList<String>,
        val enabledLanguages: ImmutableSet<String>,
    ) : NovelExtensionFilterState {
        val isEmpty: Boolean
            get() = languages.isEmpty()
    }
}

class NovelExtensionFilterScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelExtensionFilterScreenModel() }
        val state by screenModel.state.collectAsState()

        if (state is NovelExtensionFilterState.Loading) {
            LoadingScreen()
            return
        }

        val successState = state as NovelExtensionFilterState.Success

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.label_extensions),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            if (successState.isEmpty) {
                EmptyScreen(
                    stringRes = MR.strings.empty_screen,
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }
            val context = LocalContext.current
            LazyColumn(
                contentPadding = contentPadding,
                modifier = Modifier.padding(start = MaterialTheme.padding.small),
            ) {
                items(successState.languages) { language ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val iconResId = getLanguageIconID(language) ?: R.drawable.globe
                        Icon(
                            painter = painterResource(id = iconResId),
                            tint = Color.Unspecified,
                            contentDescription = language,
                            modifier = Modifier
                                .width(48.dp)
                                .height(32.dp),
                        )
                        SwitchPreferenceWidget(
                            modifier = Modifier.animateItem(),
                            title = LocaleHelper.getSourceDisplayName(language, context) +
                                (
                                    " (${LocaleHelper.getDisplayName(language)})"
                                        .takeIf { language !in listOf("all", "other") } ?: ""
                                    ),
                            checked = language in successState.enabledLanguages,
                            onCheckedChanged = { screenModel.toggle(language) },
                        )
                    }
                }
            }
        }
    }
}
