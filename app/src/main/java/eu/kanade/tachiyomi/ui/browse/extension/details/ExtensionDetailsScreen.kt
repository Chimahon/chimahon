package eu.kanade.tachiyomi.ui.browse.extension.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.ExtensionDetailsScreen
import eu.kanade.presentation.manga.components.DictionaryProfileDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.flow.collectLatest
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data class ExtensionDetailsScreen(
    private val pkgName: String,
) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val screenModel = rememberScreenModel { ExtensionDetailsScreenModel(pkgName = pkgName, context = context) }
        val state by screenModel.state.collectAsState()

        if (state.isLoading) {
            LoadingScreen()
            return
        }

        val navigator = LocalNavigator.currentOrThrow
        // KMK -->
        val source = state.extension?.sources?.getOrNull(0)
        // KMK <--

        // Tracks which source's profile dialog is open; null = none
        var sourceIdForProfileDialog by remember { mutableStateOf<Long?>(null) }

        sourceIdForProfileDialog?.let { sourceId ->
            DictionaryProfileDialog(
                profiles = screenModel.getDictionaryProfiles(),
                currentOverrideId = screenModel.getSourceProfileOverride(sourceId),
                resolvedAutoProfile = screenModel.resolveAutoProfile(sourceId),
                onDismissRequest = { sourceIdForProfileDialog = null },
                onConfirm = { profileId ->
                    screenModel.setSourceProfileOverride(sourceId, profileId)
                    sourceIdForProfileDialog = null
                },
                titleRes = MR.strings.pref_dict_profile_override_source,
            )
        }

        ExtensionDetailsScreen(
            navigateUp = navigator::pop,
            state = state,
            onClickSourcePreferences = { sourceId ->
                val novelSource = try {
                    Injekt.get<chimahon.novel.manager.NovelSourceManager>().getNovelSource(sourceId)
                } catch (_: Exception) { null }
                if (novelSource != null) {
                    navigator.push(eu.kanade.tachiyomi.ui.browse.novel.NovelSourcePreferencesScreen(sourceId))
                } else {
                    navigator.push(SourcePreferencesScreen(sourceId))
                }
            },
            // KMK -->
            onOpenWebView = if (source != null && source is HttpSource) {
                {
                    navigator.push(
                        WebViewScreen(
                            url = source.baseUrl,
                            initialTitle = source.name,
                            sourceId = source.id,
                        ),
                    )
                }
            } else {
                null
            },
            // KMK <--
            onClickEnableAll = { screenModel.toggleSources(true) },
            onClickDisableAll = { screenModel.toggleSources(false) },
            onClickClearCookies = screenModel::clearCookies,
            onClickUninstall = screenModel::uninstallExtension,
            onClickSource = screenModel::toggleSource,
            onClickIncognito = screenModel::toggleIncognito,
            onSourceDictProfileClick = { sourceId -> sourceIdForProfileDialog = sourceId },
        )

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                if (event is ExtensionDetailsEvent.Uninstalled) {
                    navigator.pop()
                }
            }
        }
    }
}
